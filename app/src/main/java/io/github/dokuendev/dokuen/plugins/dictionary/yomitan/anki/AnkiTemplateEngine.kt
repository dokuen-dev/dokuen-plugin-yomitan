package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlin.math.floor

/**
 * Resolves `{marker}` tokens in a card format's field value against [AnkiNoteData].
 *
 * Each supported marker reproduces the output of the matching partial in the original
 * Yomitan's default field templates (ext/data/templates/default-anki-field-templates.
 * handlebars) under its default options: "group" result output mode, non-compact tags,
 * and non-compact glossaries. Values the original prints with `{{...}}` are HTML-escaped the way
 * Handlebars escapes them; values it prints raw (`{{{...}}}` or helpers returning safe
 * strings) are left as-is.
 */
object AnkiTemplateEngine {
    private val markerPattern = Regex("\\{([\\p{L}\\p{N}_\\-]+)\\}")

    data class FieldRenderResult(val text: String, val unknownMarkers: List<String>)

    private const val GLOSSARY_DIV_OPEN = "<div style=\"text-align: left;\" class=\"yomitan-glossary\">"
    private const val GLOSSARY_DIV_CLOSE = "</div>"

    /** The `frequencyMode` values a dictionary's index.json can declare. */
    private const val RANK_BASED = "rank-based"
    private const val OCCURRENCE_BASED = "occurrence-based"

    /**
     * Standard markers whose backing subsystem (audio, pitch accent data, browser page
     * context) does not exist in this port.
     */
    private val stubMarkers: Set<String> = setOf(
        "audio",
        "pitch-accents", "pitch-accent-graphs", "pitch-accent-graphs-jj",
        "pitch-accent-positions", "pitch-accent-categories",
        "phonetic-transcriptions", "popup-selection-text",
        "url", "document-title"
    )

    private val supportedMarkers: Map<String, (AnkiNoteData) -> String> = mapOf(
        "expression" to { d -> escape(d.expression) },
        "reading" to { d -> escape(d.reading) },
        "furigana" to { d -> furiganaHtml(d.expression, d.reading) },
        "furigana-plain" to { d -> escape(furiganaPlain(d.expression, d.reading)) },
        "character" to { d -> escape(d.character) },
        "dictionary" to { d -> escape(d.dictionary) },
        "dictionary-alias" to { d -> escape(d.dictionaryAlias) },
        "tags" to { d -> mergeTags(d) },
        "part-of-speech" to { d -> partOfSpeech(d) },
        "conjugation" to { d -> conjugation(d) },
        "kunyomi" to { d -> d.kunyomi.joinToString(", ") { escape(it) } },
        "onyomi" to { d -> d.onyomi.joinToString(", ") { escape(it) } },
        "onyomi-hiragana" to { d ->
            d.onyomi.joinToString(", ") { escape(AnkiJapanese.convertKatakanaToHiragana(it)) }
        },
        "stroke-count" to { d -> "Stroke count: " + (d.strokeCount?.let { escape(it) } ?: "Unknown") },
        // The original's `screenshot` and `clipboard-image` partials: an <img> around the
        // stored media file's name, or nothing when there is no image.
        "screenshot" to { d -> d.screenshotFileName?.let { imageTag(it) } ?: "" },
        "clipboard-image" to { d -> d.clipboardImageFileName?.let { imageTag(it) } ?: "" },
        // The original's `clipboard-text` partial, `{{{getMedia "clipboardText"}}}`: getMedia
        // escapes the value and turns each line break into `<br>` plus the line break.
        "clipboard-text" to { d -> d.clipboardText?.let { escape(it).replace("\n", "<br>\n") } ?: "" },
        "sentence" to { d -> d.cloze?.sentence ?: "" },
        // No furigana source is available yet (see docs/anki-export.md), so these print the
        // plain sentence, which is what the original's partials print when their furigana
        // data is missing.
        "sentence-furigana" to { d -> d.cloze?.sentence ?: "" },
        "sentence-furigana-plain" to { d -> d.cloze?.sentence ?: "" },
        "cloze-prefix" to { d -> d.cloze?.prefix ?: "" },
        "cloze-body" to { d -> d.cloze?.body ?: "" },
        "cloze-body-kana" to { d -> d.cloze?.bodyKana ?: "" },
        "cloze-suffix" to { d -> d.cloze?.suffix ?: "" },
        // The original's multiLine helper around an escaped `{{context.fullQuery}}`.
        "search-query" to { d -> escape(d.searchQuery).replace("\n", "<br>") },
        "frequencies" to { d -> frequencies(d) },
        "single-frequency-number" to { d -> frequencyNumbers(d).joinToString("") { formatNumber(it) } },
        "frequency-harmonic-rank" to { d ->
            frequencyHarmonic(d, RANK_BASED).let { if (it == -1L) "9999999" else it.toString() }
        },
        "frequency-harmonic-occurrence" to { d ->
            frequencyHarmonic(d, OCCURRENCE_BASED).let { if (it == -1L) "0" else it.toString() }
        },
        "frequency-average-rank" to { d ->
            frequencyAverage(d, RANK_BASED).let { if (it == -1L) "9999999" else it.toString() }
        },
        "frequency-average-occurrence" to { d ->
            frequencyAverage(d, OCCURRENCE_BASED).let { if (it == -1L) "0" else it.toString() }
        },
        "glossary" to { d -> glossary(d, brief = false, noDictionaryTag = false) },
        "glossary-brief" to { d -> glossary(d, brief = true, noDictionaryTag = false) },
        "glossary-no-dictionary" to { d -> glossary(d, brief = false, noDictionaryTag = true) },
        "glossary-first" to { d -> glossaryFirst(d, brief = false, noDictionaryTag = false) },
        "glossary-first-brief" to { d -> glossaryFirst(d, brief = true, noDictionaryTag = false) },
        "glossary-first-no-dictionary" to { d -> glossaryFirst(d, brief = false, noDictionaryTag = true) },
        "glossary-plain" to { d -> glossaryPlain(d, noDictionaryTag = false) },
        "glossary-plain-no-dictionary" to { d -> glossaryPlain(d, noDictionaryTag = true) }
    )

