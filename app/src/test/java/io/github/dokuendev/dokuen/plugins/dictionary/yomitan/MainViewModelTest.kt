package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettingsStore
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(app)
        Dispatchers.setMain(testDispatcher)

        runBlocking {
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
        }
    }

    @Test
    fun testInitialStateAndLoadInstalled() = runBlocking {
        // Prepare dictionary items in database
        val dict1 = DictionaryEntity(
            title = "A Dictionary",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )
        val dict2 = DictionaryEntity(
            title = "B Dictionary",
            revision = "2.0",
            sequenced = true,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict1)
            db.dictionaryDao().insertDictionary(dict2)

            // Insert some mock terms and kanji so counts are loaded
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = "A Dictionary",
                        expression = "単語",
                        reading = "たんご",
                        definitionTags = null,
                        rules = "",
                        score = 1.0,
                        glossary = "[\"word\"]",
                        sequence = null,
                        termTags = null
                    )
                )
            )
            db.kanjiDao().bulkAdd(
                listOf(
                    KanjiEntity(
                        dictionary = "B Dictionary",
                        character = "漢",
                        onyomi = "カン",
                        kunyomi = "",
                        tags = "",
                        meanings = "[\"kanji\"]",
                        stats = null
                    )
                )
            )
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(false, uiState.isLoading)
        assertEquals(2, uiState.installedDictionaries.size)

        // Verify custom formatted stats counts
        val firstDict = uiState.installedDictionaries[0]
        assertEquals("A Dictionary", firstDict.meta.title)
        assertEquals("1 term imported", firstDict.countText)

        val secondDict = uiState.installedDictionaries[1]
        assertEquals("B Dictionary", secondDict.meta.title)
        assertEquals("1 kanji imported", secondDict.countText)

        // Verify shared preferences cache was updated
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("installed_dictionaries", "")
        assertEquals("A Dictionary,B Dictionary", cached)
    }

    @Test
    fun testMetaOnlyDictionariesReportMetaEntryCounts() = runBlocking {
        val termFreqDict = DictionaryEntity(
            title = "A Term Freq", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":1234,"freq":1234},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        val kanjiFreqDict = DictionaryEntity(
            title = "B Kanji Freq", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":0},"kanji":{"total":0},"kanjiMeta":{"total":1,"freq":1},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        val pitchDict = DictionaryEntity(
            title = "C Pitch", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":5,"pitch":5},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        val legacyMetaDict = DictionaryEntity(
            title = "D Legacy Meta", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true, counts = null
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(termFreqDict)
            db.dictionaryDao().insertDictionary(kanjiFreqDict)
            db.dictionaryDao().insertDictionary(pitchDict)
            db.dictionaryDao().insertDictionary(legacyMetaDict)
            db.termMetaDao().bulkAdd(
                listOf(
                    TermMetaEntity(
                        dictionary = "D Legacy Meta",
                        expression = "単語",
                        mode = "freq",
                        data = "1"
                    )
                )
            )
        }

        val viewModel = MainViewModel(app)
        waitForLoaded(viewModel)

        val countTexts = viewModel.uiState.value.installedDictionaries.associate { it.meta.title to it.countText }
        assertEquals("1,234 frequency entries imported", countTexts["A Term Freq"])
        assertEquals("1 frequency entry imported", countTexts["B Kanji Freq"])
        assertEquals("5 metadata entries imported", countTexts["C Pitch"])
        assertEquals("1 metadata entry imported", countTexts["D Legacy Meta"])
    }

    @Test
    fun testDeleteDictionary() = runBlocking {
        val dict = DictionaryEntity(
            title = "ToDelete Dict",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict)
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = "ToDelete Dict",
                        expression = "単語",
                        reading = "たんご",
                        definitionTags = null,
                        rules = "",
                        score = 1.0,
                        glossary = "[\"word\"]",
                        sequence = null,
                        termTags = null
                    )
                )
            )
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(1, uiState.installedDictionaries.size)

        viewModel.deleteDictionary(dict)

        var uiStateDel = viewModel.uiState.value
        var attemptsDel = 0
        while ((uiStateDel.isLoading || uiStateDel.progressState != null) && attemptsDel < 100) {
            delay(50.milliseconds)
            uiStateDel = viewModel.uiState.value
            attemptsDel++
        }

        // Verify database is cleared and installed dictionaries list is empty
        assertEquals(0, viewModel.uiState.value.installedDictionaries.size)
        withContext(Dispatchers.IO) {
            val terms = db.termDao().findByExpression("単語", listOf("ToDelete Dict"))
            assertEquals(0, terms.size)
        }

        // Verify SharedPreferences is cleared
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("installed_dictionaries", "")
        assertEquals("", cached)
        val activeCached = prefs.getString("active_dictionaries", "")
        assertEquals("", activeCached)
    }

    @Test
    fun testToggleDictionaryActive() = runBlocking {
        val dict = DictionaryEntity(
            title = "Toggle Dict",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict)
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        // By default, since it is the only one installed, it is set as active
        assertEquals(true, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))

        // Toggle it off
        viewModel.toggleDictionaryActive("Toggle Dict")
        assertEquals(false, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))

        // Toggle it back on
        viewModel.toggleDictionaryActive("Toggle Dict")
        assertEquals(true, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))
    }

    @Test
    fun testClearError() = runBlocking {
        val viewModel = MainViewModel(app)

        assertNull(viewModel.uiState.value.errorDialogState)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorDialogState)
    }

    @Test
    fun testDictionaryReorderingAndAppending() = runBlocking {
        val dict1 = DictionaryEntity(
            title = "Dict A", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )
        val dict2 = DictionaryEntity(
            title = "Dict B", revision = "2.0", sequenced = true, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict1)
            db.dictionaryDao().insertDictionary(dict2)
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(2, uiState.installedDictionaries.size)
        // Default alphabetical order: Dict A, Dict B
        assertEquals("Dict A", uiState.installedDictionaries[0].meta.title)
        assertEquals("Dict B", uiState.installedDictionaries[1].meta.title)

        // 1. Move Dict A (fromIndex 0) to index 1 (swapping with Dict B)
        viewModel.moveDictionary(0, 1)

        val uiStateReordered = viewModel.uiState.value
        assertEquals("Dict B", uiStateReordered.installedDictionaries[0].meta.title)
        assertEquals("Dict A", uiStateReordered.installedDictionaries[1].meta.title)

        // Verify shared preferences cache was updated with the custom order
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        assertEquals("Dict B,Dict A", prefs.getString("installed_dictionaries", ""))

        // Both are active by default
        assertEquals(true, uiStateReordered.activeDictionaries.contains("Dict A"))
        assertEquals(true, uiStateReordered.activeDictionaries.contains("Dict B"))

        // Toggle Dict A off so it's not active
        viewModel.toggleDictionaryActive("Dict A")
        assertEquals(false, viewModel.uiState.value.activeDictionaries.contains("Dict A"))

        // 2. Install a new dictionary: Dict C
        val dict3 = DictionaryEntity(
            title = "Dict C", revision = "3.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict3)
        }

        // 3. Reload dictionaries
        viewModel.loadInstalledDictionaries()

        var uiStateReloaded = viewModel.uiState.value
        var reloadAttempts = 0
        while (uiStateReloaded.isLoading && reloadAttempts < 100) {
            delay(50.milliseconds)
            uiStateReloaded = viewModel.uiState.value
            reloadAttempts++
        }

        // Verify that Dict C is appended at the bottom, after custom ordered Dict B and Dict A
        assertEquals(3, uiStateReloaded.installedDictionaries.size)
        assertEquals("Dict B", uiStateReloaded.installedDictionaries[0].meta.title)
        assertEquals("Dict A", uiStateReloaded.installedDictionaries[1].meta.title)
        assertEquals("Dict C", uiStateReloaded.installedDictionaries[2].meta.title) // Bottom!

        // Verify that Dict C is active by default, and Dict A remains toggled off (inactive)
        assertEquals(true, uiStateReloaded.activeDictionaries.contains("Dict B"))
        assertEquals(false, uiStateReloaded.activeDictionaries.contains("Dict A"))
        assertEquals(true, uiStateReloaded.activeDictionaries.contains("Dict C")) // Active by default!
    }

    private suspend fun waitForLoaded(viewModel: MainViewModel) {
        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }
    }

    @Test
    fun testFrequencyDictionaryOptionsOnlyIncludesDictionariesWithFrequencyData() = runBlocking {
        val termDict = DictionaryEntity(
            title = "Term Dict", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":100},"termMeta":{"total":0},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        val freqDict = DictionaryEntity(
            title = "Freq Dict", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":10,"freq":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(termDict)
            db.dictionaryDao().insertDictionary(freqDict)
        }

        val viewModel = MainViewModel(app)
        waitForLoaded(viewModel)

        assertEquals(listOf("Freq Dict"), viewModel.uiState.value.frequencyDictionaryOptions)
    }

    @Test
    fun testSetSortFrequencyDictionaryToNoneClearsPreference() = runBlocking {
        val freqDict = DictionaryEntity(
            title = "Freq Dict", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":10,"freq":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(freqDict)
        }

        val viewModel = MainViewModel(app)
        waitForLoaded(viewModel)

        viewModel.setSortFrequencyDictionary("Freq Dict")
        assertEquals("Freq Dict", viewModel.uiState.value.sortFrequencyDictionary)

        viewModel.setSortFrequencyDictionary("")
        assertEquals("", viewModel.uiState.value.sortFrequencyDictionary)

        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        assertEquals("", prefs.getString("sort_frequency_dictionary", ""))
    }

    @Test
    fun testSetSortFrequencyDictionaryAutoDetectsOccurrenceBasedOrder() = runBlocking {
        val freqDict = DictionaryEntity(
            title = "Occurrence Dict", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":10,"freq":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(freqDict)
            // Common words get LARGE raw values, rare words get SMALL raw values ->
            // occurrence-based data (larger = more common) -> descending.
            db.termMetaDao().bulkAdd(
                listOf(
                    TermMetaEntity(dictionary = "Occurrence Dict", expression = "来る", mode = "freq", data = "50000"),
                    TermMetaEntity(dictionary = "Occurrence Dict", expression = "言う", mode = "freq", data = "48000"),
                    TermMetaEntity(dictionary = "Occurrence Dict", expression = "猫", mode = "freq", data = "3"),
                    TermMetaEntity(dictionary = "Occurrence Dict", expression = "犬", mode = "freq", data = "2")
                )
            )
        }

        val viewModel = MainViewModel(app)
        waitForLoaded(viewModel)

        viewModel.setSortFrequencyDictionary("Occurrence Dict")

        // Poll the actual pref write (starts unset), not the uiState field - its default
        // value ("descending") would otherwise make this loop exit before detection runs.
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        var detected = prefs.getString("sort_frequency_dictionary_order", "")
        var attempts = 0
        while (detected.isNullOrEmpty() && attempts < 100) {
            delay(50.milliseconds)
            detected = prefs.getString("sort_frequency_dictionary_order", "")
            attempts++
        }

        assertEquals("descending", detected)
        assertEquals("descending", viewModel.uiState.value.sortFrequencyDictionaryOrder)
    }

    @Test
    fun testSetSortFrequencyDictionaryAutoDetectsRankBasedOrder() = runBlocking {
        val freqDict = DictionaryEntity(
            title = "Rank Dict", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", importSuccess = true,
            counts = """{"terms":{"total":0},"termMeta":{"total":10,"freq":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(freqDict)
            // Common words get SMALL raw values (better ranks), rare words get LARGE raw
            // values -> rank-based data (smaller = more common) -> ascending.
            db.termMetaDao().bulkAdd(
                listOf(
                    TermMetaEntity(dictionary = "Rank Dict", expression = "来る", mode = "freq", data = "5"),
                    TermMetaEntity(dictionary = "Rank Dict", expression = "言う", mode = "freq", data = "3"),
                    TermMetaEntity(dictionary = "Rank Dict", expression = "猫", mode = "freq", data = "48000"),
                    TermMetaEntity(dictionary = "Rank Dict", expression = "犬", mode = "freq", data = "50000")
                )
            )
        }

        val viewModel = MainViewModel(app)
        waitForLoaded(viewModel)

        viewModel.setSortFrequencyDictionary("Rank Dict")

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.sortFrequencyDictionaryOrder != "ascending" && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals("ascending", uiState.sortFrequencyDictionaryOrder)
    }

    @Test
    fun testSetAnkiEnabledPersistsAndUpdatesState() {
        val viewModel = MainViewModel(app)

        viewModel.setAnkiEnabled(true)

        assertEquals(true, viewModel.uiState.value.ankiSettings.enabled)
        val reloaded = AnkiSettingsStore.load(app)
        assertEquals(true, reloaded.enabled)
    }

    /**
     * A view model whose stored Anki settings have no card formats, for tests of the format
     * list itself (a fresh install starts with the original's three default formats).
     */
    private fun viewModelWithNoCardFormats(): MainViewModel {
        AnkiSettingsStore.save(
            app,
            io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettings()
        )
        return MainViewModel(app)
    }

    @Test
    fun testFreshInstallStartsWithTheOriginalsDefaultCardFormats() {
        val viewModel = MainViewModel(app)

        assertEquals(
            listOf("Expression", "Reading", "Kanji"),
            viewModel.uiState.value.ankiSettings.cardFormats.map { it.name }
        )
        // A new format is numbered after the existing ones, as in the original.
        viewModel.addDefaultAnkiCardFormat()
        assertEquals("Format 4", viewModel.uiState.value.ankiSettings.cardFormats.last().name)
    }

    @Test
    fun testUpdateAnkiCardFormatTransformAppliesToTheStoredFormat() {
        val viewModel = viewModelWithNoCardFormats()
        viewModel.addAnkiCardFormat(ankiFormat("Vocab", modelId = 7L))
        // A concurrent change (such as a field fetch) lands after the editor last read the format...
        viewModel.applyFetchedAnkiFields(0, 7L, listOf("Front"))

        // ...and a later edit expressed as a transform keeps it.
        viewModel.updateAnkiCardFormat(0) { it.copy(name = "Renamed") }

        val format = viewModel.uiState.value.ankiSettings.cardFormats[0]
        assertEquals("Renamed", format.name)
        assertEquals(listOf("Front"), format.fields.keys.toList())
    }

    @Test
    fun testAddAndRemoveAnkiCardFormat() {
        val viewModel = viewModelWithNoCardFormats()
        val format = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat(
            name = "Vocab",
            type = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType.TERM,
            deckId = 1L,
            deckName = "Default",
            modelId = 2L,
            modelName = "Basic",
            fields = emptyMap()
        )

        viewModel.addAnkiCardFormat(format)
        assertEquals(1, viewModel.uiState.value.ankiSettings.cardFormats.size)

        viewModel.removeAnkiCardFormat(0)
        assertEquals(0, viewModel.uiState.value.ankiSettings.cardFormats.size)
    }

    @Test
    fun testSetAnkiDuplicateScopeCheckAllModelsPersistsAndUpdatesState() {
        val viewModel = MainViewModel(app)

        viewModel.setAnkiDuplicateScopeCheckAllModels(true)

        assertEquals(true, viewModel.uiState.value.ankiSettings.duplicateScopeCheckAllModels)
        val reloaded = AnkiSettingsStore.load(app)
        assertEquals(true, reloaded.duplicateScopeCheckAllModels)
    }

    @Test
    fun testAddDefaultAnkiCardFormatAddsUpToFiveThenRejects() {
        val viewModel = viewModelWithNoCardFormats()

        repeat(5) {
            assertEquals(true, viewModel.addDefaultAnkiCardFormat())
        }
        assertEquals(5, viewModel.uiState.value.ankiSettings.cardFormats.size)

        assertEquals(false, viewModel.addDefaultAnkiCardFormat())
        assertEquals(5, viewModel.uiState.value.ankiSettings.cardFormats.size)
    }

    @Test
    fun testAddDefaultAnkiCardFormatUsesIncrementingDefaultNames() {
        val viewModel = viewModelWithNoCardFormats()

        viewModel.addDefaultAnkiCardFormat()
        viewModel.addDefaultAnkiCardFormat()

        val names = viewModel.uiState.value.ankiSettings.cardFormats.map { it.name }
        assertEquals(listOf("Format 1", "Format 2"), names)
    }

    @Test
    fun testUpdateAnkiCardFormatReplacesAtIndexAndIgnoresOutOfBoundsIndex() {
        val viewModel = viewModelWithNoCardFormats()
        val original = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat(
            name = "Vocab",
            type = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType.TERM,
            deckId = 1L,
            deckName = "Default",
            modelId = 2L,
            modelName = "Basic",
            fields = emptyMap()
        )
        viewModel.addAnkiCardFormat(original)

        viewModel.updateAnkiCardFormat(0) { it.copy(name = "Vocab (renamed)") }
        assertEquals(listOf("Vocab (renamed)"), viewModel.uiState.value.ankiSettings.cardFormats.map { it.name })
        val reloaded = AnkiSettingsStore.load(app)
        assertEquals(listOf("Vocab (renamed)"), reloaded.cardFormats.map { it.name })

        // Out-of-bounds index must be a no-op, not a crash or a silent list-growing append.
        viewModel.updateAnkiCardFormat(5) { it.copy(name = "Should Not Apply") }
        assertEquals(listOf("Vocab (renamed)"), viewModel.uiState.value.ankiSettings.cardFormats.map { it.name })
    }

    private fun ankiFormat(
        name: String,
        modelId: Long,
        fields: Map<String, io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField> = emptyMap(),
        type: io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType =
            io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType.TERM
    ) = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat(
        name = name,
        type = type,
        deckId = 1L,
        deckName = "Default",
        modelId = modelId,
        modelName = "Model $modelId",
        fields = fields
    )

    @Test
    fun testApplyFetchedAnkiFieldsUsesLatestFormatStateNotStaleSnapshot() {
        val viewModel = viewModelWithNoCardFormats()
        viewModel.addAnkiCardFormat(ankiFormat("Vocab", modelId = 7L))
        // An edit made while the field fetch is in flight...
        viewModel.updateAnkiCardFormat(0) { it.copy(name = "Vocab renamed") }

        viewModel.applyFetchedAnkiFields(0, 7L, listOf("Front", "Reading", "Meaning"))

        val format = viewModel.uiState.value.ankiSettings.cardFormats[0]
        assertEquals("Vocab renamed", format.name)
        assertEquals(listOf("Front", "Reading", "Meaning"), format.fields.keys.toList())
        assertEquals("{expression}", format.fields["Front"]?.value)
        assertEquals("{reading}", format.fields["Reading"]?.value)
        assertEquals("{glossary}", format.fields["Meaning"]?.value)
    }

    @Test
    fun testApplyFetchedAnkiFieldsIgnoresFormatWhoseModelChangedOrShifted() {
        val viewModel = viewModelWithNoCardFormats()
        viewModel.addAnkiCardFormat(ankiFormat("A", modelId = 7L))
        viewModel.addAnkiCardFormat(ankiFormat("B", modelId = 8L))
        // Format A is deleted while its fetch is in flight; B shifts into index 0.
        viewModel.removeAnkiCardFormat(0)

        viewModel.applyFetchedAnkiFields(0, 7L, listOf("Front"))

        val remaining = viewModel.uiState.value.ankiSettings.cardFormats
        assertEquals(listOf("B"), remaining.map { it.name })
        assertEquals(emptyMap<String, Any>(), remaining[0].fields)
    }

    @Test
    fun testApplyFetchedAnkiFieldsKeepsExistingFieldsAndDropsRemovedOnes() {
        val viewModel = viewModelWithNoCardFormats()
        val existing = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField(
            value = "{furigana}",
            overwriteMode = io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldOverwriteMode.APPEND
        )
        viewModel.addAnkiCardFormat(
            ankiFormat(
                "Vocab",
                modelId = 7L,
                fields = mapOf(
                    "Reading" to existing,
                    "Old" to io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField("{tags}")
                )
            )
        )

        viewModel.applyFetchedAnkiFields(0, 7L, listOf("Front", "Reading"))

        val fields = viewModel.uiState.value.ankiSettings.cardFormats[0].fields
        assertEquals(listOf("Front", "Reading"), fields.keys.toList())
        assertEquals(existing, fields["Reading"])
        val reloaded = AnkiSettingsStore.load(app)
        assertEquals(fields, reloaded.cardFormats[0].fields)
    }

    @Test
    fun testSyncAnkiCardFormatFieldsSurvivesAnkiDroidException() {
        val viewModel = MainViewModel(app)
        viewModel.addAnkiCardFormat(ankiFormat("Vocab", modelId = 7L))
        val client =
            org.mockito.kotlin.mock<io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient>()
        org.mockito.kotlin.whenever(client.getFieldList(7L)).thenThrow(SecurityException("revoked"))
        viewModel.ankiDroidClient = client

        val done = java.util.concurrent.CountDownLatch(1)
        viewModel.syncAnkiCardFormatFields(0, 7L) { done.countDown() }

        assertEquals(true, done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(emptyMap<String, Any>(), viewModel.uiState.value.ankiSettings.cardFormats[0].fields)
    }

    @Test
    fun testRefreshAnkiDeckAndModelOptionsSurvivesAnkiDroidException() {
        val viewModel = MainViewModel(app)
        val client =
            org.mockito.kotlin.mock<io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient>()
        org.mockito.kotlin.whenever(client.getDeckList()).thenThrow(IllegalStateException("provider crashed"))
        org.mockito.kotlin.whenever(client.getModelList()).thenReturn(mapOf(1L to "Basic"))
        viewModel.ankiDroidClient = client

        viewModel.refreshAnkiDeckAndModelOptions()

        val deadline = System.currentTimeMillis() + 5000
        while (viewModel.uiState.value.ankiModelOptions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(emptyMap<Long, String>(), viewModel.uiState.value.ankiDeckOptions)
        assertEquals(mapOf(1L to "Basic"), viewModel.uiState.value.ankiModelOptions)
    }

    @Test
    fun testImageSettingSettersPersistAndUpdateState() {
        val viewModel = MainViewModel(app)

        viewModel.setAnkiScreenshotBehavior(AnkiScreenshotBehavior.CROP)
        viewModel.setAnkiScreenshotFormat(AnkiScreenshotFormat.JPEG)
        viewModel.setAnkiScreenshotQuality(55)

        val state = viewModel.uiState.value.ankiSettings
        assertEquals(AnkiScreenshotBehavior.CROP, state.screenshotBehavior)
        assertEquals(AnkiScreenshotFormat.JPEG, state.screenshotFormat)
        assertEquals(55, state.screenshotQuality)
        val reloaded = AnkiSettingsStore.load(app)
        assertEquals(AnkiScreenshotBehavior.CROP, reloaded.screenshotBehavior)
        assertEquals(AnkiScreenshotFormat.JPEG, reloaded.screenshotFormat)
        assertEquals(55, reloaded.screenshotQuality)
    }

    @Test
    fun testScreenshotQualityIsClamped() {
        val viewModel = MainViewModel(app)

        viewModel.setAnkiScreenshotQuality(150)
        assertEquals(100, viewModel.uiState.value.ankiSettings.screenshotQuality)
        viewModel.setAnkiScreenshotQuality(-5)
        assertEquals(0, viewModel.uiState.value.ankiSettings.screenshotQuality)
    }
}
