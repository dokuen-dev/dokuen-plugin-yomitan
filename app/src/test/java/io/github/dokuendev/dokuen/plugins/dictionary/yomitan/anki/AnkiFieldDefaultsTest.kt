package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiFieldDefaultsTest {

    @Test
    fun firstFieldDefaultsToExpressionOrCharacter() {
        assertEquals("{expression}", AnkiFieldDefaults.defaultValue("Front", 0, AnkiEntryType.TERM, null))
        assertEquals("{character}", AnkiFieldDefaults.defaultValue("Front", 0, AnkiEntryType.KANJI, null))
    }

    @Test
    fun laterFieldsMatchMarkerNamesIgnoringCaseAndSeparators() {
        assertEquals("{reading}", AnkiFieldDefaults.defaultValue("Reading", 1, AnkiEntryType.TERM, null))
        assertEquals("{furigana-plain}", AnkiFieldDefaults.defaultValue("Furigana Plain", 1, AnkiEntryType.TERM, null))
        assertEquals("{glossary-brief}", AnkiFieldDefaults.defaultValue("glossary_brief", 1, AnkiEntryType.TERM, null))
        assertEquals("{part-of-speech}", AnkiFieldDefaults.defaultValue("PartOfSpeech", 1, AnkiEntryType.TERM, null))
    }

    @Test
    fun laterFieldsMatchAliases() {
        assertEquals("{expression}", AnkiFieldDefaults.defaultValue("Word", 1, AnkiEntryType.TERM, null))
        assertEquals("{reading}", AnkiFieldDefaults.defaultValue("Word Reading", 1, AnkiEntryType.TERM, null))
        assertEquals("{glossary}", AnkiFieldDefaults.defaultValue("Meaning", 2, AnkiEntryType.TERM, null))
        assertEquals("{audio}", AnkiFieldDefaults.defaultValue("Sound", 2, AnkiEntryType.TERM, null))
        assertEquals(
            "{frequency-harmonic-rank}",
            AnkiFieldDefaults.defaultValue("FreqSort", 2, AnkiEntryType.TERM, null)
        )
        assertEquals("{sentence}", AnkiFieldDefaults.defaultValue("Example Sentence", 2, AnkiEntryType.TERM, null))
    }

    @Test
    fun onlySecondPopupSelectionAliasApplies() {
        // The original's alias Map literal lists popup-selection-text twice; the second
        // entry replaces the first.
        assertEquals(
            "{popup-selection-text}",
            AnkiFieldDefaults.defaultValue("Selection Text", 2, AnkiEntryType.TERM, null)
        )
        assertEquals("", AnkiFieldDefaults.defaultValue("Selection", 2, AnkiEntryType.TERM, null))
    }

    @Test
    fun markersAreLimitedToTheFormatType() {
        assertEquals("{kunyomi}", AnkiFieldDefaults.defaultValue("Kunyomi", 1, AnkiEntryType.KANJI, null))
        assertEquals("", AnkiFieldDefaults.defaultValue("Kunyomi", 1, AnkiEntryType.TERM, null))
        assertEquals("", AnkiFieldDefaults.defaultValue("Reading", 1, AnkiEntryType.KANJI, null))
    }

    @Test
    fun unmatchedFieldGetsEmptyValue() {
        assertEquals("", AnkiFieldDefaults.defaultValue("Notes", 3, AnkiEntryType.TERM, null))
    }

    @Test
    fun oldFieldValueWinsOverDefaultIncludingFirstField() {
        val old = mapOf("Front" to AnkiField("{furigana}"), "Notes" to AnkiField("custom"))
        assertEquals("{furigana}", AnkiFieldDefaults.defaultValue("Front", 0, AnkiEntryType.TERM, old))
        assertEquals("custom", AnkiFieldDefaults.defaultValue("Notes", 3, AnkiEntryType.TERM, old))
    }

    @Test
    fun buildFieldsFollowsNoteTypeOrderAndKeepsExistingFields() {
        val kept = AnkiField("{furigana}", AnkiFieldOverwriteMode.PREPEND)
        val result = AnkiFieldDefaults.buildFields(
            listOf("Expression", "Reading", "Meaning"),
            AnkiEntryType.TERM,
            mapOf("Reading" to kept, "Gone" to AnkiField("{tags}"))
        )
        assertEquals(listOf("Expression", "Reading", "Meaning"), result.keys.toList())
        assertEquals(AnkiField("{expression}"), result["Expression"])
        assertEquals(kept, result["Reading"])
        assertEquals(AnkiField("{glossary}"), result["Meaning"])
    }

    @Test
    fun containsAnyMarker() {
        assertTrue(AnkiFieldDefaults.containsAnyMarker("{expression}"))
        assertTrue(AnkiFieldDefaults.containsAnyMarker("prefix {cloze-body} suffix"))
        assertFalse(AnkiFieldDefaults.containsAnyMarker(""))
        assertFalse(AnkiFieldDefaults.containsAnyMarker("plain text"))
        assertFalse(AnkiFieldDefaults.containsAnyMarker("{not a marker}"))
    }
}
