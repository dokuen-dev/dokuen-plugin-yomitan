package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.mockito.kotlin.mock as kmock

@RunWith(RobolectricTestRunner::class)
class AnkiDroidClientTest {

    @Test
    fun testGetDeckListDelegatesToApi() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val api = mock(AddContentApi::class.java)
        `when`(api.deckList).thenReturn(mapOf(1L to "Default", 2L to "Japanese"))

        val client = AnkiDroidClient(app, api)

        assertEquals(mapOf(1L to "Default", 2L to "Japanese"), client.getDeckList())
    }

    @Test
    fun testGetDeckListReturnsEmptyMapWhenApiReturnsNull() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val api = mock(AddContentApi::class.java)
        `when`(api.deckList).thenReturn(null)

        val client = AnkiDroidClient(app, api)

        assertEquals(emptyMap<Long, String>(), client.getDeckList())
    }

    @Test
    fun testAddNoteDelegatesAndReturnsNoteId() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val api = mock(AddContentApi::class.java)
        `when`(api.addNote(1L, 2L, arrayOf("Front", "Back"), setOf("dokuen"))).thenReturn(42L)

        val client = AnkiDroidClient(app, api)
        val noteId = client.addNote(1L, 2L, listOf("Front", "Back"), setOf("dokuen"))

        assertEquals(42L, noteId)
    }

    @Test
    fun testAddNoteReturnsNullOnFailure() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val api = mock(AddContentApi::class.java)
        `when`(api.addNote(1L, 2L, arrayOf("Front"), emptySet())).thenReturn(null)

        val client = AnkiDroidClient(app, api)

        assertNull(client.addNote(1L, 2L, listOf("Front"), emptySet()))
    }

    private fun capturedSelection(
        deckList: Map<Long, String>? = null,
        block: (AnkiDroidClient) -> Unit
    ): String? {
        val app: Application = ApplicationProvider.getApplicationContext()
        val resolver = kmock<ContentResolver>()
        val captor = argumentCaptor<String>()
        whenever(
            resolver.query(
                eq(FlashCardsContract.Note.CONTENT_URI_V2),
                any(),
                captor.capture(),
                isNull(),
                isNull()
            )
        )
            .thenReturn(null)
        val api = mock(AddContentApi::class.java)
        `when`(api.deckList).thenReturn(deckList)
        block(AnkiDroidClient(app, api, resolver))
        return captor.allValues.singleOrNull()
    }

    private fun AnkiDroidClient.duplicates(
        scope: AnkiDuplicateScope,
        deckId: Long = 7L,
        key: String = "食べる",
        checkAllModels: Boolean = false
    ) = findDuplicateNotes(
        modelId = 3L,
        deckId = deckId,
        key = key,
        scope = scope,
        checkAllModels = checkAllModels
    )

    private val checksum = AnkiFieldChecksum.checksum("食べる")

    @Test
    fun testFindDuplicateNotesCollectionMatchesTheChecksumAndNoteType() {
        val selection = capturedSelection { it.duplicates(AnkiDuplicateScope.COLLECTION) }

        assertEquals("csum=$checksum and mid=3", selection)
    }

    @Test
    fun testFindDuplicateNotesCheckAllModelsOmitsTheNoteType() {
        // The checksum is of the first field, whatever it is named, so a note of another type
        // whose first field has another name is found too, as AnkiConnect finds it.
        val selection = capturedSelection { it.duplicates(AnkiDuplicateScope.COLLECTION, checkAllModels = true) }

        assertEquals("csum=$checksum", selection)
    }

    @Test
    fun testFindDuplicateNotesDeckIsTheExactDeck() {
        val selection = capturedSelection { it.duplicates(AnkiDuplicateScope.DECK) }

        assertEquals("csum=$checksum and mid=3 and id in (select nid from cards where did in (7))", selection)
    }

    @Test
    fun testFindDuplicateNotesDeckRootIsTheRootDeckAndEveryDeckBelowIt() {
        // The original's getRootDeckName of "Japanese::Mining::Core" is "Japanese", so a
        // duplicate in the sibling "Japanese::Reading" must be found, and one in "Japanese2" not.
        val decks = mapOf(
            7L to "Japanese::Mining::Core",
            8L to "Japanese",
            9L to "Japanese::Reading",
            10L to "Japanese2",
            11L to "Other::Japanese"
        )
        val selection = capturedSelection(deckList = decks) { it.duplicates(AnkiDuplicateScope.DECK_ROOT) }

        assertEquals("csum=$checksum and mid=3 and id in (select nid from cards where did in (7,8,9))", selection)
    }

    @Test
    fun testFindDuplicateNotesDeckRootOfADeletedDeckFindsNothing() {
        val selection = capturedSelection(deckList = mapOf(99L to "Other")) {
            assertEquals(emptyList<AnkiNoteInfo>(), it.duplicates(AnkiDuplicateScope.DECK_ROOT))
        }

        assertNull(selection)
    }

    @Test
    fun testFindDuplicateNotesUsesTheWholeKeyIncludingQuotes() {
        // The checksum is of the stored text, so a key with a quote only matches a note whose
        // first field has the same quote.
        val selection = capturedSelection { it.duplicates(AnkiDuplicateScope.COLLECTION, key = "手紙\"") }

        assertEquals("csum=${AnkiFieldChecksum.checksum("手紙\"")} and mid=3", selection)
    }

    @Test
    fun testFindDuplicateNotesOfAnEmptyKeyFindsNothing() {
        val selection = capturedSelection {
            assertEquals(emptyList<AnkiNoteInfo>(), it.duplicates(AnkiDuplicateScope.COLLECTION, key = "<b> </b>"))
        }

        assertNull(selection)
    }

    @Test
    fun testFindDuplicateNotesMapsCursorRowsToAnkiNoteInfo() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val resolver = kmock<ContentResolver>()
        val cursor = kmock<Cursor>()
        whenever(cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID)).thenReturn(0)
        whenever(cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID)).thenReturn(3)
        whenever(cursor.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)).thenReturn(1)
        whenever(cursor.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)).thenReturn(2)
        whenever(cursor.moveToNext()).thenReturn(true, false)
        whenever(cursor.getLong(0)).thenReturn(99L)
        whenever(cursor.getLong(3)).thenReturn(42L)
        whenever(cursor.getString(1)).thenReturn("食べる\u001fto eat")
        whenever(cursor.getString(2)).thenReturn(" dokuen  vocab ")
        whenever(resolver.query(eq(FlashCardsContract.Note.CONTENT_URI_V2), any(), any(), isNull(), isNull()))
            .thenReturn(cursor)

        val client = AnkiDroidClient(app, AddContentApi(app), resolver)
        val duplicates = client.findDuplicateNotes(
            modelId = 3L,
            deckId = 7L,
            key = "食べる",
            scope = AnkiDuplicateScope.COLLECTION,
            checkAllModels = false
        )

        assertEquals(1, duplicates.size)
        assertEquals(99L, duplicates[0].id)
        assertEquals(42L, duplicates[0].modelId)
        assertEquals(listOf("食べる", "to eat"), duplicates[0].fields)
        assertEquals(setOf("dokuen", "vocab"), duplicates[0].tags)
    }

    /** Records the URI grants addImage makes and revokes. */
    private class GrantRecordingContext(base: Context) : ContextWrapper(base) {
        val granted = mutableListOf<Uri>()
        val revoked = mutableListOf<Uri>()

        override fun grantUriPermission(toPackage: String?, uri: Uri, modeFlags: Int) {
            granted += uri
        }

        override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
            revoked += uri
        }
    }

    private fun mediaFile(context: Context) = File(File(context.cacheDir, "anki_media"), "shot.png")

    // FileProvider.getUriForFile rejects Windows paths, so tests supply the URI themselves.
    private fun fakeMediaUri(file: File): Uri = Uri.parse("content://test.fileprovider/anki_media/${file.name}")

    @Test
    fun addImageInsertsTheWrittenFileAndReturnsTheStoredName() {
        val context = GrantRecordingContext(ApplicationProvider.getApplicationContext())
        val resolver = mock(ContentResolver::class.java)
        val valuesCaptor = argumentCaptor<ContentValues>()
        var contentAtInsert: ByteArray? = null
        whenever(resolver.insert(eq(FlashCardsContract.AnkiMedia.CONTENT_URI), valuesCaptor.capture())).thenAnswer {
            contentAtInsert = mediaFile(context).readBytes()
            Uri.parse("content://com.ichi2.anki.flashcards/media/shot_1.png")
        }
        val client = AnkiDroidClient(context, mock(AddContentApi::class.java), resolver, ::fakeMediaUri)

        val stored = client.addImage("shot.png") { out ->
            out.write(byteArrayOf(1, 2, 3))
            true
        }

        assertEquals("shot_1.png", stored)
        assertEquals(listOf<Byte>(1, 2, 3), contentAtInsert?.toList())
        val values = valuesCaptor.firstValue
        assertEquals("shot", values.getAsString(FlashCardsContract.AnkiMedia.PREFERRED_NAME))
        assertEquals(
            "content://test.fileprovider/anki_media/shot.png",
            values.getAsString(FlashCardsContract.AnkiMedia.FILE_URI)
        )
        assertFalse(mediaFile(context).exists())
    }

    @Test
    fun addImageRevokesTheGrantItMade() {
        val context = GrantRecordingContext(ApplicationProvider.getApplicationContext())
        val resolver = mock(ContentResolver::class.java)
        whenever(resolver.insert(any(), any())).thenReturn(Uri.parse("content://com.ichi2.anki.flashcards/media/x.png"))
        val client = AnkiDroidClient(context, mock(AddContentApi::class.java), resolver, ::fakeMediaUri)

        client.addImage("shot.png") { true }

        assertEquals(listOf(Uri.parse("content://test.fileprovider/anki_media/shot.png")), context.granted)
        assertEquals(context.granted, context.revoked)
    }

    @Test
    fun addImageReturnsNullWhenAnkiDroidRefuses() {
        val context = GrantRecordingContext(ApplicationProvider.getApplicationContext())
        val resolver = mock(ContentResolver::class.java)
        whenever(resolver.insert(any(), any())).thenReturn(null)
        val client = AnkiDroidClient(context, mock(AddContentApi::class.java), resolver, ::fakeMediaUri)

        assertNull(client.addImage("shot.png") { true })
        assertEquals(context.granted, context.revoked)
        assertFalse(mediaFile(context).exists())
    }

    @Test
    fun addImageInsertsNothingWhenTheWriterFails() {
        val context = GrantRecordingContext(ApplicationProvider.getApplicationContext())
        val resolver = mock(ContentResolver::class.java)
        val client = AnkiDroidClient(context, mock(AddContentApi::class.java), resolver, ::fakeMediaUri)

        assertNull(client.addImage("shot.png") { false })
        verify(resolver, never()).insert(any(), any())
        assertTrue(context.granted.isEmpty())
        assertFalse(mediaFile(context).exists())
    }

    @Test
    fun addImageDeletesTheFileAndRevokesTheGrantWhenInsertThrows() {
        val context = GrantRecordingContext(ApplicationProvider.getApplicationContext())
        val resolver = mock(ContentResolver::class.java)
        whenever(resolver.insert(any(), any())).thenThrow(SecurityException("no access"))
        val client = AnkiDroidClient(context, mock(AddContentApi::class.java), resolver, ::fakeMediaUri)

        assertThrows(SecurityException::class.java) { client.addImage("shot.png") { true } }
        assertEquals(1, context.revoked.size)
        assertFalse(mediaFile(context).exists())
    }
}
