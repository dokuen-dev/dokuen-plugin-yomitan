package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Media file names in the original's format (generateAnkiNoteMediaFileName and
 * ankNoteDateToString in ext/js/data/anki-util.js): the prefix, then `_` and the UTC date as
 * year-month-day-hours-minutes-seconds-milliseconds, then the extension. The month is
 * zero-based (January is 00), as the original's getUTCMonth() prints it.
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
}
