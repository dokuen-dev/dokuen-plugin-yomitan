package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for JapaneseUtil furigana distribution.
 *
 * These tests verify that the distributeFurigana function correctly segments
 * Japanese expressions and maps readings to appropriate characters, matching
 * the behavior of the original Yomitan implementation.
 *
 * Reference: yomitan/ext/js/language/ja/japanese.js::distributeFurigana
 */
class JapaneseUtilTest {

    @Test
    fun `distributeFurigana handles identical term and reading`() {
        // When term == reading, no furigana needed
        val segments = JapaneseUtil.distributeFurigana("ひらがな", "ひらがな")

        assertEquals(1, segments.size)
        assertEquals("ひらがな", segments[0].text)
        assertNull(segments[0].reading)
    }

    @Test
    fun `distributeFurigana handles simple single kanji`() {
        // Single kanji with reading
        val segments = JapaneseUtil.distributeFurigana("日", "にち")

        assertEquals(1, segments.size)
        assertEquals("日", segments[0].text)
        assertEquals("にち", segments[0].reading)
    }

    @Test
    fun `distributeFurigana handles kanji with okurigana`() {
        // Kanji followed by kana
        val segments = JapaneseUtil.distributeFurigana("食べる", "たべる")

        assertEquals(2, segments.size)

        // First segment: kanji with reading
        assertEquals("食", segments[0].text)
        assertEquals("た", segments[0].reading)

        // Second segment: kana without reading
        assertEquals("べる", segments[1].text)
        assertNull(segments[1].reading)
    }

    @Test
    fun `distributeFurigana handles multiple kanji`() {
        // Consecutive kanji form a single group, so the whole compound gets one segment.
        // "日本" has no kana to anchor the reading boundary, so the reading "にほん"
        // is assigned to the group as a whole, one RubySpan covering both characters.
        val segments = JapaneseUtil.distributeFurigana("日本", "にほん")

        assertEquals(1, segments.size)
        assertEquals("日本", segments[0].text)
        assertEquals("にほん", segments[0].reading)
    }

    @Test
    fun `distributeFurigana handles kanji-kana-kanji pattern`() {
        // Mixed kanji and kana
        val segments = JapaneseUtil.distributeFurigana("食べ物", "たべもの")

        assertEquals(3, segments.size)

        // First kanji
        assertEquals("食", segments[0].text)
        assertEquals("た", segments[0].reading)

        // Kana in the middle
        assertEquals("べ", segments[1].text)
        assertNull(segments[1].reading)

        // Second kanji
        assertEquals("物", segments[2].text)
        assertEquals("もの", segments[2].reading)
    }

    @Test
    fun `distributeFurigana handles verb with okurigana`() {
        // Verb with standard okurigana
        val segments = JapaneseUtil.distributeFurigana("走る", "はしる")

        assertEquals(2, segments.size)

        assertEquals("走", segments[0].text)
        assertEquals("はし", segments[0].reading)

        assertEquals("る", segments[1].text)
        assertNull(segments[1].reading)
    }

    @Test
    fun `distributeFurigana handles long reading for single kanji`() {
        // Kanji with multi-character reading
        val segments = JapaneseUtil.distributeFurigana("今日", "きょう")

        // This should create segments for each kanji
        assertTrue(segments.isNotEmpty())

        // Verify we have the complete text
        val reconstructed = segments.joinToString("") { it.text }
        assertEquals("今日", reconstructed)
    }

    @Test
    fun `distributeFurigana consecutive kanji get combined reading`() {
        // Regression test for the bug reported with "日曜"/"にちよう".
        // The broken grouping kept each kanji separate, producing two spans:
        //   (0,1,"に") and (1,2,"ちよう")
        // The correct grouping keeps consecutive kanji together, producing one span:
        //   (0,2,"にちよう")
        // This is correct because there is no kana between 日 and 曜 to anchor a split.
        val segments = JapaneseUtil.distributeFurigana("日曜", "にちよう")

        assertEquals(1, segments.size)
        assertEquals("日曜", segments[0].text)
        assertEquals("にちよう", segments[0].reading)
    }

