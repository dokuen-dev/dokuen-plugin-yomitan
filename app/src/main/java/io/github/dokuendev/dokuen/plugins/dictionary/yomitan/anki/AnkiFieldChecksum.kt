package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.security.MessageDigest

/**
 * Anki's first-field checksum, the `csum` column of its notes table, which Anki and AnkiConnect
 * use to find duplicate notes: the first 32 bits of the SHA-1 of the field's text with its HTML
 * stripped and media file names kept (`field_checksum` and
 * `strip_html_preserving_media_filenames` in Anki's rslib). AnkiDroid's own
 * `AddContentApi.findDuplicateNotes` computes it the same way.
 */
object AnkiFieldChecksum {
    /** An img, audio, video, or object tag, with the file name of its src or data attribute. */
    private val mediaTag = Regex(
        "<\\b(?:img|audio|video|object)\\b(?:[^>\"']|\"[^\"]*?\"|'[^']*?')*?\\b(?:src|data)\\b=" +
                "(?:\"([^\"]+?)\"[^>]*>|'([^']+?)'[^>]*>|([^ >]+)[^>]*>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val html = Regex(
        "<!--.*?-->|<style.*?>.*?</style>|<script.*?>.*?</script>|<.*?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val entity = Regex("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z]+));")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0"
    )

    fun checksum(field: String): Long {
        val digest =
            MessageDigest.getInstance("SHA-1")
                .digest(stripHtmlPreservingMediaFilenames(field).toByteArray(Charsets.UTF_8))
        return digest.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    /**
     * The field's text as Anki compares it: each media tag replaced by its file name, other tags,
     * comments, styles, and scripts removed, and character references decoded, with a non-breaking
     * space read as a space.
     */
    fun stripHtmlPreservingMediaFilenames(field: String): String {
        val withoutMedia = mediaTag.replace(field) { match ->
            " " + match.groupValues.drop(1).joinToString("") + " "
        }
        val withoutTags = html.replace(withoutMedia, "")
        if ('&' !in withoutTags) return withoutTags
        return entity.replace(withoutTags) { match ->
            val (decimal, hex, name) = match.destructured
            when {
                decimal.isNotEmpty() -> decimal.toIntOrNull()?.let { codePointString(it) }
                hex.isNotEmpty() -> hex.toIntOrNull(16)?.let { codePointString(it) }
                else -> namedEntities[name]
            } ?: match.value
        }.replace('\u00a0', ' ')
    }

    private fun codePointString(codePoint: Int): String? =
        if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else null
}
