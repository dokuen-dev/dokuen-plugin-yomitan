package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardIcon
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldMerger
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldOverwriteMode
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiMediaFileNames
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiMediaNeeds
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiNoteData
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiNoteDataBuilder
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiNoteInfo
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSentenceExtractor
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSentenceFurigana
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettings
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettingsStore
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiTemplateEngine
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.JapaneseDeinflector
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.CssProcessor
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.YomitanRenderer
import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.CustomActionContext
import io.github.dokuendev.dokuenreader.dictionary.CustomActionResult
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.DictionaryErrorCode
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.dictionary.DictionaryPluginService
import io.github.dokuendev.dokuenreader.dictionary.DictionaryResult
import io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestSource
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText
import io.github.dokuendev.dokuenreader.plugin.core.InitResult
import io.github.dokuendev.dokuenreader.plugin.core.InitResultFactory
import io.github.dokuendev.dokuenreader.plugin.core.PluginCapabilityKeys
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import io.github.dokuendev.dokuenreader.plugin.core.SdkVersion
import java.util.concurrent.atomic.AtomicLong

// A format created by the tab-strip editor's "+" starts with deckId/modelId 0 (no deck/model
// chosen yet). This must be used consistently everywhere card formats are filtered down to a
// list whose positional indices get encoded into "format=$index" action links - otherwise the
// index encoded at render time (against one filtered list) can be resolved against a
// differently-filtered list at execute time, silently routing a tap to the wrong format.
private fun AnkiCardFormat.isComplete(): Boolean = deckId != 0L && modelId != 0L

/**
 * Identifies one entry's action links: [token] names the lookup, [entry] the linked entry
 * within it. Encoded in each link as `action:anki_add?token=<token>&entry=<entry>&format=<i>`.
 */
private data class AnkiLinkRef(val token: String, val entry: Int)

private fun ankiAddLinkParams(link: String?): Map<String, String>? {
    val payload = link?.removePrefix("action:") ?: return null
    if (!payload.startsWith("anki_add?")) return null
    return payload.removePrefix("anki_add?").split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
    }.toMap()
}

private fun parseAnkiAddLink(link: String?): AnkiLinkRef? {
    val params = ankiAddLinkParams(link) ?: return null
    val token = params["token"]?.ifEmpty { null } ?: return null
    val entry = params["entry"]?.toIntOrNull() ?: return null
    return AnkiLinkRef(token, entry)
}

/** The `format=` index of an action link, or null if it is not a well-formed one. */
private fun parseAnkiAddLinkFormat(link: String?): Int? = ankiAddLinkParams(link)?.get("format")?.toIntOrNull()

/** The note a tap on a format's link last wrote: a new one, or an existing duplicate it overwrote. */
private enum class AnkiNoteWrite { ADDED, OVERWRITTEN }

/**
 * What a tap on a format's link would do, given the duplicates found for it, shown by the link's
 * color. The colors are what the original's action icons render as (ext/css/display.css and the
 * icon SVGs in ext/images): every icon is filled with green #6FB558, which the add-duplicate and
 * overwrite variants pass through an SVG color filter (computed in sRGB, as the SVGs specify),
 * and a disabled button is shown in grayscale.
 */
private enum class AnkiLinkStatus(val color: Int) {
    /** No duplicate, or not checked yet: add a note. The plain icons (big-circle.svg etc.). */
    CAN_ADD(0xFF6FB558.toInt()),

    /**
     * A duplicate exists and "Allow adding" adds another note (or "Allow overwriting" finds none
     * of the format's own note type to overwrite). #6FB558 through add-duplicate-*.svg's
     * "green-to-purple" filter (hue rotated by 180 degrees).
     */
    ADD_DUPLICATE(0xFFD08AE7.toInt()),

    /**
     * A duplicate of the format's own note type exists and "Allow overwriting" overwrites it.
     * #6FB558 through overwrite-*.svg's "green-to-orange" filter (hue rotated by 280 degrees,
     * then saturated by 2).
     */
    OVERWRITE(0xFFFF8727.toInt()),

    /**
     * A duplicate exists and "Prevent adding" blocks the add, for which the original disables its
     * button: #6FB558 through `button.action-button:disabled`'s grayscale(100%). That rule also
     * sets 25% opacity, which is left out here: it fits a small icon with a tooltip, but would
     * make a text label hard to read.
     */
    PREVENTED(0xFF9F9F9F.toInt());

    companion object {
        /**
         * The status of a format whose duplicate search found notes ([hasDuplicate]), some of
         * them of the format's own note type ([hasSameTypeDuplicate]), decided the way the tap
         * path decides what to do with them.
         */
        fun of(behavior: AnkiDuplicateBehavior, hasDuplicate: Boolean, hasSameTypeDuplicate: Boolean): AnkiLinkStatus =
            when {
                !hasDuplicate -> CAN_ADD
                behavior == AnkiDuplicateBehavior.PREVENT -> PREVENTED
                behavior == AnkiDuplicateBehavior.OVERWRITE && hasSameTypeDuplicate -> OVERWRITE
                else -> ADD_DUPLICATE
            }
    }
}

class YomitanDictionaryPluginService : DictionaryPluginService() {

    companion object {
        private const val TAG = "YomitanService"

        /** How many lookups keep their action links tappable. */
        private const val ANKI_CACHE_CAPACITY = 10

        /** Prefix of the kanji links TermEntryRenderer puts on a term's headword kanji. */
        private const val KANJI_LINK_PREFIX = "lookup:?kanji="

        // The size of the Anki links relative to the dictionary text (InlineStyle.fontSize is a
        // scale factor): they are secondary to the entry, so they are drawn a little smaller.
        private const val ANKI_LINK_FONT_SIZE = 0.75f
    }

    override val configActivityName = ".MainActivity"

    override fun isConfigured(): Boolean {
        val prefs = applicationContext.getSharedPreferences("yomitan_prefs", MODE_PRIVATE)
        val activeJoined = prefs.getString("active_dictionaries", "") ?: ""
        return activeJoined.split(",").any { it.isNotEmpty() }
    }

    private var activeDictionaryTitles: List<String> = emptyList()
    private var sortFrequencyDictionary: String = ""
    private var sortFrequencyDictionaryOrder: String = "descending"
    private var isDarkMode: Boolean = false

    /** The language of the text Dokuen reads (PluginHostConfigKeys.LANGUAGE). */
    @Volatile
    private var sessionLanguage = "ja"

    /**
     * Whether Dokuen shows an UpdateResult's message. The SDK passes it on only to hosts that
     * implement SDK 0.7.0 or newer (PluginHostConfigKeys.HOST_SDK_VERSION) and drops it for older
     * ones.
     */
    @Volatile
    private var hostShowsUpdateMessages = false
    private lateinit var db: AppDatabase
    private lateinit var renderer: YomitanRenderer
    private val deinflector = JapaneseDeinflector()
    private var ankiDroidClientInstance: AnkiDroidClient? = null

    /** Created on first use; tests replace it with a fake before triggering Anki calls. */
    internal var ankiDroidClient: AnkiDroidClient
        get() = ankiDroidClientInstance
            ?: AnkiDroidClient(applicationContext).also { ankiDroidClientInstance = it }
        set(value) {
            ankiDroidClientInstance = value
        }

    /**
     * Reloaded from the settings store at the start of every lookup: the plugin session is
     * initialized once, so edits made in the settings screen mid-session would otherwise
     * never be seen.
     */
    @Volatile
    private var ankiSettings: AnkiSettings = AnkiSettings()

    /**
     * Everything an action link needs when the user taps it, captured when the link was
     * rendered: the exact ordered list of card formats the link's `format=` index refers to,
     * the settings in force at that time, and a recipe for the note data. Snapshotting these
     * keeps a tap resolving to the format the user saw even if the settings changed, or newer
     * lookups ran, in between.
     *
     * The note data (tag and frequency queries, glossary conversion, cloze) is not computed
     * when the link is rendered, because most rendered links are never tapped and lookups are
     * the hot path. [buildNoteData] holds only the cheap inputs captured at lookup time and is
     * run on the first [noteData] call; the result is then reused.
     */
    private class AnkiLinkContext(
        val formats: List<AnkiCardFormat>,
        val settings: AnkiSettings,
        private val buildNoteData: suspend () -> AnkiNoteData
    ) {
        @Volatile
        private var cachedNoteData: AnkiNoteData? = null

        /**
         * What the last successful tap on each format's link (keyed by its index in [formats])
         * wrote, which its label shows. Guarded by the lock of the [AnkiLookup] holding this.
         */
        val writes = HashMap<Int, AnkiNoteWrite>()

        /**
         * The status of each format's link (keyed like [writes]) as last found on a tap, which
         * its color shows. A format missing here has not been checked and shows [AnkiLinkStatus.CAN_ADD].
         * Guarded like [writes].
         */
        val statuses = HashMap<Int, AnkiLinkStatus>()

        suspend fun noteData(): AnkiNoteData =
            cachedNoteData ?: buildNoteData().also { cachedNoteData = it }

        /** Replaces the reused note data, for what a tap adds to it (the sentence's furigana). */
        fun updateNoteData(data: AnkiNoteData) {
            cachedNoteData = data
        }
    }

    /**
     * The scanned match a sentence is taken from: UTF-16 indices [matchStart, matchEnd) of
     * [contextText]. The sentence itself is extracted only when a note needs it.
     */
    private class AnkiSentenceSource(val contextText: String, val matchStart: Int, val matchEnd: Int) {
        val matchedText: String get() = contextText.substring(matchStart, matchEnd)

        fun extract(): AnkiSentenceExtractor.Sentence =
            AnkiSentenceExtractor.extract(contextText, matchStart, matchEnd)

        companion object {
            fun of(contextText: String, matchStart: Int, matchEnd: Int): AnkiSentenceSource? =
                if (matchStart in 0..matchEnd && matchEnd <= contextText.length) {
                    AnkiSentenceSource(contextText, matchStart, matchEnd)
                } else {
                    null
                }
        }
    }

