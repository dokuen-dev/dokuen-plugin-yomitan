package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMediaNeedsTest {

    private val names = listOf("Front", "Back", "Picture")

    private fun fields(mode: AnkiFieldOverwriteMode = AnkiFieldOverwriteMode.COALESCE) = mapOf(
        "Front" to AnkiField("{expression}"),
        "Back" to AnkiField("{glossary}"),
        "Picture" to AnkiField("<div>{screenshot}</div>", mode)
    )

    private fun existing(vararg values: String) =
        AnkiNoteInfo(id = 1L, modelId = 2L, fields = values.toList(), tags = emptySet())

    @Test
    fun screenshotIsRequestedFirst() {
        assertEquals(listOf("screenshot", "clipboard-image"), AnkiMediaNeeds.IMAGE_MARKERS)
    }

    @Test
    fun addingANoteUsesEveryMarkerItsFieldsContain() {
        assertTrue(AnkiMediaNeeds.isNeeded("screenshot", names, fields(), existing = null))
        assertFalse(AnkiMediaNeeds.isNeeded("clipboard-image", names, fields(), existing = null))
    }

    @Test
    fun fieldsTheNoteTypeNoLongerHasDoNotCount() {
        assertFalse(AnkiMediaNeeds.isNeeded("screenshot", listOf("Front", "Back"), fields(), existing = null))
    }

    @Test
    fun overwriteModesDecideWhetherTheNewValueIsUsed() {
        val withPicture = existing("a", "b", "<img src=\"old.png\">")
        val withoutPicture = existing("a", "b", "")

        assertFalse(AnkiMediaNeeds.isNeeded("screenshot", names, fields(AnkiFieldOverwriteMode.SKIP), withoutPicture))
        assertFalse(AnkiMediaNeeds.isNeeded("screenshot", names, fields(AnkiFieldOverwriteMode.COALESCE), withPicture))
        assertTrue(
            AnkiMediaNeeds.isNeeded(
                "screenshot",
                names,
                fields(AnkiFieldOverwriteMode.COALESCE),
                withoutPicture
            )
        )
        for (mode in listOf(
            AnkiFieldOverwriteMode.OVERWRITE,
            AnkiFieldOverwriteMode.COALESCE_NEW,
            AnkiFieldOverwriteMode.APPEND,
            AnkiFieldOverwriteMode.PREPEND
        )) {
            assertTrue(mode.name, AnkiMediaNeeds.isNeeded("screenshot", names, fields(mode), withPicture))
        }
    }

    @Test
    fun anExistingNoteWithFewerFieldsCountsMissingOnesAsEmpty() {
        assertTrue(AnkiMediaNeeds.isNeeded("screenshot", names, fields(AnkiFieldOverwriteMode.COALESCE), existing("a")))
    }

    @Test
    fun clipboardTextFollowsTheSameRules() {
        val withText = mapOf(
            "Front" to AnkiField("{expression}"),
            "Back" to AnkiField("{clipboard-text}", AnkiFieldOverwriteMode.SKIP)
        )

        assertEquals("clipboard-text", AnkiMediaNeeds.CLIPBOARD_TEXT)
        assertTrue(
            AnkiMediaNeeds.isNeeded(
                AnkiMediaNeeds.CLIPBOARD_TEXT,
                listOf("Front", "Back"),
                withText,
                existing = null
            )
        )
        assertFalse(
            AnkiMediaNeeds.isNeeded(
                AnkiMediaNeeds.CLIPBOARD_TEXT,
                listOf("Front", "Back"),
                withText,
                existing("a", "")
            )
        )
    }
}
