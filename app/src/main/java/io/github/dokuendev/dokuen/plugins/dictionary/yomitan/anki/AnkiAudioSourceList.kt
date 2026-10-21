package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/** The Audio settings card's edits to the audio source list and its parsing of the timeout. */
object AnkiAudioSourceList {
    /**
     * The types the original's add button picks from, in its order for Japanese
     * (`_getUnusedAudioSourceType` in `audio-controller.js`). The original's Text-to-speech type is
     * left out because the plugin has none, and Custom URL (JSON) is not among them.
     */
    private val ADD_ORDER = listOf(
        AnkiAudioSourceType.JPOD101,
        AnkiAudioSourceType.JISHO,
        AnkiAudioSourceType.LANGUAGE_POD_101,
        AnkiAudioSourceType.LINGUA_LIBRE,
        AnkiAudioSourceType.WIKTIONARY,
        AnkiAudioSourceType.CUSTOM
    )

    /**
     * Appends a source of the first type in [ADD_ORDER] that [sources] does not have, or
     * JapanesePod101 when it has all of them, as the original's add button does for Japanese.
     */
    fun add(sources: List<AnkiAudioSource>): List<AnkiAudioSource> {
        val listed = sources.map { it.type }.toSet()
        val type = ADD_ORDER.firstOrNull { it !in listed } ?: AnkiAudioSourceType.JPOD101
        return sources + AnkiAudioSource(type)
    }

    fun remove(sources: List<AnkiAudioSource>, index: Int): List<AnkiAudioSource> =
        sources.filterIndexed { i, _ -> i != index }

    /**
     * Swaps the source at [index] with the one [offset] places away, or does nothing if that place
     * is past either end of the list.
     */
    fun move(sources: List<AnkiAudioSource>, index: Int, offset: Int): List<AnkiAudioSource> {
        val target = index + offset
        if (index !in sources.indices || target !in sources.indices) return sources
        return sources.toMutableList().apply {
            this[index] = sources[target]
            this[target] = sources[index]
        }
    }

    fun update(
        sources: List<AnkiAudioSource>,
        index: Int,
        transform: (AnkiAudioSource) -> AnkiAudioSource
    ): List<AnkiAudioSource> = sources.mapIndexed { i, source -> if (i == index) transform(source) else source }

    /**
     * Whether a source of [type] takes a URL. The original shows its URL field only for such
     * types.
     */
    fun usesUrl(type: AnkiAudioSourceType): Boolean =
        type == AnkiAudioSourceType.CUSTOM || type == AnkiAudioSourceType.CUSTOM_JSON

    /** The timeout typed into the settings field, or null unless it is a whole number of milliseconds of at least 0. */
    fun parseTimeout(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 0 }
}
