package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiMediaFileNamesTest {

    // 2026-01-05T03:04:05.006Z
    private val timestamp = 1_767_582_245_006L

    @Test
    fun matchesTheOriginalsFormatIncludingItsZeroBasedMonth() {
        assertEquals(
            "dokuen_screenshot_2026-00-05-03-04-05-006.png",
            AnkiMediaFileNames.generate("dokuen_screenshot", ".png", timestamp)
        )
    }

    @Test
    fun replacesCharactersInvalidInFileNames() {
        assertEquals(
            "a-b-c-d-e-f-g-h-i-j_2026-00-05-03-04-05-006.jpeg",
            AnkiMediaFileNames.generate("a<b>c:d\"e/f\\g|h?i*j", ".jpeg", timestamp)
        )
        assertEquals("x-y_2026-00-05-03-04-05-006.png", AnkiMediaFileNames.generate("x\u0001y", ".png", timestamp))
    }

    @Test
    fun hashedNameIsThePrefixAndTheContentsSha1() {
        // SHA-1 of "abc".
        assertEquals(
            "yomitan_audio_a9993e364706816aba3e25717850c26c9cd0d89d.mp3",
            AnkiMediaFileNames.hashed("yomitan_audio", "abc".toByteArray(), ".mp3")
        )
    }

    @Test
    fun hashedNameReplacesCharactersInvalidInFileNames() {
        assertEquals(
            "a-b_7037807198c22a7d2b0807371d763779a84fdfcf.ogg",
            AnkiMediaFileNames.hashed("a:b", byteArrayOf(1, 2, 3), ".ogg")
        )
    }

    @Test
    fun audioExtensionFollowsTheOriginalsTable() {
        assertEquals(".aac", AnkiMediaFileNames.audioExtension("audio/aac"))
        assertEquals(".mp3", AnkiMediaFileNames.audioExtension("audio/mpeg"))
        assertEquals(".mp3", AnkiMediaFileNames.audioExtension("audio/mp3"))
        assertEquals(".mp4", AnkiMediaFileNames.audioExtension("audio/mp4"))
        for (type in listOf("audio/ogg", "audio/vorbis", "application/ogg")) {
            assertEquals(".ogg", AnkiMediaFileNames.audioExtension(type))
        }
        for (type in listOf("audio/vnd.wav", "audio/wave", "audio/wav", "audio/x-wav", "audio/x-pn-wav")) {
            assertEquals(".wav", AnkiMediaFileNames.audioExtension(type))
        }
        assertEquals(".flac", AnkiMediaFileNames.audioExtension("audio/flac"))
        assertEquals(".webm", AnkiMediaFileNames.audioExtension("audio/webm"))
    }

    @Test
    fun audioExtensionFallsBackToMp3() {
        assertEquals(".mp3", AnkiMediaFileNames.audioExtension(null))
        assertEquals(".mp3", AnkiMediaFileNames.audioExtension("text/html"))
        // Compared exactly, as in the original, so a type with parameters matches nothing.
        assertEquals(".mp3", AnkiMediaFileNames.audioExtension("audio/ogg; codecs=opus"))
    }
}
