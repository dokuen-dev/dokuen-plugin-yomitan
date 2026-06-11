package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.os.Bundle
import android.util.Log
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
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

        private val deinflectionDescriptions = mapOf(
            "-(ら)れる" to "Indicates a state of being (naturally) capable of doing an action.\nUsage: Attach (ら)れる to the irrealis form (未然形) of ichidan verbs.\nAttach る to the imperative form (命令形) of godan verbs.\nする becomes できる, くる becomes こ(ら)れる",
            "-いる" to "1. Indicates an action continues or progresses to a point in time.\n2. Indicates an action is completed and remains as is.\n3. Indicates a state or condition that can be taken to be the result of undergoing some change.\nUsage: Attach いる to the て-form of verbs. い can be dropped in speech.\nAttach でいる after ない negative form of verbs.\n(Slang) Attach おる to the て-form of verbs. Contracts to とる・でる in speech.",
            "-う・よう" to "1. Expresses speaker's will or intention.\n2. Expresses an invitation to the other party.\n3. (Used in …ようとする) Indicates being on the verge of initiating an action or transforming a state.\n4. Indicates an inference of a matter.\nUsage: Attach よう to the irrealis form (未然形) of ichidan verbs.\nAttach う to the irrealis form (未然形) of godan verbs after -o euphonic change form.\nAttach かろう to the stem of i-adjectives (4th meaning only).",
            "-え" to "Slang. A sound change of i-adjectives.\nai：やばい → やべぇ\nui：さむい → さみぃ/さめぇ\noi：すごい → すげぇ",
            "-おく" to "To do certain things in advance in preparation (or in anticipation) of latter needs.\nUsage: Attach おく to the て-form of verbs.\nAttach でおく after ない negative form of verbs.\nContracts to とく・どく in speech.",
            "-がる" to "1. Shows subject’s feelings contrast with what is thought/known about them.\n2. Indicates subject's behavior (stands out).\nUsage: Attach がる to the stem of i-adjectives. It itself conjugates as a godan verb.",
            "-き" to "Attributive form (連体形) of i-adjectives. An archaic form that remains in modern Japanese.",
            "-く" to "Adverbial form of i-adjectives.\n",
            "-げ" to "Describes a person's appearance. Shows feelings of the person.\nUsage: Attach げ or 気 to the stem of i-adjectives",
            "-さ" to "Nominalizing suffix of i-adjectives indicating nature, state, mind or degree.\nUsage: Attach さ to the stem of i-adjectives.",
            "-ざる" to "Negative form of verbs.\nUsage: Attach ざる to the irrealis form (未然形) of verbs.\nする becomes せざる",
            "-しまう" to "1. Shows a sense of regret/surprise when you did have volition in doing something, but it turned out to be bad to do.\n2. Shows perfective/punctual achievement. This shows that an action has been completed.\n3. Shows unintentional action–“accidentally”.\nUsage: Attach しまう after the て-form of verbs.",
            "-すぎる" to "Shows something \"is too...\" or someone is doing something \"too much\".\nUsage: Attach すぎる to the continuative form (連用形) of verbs, or to the stem of adjectives.",
            "-す・さす" to "Contraction of the causative form.\nDescribes the intention to make someone do something.\nUsage: Attach す to the irrealis form (未然形) of godan verbs.\nAttach さす to the dictionary form (終止形) of ichidan verbs.\nする becomes さす, くる becomes こさす.\nIt itself conjugates as an godan verb.",
            "-ず" to "1. Negative form of verbs.\n2. Continuative form (連用形) of the particle ぬ (nu).\nUsage: Attach ず to the irrealis form (未然形) of verbs.",
            "-せる・させる" to "Describes the intention to make someone do something.\nUsage: Attach させる to the irrealis form (未然形) of ichidan verbs and くる.\nAttach せる to the irrealis form (未然形) of godan verbs and する.\nIt itself conjugates as an ichidan verb.",
            "-そう" to "Appearing that; looking like.\nUsage: Attach そう to the continuative form (連用形) of verbs, or to the stem of adjectives.",
            "-た" to "1. Indicates a reality that has happened in the past.\n2. Indicates the completion of an action.\n3. Indicates the confirmation of a matter.\n4. Indicates the speaker's confidence that the action will definitely be fulfilled.\n5. Indicates the events that occur before the main clause are represented as relative past.\n6. Indicates a mild imperative/command.\nUsage: Attach た to the continuative form (連用形) of verbs after euphonic change form, かった to the stem of i-adjectives.",
            "-たい" to "1. Expresses the feeling of desire or hope.\n2. Used in ...たいと思います, an indirect way of saying what the speaker intends to do.\nUsage: Attach たい to the continuative form (連用形) of verbs. たい itself conjugates as i-adjective.",
            "-たら" to "1. Denotes the latter stated event is a continuation of the previous stated event.\n2. Assumes that a matter has been completed or concluded.\nUsage: Attach たら to the continuative form (連用形) of verbs after euphonic change form, かったら to the stem of i-adjectives.",
            "-たり" to "1. Shows two actions occurring back and forth (when used with two verbs).\n2. Shows examples of actions and states (when used with multiple verbs and adjectives).\nUsage: Attach たり to the continuative form (連用形) of verbs after euphonic change form, かったり to the stem of i-adjectives",
            "-ちまう" to "Contraction of -しまう.\n1. Shows a sense of regret/surprise when you did have volition in doing something, but it turned out to be bad to do.\n2. Shows perfective/punctual achievement. This shows that an action has been completed.\n3. Shows unintentional action–“accidentally”.\nUsage: Attach しまう after the て-form of verbs, contract てしまう into ちまう.",
            "-ちゃ" to "Contraction of ～ては.\n1. Explains how something always happens under the condition that it marks.\n2. Expresses the repetition (of a series of) actions.\n3. Indicates a hypothetical situation in which the speaker gives a (negative) evaluation about the other party's intentions.\n4. Used in \"Must Not\" patterns like ～てはいけない.\nUsage: Attach は after the て-form of verbs, contract ては into ちゃ.",
            "-ちゃう" to "Contraction of -しまう.\n1. Shows a sense of regret/surprise when you did have volition in doing something, but it turned out to be bad to do.\n2. Shows perfective/punctual achievement. This shows that an action has been completed.\n3. Shows unintentional action–“accidentally”.\nUsage: Attach しまう after the て-form of verbs, contract てしまう into ちゃう.",
            "-っか・よっか" to "Contraction of volitional form + か\n1. Expresses speaker's will or intention.\n2. Expresses an invitation to the other party.\nUsage: Replace final う with っ of volitional form then add ka.\nFor example: 行こうか -> 行こっか.",
            "-て" to "て-form.\nIt has a myriad of meanings. Primarily, it is a conjunctive particle that connects two clauses together.\nUsage: Attach て to the continuative form (連用形) of verbs after euphonic change form, くて to the stem of i-adjectives.",
            "-ない" to "1. Negative form of verbs.\n2. Expresses a feeling of solicitation to the other party.\nUsage: Attach ない to the irrealis form (未然形) of verbs, くない to the stem of i-adjectives. ない itself conjugates as i-adjective. ます becomes ません.",
            "-なさい" to "Polite imperative suffix.\nUsage: Attach なさい after the continuative form (連用形) of verbs.",
            "-ぬ" to "Negative form of verbs.\nUsage: Attach ぬ to the irrealis form (未然形) of verbs.\nする becomes せぬ",
            "-ねば" to "1. Shows a hypothetical negation; if not ...\n2. Shows a must. Used with or without ならぬ.\nUsage: Attach ねば to the irrealis form (未然形) of verbs.\nする becomes せねば",
            "-ば" to "1. Conditional form; shows that the previous stated condition's establishment is the condition for the latter stated condition to occur.\n2. Shows a trigger for a latter stated perception or judgment.\nUsage: Attach ば to the hypothetical form (仮定形) of verbs and i-adjectives.",
            "-まい" to "Negative volitional form of verbs.\n1. Expresses speaker's assumption that something is likely not true.\n2. Expresses speaker's will or intention not to do something.\nUsage: Attach まい to the dictionary form (終止形) of verbs.\nAttach まい to the irrealis form (未然形) of ichidan verbs.\nする becomes しまい, くる becomes こまい",
            "-ます" to "Polite conjugation of verbs and adjectives.\nUsage: Attach ます to the continuative form (連用形) of verbs.",
            "-む" to "Archaic.\n1. Shows an inference of a certain matter.\n2. Shows speaker's intention.\nUsage: Attach む to the irrealis form (未然形) of verbs.\nする becomes せむ",
            "-ゃ" to "Contraction of -ば.",
            "-られる" to "1. Indicates an action received from an action performer.\n2. Expresses respect for the subject of action performer.\n3. Indicates a state of being (naturally) capable of doing an action.\nUsage: Attach られる to the irrealis form (未然形) of ichidan verbs.\nする becomes せられる, くる becomes こられる",
            "-れる" to "1. Indicates an action received from an action performer.\n2. Expresses respect for the subject of action performer.\nUsage: Attach れる to the irrealis form (未然形) of godan verbs.",
            "-ん" to "Negative form of verbs; a sound change of ぬ.\nUsage: Attach ん to the irrealis form (未然形) of verbs.\nする becomes せん",
            "-んとする" to "1. Shows the speaker's will or intention.\n2. Shows an action or condition is on the verge of occurring.\nUsage: Attach んとする to the irrealis form (未然形) of verbs.\nする becomes せんとする",
            "-んな" to "Slang sound change of r-column syllables to n (when before an n-sound, usually の or な)",
            "-んばかり" to "Shows an action or condition is on the verge of occurring, or an excessive/extreme degree.\nUsage: Attach んばかり to the irrealis form (未然形) of verbs.\nする becomes せんばかり",
            "-過ぎる" to "Shows something \"is too...\" or someone is doing something \"too much\".\nUsage: Attach 過ぎる to the continuative form (連用形) of verbs, or to the stem of adjectives.",
            "命令形" to "1. To give orders.\n2. (As あれ) Represents the fact that it will never change no matter the circumstances.\n3. Express a feeling of hope.",
            "連用形" to "Used to indicate actions that are (being) carried out.\nRefers to 連用形, the part of the verb after conjugating with -ます and dropping ます.",
            "関西弁" to "Negative form of kansai-ben verbs"
        )
    }

    override val configActivityName = ".MainActivity"

    override fun isConfigured(): Boolean {
        val prefs = applicationContext.getSharedPreferences("yomitan_prefs", MODE_PRIVATE)
        val activeJoined = prefs.getString("active_dictionaries", "") ?: ""
        return activeJoined.split(",").any { it.isNotEmpty() }
    }

    private var activeDictionaryTitles: List<String> = emptyList()
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
                    val kanjiMatches = db.kanjiDao().findByCharacterBulk(listOf(kanjiChar), activeDictionaryTitles)
                    if (kanjiMatches.isNotEmpty()) {
                        val sortedKanjiMatches = kanjiMatches.sortedBy { match ->
                            val dictIndex = activeDictionaryTitles.indexOf(match.dictionary)
                            if (dictIndex != -1) dictIndex else Int.MAX_VALUE
                        }
                        val kanjiEntries = sortedKanjiMatches.map { match ->
                            val dictEntity = db.dictionaryDao().getDictionaryByTitle(match.dictionary)
                            val styles = dictEntity?.styles ?: ""
                            renderer.renderKanjiEntry(match, styles)
                        }
                        entries = listOf(mergeDictionaryEntries(kanjiChar, null, null, kanjiEntries))
                    }
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
        val deinflectionRules: List<String>
    )

    private class TermGroup(
        val expression: String,
        val reading: String,
        val items: MutableList<TermLookupMatch> = mutableListOf()
    ) {
        val maxMatchedLength: Int get() = items.maxOf { it.matchedLength }
        val minDeinflectionRulesCount: Int get() = items.minOf { it.deinflectionRulesCount }
        val minDictionaryIndex: Int get() = items.minOf { it.dictionaryIndex }
        val maxScore: Double get() = items.maxOf { it.term.score }
        val maxSequence: Long get() = items.maxOf { it.term.sequence ?: -1L }
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

        return DictionaryEntry(
            headword = headword,
            pronunciation = pronunciation,
            headwordSpans = headwordSpans,
            body = StyledText(
                text = textBuilder.toString(),
                styledSpans = styledSpans.toTypedArray(),
                rubySpans = rubySpans.toTypedArray(),
                blockSpans = blockSpansArray
            )
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

        val kanjiMatches = db.kanjiDao().findByCharacterBulk(listOf(character), activeDictionaries)
        if (kanjiMatches.isEmpty()) return emptyList()

        // Sort by the user's dictionary priority order (same as the fallback in onLookup)
        val sortedMatches = kanjiMatches.sortedBy { match ->
            val idx = activeDictionaries.indexOf(match.dictionary)
            if (idx != -1) idx else Int.MAX_VALUE
        }

        val kanjiEntries = sortedMatches.map { match ->
            val dictEntity = db.dictionaryDao().getDictionaryByTitle(match.dictionary)
            val styles = dictEntity?.styles ?: ""
            renderer.renderKanjiEntry(match, styles)
        }

        return listOf(mergeDictionaryEntries(character, null, null, kanjiEntries))
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

        val sortedGroups = groupsMap.values.sortedWith(
            compareBy<TermGroup> { it.minDictionaryIndex }
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
                val entry = renderer.renderTermEntries(terms, styles)

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

            deinflections.forEach { deinflection ->
                val candidate = deinflection.term
                // Query both Expression and Reading matches and merge them to avoid omitting homophones
                val expMatches = db.termDao().findByExpression(candidate, activeDictionaries)
                Log.d(TAG, "    Candidate '$candidate': Expression query returned ${expMatches.size} matches")

                val readMatches = db.termDao().findByReading(candidate, activeDictionaries)
                Log.d(TAG, "    Candidate '$candidate': Reading query returned ${readMatches.size} matches")

                val matches = (expMatches + readMatches).distinctBy { it.id }

                for (match in matches) {
                    if (processedTermIds.contains(match.id)) continue
                    processedTermIds.add(match.id)

                    val dictIndex = activeDictionaries.indexOf(match.dictionary)
                    val resolvedDictIndex = if (dictIndex != -1) dictIndex else Int.MAX_VALUE

                    matchesList.add(
                        TermLookupMatch(
                            term = match,
                            matchedLength = substring.length,
                            deinflectionRulesCount = deinflection.rules.size,
                            dictionaryIndex = resolvedDictIndex,
                            deinflectionRules = deinflection.rules
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

        val sortedGroups = groupsMap.values.sortedWith(
            compareByDescending<TermGroup> { it.maxMatchedLength }
                .thenBy { it.minDeinflectionRulesCount }
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
                val entry = renderer.renderTermEntries(terms, styles)

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
        rules: List<String>
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

        for (i in rules.indices) {
            val rule = rules[i]
            val separator = if (i == 0) " " else " → "
            val prevEnd = builder.length
            builder.append(separator)

            val start = builder.length
            builder.append(rule)
            val end = builder.length

            val description = deinflectionDescriptions[rule]

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
