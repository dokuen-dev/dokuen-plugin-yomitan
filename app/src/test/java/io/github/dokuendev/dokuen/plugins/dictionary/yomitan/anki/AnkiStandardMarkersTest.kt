package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiStandardMarkersTest {

    @Test
    fun termMarkersMatchOriginalJapaneseList() {
        val markers = AnkiStandardMarkers.forType(AnkiEntryType.TERM)
        assertEquals(43, markers.size)
        assertEquals("audio", markers.first())
        assertEquals("pitch-accent-categories", markers.last())
        assertTrue("cloze-body-kana" in markers)
        assertFalse("character" in markers)
        assertEquals(markers.size, markers.toSet().size)
    }

    @Test
    fun kanjiMarkersMatchOriginalList() {
        val markers = AnkiStandardMarkers.forType(AnkiEntryType.KANJI)
        assertEquals(27, markers.size)
        assertEquals("character", markers.first())
        assertEquals("url", markers.last())
        assertTrue("stroke-count" in markers)
        assertFalse("expression" in markers)
    }

    @Test
    fun everyStandardMarkerIsRecognizedByTheTemplateEngine() {
        val data = AnkiNoteData(type = AnkiEntryType.TERM)
        for (type in AnkiEntryType.entries) {
            for (marker in AnkiStandardMarkers.forType(type)) {
                val result = AnkiTemplateEngine.renderField("{$marker}", data)
                assertTrue("Marker '$marker' should be recognized", result.unknownMarkers.isEmpty())
            }
        }
    }
}
