package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * A language as the original's language descriptors (ext/js/language/language-descriptors.js)
 * describe it, with its [iso] code (ISO 639-1 where one exists), its ISO 639-3 code, and its
 * English [name]. They are spelled exactly as the original spells them, since the audio sources
 * build their URLs from them.
 */
data class AnkiLanguage(val iso: String, val iso639Part3: String, val name: String)

object AnkiLanguages {
    /** The original's descriptor for a language it has no entry for. */
    val UNKNOWN = AnkiLanguage("xxx", "xxx", "Any / Unknown")

    private val languages: Map<String, AnkiLanguage> = listOf(
        AnkiLanguage("aii", "aii", "Assyrian Neo-Aramaic"),
        AnkiLanguage("ar", "ara", "Arabic (MSA)"),
        AnkiLanguage("arz", "arz", "Arabic (Egyptian)"),
        AnkiLanguage("be", "bel", "Belarusian"),
        AnkiLanguage("bg", "bul", "Bulgarian"),
        AnkiLanguage("br", "bre", "Breton"),
        AnkiLanguage("cs", "ces", "Czech"),
        AnkiLanguage("da", "dan", "Danish"),
        AnkiLanguage("de", "deu", "German"),
        AnkiLanguage("el", "ell", "Greek"),
        AnkiLanguage("en", "eng", "English"),
        AnkiLanguage("eo", "epo", "Esperanto"),
        AnkiLanguage("es", "spa", "Spanish"),
        AnkiLanguage("et", "est", "Estonian"),
        AnkiLanguage("eu", "eus", "Basque"),
        AnkiLanguage("fa", "fas", "Persian"),
        AnkiLanguage("fi", "fin", "Finnish"),
        AnkiLanguage("fr", "fra", "French"),
        AnkiLanguage("ga", "gle", "Irish"),
        AnkiLanguage("gd", "gla", "Scottish Gaelic"),
        AnkiLanguage("grc", "grc", "Ancient Greek"),
        AnkiLanguage("gv", "glv", "Manx"),
        AnkiLanguage("haw", "haw", "Hawaiian"),
        AnkiLanguage("he", "heb", "Hebrew"),
        AnkiLanguage("hi", "hin", "Hindi"),
        AnkiLanguage("hu", "hun", "Hungarian"),
        AnkiLanguage("id", "ind", "Indonesian"),
        AnkiLanguage("it", "ita", "Italian"),
        AnkiLanguage("la", "lat", "Latin"),
        AnkiLanguage("lo", "lao", "Lao"),
        AnkiLanguage("lv", "lav", "Latvian"),
        AnkiLanguage("ja", "jpn", "Japanese"),
        AnkiLanguage("ka", "kat", "Georgian"),
        AnkiLanguage("kn", "kan", "Kannada"),
        AnkiLanguage("km", "khm", "Khmer"),
        AnkiLanguage("ko", "kor", "Korean"),
        AnkiLanguage("kw", "cor", "Cornish"),
        AnkiLanguage("mn", "mon", "Mongolian"),
        AnkiLanguage("mt", "mlt", "Maltese"),
        AnkiLanguage("nl", "nld", "Dutch"),
        AnkiLanguage("no", "nor", "Norwegian"),
        AnkiLanguage("pl", "pol", "Polish"),
        AnkiLanguage("pt", "por", "Portuguese"),
        AnkiLanguage("ro", "ron", "Romanian"),
        AnkiLanguage("ru", "rus", "Russian"),
        AnkiLanguage("sga", "sga", "Old Irish"),
        AnkiLanguage("sh", "hbs", "Serbo-Croatian"),
        AnkiLanguage("sq", "sqi", "Albanian"),
        AnkiLanguage("sv", "swe", "Swedish"),
        AnkiLanguage("th", "tha", "Thai"),
        AnkiLanguage("tl", "tgl", "Tagalog"),
        AnkiLanguage("tr", "tur", "Turkish"),
        AnkiLanguage("tok", "tok", "Toki Pona"),
        AnkiLanguage("uk", "ukr", "Ukrainian"),
        AnkiLanguage("vi", "vie", "Vietnamese"),
        AnkiLanguage("cy", "cym", "Welsh"),
        AnkiLanguage("yi", "yid", "Yiddish"),
        AnkiLanguage("yue", "yue", "Cantonese"),
        AnkiLanguage("zh", "zho", "Chinese")
    ).associateBy { it.iso }

    /** The descriptor of the session language [iso], or [UNKNOWN] for a language the original does not list. */
    fun of(iso: String): AnkiLanguage = languages[iso] ?: UNKNOWN

    /**
     * The original's getRequiredAudioSources (ext/js/media/audio-downloader.js). It returns the
     * default sources for [language] (JapanesePod101, LanguagePod101, and Jisho for Japanese, and
     * Lingua Libre, LanguagePod101, and Wiktionary otherwise), minus the types [sources] already
     * has, each with no URL.
     */
    fun requiredAudioSources(language: String, sources: List<AnkiAudioSource>): List<AnkiAudioSource> {
        val defaults = if (language == "ja") {
            listOf(AnkiAudioSourceType.JPOD101, AnkiAudioSourceType.LANGUAGE_POD_101, AnkiAudioSourceType.JISHO)
        } else {
            listOf(
                AnkiAudioSourceType.LINGUA_LIBRE,
                AnkiAudioSourceType.LANGUAGE_POD_101,
                AnkiAudioSourceType.WIKTIONARY
            )
        }
        val configured = sources.map { it.type }.toSet()
        return defaults.filter { it !in configured }.map { AnkiAudioSource(it) }
    }
}
