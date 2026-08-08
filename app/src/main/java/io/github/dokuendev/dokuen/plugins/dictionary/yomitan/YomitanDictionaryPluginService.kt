package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.os.Bundle
import android.util.Log
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.JapaneseDeinflector
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.CssProcessor
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.YomitanRenderer
import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.DictionaryErrorCode
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.dictionary.DictionaryPluginService
import io.github.dokuendev.dokuenreader.dictionary.DictionaryResult
import io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText
import io.github.dokuendev.dokuenreader.plugin.core.InitResult
import io.github.dokuendev.dokuenreader.plugin.core.InitResultFactory
import io.github.dokuendev.dokuenreader.plugin.core.PluginCapabilityKeys
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys

class YomitanDictionaryPluginService : DictionaryPluginService() {

    companion object {
        private const val TAG = "YomitanService"
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
    private lateinit var db: AppDatabase
    private lateinit var renderer: YomitanRenderer
    private val deinflector = JapaneseDeinflector()

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

            val cssProcessor = CssProcessor()
            renderer = YomitanRenderer(db, cssProcessor)

            val prefs = applicationContext.getSharedPreferences("yomitan_prefs", MODE_PRIVATE)
            val joined = prefs.getString("active_dictionaries", "") ?: ""
            activeDictionaryTitles = joined.split(",").filter { it.isNotEmpty() }
            sortFrequencyDictionary = prefs.getString("sort_frequency_dictionary", "") ?: ""
            sortFrequencyDictionaryOrder =
                prefs.getString("sort_frequency_dictionary_order", "descending") ?: "descending"

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

            var entries = if (contextText.startsWith("?")) {
                lookupInternalLink(contextText, activeDictionaryTitles)
            } else {
                val minLen = maxOf(1, cursorEndIndex - start)
                scanAndDeinflectLookup(contextText, start, maxLen, minLen, activeDictionaryTitles)
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
                    entries = renderKanjiEntries(kanjiChar, activeDictionaryTitles)
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
     * Perform a direct kanji-dictionary lookup for a single character.
     *
     * This is the port of display-generator.js::_setContentTermsOrKanji('kanji', char):
     * it skips the term path entirely and queries the kanji store directly. The result
     * mirrors the JS behavior where clicking a headword kanji opens the kanji entry
     * regardless of whether a term entry also exists for that character.
     *
     * Called from lookupInternalLink when the parsed query params contain a "kanji" key,
     * which is produced by buildKanjiHeadwordSpans in TermEntryRenderer (link URL format:
     * "lookup:?kanji=<char>").
     *
     * Returns an empty list (not an exception) when no kanji entry is found so that
     * onLookup can throw the standard WORD_NOT_FOUND exception with a proper message.
     * If no kanji dictionary is installed or enabled at all, returns an informational
     * entry instead.
     *
     * @param character The single kanji character to look up (e.g. "日")
     * @param activeDictionaries The currently enabled dictionaries, in priority order
     */
    private suspend fun lookupKanjiDirect(
        character: String,
        activeDictionaries: List<String>
    ): List<DictionaryEntry> {
        if (character.isEmpty()) return emptyList()

        // If no kanji dictionary is installed or active, show a message
        val hasAnyKanjiDictionary = activeDictionaries.any { title ->
            db.dictionaryDao().countKanji(title) > 0
        }
        if (!hasAnyKanjiDictionary) {
            return listOf(buildNoKanjiDictionaryEntry(character))
        }

        return renderKanjiEntries(character, activeDictionaries)
    }

    /**
     * Renders one entry per kanji dictionary row for [character], in the user's dictionary
     * priority order, or an empty list if no active dictionary has it.
     *
     * Mirrors the original's Translator.findKanji, which makes each database row its own
     * KanjiDictionaryEntry and sorts them by dictionary index: entries from different kanji
     * dictionaries are shown separately, never merged into one. sortedBy is stable, so rows
     * from the same dictionary keep their database order, as in the original.
     */
    private suspend fun renderKanjiEntries(
        character: String,
        activeDictionaries: List<String>
    ): List<DictionaryEntry> {
        val kanjiMatches = db.kanjiDao().findByCharacterBulk(listOf(character), activeDictionaries)
        val sortedMatches = kanjiMatches.sortedBy { match ->
            val idx = activeDictionaries.indexOf(match.dictionary)
            if (idx != -1) idx else Int.MAX_VALUE
        }
        return sortedMatches.map { match ->
            val dictEntity = db.dictionaryDao().getDictionaryByTitle(match.dictionary)
            val styles = dictEntity?.styles ?: ""
            renderer.renderKanjiEntry(match, styles, activeDictionaries)
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
        activeDictionaries: List<String>
    ): List<DictionaryEntry> {
        val params = parseQueryParams(contextText)

        // ?kanji=<char> - direct kanji-dictionary lookup.
        // Produced by buildKanjiHeadwordSpans in TermEntryRenderer when the user taps
        // a kanji character in a term headword. Routes straight to the kanji store,
        // bypassing the term path entirely. See _setContentTermsOrKanji('kanji', char).
        val kanjiChar = params["kanji"]
        if (kanjiChar != null) {
            return lookupKanjiDirect(kanjiChar, activeDictionaries)
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

                // If deinflection rules were applied, prepend them to the body as a custom badge
                val finalEntry = if (primaryMatch.deinflectionRules.isNotEmpty()) {
                    decorateDeinflection(entry, primaryMatch.deinflectionRules)
                } else {
                    entry
                }
                renderedEntries.add(finalEntry)
            }

            entries.add(
                mergeDictionaryEntries(
                    group.expression,
                    renderedEntries.firstOrNull()?.pronunciation,
                    renderedEntries.firstOrNull()?.headwordSpans,
                    renderedEntries
                )
            )
        }

        return entries
    }

    private suspend fun scanAndDeinflectLookup(
        contextText: String,
        start: Int,
        maxLen: Int,
        minLen: Int,
        activeDictionaries: List<String>
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

                // If deinflection rules were applied, prepend them to the body as a custom badge
                val finalEntry = if (primaryMatch.deinflectionRules.isNotEmpty()) {
                    decorateDeinflection(entry, primaryMatch.deinflectionRules)
                } else {
                    entry
                }
                renderedEntries.add(finalEntry)
            }

            val mergedEntry =
                mergeDictionaryEntries(
                    group.expression,
                    renderedEntries.firstOrNull()?.pronunciation,
                    renderedEntries.firstOrNull()?.headwordSpans,
                    renderedEntries
                )
            entries.add(mergedEntry)
        }
        return entries
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

    override fun onShutdown() {
        Log.d(TAG, "Shutting down service connection")
    }
}
