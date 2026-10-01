package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardFormat
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiCardIcon
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidClient
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiEntryType
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiField
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettings
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiSettingsStore
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiE2ETest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val dictTitle = "E2E Dict"

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
                        )
                    )
                )
            }
            val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
            prefs.edit()
                .putString("installed_dictionaries", dictTitle)
                .putString("active_dictionaries", dictTitle)
                .commit()
        }
    }

    @Test
    fun testLookupProducesActionLinkAndExecutingItReachesAnkiDroidWithRenderedFields() {
        runBlocking {
            val settings = AnkiSettings(
                enabled = true,
                cardFormats = listOf(
                    AnkiCardFormat(
                        name = "Vocab",
                        type = AnkiEntryType.TERM,
                        deckId = 1L,
                        deckName = "Default",
                        modelId = 2L,
                        modelName = "Basic",
                        fields = mapOf(
                            "Front" to AnkiField(value = "{expression} ({reading})"),
                            "Back" to AnkiField(value = "{glossary}")
                        )
                    )
                )
            )
            AnkiSettingsStore.save(app, settings)

            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
            val config = Bundle().apply { putString(PluginHostConfigKeys.UI_THEME, "light") }
            service.onInitialize(config)

            val queryUrl = "?query=%E9%A3%9F%E3%81%B9%E3%82%8B&wildcards=off"
            val result = service.onLookup(queryUrl, 0, queryUrl.length)

            assertTrue(result.entries[0].body.text.contains("Add: Vocab"))
            val linkSpan =
                result.entries[0].body.styledSpans!!.first { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            val payload = linkSpan.style.linkUrl!!.removePrefix("action:")

            // AddContentApi cannot run under Robolectric (no AnkiDroid app present), so the
            // real client fails at its first gate. Reaching that "AnkiDroid not installed"
            // error proves only that the link's token resolved to a cached lookup, its format
            // was found, and export was enabled; rendering the field values and the AnkiDroid
            // calls themselves are covered against a mocked client in AnkiExecutionTest.
            try {
                service.onExecuteCustomAction(payload)
                fail("Expected DictionaryException for missing AnkiDroid")
            } catch (e: DictionaryException) {
                assertTrue(e.message?.contains("AnkiDroid") == true)
            }
        }
    }

    @Test
    fun testIncompleteFormatDoesNotShiftAddLinkIndexToWrongCardFormat() {
        runBlocking {
            // "Incomplete" sorts first in cardFormats but has no deck/model chosen yet (as if
            // created via the tab-strip editor's "+" and never finished). It must be excluded
            // from the rendered Add links, AND from the list that a tapped link's format=index
            // is resolved against at execute time - otherwise "Incomplete" being present but
            // filtered only on the render side shifts every subsequent format's index, so a tap
            // on "Vocab" could silently resolve back to "Incomplete" instead.
            val settings = AnkiSettings(
                enabled = true,
                checkForDuplicates = false,
                cardFormats = listOf(
                    AnkiCardFormat(
                        name = "Incomplete",
                        type = AnkiEntryType.TERM,
                        deckId = 0L,
                        deckName = "",
                        modelId = 0L,
                        modelName = "",
                        fields = emptyMap()
                    ),
                    AnkiCardFormat(
                        name = "Vocab",
                        type = AnkiEntryType.TERM,
                        deckId = 1L,
                        deckName = "Default",
                        modelId = 2L,
                        modelName = "Basic",
                        fields = mapOf(
                            "Front" to AnkiField(value = "{expression} ({reading})"),
                            "Back" to AnkiField(value = "{glossary}")
                        )
                    )
                )
            )
            AnkiSettingsStore.save(app, settings)

            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
            val config = Bundle().apply { putString(PluginHostConfigKeys.UI_THEME, "light") }
            service.onInitialize(config)

            // Stub out AnkiDroidClient entirely so this test can directly observe *which* card
            // format's modelId/deckId reach addNote() - that's the only way to prove format
            // resolution picked "Vocab" and not "Incomplete", since both would otherwise fail
            // identically at the isAnkiDroidInstalled() gate under Robolectric regardless of
            // which format was resolved.
            val mockClient = mock(AnkiDroidClient::class.java)
            `when`(mockClient.isAnkiDroidInstalled()).thenReturn(true)
            `when`(mockClient.hasPermission()).thenReturn(true)
            `when`(mockClient.getFieldList(any())).thenReturn(listOf("Front", "Back"))
            `when`(mockClient.getDeckList()).thenReturn(mapOf(1L to "Default"))
            `when`(mockClient.addNote(any(), any(), any(), any())).thenReturn(999L)

            service.ankiDroidClient = mockClient

            val queryUrl = "?query=%E9%A3%9F%E3%81%B9%E3%82%8B&wildcards=off"
            val result = service.onLookup(queryUrl, 0, queryUrl.length)

            val addLinkSpans = result.entries[0].body.styledSpans!!.filter {
                it.style.linkUrl?.startsWith("action:anki_add?") == true
            }
            // Only "Vocab" (the complete format) renders a link at all - "Incomplete" is
            // excluded from the render list entirely, so there is exactly one Add link.
            assertEquals(1, addLinkSpans.size)

            val payload = addLinkSpans.first().style.linkUrl!!.removePrefix("action:")
            val formatIndex = payload.substringAfter("format=").toInt()
            // "Vocab" is now first (and only) in the filtered render-list, so its encoded
            // index is 0.
            assertEquals(0, formatIndex)

            // Must not throw at all: reaching the correct ("Vocab") format means addNote()
            // gets called with real fields and a real deck/model, succeeding cleanly - unlike
            // reaching "Incomplete", which has no fields configured.
            service.onExecuteCustomAction(payload)

            val modelIdCaptor = argumentCaptor<Long>()
            val deckIdCaptor = argumentCaptor<Long>()
            verify(mockClient).addNote(modelIdCaptor.capture(), deckIdCaptor.capture(), any(), any())
            // format=0 resolved to "Vocab" (modelId=2, deckId=1), not "Incomplete" (modelId=0,
            // deckId=0), even though "Incomplete" sorts first in ankiSettings.cardFormats.
            assertEquals(2L, modelIdCaptor.firstValue)
            assertEquals(1L, deckIdCaptor.firstValue)
        }
    }

    @Test
    fun testAnkiLinksAreStackedSmaller() {
        runBlocking {
            fun format(name: String, icon: AnkiCardIcon) = AnkiCardFormat(
                name = name,
                type = AnkiEntryType.TERM,
                deckId = 1L,
                deckName = "Default",
                modelId = 2L,
                modelName = "Basic",
                fields = mapOf("Front" to AnkiField(value = "{expression}")),
                icon = icon
            )
            AnkiSettingsStore.save(
                app,
                AnkiSettings(
                    enabled = true,
                    cardFormats = listOf(
                        format("Vocab", AnkiCardIcon.BIG_CIRCLE),
                        format("Sentences", AnkiCardIcon.SMALL_CIRCLE)
                    )
                )
            )
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
            service.onInitialize(Bundle().apply { putString(PluginHostConfigKeys.UI_THEME, "light") })

            val queryUrl = "?query=%E9%A3%9F%E3%81%B9%E3%82%8B&wildcards=off"
            val entry = service.onLookup(queryUrl, 0, queryUrl.length).entries[0]

            // One link per line, both drawn smaller than the dictionary text.
            assertTrue(entry.body.text, entry.body.text.endsWith("\n● Add: Vocab\n◉ Add: Sentences"))
            val spans = entry.body.styledSpans!!
            val links = spans.filter { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            assertEquals(2, links.size)
            val linkSize = links[0].style.fontSize
            assertTrue("Links should be smaller than the text, was $linkSize", linkSize < 1f)
            assertEquals(linkSize, links[1].style.fontSize, 0.0001f)

            // Each shape is its own glyph inside the link span, with no span of its own.
            for (link in links) {
                assertTrue(spans.none { it.style.linkUrl == null && it.startIndex == link.startIndex })
            }
        }
    }

    @Test
    fun testAnkiActionLinksUseSameColorAcrossDifferentIconShapes() {
        // In original Yomitan, shape identifies the card format and color encodes the add/
        // overwrite/duplicate state (ext/css/display.css and the icon SVGs). Duplicates are only
        // checked once a link is tapped (see the comment above appendAnkiActionLinks), so a fresh
        // lookup shows every link in the "Add" state's green, whatever its icon shape.
        runBlocking {
            val settings = AnkiSettings(
                enabled = true,
                cardFormats = listOf(
                    AnkiCardFormat(
                        name = "Vocab",
                        type = AnkiEntryType.TERM,
                        deckId = 1L,
                        deckName = "Default",
                        modelId = 2L,
                        modelName = "Basic",
                        fields = mapOf("Front" to AnkiField(value = "{expression}")),
                        icon = AnkiCardIcon.BIG_CIRCLE
                    ),
                    AnkiCardFormat(
                        name = "Sentence Mining",
                        type = AnkiEntryType.TERM,
                        deckId = 1L,
                        deckName = "Default",
                        modelId = 2L,
                        modelName = "Basic",
                        fields = mapOf("Front" to AnkiField(value = "{expression}")),
                        icon = AnkiCardIcon.DIAMOND
                    )
                )
            )
            AnkiSettingsStore.save(app, settings)

            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
            val config = Bundle().apply { putString(PluginHostConfigKeys.UI_THEME, "light") }
            service.onInitialize(config)

            val queryUrl = "?query=%E9%A3%9F%E3%81%B9%E3%82%8B&wildcards=off"
            val result = service.onLookup(queryUrl, 0, queryUrl.length)

            val addLinkSpans = result.entries[0].body.styledSpans!!
                .filter { it.style.linkUrl?.startsWith("action:anki_add?") == true }
            assertEquals(2, addLinkSpans.size)

            val colors = addLinkSpans.map { it.style.foregroundColor }.distinct()
            assertEquals("Every format's Add link should render in the same color", 1, colors.size)
            assertEquals(0xFF6FB558.toInt(), colors.single())
        }
    }
}