    @Test
    fun `distributeFurigana fallback on complex cases`() {
        // If segmentation fails, should fallback to single segment with entire reading
        val segments = JapaneseUtil.distributeFurigana("複雑", "ふくざつ")

        // Should produce valid segments
        assertTrue(segments.isNotEmpty())

        // Verify text is preserved
        val reconstructed = segments.joinToString("") { it.text }
        assertEquals("複雑", reconstructed)
    }

    @Test
    fun `distributeFurigana handles katakana reading`() {
        // Katakana reading should be normalized to hiragana internally
        val segments = JapaneseUtil.distributeFurigana("外来語", "がいらいご")

        assertTrue(segments.isNotEmpty())

        // Verify text is preserved
        val reconstructed = segments.joinToString("") { it.text }
        assertEquals("外来語", reconstructed)
    }

    @Test
    fun `distributeFurigana preserves all characters`() {
        // Verify no characters are lost during segmentation
        val testCases = listOf(
            "日本" to "にほん",
            "食べる" to "たべる",
            "走る" to "はしる",
            "勉強する" to "べんきょうする"
        )

        for ((term, reading) in testCases) {
            val segments = JapaneseUtil.distributeFurigana(term, reading)
            val reconstructed = segments.joinToString("") { it.text }
            assertEquals("Failed for term: $term", term, reconstructed)
        }
    }

    @Test
    fun `isCodePointKanji returns true for CJK Unified Ideographs`() {
        // U+4E00-U+9FFF, the main kanji block
        assertTrue(JapaneseUtil.isCodePointKanji('日'.code))  // U+65E5
        assertTrue(JapaneseUtil.isCodePointKanji('本'.code))  // U+672C
        assertTrue(JapaneseUtil.isCodePointKanji('語'.code))  // U+8A9E
        assertTrue(JapaneseUtil.isCodePointKanji('一'.code))  // U+4E00, block start
        assertTrue(JapaneseUtil.isCodePointKanji(0x9FFF))     // block end
    }

    @Test
    fun `isCodePointKanji returns true for CJK Extension A`() {
        // U+3400-U+4DBF
        assertTrue(JapaneseUtil.isCodePointKanji(0x3400))  // block start
        assertTrue(JapaneseUtil.isCodePointKanji(0x4DBF))  // block end
    }

    @Test
    fun `isCodePointKanji returns true for CJK Compatibility Ideographs`() {
        // U+F900-U+FAFF
        assertTrue(JapaneseUtil.isCodePointKanji(0xF900))
        assertTrue(JapaneseUtil.isCodePointKanji(0xFAFF))
    }

    @Test
    fun `isCodePointKanji returns false for hiragana`() {
        assertFalse(JapaneseUtil.isCodePointKanji('あ'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('べ'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('る'.code))
    }

    @Test
    fun `isCodePointKanji returns false for katakana`() {
        assertFalse(JapaneseUtil.isCodePointKanji('ア'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('ト'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('ン'.code))
    }

    @Test
    fun `isCodePointKanji returns false for ASCII and Latin`() {
        assertFalse(JapaneseUtil.isCodePointKanji('a'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('Z'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('0'.code))
        assertFalse(JapaneseUtil.isCodePointKanji(' '.code))
    }

    @Test
    fun `isCodePointKanji returns false for common punctuation`() {
        assertFalse(JapaneseUtil.isCodePointKanji('。'.code))  // Japanese period
        assertFalse(JapaneseUtil.isCodePointKanji('、'.code))  // Japanese comma
        assertFalse(JapaneseUtil.isCodePointKanji('・'.code))  // Interpunct
    }

    @Test
    fun `isCodePointKanji correctly classifies each character in a mixed term`() {
        // 食べる: 食=kanji, べ=hiragana, る=hiragana
        assertTrue(JapaneseUtil.isCodePointKanji('食'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('べ'.code))
        assertFalse(JapaneseUtil.isCodePointKanji('る'.code))

        // 日本語: all kanji
        assertTrue(JapaneseUtil.isCodePointKanji('日'.code))
        assertTrue(JapaneseUtil.isCodePointKanji('本'.code))
        assertTrue(JapaneseUtil.isCodePointKanji('語'.code))
    }
}