    /**
     * What a kanji lookup reached through a term's headword link carries over from the term's
     * lookup, as the original's kanji links do (display.js, _onKanjiLookup and
     * _createSearchParams): the match the term's sentence comes from, if it was scanned, and
     * the lookup's query.
     */
    private class AnkiKanjiLinkOrigin(val sentenceSource: AnkiSentenceSource?, val searchQuery: String)

    /**
     * Collects the link contexts of one lookup while its entries are built. Each lookup gets
     * its own [token], and each linked entry an index into [contexts]; the whole set is
     * registered once the lookup has produced its result. [kanjiLinkOrigins] holds what the
     * lookup's kanji headword links refer to (see withKanjiLinkOrigins).
     */
    private class AnkiLookupLinks(val token: String) {
        val contexts = mutableListOf<AnkiLinkContext>()
        val kanjiLinkOrigins = mutableListOf<AnkiKanjiLinkOrigin>()
    }

    /**
     * One lookup's action links: its rendered [entries] (rewritten when a tap relabels a link,
     * since the host replaces its whole result with an UpdateResult), one [AnkiLinkContext] per
     * linked entry, and what its kanji headword links refer to. Lock the lookup to
     * read or change [entries] or its contexts' writes and statuses.
     */
    private class AnkiLookup(
        var entries: List<DictionaryEntry>,
        val contexts: List<AnkiLinkContext>,
        val kanjiLinkOrigins: List<AnkiKanjiLinkOrigin>
    )

