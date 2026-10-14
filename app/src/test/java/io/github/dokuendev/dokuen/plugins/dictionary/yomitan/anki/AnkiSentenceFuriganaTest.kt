package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.WordReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric because WordReading and RubySpan are Android parcelables.
@RunWith(RobolectricTestRunner::class)
class AnkiSentenceFuriganaTest {

    private fun word(start: Int, end: Int, vararg ruby: Triple<Int, Int, String>) = WordReading().apply {
        startIndex = start
        endIndex = end
        this.ruby = ruby.map { (s, e, text) ->
            RubySpan().apply { startIndex = s; endIndex = e; rubyText = text }
        }.toTypedArray()
    }

    private fun seg(text: String, reading: String = "") = AnkiFuriganaSegment(text, reading)

    @Test
    fun wordsBecomeTermsAndRubyBecomesSegments() {
        val words = listOf(
            word(0, 1, Triple(0, 1, "かれ")), word(1, 2), word(2, 4, Triple(2, 4, "まいにち")),
            word(4, 7, Triple(4, 5, "た")), word(7, 8)
        )

        assertEquals(
            listOf(
                listOf(seg("彼", "かれ")), listOf(seg("は")), listOf(seg("毎日", "まいにち")),
                listOf(seg("食", "た"), seg("べる")), listOf(seg("。"))
            ),
            AnkiSentenceFurigana.fromReadings("彼は毎日食べる。", words)
        )
    }

    @Test
    fun eachRunOfUncoveredTextIsOneTermWithoutReading() {
        val words = listOf(word(2, 3, Triple(2, 3, "かれ")), word(4, 5))

        assertEquals(
            listOf(
                listOf(seg("「「")),
                listOf(seg("彼", "かれ")),
                listOf(seg("」")),
                listOf(seg("は")),
                listOf(seg(" 。"))
            ),
            AnkiSentenceFurigana.fromReadings("「「彼」は 。", words)
        )
    }

    @Test
    fun rubyInsideAWordSplitsItIntoSegments() {
        val words = listOf(word(0, 4, Triple(1, 2, "ちゃ"), Triple(2, 3, "づ")))

        assertEquals(
            listOf(listOf(seg("お"), seg("茶", "ちゃ"), seg("漬", "づ"), seg("け"))),
            AnkiSentenceFurigana.fromReadings("お茶漬け", words)
        )
    }

    @Test
    fun noWordsLeaveTheWholeSentenceAsOneTerm() {
        assertEquals(listOf(listOf(seg("あいう"))), AnkiSentenceFurigana.fromReadings("あいう", emptyList()))
    }

    @Test
    fun theMarkersAreUsedOnlyInTheNoteTypesCurrentFields() {
        val fields = mapOf(
            "Front" to AnkiField(value = "{expression}"),
            "Back" to AnkiField(value = "<i>{sentence-furigana-plain}</i>"),
            "Old" to AnkiField(value = "{sentence-furigana}")
        )

        assertTrue(AnkiSentenceFurigana.isUsed(listOf("Front", "Back"), fields))
        assertFalse(AnkiSentenceFurigana.isUsed(listOf("Front"), fields))
        assertFalse(AnkiSentenceFurigana.isUsed(listOf("Front"), mapOf("Front" to AnkiField(value = "{sentence}"))))
    }
}
