package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * Combines an existing note field value with a newly rendered one according to the field's
 * overwrite mode. Mirrors the original's _getOverwrittenField (ext/js/display/display-anki.js),
 * including its plain concatenation for append/prepend (no separator is inserted).
 */
object AnkiFieldMerger {
    fun merge(existing: String, new: String, mode: AnkiFieldOverwriteMode): String = when (mode) {
        AnkiFieldOverwriteMode.OVERWRITE -> new
        AnkiFieldOverwriteMode.SKIP -> existing
        AnkiFieldOverwriteMode.APPEND -> existing + new
        AnkiFieldOverwriteMode.PREPEND -> new + existing
        AnkiFieldOverwriteMode.COALESCE -> existing.ifEmpty { new }
        AnkiFieldOverwriteMode.COALESCE_NEW -> new.ifEmpty { existing }
    }
}
