package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

/**
 * Japanese text processing utilities.
 *
 * This is a direct port of relevant functions from yomitan/ext/js/language/ja/japanese.js.
 * The primary purpose is to distribute furigana (ruby text) across kanji and kana characters
 * in Japanese text, matching the behavior of the original Yomitan implementation.
 *
 * Reference: yomitan/ext/js/language/ja/japanese.js::distributeFurigana
 */
object JapaneseUtil {

    /**
     * Represents a segment of text with optional furigana.
     *
     * @property text The base text (kanji or kana)
     * @property reading The furigana reading for this segment, or null if no reading
     */
    data class FuriganaSegment(
        val text: String,
        val reading: String?
    )

    /**
     * Internal representation of a text group (kanji or kana).
     */
    private data class FuriganaGroup(
        val isKana: Boolean,
        var text: String,
        var textNormalized: String?
    )

    /**
     * Distributes furigana across a term and its reading.
     *
     * This function segments the term into kanji and kana groups, then attempts to
     * intelligently map the reading to each group. The result is a list of segments
     * where each segment contains base text and its corresponding reading.
     *
     * Algorithm:
     * 1. If term == reading, return single segment with no furigana
     * 2. Group consecutive kanji and kana characters
     * 3. Attempt to segment the reading across these groups
     * 4. If segmentation fails, fall back to single segment with entire reading
     *
     * Reference: yomitan/ext/js/language/ja/japanese.js::distributeFurigana
     *
     * @param term The expression (e.g., "日本")
     * @param reading The reading (e.g., "にほん")
     * @return List of FuriganaSegment with distributed readings
     */
    fun distributeFurigana(term: String, reading: String): List<FuriganaSegment> {
        // Same reading - no furigana needed
        if (reading == term) {
            return listOf(FuriganaSegment(term, null))
        }

        // Group consecutive characters of the same type (kana vs non-kana/kanji).
        val groups = mutableListOf<FuriganaGroup>()
        var currentGroup: FuriganaGroup? = null
        var previousIsKana: Boolean? = null

        for (c in term) {
            val codePoint = c.code
            val isKana = isCodePointKana(codePoint)

            if (isKana == previousIsKana && currentGroup != null) {
                // Same type as previous character, extend the current group
                currentGroup.text += c
            } else {
                // Type changed (kana→kanji or kanji→kana), start a new group
                val newGroup = FuriganaGroup(
                    isKana = isKana,
                    text = c.toString(),
                    textNormalized = null
                )
                groups.add(newGroup)
                currentGroup = newGroup
                previousIsKana = isKana
            }
        }

        // Normalize kana groups (convert katakana to hiragana)
        for (group in groups) {
            if (group.isKana) {
                group.textNormalized = convertKatakanaToHiragana(group.text)
            }
        }

        // Attempt to segment the reading across groups
        val readingNormalized = convertKatakanaToHiragana(reading)
        val segments = segmentizeFurigana(reading, readingNormalized, groups, 0)

        // Return segments if successful, otherwise fallback
        return segments ?: listOf(FuriganaSegment(term, reading))
    }

    /**
     * Recursively segments a reading across text groups.
     *
     * This function attempts to match portions of the reading to each group,
     * distributing the reading intelligently across kanji and kana.
     *
     * @param reading The original reading
     * @param readingNormalized The normalized (hiragana) reading
     * @param groups List of text groups (kanji/kana)
     * @param groupIndex Current group being processed
     * @return List of segments if successful, null if segmentation failed
     */
    private fun segmentizeFurigana(
        reading: String,
        readingNormalized: String,
        groups: List<FuriganaGroup>,
        groupIndex: Int
    ): List<FuriganaSegment>? {
        if (groupIndex >= groups.size) {
            // All groups processed - check if entire reading was consumed
            return if (readingNormalized.isEmpty()) emptyList() else null
        }

        val group = groups[groupIndex]

        if (group.isKana) {
            // Kana group - must match exactly
            val groupNorm = group.textNormalized!!
            if (!readingNormalized.startsWith(groupNorm)) {
                return null
            }

            // Recursively process remaining groups with remaining reading
            val remainingReading = reading.substring(group.text.length)
            val remainingReadingNorm = readingNormalized.substring(groupNorm.length)
            val tail = segmentizeFurigana(remainingReading, remainingReadingNorm, groups, groupIndex + 1)

            return if (tail != null) {
                listOf(FuriganaSegment(group.text, null)) + tail
            } else {
                null
            }
        } else {
            // Kanji group - try different reading lengths
            val nextGroup = if (groupIndex + 1 < groups.size) groups[groupIndex + 1] else null

            // Determine how much reading to consume
            val minLength = 1 // At least one character for the kanji
            val maxLength = if (nextGroup != null && nextGroup.isKana) {
                // Next group is kana - find where it starts in the reading
                val nextGroupNorm = nextGroup.textNormalized!!
                val index = readingNormalized.indexOf(nextGroupNorm)
                if (index >= 0) index else readingNormalized.length
            } else {
                readingNormalized.length
            }

            // Try each possible reading length from shortest to longest
            // This prevents greedy consumption of readings
            for (length in minLength..maxLength) {
                val readingChunk = reading.substring(0, length)
                val remainingReading = reading.substring(length)
                val remainingReadingNorm = readingNormalized.substring(length)

                val tail = segmentizeFurigana(remainingReading, remainingReadingNorm, groups, groupIndex + 1)
                if (tail != null) {
                    return listOf(FuriganaSegment(group.text, readingChunk)) + tail
                }
            }

            // No valid segmentation found
            return null
        }
    }

