package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import android.util.Log
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.StyledText

/**
 * Fixed template renderer for term dictionary entries.
 *
 * This is a direct port of display-generator.js::createTermEntry and relevant templates
 * from the Yomitan browser extension. It renders term entries following the fixed template
 * structure:
 *
 * 1. Headwords section
 *    - Expression and reading pairs with furigana (RubySpan)
 * 2. Tags section
 *    - Dictionary source badge
 *    - Part-of-speech tags
 *    - Frequency tags
 *    - Popularity tags
 * 3. Definitions section
 *    - For each glossary item
 *    - Parse as structured content
 *    - Apply dictionary-specific CSS
 *
 * TermEntryRenderer is responsible for:
 * - Rendering headwords with furigana (RubySpan)
 * - Rendering tags with category-based styling (partOfSpeech, frequency, popular, archaism, etc.)
 * - Rendering frequencies with dictionary labels
 * - Rendering definitions using StructuredContentRenderer
 * - Applying Yomitan's internal term entry styling
 *
 * This is not a generic renderer - it implements the specific template logic from
 * display-generator.js and templates-display.html for term entries.
 *
 * Reference: yomitan/ext/js/display/display-generator.js::createTermEntry
 * Reference: yomitan/ext/data/templates/default/templates-display.html
 */
