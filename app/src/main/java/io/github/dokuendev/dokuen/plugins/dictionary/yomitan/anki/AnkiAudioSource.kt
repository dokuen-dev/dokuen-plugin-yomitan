package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlinx.serialization.Serializable

/**
 * The original's audio source types (`audio.sources[].type` in ext/data/schemas/options-schema.json)
 * that `{audio}` downloads from. The two text-to-speech types are left out: the original never
 * exports them to Anki, since it only downloads audio found at a URL.
 */
@Serializable
enum class AnkiAudioSourceType { JPOD101, LANGUAGE_POD_101, JISHO, LINGUA_LIBRE, WIKTIONARY, CUSTOM, CUSTOM_JSON }

/** One entry of the ordered audio source list. Only the custom types use [url]. */
@Serializable
data class AnkiAudioSource(
    val type: AnkiAudioSourceType = AnkiAudioSourceType.JPOD101,
    val url: String = ""
)
