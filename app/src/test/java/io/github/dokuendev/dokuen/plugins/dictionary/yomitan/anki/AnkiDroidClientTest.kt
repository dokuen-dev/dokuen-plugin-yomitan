package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
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
}
