package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * Default field values for a card format's note type, ported from the original Yomitan's
 * AnkiCardController._getDefaultFieldValue (ext/js/pages/settings/anki-controller.js).
 */
object AnkiFieldDefaults {
    private val markerAliases: Map<String, List<String>> = linkedMapOf(
        "expression" to listOf("phrase", "term", "word"),
        "reading" to listOf("expression-reading", "term-reading", "word-reading"),
        "furigana" to listOf("expression-furigana", "term-furigana", "word-furigana"),
        "glossary" to listOf("definition", "meaning"),
        "audio" to listOf("sound", "word-audio", "term-audio", "expression-audio"),
        "dictionary" to listOf("dict"),
        "pitch-accents" to listOf("pitch", "pitch-accent", "pitch-pattern"),
        "sentence" to listOf("example-sentence"),
        "frequency-harmonic-rank" to listOf("freq", "frequency", "freq-sort", "freqency-sort"),
        // The original's Map literal lists popup-selection-text twice; as a JS Map, the
        // later entry wins, so only "selection-text" is actually an alias.
        "popup-selection-text" to listOf("selection-text"),
        "pitch-accent-positions" to listOf("pitch-position"),
        "pitch-accent-categories" to listOf("pitch-categories")
    )

    private val markerPattern = Regex("\\{[\\p{L}\\p{N}_\\-]+\\}")

    /**
     * Returns the value a field named [fieldName] at position [index] should start with. An
     * existing same-named entry in [oldFields] keeps its value; otherwise the first field gets
     * `{expression}`/`{character}` and later fields get the first standard marker whose name
     * (or alias) matches the field name, ignoring case and treating `-`, `_` and spaces alike.
     */
    fun defaultValue(
        fieldName: String,
        index: Int,
        type: AnkiEntryType,
        oldFields: Map<String, AnkiField>?
    ): String {
        oldFields?.get(fieldName)?.let { return it.value }

        if (index == 0) {
            return if (type == AnkiEntryType.KANJI) "{character}" else "{expression}"
        }

        for (marker in AnkiStandardMarkers.forType(type)) {
            val names = listOf(marker) + (markerAliases[marker] ?: emptyList())
            val pattern = names.joinToString(separator = "|", prefix = "^(?:", postfix = ")$") { name ->
                name.split('-').joinToString("[-_ ]*") { Regex.escape(it) }
            }
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(fieldName)) {
                return "{$marker}"
            }
        }
        return ""
    }

    /**
     * Builds the field map for [fieldNames] (in the note type's own order), keeping every
     * existing same-named field from [oldFields] as-is and filling the rest with
     * [defaultValue].
     */
    fun buildFields(
        fieldNames: List<String>,
        type: AnkiEntryType,
        oldFields: Map<String, AnkiField>?
    ): Map<String, AnkiField> {
        val result = LinkedHashMap<String, AnkiField>()
        for ((i, name) in fieldNames.withIndex()) {
            result[name] = oldFields?.get(name) ?: AnkiField(value = defaultValue(name, i, type, oldFields))
        }
        return result
    }

    /** Mirrors the original's stringContainsAnyFieldMarker, used to validate the first field. */
    fun containsAnyMarker(value: String): Boolean = markerPattern.containsMatchIn(value)
}
