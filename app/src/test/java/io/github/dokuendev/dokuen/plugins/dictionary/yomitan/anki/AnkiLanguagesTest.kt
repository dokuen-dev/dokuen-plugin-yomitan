package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiLanguagesTest {

    @Test
    fun knownLanguagesUseTheOriginalsCodesAndNames() {
        assertEquals(AnkiLanguage("ja", "jpn", "Japanese"), AnkiLanguages.of("ja"))
        assertEquals(AnkiLanguage("yue", "yue", "Cantonese"), AnkiLanguages.of("yue"))
        // Spelled as the original spells it, even though LanguagePod101 then rejects it.
        assertEquals("Arabic (MSA)", AnkiLanguages.of("ar").name)
    }

    @Test
    fun anUnknownLanguageIsTheOriginalsAnyUnknownDescriptor() {
        assertEquals(AnkiLanguage("xxx", "xxx", "Any / Unknown"), AnkiLanguages.of("zz"))
        assertEquals(AnkiLanguages.UNKNOWN, AnkiLanguages.of(""))
    }

    @Test
    fun japaneseDefaultSourcesAreJpod101LanguagePod101AndJisho() {
        assertEquals(
            listOf(
                AnkiAudioSource(AnkiAudioSourceType.JPOD101),
                AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101),
                AnkiAudioSource(AnkiAudioSourceType.JISHO)
            ),
            AnkiLanguages.requiredAudioSources("ja", emptyList())
        )
    }

    @Test
    fun otherLanguagesDefaultToLinguaLibreLanguagePod101AndWiktionary() {
        assertEquals(
            listOf(
                AnkiAudioSourceType.LINGUA_LIBRE,
                AnkiAudioSourceType.LANGUAGE_POD_101,
                AnkiAudioSourceType.WIKTIONARY
            ),
            AnkiLanguages.requiredAudioSources("en", emptyList()).map { it.type }
        )
    }

    @Test
    fun typesAlreadyConfiguredAreNotAddedAgain() {
        val configured = listOf(
            AnkiAudioSource(AnkiAudioSourceType.JISHO),
            AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://localhost/{term}")
        )

        assertEquals(
            listOf(AnkiAudioSource(AnkiAudioSourceType.JPOD101), AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101)),
            AnkiLanguages.requiredAudioSources("ja", configured)
        )
    }
}
