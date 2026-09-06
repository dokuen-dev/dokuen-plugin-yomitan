package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiSentenceExtractorTest {

    private fun extractAround(text: String, match: String): AnkiSentenceExtractor.Sentence {
        val start = text.indexOf(match)
        return AnkiSentenceExtractor.extract(text, start, start + match.length)
    }

    @Test
    fun testStopsAtTerminatorsAndIncludesTheClosingOne() {
        val result = extractAround("今日は晴れ。昨日パンを食べた。明日も。", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("昨日パンを食べた。", 5), result)
    }

    @Test
    fun testOpeningQuoteBoundsTheSentenceWithoutBeingIncluded() {
        val result = extractAround("彼は「パンを食べた。」と言った。", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("パンを食べた。", 3), result)
    }

    @Test
    fun testQuotedTextInsideTheSentenceDoesNotEndIt() {
        val result = extractAround("彼は「はい。」と言って食べた。次。", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("彼は「はい。」と言って食べた。", 11), result)
    }

    @Test
    fun testNestedQuotesBeforeTheMatchKeepTheOriginalsQuoteStackBehavior() {
        // The original pops the wrong entry for nested quotes, so the backward scan does not
        // stop at the sentence terminator before the quotes (expected value from the original's
        // extractSentence).
        val result = extractAround("前。「A『B』C」食べた。後", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("前。「A『B』C」食べた。", 9), result)
    }

    @Test
    fun testNestedQuotesAfterTheMatchKeepTheOriginalsQuoteStackBehavior() {
        val result = extractAround("食べた「A『B』C」だ。後", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("食べた「A『B』C」だ。後", 0), result)
    }

    @Test
    fun testNewlinesTerminate() {
        val result = extractAround("一行目\n食べた\n三行目", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("食べた", 0), result)
    }

    @Test
    fun testSurroundingWhitespaceIsTrimmed() {
        val result = extractAround("　 食べた  ", "食べた")
        assertEquals(AnkiSentenceExtractor.Sentence("食べた", 0), result)
    }

    @Test
    fun testScanExtentLimitsHowFarBackTheSentenceReaches() {
        val prefix = "あ".repeat(300)
        val result = extractAround(prefix + "食べた", "食べた")
        assertEquals(200, result.offset)
        assertEquals("あ".repeat(200) + "食べた", result.text)
    }

    @Test
    fun testClozeBodyKanaFollowsTheInflectedSurfaceForm() {
        val sentence = extractAround("昨日パンを食べた。", "食べた")
        val cloze = AnkiNoteDataBuilder.buildCloze(sentence, "食べた", "食べる", "たべる")
        assertEquals(
            AnkiClozeData(
                sentence = "昨日パンを食べた。",
                prefix = "昨日パンを",
                body = "食べた",
                bodyKana = "たべた",
                suffix = "。"
            ),
            cloze
        )
    }

    @Test
    fun testKanjiClozeKeepsTheCharacterAsBodyKana() {
        val sentence = extractAround("パンを食べた。", "食")
        val cloze = AnkiNoteDataBuilder.buildCloze(sentence, "食", "", "")
        assertEquals("食", cloze.body)
        assertEquals("食", cloze.bodyKana)
        assertEquals("パンを", cloze.prefix)
        assertEquals("べた。", cloze.suffix)
    }
}