    /**
     * The newest lookups only, evicted a whole lookup at a time so every link of a retained
     * lookup stays valid however many entries it has. The host keeps a back/forward history of
     * earlier results without re-querying the plugin, so links from older lookups must stay
     * tappable, but the cache must not grow without bound. Lookups can also run concurrently,
     * hence the synchronized map and atomic counter.
     */
    @Volatile
    internal var ankiCacheCapacity: Int = ANKI_CACHE_CAPACITY
    private val ankiLookups: MutableMap<String, AnkiLookup> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, AnkiLookup>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnkiLookup>?): Boolean =
                size > ankiCacheCapacity
        }
    )
    private val ankiTokenCounter = AtomicLong()

    /**
     * Starts every lookup token this instance issues. A link can outlive the instance that
     * rendered it (for example if the plugin process is restarted while the host still shows
     * an earlier result), and a new instance restarts [ankiTokenCounter]. Without a prefix
     * unique to the instance, such a link could name a newer lookup of the new instance and
     * export the wrong entry instead of failing as a stale link.
     */
    private val ankiTokenPrefix: String = java.util.UUID.randomUUID().toString().substringBefore('-')

    override val capabilities = Bundle().apply {
        putBoolean(PluginCapabilityKeys.HANDLES_SEGMENTATION, true)
        putBoolean(PluginCapabilityKeys.REQUIRES_DICTIONARY_FORM, false)
        putStringArray(PluginCapabilityKeys.SUPPORTED_SOURCE_LANGUAGES, arrayOf("ja"))
        putStringArray(
            PluginCapabilityKeys.SUPPORTED_TARGET_LANGUAGES,
            arrayOf("en", "es", "fr", "de", "it", "zh-CN", "ko")
        )
    }

    override suspend fun onInitialize(config: Bundle?): InitResult {
        try {
            db = AppDatabase.getDatabase(applicationContext)

            val themeString = config?.getString(PluginHostConfigKeys.UI_THEME) ?: "light"
            isDarkMode = (themeString == "dark")
            // Dokuen may leave the key out for a plugin with a single source language, which is ja.
            sessionLanguage = config?.getString(PluginHostConfigKeys.LANGUAGE) ?: "ja"
            hostShowsUpdateMessages = SdkVersion.parse(config?.getString(PluginHostConfigKeys.HOST_SDK_VERSION))
                ?.let { it >= SdkVersion(0, 7, 0) } == true

            val cssProcessor = CssProcessor()
            renderer = YomitanRenderer(db, cssProcessor)

            val prefs = applicationContext.getSharedPreferences("yomitan_prefs", MODE_PRIVATE)
            val joined = prefs.getString("active_dictionaries", "") ?: ""
            activeDictionaryTitles = joined.split(",").filter { it.isNotEmpty() }
            sortFrequencyDictionary = prefs.getString("sort_frequency_dictionary", "") ?: ""
            sortFrequencyDictionaryOrder =
                prefs.getString("sort_frequency_dictionary_order", "descending") ?: "descending"
            ankiSettings = AnkiSettingsStore.load(applicationContext)

            Log.d(
                TAG,
                "Initialization complete. Active Dictionary Titles: $activeDictionaryTitles (theme: $themeString)"
            )
            return InitResultFactory.success()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Yomitan service", e)
            return InitResultFactory.failure(getString(R.string.error_init_failed, e.message))
        }
    }

    override suspend fun onLookup(
        contextText: String,
        cursorStartIndex: Int,
        cursorEndIndex: Int
    ): DictionaryResult {
        try {
            if (activeDictionaryTitles.isEmpty()) {
                throw DictionaryException(
                    DictionaryErrorCode.WORD_NOT_FOUND,
                    getString(R.string.error_no_dictionaries_enabled)
                )
            }

            ankiSettings = AnkiSettingsStore.load(applicationContext)

            val start = maxOf(0, minOf(cursorStartIndex, contextText.length))
            val maxLen = minOf(contextText.length - start, 12)
            if (maxLen <= 0 && !contextText.startsWith("?")) {
                throw DictionaryException(DictionaryErrorCode.INVALID_QUERY, getString(R.string.error_query_empty))
            }

            val dao = db.dictionaryDao()
            val termCount = activeDictionaryTitles.sumOf { dao.countTerms(it) }
            val kanjiCount = activeDictionaryTitles.sumOf { dao.countKanji(it) }

            Log.d(
                TAG,
                "onLookup: contextText='$contextText', cursorStartIndex=$cursorStartIndex, cursorEndIndex=$cursorEndIndex, " +
                        "start=$start, maxLen=$maxLen, activeDictionaryTitles=$activeDictionaryTitles, " +
                        "totalTermsInDb=$termCount, totalKanjiInDb=$kanjiCount"
            )

            val ankiLinks = AnkiLookupLinks("$ankiTokenPrefix-${ankiTokenCounter.getAndIncrement()}")
            var entries = if (contextText.startsWith("?")) {
                lookupInternalLink(contextText, activeDictionaryTitles, ankiLinks)
            } else {
                val minLen = maxOf(1, cursorEndIndex - start)
                scanAndDeinflectLookup(contextText, start, maxLen, minLen, activeDictionaryTitles, ankiLinks)
            }

            if (entries.isEmpty()) {
                val kanjiChar = if (contextText.startsWith("?")) {
                    parseQueryParams(contextText)["query"]?.takeIf { it.length == 1 }
                } else if (cursorEndIndex - start == 1) {
                    contextText.getOrNull(start)?.toString()
                } else {
                    null
                }

                if (kanjiChar != null) {
                    entries = renderKanjiEntries(
                        kanjiChar,
                        activeDictionaryTitles,
                        ankiLinks,
                        sentenceSource = if (!contextText.startsWith("?")) {
                            AnkiSentenceSource.of(contextText, start, start + kanjiChar.length)
                        } else {
                            null
                        },
                        searchQuery = kanjiChar
                    )
                }
            }

            Log.d(TAG, "onLookup completed. Found ${entries.size} entry/entries.")
            if (entries.isEmpty()) {
                val queriedWord = if (cursorEndIndex > cursorStartIndex) {
                    contextText.substring(cursorStartIndex, cursorEndIndex)
                } else {
                    contextText.substring(start, minOf(start + 1, contextText.length))
                }
                Log.d(TAG, "onLookup failed to find any definitions for queriedWord='$queriedWord'")
                throw DictionaryException(
                    DictionaryErrorCode.WORD_NOT_FOUND,
                    getString(R.string.error_no_definitions_found, queriedWord)
                )
            }

            registerAnkiLookup(ankiLinks, entries)
            return DictionaryResult(entries = entries.toTypedArray())
        } catch (e: DictionaryException) {
            Log.d(TAG, "onLookup aborted with DictionaryException: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "onLookup encountered an unexpected exception", e)
            throw e
        }
    }

    private data class TermLookupMatch(
        val term: io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity,
        val matchedLength: Int,
        val deinflectionRulesCount: Int,
        val dictionaryIndex: Int,
        /** The conjugations undone to reach [term], innermost first. */
        val deinflectionRules: List<InflectionRule>
    )

    private class TermGroup(
        val expression: String,
        val reading: String,
        val items: MutableList<TermLookupMatch> = mutableListOf(),
        // Matches yomitan/translator.js's TermDictionaryEntry.frequencyOrder: defaults to 0
        // (a no-op sort key) and is only ever populated when the user has explicitly chosen
        // a frequency sorting dictionary in settings (see populateSortFrequencyOrder).
        var frequencyOrder: Double = 0.0
    ) {
        val maxMatchedLength: Int get() = items.maxOf { it.matchedLength }
        val minDeinflectionRulesCount: Int get() = items.minOf { it.deinflectionRulesCount }
        val minDictionaryIndex: Int get() = items.minOf { it.dictionaryIndex }
        val maxScore: Double get() = items.maxOf { it.term.score }
        val maxSequence: Long get() = items.maxOf { it.term.sequence ?: -1L }
    }

    /**
     * Populate each group's [TermGroup.frequencyOrder] using the single dictionary the user
     * chose in the "Frequency sorting dictionary" setting, if any.
     *
     * Mirrors yomitan/ext/js/language/translator.js's findTerms + _updateSortFrequencies:
     * frequency-based sorting is opt-in (skipped entirely when no dictionary is chosen, in
     * which case every group keeps frequencyOrder = 0 and this key is a no-op in the sort
     * comparator) and, when enabled, uses only that one dictionary's raw values - never a
     * blend across every active frequency dictionary.
     */
    private suspend fun populateSortFrequencyOrder(groups: Collection<TermGroup>) {
        if (groups.isEmpty() || sortFrequencyDictionary.isEmpty()) return

        val expressions = groups.map { it.expression }.distinct()
        val metas = try {
            db.termMetaDao().findByExpressionBulk(expressions, listOf(sortFrequencyDictionary))
        } catch (e: Exception) {
            emptyList()
        }
        val freqMetas = metas.filter { it.mode == "freq" }
        val ascending = sortFrequencyDictionaryOrder == "ascending"

        for (group in groups) {
            val rawFrequencies = freqMetas
                .filter { it.expression == group.expression }
                .map {
                    io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.RawFrequency(
                        dictionary = it.dictionary,
                        jsonData = it.data,
                        expression = group.expression
                    )
                }
            group.frequencyOrder =
                io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.FrequencyUtil.calculateSortFrequencyOrder(
                    rawFrequencies,
                    group.reading,
                    sortFrequencyDictionary,
                    ascending
                )
        }
    }

    private fun mergeDictionaryEntries(
        headword: String,
        pronunciation: Array<RubySpan>?,
        headwordSpans: Array<HeadwordSpan>?,
        entriesToMerge: List<DictionaryEntry>
    ): DictionaryEntry {
        if (entriesToMerge.isEmpty()) return DictionaryEntry("", null, null, StyledText("", emptyArray(), emptyArray()))
        if (entriesToMerge.size == 1) return entriesToMerge.first()

        val textBuilder = java.lang.StringBuilder()
        val styledSpans = mutableListOf<StyledSpan>()
        val rubySpans = mutableListOf<RubySpan>()
        val blockSpans = mutableListOf<BlockSpan>()

        for ((index, entry) in entriesToMerge.withIndex()) {
            val startOffset = textBuilder.length
            textBuilder.append(entry.body.text)

            entry.body.styledSpans?.forEach { span ->
                styledSpans.add(StyledSpan(span.startIndex + startOffset, span.endIndex + startOffset, span.style))
            }
            entry.body.rubySpans?.forEach { span ->
                rubySpans.add(RubySpan(span.startIndex + startOffset, span.endIndex + startOffset, span.rubyText))
            }
            entry.body.blockSpans?.forEach { span ->
                blockSpans.add(
                    BlockSpan(
                        span.startIndex + startOffset,
                        span.endIndex + startOffset,
                        span.blockType,
                        span.indentLevel,
                        span.listMarker,
                        span.backgroundColor
                    )
                )
            }

            if (index < entriesToMerge.size - 1) {
                if (!textBuilder.endsWith("\n")) {
                    textBuilder.append("\n")
                }
                textBuilder.append("\n")
            }
        }

        val blockSpansArray = if (blockSpans.isEmpty()) null else blockSpans.toTypedArray()

        val firstFlags = entriesToMerge.first().displayFlags
        val mergedDisplayFlags = if (entriesToMerge.all { it.displayFlags == firstFlags }) firstFlags else 0

        return DictionaryEntry(
            headword = headword,
            pronunciation = pronunciation,
            headwordSpans = headwordSpans,
            body = StyledText(
                text = textBuilder.toString(),
                styledSpans = styledSpans.toTypedArray(),
                rubySpans = rubySpans.toTypedArray(),
                blockSpans = blockSpansArray
            ),
            displayFlags = mergedDisplayFlags
        )
    }

    /**
     * Stores one lookup's link contexts together with its entries, so tapping a link updates
     * the entries of the lookup that produced it (not whichever lookup ran last).
     */
    private fun registerAnkiLookup(links: AnkiLookupLinks, entries: List<DictionaryEntry>) {
        if (links.contexts.isEmpty() && links.kanjiLinkOrigins.isEmpty()) return
        ankiLookups[links.token] = AnkiLookup(entries, links.contexts.toList(), links.kanjiLinkOrigins.toList())
    }

    private fun ankiLinkContext(ref: AnkiLinkRef): AnkiLinkContext? =
        ankiLookups[ref.token]?.contexts?.getOrNull(ref.entry)

    /**
     * What a kanji headword link's `origin_ref=<token>.<index>` parameter refers to, or null if
     * the parameter is missing or malformed, or its lookup has been evicted.
     */
    private fun ankiKanjiLinkOrigin(originRef: String?): AnkiKanjiLinkOrigin? {
        if (originRef == null) return null
        val separator = originRef.lastIndexOf('.')
        if (separator <= 0) return null
        val index = originRef.substring(separator + 1).toIntOrNull() ?: return null
        return ankiLookups[originRef.substring(0, separator)]?.kanjiLinkOrigins?.getOrNull(index)
    }

    /**
     * Adds `origin_ref=<token>.<index>` to each of [entry]'s kanji headword links, pointing at
     * [origin], so a kanji note made after following the link gets the sentence the term was
     * found in and the term lookup's query. The original's kanji links do the same by carrying
     * the popup's sentence and full query into the kanji lookup (display.js, _onKanjiLookup).
     * Only a reference fits in the link: the host hands the link target to onLookup verbatim
     * and caps the length of link URLs.
     */
    private fun withKanjiLinkOrigins(
        entry: DictionaryEntry,
        origin: AnkiKanjiLinkOrigin,
        links: AnkiLookupLinks
    ): DictionaryEntry {
        val spans = entry.headwordSpans ?: return entry
        if (spans.none { it.linkUrl?.startsWith(KANJI_LINK_PREFIX) == true }) return entry
        val ref = "${links.token}.${links.kanjiLinkOrigins.size}"
        links.kanjiLinkOrigins.add(origin)
        val linkedSpans = spans.map { span ->
            val url = span.linkUrl
            if (url != null && url.startsWith(KANJI_LINK_PREFIX)) {
                HeadwordSpan(startIndex = span.startIndex, endIndex = span.endIndex, linkUrl = "$url&origin_ref=$ref")
            } else {
                span
            }
        }
        return DictionaryEntry(
            headword = entry.headword,
            pronunciation = entry.pronunciation,
            headwordSpans = linkedSpans.toTypedArray(),
            body = entry.body,
            displayFlags = entry.displayFlags
        )
    }

    /**
     * The note data for the action link [link] (built on first request), or null if its
     * lookup was never registered or has been evicted.
     */
    internal suspend fun ankiNoteDataForLink(link: String): AnkiNoteData? =
        parseAnkiAddLink(link)?.let { ankiLinkContext(it) }?.noteData()

    // Plain shape glyphs, not color emoji: a color emoji carries its own fixed color and
    // ignores InlineStyle.foregroundColor, which would defeat coloring the links by their
    // status. These render in the link's color instead, like the original's single-color SVGs.
    private fun AnkiCardIcon.glyph(): String = when (this) {
        AnkiCardIcon.BIG_CIRCLE -> "●"
        AnkiCardIcon.SMALL_CIRCLE -> "◉"
        AnkiCardIcon.SQUARE -> "■"
        AnkiCardIcon.DIAMOND -> "◆"
    }

    /**
     * The text of [format]'s action link: its shape glyph, then "Add", or, once a tap on it has
     * written a note, what that tap did.
     */
    private fun ankiLinkLabel(format: AnkiCardFormat, write: AnkiNoteWrite?): String {
        val label = when (write) {
            null -> R.string.anki_add_link_label
            AnkiNoteWrite.ADDED -> R.string.anki_added_link_label
            AnkiNoteWrite.OVERWRITTEN -> R.string.anki_overwritten_link_label
        }
        return "${format.icon.glyph()} ${getString(label, format.name)}"
    }

    // Links are rendered in the "Add" color without checking for duplicates: that would need one
    // findDuplicateNotes ContentProvider round-trip per configured card format on every
    // single lookup (a hot path). The check waits until the user taps one of an entry's links,
    // when an AnkiDroid round-trip is expected anyway, and then covers that entry's links only
    // (see checkOtherAnkiLinks). The same reasoning is why existing note tags/flags aren't
    // previewed before tapping Add either (see the doc comment on AnkiSettings in AnkiSettings.kt).
    private fun appendAnkiActionLinks(
        entry: DictionaryEntry,
        formats: List<AnkiCardFormat>,
        settings: AnkiSettings,
        links: AnkiLookupLinks,
        buildNoteData: suspend () -> AnkiNoteData
    ): DictionaryEntry {
        if (formats.isEmpty()) return entry
        val entryIndex = links.contexts.size
        links.contexts.add(AnkiLinkContext(formats, settings, buildNoteData))
        val textBuilder = StringBuilder(entry.body.text)
        val newStyledSpans = entry.body.styledSpans?.toMutableList() ?: mutableListOf()

        if (textBuilder.isNotEmpty() && !textBuilder.endsWith("\n")) {
            textBuilder.append("\n")
        }
        textBuilder.append("\n")

        // One link per line.
        for ((index, format) in formats.withIndex()) {
            if (index > 0) textBuilder.append("\n")
            val linkStart = textBuilder.length
            textBuilder.append(ankiLinkLabel(format, null))
            val linkEnd = textBuilder.length
            newStyledSpans.add(
                StyledSpan(
                    startIndex = linkStart,
                    endIndex = linkEnd,
                    style = InlineStyle(
                        linkUrl = "action:anki_add?token=${links.token}&entry=$entryIndex&format=$index",
                        foregroundColor = AnkiLinkStatus.CAN_ADD.color,
                        fontSize = ANKI_LINK_FONT_SIZE
                    )
                )
            )
        }

        return DictionaryEntry(
            headword = entry.headword,
            pronunciation = entry.pronunciation,
            headwordSpans = entry.headwordSpans,
            body = StyledText(
                text = textBuilder.toString(),
                styledSpans = newStyledSpans.toTypedArray(),
                rubySpans = entry.body.rubySpans,
                blockSpans = entry.body.blockSpans
            ),
            displayFlags = entry.displayFlags
        )
    }

    private fun appendTermAnkiLinksIfConfigured(
        entry: DictionaryEntry,
        group: TermGroup,
        sortedItems: List<TermLookupMatch>,
        contextText: String,
        scanStart: Int?,
        searchQuery: String,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks
    ): DictionaryEntry {
        val settings = ankiSettings
        if (!settings.enabled) return entry

        // The host passes a whole OCR block as contextText, so notes use the sentence around
        // the match rather than the entire block.
        val sentenceSource = scanStart?.let { AnkiSentenceSource.of(contextText, it, it + group.maxMatchedLength) }
        val hasKanjiFormat = settings.cardFormats.any { it.type == AnkiEntryType.KANJI && it.isComplete() }
        val linkedEntry = if (hasKanjiFormat) {
            withKanjiLinkOrigins(entry, AnkiKanjiLinkOrigin(sentenceSource, searchQuery), links)
        } else {
            entry
        }

        val formats = settings.cardFormats.filter {
            // Excludes incomplete formats rather than rendering an Add link that would fail
            // at AnkiDroid's addNote() with an invalid deck/model id.
            it.type == AnkiEntryType.TERM && it.isComplete()
        }
        if (formats.isEmpty()) return linkedEntry

        // Only cheap values are captured here; the queries and conversions run at tap time.
        val groupTerms = sortedItems.map { it.term }
        val inflectionRuleChains = sortedItems.map { it.deinflectionRules }.distinct()
        val groupExpression = group.expression
        val groupReading = group.reading

        return appendAnkiActionLinks(linkedEntry, formats, settings, links) {
            val primaryTerm = groupTerms.first()
            val tagMetaList = groupTerms.map { it.dictionary }.distinct().flatMap { dictionary ->
                try {
                    db.tagMetaDao().findAllForDictionary(dictionary)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            // Frequency dictionaries are standalone (no term_bank of their own), so their
            // term_meta rows live under a different dictionary title than this term's own -
            // query across every active dictionary, not just primaryTerm.dictionary. This
            // matches YomitanRenderer.renderTermEntries's established pattern.
            val termMetaList = try {
                db.termMetaDao().findByExpressionBulk(listOf(primaryTerm.expression), activeDictionaries)
            } catch (e: Exception) {
                emptyList()
            }
            val dictionaryStyles = groupTerms.map { it.dictionary }.distinct().associateWith { dictionary ->
                try {
                    db.dictionaryDao().getDictionaryByTitle(dictionary)?.styles ?: ""
                } catch (e: Exception) {
                    ""
                }
            }

            val cloze = sentenceSource?.let {
                AnkiNoteDataBuilder.buildCloze(it.extract(), it.matchedText, groupExpression, groupReading)
            }

            AnkiNoteDataBuilder.buildTermNoteData(
                terms = groupTerms,
                tagMetaList = tagMetaList,
                termMetaList = termMetaList,
                cloze = cloze,
                dictionaryOrder = activeDictionaries,
                inflectionRuleChains = inflectionRuleChains,
                dictionaryStyles = dictionaryStyles,
                searchQuery = searchQuery,
                frequencyModes = ankiFrequencyModes(termMetaList.map { it.dictionary })
            )
        }
    }

    /**
     * [kanji] is the entry's own kanji dictionary row. [sentenceSource] is the match the note's
     * sentence and cloze come from: the tapped kanji itself, or, for a kanji reached through a
     * term's headword link, that term's match. In the second case the cloze is split at the
     * term's position with a body as long as the kanji, as the original's getCloze does with the
     * sentence a kanji link carries over. [searchQuery] is the lookup's query, for
     * `{search-query}`.
     */
    private fun appendKanjiAnkiLinksIfConfigured(
        entry: DictionaryEntry,
        kanji: KanjiEntity,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks,
        sentenceSource: AnkiSentenceSource?,
        searchQuery: String
    ): DictionaryEntry {
        val settings = ankiSettings
        if (!settings.enabled) return entry
        val formats = settings.cardFormats.filter {
            it.type == AnkiEntryType.KANJI && it.isComplete()
        }
        if (formats.isEmpty()) return entry

        // Only cheap values are captured here; the queries and conversions run at tap time.
        return appendAnkiActionLinks(entry, formats, settings, links) {
            val tagMetaList = try {
                db.tagMetaDao().findAllForDictionary(kanji.dictionary)
            } catch (e: Exception) {
                emptyList()
            }
            val kanjiMetaList = try {
                db.kanjiMetaDao().findByCharacterBulk(listOf(kanji.character), activeDictionaries)
            } catch (e: Exception) {
                emptyList()
            }
            val cloze = sentenceSource?.let {
                AnkiNoteDataBuilder.buildCloze(it.extract(), kanji.character, "", "")
            }
            AnkiNoteDataBuilder.buildKanjiNoteData(
                kanji = kanji,
                tagMetaList = tagMetaList,
                kanjiMetaList = kanjiMetaList,
                cloze = cloze,
                dictionaryOrder = activeDictionaries,
                searchQuery = searchQuery,
                frequencyModes = ankiFrequencyModes(kanjiMetaList.map { it.dictionary })
            )
        }
    }

    /**
     * The `frequencyMode` each of [dictionaries] declares in its index.json, for the frequency
     * markers that only count rank-based or occurrence-based values. A dictionary that declares
     * none is left out.
     */
    private suspend fun ankiFrequencyModes(dictionaries: Collection<String>): Map<String, String> =
        dictionaries.distinct().mapNotNull { title ->
            try {
                db.dictionaryDao().getDictionaryByTitle(title)?.frequencyMode?.let { title to it }
            } catch (e: Exception) {
                null
            }
        }.toMap()

    private fun entryHasLink(entry: DictionaryEntry, ref: AnkiLinkRef): Boolean =
        entry.body.styledSpans?.any { parseAnkiAddLink(it.style.linkUrl) == ref } == true

    /**
     * [entry] with each of its action links for [ref] relabeled from [context]'s writes and
     * recolored from its statuses. A label can change length, so every index at or after the end
     * of a relabeled link (text, styled, ruby and block spans alike) is shifted to match, keeping
     * the rest of the entry in place.
     */
    private fun restyleAnkiLinks(entry: DictionaryEntry, ref: AnkiLinkRef, context: AnkiLinkContext): DictionaryEntry {
        val body = entry.body
        var text = body.text
        var styledSpans = body.styledSpans?.toList() ?: return entry
        var rubySpans = body.rubySpans?.toList()
        var blockSpans = body.blockSpans?.toList()
        // The last link first: relabeling a link only moves what comes after it, so the links
        // still to be relabeled keep their indices.
        val links =
            styledSpans.filter { parseAnkiAddLink(it.style.linkUrl) == ref }.sortedByDescending { it.startIndex }
        for (link in links) {
            val formatIndex = parseAnkiAddLinkFormat(link.style.linkUrl) ?: continue
            val format = context.formats.getOrNull(formatIndex) ?: continue
            val label = ankiLinkLabel(format, context.writes[formatIndex])
            val color = (context.statuses[formatIndex] ?: AnkiLinkStatus.CAN_ADD).color
            val end = link.endIndex
            val delta = label.length - (end - link.startIndex)
            fun shift(index: Int) = if (index >= end) index + delta else index
            text = text.replaceRange(link.startIndex, end, label)
            styledSpans = styledSpans.map { span ->
                if (span.startIndex == link.startIndex && span.style.linkUrl == link.style.linkUrl) {
                    StyledSpan(
                        startIndex = span.startIndex,
                        endIndex = span.startIndex + label.length,
                        style = InlineStyle(
                            linkUrl = span.style.linkUrl,
                            foregroundColor = color,
                            fontSize = span.style.fontSize
                        )
                    )
                } else {
                    StyledSpan(startIndex = shift(span.startIndex), endIndex = shift(span.endIndex), style = span.style)
                }
            }
            rubySpans = rubySpans?.map {
                RubySpan(startIndex = shift(it.startIndex), endIndex = shift(it.endIndex), rubyText = it.rubyText)
            }
            blockSpans = blockSpans?.map {
                BlockSpan(
                    startIndex = shift(it.startIndex),
                    endIndex = shift(it.endIndex),
                    blockType = it.blockType,
                    indentLevel = it.indentLevel,
                    listMarker = it.listMarker,
                    backgroundColor = it.backgroundColor
                )
            }
        }
        return DictionaryEntry(
            headword = entry.headword,
            pronunciation = entry.pronunciation,
            headwordSpans = entry.headwordSpans,
            body = StyledText(
                text = text,
                styledSpans = styledSpans.toTypedArray(),
                rubySpans = rubySpans?.toTypedArray(),
                blockSpans = blockSpans?.toTypedArray()
            ),
            displayFlags = entry.displayFlags
        )
    }

    /**
     * Applies [update] to the writes and statuses of [ref]'s links, and restyles those links in
     * the lookup's stored entries. Returns the lookup's entries to show, or null if the lookup is
     * no longer tracked. The host replaces its whole displayed result with what an UpdateResult
     * carries, so the entries returned must be those of the lookup that produced [ref], never
     * another lookup's.
     */
    private fun updateAnkiLinks(ref: AnkiLinkRef, update: (AnkiLinkContext) -> Unit): Array<DictionaryEntry>? {
        val lookup = ankiLookups[ref.token] ?: return null
        synchronized(lookup) {
            val context = lookup.contexts.getOrNull(ref.entry) ?: return null
            update(context)
            lookup.entries =
                lookup.entries.map { if (entryHasLink(it, ref)) restyleAnkiLinks(it, ref, context) else it }
            return lookup.entries.toTypedArray()
        }
    }

    /**
     * The duplicates of a note of [format] whose first field is [key], searched the way
     * [settings] ask for, or null if they ask for no search (duplicate checking off, or an empty
     * first field, which cannot be searched for).
     */
    private fun findAnkiDuplicates(
        settings: AnkiSettings,
        format: AnkiCardFormat,
        key: String
    ): List<AnkiNoteInfo>? {
        if (!settings.checkForDuplicates || key.isEmpty()) return null
        return ankiDroidClient.findDuplicateNotes(
            modelId = format.modelId,
            deckId = format.deckId,
            key = key,
            scope = settings.duplicateScope,
            checkAllModels = settings.duplicateScopeCheckAllModels
        )
    }

    /**
     * The status of each of [context]'s formats except [tappedIndex], found with the duplicate
     * search a tap on its link would run: one AnkiDroid query per format, plus one field-list
     * query per note type not already in [fieldLists]. Formats the search does not run for, or
     * fails for, are left out, keeping whatever status they had.
     */
    private suspend fun checkOtherAnkiLinks(
        context: AnkiLinkContext,
        tappedIndex: Int,
        fieldLists: MutableMap<Long, List<String>>
    ): Map<Int, AnkiLinkStatus> {
        val settings = context.settings
        if (!settings.checkForDuplicates) return emptyMap()
        val statuses = HashMap<Int, AnkiLinkStatus>()
        for ((index, format) in context.formats.withIndex()) {
            if (index == tappedIndex) continue
            try {
                val fieldNames = fieldLists.getOrPut(format.modelId) { ankiDroidClient.getFieldList(format.modelId) }
                val noteData = context.noteData()
                // A tap on this link would first ask Dokuen for the sentence's furigana, which this
                // tap did not need, so its key cannot be rendered the way that tap would render it.
                if (needsSentenceFurigana(noteData, fieldNames.take(1), format.fields)) continue
                val key = fieldNames.firstOrNull()
                    ?.let { format.fields[it] }
                    ?.let { AnkiTemplateEngine.renderField(it.value, noteData).text }
                    ?: ""
                val duplicates = findAnkiDuplicates(settings, format, key) ?: continue
                statuses[index] = AnkiLinkStatus.of(
                    settings.duplicateBehavior,
                    hasDuplicate = duplicates.isNotEmpty(),
                    hasSameTypeDuplicate = duplicates.any { it.modelId == format.modelId }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The tapped note is already written; a failed check only leaves a color as it was.
                Log.w(TAG, "AnkiDroid duplicate check failed for card format ${format.name}", e)
            }
        }
        return statuses
    }

    private suspend fun buildEntriesFromTermGroups(
        sortedGroups: List<TermGroup>,
        activeDictionaries: List<String>,
        contextText: String,
        scanStart: Int?,
        searchQuery: String,
        links: AnkiLookupLinks
    ): List<DictionaryEntry> {
        val entries = mutableListOf<DictionaryEntry>()
        for (group in sortedGroups) {
            val sortedItems = group.items.sortedWith(
                compareBy<TermLookupMatch> { it.dictionaryIndex }
                    .thenByDescending { it.term.score }
                    .thenByDescending { it.term.sequence }
            )

            val itemGroups = mutableListOf<MutableList<TermLookupMatch>>()
            val sequenceToGroup = mutableMapOf<Pair<String, Long>, MutableList<TermLookupMatch>>()

            for (item in sortedItems) {
                val seq = item.term.sequence
                val dict = item.term.dictionary
                if (seq != null && seq >= 0L) {
                    val key = Pair(dict, seq)
                    val seqGroup = sequenceToGroup.getOrPut(key) {
                        val newGroup = mutableListOf<TermLookupMatch>()
                        itemGroups.add(newGroup)
                        newGroup
                    }
                    seqGroup.add(item)
                } else {
                    itemGroups.add(mutableListOf(item))
                }
            }

            val renderedEntries = mutableListOf<DictionaryEntry>()
            for (itemGroup in itemGroups) {
                val primaryMatch = itemGroup.first()
                val dictEntity = db.dictionaryDao().getDictionaryByTitle(primaryMatch.term.dictionary)
                val styles = dictEntity?.styles ?: ""

                val terms = itemGroup.map { it.term }
                val entry = renderer.renderTermEntries(terms, styles, activeDictionaries)

                val finalEntry = if (primaryMatch.deinflectionRules.isNotEmpty()) {
                    decorateDeinflection(entry, primaryMatch.deinflectionRules)
                } else {
                    entry
                }
                renderedEntries.add(finalEntry)
            }

            val mergedEntry = mergeDictionaryEntries(
                group.expression,
                renderedEntries.firstOrNull()?.pronunciation,
                renderedEntries.firstOrNull()?.headwordSpans,
                renderedEntries
            )

            entries.add(
                appendTermAnkiLinksIfConfigured(
                    mergedEntry,
                    group,
                    sortedItems,
                    contextText,
                    scanStart,
                    searchQuery,
                    activeDictionaries,
                    links
                )
            )
        }
        return entries
    }

    /**
     * Perform a direct kanji-dictionary lookup for a single character.
     *
     * This is the port of display-generator.js::_setContentTermsOrKanji('kanji', char):
     * it skips the term path entirely and queries the kanji store directly. The result
     * mirrors the JS behavior where clicking a headword kanji opens the kanji entry
     * regardless of whether a term entry also exists for that character.
     *
     * Called from lookupInternalLink when the parsed query params contain a "kanji" key,
     * which is produced by buildKanjiHeadwordSpans in TermEntryRenderer (link URL format:
     * "lookup:?kanji=<char>", plus "&origin_ref=<token>.<index>" when withKanjiLinkOrigins
     * added one).
     *
     * Returns an empty list (not an exception) when no kanji entry is found so that
     * onLookup can throw the standard WORD_NOT_FOUND exception with a proper message.
     * If no kanji dictionary is installed or enabled at all, returns an informational
     * entry instead.
     *
     * @param character The single kanji character to look up (e.g. "日")
     * @param activeDictionaries The currently enabled dictionaries, in priority order
     * @param sentenceSource The match of the term whose headword link was followed, if its
     *   lookup recorded one and is still cached; used for the kanji note's sentence.
     * @param searchQuery The lookup's query, for the kanji note's `{search-query}`.
     */
    private suspend fun lookupKanjiDirect(
        character: String,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks,
        sentenceSource: AnkiSentenceSource?,
        searchQuery: String
    ): List<DictionaryEntry> {
        if (character.isEmpty()) return emptyList()

        // If no kanji dictionary is installed or active, show a message
        val hasAnyKanjiDictionary = activeDictionaries.any { title ->
            db.dictionaryDao().countKanji(title) > 0
        }
        if (!hasAnyKanjiDictionary) {
            return listOf(buildNoKanjiDictionaryEntry(character))
        }

        return renderKanjiEntries(character, activeDictionaries, links, sentenceSource, searchQuery)
    }

    /**
     * Renders one entry per kanji dictionary row for [character], in the user's dictionary
     * priority order, or an empty list if no active dictionary has it.
     *
     * Mirrors the original's Translator.findKanji, which makes each database row its own
     * KanjiDictionaryEntry and sorts them by dictionary index: entries from different kanji
     * dictionaries are shown separately, never merged into one. sortedBy is stable, so rows
     * from the same dictionary keep their database order, as in the original.
     *
     * Each entry gets its own Anki links, built from its own row, as each of the original's
     * kanji entries has its own add buttons. [sentenceSource] is the match every note's
     * sentence comes from and [searchQuery] the lookup's query (see
     * appendKanjiAnkiLinksIfConfigured).
     */
    private suspend fun renderKanjiEntries(
        character: String,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks,
        sentenceSource: AnkiSentenceSource?,
        searchQuery: String
    ): List<DictionaryEntry> {
        val kanjiMatches = db.kanjiDao().findByCharacterBulk(listOf(character), activeDictionaries)
        val sortedMatches = kanjiMatches.sortedBy { match ->
            val idx = activeDictionaries.indexOf(match.dictionary)
            if (idx != -1) idx else Int.MAX_VALUE
        }
        return sortedMatches.map { match ->
            val dictEntity = db.dictionaryDao().getDictionaryByTitle(match.dictionary)
            val styles = dictEntity?.styles ?: ""
            val entry = renderer.renderKanjiEntry(match, styles, activeDictionaries)
            appendKanjiAnkiLinksIfConfigured(entry, match, activeDictionaries, links, sentenceSource, searchQuery)
        }
    }

    /**
     * Build an informational DictionaryEntry displayed when the user taps a kanji headword
     * link but no kanji dictionary is installed or enabled.
     *
     * Returned as a success result (not an exception) so the host app renders it inline
     * in the lookup panel. The entry uses the tapped character as its headword so the
     * display context is clear, and the body explains what the user needs to do.
     *
     * @param character The kanji character that was tapped (used as the headword)
     */
    private fun buildNoKanjiDictionaryEntry(character: String): DictionaryEntry {
        val title = getString(R.string.no_kanji_dictionary_title)
        val body = getString(R.string.no_kanji_dictionary_body)

        val titleEnd = title.length
        val fullText = "$title\n$body"

        return DictionaryEntry(
            headword = character,
            pronunciation = null,
            headwordSpans = null,
            body = StyledText(
                text = fullText,
                styledSpans = arrayOf(
                    StyledSpan(
                        startIndex = 0,
                        endIndex = titleEnd,
                        style = InlineStyle(bold = true)
                    )
                ),
                rubySpans = null,
                blockSpans = null
            ),
            displayFlags = io.github.dokuendev.dokuenreader.dictionary.FLAG_HEADWORD_STROKE_ORDER
        )
    }

    private suspend fun lookupInternalLink(
        contextText: String,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks
    ): List<DictionaryEntry> {
        val params = parseQueryParams(contextText)

        // ?kanji=<char> - direct kanji-dictionary lookup.
        // Produced by buildKanjiHeadwordSpans in TermEntryRenderer when the user taps
        // a kanji character in a term headword. Routes straight to the kanji store,
        // bypassing the term path entirely. See _setContentTermsOrKanji('kanji', char).
        val kanjiChar = params["kanji"]
        if (kanjiChar != null) {
            val origin = ankiKanjiLinkOrigin(params["origin_ref"])
            // As the original's _createSearchParams: the kanji lookup keeps the term lookup's
            // query when that is longer than the kanji itself.
            val searchQuery = origin?.searchQuery?.takeIf { it.length > kanjiChar.length } ?: kanjiChar
            return lookupKanjiDirect(kanjiChar, activeDictionaries, links, origin?.sentenceSource, searchQuery)
        }

        val processedTermIds = mutableSetOf<Long>()
        val query = params["query"] ?: ""
        val primaryReading = params["primary_reading"]
        val wildcards = params["wildcards"]

        if (query.isEmpty()) return emptyList()

        val matches =
            if (wildcards != "off" && (query.startsWith("*") || query.startsWith("＊") || query.endsWith("*") || query.endsWith(
                    "＊"
                ))
            ) {
                val isSuffix = query.startsWith("*") || query.startsWith("＊")
                val isPrefix = query.endsWith("*") || query.endsWith("＊")
                val term =
                    if (isSuffix) query.substring(1) else if (isPrefix) query.substring(0, query.length - 1) else query

                if (isSuffix) {
                    val reversed = term.reversed()
                    val expSuffix =
                        db.termDao().findByExpressionSuffix(reversed, reversed + "\uFFFF", activeDictionaries)
                    val readSuffix =
                        db.termDao().findByReadingSuffix(reversed, reversed + "\uFFFF", activeDictionaries)
                    (expSuffix + readSuffix).distinctBy { it.id }
                } else {
                    val expPrefix = db.termDao().findByExpressionPrefix(term, term + "\uFFFF", activeDictionaries)
                    val readPrefix = db.termDao().findByReadingPrefix(term, term + "\uFFFF", activeDictionaries)
                    (expPrefix + readPrefix).distinctBy { it.id }
                }
            } else {
                if (!primaryReading.isNullOrEmpty()) {
                    val exactMatches = db.termDao().findExact(query, primaryReading, activeDictionaries)
                    exactMatches.ifEmpty {
                        val expMatches = db.termDao().findByExpression(query, activeDictionaries)
                        val readMatches = db.termDao().findByReading(query, activeDictionaries)
                        (expMatches + readMatches).distinctBy { it.id }
                    }
                } else {
                    val expMatches = db.termDao().findByExpression(query, activeDictionaries)
                    val readMatches = db.termDao().findByReading(query, activeDictionaries)
                    (expMatches + readMatches).distinctBy { it.id }
                }
            }

        val groupsMap = mutableMapOf<String, TermGroup>()
        for (match in matches) {
            if (processedTermIds.contains(match.id)) continue
            processedTermIds.add(match.id)

            val dictIndex = activeDictionaries.indexOf(match.dictionary)
            val resolvedDictIndex = if (dictIndex != -1) dictIndex else Int.MAX_VALUE

            val lookupMatch = TermLookupMatch(
                term = match,
                matchedLength = 0,
                deinflectionRulesCount = 0,
                dictionaryIndex = resolvedDictIndex,
                deinflectionRules = emptyList()
            )

            val key = "${match.expression}\n${match.reading}"
            val group = groupsMap.getOrPut(key) { TermGroup(match.expression, match.reading) }
            group.items.add(lookupMatch)
        }

        populateSortFrequencyOrder(groupsMap.values)

        val sortedGroups = groupsMap.values.sortedWith(
            compareBy<TermGroup> { it.frequencyOrder }
                .thenBy { it.minDictionaryIndex }
                .thenByDescending { it.maxScore }
                .thenByDescending { it.expression.length }
                .thenBy { it.expression }
                .thenBy { it.reading }
                .thenByDescending { it.maxSequence }
        )

        return buildEntriesFromTermGroups(
            sortedGroups,
            activeDictionaries,
            contextText,
            scanStart = null,
            searchQuery = query,
            links = links
        )
    }

    private suspend fun scanAndDeinflectLookup(
        contextText: String,
        start: Int,
        maxLen: Int,
        minLen: Int,
        activeDictionaries: List<String>,
        links: AnkiLookupLinks
    ): List<DictionaryEntry> {
        val matchesList = mutableListOf<TermLookupMatch>()
        val processedTermIds = mutableSetOf<Long>()

        // Yomitan prioritizes longer matching terms. Scanning from maxLen down to minLen ensures
        // that longer matches are automatically processed and appended first in descending order.
        for (len in maxLen downTo minLen) {
            val substring = contextText.substring(start, start + len).trim()
            if (substring.isEmpty()) continue

            // Run deinflector to yield all possible base dictionary form candidates
            val deinflections = deinflector.deinflect(substring)
            Log.d(
                TAG,
                "  Scanning len $len: substring='$substring' (Deinflections: ${deinflections.map { it.term }})"
            )

            // The same text can be reached through several conjugations, each leaving it a different
            // word class. As in the original (_groupDeinflectionsByTerm and
            // _matchEntriesToDeinflections in translator.js), each text is looked up once, and an entry
            // is only a match through a path whose word class its parts of speech allow, so, say, a
            // noun is never reached through a verb ending. Paths come shortest first, so an entry
            // shows the shortest chain that reaches it.
            for ((candidate, paths) in deinflections.groupBy { it.term }) {
                // Query both Expression and Reading matches and merge them to avoid omitting homophones
                val expMatches = db.termDao().findByExpression(candidate, activeDictionaries)
                Log.d(TAG, "    Candidate '$candidate': Expression query returned ${expMatches.size} matches")

                val readMatches = db.termDao().findByReading(candidate, activeDictionaries)
                Log.d(TAG, "    Candidate '$candidate': Reading query returned ${readMatches.size} matches")

                val matches = (expMatches + readMatches).distinctBy { it.id }

                for (match in matches) {
                    if (processedTermIds.contains(match.id)) continue
                    val entryConditions = deinflector.conditionFlagsFromPartsOfSpeech(match.rules)
                    val path = paths.firstOrNull { JapaneseDeinflector.conditionsMatch(it.conditions, entryConditions) }
                        ?: continue
                    processedTermIds.add(match.id)

                    val dictIndex = activeDictionaries.indexOf(match.dictionary)
                    val resolvedDictIndex = if (dictIndex != -1) dictIndex else Int.MAX_VALUE

                    matchesList.add(
                        TermLookupMatch(
                            term = match,
                            matchedLength = substring.length,
                            deinflectionRulesCount = path.rules.size,
                            dictionaryIndex = resolvedDictIndex,
                            deinflectionRules = path.rules
                        )
                    )
                }
            }
        }

        val groupsMap = mutableMapOf<String, TermGroup>()
        for (match in matchesList) {
            val key = "${match.term.expression}\n${match.term.reading}"
            val group = groupsMap.getOrPut(key) { TermGroup(match.term.expression, match.term.reading) }
            group.items.add(match)
        }

        populateSortFrequencyOrder(groupsMap.values)

        val sortedGroups = groupsMap.values.sortedWith(
            compareByDescending<TermGroup> { it.maxMatchedLength }
                .thenBy { it.minDeinflectionRulesCount }
                .thenBy { it.frequencyOrder }
                .thenBy { it.minDictionaryIndex }
                .thenByDescending { it.maxScore }
                .thenByDescending { it.expression.length }
                .thenBy { it.expression }
                .thenBy { it.reading }
                .thenByDescending { it.maxSequence }
        )

        // The original's popup query is the scanned text cut to the longest match of any result
        // (text-scanner.js, _findTermDictionaryEntries), the same for every entry.
        val longestMatch = sortedGroups.maxOfOrNull { it.maxMatchedLength } ?: 0
        return buildEntriesFromTermGroups(
            sortedGroups,
            activeDictionaries,
            contextText,
            scanStart = start,
            searchQuery = contextText.substring(start, minOf(start + longestMatch, contextText.length)),
            links = links
        )
    }

    private fun decorateDeinflection(
        entry: DictionaryEntry,
        rules: List<InflectionRule>
    ): DictionaryEntry {
        val badgeText = getString(R.string.deinflected_badge)
        val builder = StringBuilder(badgeText)

        val badgeBgColor = if (isDarkMode) 0xFFB07F39.toInt() else 0xFFF57C00.toInt()
        val badgeFgColor = if (isDarkMode) 0xFFF1F1F1.toInt() else 0xFFFFFFFF.toInt()
        val notesFgColor = if (isDarkMode) 0xFF888888.toInt() else 0xFF666666.toInt()

        val badgeSpan = StyledSpan(
            startIndex = 0,
            endIndex = badgeText.length,
            style = InlineStyle(
                bold = true,
                fontSize = 0.75f,
                foregroundColor = badgeFgColor,
                textBackgroundColor = badgeBgColor
            )
        )

        val newSpans = mutableListOf<StyledSpan>()
        newSpans.add(badgeSpan)

        // Like the original's inflection rule chain: innermost first, joined with "«", so
        // 食べさせた reads "-せる・させる « -た".
        for (i in rules.indices) {
            val rule = rules[i]
            val separator = if (i == 0) " " else " « "
            val prevEnd = builder.length
            builder.append(separator)

            builder.append(rule.name)
            val end = builder.length

            val description = rule.description

            val spanStart = if (i == 0) prevEnd else prevEnd + 1
            val spanEnd = if (i == rules.size - 1) end else end + 1

            // Style the separator and rule part together with hoverText
            newSpans.add(
                StyledSpan(
                    startIndex = spanStart,
                    endIndex = spanEnd,
                    style = InlineStyle(
                        italic = true,
                        fontSize = 0.75f,
                        foregroundColor = notesFgColor,
                        hoverText = description
                    )
                )
            )
        }

        builder.append("\n")
        val deinflectBadgeText = builder.toString()
        val shift = deinflectBadgeText.length

        val oldText = entry.body.text
        val newText = deinflectBadgeText + oldText

        entry.body.styledSpans?.forEach { span ->
            newSpans.add(StyledSpan(span.startIndex + shift, span.endIndex + shift, span.style))
        }

        val newRubySpans = mutableListOf<RubySpan>()
        entry.body.rubySpans?.forEach { ruby ->
            newRubySpans.add(
                RubySpan(
                    ruby.startIndex + shift,
                    ruby.endIndex + shift,
                    ruby.rubyText
                )
            )
        }

        val newBlockSpans = mutableListOf<BlockSpan>()
        entry.body.blockSpans?.forEach { span ->
            newBlockSpans.add(
                BlockSpan(
                    span.startIndex + shift,
                    span.endIndex + shift,
                    span.blockType,
                    span.indentLevel,
                    span.listMarker,
                    span.backgroundColor
                )
            )
        }

        return DictionaryEntry(
            headword = entry.headword,
            pronunciation = entry.pronunciation,
            headwordSpans = entry.headwordSpans,
            body = StyledText(
                text = newText,
                blockSpans = if (newBlockSpans.isEmpty()) null else newBlockSpans.toTypedArray(),
                styledSpans = newSpans.toTypedArray(),
                rubySpans = newRubySpans.toTypedArray()
            )
        )
    }

    private fun parseQueryParams(queryStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val query = if (queryStr.startsWith("?")) queryStr.substring(1) else queryStr
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx != -1) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                try {
                    map[key] = java.net.URLDecoder.decode(value, "UTF-8")
                } catch (_: Exception) {
                    map[key] = value
                }
            }
        }
        return map
    }

    override suspend fun onExecuteCustomAction(
        actionPayload: String,
        context: CustomActionContext
    ): CustomActionResult {
        return when {
            actionPayload.startsWith("anki_add?") -> try {
                executeAnkiAddAction(actionPayload, context)
            } catch (e: DictionaryException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // AnkiDroid's ContentProvider can throw (permission revoked mid-call, collection
                // unavailable, provider crash). Report it instead of letting it escape raw.
                Log.w(TAG, "AnkiDroid call failed", e)
                throw DictionaryException(
                    DictionaryErrorCode.INTERNAL_ERROR,
                    getString(R.string.error_anki_unexpected, getString(R.string.app_name))
                )
            }

            else -> throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                "Unknown custom action: $actionPayload"
            )
        }
    }

    private suspend fun executeAnkiAddAction(
        actionPayload: String,
        actionContext: CustomActionContext
    ): CustomActionResult {
        val queryPart = actionPayload.removePrefix("anki_add?")
        val params = parseQueryParams("?$queryPart")
        val ref = parseAnkiAddLink(actionPayload)
            ?: throw DictionaryException(DictionaryErrorCode.INTERNAL_ERROR, getString(R.string.error_anki_stale_link))
        val formatIndex = params["format"]?.toIntOrNull()
            ?: throw DictionaryException(DictionaryErrorCode.INTERNAL_ERROR, getString(R.string.error_anki_stale_link))

        val linkContext = ankiLinkContext(ref)
            ?: throw DictionaryException(DictionaryErrorCode.INTERNAL_ERROR, getString(R.string.error_anki_stale_link))
        // Resolved against the list captured when the link was rendered, not the current
        // settings, so the index always means the format the user tapped.
        val format = linkContext.formats.getOrNull(formatIndex)
            ?: throw DictionaryException(DictionaryErrorCode.INTERNAL_ERROR, getString(R.string.error_anki_stale_link))
        val settings = linkContext.settings

        // The snapshot only decides which format and duplicate rules apply; whether export is
        // allowed at all is always the live setting, so turning it off takes effect at once.
        val liveSettings = AnkiSettingsStore.load(applicationContext)
        if (!liveSettings.enabled) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                getString(R.string.error_anki_export_disabled)
            )
        }

        if (!ankiDroidClient.isAnkiDroidInstalled()) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                getString(R.string.error_ankidroid_not_installed)
            )
        }
        if (!ankiDroidClient.hasPermission()) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                getString(R.string.error_ankidroid_no_permission)
            )
        }

        // Only the note type's current fields are rendered, unlike the original, which renders
        // every stored field. AddContentApi takes a note's fields as a positional array in the
        // note type's field order (joined into FlashCardsContract's FLDS column), with no field
        // names, so a stored field the note type no longer has has no slot and can never be
        // written. Rendering it anyway would only report marker errors in text that never
        // reaches the note.
        val fieldNames = ankiDroidClient.getFieldList(format.modelId)
        // AnkiDroid adds a note to a deck id that no longer exists without complaint, leaving its
        // cards in no deck, so a deleted deck or note type is refused here, as AnkiConnect refuses
        // a deck or note type it cannot find.
        if (fieldNames.isEmpty()) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                getString(R.string.error_anki_note_type_missing, format.name, format.modelName)
            )
        }
        if (format.deckId !in ankiDroidClient.getDeckList()) {
            throw DictionaryException(
                DictionaryErrorCode.INTERNAL_ERROR,
                getString(R.string.error_anki_deck_missing, format.name, format.deckName)
            )
        }
        // The first field is the duplicate search's key, so the sentence's furigana is requested
        // before rendering only when that field uses it; the other fields wait for the duplicate
        // decision (see below).
        var noteData = linkContext.noteData()
        val furiganaRequestedForKey = needsSentenceFurigana(noteData, fieldNames.take(1), format.fields)
        if (furiganaRequestedForKey) {
            noteData = requestSentenceFurigana(linkContext, noteData, actionContext)
        }
        val keyData = noteData
        val rendered = fieldNames.map { fieldName ->
            format.fields[fieldName]?.let { AnkiTemplateEngine.renderField(it.value, keyData) }
        }
        val fieldValues = rendered.map { it?.text ?: "" }
        // Like the original, an unknown marker does not stop the export: it is written into the
        // field as `{marker-render-error}` and reported once the note is saved.
        val unknownMarkers = rendered.flatMap { it?.unknownMarkers ?: emptyList() }.distinct()

        val searched = findAnkiDuplicates(settings, format, fieldValues.firstOrNull() ?: "")
        val duplicates = searched ?: emptyList()
        // Once written, the note is a duplicate of the format's own note type for the next tap's
        // search, as the original's button reflects after an add. Without a search, the next
        // tap just adds another note.
        val statusAfterWrite = if (searched != null) {
            AnkiLinkStatus.of(settings.duplicateBehavior, hasDuplicate = true, hasSameTypeDuplicate = true)
        } else {
            AnkiLinkStatus.ADD_DUPLICATE
        }

        // A note of the format's own note type to overwrite; null means a new note is added.
        var existing: AnkiNoteInfo? = null
        if (duplicates.isNotEmpty()) {
            when (settings.duplicateBehavior) {
                AnkiDuplicateBehavior.PREVENT -> {
                    // The message replaces the refresh, so the link turns gray the next time a
                    // tap refreshes the lookup.
                    updateAnkiLinks(ref) { it.statuses[formatIndex] = AnkiLinkStatus.PREVENTED }
                    return CustomActionResult.SuccessMessage(getString(R.string.anki_note_already_exists))
                }

                AnkiDuplicateBehavior.OVERWRITE -> {
                    // A duplicate search across all note types can return notes whose fields
                    // differ from this format's, and a positional merge would corrupt them, so
                    // only a note of the format's own note type is ever overwritten. Without
                    // one, a new note is added.
                    existing = duplicates.firstOrNull { it.modelId == format.modelId }
                }

                AnkiDuplicateBehavior.ALWAYS_ADD -> {
                    // add below
                }
            }
        }

        // Like the images below, the furigana for the other fields is requested only now that it
        // is known what the write keeps, and at most once per tap.
        if (!furiganaRequestedForKey && canRequestSentenceFurigana(noteData) &&
            AnkiSentenceFurigana.isNeeded(fieldNames, format.fields, existing)
        ) {
            noteData = requestSentenceFurigana(linkContext, noteData, actionContext)
        }

        // Images are requested only now that it is known what the write keeps, and the fields
        // above (including the duplicate key) were rendered without them. How images are
        // obtained and encoded follows the live settings, not the link's snapshot.
        val media = acquireAnkiMedia(actionContext, liveSettings, format, fieldNames, existing)
            ?: return CustomActionResult.SuccessMessage(getString(R.string.anki_note_not_added))
        if (!media.isEmpty) {
            noteData = noteData.copy(
                screenshotFileName = media.screenshotFileName,
                clipboardImageFileName = media.clipboardImageFileName,
                clipboardText = media.clipboardText
            )
        }
        val finalData = noteData
        val finalValues = if (finalData === keyData) {
            fieldValues
        } else {
            fieldNames.map { fieldName ->
                format.fields[fieldName]?.let { AnkiTemplateEngine.renderField(it.value, finalData).text } ?: ""
            }
        }

        val toOverwrite = existing
        if (toOverwrite != null) {
            val mergedFields = fieldNames.mapIndexed { i, fieldName ->
                val overwriteMode = format.fields[fieldName]?.overwriteMode ?: AnkiFieldOverwriteMode.COALESCE
                AnkiFieldMerger.merge(toOverwrite.fields.getOrElse(i) { "" }, finalValues[i], overwriteMode)
            }
            if (!ankiDroidClient.updateNoteFields(toOverwrite.id, mergedFields)) {
                throw DictionaryException(
                    DictionaryErrorCode.INTERNAL_ERROR,
                    getString(R.string.error_anki_update_failed)
                )
            }
            return finishAnkiWrite(
                ref, linkContext, formatIndex, added = false, statusAfterWrite, fieldNames, unknownMarkers,
                media.failedMarkers, media.deniedMarkers
            )
        }

        ankiDroidClient.addNote(format.modelId, format.deckId, finalValues, settings.tags.toSet())
            ?: throw DictionaryException(DictionaryErrorCode.INTERNAL_ERROR, getString(R.string.error_anki_add_failed))
        return finishAnkiWrite(
            ref, linkContext, formatIndex, added = true, statusAfterWrite, fieldNames, unknownMarkers,
            media.failedMarkers, media.deniedMarkers
        )
    }

    /**
     * Requests the cloze sentence's furigana from Dokuen and returns [data] with it added. Call it
     * only for a [data] that canRequestSentenceFurigana accepts. The answer is kept in [context]
     * for later taps of the same entry. When Dokuen gives none, [data] is returned unchanged and
     * nothing is kept, so the next tap asks again.
     */
    private suspend fun requestSentenceFurigana(
        context: AnkiLinkContext,
        data: AnkiNoteData,
        actionContext: CustomActionContext
    ): AnkiNoteData {
        val sentence = data.cloze?.sentence ?: return data
        val words = actionContext.requestReadings(sentence) ?: return data
        return data.copy(sentenceFurigana = AnkiSentenceFurigana.fromReadings(sentence, words))
            .also { context.updateNoteData(it) }
    }

    /**
     * Whether Dokuen can be asked for the cloze sentence's furigana for [data]. That is the case
     * when [data] has a non-empty sentence but no furigana yet, the session language is `ja`, and
     * the sentence is at most CustomActionContext.MAX_READINGS_TEXT_LENGTH long.
     */
    private fun canRequestSentenceFurigana(data: AnkiNoteData): Boolean {
        val sentence = data.cloze?.sentence ?: return false
        return data.sentenceFurigana == null && sessionLanguage == "ja" && sentence.isNotEmpty() &&
                sentence.length <= CustomActionContext.MAX_READINGS_TEXT_LENGTH
    }

    /**
     * Whether rendering [fieldNames] of [fields] with [data] would ask Dokuen for the cloze
     * sentence's furigana first. That is the case when a field uses `{sentence-furigana}` or
     * `{sentence-furigana-plain}` and canRequestSentenceFurigana accepts [data].
     */
    private fun needsSentenceFurigana(
        data: AnkiNoteData,
        fieldNames: List<String>,
        fields: Map<String, AnkiField>
    ): Boolean = canRequestSentenceFurigana(data) && AnkiSentenceFurigana.isUsed(fieldNames, fields)

    /**
     * The images a note write got: their AnkiDroid file names, the markers whose image could not
     * be stored, the markers left empty because the host denied images (the user has no Dokuen
     * Pro), and the clipboard text.
     */
    private data class AnkiMedia(
        val screenshotFileName: String? = null,
        val clipboardImageFileName: String? = null,
        val failedMarkers: List<String> = emptyList(),
        val deniedMarkers: List<String> = emptyList(),
        val clipboardText: String? = null
    ) {
        val isEmpty: Boolean
            get() = screenshotFileName == null && clipboardImageFileName == null && clipboardText == null
    }

    /**
     * Requests from the host, and stores in AnkiDroid, the images the write will use (see
     * AnkiMediaNeeds), the screenshot first. Returns null when the user cancelled, in which
     * case nothing further is requested and nothing may be written. Once the host denies one
     * image it would deny the rest, so the remaining images are not requested and count as
     * denied too. Then requests the clipboard text if the write uses it; the host does not gate
     * text on Dokuen Pro, so this happens even when images were denied.
     */
    private suspend fun acquireAnkiMedia(
        actionContext: CustomActionContext,
        settings: AnkiSettings,
        format: AnkiCardFormat,
        fieldNames: List<String>,
        existing: AnkiNoteInfo?
    ): AnkiMedia? {
        // One timestamp per note, as the original names all of a note's media at once.
        val timestamp = System.currentTimeMillis()
        var media = AnkiMedia()
        val failed = mutableListOf<String>()
        val denied = mutableListOf<String>()
        for (marker in AnkiMediaNeeds.IMAGE_MARKERS) {
            if (!AnkiMediaNeeds.isNeeded(marker, fieldNames, format.fields, existing)) continue
            if (denied.isNotEmpty()) {
                denied += marker
                continue
            }
            val isScreenshot = marker == AnkiMediaNeeds.SCREENSHOT
            val source = if (isScreenshot) ImageRequestSource.SCREENSHOT else ImageRequestSource.CLIPBOARD
            val interactive = isScreenshot && settings.screenshotBehavior == AnkiScreenshotBehavior.CROP
            when (val result = actionContext.requestImage(source, interactive)) {
                ImageRequestResult.Cancelled -> return null
                ImageRequestResult.NoImage -> Unit
                ImageRequestResult.Denied -> denied += marker
                is ImageRequestResult.Image -> {
                    val fileName = try {
                        storeAnkiImage(result.bitmap, isScreenshot, settings, timestamp)
                    } finally {
                        result.bitmap.recycle()
                    }
                    when {
                        fileName == null -> failed += marker
                        isScreenshot -> media = media.copy(screenshotFileName = fileName)
                        else -> media = media.copy(clipboardImageFileName = fileName)
                    }
                }
            }
        }
        if (AnkiMediaNeeds.isNeeded(AnkiMediaNeeds.CLIPBOARD_TEXT, fieldNames, format.fields, existing)) {
            // The original reads the clipboard by pasting into a <textarea>, whose value has every
            // CRLF and lone CR turned into LF.
            val text = actionContext.requestClipboardText()?.replace("\r\n", "\n")?.replace('\r', '\n')
            media = media.copy(clipboardText = text)
        }
        return media.copy(failedMarkers = failed, deniedMarkers = denied)
    }

    /**
     * Encodes [bitmap] and stores it in AnkiDroid, returning the stored name or null. A screenshot follows
     * the image format setting. A clipboard image is always PNG: the original stores it in the
     * clipboard's own format, and the clipboard image it reads (a pasted `data:` URL) is a PNG.
     */
    private fun storeAnkiImage(
        bitmap: Bitmap,
        isScreenshot: Boolean,
        settings: AnkiSettings,
        timestamp: Long
    ): String? {
        val format = if (isScreenshot) settings.screenshotFormat else AnkiScreenshotFormat.PNG
        val (compressFormat, extension) = when (format) {
            AnkiScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG to ".png"
            AnkiScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG to ".jpeg"
        }
        // Bitmap.compress throws for a quality outside 0..100, which a hand-edited settings file
        // could hold.
        val quality = settings.screenshotQuality.coerceIn(0, 100)
        // Not the original's "yomitan_browser_screenshot" / "yomitan_clipboard_image": the images
        // come from Dokuen, not from a browser tab.
        val prefix = if (isScreenshot) "dokuen_screenshot" else "dokuen_clipboard_image"
        // Encoding happens inside the try too, so any failure only loses this image, not the note.
        return try {
            ankiDroidClient.addImage(AnkiMediaFileNames.generate(prefix, extension, timestamp)) { out ->
                bitmap.compress(compressFormat, quality, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not store an image in AnkiDroid", e)
            null
        }
    }

    /**
     * Result of a successful write. The tapped link is relabeled ("Added" or "Overwritten") and
     * colored by [statusAfterWrite], every other link of the entry is colored by a fresh
     * duplicate check (see checkOtherAnkiLinks), and the lookup's entries are returned to show
     * them, along with a warning for unknown markers, images AnkiDroid did not accept, or images
     * the host denied. If the lookup is no longer tracked, the note was still written, so the
     * warning or a plain confirmation is returned. A host that would drop an UpdateResult's
     * message (see hostShowsUpdateMessages) gets the warning on its own instead, and the new
     * labels and colors show the next time a tap refreshes the lookup.
     */
    private suspend fun finishAnkiWrite(
        ref: AnkiLinkRef,
        context: AnkiLinkContext,
        formatIndex: Int,
        added: Boolean,
        statusAfterWrite: AnkiLinkStatus,
        fieldNames: List<String>,
        unknownMarkers: List<String>,
        failedMediaMarkers: List<String>,
        deniedImageMarkers: List<String>
    ): CustomActionResult {
        val format = context.formats[formatIndex]
        val write = if (added) AnkiNoteWrite.ADDED else AnkiNoteWrite.OVERWRITTEN
        val otherStatuses = if (ankiLookups.containsKey(ref.token)) {
            checkOtherAnkiLinks(context, formatIndex, mutableMapOf(format.modelId to fieldNames))
        } else {
            emptyMap()
        }
        val entries = updateAnkiLinks(ref) {
            it.writes[formatIndex] = write
            it.statuses[formatIndex] = statusAfterWrite
            it.statuses.putAll(otherStatuses)
        }
        val warnings = buildList {
            if (unknownMarkers.isNotEmpty()) {
                add(
                    getString(
                        if (added) R.string.anki_note_added_unknown_markers else R.string.anki_note_updated_unknown_markers,
                        format.name,
                        unknownMarkers.joinToString(", ") { "{$it}" }
                    )
                )
            }
            if (failedMediaMarkers.isNotEmpty()) {
                add(
                    getString(
                        if (added) R.string.anki_note_added_media_failed else R.string.anki_note_updated_media_failed,
                        failedMediaMarkers.joinToString(", ") { "{$it}" }
                    )
                )
            }
            if (deniedImageMarkers.isNotEmpty()) {
                val markers = deniedImageMarkers.map { "{$it}" }
                // There are only two image markers.
                add(
                    if (markers.size == 1) {
                        getString(
                            if (added) R.string.anki_note_added_image_denied else R.string.anki_note_updated_image_denied,
                            markers[0]
                        )
                    } else {
                        getString(
                            if (added) R.string.anki_note_added_images_denied else R.string.anki_note_updated_images_denied,
                            markers[0],
                            markers[1]
                        )
                    }
                )
            }
        }
        val warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
        if (entries != null && (warning == null || hostShowsUpdateMessages)) {
            return CustomActionResult.UpdateResult(DictionaryResult(entries = entries), warning)
        }
        return CustomActionResult.SuccessMessage(
            warning ?: getString(if (added) R.string.anki_note_added else R.string.anki_note_updated)
        )
    }

    override fun onShutdown() {
        Log.d(TAG, "Shutting down service connection")
        ankiLookups.clear()
    }
}
