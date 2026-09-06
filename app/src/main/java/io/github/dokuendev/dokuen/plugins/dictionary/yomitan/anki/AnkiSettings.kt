package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlinx.serialization.Serializable

@Serializable
enum class AnkiEntryType { TERM, KANJI }

@Serializable
enum class AnkiDuplicateScope { COLLECTION, DECK, DECK_ROOT }

@Serializable
enum class AnkiDuplicateBehavior { PREVENT, OVERWRITE, ALWAYS_ADD }

/**
 * Declared in the order of the original's per-field overwrite menu (templates-settings.html),
 * which the settings UI lists in declaration order. Persisted by name, so reordering is safe.
 */
@Serializable
enum class AnkiFieldOverwriteMode { SKIP, PREPEND, APPEND, COALESCE, COALESCE_NEW, OVERWRITE }

/**
 * The original's 4 icon choices. Action links are plain text with no icon-attachment point,
 * so each choice maps to a plain shape glyph prepended to the format's action-link label at
 * render time (see the `AnkiCardIcon.glyph()` extension in YomitanDictionaryPluginService.kt).
 * The shape alone tells the formats apart: as with the original's icons, a link's color shows
 * its duplicate state, not its format.
 */
@Serializable
enum class AnkiCardIcon { BIG_CIRCLE, SMALL_CIRCLE, SQUARE, DIAMOND }

@Serializable
data class AnkiField(
    val value: String = "",
    val overwriteMode: AnkiFieldOverwriteMode = AnkiFieldOverwriteMode.COALESCE
)

@Serializable
data class AnkiCardFormat(
    val name: String,
    val type: AnkiEntryType,
    val deckId: Long,
    val deckName: String,
    val modelId: Long,
    val modelName: String,
    val fields: Map<String, AnkiField> = emptyMap(),
    val icon: AnkiCardIcon = AnkiCardIcon.BIG_CIRCLE
)

/**
 * Settings the original Yomitan browser extension's Anki tab exposes but this port
 * deliberately does not, because AnkiDroid's API surface has no equivalent, not because
 * of engineering effort:
 * - AnkiConnect server address / API key: structurally inapplicable. This port talks to
 *   AnkiDroid directly via AddContentApi/FlashCardsContract, which has no server address
 *   or API key concept at all.
 * - Suspend new cards / force Anki sync on adding a card: AddContentApi exposes no method
 *   for suspending cards or forcing a sync (confirmed against the AnkiDroid API's shipped
 *   sources), a genuine API-surface gap rather than a tradeoff.
 * - Screenshot format/quality, audio download timeout: not yet applicable since the
 *   {screenshot} and {audio} template markers themselves have no real implementation yet
 *   (see docs/anki-export.md), so revisit if/when those markers ship.
 *
 * Showing existing-note tags/flags before the user taps "Add" is also deliberately not
 * implemented: it would need one AnkiDroid ContentProvider round-trip per configured card
 * format on every single lookup (a hot path), just for a label nicety. See the matching
 * comment on appendAnkiActionLinks in YomitanDictionaryPluginService.kt, which defers the
 * duplicate check until a link is tapped for the same reason.
 *
 * Defaults match the original's options schema (ext/data/schemas/options-schema.json), with one
 * intentional exception: the default card tag is "dokuen-yomitan" rather than the original's
 * "yomitan", so notes this port adds can be told apart from notes desktop Yomitan adds when both
 * write to the same synced collection. The original's three default card formats have
 * user-facing names, so they come from string resources via AnkiSettingsStore.defaults rather
 * than from [cardFormats]'s default here.
 */
@Serializable
data class AnkiSettings(
    val enabled: Boolean = false,
    val cardFormats: List<AnkiCardFormat> = emptyList(),
    val tags: List<String> = listOf("dokuen-yomitan"),
    val checkForDuplicates: Boolean = true,
    val duplicateScope: AnkiDuplicateScope = AnkiDuplicateScope.COLLECTION,
    val duplicateScopeCheckAllModels: Boolean = false,
    val duplicateBehavior: AnkiDuplicateBehavior = AnkiDuplicateBehavior.ALWAYS_ADD
)
