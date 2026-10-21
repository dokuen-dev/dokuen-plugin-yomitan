package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiAudioSourceListTest {

    private val jpod = AnkiAudioSource(AnkiAudioSourceType.JPOD101)
    private val jisho = AnkiAudioSource(AnkiAudioSourceType.JISHO)
    private val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://x/{term}")

    @Test
    fun addAppendsTheFirstTypeNotYetListedAsTheOriginalDoes() {
        val languagePod = AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101)
        val all = listOf(
            AnkiAudioSourceType.JPOD101, AnkiAudioSourceType.JISHO, AnkiAudioSourceType.LANGUAGE_POD_101,
            AnkiAudioSourceType.LINGUA_LIBRE, AnkiAudioSourceType.WIKTIONARY, AnkiAudioSourceType.CUSTOM
        ).map { AnkiAudioSource(it) }
        assertEquals(listOf(jpod), AnkiAudioSourceList.add(emptyList()))
        assertEquals(listOf(jpod, jisho), AnkiAudioSourceList.add(listOf(jpod)))
        assertEquals(listOf(jpod, jisho, languagePod), AnkiAudioSourceList.add(listOf(jpod, jisho)))
        assertEquals(listOf(jisho, jpod), AnkiAudioSourceList.add(listOf(jisho)))
        assertEquals(all + jpod, AnkiAudioSourceList.add(all))
        // Custom URL (JSON) is not among the types the original's add button picks from.
        val customJson = AnkiAudioSource(AnkiAudioSourceType.CUSTOM_JSON, "http://x/{term}")
        assertEquals(listOf(customJson, jpod), AnkiAudioSourceList.add(listOf(customJson)))
    }

    @Test
    fun removeDropsOneSource() {
        assertEquals(listOf(jpod, custom), AnkiAudioSourceList.remove(listOf(jpod, jisho, custom), 1))
        assertEquals(listOf(jpod), AnkiAudioSourceList.remove(listOf(jpod), 5))
    }

    @Test
    fun moveSwapsWithANeighborAndIgnoresTheEnds() {
        assertEquals(listOf(jisho, jpod, custom), AnkiAudioSourceList.move(listOf(jpod, jisho, custom), 1, -1))
        assertEquals(listOf(jpod, custom, jisho), AnkiAudioSourceList.move(listOf(jpod, jisho, custom), 1, 1))
        assertEquals(listOf(jpod, jisho), AnkiAudioSourceList.move(listOf(jpod, jisho), 0, -1))
        assertEquals(listOf(jpod, jisho), AnkiAudioSourceList.move(listOf(jpod, jisho), 1, 1))
    }

    @Test
    fun updateChangesOneSource() {
        assertEquals(
            listOf(jpod, jisho.copy(type = AnkiAudioSourceType.WIKTIONARY)),
            AnkiAudioSourceList.update(listOf(jpod, jisho), 1) { it.copy(type = AnkiAudioSourceType.WIKTIONARY) }
        )
    }

    @Test
    fun onlyTheCustomTypesUseAUrl() {
        assertTrue(AnkiAudioSourceList.usesUrl(AnkiAudioSourceType.CUSTOM))
        assertTrue(AnkiAudioSourceList.usesUrl(AnkiAudioSourceType.CUSTOM_JSON))
        assertFalse(AnkiAudioSourceList.usesUrl(AnkiAudioSourceType.JPOD101))
    }

    @Test
    fun parseTimeout() {
        assertEquals(0, AnkiAudioSourceList.parseTimeout("0"))
        assertEquals(1500, AnkiAudioSourceList.parseTimeout(" 1500 "))
        assertNull(AnkiAudioSourceList.parseTimeout("-5"))
        assertNull(AnkiAudioSourceList.parseTimeout("abc"))
        assertNull(AnkiAudioSourceList.parseTimeout(""))
        assertNull(AnkiAudioSourceList.parseTimeout("99999999999"))
    }
}
