package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * Pure helpers for reconciling the free-text "Tags" field in the Anki export settings UI
 * with the upstream (persisted) list of tags. Extracted so the comparison logic used to
 * decide whether the text field should be resynced from upstream state (without clobbering
 * in-progress typing) is independently testable.
 */
object TagsTextUtil {
    /** The original's splitTags: tags are separated by any run of commas, semicolons and spaces. */
    fun parseTags(text: String): List<String> =
        text.split(Regex("[,; ]+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The original's joinTags: how the field shows the tags when it is not being edited. */
    fun formatTags(tags: List<String>): String = tags.joinToString(" ")

    fun shouldResyncFromUpstream(localText: String, upstreamTags: List<String>): Boolean =
        parseTags(localText) != upstreamTags
}
