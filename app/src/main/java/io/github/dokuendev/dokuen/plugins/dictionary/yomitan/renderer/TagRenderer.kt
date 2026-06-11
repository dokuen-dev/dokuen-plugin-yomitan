package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.TagRenderer.appendTagBadge
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.TagRenderer.getCategoryColor
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared helpers for rendering tag badges in both term and kanji entries.
 *
 * Both TermEntryRenderer and KanjiEntryRenderer need to:
 * - Map tag categories to ARGB background colors
 * - Render a " text " badge as a styled span in a SpanGenerator
 * - Build hover text for a DictionaryEntity tooltip
 *
 * This object centralizes those three pieces so neither renderer duplicates them.
 *
 * Reference: yomitan/ext/css/display.css (tag category colors, lines 142-152)
 * Reference: yomitan/ext/js/display/display-generator.js::_createTag
 */
object TagRenderer {

    /**
     * Map a Yomitan tag category name to its ARGB background color.
     *
     * Colors are taken from the default light theme in display.css.
     * All values include a full 0xFF alpha byte so they are opaque.
     *
     * @param category The tag category string (e.g. "partOfSpeech", "frequency")
     * @return ARGB color integer for use as textBackgroundColor
     */
    // @formatter:off
    fun getCategoryColor(category: String): Int = when (category) {
        "partOfSpeech"             -> 0xFF565656.toInt() // Gray
        "archaism"                 -> 0xFFD9534F.toInt() // Brown/red
        "popular"                  -> 0xFF0275D8.toInt() // Blue
        "frequency"                -> 0xFF5CB85C.toInt() // Green
        "name"                     -> 0xFFB6327A.toInt() // Magenta
        "expression"               -> 0xFFF0AD4E.toInt() // Orange
        "dictionary"               -> 0xFFAA66CC.toInt() // Purple
        "search"                   -> 0xFF8A8A91.toInt() // Gray
        "frequent"                 -> 0xFF5BC0DE.toInt() // Cyan
        "pronunciation-dictionary" -> 0xFF6640BE.toInt() // Purple
        else                       -> 0xFF8A8A91.toInt() // Default gray
    }
    // @formatter:on

    /**
     * Append a space-padded badge (" text ") to [spanGenerator] and register
     * the corresponding [InlineStyle] span.
     *
     * The badge always uses white text (0xFFFFFFFF), bold, 0.8× font size, and
     * the supplied [backgroundColor] as [InlineStyle.textBackgroundColor].
     *
     * @param text            The label to display inside the badge (unpadded)
     * @param backgroundColor ARGB background color, typically from [getCategoryColor]
     * @param hoverText       Optional tooltip text; null produces no tooltip
     * @param spanGenerator   The accumulator to write into
     */
    fun appendTagBadge(
        text: String,
        backgroundColor: Int,
        hoverText: String?,
        spanGenerator: SpanGenerator
    ) {
        val start = spanGenerator.getCurrentIndex()
        spanGenerator.appendText(" $text ")
        val end = spanGenerator.getCurrentIndex()

        spanGenerator.addStyledSpan(
            start, end,
            InlineStyle(
                bold = true,
                italic = false,
                fontSize = 0.8f,
                foregroundColor = 0xFFFFFFFF.toInt(),
                textBackgroundColor = backgroundColor,
                hoverText = hoverText,
                linkUrl = null
            )
        )
    }

    /**
     * Build the multi-line hover text for a dictionary badge tooltip.
     *
     * Returns null when [dictionaryEntity] is null (no badge tooltip needed).
     *
     * Lines included:
     * - Title
     * - Revision ("rev.X")
     * - Author (if non-blank)
     * - Description (if non-blank)
     * - URL (if non-blank)
     * - Term count from the counts JSON (if present and > 0)
     *
     * @param dictionaryEntity The dictionary metadata, or null
     * @return Newline-joined tooltip string, or null
     */
    fun buildDictionaryHoverText(dictionaryEntity: DictionaryEntity?): String? {
        dictionaryEntity ?: return null

        val lines = mutableListOf<String>()
        lines.add(dictionaryEntity.title)
        lines.add("rev.${dictionaryEntity.revision}")
        if (!dictionaryEntity.author.isNullOrBlank()) {
            lines.add("Author: ${dictionaryEntity.author}")
        }
        if (!dictionaryEntity.description.isNullOrBlank()) {
            lines.add("Description: ${dictionaryEntity.description}")
        }
        if (!dictionaryEntity.url.isNullOrBlank()) {
            lines.add("URL: ${dictionaryEntity.url}")
        }
        val countsJson = dictionaryEntity.counts
        if (!countsJson.isNullOrBlank()) {
            try {
                val json = Json.parseToJsonElement(countsJson).jsonObject
                val termsTotal = json["terms"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
                if (termsTotal != null && termsTotal > 0) {
                    lines.add("Term Count: $termsTotal")
                }
            } catch (_: Exception) {
                // counts field is optional; ignore parse failures
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Render a list of [TagMetaEntity]-backed tag badges followed by a space separator.
     *
     * For each tag name, looks up the corresponding [TagMetaEntity] in [tagMetaMap]
     * to obtain its category and hover text, then delegates to [appendTagBadge].
     * A single space is appended after each badge (matching the term renderer convention).
     *
     * @param tagNames   Ordered list of tag name strings to render
     * @param tagMetaMap Map from tag name to [TagMetaEntity] for quick lookup
     * @param spanGenerator The accumulator to write into
     */
    fun appendTagBadges(
        tagNames: List<String>,
        tagMetaMap: Map<String, TagMetaEntity>,
        spanGenerator: SpanGenerator
    ) {
        for (tagName in tagNames) {
            val meta = tagMetaMap[tagName]
            val color = getCategoryColor(meta?.category ?: "")
            val hover = meta?.notes?.takeIf { it.isNotBlank() }
            appendTagBadge(tagName, color, hover, spanGenerator)
            spanGenerator.appendText(" ")
        }
    }

    /**
     * Append an unpadded frequency source badge (dictionary name only, no spaces) and
     * register the corresponding [InlineStyle] span.
     *
     * Frequency badges differ from tag badges in two ways:
     * - No space-padding around the label (the caller appends ": " after)
     * - Always dictionary-purple (0xFFAA66CC), no hover text
     *
     * @param dictionaryName The frequency dictionary name to display
     * @param spanGenerator  The accumulator to write into
     */
    fun appendFrequencyBadge(dictionaryName: String, spanGenerator: SpanGenerator) {
        val start = spanGenerator.getCurrentIndex()
        spanGenerator.appendText(dictionaryName)
        val end = spanGenerator.getCurrentIndex()

        spanGenerator.addStyledSpan(
            start, end,
            InlineStyle(
                bold = true,
                italic = false,
                fontSize = 0.8f,
                foregroundColor = 0xFFFFFFFF.toInt(),
                textBackgroundColor = 0xFFAA66CC.toInt(), // Dictionary purple
                hoverText = null,
                linkUrl = null
            )
        )
    }
}
