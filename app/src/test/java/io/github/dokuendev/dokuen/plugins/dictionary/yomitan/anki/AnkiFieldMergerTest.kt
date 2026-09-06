package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiFieldMergerTest {

    @Test
    fun overwriteAlwaysUsesNewValue() {
        assertEquals("new", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.OVERWRITE))
        assertEquals("", AnkiFieldMerger.merge("old", "", AnkiFieldOverwriteMode.OVERWRITE))
    }

    @Test
    fun skipAlwaysKeepsExistingValue() {
        assertEquals("old", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.SKIP))
        assertEquals("", AnkiFieldMerger.merge("", "new", AnkiFieldOverwriteMode.SKIP))
    }

    @Test
    fun appendConcatenatesWithoutSeparator() {
        assertEquals("oldnew", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.APPEND))
        assertEquals("new", AnkiFieldMerger.merge("", "new", AnkiFieldOverwriteMode.APPEND))
    }

    @Test
    fun prependConcatenatesWithoutSeparator() {
        assertEquals("newold", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.PREPEND))
        assertEquals("new", AnkiFieldMerger.merge("", "new", AnkiFieldOverwriteMode.PREPEND))
    }

    @Test
    fun coalesceKeepsExistingUnlessEmpty() {
        assertEquals("old", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.COALESCE))
        assertEquals("new", AnkiFieldMerger.merge("", "new", AnkiFieldOverwriteMode.COALESCE))
    }

    @Test
    fun coalesceNewUsesNewUnlessEmpty() {
        assertEquals("new", AnkiFieldMerger.merge("old", "new", AnkiFieldOverwriteMode.COALESCE_NEW))
        assertEquals("old", AnkiFieldMerger.merge("old", "", AnkiFieldOverwriteMode.COALESCE_NEW))
    }
}
