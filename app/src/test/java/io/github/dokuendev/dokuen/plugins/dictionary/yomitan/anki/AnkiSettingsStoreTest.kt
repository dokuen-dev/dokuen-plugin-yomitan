package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnkiSettingsStoreTest {

    @Test
    fun testLoadReturnsDefaultsWhenNothingSaved() {
        val app: Application = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val loaded = AnkiSettingsStore.load(app)

        // The original's defaults (ext/data/schemas/options-schema.json), except the card tag,
        // which is intentionally "dokuen-yomitan" rather than "yomitan".
        assertFalse(loaded.enabled)
        assertEquals(listOf("dokuen-yomitan"), loaded.tags)
        assertEquals(AnkiDuplicateScope.COLLECTION, loaded.duplicateScope)
        assertEquals(AnkiDuplicateBehavior.ALWAYS_ADD, loaded.duplicateBehavior)
        assertEquals(
            listOf(
                Triple("Expression", AnkiEntryType.TERM, AnkiCardIcon.BIG_CIRCLE),
                Triple("Reading", AnkiEntryType.TERM, AnkiCardIcon.SMALL_CIRCLE),
                Triple("Kanji", AnkiEntryType.KANJI, AnkiCardIcon.BIG_CIRCLE)
            ),
            loaded.cardFormats.map { Triple(it.name, it.type, it.icon) }
        )
        assertTrue(loaded.cardFormats.all { it.deckId == 0L && it.modelId == 0L && it.fields.isEmpty() })
    }

    @Test
    fun testSaveWritesDefaultValuedSettingsExplicitly() {
        // Values equal to a default are still written, so changing a default later cannot
        // silently change settings a user already has.
        val app: Application = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        AnkiSettingsStore.save(app, AnkiSettings())

        val raw = prefs.getString("anki_settings", null)!!
        for (key in listOf(
            "enabled",
            "cardFormats",
            "tags",
            "checkForDuplicates",
            "duplicateScope",
            "duplicateBehavior"
        )) {
            assertTrue(raw, raw.contains("\"$key\""))
        }
    }

    @Test
    fun testSaveThenLoadRoundTripsCardFormats() {
        val app: Application = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE).edit().clear().commit()

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
                        "Front" to AnkiField(value = "{expression}", overwriteMode = AnkiFieldOverwriteMode.COALESCE),
                        "Back" to AnkiField(value = "{glossary}", overwriteMode = AnkiFieldOverwriteMode.APPEND)
                    ),
                    icon = AnkiCardIcon.DIAMOND
                )
            ),
            tags = listOf("dokuen"),
            checkForDuplicates = true,
            duplicateScope = AnkiDuplicateScope.DECK,
            duplicateScopeCheckAllModels = true,
            duplicateBehavior = AnkiDuplicateBehavior.OVERWRITE
        )

        AnkiSettingsStore.save(app, settings)
        val loaded = AnkiSettingsStore.load(app)

        assertEquals(settings, loaded)
    }

    @Test
    fun testLoadReturnsDefaultsWhenStoredJsonIsCorrupted() {
        // load()'s catch-all Exception handler is real recovery logic (e.g. a future app
        // version writes a shape this version can't parse) and had zero coverage - nothing
        // exercised the catch branch at all before this test.
        val app: Application = ApplicationProvider.getApplicationContext()
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("anki_settings", "{not valid json").commit()

        val loaded = AnkiSettingsStore.load(app)

        assertEquals(AnkiSettingsStore.defaults(app), loaded)
    }
}
