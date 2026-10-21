package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Media file names in the original's formats. [generate] names a file as
 * generateAnkiNoteMediaFileName and ankNoteDateToString in ext/js/data/anki-util.js do: the
 * prefix, then `_` and the UTC date as year-month-day-hours-minutes-seconds-milliseconds, then
 * the extension. The month is zero-based (January is 00), as the original's getUTCMonth() prints
 * it.
 */
object AnkiMediaFileNames {
    private val invalidCharacters = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")

    fun generate(prefix: String, extension: String, timestampMillis: Long): String {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestampMillis }
        val date = String.format(
            Locale.ROOT,
            "%d-%02d-%02d-%02d-%02d-%02d-%03d",
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH),
            utc.get(Calendar.DAY_OF_MONTH),
            utc.get(Calendar.HOUR_OF_DAY),
            utc.get(Calendar.MINUTE),
            utc.get(Calendar.SECOND),
            utc.get(Calendar.MILLISECOND)
        )
        return "${prefix}_$date$extension".replace(invalidCharacters, "-")
    }

    /**
     * The original's mediaFileNameHashOrTimestamp (ext/js/data/anki-util.js), naming the file as a
     * browser that can hash the content does. The name is the prefix, then `_` and the lowercase
     * hex SHA-1 of [content], then the extension, with the same invalid-character replacement as
     * [generate].
     */
    fun hashed(prefix: String, content: ByteArray, extension: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(content)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        return "${prefix}_$digest$extension".replace(invalidCharacters, "-")
    }

    /**
     * The original's getFileExtensionFromAudioMediaType (ext/js/media/media-util.js) for a
     * response's Content-Type, compared exactly as the original compares it, or `.mp3` when
     * nothing matches, which is what _injectAnkiNoteAudio falls back to.
     */
    fun audioExtension(contentType: String?): String = when (contentType) {
        "audio/aac" -> ".aac"
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4" -> ".mp4"
        "audio/ogg", "audio/vorbis", "application/ogg" -> ".ogg"
        "audio/vnd.wav", "audio/wave", "audio/wav", "audio/x-wav", "audio/x-pn-wav" -> ".wav"
        "audio/flac" -> ".flac"
        "audio/webm" -> ".webm"
        else -> ".mp3"
    }
}
