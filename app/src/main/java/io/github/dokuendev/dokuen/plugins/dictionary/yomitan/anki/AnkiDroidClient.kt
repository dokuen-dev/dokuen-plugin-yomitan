package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi

data class AnkiNoteInfo(val id: Long, val modelId: Long, val fields: List<String>, val tags: Set<String>)

class AnkiDroidClient(
    private val context: Context,
    private val api: AddContentApi = AddContentApi(context),
    private val contentResolver: ContentResolver = context.contentResolver
) {
    companion object {
        const val PERMISSION: String = AddContentApi.READ_WRITE_PERMISSION
        private val NOTE_PROJECTION = arrayOf(
            FlashCardsContract.Note._ID,
            FlashCardsContract.Note.MID,
            FlashCardsContract.Note.FLDS,
            FlashCardsContract.Note.TAGS
        )
    }

    fun isAnkiDroidInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.ichi2.anki", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            AddContentApi.READ_WRITE_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getDeckList(): Map<Long, String> = api.deckList ?: emptyMap()

    fun getModelList(): Map<Long, String> = api.getModelList(1) ?: emptyMap()

    fun getFieldList(modelId: Long): List<String> = api.getFieldList(modelId)?.toList() ?: emptyList()

    fun addNote(modelId: Long, deckId: Long, fields: List<String>, tags: Set<String>): Long? {
        return api.addNote(modelId, deckId, fields.toTypedArray(), tags)
    }

    fun updateNoteFields(noteId: Long, fields: List<String>): Boolean {
        return api.updateNoteFields(noteId, fields.toTypedArray())
    }

    /**
     * The notes the original counts as duplicates of a new note whose first field is [key], as
     * AnkiConnect's add-time check (`isNoteDuplicateOrEmptyInScope`) finds them. Every note whose
     * first field, whatever it is named, has the same checksum as [key] (see [AnkiFieldChecksum]),
     * of the note type [modelId] unless [checkAllModels], and, for a deck scope, with a card in
     * one of the scope's decks. COLLECTION has no deck condition. DECK is the format's own deck
     * [deckId] only. DECK_ROOT is the top-level ancestor of that deck (the part of its name before
     * the first `::`, like the original's getRootDeckName) and every deck below it. If the deck no
     * longer exists, nothing is a duplicate, as in AnkiConnect. A key that is empty once its HTML
     * is stripped has no duplicates.
     *
     * AddContentApi.findDuplicateNotes cannot do this (it has no deck scope and matches only one
     * note type), so this queries FlashCardsContract.Note.CONTENT_URI_V2, whose selection is SQL
     * against AnkiDroid's notes table, the same query AddContentApi makes internally.
     */
    fun findDuplicateNotes(
        modelId: Long,
        deckId: Long,
        key: String,
        scope: AnkiDuplicateScope,
        checkAllModels: Boolean
    ): List<AnkiNoteInfo> {
        if (AnkiFieldChecksum.stripHtmlPreservingMediaFilenames(key).isBlank()) return emptyList()
        val deckIds = when (scope) {
            AnkiDuplicateScope.COLLECTION -> null
            AnkiDuplicateScope.DECK -> listOf(deckId)
            AnkiDuplicateScope.DECK_ROOT -> {
                val decks = getDeckList()
                val root = decks[deckId]?.let { rootDeckName(it) } ?: return emptyList()
                decks.filterValues { it == root || it.startsWith("$root::") }.keys.toList()
            }
        }
        val selection = buildString {
            append("${FlashCardsContract.Note.CSUM}=${AnkiFieldChecksum.checksum(key)}")
            if (!checkAllModels) append(" and ${FlashCardsContract.Note.MID}=$modelId")
            if (deckIds != null) {
                append(" and id in (select nid from cards where did in (${deckIds.joinToString(",")}))")
            }
        }
        return queryNotes(FlashCardsContract.Note.CONTENT_URI_V2, selection).sortedBy { it.id }
    }

    /** Every note matching [search], an Anki browser search string (not SQL). */
    fun findNotes(search: String): List<AnkiNoteInfo> = queryNotes(FlashCardsContract.Note.CONTENT_URI, search)

    private fun queryNotes(uri: Uri, selection: String): List<AnkiNoteInfo> {
        val cursor = contentResolver.query(uri, NOTE_PROJECTION, selection, null, null) ?: return emptyList()

        val results = mutableListOf<AnkiNoteInfo>()
        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
            val midIndex = c.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
            val fldsIndex = c.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)
            val tagsIndex = c.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
            while (c.moveToNext()) {
                val fields = c.getString(fldsIndex)?.split('\u001f') ?: emptyList()
                val tags = c.getString(tagsIndex)
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
                results.add(
                    AnkiNoteInfo(
                        id = c.getLong(idIndex),
                        modelId = c.getLong(midIndex),
                        fields = fields,
                        tags = tags
                    )
                )
            }
        }
        return results
    }

    private fun rootDeckName(deckName: String): String {
        val index = deckName.indexOf("::")
        return if (index >= 0) deckName.substring(0, index) else deckName
    }
}
