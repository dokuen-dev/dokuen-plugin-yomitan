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
}
