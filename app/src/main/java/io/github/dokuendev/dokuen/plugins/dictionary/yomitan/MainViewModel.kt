package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateScope
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldDefaults
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettings
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettingsStore
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.AndroidMediaLoader
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.DictionaryImporter
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.ImportDetails
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.FrequencyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ProgressState(
    val stage: String,
    val progress: Int,
    val total: Int = 0,
    val details: String = "",
    val isImporting: Boolean = false
)

data class InstalledDictionaryInfo(
    val meta: DictionaryEntity,
    val countText: String
)

data class ErrorDialogState(
    val title: String,
    val message: String
)

data class MainUiState(
    val installedDictionaries: List<InstalledDictionaryInfo> = emptyList(),
    val activeDictionaries: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val progressState: ProgressState? = null,
    val errorDialogState: ErrorDialogState? = null,
    /** Installed dictionaries that actually contain frequency data, for the sort-dictionary picker. */
    val frequencyDictionaryOptions: List<String> = emptyList(),
    /** Empty string means "None" (no frequency-based sorting), matching yomitan's own default. */
    val sortFrequencyDictionary: String = "",
    val sortFrequencyDictionaryOrder: String = "descending",
    val ankiSettings: AnkiSettings = AnkiSettings(),
    val ankiDroidInstalled: Boolean = false,
    val ankiDroidPermissionGranted: Boolean = false,
    val ankiDeckOptions: Map<Long, String> = emptyMap(),
    val ankiModelOptions: Map<Long, String> = emptyMap()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val PRESETS_JITENDEX =
            "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip"
        const val PRESETS_JMNEDICT =
            "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip"
        const val PRESETS_KANJIDIC =
            "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip"
        const val PRESETS_BCCWJ =
            "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/BCCWJ_SUW_LUW_combined.zip"
        const val PRESETS_JPDB =
            "https://github.com/Kuuuube/yomitan-dictionaries/releases/download/yomitan-permalink/JPDB_v2.2_Frequency_Kana.zip"
        const val PRESETS_JITEN =
            "https://api.jiten.moe/api/frequency-list/download?downloadType=yomitan"
        const val MAX_ANKI_CARD_FORMATS = 5
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var ankiDroidClientInstance: AnkiDroidClient? = null

    /** Lazily created; tests may replace it with a fake before triggering Anki calls. */
    internal var ankiDroidClient: AnkiDroidClient
        get() = ankiDroidClientInstance ?: AnkiDroidClient(getApplication()).also { ankiDroidClientInstance = it }
        set(value) {
            ankiDroidClientInstance = value
        }

    init {
        loadInstalledDictionaries()
        loadAnkiSettings()
        refreshAnkiStatus()
    }

    fun loadInstalledDictionaries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val db = AppDatabase.getDatabase(getApplication())
                val dao = db.dictionaryDao()

                val metas = withContext(Dispatchers.IO) { dao.getAllDictionaries() }

                val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
                val installedJoined = prefs.getString("installed_dictionaries", "") ?: ""
                val savedOrder = installedJoined.split(",").filter { it.isNotEmpty() }

                // 1. Reorder installed dictionaries in UI, appending new ones to bottom
                val sortedMetas = metas.sortedWith(compareBy<DictionaryEntity> { meta ->
                    val idx = savedOrder.indexOf(meta.title)
                    if (idx != -1) idx else Int.MAX_VALUE
                }.thenBy { it.title.lowercase() })

                val results = mutableListOf<InstalledDictionaryInfo>()
                val titleList = mutableListOf<String>()

                withContext(Dispatchers.IO) {
                    for (meta in sortedMetas) {
                        var termCount = 0L
                        var kanjiCount = 0L
                        var metaCount = 0L
                        var metaFreqCount = 0L
                        var countsLoaded = false

                        val countsJson = meta.counts
                        if (!countsJson.isNullOrEmpty()) {
                            try {
                                val json = Json.parseToJsonElement(countsJson).jsonObject
                                fun count(section: String, key: String) =
                                    json[section]?.jsonObject?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
                                termCount = count("terms", "total")
                                kanjiCount = count("kanji", "total")
                                metaCount = count("termMeta", "total") + count("kanjiMeta", "total")
                                metaFreqCount = count("termMeta", "freq") + count("kanjiMeta", "freq")
                                countsLoaded = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing cached counts for ${meta.title}", e)
                            }
                        }

                        if (!countsLoaded) {
                            termCount = dao.countTerms(meta.title)
                            kanjiCount = dao.countKanji(meta.title)
                            // The meta count only shows when there are no terms or kanji, so
                            // the two table scans are skipped otherwise.
                            // Without cached counts the per-mode breakdown is unknown, so
                            // metaFreqCount stays 0 and the generic wording is used.
                            if (termCount == 0L && kanjiCount == 0L) {
                                metaCount = dao.countTermMeta(meta.title) + dao.countKanjiMeta(meta.title)
                            }
                        }

                        // Frequency, pitch accent, and similar dictionaries ship only
                        // term_meta/kanji_meta banks, so they have no terms or kanji to report.
                        val countText = if (termCount == 0L && kanjiCount == 0L && metaCount > 0L) {
                            getApplication<Application>().resources.getQuantityString(
                                if (metaFreqCount == metaCount) {
                                    R.plurals.frequency_entries_imported
                                } else {
                                    R.plurals.meta_entries_imported
                                },
                                metaCount.toInt(),
                                metaCount
                            )
                        } else if (termCount == 0L && kanjiCount > 0L) {
                            getApplication<Application>().resources.getQuantityString(
                                R.plurals.kanji_imported,
                                kanjiCount.toInt(),
                                kanjiCount
                            )
                        } else if (termCount > 0L && kanjiCount > 0L) {
                            getApplication<Application>().resources.getQuantityString(
                                R.plurals.terms_kanji_imported,
                                termCount.toInt(),
                                termCount,
                                kanjiCount
                            )
                        } else {
                            getApplication<Application>().resources.getQuantityString(
                                R.plurals.terms_imported,
                                termCount.toInt(),
                                termCount
                            )
                        }
                        results.add(InstalledDictionaryInfo(meta, countText))
                        titleList.add(meta.title)
                    }
                }

                updateSharedPreferencesCache(titleList)

                val activeJoined = prefs.getString("active_dictionaries", "") ?: ""
                val activeList =
                    activeJoined.split(",").filter { it.isNotEmpty() && titleList.contains(it) }.toMutableList()

                // 2. Newly installed dictionaries active by default
                val newlyInstalled = titleList.filter { !savedOrder.contains(it) }
                for (newDict in newlyInstalled) {
                    if (!activeList.contains(newDict)) {
                        activeList.add(newDict)
                    }
                }

                var activeSet = activeList.toSet()

                if (activeSet.isEmpty() && titleList.isNotEmpty()) {
                    activeSet = setOf(titleList.first())
                    prefs.edit { putString("active_dictionaries", titleList.first()) }
                } else {
                    val newJoined = activeList.joinToString(",")
                    prefs.edit { putString("active_dictionaries", newJoined) }
                }

                val freqOptions = results
                    .filter { FrequencyUtil.hasFrequencyData(it.meta.counts) }
                    .map { it.meta.title }

                var sortFreqDict = prefs.getString("sort_frequency_dictionary", "") ?: ""
                if (sortFreqDict.isNotEmpty() && sortFreqDict !in freqOptions) {
                    // The chosen dictionary was deleted or no longer has frequency data.
                    sortFreqDict = ""
                    prefs.edit { putString("sort_frequency_dictionary", "") }
                }
                val sortFreqOrder = prefs.getString("sort_frequency_dictionary_order", "descending") ?: "descending"

                _uiState.update {
                    it.copy(
                        installedDictionaries = results,
                        activeDictionaries = activeSet,
                        isLoading = false,
                        frequencyDictionaryOptions = freqOptions,
                        sortFrequencyDictionary = sortFreqDict,
                        sortFrequencyDictionaryOrder = sortFreqOrder
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading installed dictionaries", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorDialogState = ErrorDialogState(
                            title = getApplication<Application>().getString(R.string.error_load_failed_title),
                            message = e.message
                                ?: getApplication<Application>().getString(R.string.error_load_failed_message)
                        )
                    )
                }
            }
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        val currentList = _uiState.value.installedDictionaries.toMutableList()
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices) return

        val item = currentList.removeAt(fromIndex)
        currentList.add(toIndex, item)

        val installedTitles = currentList.map { it.meta.title }
        updateSharedPreferencesCache(installedTitles)

        val activeSet = _uiState.value.activeDictionaries
        val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val orderedActive = installedTitles.filter { activeSet.contains(it) }
        prefs.edit { putString("active_dictionaries", orderedActive.joinToString(",")) }

        _uiState.update {
            it.copy(
                installedDictionaries = currentList,
                activeDictionaries = orderedActive.toSet()
            )
        }
    }

    fun toggleDictionaryActive(title: String) {
        val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val currentActive = _uiState.value.activeDictionaries.toMutableSet()
        if (currentActive.contains(title)) {
            currentActive.remove(title)
        } else {
            currentActive.add(title)
        }
        val joined = currentActive.joinToString(",")
        prefs.edit { putString("active_dictionaries", joined) }
        _uiState.update { it.copy(activeDictionaries = currentActive) }
    }

    /**
     * Set (or clear, with an empty string) the "Frequency sorting dictionary" - the single
     * dictionary term lookups are sorted by, matching yomitan's own
     * general.sortFrequencyDictionary setting. Selecting a dictionary immediately re-runs
     * yomitan's auto-detect heuristic to guess the sort direction (see
     * [detectAndApplySortOrder]), the same as picking a dictionary in yomitan's settings page
     * does automatically.
     */
    fun setSortFrequencyDictionary(title: String) {
        val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("sort_frequency_dictionary", title) }
        _uiState.update { it.copy(sortFrequencyDictionary = title) }
        if (title.isNotEmpty()) {
            detectAndApplySortOrder(title)
        }
    }

    /** Manually override the sort direction (bypassing/replacing the auto-detected value). */
    fun setSortFrequencyDictionaryOrder(order: String) {
        val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("sort_frequency_dictionary_order", order) }
        _uiState.update { it.copy(sortFrequencyDictionaryOrder = order) }
    }

    /**
     * Re-run yomitan's auto-detect heuristic for [dictionary] and apply the result if it
     * gives a clear signal.
     *
     * Mirrors ext/js/pages/settings/sort-frequency-dictionary-controller.js's
     * `_autoUpdateOrder`/`_getFrequencyOrder` exactly: look up a handful of known-common and
     * known-rare Japanese words in this one dictionary, and infer occurrence-based
     * (descending) vs rank-based (ascending) from whether the common words' raw values are
     * larger or smaller than the rare words'. A sign of 0 (ambiguous / no data) leaves the
     * current order untouched, exactly as yomitan does.
     */
    fun detectAndApplySortOrder(dictionary: String) {
        viewModelScope.launch {
            val sign = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getDatabase(getApplication()).termMetaDao()
                val allTerms = FrequencyUtil.MORE_COMMON_JAPANESE_TERMS + FrequencyUtil.LESS_COMMON_JAPANESE_TERMS
                val metas = dao.findByExpressionBulk(allTerms, listOf(dictionary)).filter { it.mode == "freq" }
                val valuesByTerm = metas.groupBy({ it.expression }) { meta ->
                    FrequencyUtil.parseFrequency(meta.data, meta.expression).frequency
                }
                val moreCommonValues = FrequencyUtil.MORE_COMMON_JAPANESE_TERMS.map { valuesByTerm[it] ?: emptyList() }
                val lessCommonValues = FrequencyUtil.LESS_COMMON_JAPANESE_TERMS.map { valuesByTerm[it] ?: emptyList() }
                FrequencyUtil.detectFrequencyOrderSign(moreCommonValues, lessCommonValues)
            }
            if (sign == 0) return@launch
            setSortFrequencyDictionaryOrder(if (sign > 0) "descending" else "ascending")
        }
    }

    fun deleteDictionary(meta: DictionaryEntity) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progressState = ProgressState(
                        stage = getApplication<Application>().getString(R.string.progress_deleting_dictionary),
                        progress = 0,
                        isImporting = false
                    )
                )
            }
            try {
                val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
                val activeJoined = prefs.getString("active_dictionaries", "") ?: ""
                val activeList = activeJoined.split(",").filter { it.isNotEmpty() && it != meta.title }
                prefs.edit { putString("active_dictionaries", activeList.joinToString(",")) }

                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(getApplication()).dictionaryDeletionDao().deleteDictionary(meta.title)
                }
                // Set isLoading = true atomically with clearing progressState so there is no
                // window where both appear idle before the reload finishes. Without this, the
                // test polling loop (isLoading || progressState != null) exits prematurely and
                // observes the stale list before loadInstalledDictionaries() completes.
                _uiState.update { it.copy(progressState = null, isLoading = true) }
                loadInstalledDictionaries()
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting dictionary", e)
                _uiState.update {
                    it.copy(
                        progressState = null,
                        errorDialogState = ErrorDialogState(
                            title = getApplication<Application>().getString(R.string.error_delete_failed_title),
                            message = e.message
                                ?: getApplication<Application>().getString(R.string.error_delete_failed_message)
                        )
                    )
                }
            }
        }
    }

    fun importLocalZip(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    progressState = ProgressState(
                        stage = getApplication<Application>().getString(R.string.progress_importing_local_zip),
                        progress = 0,
                        isImporting = true
                    )
                )
            }
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _uiState.update {
                        it.copy(
                            progressState = null,
                            errorDialogState = ErrorDialogState(
                                title = getApplication<Application>().getString(R.string.error_file_error_title),
                                message = getApplication<Application>().getString(R.string.error_file_error_message)
                            )
                        )
                    }
                    return@launch
                }

                val db = AppDatabase.getDatabase(getApplication())
                val mediaLoader = AndroidMediaLoader()
                val importer = DictionaryImporter(getApplication(), db, mediaLoader) { current, total ->
                    _uiState.update {
                        it.copy(
                            progressState = ProgressState(
                                stage = getApplication<Application>().getString(R.string.progress_importing_dictionary),
                                progress = current,
                                total = total,
                                details = if (total > 0) {
                                    getApplication<Application>().getString(
                                        R.string.progress_details_ratio,
                                        current,
                                        total
                                    )
                                } else {
                                    getApplication<Application>().resources.getQuantityString(
                                        R.plurals.progress_details_processed,
                                        current,
                                        current
                                    )
                                },
                                isImporting = true
                            )
                        )
                    }
                }
                val result = importer.importDictionary(inputStream, ImportDetails())
                if (result.errors.isNotEmpty()) {
                    throw result.errors.first()
                }

                _uiState.update { it.copy(progressState = null) }
                loadInstalledDictionaries()

            } catch (e: Exception) {
                Log.e(TAG, "Error importing local ZIP file", e)
                _uiState.update {
                    it.copy(
                        progressState = null,
                        errorDialogState = ErrorDialogState(
                            title = getApplication<Application>().getString(R.string.error_import_failed_title),
                            message = e.message
                                ?: getApplication<Application>().getString(R.string.error_import_failed_message)
                        )
                    )
                }
            }
        }
    }

    fun downloadAndInstallPreset(urlString: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        progressState = ProgressState(
                            stage = getApplication<Application>().getString(R.string.progress_starting_download),
                            progress = 0,
                            total = 100,
                            isImporting = true
                        )
                    )
                }
            }

            var tempFile: File? = null
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 20000
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw Exception("Server returned HTTP error ${connection.responseCode}")
                }

                val contentLength = connection.contentLength
                val inputStream = connection.inputStream

                tempFile = File.createTempFile("yomitan_download_", ".zip", getApplication<Application>().cacheDir)
                val outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(1024 * 16)
                var bytesRead: Int
                var downloadedBytes = 0

                while (true) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val progress = if (contentLength > 0) {
                        ((downloadedBytes.toFloat() / contentLength.toFloat()) * 100).toInt()
                    } else {
                        0
                    }

                    val details = if (contentLength > 0) {
                        getApplication<Application>().getString(
                            R.string.progress_details_download_ratio,
                            downloadedBytes.toFloat() / 1024 / 1024,
                            contentLength.toFloat() / 1024 / 1024
                        )
                    } else {
                        getApplication<Application>().getString(
                            R.string.progress_details_download_single,
                            downloadedBytes.toFloat() / 1024 / 1024
                        )
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                progressState = ProgressState(
                                    stage = getApplication<Application>().getString(
                                        R.string.progress_downloading,
                                        name
                                    ),
                                    progress = progress,
                                    total = 100,
                                    details = details,
                                    isImporting = true
                                )
                            )
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            progressState = ProgressState(
                                stage = getApplication<Application>().getString(R.string.progress_extracting_importing),
                                progress = 0,
                                isImporting = true
                            )
                        )
                    }
                }

                val fileInputStream = FileInputStream(tempFile)
                val db = AppDatabase.getDatabase(getApplication())
                val mediaLoader = AndroidMediaLoader()
                val importer = DictionaryImporter(getApplication(), db, mediaLoader) { current, total ->
                    _uiState.update {
                        it.copy(
                            progressState = ProgressState(
                                stage = getApplication<Application>().getString(R.string.progress_importing_dictionary),
                                progress = current,
                                total = total,
                                details = if (total > 0) {
                                    getApplication<Application>().getString(
                                        R.string.progress_details_ratio,
                                        current,
                                        total
                                    )
                                } else {
                                    getApplication<Application>().resources.getQuantityString(
                                        R.plurals.progress_details_processed,
                                        current,
                                        current
                                    )
                                },
                                isImporting = true
                            )
                        )
                    }
                }
                val result = importer.importDictionary(fileInputStream, ImportDetails())
                if (result.errors.isNotEmpty()) {
                    throw result.errors.first()
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(progressState = null) }
                    loadInstalledDictionaries()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading preset dictionary", e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            progressState = null,
                            errorDialogState = ErrorDialogState(
                                title = getApplication<Application>().getString(R.string.error_installation_failed_title),
                                message = e.message
                                    ?: getApplication<Application>().getString(R.string.error_installation_failed_message)
                            )
                        )
                    }
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorDialogState = null) }
    }

    private fun updateSharedPreferencesCache(titles: List<String>) {
        val joined = titles.joinToString(",")
        val prefs = getApplication<Application>().getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("installed_dictionaries", joined) }
        Log.d(TAG, "Cached installed dictionaries: $joined")
    }

    fun refreshAnkiStatus() {
        viewModelScope.launch {
            val installed = ankiCallOrDefault("isAnkiDroidInstalled", false) { ankiDroidClient.isAnkiDroidInstalled() }
            val granted = ankiCallOrDefault("hasPermission", false) { ankiDroidClient.hasPermission() }
            _uiState.update { it.copy(ankiDroidInstalled = installed, ankiDroidPermissionGranted = granted) }
            if (installed && granted) refreshAnkiDeckAndModelOptions()
        }
    }

    fun refreshAnkiDeckAndModelOptions() {
        viewModelScope.launch {
            val decks = ankiCallOrDefault("getDeckList", emptyMap()) { ankiDroidClient.getDeckList() }
            val models = ankiCallOrDefault("getModelList", emptyMap()) { ankiDroidClient.getModelList() }
            _uiState.update { it.copy(ankiDeckOptions = decks, ankiModelOptions = models) }
        }
    }

    /**
     * Fetches the field names of [modelId] from AnkiDroid and merges them into the card
     * format at [index] via [applyFetchedAnkiFields]. [onDone] runs once the fetch finishes,
     * whether or not anything was applied.
     */
    fun syncAnkiCardFormatFields(index: Int, modelId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val fields = ankiCallOrDefault("getFieldList", emptyList()) { ankiDroidClient.getFieldList(modelId) }
            if (fields.isNotEmpty()) applyFetchedAnkiFields(index, modelId, fields)
            onDone()
        }
    }

    /**
     * Rebuilds the fields of the card format at [index] for [fieldNames], reading the
     * format's current state at the time the fetch completes (not a snapshot from when it
     * started), so edits made in the meantime are kept. Does nothing if that format no
     * longer uses [modelId] (its model was changed again, or the format was deleted and
     * another one shifted into its slot). Existing same-named fields are kept as-is; new
     * ones get the original Yomitan's default values.
     */
    internal fun applyFetchedAnkiFields(index: Int, modelId: Long, fieldNames: List<String>) {
        val current = _uiState.value.ankiSettings.cardFormats.getOrNull(index) ?: return
        if (current.modelId != modelId) return
        val merged = AnkiFieldDefaults.buildFields(fieldNames, current.type, current.fields)
        if (merged == current.fields && merged.keys.toList() == current.fields.keys.toList()) return
        updateAnkiCardFormat(index) { it.copy(fields = merged) }
    }

    private suspend fun <T> ankiCallOrDefault(name: String, default: T, block: () -> T): T {
        return withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                // AnkiDroid's ContentProvider can throw (permission revoked, collection not
                // available, provider crash); show the degraded state instead of crashing.
                Log.w(TAG, "AnkiDroid call $name failed", e)
                default
            }
        }
    }

    fun setAnkiEnabled(enabled: Boolean) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(enabled = enabled))
    }

    fun setAnkiTags(tags: List<String>) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(tags = tags))
    }

    fun setAnkiCheckForDuplicates(check: Boolean) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(checkForDuplicates = check))
    }

    fun setAnkiDuplicateScope(scope: AnkiDuplicateScope) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(duplicateScope = scope))
    }

    fun setAnkiDuplicateScopeCheckAllModels(checkAllModels: Boolean) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(duplicateScopeCheckAllModels = checkAllModels))
    }

    fun setAnkiDuplicateBehavior(behavior: AnkiDuplicateBehavior) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(duplicateBehavior = behavior))
    }

    fun setAnkiScreenshotBehavior(behavior: AnkiScreenshotBehavior) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(screenshotBehavior = behavior))
    }

    fun setAnkiScreenshotFormat(format: AnkiScreenshotFormat) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(screenshotFormat = format))
    }

    fun setAnkiScreenshotQuality(quality: Int) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(screenshotQuality = quality.coerceIn(0, 100)))
    }

    fun addAnkiCardFormat(format: AnkiCardFormat) {
        persistAnkiSettings(_uiState.value.ankiSettings.copy(cardFormats = _uiState.value.ankiSettings.cardFormats + format))
    }

    /**
     * Applies [transform] to the card format at [index] as currently stored, so an edit never
     * writes back a copy captured before a concurrent change (such as a field list fetched from
     * AnkiDroid). Does nothing if [index] is out of range.
     */
    fun updateAnkiCardFormat(index: Int, transform: (AnkiCardFormat) -> AnkiCardFormat) {
        val updatedList = _uiState.value.ankiSettings.cardFormats.toMutableList()
        if (index in updatedList.indices) {
            updatedList[index] = transform(updatedList[index])
            persistAnkiSettings(_uiState.value.ankiSettings.copy(cardFormats = updatedList))
        }
    }

    fun removeAnkiCardFormat(index: Int) {
        val updatedList = _uiState.value.ankiSettings.cardFormats.toMutableList()
        if (index in updatedList.indices) {
            updatedList.removeAt(index)
            persistAnkiSettings(_uiState.value.ankiSettings.copy(cardFormats = updatedList))
        }
    }

    /**
     * Creates a new card format with placeholder deck/model (id 0, matching the tab-strip
     * editor's "add first, fill in afterward" flow; see appendTermAnkiLinksIfConfigured's
     * completeness filter in YomitanDictionaryPluginService.kt, which keeps an unconfigured
     * format from rendering a broken Add link). Returns false without adding if the format
     * count is already at the original's 5-format cap.
     */
    fun addDefaultAnkiCardFormat(): Boolean {
        val current = _uiState.value.ankiSettings.cardFormats
        if (current.size >= MAX_ANKI_CARD_FORMATS) return false
        addAnkiCardFormat(
            AnkiCardFormat(
                name = getApplication<Application>().getString(
                    R.string.anki_card_format_default_name,
                    current.size + 1
                ),
                type = AnkiEntryType.TERM,
                deckId = 0L,
                deckName = "",
                modelId = 0L,
                modelName = "",
                fields = emptyMap()
            )
        )
        return true
    }

    private fun persistAnkiSettings(settings: AnkiSettings) {
        AnkiSettingsStore.save(getApplication(), settings)
        _uiState.update { it.copy(ankiSettings = settings) }
    }

    private fun loadAnkiSettings() {
        val loaded = AnkiSettingsStore.load(getApplication())
        _uiState.update { it.copy(ankiSettings = loaded) }
    }
}
