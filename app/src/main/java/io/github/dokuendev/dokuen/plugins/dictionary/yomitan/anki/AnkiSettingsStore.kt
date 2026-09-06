package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.content.Context
import androidx.core.content.edit
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.R
import kotlinx.serialization.json.Json

object AnkiSettingsStore {
    private const val PREFS_NAME = "yomitan_prefs"
    private const val KEY = "anki_settings"

    // encodeDefaults writes every value, including ones equal to the current defaults, so a
    // later change to a default never silently changes an existing user's settings.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The settings a new user starts with: [AnkiSettings]'s defaults plus the original's three
     * default card formats (Expression and Reading for terms, Kanji for kanji), with no deck or
     * note type chosen yet.
     */
    fun defaults(context: Context): AnkiSettings = AnkiSettings(
        cardFormats = listOf(
            defaultFormat(
                context.getString(R.string.anki_card_format_default_expression),
                AnkiEntryType.TERM,
                AnkiCardIcon.BIG_CIRCLE
            ),
            defaultFormat(
                context.getString(R.string.anki_card_format_default_reading),
                AnkiEntryType.TERM,
                AnkiCardIcon.SMALL_CIRCLE
            ),
            defaultFormat(
                context.getString(R.string.kanji),
                AnkiEntryType.KANJI,
                AnkiCardIcon.BIG_CIRCLE
            )
        )
    )

    private fun defaultFormat(name: String, type: AnkiEntryType, icon: AnkiCardIcon) = AnkiCardFormat(
        name = name,
        type = type,
        deckId = 0L,
        deckName = "",
        modelId = 0L,
        modelName = "",
        icon = icon
    )

    fun load(context: Context): AnkiSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return defaults(context)
        return try {
            json.decodeFromString<AnkiSettings>(raw)
        } catch (e: Exception) {
            defaults(context)
        }
    }

    fun save(context: Context, settings: AnkiSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY, json.encodeToString<AnkiSettings>(settings)) }
    }
}
