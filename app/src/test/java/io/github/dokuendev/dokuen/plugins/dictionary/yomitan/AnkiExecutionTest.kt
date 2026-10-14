package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDuplicateScope
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiFieldOverwriteMode
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiNoteInfo
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotBehavior
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiScreenshotFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettings
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettingsStore
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import io.github.dokuendev.dokuenreader.dictionary.CustomActionResult
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.dictionary.DictionaryResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestResult
import io.github.dokuendev.dokuenreader.dictionary.ImageRequestSource
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.WordReading
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URLEncoder

/**
 * Behavior of the tap path (onLookup -> action link -> onExecuteCustomAction) against a
 * mocked AnkiDroidClient: duplicate handling, template validation, settings snapshots,
 * and the token cache.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiExecutionTest {

    private companion object {
        // The link colors of the duplicate states, as the original's icons render them.
        val GREEN = 0xFF6FB558.toInt()
        val PURPLE = 0xFFD08AE7.toInt()
        val ORANGE = 0xFFFF8727.toInt()
        val GRAY = 0xFF9F9F9F.toInt()
    }

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val dictTitle = "Exec Dict"

    @Before
    fun setUp() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            db = AppDatabase.getDatabase(app)
            withContext(Dispatchers.IO) {
                db.clearAllTables()
                db.dictionaryDao().insertDictionary(
                    DictionaryEntity(
                        title = dictTitle, revision = "1.0", sequenced = true, version = 3,
                        importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
                        styles = "", counts = null, importSuccess = true
                    )
                )
                db.termDao().bulkAdd(
                    listOf(
                        TermEntity(
                            dictionary = dictTitle, expression = "食べる", reading = "たべる",
                            definitionTags = "v1", rules = "v1", score = 100.0, sequence = 1,
                            termTags = "", glossary = "[\"to eat\"]"
                        ),
                        TermEntity(
                            dictionary = dictTitle, expression = "水入り", reading = "みずいり",
                            definitionTags = "", rules = "", score = 100.0, sequence = 2,
                            termTags = "", glossary = "[\"water entry\"]"
                        )
                    )
                )
            }
            app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE).edit()
                .putString("installed_dictionaries", dictTitle)
                .putString("active_dictionaries", dictTitle)
                .commit()
        }
    }

    private fun format(
        name: String = "Vocab",
        modelId: Long = 2L,
        fields: Map<String, AnkiField> = mapOf(
            "Front" to AnkiField(value = "{expression} ({reading})"),
            "Back" to AnkiField(value = "{reading}")
        )
    ) = AnkiCardFormat(
        name = name,
        type = AnkiEntryType.TERM,
        deckId = 1L,
        deckName = "Default",
        modelId = modelId,
        modelName = "Basic",
        fields = fields
    )

    private fun settings(
        first: AnkiCardFormat = format(),
        vararg rest: AnkiCardFormat,
        enabled: Boolean = true,
        behavior: AnkiDuplicateBehavior = AnkiDuplicateBehavior.PREVENT,
        checkForDuplicates: Boolean = true,
        tags: List<String> = emptyList()
    ) = AnkiSettings(
        enabled = enabled,
        cardFormats = listOf(first) + rest,
        tags = tags,
        checkForDuplicates = checkForDuplicates,
        duplicateBehavior = behavior
    )

    private fun mockClient(): AnkiDroidClient {
        val client = mock(AnkiDroidClient::class.java)
        whenever(client.isAnkiDroidInstalled()).thenReturn(true)
        whenever(client.hasPermission()).thenReturn(true)
        whenever(client.getFieldList(any())).thenReturn(listOf("Front", "Back"))
        whenever(client.getDeckList()).thenReturn(mapOf(1L to "Default"))
        whenever(client.addNote(any(), any(), any(), any())).thenReturn(999L)
        whenever(client.updateNoteFields(any(), any())).thenReturn(true)
        stubDuplicates(client, emptyList())
        return client
    }

    private fun stubDuplicates(client: AnkiDroidClient, duplicates: List<AnkiNoteInfo>) {
        whenever(
            client.findDuplicateNotes(any(), any(), any(), any<AnkiDuplicateScope>(), any())
        ).thenReturn(duplicates)
    }

    private suspend fun newService(
        settings: AnkiSettings,
        client: AnkiDroidClient,
        language: String? = null
    ): YomitanDictionaryPluginService {
        AnkiSettingsStore.save(app, settings)
        val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
        service.ankiDroidClient = client
        service.onInitialize(Bundle().apply {
            putString(PluginHostConfigKeys.UI_THEME, "light")
            language?.let { putString(PluginHostConfigKeys.LANGUAGE, it) }
        })
        return service
    }

    private suspend fun lookup(service: YomitanDictionaryPluginService, word: String): DictionaryResult {
        val query = "?query=${URLEncoder.encode(word, "UTF-8")}&wildcards=off"
        return service.onLookup(query, 0, query.length)
    }

    private fun addPayloads(entry: DictionaryEntry): List<String> =
        entry.body.styledSpans!!
            .mapNotNull { it.style.linkUrl }
            .filter { it.startsWith("action:anki_add?") }
            .map { it.removePrefix("action:") }

    /** The text of each of [entry]'s action links, in order. */
    private fun linkLabels(entry: DictionaryEntry): List<String> =
        entry.body.styledSpans!!
            .filter { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            .sortedBy { it.startIndex }
            .map { entry.body.text.substring(it.startIndex, it.endIndex) }

    /** The color of each of [entry]'s action links, in order. */
    private fun linkColors(entry: DictionaryEntry): List<Int> =
        entry.body.styledSpans!!
            .filter { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            .sortedBy { it.startIndex }
            .map { it.style.foregroundColor }

    /** Makes the duplicate search for note type [modelId] find [duplicates]. */
    private fun stubDuplicatesFor(client: AnkiDroidClient, modelId: Long, duplicates: List<AnkiNoteInfo>) {
        whenever(
            client.findDuplicateNotes(eq(modelId), any(), any(), any<AnkiDuplicateScope>(), any())
        ).thenReturn(duplicates)
    }

    private fun verifyDuplicateSearches(client: AnkiDroidClient, count: Int) {
        verify(client, times(count)).findDuplicateNotes(any(), any(), any(), any<AnkiDuplicateScope>(), any())
    }

    /** The single entry an UpdateResult carries. */
    private fun updatedEntry(result: CustomActionResult): DictionaryEntry =
        (result as CustomActionResult.UpdateResult).result.entries.single()

    /** The warning an UpdateResult carries along with the refreshed entries. */
    private fun warning(result: CustomActionResult): String =
        (result as CustomActionResult.UpdateResult).message!!

    private suspend fun executeError(service: YomitanDictionaryPluginService, payload: String): String {
        try {
            service.executeAction(payload)
        } catch (e: DictionaryException) {
            return e.message ?: ""
        }
        fail("Expected DictionaryException")
        return ""
    }

    private fun duplicate(id: Long = 55L, vararg fields: String, modelId: Long = 2L) =
        AnkiNoteInfo(id = id, modelId = modelId, fields = fields.toList(), tags = emptySet())

    @Test
    fun tapAddsNoteWithRenderedFieldsAndConfiguredTags() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(tags = listOf("dokuen", "mined")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        val tagsCaptor = argumentCaptor<Set<String>>()
        verify(client).addNote(eq(2L), eq(1L), fieldsCaptor.capture(), tagsCaptor.capture())
        assertEquals(listOf("食べる (たべる)", "たべる"), fieldsCaptor.firstValue)
        assertEquals(setOf("dokuen", "mined"), tagsCaptor.firstValue)
        assertTrue(result is CustomActionResult.UpdateResult)
    }

    @Test
    fun unknownMarkerIsWrittenAsRenderErrorAndReported() = runBlocking<Unit> {
        val client = mockClient()
        val badFormat =
            format(fields = mapOf("Front" to AnkiField(value = "{expression}"), "Back" to AnkiField("{expresion}")))
        val service = newService(settings(badFormat), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(listOf("食べる", "{expresion-render-error}"), fieldsCaptor.firstValue)
        val message = warning(result)
        assertTrue(message, message.contains("{expresion}"))
        assertTrue(message, message.contains("Vocab"))
        assertEquals(listOf("● Added: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun fieldsTheNoteTypeNoLongerHasAreNeitherSentNorReported() = runBlocking<Unit> {
        val client = mockClient()
        val fmt = format(
            fields = mapOf(
                "Front" to AnkiField(value = "{expression}"),
                "Back" to AnkiField(value = "{reading}"),
                "Removed" to AnkiField(value = "{expresion}")
            )
        )
        val service = newService(settings(fmt), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(listOf("食べる", "たべる"), fieldsCaptor.firstValue)
        assertTrue(result is CustomActionResult.UpdateResult)
    }

    @Test
    fun recognizedButUnimplementedMarkersAreNotErrors() = runBlocking<Unit> {
        val client = mockClient()
        val fmt = format(
            fields = mapOf(
                "Front" to AnkiField(value = "{expression}"),
                "Back" to AnkiField("{audio}{screenshot}")
            )
        )
        val service = newService(settings(fmt), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(listOf("食べる", ""), fieldsCaptor.firstValue)
    }

    @Test
    fun duplicateSearchReceivesIdsAndSettingsScope() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(
            settings().copy(duplicateScope = AnkiDuplicateScope.DECK_ROOT, duplicateScopeCheckAllModels = true),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client).findDuplicateNotes(
            modelId = 2L,
            deckId = 1L,
            key = "食べる (たべる)",
            scope = AnkiDuplicateScope.DECK_ROOT,
            checkAllModels = true
        )
    }

    @Test
    fun preventBehaviorReportsExistingNoteAndWritesNothing() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる (たべる)", "x")))
        val service = newService(settings(behavior = AnkiDuplicateBehavior.PREVENT), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        assertTrue(result is CustomActionResult.SuccessMessage)
        assertTrue((result as CustomActionResult.SuccessMessage).message.contains("already exists"))
        verify(client, never()).addNote(any(), any(), any(), any())
        verify(client, never()).updateNoteFields(any(), any())
    }

    @Test
    fun overwriteBehaviorMergesPerFieldModeAndMarksTheLinkOverwritten() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "旧", "old")))
        val fmt = format(
            fields = mapOf(
                "Front" to AnkiField(value = "{expression}", overwriteMode = AnkiFieldOverwriteMode.OVERWRITE),
                "Back" to AnkiField(value = "{reading}", overwriteMode = AnkiFieldOverwriteMode.APPEND)
            )
        )
        val service = newService(settings(fmt, behavior = AnkiDuplicateBehavior.OVERWRITE), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).updateNoteFields(eq(55L), fieldsCaptor.capture())
        // APPEND concatenates with no separator, like the original.
        assertEquals(listOf("食べる", "oldたべる"), fieldsCaptor.firstValue)
        verify(client, never()).addNote(any(), any(), any(), any())
        assertEquals(listOf("● Overwritten: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun alwaysAddBehaviorAddsDespiteDuplicates() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる (たべる)", "x")))
        val service = newService(settings(behavior = AnkiDuplicateBehavior.ALWAYS_ADD), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client).addNote(any(), any(), any(), any())
        verify(client, never()).updateNoteFields(any(), any())
    }

    @Test
    fun disabledDuplicateCheckSkipsTheSearch() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(checkForDuplicates = false), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client, never()).findDuplicateNotes(any(), any(), any(), any<AnkiDuplicateScope>(), any())
        verify(client).addNote(any(), any(), any(), any())
    }

    @Test
    fun emptyKeyFieldSkipsTheSearchButStillAdds() = runBlocking<Unit> {
        val client = mockClient()
        val fmt = format(fields = mapOf("Front" to AnkiField(value = "{audio}"), "Back" to AnkiField("{reading}")))
        val service = newService(settings(fmt), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client, never()).findDuplicateNotes(any(), any(), any(), any<AnkiDuplicateScope>(), any())
        verify(client).addNote(any(), any(), any(), any())
    }

    @Test
    fun addFailureIsReported() = runBlocking<Unit> {
        val client = mockClient()
        whenever(client.addNote(any(), any(), any(), any())).thenReturn(null)
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, payload)

        assertTrue(message, message.contains("Failed to add"))
    }

    @Test
    fun aDeletedDeckIsReportedInsteadOfAddingTheNote() = runBlocking<Unit> {
        // AnkiDroid would add the note anyway, with its cards in no deck.
        val client = mockClient()
        whenever(client.getDeckList()).thenReturn(mapOf(5L to "Other"))
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, payload)

        assertTrue(message, message.contains("\"Default\", which no longer exists"))
        assertTrue(message, message.contains("\"Vocab\""))
        verify(client, never()).addNote(any(), any(), any(), any())
    }

    @Test
    fun aDeletedNoteTypeIsReportedInsteadOfAddingTheNote() = runBlocking<Unit> {
        val client = mockClient()
        whenever(client.getFieldList(any())).thenReturn(emptyList())
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, payload)

        assertTrue(message, message.contains("\"Basic\", which no longer exists"))
        verify(client, never()).addNote(any(), any(), any(), any())
    }

    @Test
    fun ankiDroidExceptionIsReportedAsDictionaryException() = runBlocking<Unit> {
        val client = mockClient()
        whenever(client.addNote(any(), any(), any(), any())).thenThrow(SecurityException("permission revoked"))
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, payload)

        assertTrue(message, message.contains("AnkiDroid could not complete"))
    }

    @Test
    fun updateFailureIsReported() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる (たべる)", "x")))
        whenever(client.updateNoteFields(any(), any())).thenReturn(false)
        val service = newService(settings(behavior = AnkiDuplicateBehavior.OVERWRITE), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, payload)

        assertTrue(message, message.contains("Failed to update"))
    }

    @Test
    fun linkFromAnEarlierLookupStillWorksAndUpdatesThatLookupsEntries() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(), client)
        val first = lookup(service, "食べる")
        val firstPayload = addPayloads(first.entries[0]).single()
        // A later lookup must neither invalidate the first one's links nor be what the
        // first one's UpdateResult carries: the host replaces its display with that result.
        lookup(service, "水入り")

        val result = service.executeAction(firstPayload)

        assertTrue(result is CustomActionResult.UpdateResult)
        val entries = (result as CustomActionResult.UpdateResult).result.entries
        assertEquals(1, entries.size)
        assertEquals(first.entries[0].headword, entries[0].headword)
        assertEquals(listOf("● Added: Vocab"), linkLabels(entries[0]))
    }

    @Test
    fun formatIndexResolvesAgainstTheFormatsSeenWhenTheLinkWasRendered() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(format(name = "Vocab", modelId = 2L), tags = listOf("old-tag")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        // Settings edited after the popup was shown: a new first format, different tags.
        AnkiSettingsStore.save(
            app,
            settings(
                format(name = "Other", modelId = 9L),
                format(name = "Vocab", modelId = 2L),
                tags = listOf("new-tag")
            )
        )
        service.executeAction(payload)

        val tagsCaptor = argumentCaptor<Set<String>>()
        verify(client).addNote(eq(2L), eq(1L), any(), tagsCaptor.capture())
        assertEquals(setOf("old-tag"), tagsCaptor.firstValue)
    }

    @Test
    fun disabledSettingsRenderNoLinks() = runBlocking<Unit> {
        val service = newService(settings(enabled = false), mockClient())

        val entry = lookup(service, "食べる").entries[0]

        assertTrue(addPayloads(entry).isEmpty())
        assertFalse(entry.body.text.contains("Add: Vocab"))
    }

    @Test
    fun turningExportOffAfterRenderingRefusesTheTap() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        AnkiSettingsStore.save(app, settings(enabled = false))
        val message = executeError(service, payload)

        assertTrue(message, message.contains("turned off"))
        verifyNoInteractions(client)
    }

    @Test
    fun settingsEditedMidSessionApplyToTheNextLookup() = runBlocking<Unit> {
        val service = newService(settings(enabled = false), mockClient())
        assertTrue(addPayloads(lookup(service, "食べる").entries[0]).isEmpty())

        AnkiSettingsStore.save(app, settings(enabled = true))

        assertEquals(1, addPayloads(lookup(service, "食べる").entries[0]).size)
    }

    @Test
    fun evictedTokenYieldsExpiredLinkErrorInsteadOfCrashing() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(), client)
        service.ankiCacheCapacity = 2
        val firstPayload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val secondPayload = addPayloads(lookup(service, "水入り").entries[0]).single()
        val thirdPayload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val message = executeError(service, firstPayload)

        assertTrue(message, message.contains("expired"))
        // The newest lookups are still usable.
        assertNotNull(service.executeAction(secondPayload))
        assertNotNull(service.executeAction(thirdPayload))
    }

    @Test
    fun linkFromAnEarlierServiceInstanceIsStaleInANewOne() = runBlocking<Unit> {
        val earlierService = newService(settings(), mockClient())
        val earlierPayload = addPayloads(lookup(earlierService, "水入り").entries[0]).single()

        // A new instance (as after a plugin process restart) makes its first lookup, which
        // would have reused the earlier instance's first token if tokens were only counted.
        val client = mockClient()
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        assertFalse(payload, payload.substringBefore('&') == earlierPayload.substringBefore('&'))

        val message = executeError(service, earlierPayload)

        assertTrue(message, message.contains("expired"))
        verify(client, never()).addNote(any(), any(), any(), any())
    }

    @Test
    fun everyEntryOfALargeLookupStaysTappable() = runBlocking<Unit> {
        // More entries than the cache holds lookups, so a per-entry cache would evict the top
        // entries' links before they were ever shown.
        withContext(Dispatchers.IO) {
            db.termDao().bulkAdd(
                (0 until 60).map { i ->
                    TermEntity(
                        dictionary = dictTitle, expression = "か$i", reading = "か$i",
                        definitionTags = "", rules = "", score = 100.0 - i, sequence = 100L + i,
                        termTags = "", glossary = "[\"g$i\"]"
                    )
                }
            )
        }
        val client = mockClient()
        val service = newService(settings(behavior = AnkiDuplicateBehavior.ALWAYS_ADD), client)
        val query = "?query=${URLEncoder.encode("か*", "UTF-8")}"
        val entries = service.onLookup(query, 0, query.length).entries
        assertEquals(60, entries.size)

        for (entry in listOf(entries.first(), entries.last())) {
            val result = service.executeAction(addPayloads(entry).single())
            assertTrue(result is CustomActionResult.UpdateResult)
        }
    }

    @Test
    fun shutdownClearsTheTokenCache() = runBlocking<Unit> {
        val service = newService(settings(), mockClient())
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.onShutdown()

        val message = executeError(service, payload)
        assertTrue(message, message.contains("expired"))
    }

    @Test
    fun overwriteSkipsDuplicatesOfOtherNoteTypesAndUpdatesTheSameTypeOne() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(
            client,
            listOf(
                duplicate(70L, "他", "other", modelId = 9L),
                duplicate(55L, "旧", "old", modelId = 2L)
            )
        )
        val fmt = format(
            fields = mapOf(
                "Front" to AnkiField(value = "{expression}", overwriteMode = AnkiFieldOverwriteMode.OVERWRITE),
                "Back" to AnkiField(value = "{reading}", overwriteMode = AnkiFieldOverwriteMode.OVERWRITE)
            )
        )
        val service = newService(
            settings(fmt, behavior = AnkiDuplicateBehavior.OVERWRITE).copy(duplicateScopeCheckAllModels = true),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client).updateNoteFields(eq(55L), eq(listOf("食べる", "たべる")))
        verify(client, never()).updateNoteFields(eq(70L), any())
        verify(client, never()).addNote(any(), any(), any(), any())
    }

    @Test
    fun overwriteWithOnlyOtherNoteTypeDuplicatesAddsNewNoteWithoutUpdating() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(70L, "他", "other", modelId = 9L)))
        val service = newService(
            settings(behavior = AnkiDuplicateBehavior.OVERWRITE).copy(duplicateScopeCheckAllModels = true),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload)

        verify(client).addNote(eq(2L), eq(1L), eq(listOf("食べる (たべる)", "たべる")), any())
        verify(client, never()).updateNoteFields(any(), any())
        assertEquals(listOf("● Added: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun preventBehaviorStillBlocksWhenOnlyOtherNoteTypeDuplicatesExist() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(70L, "他", "other", modelId = 9L)))
        val service = newService(settings(behavior = AnkiDuplicateBehavior.PREVENT), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload)

        verify(client, never()).addNote(any(), any(), any(), any())
        verify(client, never()).updateNoteFields(any(), any())
    }

    @Test
    fun noteDataIsBuiltAtTapTimeNotAtLookupTime() = runBlocking<Unit> {
        val client = mockClient()
        val fmt = format(
            fields = mapOf("Front" to AnkiField(value = "{expression}"), "Back" to AnkiField(value = "{frequencies}"))
        )
        val service = newService(settings(fmt), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        // Frequency data that only exists after the lookup has rendered its links: it can be
        // in the note only if the meta queries run when the link is tapped.
        withContext(Dispatchers.IO) {
            db.termMetaDao().bulkAdd(
                listOf(TermMetaEntity(dictionary = dictTitle, expression = "食べる", mode = "freq", data = "42"))
            )
        }

        service.executeAction(payload)

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("食べる", fieldsCaptor.firstValue[0])
        assertTrue(fieldsCaptor.firstValue[1], fieldsCaptor.firstValue[1].contains("$dictTitle: 42"))
    }

    @Test
    fun noteDataIsBuiltOnceAndReusedAcrossTaps() = runBlocking<Unit> {
        val client = mockClient()
        val fmt = format(
            fields = mapOf("Front" to AnkiField(value = "{expression}"), "Back" to AnkiField(value = "{frequencies}"))
        )
        val service = newService(settings(fmt, behavior = AnkiDuplicateBehavior.ALWAYS_ADD), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val first = service.ankiNoteDataForLink(payload)
        withContext(Dispatchers.IO) {
            db.termMetaDao().bulkAdd(
                listOf(TermMetaEntity(dictionary = dictTitle, expression = "食べる", mode = "freq", data = "42"))
            )
        }
        val second = service.ankiNoteDataForLink(payload)

        assertNotNull(first)
        assertTrue(first === second)
    }

    @Test
    fun onlyTheTappedLinkIsMarkedAddedAndTheOthersStayInPlace() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(
            settings(format(name = "Vocab"), format(name = "Sentences"), format(name = "Cloze")),
            client
        )
        val entry = lookup(service, "食べる").entries[0]
        val payloads = addPayloads(entry)

        val updated = updatedEntry(service.executeAction(payloads[1]))

        assertEquals(listOf("● Add: Vocab", "● Added: Sentences", "● Add: Cloze"), linkLabels(updated))
        // Every link still leads where it did, and the text before the links is unchanged.
        assertEquals(payloads, addPayloads(updated))
        val linksStart = entry.body.styledSpans!!.filter { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            .minOf { it.startIndex }
        assertEquals(entry.body.text.substring(0, linksStart), updated.body.text.substring(0, linksStart))
        val length = updated.body.text.length
        assertTrue(updated.body.styledSpans!!.all { it.endIndex <= length })
        assertTrue(updated.body.blockSpans.orEmpty().all { it.endIndex <= length })
    }

    @Test
    fun secondTapOnAnAddedLinkIsPreventedWhenDuplicatesAreNotAllowed() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(behavior = AnkiDuplicateBehavior.PREVENT), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        service.executeAction(payload)
        stubDuplicates(client, listOf(duplicate(999L, "食べる (たべる)", "たべる")))

        val result = service.executeAction(payload)

        assertTrue((result as CustomActionResult.SuccessMessage).message.contains("already exists"))
        verify(client).addNote(any(), any(), any(), any())
    }

    @Test
    fun secondTapOnAnAddedLinkOverwritesTheNoteWhenOverwritingIsAllowed() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(behavior = AnkiDuplicateBehavior.OVERWRITE), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        assertEquals(listOf("● Added: Vocab"), linkLabels(updatedEntry(service.executeAction(payload))))
        stubDuplicates(client, listOf(duplicate(999L, "食べる (たべる)", "たべる")))

        val result = service.executeAction(payload)

        verify(client).updateNoteFields(eq(999L), any())
        verify(client).addNote(any(), any(), any(), any())
        assertEquals(listOf("● Overwritten: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun secondTapOnAnAddedLinkAddsAnotherNoteWhenAddingIsAllowed() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(behavior = AnkiDuplicateBehavior.ALWAYS_ADD), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        service.executeAction(payload)
        stubDuplicates(client, listOf(duplicate(999L, "食べる (たべる)", "たべる")))

        val result = service.executeAction(payload)

        verify(client, times(2)).addNote(any(), any(), any(), any())
        assertEquals(listOf("● Added: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun addWithUnknownMarkersIsReportedAndRelabelsItsLinkAtOnce() = runBlocking<Unit> {
        val client = mockClient()
        val badFormat = format(
            name = "Bad",
            fields = mapOf("Front" to AnkiField(value = "{expression}"), "Back" to AnkiField("{expresion}"))
        )
        val service = newService(settings(badFormat, format(name = "Vocab")), client)
        val payloads = addPayloads(lookup(service, "食べる").entries[0])

        val result = service.executeAction(payloads[0])

        assertTrue(warning(result).contains("{expresion}"))
        assertEquals(listOf("● Added: Bad", "● Add: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun linksAreGreenUntilATapChecksEveryLinkOfThatEntry() = runBlocking<Unit> {
        val client = mockClient()
        // Vocab (note type 2) has no duplicate yet; Sentences (5) has one of its own note type;
        // Cloze (7) only has one of another note type, which can never be overwritten.
        stubDuplicatesFor(client, 5L, listOf(duplicate(50L, "食べる", "x", modelId = 5L)))
        stubDuplicatesFor(client, 7L, listOf(duplicate(70L, "食べる", "x", modelId = 9L)))
        val service = newService(
            settings(
                format(name = "Vocab", modelId = 2L),
                format(name = "Sentences", modelId = 5L),
                format(name = "Cloze", modelId = 7L),
                behavior = AnkiDuplicateBehavior.OVERWRITE
            ).copy(duplicateScopeCheckAllModels = true),
            client
        )
        val entry = lookup(service, "食べる").entries[0]
        assertEquals(listOf(GREEN, GREEN, GREEN), linkColors(entry))
        verifyDuplicateSearches(client, 0)

        val updated = updatedEntry(service.executeAction(addPayloads(entry)[0]))

        assertEquals(listOf("● Added: Vocab", "● Add: Sentences", "● Add: Cloze"), linkLabels(updated))
        // The added note would now be overwritten, like Sentences' note; Cloze would get a new one.
        assertEquals(listOf(ORANGE, ORANGE, PURPLE), linkColors(updated))
        // One search per format of the tapped entry: the tapped format's before it was written,
        // and one for each of the others after.
        verifyDuplicateSearches(client, 3)
        verify(client).findDuplicateNotes(
            modelId = 7L,
            deckId = 1L,
            key = "食べる (たべる)",
            scope = AnkiDuplicateScope.COLLECTION,
            checkAllModels = true
        )
    }

    @Test
    fun addedLinkColorFollowsTheDuplicateBehavior() = runBlocking<Unit> {
        val expected = mapOf(
            AnkiDuplicateBehavior.PREVENT to GRAY,
            AnkiDuplicateBehavior.OVERWRITE to ORANGE,
            AnkiDuplicateBehavior.ALWAYS_ADD to PURPLE
        )
        for ((behavior, color) in expected) {
            val client = mockClient()
            stubDuplicatesFor(client, 5L, listOf(duplicate(50L, "食べる", "x", modelId = 5L)))
            val service = newService(
                settings(
                    format(name = "Vocab", modelId = 2L),
                    format(name = "Other", modelId = 5L),
                    behavior = behavior
                ),
                client
            )

            val result = service.executeAction(addPayloads(lookup(service, "食べる").entries[0])[0])

            // Both formats now have a duplicate, so both show what their next tap would do.
            assertEquals(behavior.name, listOf(color, color), linkColors(updatedEntry(result)))
        }
    }

    @Test
    fun onlyTheTappedEntryIsChecked() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            db.termDao().bulkAdd(
                (0 until 2).map { i ->
                    TermEntity(
                        dictionary = dictTitle, expression = "か$i", reading = "か$i",
                        definitionTags = "", rules = "", score = 100.0 - i, sequence = 100L + i,
                        termTags = "", glossary = "[\"g$i\"]"
                    )
                }
            )
        }
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(50L, "x", "x")))
        val service = newService(
            settings(format(name = "Vocab"), format(name = "Other"), behavior = AnkiDuplicateBehavior.ALWAYS_ADD),
            client
        )
        val query = "?query=${URLEncoder.encode("か*", "UTF-8")}"
        val entries = service.onLookup(query, 0, query.length).entries

        val result = service.executeAction(addPayloads(entries[0])[0])

        val updated = (result as CustomActionResult.UpdateResult).result.entries
        assertEquals(listOf(PURPLE, PURPLE), linkColors(updated[0]))
        assertEquals(listOf(GREEN, GREEN), linkColors(updated[1]))
        verifyDuplicateSearches(client, 2)
    }

    @Test
    fun withoutDuplicateCheckingNothingIsQueriedAndTheAddedLinkWouldAddAgain() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(
            settings(format(name = "Vocab"), format(name = "Other"), checkForDuplicates = false),
            client
        )

        val result = service.executeAction(addPayloads(lookup(service, "食べる").entries[0])[0])

        assertEquals(listOf(PURPLE, GREEN), linkColors(updatedEntry(result)))
        verifyDuplicateSearches(client, 0)
    }

    @Test
    fun preventedTapGraysItsLinkOnTheNextRefresh() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicatesFor(client, 2L, listOf(duplicate(20L, "食べる (たべる)", "x", modelId = 2L)))
        val service = newService(
            settings(format(name = "Vocab", modelId = 2L), format(name = "Other", modelId = 5L)),
            client
        )
        val payloads = addPayloads(lookup(service, "食べる").entries[0])

        val prevented = service.executeAction(payloads[0])
        val refreshed = updatedEntry(service.executeAction(payloads[1]))

        assertTrue((prevented as CustomActionResult.SuccessMessage).message.contains("already exists"))
        assertEquals(listOf("● Add: Vocab", "● Added: Other"), linkLabels(refreshed))
        assertEquals(listOf(GRAY, GRAY), linkColors(refreshed))
    }

    @Test
    fun aFailedCheckOfAnotherLinkLeavesItsColorAndStillRefreshes() = runBlocking<Unit> {
        val client = mockClient()
        whenever(
            client.findDuplicateNotes(eq(5L), any(), any(), any<AnkiDuplicateScope>(), any())
        ).thenThrow(SecurityException("permission revoked"))
        val service = newService(
            settings(format(name = "Vocab", modelId = 2L), format(name = "Other", modelId = 5L)),
            client
        )

        val result = service.executeAction(addPayloads(lookup(service, "食べる").entries[0])[0])

        assertEquals(listOf(GRAY, GREEN), linkColors(updatedEntry(result)))
    }

    private fun kanjiFormat() = AnkiCardFormat(
        name = "Kanji",
        type = AnkiEntryType.KANJI,
        deckId = 1L,
        deckName = "Default",
        modelId = 3L,
        modelName = "Basic",
        fields = mapOf("Front" to AnkiField(value = "{character}"), "Back" to AnkiField(value = "{sentence}"))
    )

    private suspend fun addKanji(vararg characters: String) = withContext(Dispatchers.IO) {
        db.kanjiDao().bulkAdd(
            characters.map {
                KanjiEntity(
                    dictionary = dictTitle, character = it, onyomi = "", kunyomi = "", tags = "",
                    meanings = "[\"meaning\"]", stats = null
                )
            }
        )
    }

    /** Scans [contextText] with the cursor on the first character of [word], as a tap does. */
    private suspend fun scan(
        service: YomitanDictionaryPluginService,
        contextText: String,
        word: String
    ): DictionaryResult {
        val start = contextText.indexOf(word)
        return service.onLookup(contextText, start, start + 1)
    }

    private fun kanjiLink(entry: DictionaryEntry, character: String): String =
        entry.headwordSpans!!.mapNotNull { it.linkUrl }.single { it.startsWith("lookup:?kanji=$character") }

    private suspend fun followLink(service: YomitanDictionaryPluginService, link: String): DictionaryResult {
        val target = link.removePrefix("lookup:")
        return service.onLookup(target, 0, target.length)
    }

    private val furiganaFormat = format(
        fields = mapOf(
            "Front" to AnkiField(value = "{sentence-furigana-plain}"),
            "Back" to AnkiField(value = "{sentence-furigana}")
        )
    )

    /** Dokuen's readings of 冷たい水入りの瓶。: 冷たい / 水入り / の / 瓶 / 。 */
    private fun sentenceReadings(): List<WordReading> = listOf(
        wordReading(0, 3, 0 to 1 to "つめ"),
        wordReading(3, 6, 3 to 4 to "みず", 4 to 5 to "い"),
        wordReading(6, 7),
        wordReading(7, 8, 7 to 8 to "びん"),
        wordReading(8, 9)
    )

    private fun wordReading(start: Int, end: Int, vararg ruby: Pair<Pair<Int, Int>, String>) = WordReading().apply {
        startIndex = start
        endIndex = end
        this.ruby = ruby.map { (range, text) ->
            RubySpan().apply { startIndex = range.first; endIndex = range.second; rubyText = text }
        }.toTypedArray()
    }

    private suspend fun scannedPayload(
        service: YomitanDictionaryPluginService,
        contextText: String = "冷たい水入りの瓶。次の文。"
    ) =
        addPayloads(scan(service, contextText, "水入り").entries.single { it.headword == "水入り" }).single()

    @Test
    fun sentenceFuriganaIsRequestedBeforeTheDuplicateSearchAndRendered() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(furiganaFormat), client)
        val context = FakeActionContext(readings = { sentenceReadings() })

        service.executeAction(scannedPayload(service), context)

        assertEquals(listOf("冷たい水入りの瓶。"), context.readingsRequests)
        val plain = "冷[つめ]たい 水[みず] 入[い]りの 瓶[びん]。"
        verify(client).findDuplicateNotes(any(), any(), eq(plain), any<AnkiDuplicateScope>(), any())
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(
            listOf(
                plain,
                "<span class=\"term\"><ruby>冷<rt>つめ</rt></ruby>たい</span>" +
                        "<span class=\"term\"><ruby>水<rt>みず</rt></ruby><ruby>入<rt>い</rt></ruby>り</span>" +
                        "<span class=\"term\">の</span><span class=\"term\"><ruby>瓶<rt>びん</rt></ruby></span>" +
                        "<span class=\"term\">。</span>"
            ),
            fieldsCaptor.firstValue
        )
    }

    @Test
    fun noReadingsFallBackToThePlainSentence() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(furiganaFormat), client)
        val context = FakeActionContext()

        service.executeAction(scannedPayload(service), context)

        assertEquals(1, context.readingsRequests.size)
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(listOf("冷たい水入りの瓶。", "冷たい水入りの瓶。"), fieldsCaptor.firstValue)
    }

    @Test
    fun noReadingsRequestWithoutTheMarkersInTheNoteTypesFields() = runBlocking<Unit> {
        val withOldField = format(
            fields = mapOf(
                "Front" to AnkiField(value = "{expression}"),
                "Back" to AnkiField(value = "{reading}"),
                "Gone" to AnkiField(value = "{sentence-furigana}")
            )
        )
        val service = newService(settings(withOldField), mockClient())
        val context = FakeActionContext(readings = { sentenceReadings() })

        service.executeAction(scannedPayload(service), context)

        assertTrue(context.readingsRequests.isEmpty())
    }

    @Test
    fun noReadingsRequestWithoutASentence() = runBlocking<Unit> {
        val service = newService(settings(furiganaFormat), mockClient())
        val context = FakeActionContext(readings = { sentenceReadings() })

        // A lookup through a link has no sentence.
        service.executeAction(addPayloads(lookup(service, "食べる").entries[0]).single(), context)

        assertTrue(context.readingsRequests.isEmpty())
    }

    @Test
    fun noReadingsRequestOutsideJapaneseSessions() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(furiganaFormat), client, language = "zh")
        val context = FakeActionContext(readings = { sentenceReadings() })

        service.executeAction(scannedPayload(service), context)

        assertTrue(context.readingsRequests.isEmpty())
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals(listOf("冷たい水入りの瓶。", "冷たい水入りの瓶。"), fieldsCaptor.firstValue)
    }

    @Test
    fun noReadingsRequestForASentenceOverTheLimit() = runBlocking<Unit> {
        // 𠀋 is two UTF-16 code units, so the sentence the scan extent allows exceeds 500 of them.
        val longContext = "𠀋".repeat(300) + "水入り" + "𠀋".repeat(300) + "。"
        val service = newService(settings(furiganaFormat), mockClient())
        val context = FakeActionContext(readings = { sentenceReadings() })

        service.executeAction(scannedPayload(service, longContext), context)

        assertTrue(context.readingsRequests.isEmpty())
    }

    @Test
    fun readingsAreAskedForOnceAndReusedByLaterTaps() = runBlocking<Unit> {
        val service = newService(settings(furiganaFormat, behavior = AnkiDuplicateBehavior.ALWAYS_ADD), mockClient())
        val context = FakeActionContext(readings = { sentenceReadings() })
        val payload = scannedPayload(service)

        service.executeAction(payload, context)
        service.executeAction(payload, context)

        assertEquals(1, context.readingsRequests.size)
    }

    @Test
    fun noReadingsAreAskedForAgainOnTheNextTap() = runBlocking<Unit> {
        val service = newService(settings(furiganaFormat, behavior = AnkiDuplicateBehavior.ALWAYS_ADD), mockClient())
        val context = FakeActionContext()
        val payload = scannedPayload(service)

        service.executeAction(payload, context)
        service.executeAction(payload, context)

        assertEquals(2, context.readingsRequests.size)
    }

    @Test
    fun readingsAreRequestedBeforeImagesAndClipboardText() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val format = imageFormat(picture = "{screenshot}{clipboard-text}{sentence-furigana}")
        val service = newService(settings(format), client)
        val context = FakeActionContext(readings = { sentenceReadings() }, clipboardText = "copied")

        service.executeAction(scannedPayload(service), context)

        assertEquals(listOf("readings", "image:SCREENSHOT", "text"), context.log)
    }

    @Test
    fun scannedTermNoteUsesTheSentenceAroundTheMatch() = runBlocking<Unit> {
        val service = newService(settings(format()), mockClient())
        val entry = scan(service, "冷たい水入りの瓶。次の文。", "水入り").entries.single { it.headword == "水入り" }

        val cloze = service.ankiNoteDataForLink(addPayloads(entry).single())!!.cloze!!

        assertEquals("冷たい水入りの瓶。", cloze.sentence)
        assertEquals("冷たい", cloze.prefix)
        assertEquals("水入り", cloze.body)
        assertEquals("の瓶。", cloze.suffix)
    }

    @Test
    fun tappedKanjiNoteUsesTheSentenceAroundTheKanji() = runBlocking<Unit> {
        addKanji("瓶")
        val service = newService(settings(kanjiFormat()), mockClient())
        val entry = scan(service, "冷たい水入りの瓶。次の文。", "瓶").entries.single()

        val cloze = service.ankiNoteDataForLink(addPayloads(entry).single())!!.cloze!!

        assertEquals("冷たい水入りの瓶。", cloze.sentence)
        assertEquals("冷たい水入りの", cloze.prefix)
        assertEquals("瓶", cloze.body)
        assertEquals("。", cloze.suffix)
    }

    @Test
    fun kanjiNoteFromAHeadwordLinkUsesTheTermsSentence() = runBlocking<Unit> {
        addKanji("入")
        val service = newService(settings(format(), kanjiFormat()), mockClient())
        val termEntry = scan(service, "冷たい水入りの瓶。次の文。", "水入り").entries.single { it.headword == "水入り" }
        val link = kanjiLink(termEntry, "入")
        assertTrue(link, link.contains("&origin_ref="))

        val kanjiEntry = followLink(service, link).entries.single()
        val cloze = service.ankiNoteDataForLink(addPayloads(kanjiEntry).single())!!.cloze!!

        assertEquals("冷たい水入りの瓶。", cloze.sentence)
        // As in the original's getCloze, the split is at the term's position, with a body as
        // long as the kanji, rather than around the kanji itself.
        assertEquals("冷たい", cloze.prefix)
        assertEquals("水", cloze.body)
        assertEquals("入りの瓶。", cloze.suffix)
    }

    @Test
    fun kanjiHeadwordLinksAreUnchangedWithoutAKanjiFormat() = runBlocking<Unit> {
        val service = newService(settings(format()), mockClient())
        val termEntry = scan(service, "冷たい水入りの瓶。", "水入り").entries.single { it.headword == "水入り" }

        assertEquals("lookup:?kanji=入", kanjiLink(termEntry, "入"))
    }

    @Test
    fun kanjiNoteHasNoSentenceWhenTheLinkedLookupIsGoneOrTheRefIsMalformed() = runBlocking<Unit> {
        addKanji("入")
        val service = newService(settings(format(), kanjiFormat()), mockClient())
        service.ankiCacheCapacity = 1
        val termEntry = scan(service, "冷たい水入りの瓶。", "水入り").entries.single { it.headword == "水入り" }
        val link = kanjiLink(termEntry, "入")
        // Evicts the lookup the kanji link refers to. This lookup stays cached, but it was not
        // scanned, so what its kanji links refer to has no sentence.
        val cachedToken = addPayloads(lookup(service, "食べる").entries[0]).single()
            .substringAfter("token=").substringBefore('&')
        service.ankiCacheCapacity = 10

        for (target in listOf(
            link,
            "lookup:?kanji=入&origin_ref=nonsense",
            "lookup:?kanji=入&origin_ref=$cachedToken.0"
        )) {
            val kanjiEntry = followLink(service, target).entries.single()
            val noteData = service.ankiNoteDataForLink(addPayloads(kanjiEntry).single())!!
            assertEquals(target, null, noteData.cloze)
        }
    }

    @Test
    fun eachKanjiEntryExportsItsOwnDictionary() = runBlocking<Unit> {
        val second = "Kanji Two"
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(
                DictionaryEntity(
                    title = second, revision = "1.0", sequenced = false, version = 3,
                    importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
                    styles = "", counts = null, importSuccess = true
                )
            )
            db.kanjiDao().bulkAdd(
                listOf(
                    KanjiEntity(
                        dictionary = second, character = "瓶", onyomi = "", kunyomi = "", tags = "",
                        meanings = "[\"jar\"]", stats = null
                    ),
                    KanjiEntity(
                        dictionary = dictTitle, character = "瓶", onyomi = "", kunyomi = "", tags = "",
                        meanings = "[\"bottle\"]", stats = null
                    )
                )
            )
        }
        app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE).edit()
            .putString("active_dictionaries", "$dictTitle,$second")
            .commit()
        val service = newService(settings(kanjiFormat()), mockClient())

        val entries = scan(service, "冷たい水入りの瓶。", "瓶").entries
        val notes = entries.map { service.ankiNoteDataForLink(addPayloads(it).single())!! }

        // One entry per kanji dictionary, in priority order, each with its own data.
        assertEquals(listOf(dictTitle, second), notes.map { it.dictionary })
        assertEquals(listOf(listOf("bottle"), listOf("jar")), notes.map { it.glossary })
        assertEquals(listOf("冷たい水入りの瓶。", "冷たい水入りの瓶。"), notes.map { it.cloze!!.sentence })
    }

    @Test
    fun searchQueryIsTheLongestScannedMatch() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = dictTitle, expression = "水", reading = "みず", definitionTags = "",
                        rules = "", score = 100.0, sequence = 3, termTags = "", glossary = "[\"water\"]"
                    )
                )
            )
        }
        val service = newService(settings(format()), mockClient())
        val entries = scan(service, "冷たい水入りの瓶。", "水入り").entries

        // As in the original, every entry of a lookup has the same query: the scanned text cut
        // to the longest match of any result.
        assertEquals(listOf("水入り", "水"), entries.map { it.headword })
        for (entry in entries) {
            assertEquals("水入り", service.ankiNoteDataForLink(addPayloads(entry).single())!!.searchQuery)
        }
    }

    @Test
    fun searchQueryOfAnInternalLinkIsItsQuery() = runBlocking<Unit> {
        val service = newService(settings(format()), mockClient())
        val entry = lookup(service, "食べる").entries[0]

        assertEquals("食べる", service.ankiNoteDataForLink(addPayloads(entry).single())!!.searchQuery)
    }

    @Test
    fun searchQueryOfAScannedKanjiIsTheCharacter() = runBlocking<Unit> {
        addKanji("瓶")
        val service = newService(settings(format(), kanjiFormat()), mockClient())
        val tapped = scan(service, "冷たい水入りの瓶。", "瓶").entries.single()

        assertEquals("瓶", service.ankiNoteDataForLink(addPayloads(tapped).single())!!.searchQuery)
    }

    @Test
    fun kanjiFromAHeadwordLinkKeepsTheTermLookupsQuery() = runBlocking<Unit> {
        // As the original's kanji links carry the popup's full query into the kanji lookup.
        addKanji("入", "食")
        val service = newService(settings(format(), kanjiFormat()), mockClient())
        val scanned = scan(service, "冷たい水入りの瓶。", "水入り").entries.single { it.headword == "水入り" }
        val searched = lookup(service, "食べる").entries[0]

        val fromScan = followLink(service, kanjiLink(scanned, "入")).entries.single()
        val fromSearch = followLink(service, kanjiLink(searched, "食")).entries.single()

        assertEquals("水入り", service.ankiNoteDataForLink(addPayloads(fromScan).single())!!.searchQuery)
        assertEquals("食べる", service.ankiNoteDataForLink(addPayloads(fromSearch).single())!!.searchQuery)
        // An internal link lookup has no sentence to carry over.
        assertEquals(null, service.ankiNoteDataForLink(addPayloads(fromSearch).single())!!.cloze)
    }

    @Test
    fun kanjiFromAnExpiredHeadwordLinkUsesTheCharacter() = runBlocking<Unit> {
        addKanji("入")
        val service = newService(settings(format(), kanjiFormat()), mockClient())
        val linked = followLink(service, "lookup:?kanji=入&origin_ref=nonsense").entries.single()

        assertEquals("入", service.ankiNoteDataForLink(addPayloads(linked).single())!!.searchQuery)
    }

    @Test
    fun glossaryIncludesTheDictionaryStyles() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val dao = db.dictionaryDao()
            dao.updateDictionary(dao.getDictionaryByTitle(dictTitle)!!.copy(styles = "a { color: red; }"))
        }
        val client = mockClient()
        val fmt = format(
            fields = mapOf("Front" to AnkiField(value = "{expression}"), "Back" to AnkiField(value = "{glossary}"))
        )
        val service = newService(settings(fmt), client)

        service.executeAction(addPayloads(lookup(service, "食べる").entries[0]).single())

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertTrue(
            fieldsCaptor.firstValue[1],
            fieldsCaptor.firstValue[1].endsWith(
                "</li><style>.yomitan-glossary [data-dictionary=\"$dictTitle\"] a { color: red; }</style></ol></div>"
            )
        )
    }

    private fun imageFormat(
        picture: String = "{screenshot}",
        mode: AnkiFieldOverwriteMode = AnkiFieldOverwriteMode.COALESCE
    ) = format(
        fields = mapOf(
            "Front" to AnkiField(value = "{expression}"),
            "Back" to AnkiField(value = picture, overwriteMode = mode)
        )
    )

    private fun image() = ImageRequestResult.Image(Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888))

    /** Stubs addImage to run its writer and store under [storedAs]; returns what each call wrote. */
    private fun stubAddImage(client: AnkiDroidClient, storedAs: (String) -> String? = { it }): List<ByteArray> {
        val written = mutableListOf<ByteArray>()
        whenever(client.addImage(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val write = it.arguments[1] as (OutputStream) -> Boolean
            val out = ByteArrayOutputStream()
            if (!write(out)) return@thenAnswer null
            written += out.toByteArray()
            storedAs(it.arguments[0] as String)
        }
        return written
    }

    @Test
    fun screenshotIsRequestedAndWrittenIntoTheNote() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val service = newService(settings(imageFormat()), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image()))

        service.executeAction(payload, context)

        assertEquals(listOf(ImageRequestSource.SCREENSHOT to false), context.requests)
        val nameCaptor = argumentCaptor<String>()
        verify(client).addImage(nameCaptor.capture(), any())
        assertTrue(nameCaptor.firstValue.matches(Regex("dokuen_screenshot_\\d{4}(-\\d{2}){5}-\\d{3}\\.png")))
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("<img src=\"${nameCaptor.firstValue}\" />", fieldsCaptor.firstValue[1])
    }

    @Test
    fun cropSettingMakesTheRequestInteractiveAndJpegIsUsed() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val service = newService(
            settings(imageFormat()).copy(
                screenshotBehavior = AnkiScreenshotBehavior.CROP,
                screenshotFormat = AnkiScreenshotFormat.JPEG
            ),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image()))

        service.executeAction(payload, context)

        assertEquals(listOf(ImageRequestSource.SCREENSHOT to true), context.requests)
        val nameCaptor = argumentCaptor<String>()
        verify(client).addImage(nameCaptor.capture(), any())
        assertTrue(nameCaptor.firstValue.endsWith(".jpeg"))
    }

    @Test
    fun clipboardImageIsPngWhateverTheScreenshotFormat() = runBlocking<Unit> {
        val client = mockClient()
        val written = stubAddImage(client)
        val service = newService(
            settings(imageFormat(picture = "{screenshot}{clipboard-image}"))
                .copy(screenshotFormat = AnkiScreenshotFormat.JPEG),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(
            mapOf(ImageRequestSource.SCREENSHOT to image(), ImageRequestSource.CLIPBOARD to image())
        )

        service.executeAction(payload, context)

        val nameCaptor = argumentCaptor<String>()
        verify(client, times(2)).addImage(nameCaptor.capture(), any())
        assertTrue(nameCaptor.firstValue.endsWith(".jpeg"))
        assertTrue(nameCaptor.secondValue, nameCaptor.secondValue.matches(Regex("dokuen_clipboard_image_.*\\.png")))
        // The PNG signature's first bytes.
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), written[1].take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun outOfRangeJpegQualityStillStoresTheImage() = runBlocking<Unit> {
        val client = mockClient()
        val written = stubAddImage(client)
        val service = newService(
            settings(imageFormat()).copy(screenshotFormat = AnkiScreenshotFormat.JPEG, screenshotQuality = 150),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload, FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image())))

        assertEquals(1, written.size)
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertTrue(fieldsCaptor.firstValue[1], fieldsCaptor.firstValue[1].startsWith("<img src=\"dokuen_screenshot_"))
    }

    @Test
    fun anImageThatCannotBeStoredStillWritesTheNoteAndSaysSo() = runBlocking<Unit> {
        val client = mockClient()
        whenever(client.addImage(any(), any())).thenThrow(IllegalStateException("cannot store"))
        val service = newService(settings(imageFormat()), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload, FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image())))

        verify(client).addNote(any(), any(), any(), any())
        val message = warning(result)
        assertTrue(message, message.contains("{screenshot}"))
    }

    @Test
    fun noRequestWithoutAnImageMarker() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext()

        service.executeAction(payload, context)

        assertTrue(context.requests.isEmpty())
        verify(client).addNote(any(), any(), any(), any())
    }

    @Test
    fun screenshotThenClipboardAreBothRequested() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val service = newService(settings(imageFormat(picture = "{screenshot}{clipboard-image}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(
            mapOf(ImageRequestSource.SCREENSHOT to image(), ImageRequestSource.CLIPBOARD to image())
        )

        service.executeAction(payload, context)

        assertEquals(
            listOf(ImageRequestSource.SCREENSHOT to false, ImageRequestSource.CLIPBOARD to false),
            context.requests
        )
        val nameCaptor = argumentCaptor<String>()
        verify(client, times(2)).addImage(nameCaptor.capture(), any())
        assertTrue(nameCaptor.secondValue.startsWith("dokuen_clipboard_image_"))
    }

    @Test
    fun noImageLeavesTheMarkerEmpty() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat()), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload, FakeActionContext())

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("", fieldsCaptor.firstValue[1])
        verify(client, never()).addImage(any(), any())
    }

    @Test
    fun cancelWritesNothingAndSkipsTheClipboard() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat(picture = "{screenshot}{clipboard-image}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to ImageRequestResult.Cancelled))

        service.executeAction(payload, context)

        assertEquals(listOf(ImageRequestSource.SCREENSHOT to false), context.requests)
        verify(client, never()).addNote(any(), any(), any(), any())
        verify(client, never()).updateNoteFields(any(), any())
    }

    @Test
    fun preventedDuplicateRequestsNothing() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる", "")))
        val service = newService(settings(imageFormat(), behavior = AnkiDuplicateBehavior.PREVENT), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image()))

        service.executeAction(payload, context)

        assertTrue(context.requests.isEmpty())
    }

    @Test
    fun overwritingASkippedImageFieldRequestsNothing() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる", "")))
        val service = newService(
            settings(imageFormat(mode = AnkiFieldOverwriteMode.SKIP), behavior = AnkiDuplicateBehavior.OVERWRITE),
            client
        )
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image()))

        service.executeAction(payload, context)

        assertTrue(context.requests.isEmpty())
        verify(client).updateNoteFields(eq(55L), any())
    }

    @Test
    fun overwriteMergesTheImageIntoTheExistingNote() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) { "stored.png" } }
        stubDuplicates(client, listOf(duplicate(55L, "食べる", "")))
        val service = newService(settings(imageFormat(), behavior = AnkiDuplicateBehavior.OVERWRITE), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload, FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image())))

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).updateNoteFields(eq(55L), fieldsCaptor.capture())
        assertEquals("<img src=\"stored.png\" />", fieldsCaptor.firstValue[1])
    }

    @Test
    fun screenshotInTheFirstFieldDoesNotChangeTheDuplicateKey() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val firstFieldImage = format(
            fields = mapOf("Front" to AnkiField("{expression}{screenshot}"), "Back" to AnkiField("{reading}"))
        )
        val service = newService(settings(firstFieldImage), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload, FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image())))

        verify(client).findDuplicateNotes(any(), any(), eq("食べる"), any<AnkiDuplicateScope>(), any())
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertTrue(fieldsCaptor.firstValue[0].startsWith("食べる<img src=\"dokuen_screenshot_"))
    }

    @Test
    fun failedMediaInsertStillWritesTheNoteAndSaysSo() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) { null } }
        val service = newService(settings(imageFormat()), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(payload, FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to image())))

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("", fieldsCaptor.firstValue[1])
        val message = warning(result)
        assertTrue(message, message.contains("{screenshot}"))
    }

    @Test
    fun deniedWritesTheNoteWithoutImagesAndSaysWhy() = runBlocking<Unit> {
        val client = mockClient().also { stubAddImage(it) }
        val service = newService(settings(imageFormat(picture = "{screenshot}{clipboard-image}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(
            mapOf(ImageRequestSource.SCREENSHOT to ImageRequestResult.Denied, ImageRequestSource.CLIPBOARD to image())
        )

        val result = service.executeAction(payload, context)

        assertEquals(listOf(ImageRequestSource.SCREENSHOT to false), context.requests)
        verify(client, never()).addImage(any(), any())
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("", fieldsCaptor.firstValue[1])
        assertEquals(
            "Note added successfully, but with empty {screenshot} and {clipboard-image} fields " +
                    "(images require Dokuen Pro).",
            warning(result)
        )
        assertEquals(listOf("● Added: Vocab"), linkLabels(updatedEntry(result)))
    }

    @Test
    fun deniedOnOverwriteSaysTheNoteWasUpdated() = runBlocking<Unit> {
        val client = mockClient()
        stubDuplicates(client, listOf(duplicate(55L, "食べる", "")))
        val service = newService(settings(imageFormat(), behavior = AnkiDuplicateBehavior.OVERWRITE), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        val result = service.executeAction(
            payload,
            FakeActionContext(mapOf(ImageRequestSource.SCREENSHOT to ImageRequestResult.Denied))
        )

        verify(client).updateNoteFields(eq(55L), any())
        assertEquals(
            "Note updated successfully, but with empty {screenshot} field (images require Dokuen Pro).",
            warning(result)
        )
    }

    @Test
    fun clipboardTextIsRequestedAndRendered() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat(picture = "{clipboard-text}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(clipboardText = "line 1\nline <2>")

        service.executeAction(payload, context)

        assertEquals(1, context.textRequests)
        assertTrue(context.requests.isEmpty())
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("line 1<br>\nline &lt;2&gt;", fieldsCaptor.firstValue[1])
    }

    @Test
    fun clipboardTextLineBreaksAreNormalizedAsTheOriginalsPasteTargetDoes() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat(picture = "{clipboard-text}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()

        service.executeAction(payload, FakeActionContext(clipboardText = "a\r\nb\rc\nd"))

        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("a<br>\nb<br>\nc<br>\nd", fieldsCaptor.firstValue[1])
    }

    @Test
    fun clipboardTextIsRequestedAfterImagesEvenWhenTheyAreDenied() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat(picture = "{screenshot}|{clipboard-text}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(
            mapOf(ImageRequestSource.SCREENSHOT to ImageRequestResult.Denied),
            clipboardText = "copied"
        )

        val result = service.executeAction(payload, context)

        assertEquals(listOf(ImageRequestSource.SCREENSHOT to false), context.requests)
        assertEquals(1, context.textRequests)
        val fieldsCaptor = argumentCaptor<List<String>>()
        verify(client).addNote(any(), any(), fieldsCaptor.capture(), any())
        assertEquals("|copied", fieldsCaptor.firstValue[1])
        assertEquals(
            "Note added successfully, but with empty {screenshot} field (images require Dokuen Pro).",
            warning(result)
        )
    }

    @Test
    fun cancelSkipsTheClipboardText() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat(picture = "{screenshot}{clipboard-text}")), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(
            mapOf(ImageRequestSource.SCREENSHOT to ImageRequestResult.Cancelled),
            clipboardText = "copied"
        )

        service.executeAction(payload, context)

        assertEquals(0, context.textRequests)
        verify(client, never()).addNote(any(), any(), any(), any())
    }

    @Test
    fun noClipboardTextWithoutTheMarker() = runBlocking<Unit> {
        val client = mockClient()
        val service = newService(settings(imageFormat()), client)
        val payload = addPayloads(lookup(service, "食べる").entries[0]).single()
        val context = FakeActionContext(clipboardText = "copied")

        service.executeAction(payload, context)

        assertEquals(0, context.textRequests)
    }
}
