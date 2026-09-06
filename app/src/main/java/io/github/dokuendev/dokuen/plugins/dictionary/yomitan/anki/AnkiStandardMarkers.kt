package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * The standard field markers offered for each card format type, in the same order as the
 * original Yomitan's getStandardFieldMarkers (ext/js/data/anki-template-util.js, Japanese
 * language). Drives the per-field marker-insert menu and default field value matching.
 */
object AnkiStandardMarkers {
    private val termMarkers: List<String> = listOf(
        "audio",
        "clipboard-image",
        "clipboard-text",
        "cloze-body",
        "cloze-prefix",
        "cloze-suffix",
        "conjugation",
        "dictionary",
        "dictionary-alias",
        "document-title",
        "expression",
        "frequencies",
        "frequency-harmonic-rank",
        "frequency-harmonic-occurrence",
        "frequency-average-rank",
        "frequency-average-occurrence",
        "furigana",
        "furigana-plain",
        "glossary",
        "glossary-brief",
        "glossary-no-dictionary",
        "glossary-plain",
        "glossary-plain-no-dictionary",
        "glossary-first",
        "glossary-first-brief",
        "glossary-first-no-dictionary",
        "part-of-speech",
        "phonetic-transcriptions",
        "reading",
        "screenshot",
        "search-query",
        "popup-selection-text",
        "sentence",
        "sentence-furigana",
        "sentence-furigana-plain",
        "tags",
        "url",
        "cloze-body-kana",
        "pitch-accents",
        "pitch-accent-graphs",
        "pitch-accent-graphs-jj",
        "pitch-accent-positions",
        "pitch-accent-categories"
    )

    private val kanjiMarkers: List<String> = listOf(
        "character",
        "clipboard-image",
        "clipboard-text",
        "cloze-body",
        "cloze-prefix",
        "cloze-suffix",
        "dictionary",
        "dictionary-alias",
        "document-title",
        "frequencies",
        "frequency-harmonic-rank",
        "frequency-harmonic-occurrence",
        "frequency-average-rank",
        "frequency-average-occurrence",
        "glossary",
        "kunyomi",
        "onyomi",
        "onyomi-hiragana",
        "screenshot",
        "search-query",
        "popup-selection-text",
        "sentence",
        "sentence-furigana",
        "sentence-furigana-plain",
        "stroke-count",
        "tags",
        "url"
    )

    fun forType(type: AnkiEntryType): List<String> = when (type) {
        AnkiEntryType.TERM -> termMarkers
        AnkiEntryType.KANJI -> kanjiMarkers
    }
}