    /**
     * Checks if a Unicode code point represents a kanji (CJK ideograph) character.
     *
     * Covers the following Unicode blocks:
     * - CJK Unified Ideographs                   U+4E00-U+9FFF
     * - CJK Extension A                          U+3400-U+4DBF
     * - CJK Extension B                          U+20000-U+2A6DF  (surrogate pair range)
     * - CJK Compatibility Ideographs             U+F900-U+FAFF
     * - CJK Unified Ideographs Extension C-F,G   U+2A700-U+2CEAF
     *
     * Reference: yomitan/ext/js/language/ja/japanese.js::isCodePointKanji
     *
     * @param codePoint The Unicode code point to check
     * @return true if the code point is a kanji, false otherwise
     */
    fun isCodePointKanji(codePoint: Int): Boolean {
        return (codePoint in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
                (codePoint in 0x3400..0x4DBF) ||   // CJK Extension A
                (codePoint in 0x20000..0x2A6DF) || // CJK Extension B
                (codePoint in 0xF900..0xFAFF) ||   // CJK Compatibility Ideographs
                (codePoint in 0x2A700..0x2CEAF)    // CJK Extension C-G
    }

    /**
     * Checks if a Unicode code point represents a kana character.
     *
     * Kana includes hiragana and katakana (full-width and half-width).
     *
     * Reference: yomitan/ext/js/language/ja/japanese.js::isCodePointKana
     *
     * @param codePoint The Unicode code point to check
     * @return true if the code point is kana, false otherwise
     */
    private fun isCodePointKana(codePoint: Int): Boolean {
        return (codePoint in 0x3040..0x309F) || // Hiragana
                (codePoint in 0x30A0..0x30FF) || // Katakana
                (codePoint in 0xFF65..0xFF9F)    // Half-width katakana
    }

    /**
     * Converts katakana characters to hiragana.
     *
     * This normalization is used for matching readings that may be in either script.
     * Half-width katakana is also converted.
     *
     * Reference: yomitan/ext/js/language/ja/japanese.js::convertKatakanaToHiragana
     *
     * @param text Text containing katakana characters
     * @return Text with katakana converted to hiragana
     */
    private fun convertKatakanaToHiragana(text: String): String {
        return buildString {
            for (c in text) {
                val code = c.code
                append(
                    when (code) {
                        // Full-width katakana to hiragana
                        in 0x30A1..0x30F6 -> (code - 0x60).toChar()
                        // Half-width katakana to hiragana (simplified mapping)
                        in 0xFF65..0xFF9F -> {
                            // This is a simplified conversion - the original uses a lookup table
                            // For this port, we'll use a basic mapping
                            when (code) {
                                0xFF66 -> 'ヲ'
                                0xFF67 -> 'ァ'
                                0xFF68 -> 'ィ'
                                0xFF69 -> 'ゥ'
                                0xFF6A -> 'ェ'
                                0xFF6B -> 'ォ'
                                0xFF6C -> 'ャ'
                                0xFF6D -> 'ュ'
                                0xFF6E -> 'ョ'
                                0xFF6F -> 'ッ'
                                0xFF71 -> 'ア'
                                0xFF72 -> 'イ'
                                0xFF73 -> 'ウ'
                                0xFF74 -> 'エ'
                                0xFF75 -> 'オ'
                                0xFF76 -> 'カ'
                                0xFF77 -> 'キ'
                                0xFF78 -> 'ク'
                                0xFF79 -> 'ケ'
                                0xFF7A -> 'コ'
                                0xFF7B -> 'サ'
                                0xFF7C -> 'シ'
                                0xFF7D -> 'ス'
                                0xFF7E -> 'セ'
                                0xFF7F -> 'ソ'
                                0xFF80 -> 'タ'
                                0xFF81 -> 'チ'
                                0xFF82 -> 'ツ'
                                0xFF83 -> 'テ'
                                0xFF84 -> 'ト'
                                0xFF85 -> 'ナ'
                                0xFF86 -> 'ニ'
                                0xFF87 -> 'ヌ'
                                0xFF88 -> 'ネ'
                                0xFF89 -> 'ノ'
                                0xFF8A -> 'ハ'
                                0xFF8B -> 'ヒ'
                                0xFF8C -> 'フ'
                                0xFF8D -> 'ヘ'
                                0xFF8E -> 'ホ'
                                0xFF8F -> 'マ'
                                0xFF90 -> 'ミ'
                                0xFF91 -> 'ム'
                                0xFF92 -> 'メ'
                                0xFF93 -> 'モ'
                                0xFF94 -> 'ヤ'
                                0xFF95 -> 'ユ'
                                0xFF96 -> 'ヨ'
                                0xFF97 -> 'ラ'
                                0xFF98 -> 'リ'
                                0xFF99 -> 'ル'
                                0xFF9A -> 'レ'
                                0xFF9B -> 'ロ'
                                0xFF9C -> 'ワ'
                                0xFF9D -> 'ン'
                                else -> c
                            }.let { katakana ->
                                // Now convert full-width katakana to hiragana
                                val katakanaCode = katakana.code
                                if (katakanaCode in 0x30A1..0x30F6) {
                                    (katakanaCode - 0x60).toChar()
                                } else {
                                    katakana
                                }
                            }
                        }

                        else -> c
                    })
            }
        }
    }
}