    fun renderField(template: String, data: AnkiNoteData): FieldRenderResult {
        val unknown = mutableListOf<String>()
        val text = markerPattern.replace(template) { match ->
            val marker = match.groupValues[1]
            when {
                supportedMarkers.containsKey(marker) -> supportedMarkers.getValue(marker)(data)
                stubMarkers.contains(marker) -> ""
                else -> {
                    unknown.add(marker)
                    "{$marker-render-error}"
                }
            }
        }
        return FieldRenderResult(text, unknown)
    }

    /**
     * The original's `<img src="{{getMedia ...}}" />`: getMedia returns the escaped name as a
     * plain string, which the double-brace output escapes a second time.
     */
    private fun imageTag(fileName: String): String = "<img src=\"${escape(escape(fileName))}\" />"

    /** Handlebars' escapeExpression, applied by the original to every `{{value}}` output. */
    internal fun escape(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#x27;")
                '`' -> sb.append("&#x60;")
                '=' -> sb.append("&#x3D;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun furiganaHtml(expression: String, reading: String): String {
        val sb = StringBuilder()
        for (segment in AnkiJapanese.distributeFurigana(expression, reading)) {
            if (segment.reading.isNotEmpty()) {
                sb.append("<ruby>").append(segment.text).append("<rt>").append(segment.reading).append("</rt></ruby>")
            } else {
                sb.append(segment.text)
            }
        }
        return sb.toString()
    }

    private fun furiganaPlain(expression: String, reading: String): String {
        val sb = StringBuilder()
        for (segment in AnkiJapanese.distributeFurigana(expression, reading)) {
            if (segment.reading.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(segment.text).append('[').append(segment.reading).append(']')
            } else {
                sb.append(segment.text)
            }
        }
        return sb.toString()
    }

    /** The original's mergeTags helper in group mode: headword tags, then each definition's tags. */
    private fun mergeTags(d: AnkiNoteData): String {
        if (d.type != AnkiEntryType.TERM) return ""
        val names = LinkedHashSet<String>()
        d.termTags.forEach { names.add(it.name) }
        d.definitions.forEach { definition -> definition.definitionTags.forEach { names.add(it.name) } }
        return names.joinToString(", ")
    }

    private fun partOfSpeechPretty(wordClass: String): String = when (wordClass) {
        "v1" -> "Ichidan verb"
        "v5" -> "Godan verb"
        "vk" -> "Kuru verb"
        "vs" -> "Suru verb"
        "vz" -> "Zuru verb"
        "adj-i" -> "I-adjective"
        "n" -> "Noun"
        else -> escape(wordClass)
    }

    /**
     * The original part-of-speech partial writes the ", " separator after an item only when
     * it is not the first one, so two word classes render as "AB, " rather than "A, B". That
     * quirk is reproduced so field values stay identical to the desktop extension's.
     */
    private fun partOfSpeech(d: AnkiNoteData): String {
        if (d.type == AnkiEntryType.KANJI) return ""
        val sb = StringBuilder()
        var first = true
        val used = HashSet<String>()
        for (wordClass in d.wordClasses) {
            if (!used.add(wordClass)) continue
            sb.append(partOfSpeechPretty(wordClass))
            if (!first) sb.append(", ")
            first = false
        }
        if (first) sb.append("Unknown")
        return sb.toString()
    }

    private fun conjugation(d: AnkiNoteData): String {
        val chains = d.inflectionRuleChains
        if (d.type != AnkiEntryType.TERM || chains.isEmpty()) return ""
        val multiple = chains.size > 1
        val sb = StringBuilder()
        if (multiple) sb.append("<ul>")
        for (chain in chains) {
            if (chain.isEmpty()) continue
            if (multiple) sb.append("<li>")
            chain.forEachIndexed { index, rule ->
                if (index > 0) sb.append(" « ")
                sb.append(escape(rule.name))
            }
            if (multiple) sb.append("</li>")
        }
        if (multiple) sb.append("</ul>")
        return sb.toString()
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()

    private fun frequencies(d: AnkiNoteData): String {
        if (d.frequencies.isEmpty()) return ""
        val sb = StringBuilder("<ul style=\"text-align: left;\">")
        for (item in d.frequencies) {
            sb.append("<li>")
                .append(escape(item.dictionaryAlias))
                .append(": ")
                .append(escape(item.displayValue ?: formatNumber(item.frequency)))
                .append("</li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    /**
     * The original's getFrequencyNumbers: the first value of each dictionary in turn. With a
     * [frequencyMode], a dictionary that declares a different mode is skipped and one that
     * declares none is kept.
     */
    private fun frequencyNumbers(d: AnkiNoteData, frequencyMode: String? = null): List<Double> {
        val results = mutableListOf<Double>()
        var previousDictionary: String? = null
        for (item in d.frequencies) {
            val wrongFrequencyMode =
                frequencyMode != null && item.frequencyMode != null && item.frequencyMode != frequencyMode
            if (item.dictionary == previousDictionary || wrongFrequencyMode) continue
            previousDictionary = item.dictionary
            val displayValue = item.displayValue
            if (displayValue != null) {
                val parsed = Regex("^\\d+").find(displayValue)?.value?.toLongOrNull()
                if (parsed != null && parsed > 0) {
                    results.add(parsed.toDouble())
                    continue
                }
            }
            if (item.frequency > 0) results.add(item.frequency)
        }
        return results
    }

    private fun frequencyHarmonic(d: AnkiNoteData, frequencyMode: String): Long {
        val numbers = frequencyNumbers(d, frequencyMode)
        if (numbers.isEmpty()) return -1
        val total = numbers.sumOf { 1.0 / it }
        return floor(numbers.size / total).toLong()
    }

    private fun frequencyAverage(d: AnkiNoteData, frequencyMode: String): Long {
        val numbers = frequencyNumbers(d, frequencyMode)
        if (numbers.isEmpty()) return -1
        return floor(numbers.sum() / numbers.size).toLong()
    }

    /** The original's glossary-single partial with compactTags and compactGlossaries off. */
    private fun glossarySingle(definition: AnkiDefinition, brief: Boolean, noDictionaryTag: Boolean): String {
        val sb = StringBuilder()
        if (!brief) {
            var any = false
            for (tag in definition.definitionTags) {
                sb.append(if (any) ", " else "<i>(").append(escape(tag.name))
                any = true
            }
            if (!noDictionaryTag) {
                sb.append(if (any) ", " else "<i>(").append(escape(definition.dictionaryAlias))
                any = true
            }
            if (any) sb.append(")</i> ")
        }
        if (definition.glossary.size <= 1) {
            definition.glossary.forEach { sb.append(it) }
        } else {
            sb.append("<ul>")
            definition.glossary.forEach { sb.append("<li>").append(it).append("</li>") }
            sb.append("</ul>")
        }
        return sb.toString()
    }

    private fun kanjiGlossaryBody(d: AnkiNoteData): String =
        if (d.glossary.size > 1) {
            "<ol>" + d.glossary.joinToString("") { "<li>${escape(it)}</li>" } + "</ol>"
        } else {
            d.glossary.joinToString("") { escape(it) }
        }

    /** The original's `{{#if ...ScopedStyles}}<style>{{{...ScopedStyles}}}</style>{{/if}}`. */
    private fun styleElement(css: String): String = if (css.isEmpty()) "" else "<style>$css</style>"

    private fun glossary(d: AnkiNoteData, brief: Boolean, noDictionaryTag: Boolean): String {
        val body = when (d.type) {
            // Each definition is followed by its dictionary's CSS, scoped to its own <li>.
            AnkiEntryType.TERM -> "<ol>" + d.definitions.joinToString("") { definition ->
                "<li data-dictionary=\"${escape(definition.dictionary)}\">" +
                        glossarySingle(definition, brief, noDictionaryTag) + "</li>" +
                        styleElement(definition.styles.dictionaryScoped)
            } + "</ol>"

            AnkiEntryType.KANJI -> kanjiGlossaryBody(d)
        }
        return GLOSSARY_DIV_OPEN + body + GLOSSARY_DIV_CLOSE
    }

    private fun glossaryFirst(d: AnkiNoteData, brief: Boolean, noDictionaryTag: Boolean): String {
        val body = when (d.type) {
            // The single definition has no <li>, so its CSS is scoped to the glossary only.
            AnkiEntryType.TERM -> d.definitions.firstOrNull()?.let {
                glossarySingle(it, brief, noDictionaryTag) + styleElement(it.styles.glossaryScoped)
            } ?: ""
            // The original glossary-first partial has no kanji branch.
            AnkiEntryType.KANJI -> ""
        }
        return GLOSSARY_DIV_OPEN + body + GLOSSARY_DIV_CLOSE
    }

    private fun glossaryPlain(d: AnkiNoteData, noDictionaryTag: Boolean): String {
        val sb = StringBuilder()
        when (d.type) {
            AnkiEntryType.TERM -> d.definitions.forEachIndexed { index, definition ->
                if (!noDictionaryTag) sb.append('(').append(escape(definition.dictionaryAlias)).append(")<br>")
                sb.append(definition.glossaryPlain.joinToString("<br>"))
                if (index < d.definitions.size - 1) sb.append("<br>")
            }

            AnkiEntryType.KANJI -> {
                if (!noDictionaryTag) sb.append('(').append(escape(d.dictionaryAlias)).append(")<br>")
                d.glossary.forEach { sb.append(it) }
            }
        }
        return sb.toString()
    }
}

/**
 * Ports of the furigana and kana helpers from the original's ext/js/language/ja/japanese.js
 * that the Anki templates depend on (distributeFurigana, distributeFuriganaInflected,
 * convertKatakanaToHiragana), kept faithful to the original's segmentation rules, including
 * its refusal to pick a split when a reading can be divided more than one way.
 */
internal object AnkiJapanese {
    data class FuriganaSegment(val text: String, val reading: String)

    private class FuriganaGroup(val isKana: Boolean, var text: String, var textNormalized: String? = null)

    private const val KATAKANA_SMALL_KA = 0x30F5
    private const val KATAKANA_SMALL_KE = 0x30F6
    private const val PROLONGED_SOUND_MARK = 0x30FC
    private const val HIRAGANA_CONVERSION_START = 0x3041
    private const val KATAKANA_CONVERSION_START = 0x30A1
    private const val KATAKANA_CONVERSION_END = 0x30F6

    private val kanaToVowel: Map<Char, String> = buildMap {
        val vowelToKana = listOf(
            "a" to "ぁあかがさざただなはばぱまゃやらゎわヵァアカガサザタダナハバパマャヤラヮワヵヷ",
            "i" to "ぃいきぎしじちぢにひびぴみりゐィイキギシジチヂニヒビピミリヰヸ",
            "u" to "ぅうくぐすずっつづぬふぶぷむゅゆるゥウクグスズッツヅヌフブプムュユルヴ",
            "e" to "ぇえけげせぜてでねへべぺめれゑヶェエケゲセゼテデネヘベペメレヱヶヹ",
            "o" to "ぉおこごそぞとどのほぼぽもょよろをォオコゴソゾトドノホボポモョヨロヲヺ",
            "" to "のノ"
        )
        for ((vowel, characters) in vowelToKana) {
            for (c in characters) put(c, vowel)
        }
    }

    fun isCodePointKana(codePoint: Int): Boolean =
        codePoint in 0x3040..0x309F || codePoint in 0x30A0..0x30FF

    private fun prolongedHiragana(previous: Char): Char? = when (kanaToVowel[previous]) {
        "a" -> 'あ'
        "i" -> 'い'
        "u" -> 'う'
        "e" -> 'え'
        "o" -> 'う'
        else -> null
    }

    fun convertKatakanaToHiragana(text: String, keepProlongedSoundMarks: Boolean = false): String {
        val result = StringBuilder()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                codePoint == KATAKANA_SMALL_KA || codePoint == KATAKANA_SMALL_KE -> result.appendCodePoint(codePoint)
                codePoint == PROLONGED_SOUND_MARK -> {
                    val replacement = if (!keepProlongedSoundMarks && result.isNotEmpty()) {
                        prolongedHiragana(result[result.length - 1])
                    } else {
                        null
                    }
                    if (replacement != null) result.append(replacement) else result.appendCodePoint(codePoint)
                }

                codePoint in KATAKANA_CONVERSION_START..KATAKANA_CONVERSION_END ->
                    result.appendCodePoint(codePoint + (HIRAGANA_CONVERSION_START - KATAKANA_CONVERSION_START))

                else -> result.appendCodePoint(codePoint)
            }
        }
        return result.toString()
    }

    fun distributeFurigana(term: String, reading: String): List<FuriganaSegment> {
        if (reading == term) return listOf(FuriganaSegment(term, ""))

        val groups = mutableListOf<FuriganaGroup>()
        var groupPre: FuriganaGroup? = null
        var isKanaPre: Boolean? = null
        var i = 0
        while (i < term.length) {
            val codePoint = term.codePointAt(i)
            val c = String(Character.toChars(codePoint))
            i += Character.charCount(codePoint)
            val isKana = isCodePointKana(codePoint)
            if (isKana == isKanaPre && groupPre != null) {
                groupPre.text += c
            } else {
                groupPre = FuriganaGroup(isKana, c)
                groups.add(groupPre)
                isKanaPre = isKana
            }
        }
        for (group in groups) {
            if (group.isKana) group.textNormalized = convertKatakanaToHiragana(group.text)
        }

        val readingNormalized = convertKatakanaToHiragana(reading)
        return segmentizeFurigana(reading, readingNormalized, groups, 0)
            ?: listOf(FuriganaSegment(term, reading))
    }

    private fun segmentizeFurigana(
        reading: String,
        readingNormalized: String,
        groups: List<FuriganaGroup>,
        groupsStart: Int
    ): MutableList<FuriganaSegment>? {
        val groupCount = groups.size - groupsStart
        if (groupCount <= 0) {
            return if (reading.isEmpty()) mutableListOf() else null
        }

        val group = groups[groupsStart]
        val text = group.text
        val textLength = text.length
        if (group.isKana) {
            val textNormalized = group.textNormalized
            if (textNormalized != null && readingNormalized.startsWith(textNormalized)) {
                val segments = segmentizeFurigana(
                    reading.substring(textLength),
                    readingNormalized.substring(textLength),
                    groups,
                    groupsStart + 1
                )
                if (segments != null) {
                    if (reading.startsWith(text)) {
                        segments.add(0, FuriganaSegment(text, ""))
                    } else {
                        segments.addAll(0, getFuriganaKanaSegments(text, reading))
                    }
                    return segments
                }
            }
            return null
        } else {
            var result: MutableList<FuriganaSegment>? = null
            var i = reading.length
            while (i >= textLength) {
                val segments = segmentizeFurigana(
                    reading.substring(i),
                    readingNormalized.substring(i),
                    groups,
                    groupsStart + 1
                )
                if (segments != null) {
                    // More than one way to segmentize the tail: ambiguous.
                    if (result != null) return null
                    segments.add(0, FuriganaSegment(text, reading.substring(0, i)))
                    result = segments
                }
                // There is only one way to segmentize the last non-kana group.
                if (groupCount == 1) break
                --i
            }
            return result
        }
    }

    private fun getFuriganaKanaSegments(text: String, reading: String): List<FuriganaSegment> {
        val textLength = text.length
        val newSegments = mutableListOf<FuriganaSegment>()
        var start = 0
        var state = reading.getOrNull(0) == text[0]
        for (i in 1 until textLength) {
            val newState = reading.getOrNull(i) == text[i]
            if (state == newState) continue
            newSegments.add(FuriganaSegment(text.substring(start, i), if (state) "" else reading.substring(start, i)))
            state = newState
            start = i
        }
        newSegments.add(
            FuriganaSegment(
                text.substring(start, textLength),
                if (state) "" else reading.substring(start, minOf(textLength, reading.length))
            )
        )
        return newSegments
    }

    private fun getStemLength(text1: String, text2: String): Int {
        val minLength = minOf(text1.length, text2.length)
        if (minLength == 0) return 0
        var i = 0
        while (true) {
            val char1 = text1.codePointAt(i)
            val char2 = text2.codePointAt(i)
            if (char1 != char2) break
            val charLength = Character.charCount(char1)
            i += charLength
            if (i >= minLength) {
                // Don't consume a partial surrogate pair.
                if (i > minLength) i -= charLength
                break
            }
        }
        return i
    }

    fun distributeFuriganaInflected(term: String, reading: String, source: String): List<FuriganaSegment> {
        val termNormalized = convertKatakanaToHiragana(term)
        val readingNormalized = convertKatakanaToHiragana(reading)
        val sourceNormalized = convertKatakanaToHiragana(source)

        var mainText = term
        var effectiveReading = reading
        var stemLength = getStemLength(termNormalized, sourceNormalized)

        // Check if the source is derived from the reading instead of the term.
        val readingStemLength = getStemLength(readingNormalized, sourceNormalized)
        if (readingStemLength > 0 && readingStemLength >= stemLength) {
            mainText = reading
            stemLength = readingStemLength
            effectiveReading = source.substring(0, stemLength) + reading.substring(stemLength)
        }

        val segments = mutableListOf<FuriganaSegment>()
        if (stemLength > 0) {
            mainText = source.substring(0, stemLength) + mainText.substring(stemLength)
            val segments2 = distributeFurigana(mainText, effectiveReading)
            var consumed = 0
            for (segment in segments2) {
                val start = consumed
                consumed += segment.text.length
                if (consumed < stemLength) {
                    segments.add(segment)
                } else if (consumed == stemLength) {
                    segments.add(segment)
                    break
                } else {
                    if (start < stemLength) {
                        segments.add(FuriganaSegment(mainText.substring(start, stemLength), ""))
                    }
                    break
                }
            }
        }

        if (stemLength < source.length) {
            val remainder = source.substring(stemLength)
            val last = segments.lastOrNull()
            if (last != null && last.reading.isEmpty()) {
                // Append to the last segment if it has an empty reading.
                segments[segments.size - 1] = last.copy(text = last.text + remainder)
            } else {
                segments.add(FuriganaSegment(remainder, ""))
            }
        }
        return segments
    }
}
