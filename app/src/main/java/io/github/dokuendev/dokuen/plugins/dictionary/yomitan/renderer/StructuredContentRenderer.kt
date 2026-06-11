package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import android.util.Log
import io.github.dokuendev.dokuenreader.dictionary.BLOCK_TYPE_TABLE
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generic processor for Yomitan structured content.
 * This is a direct port of structured-content-generator.js from the Yomitan browser extension.
 *
 * StructuredContentRenderer is responsible for:
 * - Recursively traversing JSON content (strings, arrays, objects with tag/content/style/data/lang)
 * - Handling all supported tags: div, span, ul, ol, li, ruby, rt, rp, a, img, table, thead, tbody, tr, th, td, br, details, summary
 * - Preserving ALL data attributes without filtering
 * - Applying inline styles from style field
 * - Resolving CSS rules via CssProcessor
 * - Delegating to SpanGenerator for text/span creation
 *
 * This is a GENERIC processor - it does NOT know about dictionary-specific semantic concepts
 * like "sense-group", "glossary", "xref", etc. Those are arbitrary labels defined by
 * dictionary CSS via data attributes.
 *
 * Reference: yomitan/ext/js/display/structured-content-generator.js
 */
class StructuredContentRenderer(
    private val cssProcessor: CssProcessor,
    private val spanGenerator: SpanGenerator
) {
    /**
     * List of CSS rules to apply during rendering.
     * This should be set before calling render().
     */
    var cssRules: List<CssRule> = emptyList()

    /**
     * Current nesting depth for indent level tracking.
     * Incremented when entering block-level elements, decremented when exiting.
     */
    private var currentIndentLevel = 0

    /**
     * Current list marker inherited from parent ul/ol element.
     * When rendering li elements, they should use this marker.
     */
    private var currentListMarker: String? = null
    private var pendingMarginSpace = false
    private val parentStack = mutableListOf<ElementContext>()

    /**
     * Render structured content into the SpanGenerator.
     *
     * This is the main entry point for processing Yomitan structured content.
     * Content can be:
     * - String: Plain text to append
     * - JsonArray: Array of content items to process recursively
     * - JsonObject: Element with "tag" field to render
     * - JsonNull: Skip (no-op)
     *
     * Reference: _appendStructuredContent in structured-content-generator.js
     *
     * @param content The JSON content to render (String | Array | Object with tag/content/style/data/lang)
     * @param dictionary The dictionary name (used for media loading, currently unused in flat text rendering)
     * @param language The current language context (null = auto-detect, propagates to children)
     */
    fun render(
        content: JsonElement,
        dictionary: String,
        language: String? = null,
        parentStyle: ResolvedStyle? = null
    ) {
        when (content) {
            // String content → append text
            is JsonPrimitive -> {
                if (content.isString) {
                    val text = content.content
                    if (text.isNotEmpty()) {
                        appendText(text)
                        // Note: Language auto-detection from text is not implemented in this port
                        // The original uses getLanguageFromText() but we're focusing on core rendering
                    }
                }
            }

            // Array content → recurse for each element
            is JsonArray -> {
                for (item in content) {
                    render(item, dictionary, language, parentStyle)
                }
            }

            // Object content → render as element
            is JsonObject -> {
                renderElement(content, dictionary, language, parentStyle)
            }
        }
    }

    /**
     * Render a structured content element (JSON object with "tag" field).
     *
     * This method dispatches to specific handlers based on the tag type.
     * All elements can have optional fields:
     * - tag: Element tag name (required)
     * - content: Child content (string, array, or nested objects)
     * - data: Arbitrary key-value metadata for CSS selector matching
     * - style: Inline CSS style declarations
     * - lang: Language annotation
     *
     * Reference: _createStructuredContentGenericElement in structured-content-generator.js
     *
     * Supported tags:
     * - br: Line break
     * - ruby, rt, rp: Ruby annotations (furigana)
     * - table, thead, tbody, tfoot, tr, th, td: Tables
     * - div, span, ol, ul, li, details, summary: Generic containers
     * - img: Images (rendered as text placeholders)
     * - a: Links (internal lookup or external URLs)
     *
     * @param element The JSON object representing the element
     * @param dictionary The dictionary name
     * @param language The current language context
     */
    private fun renderElement(
        element: JsonObject,
        dictionary: String,
        language: String?,
        parentStyle: ResolvedStyle? = null
    ) {
        // Extract tag (required field)
        val tag = element["tag"]?.jsonPrimitive?.contentOrNull ?: return

        if (tag in BLOCK_LEVEL_TAGS || tag == "br") {
            pendingMarginSpace = false
        }

        // Dispatch based on tag type
        when (tag) {
            "br" -> {
                // Line break → insert newline
                spanGenerator.newline()
            }

            "ruby" -> {
                // Ruby annotations - extract base text and rt (ruby text) for furigana
                renderRubyElement(element, dictionary, language, parentStyle)
            }

            "rt", "rp" -> {
                // rt and rp elements are handled within ruby elements
                // If encountered standalone, render as simple text
                renderSimpleElement(
                    tag,
                    element,
                    dictionary,
                    language,
                    hasStyle = false,
                    parentStyle = parentStyle
                )
            }

            "table" -> {
                // Tables - render using markdown-like pipe-separated format
                renderTableElement(element)
            }

            "thead", "tbody", "tfoot", "tr" -> {
                // Table structure elements - handled by renderTableElement
                // If encountered standalone (not within a table), render as simple element
                renderSimpleElement(
                    tag,
                    element,
                    dictionary,
                    language,
                    hasStyle = false,
                    parentStyle = parentStyle
                )
            }

            "th", "td" -> {
                // Table cells - handled by renderTableElement
                // If encountered standalone (not within a table), render as simple element
                renderSimpleElement(
                    tag,
                    element,
                    dictionary,
                    language,
                    hasStyle = false,
                    parentStyle = parentStyle
                )
            }

            "div", "span", "ol", "ul", "li", "details", "summary" -> {
                // Generic container elements
                renderSimpleElement(
                    tag,
                    element,
                    dictionary,
                    language,
                    hasStyle = true,
                    parentStyle = parentStyle
                )
            }

            "img" -> {
                // Images - render as text placeholder since SDK doesn't support inline images
                renderImageElement(element)
            }

            "a" -> {
                // Links - convert internal dictionary links to lookup URLs
                renderLinkElement(element, dictionary, language, parentStyle)
            }

            else -> {
                // Unsupported tag - skip with warning
                Log.w(TAG, "Unsupported structured content tag: $tag")
            }
        }
    }

    /**
     * Render a simple element (generic container).
     *
     * Simple elements are containers that:
     * - Can have child content
     * - Can have data attributes for CSS matching
     * - Can have inline styles
     * - Can have language annotations
     *
     * This is the workhorse method for most structured content rendering.
     *
     * This method:
     * - Resolves CSS styles based on tag, data attributes, classes, and inline styles
     * - Creates StyledSpan objects for styled text ranges
     * - Creates BlockSpan objects for block-level elements with styling
     *
     * Reference: _createStructuredContentElement in structured-content-generator.js
     *
     * @param tag The element tag name
     * @param element The JSON object representing the element
     * @param dictionary The dictionary name
     * @param language The current language context
     * @param hasStyle Whether this element can have style/title/open attributes
     */
    private fun renderSimpleElement(
        tag: String,
        element: JsonObject,
        dictionary: String,
        language: String?,
        hasStyle: Boolean,
        parentStyle: ResolvedStyle? = null
    ) {
        val isBlockLevel = tag in BLOCK_LEVEL_TAGS
        if (!isBlockLevel) {
            if (pendingMarginSpace) {
                spanGenerator.appendText(" ")
            }
            pendingMarginSpace = false
        } else {
            pendingMarginSpace = false
        }

        // Track start position for potential span creation
        val startIndex = spanGenerator.getCurrentIndex()

        // Extract data attributes (arbitrary key-value metadata)
        val dataAttributes = extractDataAttributes(element)

        // Extract and update language if specified
        var effectiveLanguage = language
        val langValue = element["lang"]?.jsonPrimitive?.contentOrNull
        if (langValue != null) {
            effectiveLanguage = langValue
        }

        // Extract inline styles if supported
        val inlineStyles = if (hasStyle) {
            extractInlineStyles(element)
        } else {
            emptyMap()
        }

        // Extract title attribute for hover text
        val titleValue = if (hasStyle) {
            element["title"]?.jsonPrimitive?.contentOrNull
        } else {
            null
        }

        // Extract classes (if present in the element)
        // Note: In Yomitan's JSON structured content, classes aren't typically used,
        // but we support them for completeness
        val classes = extractClasses(element)

        // Resolve CSS styles based on tag, dataAttributes, classes, inlineStyles
        // This matches CSS rules from dictionary styles and Yomitan internal styles
        val resolvedStyle = cssProcessor.resolveStyles(
            tag = tag,
            dataAttributes = dataAttributes,
            classes = classes,
            inlineStyle = inlineStyles,
            parentStyles = parentStyle,
            cssRules = cssRules,
            parentStack = parentStack
        )

        // For ul/ol elements, save the list marker for child li elements
        val savedListMarker = currentListMarker
        if (tag == "ul" || tag == "ol") {
            // If this ul/ol has a list-style-type, use it for child li elements
            // Otherwise, default to standard HTML/CSS markers: "•" for ul, "decimal" for ol.
            currentListMarker = resolvedStyle.listMarker ?: if (tag == "ul") "•" else "decimal"
        }

        // For li elements, use the inherited list marker from parent ul/ol
        // unless the li itself has an explicit list-style-type
        val effectiveListMarker = if (tag == "li") {
            // Li's own marker takes precedence, otherwise use parent's
            val marker = resolvedStyle.listMarker ?: currentListMarker
            if (marker == "none" || marker == "decimal") null else marker
        } else if (tag == "ul" || tag == "ol") {
            // Don't apply marker to ul/ol itself, only to li children
            null
        } else {
            val marker = resolvedStyle.listMarker
            if (marker == "none" || marker == "decimal") null else marker
        }

        // Determine block type: li elements are list-items (blockType=1) ONLY when they have a marker.
        // If the li has no marker (e.g., parent ul has list-style-type: none), use blockType=0
        // because the host app will add a default "•" bullet for blockType=1 with null listMarker.
        // Block-level elements with a background color are code-blocks (blockType=2).
        val effectiveBlockType = if (tag == "li") {
            val marker = resolvedStyle.listMarker ?: currentListMarker
            if (marker != "none") 1 else 0
        } else if (resolvedStyle.backgroundColor != 0) {
            2 // code-block if it has a background color
        } else {
            resolvedStyle.blockType
        }

        // Increment indent level for block-level elements
        if (isBlockLevel) {
            if (spanGenerator.getCurrentIndex() > 0 && !spanGenerator.endsWithNewline()) {
                spanGenerator.newline()
            }
            currentIndentLevel++
        }

        val isTagBadge = !isBlockLevel && (
                classes.contains("tag") ||
                        classes.contains("badge") ||
                        dataAttributes["data-sc-class"] == "tag" ||
                        dataAttributes["data-class"] == "tag" ||
                        dataAttributes["data-sc-class"] == "badge" ||
                        dataAttributes["data-class"] == "badge"
                )

        if (isTagBadge) {
            spanGenerator.appendText(" ")
        }

        val beforeStyle = cssProcessor.resolvePseudoElementStyles(
            pseudoType = "before",
            tag = tag,
            dataAttributes = dataAttributes,
            classes = classes,
            parentStyles = resolvedStyle,
            cssRules = cssRules,
            parentStack = parentStack
        )
        beforeStyle?.content?.let { appendText(it) }

        // Render child content
        val content = element["content"]
        if (content != null) {
            parentStack.add(ElementContext(tag, dataAttributes, classes))
            render(content, dictionary, effectiveLanguage, resolvedStyle)
            parentStack.removeAt(parentStack.size - 1)
        }

        val afterStyle = cssProcessor.resolvePseudoElementStyles(
            pseudoType = "after",
            tag = tag,
            dataAttributes = dataAttributes,
            classes = classes,
            parentStyles = resolvedStyle,
            cssRules = cssRules,
            parentStack = parentStack
        )
        afterStyle?.content?.let { appendText(it) }

        if (isTagBadge) {
            spanGenerator.appendText(" ")
        }
        if (!isBlockLevel && isPositiveMargin(resolvedStyle.marginRight)) {
            pendingMarginSpace = true
        }

        // Track end position before trailing newline so block spans do NOT include it
        // We use getCurrentIndexWithoutTrailingNewlines to ignore newlines added by inner block elements
        val endIndex =
            if (isBlockLevel) spanGenerator.getCurrentIndexWithoutTrailingNewlines() else spanGenerator.getCurrentIndex()

        // For block-level elements, add trailing newline after capturing endIndex
        if (isBlockLevel) {
            if (!spanGenerator.endsWithNewline()) {
                spanGenerator.newline()
            }
        }

        // Create spans based on resolved styles
        // Create spans if there's text content OR if it's a block-level element with styles
        val shouldCreateSpans = endIndex > startIndex ||
                (isBlockLevel && (resolvedStyle.backgroundColor != 0 || resolvedStyle.blockType != 0))

        if (shouldCreateSpans) {
            // Create StyledSpan for inline styling (if any non-default styles are present)
            if (hasNonDefaultInlineStyles(resolvedStyle) || titleValue != null) {
                // For inline elements (non-block-level), background-color should become textBackgroundColor
                // For block elements, it stays as backgroundColor in the BlockSpan
                val textBgColor = if (!isBlockLevel && resolvedStyle.backgroundColor != 0) {
                    resolvedStyle.backgroundColor
                } else {
                    resolvedStyle.textBackgroundColor
                }

                val inlineStyle = InlineStyle(
                    bold = resolvedStyle.bold,
                    italic = resolvedStyle.italic,
                    fontSize = resolvedStyle.fontSize,
                    foregroundColor = resolvedStyle.foregroundColor,
                    textBackgroundColor = textBgColor,
                    hoverText = titleValue ?: resolvedStyle.hoverText,
                    linkUrl = null // Links are handled separately
                )

                spanGenerator.addStyledSpan(startIndex, endIndex, inlineStyle)
            }

            // Create BlockSpan for block-level elements
            if (isBlockLevel) {
                // Only create a BlockSpan if there are block-level properties to apply
                if (effectiveBlockType != 0 || resolvedStyle.backgroundColor != 0 || effectiveListMarker != null) {
                    spanGenerator.addBlockSpan(
                        startIndex = startIndex,
                        endIndex = endIndex,
                        blockType = effectiveBlockType,
                        backgroundColor = resolvedStyle.backgroundColor,
                        listMarker = effectiveListMarker,
                        indentLevel = 0 // Always 0 for Yomitan rendering to match expected result
                    )
                }
            }
        }

        // Decrement indent level when exiting block-level elements
        if (isBlockLevel) {
            currentIndentLevel--
        }

        // Restore list marker context when exiting ul/ol
        if (tag == "ul" || tag == "ol") {
            currentListMarker = savedListMarker
        }
    }

    /**
     * Check if the resolved style has any non-default inline styling properties.
     * This is used to avoid creating unnecessary StyledSpan objects for unstyled text.
     *
     * @param style The resolved style to check
     * @return true if any inline style property differs from defaults
     */
    private fun hasNonDefaultInlineStyles(style: ResolvedStyle): Boolean {
        return style.bold ||
                style.italic ||
                style.fontSize != 1.0f ||
                style.foregroundColor != 0 ||
                style.textBackgroundColor != 0 ||
                style.backgroundColor != 0 ||  // Check background color too (for inline elements)
                style.hoverText != null
    }

    /**
     * Extract CSS classes from an element.
     * In Yomitan's structured content, classes are typically not used directly in JSON,
     * but we support them for completeness if present in a "class" field.
     *
     * @param element The JSON object representing the element
     * @return Set of CSS class names
     */
    private fun extractClasses(element: JsonObject): Set<String> {
        val classField = element["class"]?.jsonPrimitive?.contentOrNull ?: return emptySet()

        // Split by whitespace to handle multiple classes
        return classField.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Render a table element using pipe-delimited format.
     *
     * Tables are rendered as pipe-delimited text using the format:
     * | cell1 | cell2 | cell3 |
     *
     * This method:
     * 1. Parses table structure (thead, tbody, tfoot, tr, th, td)
     * 2. Extracts cell content and handles colSpan/rowSpan
     * 3. Builds pipe-delimited text format
     * 4. Creates a BlockSpan with BLOCK_TYPE_TABLE
     *
     * Table structure in JSON:
     * ```json
     * {
     *   "tag": "table",
     *   "content": [
     *     {"tag": "thead", "content": [{"tag": "tr", "content": [...]}]},
     *     {"tag": "tbody", "content": [{"tag": "tr", "content": [...]}]},
     *     {"tag": "tfoot", "content": [{"tag": "tr", "content": [...]}"]}
     *   ]
     * }
     * ```
     *
     * Cell elements (th/td) can have:
     * - colSpan: Number of columns to span (default 1)
     * - rowSpan: Number of rows to span (default 1)
     * - content: Nested structured content
     *
     * The SDK will automatically:
     * - Style the first row as a header
     * - Trim leading/trailing whitespace from cells
     * - Parse line breaks within cells using <br> or <br/> tags
     *
     * Reference: SDK documentation at sdk/dictionary/README.md (lines 690-800)
     * Reference: _createStructuredContentTableElement in structured-content-generator.js
     *
     * @param element The JSON object representing the table element
     */
    private fun renderTableElement(
        element: JsonObject
    ) {
        if (spanGenerator.getCurrentIndex() > 0 && !spanGenerator.endsWithNewline()) {
            spanGenerator.newline()
        }
        // Track start position for block span
        val startIndex = spanGenerator.getCurrentIndex()

        // Parse table structure into rows and cells
        val tableData = parseTableStructure(element, parentStack.toMutableList())

        if (tableData.isEmpty()) {
            // Empty table - just skip it
            return
        }

        // Render table as pipe-delimited format
        renderTableAsPipeDelimited(tableData)

        val endIndex = spanGenerator.getCurrentIndex()

        // Create block span with BLOCK_TYPE_TABLE (value = 3)
        if (endIndex > startIndex) {
            spanGenerator.addBlockSpan(
                startIndex = startIndex,
                endIndex = endIndex,
                blockType = BLOCK_TYPE_TABLE, // 3 - tells SDK to parse as pipe-delimited table
                backgroundColor = 0,
                listMarker = null,
                indentLevel = 0 // Always 0 to match expected result format
            )
        }

        // Add newline after table
        spanGenerator.newline()
    }

    /**
     * Data structure representing a table cell.
     */
    private data class TableCell(
        val content: String,
        val colSpan: Int,
        val rowSpan: Int,
        val isHeader: Boolean
    )

    /**
     * Parse table structure from JSON into a 2D array of cells.
     *
     * This method extracts rows and cells from the table element,
     * handling thead, tbody, tfoot, tr, th, and td elements.
     *
     * @param element The JSON object representing the table element
     * @return List of rows, where each row is a list of cells
     */
    private fun parseTableStructure(
        element: JsonObject,
        localParentStack: MutableList<ElementContext>
    ): List<List<TableCell>> {
        val tableDataAttributes = extractDataAttributes(element)
        val tableClasses = extractClasses(element)
        localParentStack.add(ElementContext("table", tableDataAttributes, tableClasses))

        val rows = mutableListOf<List<TableCell>>()
        val content = element["content"] ?: run {
            localParentStack.removeAt(localParentStack.size - 1)
            return rows
        }

        // Process table sections (thead, tbody, tfoot) or rows directly
        when (content) {
            is JsonArray -> {
                for (item in content) {
                    if (item is JsonObject) {
                        when (val tag = item["tag"]?.jsonPrimitive?.contentOrNull) {
                            "thead", "tbody", "tfoot" -> {
                                // Process rows within section
                                val sectionDataAttributes = extractDataAttributes(item)
                                val sectionClasses = extractClasses(item)
                                localParentStack.add(ElementContext(tag, sectionDataAttributes, sectionClasses))
                                rows.addAll(parseTableRows(item, localParentStack))
                                localParentStack.removeAt(localParentStack.size - 1)
                            }

                            "tr" -> {
                                // Direct row (no section wrapper)
                                parseTableRow(item, localParentStack)?.let { rows.add(it) }
                            }
                        }
                    }
                }
            }

            is JsonObject -> {
                // Single section or row
                when (val tag = content["tag"]?.jsonPrimitive?.contentOrNull) {
                    "thead", "tbody", "tfoot" -> {
                        val sectionDataAttributes = extractDataAttributes(content)
                        val sectionClasses = extractClasses(content)
                        localParentStack.add(ElementContext(tag, sectionDataAttributes, sectionClasses))
                        rows.addAll(parseTableRows(content, localParentStack))
                        localParentStack.removeAt(localParentStack.size - 1)
                    }

                    "tr" -> {
                        parseTableRow(content, localParentStack)?.let { rows.add(it) }
                    }
                }
            }

            is JsonPrimitive -> {
                // Not a valid table structure
            }
        }

        localParentStack.removeAt(localParentStack.size - 1)
        return rows
    }

    /**
     * Parse rows from a table section (thead, tbody, tfoot).
     *
     * @param section The JSON object representing the table section
     * @return List of rows
     */
    private fun parseTableRows(
        section: JsonObject,
        localParentStack: MutableList<ElementContext>
    ): List<List<TableCell>> {
        val rows = mutableListOf<List<TableCell>>()
        val content = section["content"] ?: return rows

        when (content) {
            is JsonArray -> {
                for (item in content) {
                    if (item is JsonObject) {
                        val tag = item["tag"]?.jsonPrimitive?.contentOrNull
                        if (tag == "tr") {
                            parseTableRow(item, localParentStack)?.let { rows.add(it) }
                        }
                    }
                }
            }

            is JsonObject -> {
                val tag = content["tag"]?.jsonPrimitive?.contentOrNull
                if (tag == "tr") {
                    parseTableRow(content, localParentStack)?.let { rows.add(it) }
                }
            }

            is JsonPrimitive -> {
                // Not a valid table section
            }
        }

        return rows
    }

    /**
     * Parse a single table row.
     *
     * @param row The JSON object representing the row
     * @return List of cells in the row, or null if invalid
     */
    private fun parseTableRow(
        row: JsonObject,
        localParentStack: MutableList<ElementContext>
    ): List<TableCell>? {
        val cells = mutableListOf<TableCell>()
        val content = row["content"] ?: return null

        val trDataAttributes = extractDataAttributes(row)
        val trClasses = extractClasses(row)
        localParentStack.add(ElementContext("tr", trDataAttributes, trClasses))

        when (content) {
            is JsonArray -> {
                for (item in content) {
                    if (item is JsonObject) {
                        val tag = item["tag"]?.jsonPrimitive?.contentOrNull
                        if (tag == "th" || tag == "td") {
                            cells.add(parseTableCell(item, tag == "th", localParentStack))
                        }
                    }
                }
            }

            is JsonObject -> {
                val tag = content["tag"]?.jsonPrimitive?.contentOrNull
                if (tag == "th" || tag == "td") {
                    cells.add(parseTableCell(content, tag == "th", localParentStack))
                }
            }

            is JsonPrimitive -> {
                // Not a valid row
            }
        }

        localParentStack.removeAt(localParentStack.size - 1)
        return cells.ifEmpty { null }
    }

    /**
     * Parse a single table cell.
     *
     * @param cell The JSON object representing the cell
     * @param isHeader Whether this is a header cell (th)
     * @return TableCell object
     */
    private fun parseTableCell(
        cell: JsonObject,
        isHeader: Boolean,
        localParentStack: MutableList<ElementContext>
    ): TableCell {
        // Extract colSpan and rowSpan attributes
        val colSpan = cell["colSpan"]?.jsonPrimitive?.intOrNull ?: 1
        val rowSpan = cell["rowSpan"]?.jsonPrimitive?.intOrNull ?: 1

        val cellDataAttributes = extractDataAttributes(cell)
        val cellClasses = extractClasses(cell)
        val cellTag = if (isHeader) "th" else "td"
        localParentStack.add(ElementContext(cellTag, cellDataAttributes, cellClasses))

        // Extract cell content as plain text
        val content = cell["content"]
        val cellText = if (content != null) {
            extractTextWithPseudoElements(content, localParentStack)
        } else {
            ""
        }

        localParentStack.removeAt(localParentStack.size - 1)

        return TableCell(
            content = cellText,
            colSpan = colSpan.coerceAtLeast(1), // Ensure at least 1
            rowSpan = rowSpan.coerceAtLeast(1), // Ensure at least 1
            isHeader = isHeader
        )
    }

    /**
     * Render table as pipe-delimited format.
     *
     * This method converts the parsed table data into pipe-delimited text
     * following the format: | cell1 | cell2 | cell3 |
     *
     * Each row is a line of text with cells separated by pipe characters.
     * The SDK will:
     * - Automatically style the first row as a header
     * - Trim leading/trailing whitespace from cells
     * - Handle line breaks within cells using <br> or <br/> tags
     *
     * colSpan handling:
     * - For cells with colSpan > 1, we repeat the cell content across multiple columns
     * - OR we could use empty cells for the spanned columns
     * - The SDK documentation doesn't specify exact behavior, so we'll duplicate content
     *
     * rowSpan handling:
     * - This is more complex in pipe-delimited format
     * - We'll need to track which cells are "virtual" (part of a rowspan from above)
     * - For now, we'll duplicate the content in rowspan cells (simple approach)
     *
     * @param tableData The parsed table data
     */
    private fun renderTableAsPipeDelimited(
        tableData: List<List<TableCell>>
    ) {
        if (tableData.isEmpty()) {
            return
        }

        // Calculate the maximum number of columns
        val maxColumns = tableData.maxOfOrNull { row ->
            row.sumOf { it.colSpan }
        } ?: 0

        // Track which cells are occupied by rowspan from previous rows
        // Key: (rowIndex, colIndex) -> content to display
        val rowspanCells = mutableMapOf<Pair<Int, Int>, String>()

        // Render each row
        for ((rowIndex, row) in tableData.withIndex()) {
            val rowBuilder = StringBuilder()
            rowBuilder.append('|')

            var colIndex = 0
            var cellIndex = 0

            while (colIndex < maxColumns) {
                // Check if this column is occupied by a rowspan from a previous row
                val rowspanContent = rowspanCells[Pair(rowIndex, colIndex)]
                if (rowspanContent != null) {
                    // This cell is part of a rowspan from above - use the saved content
                    rowBuilder.append(' ')
                    rowBuilder.append(rowspanContent)
                    rowBuilder.append(" |")
                    colIndex++
                    continue
                }

                // Get the cell from the current row (if available)
                val cell = if (cellIndex < row.size) {
                    row[cellIndex]
                } else {
                    // Row is shorter than maxColumns - use empty cell
                    TableCell("", 1, 1, false)
                }
                cellIndex++

                // Add the cell content
                rowBuilder.append(' ')
                rowBuilder.append(cell.content)
                rowBuilder.append(" |")

                // Handle colSpan: if colSpan > 1, repeat the content (or use empty cells)
                // For now, we'll use empty cells for the spanned columns
                if (cell.colSpan > 1) {
                    for (i in 1 until cell.colSpan) {
                        if (colIndex + i < maxColumns) {
                            rowBuilder.append("  |") // Empty cell
                        }
                    }
                }

                // Handle rowSpan: if rowSpan > 1, save this content for future rows
                if (cell.rowSpan > 1) {
                    for (i in 1 until cell.rowSpan) {
                        if (rowIndex + i < tableData.size) {
                            rowspanCells[Pair(rowIndex + i, colIndex)] = cell.content
                        }
                    }
                }

                colIndex += cell.colSpan
            }

            // Append the row text
            appendText(rowBuilder.toString())

            // Add newline after each row except the last
            if (rowIndex < tableData.size - 1) {
                spanGenerator.newline()
            }
        }
    }

    /**
     * Render an image element as a text placeholder.
     *
     * Since the Dokuen SDK doesn't support inline images, we create a text placeholder
     * with the format "[Image: path]". Optional metadata like dimensions and alt text
     * can be included in the hoverText for reference.
     *
     * Reference: createDefinitionImage in structured-content-generator.js
     * The original creates an HTML anchor with image loading, but we only create
     * a text placeholder with metadata.
     *
     * Image elements can have the following fields:
     * - path (required): Path to the image file
     * - width: Image width (default 100)
     * - height: Image height (default 100)
     * - preferredWidth: Preferred display width
     * - preferredHeight: Preferred display height
     * - title: Title/tooltip text
     * - alt: Alternative text description
     * - pixelated: Whether to use pixelated rendering
     * - imageRendering: Rendering mode (pixelated, auto, etc.)
     * - appearance: Visual appearance setting
     * - background: Whether to show background
     * - collapsed: Whether the image starts collapsed
     * - collapsible: Whether the image can be collapsed
     * - verticalAlign: Vertical alignment
     * - border: Border style
     * - borderRadius: Border radius
     * - sizeUnits: Unit for size values (em, px, etc.)
     *
     * @param element The JSON object representing the image element
     */
    private fun renderImageElement(
        element: JsonObject
    ) {
        // Extract path (required field)
        val path = element["path"]?.jsonPrimitive?.contentOrNull
        if (path == null) {
            Log.w(TAG, "Image element missing required 'path' field")
            return
        }

        if (pendingMarginSpace) {
            spanGenerator.appendText(" ")
        }
        pendingMarginSpace = false

        // Track start position for potential styled span with hover text
        val startIndex = spanGenerator.getCurrentIndex()

        // Create text placeholder with format "[Image: path]"
        val placeholderText = "[Image: $path]"
        appendText(placeholderText)

        val endIndex = spanGenerator.getCurrentIndex()

        // Extract optional metadata for hover text
        val width = element["width"]?.jsonPrimitive?.intOrNull
        val height = element["height"]?.jsonPrimitive?.intOrNull
        val preferredWidth = element["preferredWidth"]?.jsonPrimitive?.intOrNull
        val preferredHeight = element["preferredHeight"]?.jsonPrimitive?.intOrNull
        val title = element["title"]?.jsonPrimitive?.contentOrNull
        val alt = element["alt"]?.jsonPrimitive?.contentOrNull

        // Build hover text with metadata
        val hoverTextParts = mutableListOf<String>()

        // Add alt text if present
        if (!alt.isNullOrEmpty()) {
            hoverTextParts.add("Alt: $alt")
        }

        // Add dimensions if present
        // Use preferred dimensions if available, otherwise use width/height
        val displayWidth = preferredWidth ?: width
        val displayHeight = preferredHeight ?: height
        if (displayWidth != null && displayHeight != null) {
            hoverTextParts.add("Size: ${displayWidth}x${displayHeight}")
        } else if (displayWidth != null) {
            hoverTextParts.add("Width: $displayWidth")
        } else if (displayHeight != null) {
            hoverTextParts.add("Height: $displayHeight")
        }

        // Add title if present
        if (!title.isNullOrEmpty()) {
            hoverTextParts.add("Title: $title")
        }

        // Create styled span with hover text if we have any metadata
        if (hoverTextParts.isNotEmpty()) {
            val hoverText = hoverTextParts.joinToString("\n")
            val inlineStyle = InlineStyle(
                bold = false,
                italic = false,
                fontSize = 1.0f,
                foregroundColor = 0,
                textBackgroundColor = 0,
                hoverText = hoverText,
                linkUrl = null
            )
            spanGenerator.addStyledSpan(startIndex, endIndex, inlineStyle)
        }
    }

    /**
     * Render a link element (anchor tag).
     *
     * Link elements can be:
     * 1. Internal dictionary links: href starts with "?" → prepend "lookup:" and pass verbatim
     * 2. External links: href starts with "http://" or "https://" → preserve as-is
     *
     * The href attribute contains:
     * - For internal links: ?query={URL_ENCODED_TERM}&other_params... (or ?kanji=<char>, etc.)
     * - For external links: Full URL
     *
     * The entire "?..." string is preserved as-is and passed to onLookup as contextText.
     * URL decoding of individual parameter values is handled by parseQueryParams inside
     * lookupInternalLink, not here.
     *
     * Link elements can contain nested structured content (e.g., ruby annotations),
     * so we recursively render the content and apply the link to the resulting text range.
     *
     * Reference: _createLinkElement in structured-content-generator.js
     * The original creates an HTML anchor with href attribute. For our flat text model,
     * we extract the href and apply it as linkUrl in the InlineStyle.
     *
     * @param element The JSON object representing the link element
     * @param dictionary The dictionary name
     * @param language The current language context
     */
    private fun renderLinkElement(
        element: JsonObject,
        dictionary: String,
        language: String?,
        parentStyle: ResolvedStyle? = null
    ) {
        // Extract href attribute (required for links)
        val href = element["href"]?.jsonPrimitive?.contentOrNull
        if (href.isNullOrEmpty()) {
            // No href - render as simple text without link
            Log.w(TAG, "Link element missing 'href' attribute")
            renderSimpleElement(
                "a",
                element,
                dictionary,
                language,
                hasStyle = true,
                parentStyle = parentStyle
            )
            return
        }

        if (pendingMarginSpace) {
            spanGenerator.appendText(" ")
        }
        pendingMarginSpace = false

        // Track start position for the link span
        val startIndex = spanGenerator.getCurrentIndex()

        // Extract data attributes and inline styles for CSS resolution
        val dataAttributes = extractDataAttributes(element)
        val inlineStyles = extractInlineStyles(element)
        val classes = extractClasses(element)
        val titleValue = element["title"]?.jsonPrimitive?.contentOrNull

        // Update language if specified
        var effectiveLanguage = language
        val langValue = element["lang"]?.jsonPrimitive?.contentOrNull
        if (langValue != null) {
            effectiveLanguage = langValue
        }

        // Resolve CSS styles for the link element
        val resolvedStyle = cssProcessor.resolveStyles(
            tag = "a",
            dataAttributes = dataAttributes,
            classes = classes,
            inlineStyle = inlineStyles,
            parentStyles = parentStyle,
            cssRules = cssRules,
            parentStack = parentStack
        )

        // Render child content (which may contain ruby annotations or other nested elements)
        val content = element["content"]
        if (content != null) {
            parentStack.add(ElementContext("a", dataAttributes, classes))
            render(content, dictionary, effectiveLanguage, resolvedStyle)
            parentStack.removeAt(parentStack.size - 1)
        }

        val endIndex = spanGenerator.getCurrentIndex()

        // Convert href to linkUrl format
        val linkUrl = convertHrefToLinkUrl(href)

        // Create StyledSpan with link
        // Only create a span if there's text content
        if (endIndex > startIndex) {
            val inlineStyle = InlineStyle(
                bold = resolvedStyle.bold,
                italic = resolvedStyle.italic,
                fontSize = resolvedStyle.fontSize,
                foregroundColor = resolvedStyle.foregroundColor,
                textBackgroundColor = resolvedStyle.textBackgroundColor,
                hoverText = titleValue ?: resolvedStyle.hoverText,
                linkUrl = linkUrl
            )

            spanGenerator.addStyledSpan(startIndex, endIndex, inlineStyle)
        }

        if (isPositiveMargin(resolvedStyle.marginRight)) {
            pendingMarginSpace = true
        }
    }

    /**
     * Convert Yomitan href format to Dokuen linkUrl format.
     *
     * Conversion rules:
     * 1. Internal links starting with "?" → prepend "lookup:" and pass entire string verbatim
     * 2. External links starting with "http://" or "https://" → preserve as-is
     * 3. Other formats → preserve as-is (fallback)
     *
     * Internal link examples:
     *   Input:  "?query=%E6%97%A5%E6%9B%9C&wildcards=off&primary_reading=%E3%81%AB%E3%81%A1%E3%82%88%E3%81%86"
     *   Output: "lookup:?query=%E6%97%A5%E6%9B%9C&wildcards=off&primary_reading=%E3%81%AB%E3%81%A1%E3%82%88%E3%81%86"
     *
     *   Input:  "?kanji=日"
     *   Output: "lookup:?kanji=日"
     *
     * External link example:
     *   Input:  "https://www.example.com/page"
     *   Output: "https://www.example.com/page"
     *
     * URL decoding of individual parameter values is handled downstream by parseQueryParams
     * inside lookupInternalLink, not here.
     *
     * @param href The href attribute value from the link element
     * @return The converted linkUrl string for Dokuen SDK
     */
    private fun convertHrefToLinkUrl(href: String): String {
        // Internal dictionary links start with "?" (e.g. "?query=...", "?kanji=...").
        // onLookup receives everything after the "lookup:" prefix verbatim as contextText,
        // and lookupInternalLink() then parses the full query string.
        // We must therefore preserve the entire "?..." string so that all parameters
        // (query, primary_reading, wildcards, kanji, …) reach lookupInternalLink intact.
        //
        // Conversion rules:
        //   "?..."         → "lookup:?..."     (all internal links, all params preserved)
        //   "http(s)://…"  → unchanged         (external links)
        //   anything else  → unchanged         (fallback)
        if (href.startsWith("?")) {
            return "lookup:$href"
        }

        // Check if this is an external link (starts with http:// or https://)
        if (href.startsWith("http://") || href.startsWith("https://")) {
            // Preserve external links as-is
            return href
        }

        // Fallback: preserve other formats as-is
        // (This shouldn't normally happen in well-formed Yomitan data)
        return href
    }

    /**
     * Extract data attributes from an element.
     *
     * Data attributes are arbitrary key-value pairs in the "data" field
     * that CSS selectors can target (e.g., [data-content="sense-group"]).
     *
     * This method extracts ALL data attributes without filtering by key name.
     * Dictionary authors can use ANY data attribute names they want.
     *
     * Reference: _setElementDataset in structured-content-generator.js
     * Note: The original prefixes keys with "sc" for DOM dataset, but we prefix with "data-"
     * to match CSS attribute selectors like [data-content="value"]
     *
     * @param element The JSON object representing the element
     * @return Map of data attribute names to values (with "data-" prefix)
     */
    private fun extractDataAttributes(element: JsonObject): Map<String, String> {
        val dataObject = element["data"]?.jsonObject ?: return emptyMap()

        val dataAttributes = mutableMapOf<String, String>()
        for ((key, value) in dataObject) {
            // Convert JsonElement to string representation
            val stringValue = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                else -> value.toString()
            }

            // Normalize key to strip pre-existing "sc-" or "sc" prefix if present
            val baseKey = if (key.startsWith("sc-")) {
                key.substring(3)
            } else if (key.startsWith("sc") && key.length > 2 && key[2].isUpperCase()) {
                key.substring(2).replaceFirstChar { it.lowercase() }
            } else {
                key
            }

            val kebabKey = camelToKebab(baseKey)
            // Add data-sc-kebab-key (original Yomitan dataset behavior)
            dataAttributes["data-sc-$kebabKey"] = stringValue
            // Add data-kebab-key (simplified attribute behavior used by some tests/CSS)
            dataAttributes["data-$kebabKey"] = stringValue

            // Also add raw key versions just in case
            if (baseKey != kebabKey) {
                dataAttributes["data-sc-$baseKey"] = stringValue
                dataAttributes["data-$baseKey"] = stringValue
            }
        }

        return dataAttributes
    }

    /**
     * Extract inline styles from an element.
     *
     * Inline styles are specified in the "style" field as key-value pairs
     * with CSS property names and values.
     *
     * Reference: _setStructuredContentElementStyle in structured-content-generator.js
     *
     * Supported style properties (from the original):
     * - fontStyle, fontWeight, fontSize
     * - color, background, backgroundColor
     * - textDecorationLine, textDecorationStyle, textDecorationColor
     * - borderColor, borderStyle, borderRadius, borderWidth
     * - clipPath, verticalAlign, textAlign
     * - textEmphasis, textShadow
     * - margin, marginTop, marginLeft, marginRight, marginBottom
     * - padding, paddingTop, paddingLeft, paddingRight, paddingBottom
     * - wordBreak, whiteSpace, cursor, listStyleType
     *
     * @param element The JSON object representing the element
     * @return Map of CSS property names to values
     */
    private fun extractInlineStyles(element: JsonObject): Map<String, String> {
        val styleObject = element["style"]?.jsonObject ?: return emptyMap()

        val styles = mutableMapOf<String, String>()
        for ((key, value) in styleObject) {
            // Convert JsonElement to string representation
            val stringValue = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                is JsonArray -> value.joinToString(" ") { it.toString().trim('"') }
                else -> value.toString()
            }
            // Yomitan structured content uses camelCase style keys (e.g. "fontWeight", "fontSize")
            // but CssProcessor.buildResolvedStyle expects kebab-case (e.g. "font-weight", "font-size").
            // Convert here so inline styles are correctly resolved.
            styles[camelToKebab(key)] = stringValue
        }

        return styles
    }

    /**
     * Convert a camelCase string to kebab-case.
     * e.g. "fontWeight" -> "font-weight", "backgroundColor" -> "background-color"
     */
    private fun camelToKebab(camel: String): String {
        return camel.replace(Regex("([A-Z])")) { "-${it.value.lowercase()}" }
    }

    /**
     * Render a ruby annotation element.
     *
     * Ruby elements contain base text with rt (ruby text) child elements for pronunciation.
     * This method extracts the base text and ruby text, creates RubySpan objects to map
     * character indices to pronunciation annotations.
     *
     * Ruby structure in JSON:
     * ```json
     * {
     *   "tag": "ruby",
     *   "content": [
     *     "日",                              // Base text
     *     {"tag": "rt", "content": "にち"}   // Ruby text (pronunciation)
     *   ]
     * }
     * ```
     *
     * Output:
     * - Text: "日" (base text only)
     * - RubySpan: startIndex=0, endIndex=1, rubyText="にち"
     *
     * Reference: Ruby elements in structured-content-generator.js are rendered as HTML <ruby> tags,
     * which browsers natively display as furigana. For our flat text model, we extract the base
     * text and rt elements to create RubySpan annotations.
     *
     * @param element The JSON object representing the ruby element
     * @param dictionary The dictionary name
     * @param language The current language context
     */
    private fun renderRubyElement(
        element: JsonObject,
        dictionary: String,
        language: String?,
        parentStyle: ResolvedStyle? = null
    ) {
        val content = element["content"] ?: return

        if (pendingMarginSpace) {
            spanGenerator.appendText(" ")
        }
        pendingMarginSpace = false

        // Track start position for the ruby span
        val startIndex = spanGenerator.getCurrentIndex()

        // Extract base text and ruby text (rt) from content.
        // Content can be:
        // 1. Array with base text and rt element: ["日", {"tag": "rt", "content": "にち"}]
        // 2. Single rt element: {"tag": "rt", "content": "にち"} (less common)
        // 3. Just base text: "日" (ruby without rt)

        when (content) {
            is JsonArray -> {
                // Most common case: array with base text and rt element(s)
                var rubyText: String? = null

                for (item in content) {
                    when (item) {
                        is JsonPrimitive -> {
                            // Base text - append it to the output
                            if (item.isString) {
                                val text = item.content
                                if (text.isNotEmpty()) {
                                    appendText(text)
                                }
                            }
                        }

                        is JsonObject -> {
                            // Check if this is an rt or rp element
                            val tag = item["tag"]?.jsonPrimitive?.contentOrNull
                            when (tag) {
                                "rt" -> {
                                    // Extract ruby text (pronunciation)
                                    rubyText = extractTextContent(item, dictionary, language)
                                }

                                "rp" -> {
                                    // rp (ruby parenthesis) elements are used for fallback
                                    // rendering in browsers that don't support ruby.
                                    // We skip them since we're extracting the ruby text directly.
                                    // Example: <rp>(</rp><rt>にち</rt><rp>)</rp>
                                }

                                else -> {
                                    // Other nested elements - render recursively
                                    // (though this is uncommon in ruby elements)
                                    render(item, dictionary, language, parentStyle)
                                }
                            }
                        }

                        else -> {
                            // Handle JsonArray or JsonNull
                            render(item, dictionary, language, parentStyle)
                        }
                    }
                }

                // Create RubySpan if we found ruby text
                val endIndex = spanGenerator.getCurrentIndex()
                if (rubyText != null && endIndex > startIndex) {
                    spanGenerator.addRubySpan(startIndex, endIndex, rubyText)
                }
            }

            is JsonObject -> {
                // Less common: single rt element
                val tag = content["tag"]?.jsonPrimitive?.contentOrNull
                if (tag == "rt") {
                    // Ruby text without base text - just render as text
                    // This shouldn't normally happen in well-formed data
                    render(content, dictionary, language, parentStyle)
                } else {
                    // Some other element - render normally
                    render(content, dictionary, language, parentStyle)
                }
            }

            is JsonPrimitive -> {
                // Ruby with just base text, no rt element
                if (content.isString) {
                    val text = content.content
                    if (text.isNotEmpty()) {
                        appendText(text)
                    }
                }
            }
        }
    }

    /**
     * Extract text content from an element recursively.
     * This is used to extract ruby text (rt content) which may contain
     * nested elements or plain strings.
     *
     * @param element The JSON object to extract text from
     * @param dictionary The dictionary name
     * @param language The current language context
     * @return Extracted text content as a string
     */
    private fun extractTextContent(
        element: JsonObject,
        dictionary: String,
        language: String?
    ): String {
        val content = element["content"] ?: return ""
        return extractTextFromJsonElement(content, dictionary, language)
    }

    /**
     * Recursively extract plain text from a JSON element.
     * This flattens nested structures into a single text string.
     *
     * @param jsonElement The JSON element to extract text from
     * @param dictionary The dictionary name
     * @param language The current language context
     * @return Extracted text content as a string
     */
    private fun extractTextFromJsonElement(
        jsonElement: JsonElement,
        dictionary: String,
        language: String?
    ): String {
        return when (jsonElement) {
            is JsonPrimitive -> {
                if (jsonElement.isString) {
                    jsonElement.content
                } else {
                    ""
                }
            }

            is JsonArray -> {
                // Concatenate all text from array elements
                jsonElement.joinToString("") { item ->
                    extractTextFromJsonElement(item, dictionary, language)
                }
            }

            is JsonObject -> {
                // Extract content field recursively
                val content = jsonElement["content"]
                if (content != null) {
                    extractTextFromJsonElement(content, dictionary, language)
                } else {
                    ""
                }
            }
        }
    }

    private fun extractTextWithPseudoElements(
        jsonElement: JsonElement,
        localParentStack: MutableList<ElementContext>
    ): String {
        return when (jsonElement) {
            is JsonPrimitive -> {
                if (jsonElement.isString) {
                    jsonElement.content
                } else {
                    ""
                }
            }

            is JsonArray -> {
                jsonElement.joinToString("") { item ->
                    extractTextWithPseudoElements(item, localParentStack)
                }
            }

            is JsonObject -> {
                val tag = jsonElement["tag"]?.jsonPrimitive?.contentOrNull ?: return ""
                val dataAttributes = extractDataAttributes(jsonElement)
                val classes = extractClasses(jsonElement)

                val resolvedStyle = cssProcessor.resolveStyles(
                    tag = tag,
                    dataAttributes = dataAttributes,
                    classes = classes,
                    inlineStyle = extractInlineStyles(jsonElement),
                    parentStyles = null,
                    cssRules = cssRules,
                    parentStack = localParentStack
                )

                val beforeStyle = cssProcessor.resolvePseudoElementStyles(
                    pseudoType = "before",
                    tag = tag,
                    dataAttributes = dataAttributes,
                    classes = classes,
                    parentStyles = resolvedStyle,
                    cssRules = cssRules,
                    parentStack = localParentStack
                )
                val beforeText = beforeStyle?.content ?: ""

                val afterStyle = cssProcessor.resolvePseudoElementStyles(
                    pseudoType = "after",
                    tag = tag,
                    dataAttributes = dataAttributes,
                    classes = classes,
                    parentStyles = resolvedStyle,
                    cssRules = cssRules,
                    parentStack = localParentStack
                )
                val afterText = afterStyle?.content ?: ""

                val content = jsonElement["content"]
                val childText = if (content != null) {
                    localParentStack.add(ElementContext(tag, dataAttributes, classes))
                    val result = extractTextWithPseudoElements(content, localParentStack)
                    localParentStack.removeAt(localParentStack.size - 1)
                    result
                } else {
                    ""
                }

                beforeText + childText + afterText
            }
        }
    }

    private fun isPositiveMargin(margin: String?): Boolean {
        if (margin == null) return false
        val trimmed = margin.trim().lowercase()
        if (trimmed == "0" || trimmed == "0px" || trimmed == "0em" || trimmed == "0rem" || trimmed == "none") {
            return false
        }
        val numberPart = trimmed.takeWhile { it.isDigit() || it == '.' || it == '-' }
        val value = numberPart.toDoubleOrNull()
        return value != null && value > 0.0
    }

    private fun appendText(text: String) {
        if (text.isNotEmpty()) {
            if (pendingMarginSpace) {
                spanGenerator.appendText(" ")
            }
            pendingMarginSpace = false
            spanGenerator.appendText(text)
        }
    }

    companion object {
        private const val TAG = "StructuredContentRenderer"

        /**
         * Set of block-level HTML tags that should have newlines after their content.
         *
         * This matches the behavior of HTML block-level elements that create
         * paragraph boundaries.
         */
        private val BLOCK_LEVEL_TAGS = setOf(
            "div", "p",
            "ul", "ol", "li",
            "table", "thead", "tbody", "tfoot", "tr", "th", "td",
            "details", "summary"
        )
    }
}
