package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * Which host-provided markers (images, clipboard text, and the sentence furigana) a note write
 * will actually use, so the host is only asked for what ends up on the card.
 */
object AnkiMediaNeeds {
    const val SCREENSHOT = "screenshot"
    const val CLIPBOARD_IMAGE = "clipboard-image"
    const val CLIPBOARD_TEXT = "clipboard-text"

    /** The image markers, in the order their images are requested. */
    val IMAGE_MARKERS = listOf(SCREENSHOT, CLIPBOARD_IMAGE)

    /**
     * Whether `{marker}` appears in a field of the note type's current [fieldNames] whose newly
     * rendered value the write keeps. When adding a note ([existing] null), every such field
     * counts. When overwriting [existing], a Skip field never keeps the new value, and a
     * "Fill if empty" field only when its existing value is empty (see AnkiFieldMerger).
     */
    fun isNeeded(
        marker: String,
        fieldNames: List<String>,
        fields: Map<String, AnkiField>,
        existing: AnkiNoteInfo?
    ): Boolean {
        val token = "{$marker}"
        return fieldNames.withIndex().any { (index, name) ->
            val field = fields[name] ?: return@any false
            if (!field.value.contains(token)) return@any false
            if (existing == null) return@any true
            when (field.overwriteMode) {
                AnkiFieldOverwriteMode.SKIP -> false
                AnkiFieldOverwriteMode.COALESCE -> existing.fields.getOrElse(index) { "" }.isEmpty()
                else -> true
            }
        }
    }
}
