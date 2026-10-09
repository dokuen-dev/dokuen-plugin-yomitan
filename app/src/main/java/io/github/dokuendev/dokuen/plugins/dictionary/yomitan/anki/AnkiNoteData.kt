package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

data class AnkiClozeData(
    val sentence: String,
    val prefix: String,
    val body: String,
    val bodyKana: String,
    val suffix: String
)

/**
 * One frequency row attached to a note, mirroring the original's TermFrequency /
 * KanjiFrequency template objects. [frequency] is the numeric value and [displayValue]
 * the dictionary-supplied display string (null when the dictionary gave only a number);
 * the {frequencies} marker shows the display value when present, else the number.
 * [frequencyMode] is the `frequencyMode` the dictionary's index.json declares
 * ("rank-based" or "occurrence-based"), or null when it declares none.
 */
data class AnkiFrequencyItem(
    val dictionaryAlias: String,
    val frequency: Double,
    val displayValue: String? = null,
    val dictionary: String = dictionaryAlias,
    val frequencyMode: String? = null
)

data class AnkiTagItem(
    val name: String,
    val notes: String
)

/**
 * One definition of a grouped term entry: a single term_bank row from one dictionary,
 * matching the original's per-definition template object in its default "group" result
 * mode. [glossary] holds each glossary item already formatted as HTML (the original's
 * formatGlossary helper) and [glossaryPlain] the same items formatted as plain text
 * (formatGlossaryPlain). [styles] is the dictionary's styles.css, scoped for the glossary
 * markers.
 */
data class AnkiDefinition(
    val dictionary: String,
    val dictionaryAlias: String = dictionary,
    val definitionTags: List<AnkiTagItem> = emptyList(),
    val glossary: List<String> = emptyList(),
    val glossaryPlain: List<String> = emptyList(),
    val styles: AnkiScopedStyles = AnkiScopedStyles()
)

data class AnkiNoteData(
    val type: AnkiEntryType,
    val expression: String = "",
    val reading: String = "",
    /** Term entries: every definition shown for the grouped headword, in display order. */
    val definitions: List<AnkiDefinition> = emptyList(),
    /** Term entries: headword tags (term_bank termTags), merged and sorted. */
    val termTags: List<AnkiTagItem> = emptyList(),
    /** Term entries: the headword's word classes (term_bank rules), in first-seen order. */
    val wordClasses: List<String> = emptyList(),
    /**
     * Term entries: the inflection rule chains that led from the scanned text to this
     * headword; an empty inner list means the text matched uninflected.
     */
    val inflectionRuleChains: List<List<io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule>> = emptyList(),
    val frequencies: List<AnkiFrequencyItem> = emptyList(),
    /** Title of the first definition's dictionary (terms) or the kanji's dictionary. */
    val dictionary: String = "",
    val dictionaryAlias: String = dictionary,
    val cloze: AnkiClozeData? = null,
    /**
     * The lookup's query, which the original's `{search-query}` prints: the longest text the
     * scan matched, the query of an internal link, or the kanji character.
     */
    val searchQuery: String = "",
    val character: String = "",
    /** Kanji entries: the meanings, unformatted. */
    val glossary: List<String> = emptyList(),
    val kunyomi: List<String> = emptyList(),
    val onyomi: List<String> = emptyList(),
    /** Kanji entries: the "strokes" stat value when the dictionary provides one. */
    val strokeCount: String? = null,
    /** The AnkiDroid media file name of the `{screenshot}` image, set per tap. */
    val screenshotFileName: String? = null,
    /** The AnkiDroid media file name of the `{clipboard-image}` image, set per tap. */
    val clipboardImageFileName: String? = null,
    /** The clipboard's text for `{clipboard-text}`, set per tap. */
    val clipboardText: String? = null
)
