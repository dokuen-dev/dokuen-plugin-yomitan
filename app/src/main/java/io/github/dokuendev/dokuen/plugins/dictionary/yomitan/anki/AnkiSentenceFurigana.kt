package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import io.github.dokuendev.dokuenreader.dictionary.WordReading

/**
 * The cloze sentence's furigana for `{sentence-furigana}` and `{sentence-furigana-plain}`, built
 * from Dokuen's readings of the sentence (CustomActionContext.requestReadings) in the shape the
 * original builds from its scanning parser's output.
 */
object AnkiSentenceFurigana {
    const val HTML_MARKER = "sentence-furigana"
    const val PLAIN_MARKER = "sentence-furigana-plain"

    /** Whether a field of the note type's current [fieldNames] uses either marker. */
    fun isUsed(fieldNames: List<String>, fields: Map<String, AnkiField>): Boolean =
        fieldNames.any { name ->
            val value = fields[name]?.value ?: return@any false
            value.contains("{$HTML_MARKER}") || value.contains("{$PLAIN_MARKER}")
        }

    /**
     * The terms of [sentence] from Dokuen's [words] of it. Each word is a term, and each stretch of
     * text no word covers is one term without a reading, as the original's scanning parser groups
     * the text it finds no dictionary term for. Inside a word, each ruby span is a segment with
     * its reading, and the text between spans is a segment without one. [words] keep the SDK's
     * contract (sorted, not overlapping, ruby inside its word), which the SDK has checked.
     */
    fun fromReadings(sentence: String, words: List<WordReading>): List<List<AnkiFuriganaSegment>> {
        val terms = mutableListOf<List<AnkiFuriganaSegment>>()
        var position = 0
        for (word in words) {
            if (word.startIndex > position) {
                terms += listOf(AnkiFuriganaSegment(sentence.substring(position, word.startIndex), ""))
            }
            val segments = mutableListOf<AnkiFuriganaSegment>()
            var inWord = word.startIndex
            for (span in word.ruby.orEmpty()) {
                if (span.startIndex > inWord) segments += AnkiFuriganaSegment(
                    sentence.substring(
                        inWord,
                        span.startIndex
                    ), ""
                )
                segments += AnkiFuriganaSegment(sentence.substring(span.startIndex, span.endIndex), span.rubyText)
                inWord = span.endIndex
            }
            if (word.endIndex > inWord) segments += AnkiFuriganaSegment(sentence.substring(inWord, word.endIndex), "")
            terms += segments
            position = word.endIndex
        }
        if (position < sentence.length) terms += listOf(AnkiFuriganaSegment(sentence.substring(position), ""))
        return terms
    }
}
