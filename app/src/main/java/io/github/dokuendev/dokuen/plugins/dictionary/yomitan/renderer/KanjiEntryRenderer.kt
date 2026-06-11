package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of display-generator.js::createKanjiEntry and relevant templates from the
 * Yomitan browser extension. Renders kanji entries following the fixed template
 * structure:
 *
 * 1. Glyph section
 *    - Large kanji character display
 * 2. Meanings section
 *    - Ordered list of definitions
 * 3. Readings section
 *    - On-readings (Chinese, katakana): onyomi
 *    - Kun-readings (Japanese, hiragana): kunyomi
 * 4. Statistics section
 *    - Table with grade, frequency, strokes, JLPT level
 * 5. Classifications section (optional)
 *    - Key-value pairs from stats.class
 * 6. Codepoints section (optional)
 *    - Unicode, JIS, etc. from stats.code
 * 7. Dictionary Indices section (optional)
 *    - Dictionary-specific reference numbers from stats.index
 *
 * This is not a generic renderer. It implements the specific template logic from
 * display-generator.js and templates-display.html for kanji entries.
 *
 * Reference: yomitan/ext/js/display/display-generator.js::createKanjiEntry
 * Reference: yomitan/ext/data/templates/default/templates-display.html
 */
class KanjiEntryRenderer(
    private val spanGenerator: SpanGenerator
) {
    /**
     * Render a kanji dictionary entry.
     *
     * This method creates a DictionaryEntry following the fixed template structure
     * from display-generator.js::createKanjiEntry. The output includes:
     * - Large kanji glyph with prominence styling
     * - Meanings as an ordered list
     * - On-readings and kun-readings in separate sections
     * - Statistics table with grade, frequency, strokes, JLPT
     * - Optional sections for classifications, codepoints, and dictionary indices
     *
     * The rendering process:
     * 1. Extract character from KanjiEntity (used as headword)
     * 2. Render meanings list (ordered list with proper formatting)
     * 3. Render readings sections (onyomi and kunyomi separately)
     * 4. Render statistics table from stats JSON
     * 5. Render optional sections if present in stats
     * 6. Build final DictionaryEntry with StyledText body
     *
     * Reference: yomitan/ext/js/display/display-generator.js::createKanjiEntry
     *
     * @param kanjiEntity The kanji entry from the database
     * @param tagMetaList List of tag metadata for styling
     * @return DictionaryEntry with headword (kanji character), empty pronunciation, and styled body
     */
    fun render(
        kanjiEntity: KanjiEntity,
        tagMetaList: List<TagMetaEntity>,
        dictionaryEntity: DictionaryEntity? = null
    ): DictionaryEntry {
        // Render kanji tags and dictionary badge
        renderTags(kanjiEntity, tagMetaList, dictionaryEntity)

        // Render meanings, readings, and statistics in a main table
        renderMainTable(kanjiEntity.meanings, kanjiEntity.onyomi, kanjiEntity.kunyomi, kanjiEntity.stats, tagMetaList)

        // Render optional sections (classifications, codepoints, dictionary indices)
        // Reference: yomitan/ext/js/display/display-generator.js lines 301-315
        // These sections are only rendered if data is present in the stats JSON
        renderOptionalSections(kanjiEntity.stats, tagMetaList)

        // Build the body content using SpanGenerator
        val body = spanGenerator.build()

        return DictionaryEntry(
            headword = kanjiEntity.character,
            pronunciation = null,
            headwordSpans = null,
            body = body,
            displayFlags = io.github.dokuendev.dokuenreader.dictionary.FLAG_HEADWORD_STROKE_ORDER
        )
    }

    /**
     * Render the kanji tags with category-based styling.
     *
     * Reference: yomitan/ext/js/display/display-generator.js lines 208, 258
     */
    private fun renderTags(
        kanjiEntity: KanjiEntity,
        tagMetaList: List<TagMetaEntity>,
        dictionaryEntity: DictionaryEntity?
    ) {
        val tagNames = kanjiEntity.tags.split(' ').filter { it.isNotBlank() }.distinct()
        val tagMetaMap = tagMetaList.associateBy { it.name }
        val validTagNames = tagNames.filter { it in tagMetaMap }

        if (validTagNames.isNotEmpty() || dictionaryEntity != null) {
            TagRenderer.appendTagBadges(validTagNames, tagMetaMap, spanGenerator)

            if (dictionaryEntity != null) {
                val hoverText = TagRenderer.buildDictionaryHoverText(dictionaryEntity)
                TagRenderer.appendTagBadge(
                    dictionaryEntity.title,
                    TagRenderer.getCategoryColor("dictionary"),
                    hoverText,
                    spanGenerator
                )
            }

            spanGenerator.newline()
            spanGenerator.newline()
        }
    }

    private fun renderMainTable(
        meaningsJson: String,
        onyomiString: String,
        kunyomiString: String,
        statsJson: String?,
        tagMetaList: List<TagMetaEntity>
    ) {
        val meanings = try {
            Json.parseToJsonElement(meaningsJson)
                .jsonArray
                .map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyList()
        }
        val meaningsCell = meanings.mapIndexed { index, meaning ->
            "${index + 1}. $meaning"
        }.joinToString("<br>")

        val onyomiReadings = if (onyomiString.isNotBlank()) onyomiString.trim().split("\\s+".toRegex()) else emptyList()
        val kunyomiReadings =
            if (kunyomiString.isNotBlank()) kunyomiString.trim().split("\\s+".toRegex()) else emptyList()
        val onyomiContent = onyomiReadings.joinToString("<br>")
        val kunyomiContent = kunyomiReadings.joinToString("<br>")
        val readingsCell = when {
            onyomiContent.isNotEmpty() && kunyomiContent.isNotEmpty() -> "$onyomiContent<br><br>$kunyomiContent"
            onyomiContent.isNotEmpty() -> onyomiContent
            kunyomiContent.isNotEmpty() -> kunyomiContent
            else -> ""
        }

        val statsMap = try {
            val jsonElement = Json.parseToJsonElement(statsJson ?: "{}")
            jsonElement.jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
        val miscStats = statsMap.filter { (name, _) ->
            tagMetaList.any { tag -> tag.name == name && tag.category == "misc" }
        }
        val statItems = miscStats.mapNotNull { (name, value) ->
            val tagMeta = tagMetaList.find { it.name == name && it.category == "misc" }
            if (tagMeta != null) {
                val displayName = tagMeta.notes.ifBlank { name }
                Triple(tagMeta, displayName, value)
            } else null
        }.sortedWith(compareBy<Triple<TagMetaEntity, String, String>> { it.first.order }.thenBy { it.second })
        val statsCell = statItems.joinToString("<br>") { (_, displayName, value) ->
            "$displayName: $value"
        }

        val headers = mutableListOf<String>()
        val cells = mutableListOf<String>()
        if (meaningsCell.isNotEmpty()) {
            headers.add("Meaning")
            cells.add(meaningsCell)
        }
        if (readingsCell.isNotEmpty()) {
            headers.add("Readings")
            cells.add(readingsCell)
        }
        if (statsCell.isNotEmpty()) {
            headers.add("Statistics")
            cells.add(statsCell)
        }

        if (headers.isNotEmpty()) {
            val tableStart = spanGenerator.getCurrentIndex()
            spanGenerator.appendText("| " + headers.joinToString(" | ") + " |")
            spanGenerator.newline()
            spanGenerator.appendText("| " + cells.joinToString(" | ") + " |")
            spanGenerator.newline()
            val tableEnd = spanGenerator.getCurrentIndex()

            spanGenerator.addBlockSpan(
                startIndex = tableStart,
                endIndex = tableEnd,
                blockType = 3, // BLOCK_TYPE_TABLE
                backgroundColor = 0,
                listMarker = null,
                indentLevel = 0
            )

            var currentOffset = tableStart + 2
            headers.forEach { header ->
                spanGenerator.addStyledSpan(
                    currentOffset,
                    currentOffset + header.length,
                    io.github.dokuendev.dokuenreader.dictionary.InlineStyle(
                        bold = true,
                        italic = false,
                        fontSize = 1.0f,
                        foregroundColor = 0,
                        textBackgroundColor = 0,
                        hoverText = null,
                        linkUrl = null
                    )
                )
                currentOffset += header.length + 3
            }
            spanGenerator.newline()
        }
    }

    /**
     * Render optional sections (classifications, codepoints, dictionary indices) if present.
     *
     * From the reference implementation:
     * - display-generator.js lines 301-315: Creates three optional sections:
     *   1. Classifications (stats.class): deroo, four_corner, skip, etc.
     *   2. Codepoints (stats.code): jis208, jis212, jis213, ucs, etc.
     *   3. Dictionary Indices (stats.index): busy_people, crowley, gakken, etc.
     * - Each section is only rendered if data exists (elements.length > 0)
     * - If no data exists, the header and container are removed entirely
     * - Uses _createKanjiInfoTable which returns empty array if count === 0
     *
     * The rendering logic:
     * 1. Filter stats to only include the relevant category
     * 2. Create table items with display names from tag_meta.notes
     * 3. If no items exist, skip the entire section
     * 4. Otherwise, render section header and table
     *
     * Reference: yomitan/ext/js/display/display-generator.js lines 301-325
     * Reference: yomitan/ext/js/display/display-generator.js lines 700-727 (_createKanjiInfoTable)
     *
     * @param statsJson JSON object with stat key-value pairs (e.g., {"jis208": "1-38-92", "ucs": "65e5"})
     * @param tagMetaList List of tag metadata with name, category, and notes fields
     */
    private fun renderOptionalSections(statsJson: String?, tagMetaList: List<TagMetaEntity>) {
        // If stats JSON is null or empty, skip rendering
        if (statsJson.isNullOrBlank()) {
            return
        }

        // Parse the stats JSON object
        val statsMap = try {
            val jsonElement = Json.parseToJsonElement(statsJson)
            jsonElement.jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            // If parsing fails, skip rendering optional sections
            emptyMap()
        }

        // Render classifications section (category: "class")
        renderStatSection(
            statsMap = statsMap,
            tagMetaList = tagMetaList,
            category = "class",
            sectionTitle = "Classifications"
        )

        // Render codepoints section (category: "code")
        renderStatSection(
            statsMap = statsMap,
            tagMetaList = tagMetaList,
            category = "code",
            sectionTitle = "Codepoints"
        )

        // Render dictionary indices section (category: "index")
        renderStatSection(
            statsMap = statsMap,
            tagMetaList = tagMetaList,
            category = "index",
            sectionTitle = "Dictionary Indices"
        )
    }

    /**
     * Render a single stat section (helper method for renderOptionalSections).
     *
     * This method filters stats by category, creates table items with display names,
     * and renders the section only if data exists.
     *
     * The rendering format is identical to renderStatisticsTable:
     * - Section header in bold with larger font
     * - Key-value pairs in "Name: Value" format
     * - Sorted by tag order
     *
     * Reference: yomitan/ext/js/display/display-generator.js lines 700-727
     *
     * @param statsMap Parsed stats map (key-value pairs)
     * @param tagMetaList List of tag metadata
     * @param category The category to filter by ("class", "code", or "index")
     * @param sectionTitle The section header title ("Classifications", "Codepoints", or "Dictionary Indices")
     */
    private fun renderStatSection(
        statsMap: Map<String, String>,
        tagMetaList: List<TagMetaEntity>,
        category: String,
        sectionTitle: String
    ) {
        val categoryStats = statsMap.filter { (name, _) ->
            tagMetaList.any { tag -> tag.name == name && tag.category == category }
        }
        if (categoryStats.isEmpty()) return

        val statItems = categoryStats.mapNotNull { (name, value) ->
            val tagMeta = tagMetaList.find { it.name == name && it.category == category }
            if (tagMeta != null) {
                val displayName = tagMeta.notes.ifBlank { name }
                Triple(tagMeta, displayName, value)
            } else null
        }.sortedWith(compareBy<Triple<TagMetaEntity, String, String>> { it.first.order }.thenBy { it.second })

        if (statItems.isEmpty()) return

        val tableStart = spanGenerator.getCurrentIndex()
        spanGenerator.appendText("| $sectionTitle |  |")
        spanGenerator.newline()

        statItems.forEach { (_, displayName, value) ->
            spanGenerator.appendText("| $displayName | $value |")
            spanGenerator.newline()
        }
        val tableEnd = spanGenerator.getCurrentIndex()

        spanGenerator.addBlockSpan(
            startIndex = tableStart,
            endIndex = tableEnd,
            blockType = 3, // BLOCK_TYPE_TABLE
            backgroundColor = 0,
            listMarker = null,
            indentLevel = 0
        )

        spanGenerator.addStyledSpan(
            tableStart + 2,
            tableStart + 2 + sectionTitle.length,
            io.github.dokuendev.dokuenreader.dictionary.InlineStyle(
                bold = true,
                italic = false,
                fontSize = 1.0f,
                foregroundColor = 0,
                textBackgroundColor = 0,
                hoverText = null,
                linkUrl = null
            )
        )
        spanGenerator.newline()
    }
}