class TermEntryRenderer(
    private val structuredContentRenderer: StructuredContentRenderer,
    private val spanGenerator: SpanGenerator
) {
    companion object {
        private const val TAG = "TermEntryRenderer"
    }

    /**
     * Render a term dictionary entry.
     *
     * This method creates a DictionaryEntry following the fixed template structure
     * from display-generator.js::createTermEntry. The output includes:
     * - Headword with furigana pronunciation
     * - Tags with category-based styling
     * - Frequencies with dictionary labels
     * - Definitions parsed as structured content
     *
     * The rendering process:
     * 1. Extract expression and reading from TermEntity
     * 2. Render headwords with furigana (RubySpan annotations)
     * 3. Render tags with category-based colors from Yomitan internal CSS
     * 4. Render frequencies with dictionary source labels
     * 5. Parse and render glossary array as structured content
     * 6. Build final DictionaryEntry with StyledText body
     *
     * Reference: yomitan/ext/js/display/display-generator.js::createTermEntry
     *
     * @param termEntity The term entry from the database
     * @param tagMetaList List of tag metadata for styling
     * @param termMetaList List of term metadata including frequency information
     * @return DictionaryEntry with headword, pronunciation, and styled body
     */
    fun render(
        termEntity: TermEntity,
        tagMetaList: List<TagMetaEntity>,
        termMetaList: List<TermMetaEntity> = emptyList(),
        dictionaryEntity: DictionaryEntity? = null
    ): DictionaryEntry {
        return render(listOf(termEntity), tagMetaList, termMetaList, dictionaryEntity)
    }

    fun render(
        termEntities: List<TermEntity>,
        tagMetaList: List<TagMetaEntity>,
        termMetaList: List<TermMetaEntity> = emptyList(),
        dictionaryEntity: DictionaryEntity? = null
    ): DictionaryEntry {
        if (termEntities.isEmpty()) {
            return DictionaryEntry("", null, null, StyledText("", null, null, null))
        }
        if (termEntities.size == 1) {
            return renderSingle(termEntities.first(), tagMetaList, termMetaList, dictionaryEntity)
        }
        return renderGrouped(termEntities, tagMetaList, termMetaList, dictionaryEntity)
    }

    private fun renderSingle(
        termEntity: TermEntity,
        tagMetaList: List<TagMetaEntity>,
        termMetaList: List<TermMetaEntity> = emptyList(),
        dictionaryEntity: DictionaryEntity? = null
    ): DictionaryEntry {
        // Basic setup - extract headword and reading
        // This establishes the structure for the fixed template rendering

        val headword = termEntity.expression
        val reading = termEntity.reading

        // Render headwords with furigana
        // Extract expression and reading from TermEntity
        // Create ruby annotations mapping base characters to readings
        // Apply furigana as RubySpan objects in DictionaryEntry.pronunciation field
        //
        // Reference: yomitan/ext/js/display/display-generator.js::_createTermHeadword
        // Reference: yomitan/ext/js/display/display-generator.js::_appendFurigana
        // Reference: yomitan/ext/js/language/ja/japanese.js::distributeFurigana
        //
        // The original implementation uses distributeFurigana to intelligently segment
        // the expression into kanji and kana groups, then maps the reading to each group.
        // Each segment with a reading becomes a ruby element in the DOM.
        //
        // For our flat text model with RubySpan annotations:
        // - Distribute furigana across the headword using JapaneseUtil.distributeFurigana
        // - For each segment with a reading, create a RubySpan
        // - RubySpan startIndex/endIndex map to character positions in the headword
        val pronunciation = if (reading != headword) {
            // Use distributeFurigana to intelligently map reading to characters
            val segments = JapaneseUtil.distributeFurigana(headword, reading)

            // Build RubySpan array from segments
            val rubySpans = mutableListOf<io.github.dokuendev.dokuenreader.dictionary.RubySpan>()
            var currentIndex = 0

            for (segment in segments) {
                val segmentLength = segment.text.length
                val segmentEnd = currentIndex + segmentLength

                // Only create RubySpan if this segment has a reading
                if (segment.reading != null) {
                    rubySpans.add(
                        io.github.dokuendev.dokuenreader.dictionary.RubySpan(
                            startIndex = currentIndex,
                            endIndex = segmentEnd,
                            rubyText = segment.reading
                        )
                    )
                }

                currentIndex = segmentEnd
            }

            rubySpans.toTypedArray()
        } else {
            emptyArray()
        }

        // Initialize SpanGenerator for body content
        // This will accumulate text and spans following the template structure:
        // 1. Headwords section
        // 2. Tags section
        // 3. Frequencies section
        // 4. Definitions section

        // Render tags with category-based styling
        // Extract termTags and definitionTags from TermEntity
        // Match tags against TagMetaEntity list to get categories
        // Apply Yomitan internal CSS styling based on category (partOfSpeech, frequency, popular, archaism, etc.)
        // Create styled badge text with white text on colored background
        // Map tag categories to InlineStyle with specific colors
        //
        // Reference: yomitan/ext/js/display/display-generator.js::_createTag
        // Reference: yomitan/ext/css/display.css (tag category colors)
        //
        // Tag categories and their colors from display.css:
        // - partOfSpeech: #565656 (gray)
        // - archaism: #d9534f (brown/red)
        // - popular: #0275d8 (blue)
        // - frequency: #5cb85c (green)
        // - name: #b6327a (magenta)
        // - expression: #f0ad4e (orange)
        // - dictionary: #aa66cc (purple)
        // - search: #8a8a91 (gray)
        // - frequent: #5bc0de (cyan)
        // - pronunciation-dictionary: #6640be (purple)
        //
        // Tag rendering approach:
        // 1. Extract space-separated tag names from termTags and definitionTags
        // 2. Look up each tag in tagMetaList to find its category
        // 3. Apply category-based background color
        // 4. Use white text color (#ffffff) for all tags
        // 5. Use smaller font size (0.8f scale)
        // 6. Create StyledSpan for each tag badge

        // Extract termTags (term-level tags like "news", "ichi1")
        val termTagNames = termEntity.termTags
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Extract definitionTags (definition-level tags like "n", "v1")
        val definitionTagNames = termEntity.definitionTags
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Combine all tags (term tags + definition tags)
        val allTagNames = termTagNames + definitionTagNames

        // Create a map of tag names to TagMetaEntity for quick lookup
        val tagMap = tagMetaList.associateBy { it.name }

        // Render each tag as a space-padded styled badge
        TagRenderer.appendTagBadges(allTagNames, tagMap, spanGenerator)

        // Render the dictionary name badge
        val dictHoverText = TagRenderer.buildDictionaryHoverText(dictionaryEntity)
        TagRenderer.appendTagBadge(
            termEntity.dictionary,
            TagRenderer.getCategoryColor("dictionary"),
            dictHoverText,
            spanGenerator
        )

        // Append space separator after the dictionary name badge
        spanGenerator.appendText(" ")

        // Add newline after tags section
        spanGenerator.newline()

        // Render frequencies with dictionary source labels
        // Extract frequency information from TermMetaEntity list
        // Group frequencies by dictionary
        // Apply badge styling for dictionary names
        // Reference frequency rendering in original templates
        //
        // Reference: yomitan/ext/js/display/display-generator.js::_createFrequencyGroup
        // Reference: yomitan/ext/js/display/display-generator.js::_createTermFrequency
        //
        // The original implementation:
        // 1. Groups frequencies by dictionary
        // 2. For each dictionary, creates a badge with the dictionary name
        // 3. Lists frequency values with optional reading disambiguation
        // 4. Displays frequency as either a numeric value or a displayValue string
        //
        // For our flat text model:
        // - Render dictionary name as a styled badge (similar to tags)
        // - For each frequency: render the value or displayValue
        // - If frequency has a reading, include it for disambiguation
        // - Use bracket notation for disambiguation: expression[reading]: frequency

        // Filter termMeta to only frequency entries
        val frequencyEntries = termMetaList.filter { it.mode == "freq" }

        if (frequencyEntries.isNotEmpty()) {
            // Group frequencies by dictionary
            val frequenciesByDict = frequencyEntries.groupBy { it.dictionary }

            // Render each dictionary's frequencies
            for ((dictionary, frequencies) in frequenciesByDict) {
                // Add space before dictionary badge if this isn't the first content
                if (spanGenerator.getCurrentIndex() > 0) {
                    spanGenerator.appendText(" ")
                }

                TagRenderer.appendFrequencyBadge(dictionary, spanGenerator)

                // Append colon separator
                spanGenerator.appendText(": ")

                // Render each frequency value
                val freqValues = mutableListOf<String>()
                for (freqMeta in frequencies) {
                    val freqValue = parseFrequencyData(freqMeta.data, termEntity.expression, termEntity.reading)
                    freqValues.add(freqValue)
                }

                // Join frequency values with commas
                spanGenerator.appendText(freqValues.joinToString(", "))

                // Add space after this dictionary's frequencies
                spanGenerator.appendText(" ")
            }

            // Add newline after frequencies section
            spanGenerator.newline()
        }

        // Render definitions using StructuredContentRenderer
        // Parse glossary JSON array from TermEntity
        // For each glossary item, determine type (text string, object with type "text", type "image", type "structured-content")
        // Delegate structured content rendering to StructuredContentRenderer
        // Apply dictionary-specific CSS from styles.css
        // Separate definition entries with paragraph boundaries
        //
        // Reference: yomitan/ext/js/display/display-generator.js::_appendMultiple
        // Reference: yomitan/ext/js/display/display-generator.js::_createTermDefinitionItem
        //
        // The glossary field is a JSON array that can contain:
        // 1. Plain strings: "Sunday"
        // 2. Objects with type "text": {"type": "text", "text": "Sunday"}
        // 3. Objects with type "image": {"type": "image", "path": "image.jpg", ...}
        // 4. Objects with type "structured-content": {"type": "structured-content", "content": {...}}
        //
        // For structured-content objects, the content field contains the full structured content tree
        // that should be delegated to StructuredContentRenderer for processing.

        // Parse glossary JSON array
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val glossaryArray = try {
            json.parseToJsonElement(termEntity.glossary) as? kotlinx.serialization.json.JsonArray
        } catch (e: Exception) {
            // If JSON parsing fails, log error and skip definitions
            Log.e(TAG, "Error parsing glossary JSON: ${e.message}", e)
            null
        }

        // Render each glossary item
        if (glossaryArray != null) {
            val flatStrings = isFlatStringArray(glossaryArray)
            for ((index, glossaryItem) in glossaryArray.withIndex()) {
                // Add separator between definitions (not before the first one if there's already content).
                // For flat string arrays the newline is emitted inside the item (as part of the BlockSpan),
                // so no inter-item newline is needed here.
                if (index > 0 && !flatStrings) {
                    spanGenerator.newline()
                }

                when (glossaryItem) {
                    // Case 1: Plain string
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        if (glossaryItem.isString) {
                            if (flatStrings) {
                                // Render as a bullet list item (blockType=1, no listMarker)
                                val itemStart = spanGenerator.getCurrentIndex()
                                spanGenerator.appendText(glossaryItem.content)
                                spanGenerator.newline()
                                val itemEnd = spanGenerator.getCurrentIndex()
                                spanGenerator.addBlockSpan(
                                    startIndex = itemStart,
                                    endIndex = itemEnd,
                                    blockType = 1,
                                    backgroundColor = 0,
                                    listMarker = null,
                                    indentLevel = 0
                                )
                            } else {
                                spanGenerator.appendText(glossaryItem.content)
                            }
                        }
                    }

                    // Case 2: Object with type field
                    is kotlinx.serialization.json.JsonObject -> {
                        val typeField = glossaryItem["type"]?.let {
                            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                        }

                        when (typeField) {
                            "text" -> {
                                // Extract text field and append
                                val textField = glossaryItem["text"]?.let {
                                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                }
                                if (textField != null) {
                                    spanGenerator.appendText(textField)
                                }
                            }

                            "image" -> {
                                // Extract image path and create text placeholder
                                val pathField = glossaryItem["path"]?.let {
                                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                }
                                if (pathField != null) {
                                    // Render as text placeholder since SDK doesn't support inline images
                                    spanGenerator.appendText("[Image: $pathField]")
                                }
                            }

                            "structured-content" -> {
                                // Delegate to StructuredContentRenderer for processing
                                val contentField = glossaryItem["content"]
                                if (contentField != null) {
                                    // Render structured content using the renderer
                                    // The dictionary name is needed for media loading (though we're using text placeholders)
                                    structuredContentRenderer.render(
                                        content = contentField,
                                        dictionary = termEntity.dictionary,
                                        language = null // Auto-detect language from content
                                    )
                                }
                            }

                            else -> {
                                // Unknown type - log warning and skip
                                Log.w(TAG, "Unknown glossary type: $typeField")
                            }
                        }
                    }

                    // Other JSON types (JsonArray, JsonNull) - skip
                    else -> {
                        // Ignore unsupported types
                    }
                }
            }

            // Add final newline after all definitions
            if (!spanGenerator.endsWithNewline()) {
                spanGenerator.newline()
            }
        }

        val body = spanGenerator.build()

        return DictionaryEntry(
            headword = headword,
            pronunciation = pronunciation,
            headwordSpans = buildKanjiHeadwordSpans(headword),
            body = body
        )
    }

    private fun renderGrouped(
        termEntities: List<TermEntity>,
        tagMetaList: List<TagMetaEntity>,
        termMetaList: List<TermMetaEntity> = emptyList(),
        dictionaryEntity: DictionaryEntity? = null
    ): DictionaryEntry {
        val primaryTerm = termEntities.first()
        val headword = primaryTerm.expression
        val reading = primaryTerm.reading

        // Furigana RubySpan distribution
        val pronunciation = if (reading != headword) {
            val segments = JapaneseUtil.distributeFurigana(headword, reading)
            val rubySpans = mutableListOf<io.github.dokuendev.dokuenreader.dictionary.RubySpan>()
            var currentIndex = 0
            for (segment in segments) {
                val segmentLength = segment.text.length
                val segmentEnd = currentIndex + segmentLength
                if (segment.reading != null) {
                    rubySpans.add(
                        io.github.dokuendev.dokuenreader.dictionary.RubySpan(
                            startIndex = currentIndex,
                            endIndex = segmentEnd,
                            rubyText = segment.reading
                        )
                    )
                }
                currentIndex = segmentEnd
            }
            rubySpans.toTypedArray()
        } else {
            emptyArray()
        }

        // Render term tags (union across all terms in group)
        val termTagNames = termEntities.flatMap { term ->
            term.termTags?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()

        val tagMap = tagMetaList.associateBy { it.name }

        TagRenderer.appendTagBadges(termTagNames, tagMap, spanGenerator)

        // Render the dictionary name badge once at the top
        val dictHoverText = TagRenderer.buildDictionaryHoverText(dictionaryEntity)
        TagRenderer.appendTagBadge(
            primaryTerm.dictionary,
            TagRenderer.getCategoryColor("dictionary"),
            dictHoverText,
            spanGenerator
        )
        spanGenerator.newline()

        // Frequencies section
        val frequencyEntries = termMetaList.filter { it.mode == "freq" }
        if (frequencyEntries.isNotEmpty()) {
            val frequenciesByDict = frequencyEntries.groupBy { it.dictionary }
            var freqIdx = 0
            for ((dictionary, frequencies) in frequenciesByDict) {
                if (spanGenerator.getCurrentIndex() > 0) {
                    spanGenerator.appendText(" ")
                }
                TagRenderer.appendFrequencyBadge(dictionary, spanGenerator)
                spanGenerator.appendText(": ")

                val freqValues = frequencies.map { freqMeta ->
                    parseFrequencyData(freqMeta.data, primaryTerm.expression, primaryTerm.reading)
                }
                spanGenerator.appendText(freqValues.joinToString(", "))
                if (freqIdx < frequenciesByDict.size - 1) {
                    spanGenerator.appendText(" ")
                }
                freqIdx++
            }
            spanGenerator.newline()
        }

        // Definitions loop for each term entity in the group
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for ((idx, termEntity) in termEntities.withIndex()) {
            if (idx > 0) {
                // Separator between group definitions
                if (!spanGenerator.endsWithNewline()) {
                    spanGenerator.newline()
                }
            }

            // Render definition-level tags (e.g. "1", "n", etc.) + dictionary badge
            val definitionTagNames = termEntity.definitionTags
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            // Exclude dictionary name badge from definition tags as it is rendered once at the top
            val allDefTags = definitionTagNames

            for ((index, tagName) in allDefTags.withIndex()) {
                val isDict = (tagName == termEntity.dictionary)
                val tagMeta = if (isDict) null else tagMap[tagName]
                val category = if (isDict) "dictionary" else (tagMeta?.category ?: "")
                val hover = if (isDict) dictHoverText else tagMeta?.notes?.takeIf { it.isNotBlank() }
                TagRenderer.appendTagBadge(tagName, TagRenderer.getCategoryColor(category), hover, spanGenerator)
                // Add space between badges, but not after the last one
                if (index < allDefTags.size - 1) {
                    spanGenerator.appendText(" ")
                }
            }
            spanGenerator.newline()

            // Render glossary of this term entity
            val glossaryArray = try {
                json.parseToJsonElement(termEntity.glossary) as? kotlinx.serialization.json.JsonArray
            } catch (_: Exception) {
                null
            }

            if (glossaryArray != null) {
                val flatStrings = isFlatStringArray(glossaryArray)
                for ((gIdx, glossaryItem) in glossaryArray.withIndex()) {
                    // For flat string arrays the newline is emitted inside the item (as part of the
                    // BlockSpan), so no inter-item newline is needed here.
                    if (gIdx > 0 && !flatStrings) {
                        spanGenerator.newline()
                    }
                    when (glossaryItem) {
                        is kotlinx.serialization.json.JsonPrimitive -> {
                            if (glossaryItem.isString) {
                                if (flatStrings) {
                                    // Render as a bullet list item (blockType=1, no listMarker)
                                    val itemStart = spanGenerator.getCurrentIndex()
                                    spanGenerator.appendText(glossaryItem.content)
                                    spanGenerator.newline()
                                    val itemEnd = spanGenerator.getCurrentIndex()
                                    spanGenerator.addBlockSpan(
                                        startIndex = itemStart,
                                        endIndex = itemEnd,
                                        blockType = 1,
                                        backgroundColor = 0,
                                        listMarker = null,
                                        indentLevel = 0
                                    )
                                } else {
                                    spanGenerator.appendText(glossaryItem.content)
                                }
                            }
                        }

                        is kotlinx.serialization.json.JsonObject -> {
                            val typeField = glossaryItem["type"]?.let {
                                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                            }
                            when (typeField) {
                                "text" -> {
                                    val textField = glossaryItem["text"]?.let {
                                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                    }
                                    if (textField != null) spanGenerator.appendText(textField)
                                }

                                "image" -> {
                                    val pathField = glossaryItem["path"]?.let {
                                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                    }
                                    if (pathField != null) spanGenerator.appendText("[Image: $pathField]")
                                }

                                "structured-content" -> {
                                    val contentField = glossaryItem["content"]
                                    if (contentField != null) {
                                        structuredContentRenderer.render(
                                            content = contentField,
                                            dictionary = termEntity.dictionary,
                                            language = null
                                        )
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }

        if (!spanGenerator.endsWithNewline()) {
            spanGenerator.newline()
        }

        val body = spanGenerator.build()
        return DictionaryEntry(
            headword = headword,
            pronunciation = pronunciation,
            headwordSpans = buildKanjiHeadwordSpans(headword),
            body = body
        )
    }

    /**
     * Port of display-generator.js::_appendFurigana + _appendKanjiLinks + _createKanjiLink.
     *
     * Builds an array of HeadwordSpan objects, one per kanji character in the headword,
     * to be set on DictionaryEntry.headwordSpans. Each span covers a single kanji character
     * and carries a "lookup:?kanji=<char>" link so the host app navigates to the kanji
     * dictionary entry when the user taps that character.
     * Non-kanji characters (kana, punctuation, Latin, etc.) are not spanned.
     *
     * The indices in each HeadwordSpan are positions in the headword string itself,
     * not in the body text. This is the correct field: DictionaryEntry.headwordSpans
     * is specifically designed for link annotations on the headword.
     * DictionaryEntry.pronunciation (RubySpan[]) handles furigana separately and is
     * already populated by the caller.
     *
     * Link format: "lookup:?kanji=<char>"
     *   - The "lookup:" prefix is stripped by the host, which passes the remainder
     *     verbatim to onLookup() as contextText.
     *   - "?kanji=<char>" is then routed by lookupInternalLink() -> lookupKanjiDirect(),
     *     bypassing the term path regardless of whether a term entry also exists.
     *
     * Returns null (not an empty array) when no kanji characters are present, so the
     * host app can cheaply skip headword-span rendering for kana-only entries.
     *
     * Reference: yomitan/ext/js/display/display-generator.js::_createTermHeadword
     * Reference: yomitan/ext/js/display/display-generator.js::_appendKanjiLinks
     * Reference: yomitan/ext/js/display/display-generator.js::_createKanjiLink
     * Reference: yomitan/ext/js/language/ja/japanese.js::isCodePointKanji
     *
     * @param headword The expression string (e.g. "日本語")
     * @return Array of HeadwordSpan (one per kanji character), or null if none.
     */
    private fun buildKanjiHeadwordSpans(headword: String): Array<io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan>? {
        val spans = mutableListOf<io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan>()
        var charIndex = 0
        for (char in headword) {
            if (JapaneseUtil.isCodePointKanji(char.code)) {
                spans.add(
                    io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan(
                        startIndex = charIndex,
                        endIndex = charIndex + 1,
                        linkUrl = "lookup:?kanji=$char"
                    )
                )
            }
            charIndex++
        }
        return if (spans.isEmpty()) null else spans.toTypedArray()
    }

    /**
     * Parse frequency data from TermMetaEntity.data JSON field.
     *
     * The frequency data can be in several formats:
     * - Simple number: 1
     * - Simple string: "four"
     * - Object with value: {"value": 6}
     * - Object with value and displayValue: {"value": 7, "displayValue": "seven"}
     * - Object with reading and frequency: {"reading": "だ", "frequency": 8}
     * - Object with reading and frequency string: {"reading": "だ", "frequency": "fourteen"}
     * - Object with reading and frequency object: {"reading": "だ", "frequency": {"value": 26, "displayValue": "twenty-seven"}}
     *
     * Reference: yomitan/test/data/dictionaries/valid-dictionary1/term_meta_bank_1.json
     * Reference: yomitan/ext/js/display/display-generator.js::_createTermFrequency
     *
     * @param jsonData The JSON string from TermMetaEntity.data
     * @param expression The term expression for disambiguation
     * @param reading The term reading for disambiguation
     * @return Formatted frequency string
     */
    private fun parseFrequencyData(jsonData: String, expression: String, reading: String): String {
        return try {
            // Parse JSON
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            when (val element = json.parseToJsonElement(jsonData)) {
                // Simple number
                is kotlinx.serialization.json.JsonPrimitive if element.isString.not() -> {
                    element.content
                }
                // Simple string
                is kotlinx.serialization.json.JsonPrimitive if element.isString -> {
                    element.content
                }
                // Object
                is kotlinx.serialization.json.JsonObject -> {
                    // Check if it has a "reading" field (reading-specific frequency)
                    val freqReading = element["reading"]?.let {
                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                    }

                    // Get the frequency value
                    val freqValue = when {
                        // frequency can be number, string, or object
                        element.containsKey("frequency") -> {
                            when (val freq = element["frequency"]!!) {
                                is kotlinx.serialization.json.JsonPrimitive if freq.isString.not() -> freq.content
                                is kotlinx.serialization.json.JsonPrimitive if freq.isString -> freq.content
                                is kotlinx.serialization.json.JsonObject -> {
                                    // frequency is an object with value/displayValue
                                    val displayValue = freq["displayValue"]?.let {
                                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                    }
                                    val value = freq["value"]?.let {
                                        if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                                    }
                                    displayValue ?: value ?: "?"
                                }

                                else -> "?"
                            }
                        }
                        // value field (simple format)
                        element.containsKey("value") -> {
                            val displayValue = element["displayValue"]?.let {
                                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                            }
                            val value = element["value"]?.let {
                                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                            }
                            displayValue ?: value ?: "?"
                        }

                        else -> "?"
                    }

                    // Format output with reading disambiguation if needed
                    if (freqReading != null && freqReading != reading) {
                        "$expression[$freqReading]: $freqValue"
                    } else {
                        freqValue
                    }
                }

                else -> "?"
            }
        } catch (_: Exception) {
            // If JSON parsing fails, return placeholder
            "?"
        }
    }

    /**
     * Returns true if every element in [array] is a plain JSON string.
     *
     * A "flat string array" glossary (as used by name dictionaries like JMnedict)
     * contains only primitive string values and no structured-content objects.
     * These items are rendered as bullet-list entries (BlockSpan blockType=1) rather
     * than plain text blocks.
     */
    private fun isFlatStringArray(array: kotlinx.serialization.json.JsonArray): Boolean {
        return array.isNotEmpty() && array.all {
            it is kotlinx.serialization.json.JsonPrimitive && it.isString
        }
    }
}
