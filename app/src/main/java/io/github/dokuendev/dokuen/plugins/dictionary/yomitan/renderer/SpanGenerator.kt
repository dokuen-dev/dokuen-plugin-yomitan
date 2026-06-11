package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText

/**
 * SpanGenerator tracks text accumulation and creates span annotations.
 *
 * This class is responsible for:
 * - Maintaining a StringBuilder for accumulated text
 * - Tracking the current character index
 * - Creating StyledSpan, BlockSpan, and RubySpan with correct ranges
 * - Validating span indices
 */
class SpanGenerator {
    /**
     * StringBuilder for accumulating text content.
     */
    private val textBuilder = StringBuilder()

    /**
     * Mutable list for block-level formatting spans.
     */
    private val blockSpans = mutableListOf<BlockSpan>()

    /**
     * Mutable list for inline styling spans.
     */
    private val styledSpans = mutableListOf<StyledSpan>()

    /**
     * Mutable list for ruby text annotations.
     */
    private val rubySpans = mutableListOf<RubySpan>()

    /**
     * Appends text to the accumulated content.
     *
     * This method adds text to the internal StringBuilder and updates the
     * current index accordingly. It preserves all characters including
     * whitespace.
     *
     * @param text The text content to append
     */
    fun appendText(text: String) {
        textBuilder.append(text)
    }

    /**
     * Returns the current character index in the accumulated text.
     *
     * This is the length of the accumulated text so far, which represents
     * the position where the next character will be inserted. Indices are
     * 0-based.
     *
     * @return The current character index (equals text length so far)
     */
    fun getCurrentIndex(): Int {
        return textBuilder.length
    }

    /**
     * Appends a newline character to the accumulated text.
     *
     * This method adds a '\n' character to create paragraph boundaries.
     * It's typically called when closing block-level elements like div, p,
     * or when encountering br tags.
     */
    fun newline() {
        textBuilder.append('\n')
    }

    /**
     * Checks if the accumulated text ends with a newline character.
     */
    fun endsWithNewline(): Boolean {
        if (textBuilder.isEmpty()) return false
        return textBuilder[textBuilder.length - 1] == '\n'
    }

    /**
     * Returns the index of the last non-newline character (plus one).
     * This is useful for capturing an endIndex that excludes trailing newlines.
     */
    fun getCurrentIndexWithoutTrailingNewlines(): Int {
        var idx = textBuilder.length
        while (idx > 0 && (textBuilder[idx - 1] == '\n' || textBuilder[idx - 1] == '\r')) {
            idx--
        }
        return idx
    }

    /**
     * Creates a StyledSpan with the specified range and style.
     *
     * This method creates an inline style annotation for a text range.
     * The range is specified by startIndex (inclusive) and endIndex (exclusive).
     * Multiple StyledSpans can overlap the same text range to apply
     * multiple styles.
     *
     * @param startIndex The starting character index (inclusive), 0-based
     * @param endIndex The ending character index (exclusive), 0-based
     * @param style The InlineStyle to apply to this range
     */
    fun addStyledSpan(startIndex: Int, endIndex: Int, style: InlineStyle) {
        styledSpans.add(StyledSpan(startIndex, endIndex, style))
    }

    /**
     * Creates a BlockSpan for block-level formatting.
     *
     * This method creates a block-level annotation for a text range,
     * typically representing paragraphs, list items, or code blocks.
     * BlockSpans control paragraph-level formatting including background
     * colors, list markers, and indentation.
     *
     * @param startIndex The starting character index (inclusive), 0-based
     * @param endIndex The ending character index (exclusive), 0-based
     * @param blockType The block type: 0=normal, 1=list-item, 2=code-block
     * @param backgroundColor The background color as ARGB integer
     * @param listMarker The list marker string (e.g., "•", "①"), null for non-list blocks
     * @param indentLevel The indentation level from nesting depth
     */
    fun addBlockSpan(
        startIndex: Int,
        endIndex: Int,
        blockType: Int,
        backgroundColor: Int,
        listMarker: String?,
        indentLevel: Int
    ) {
        blockSpans.add(
            BlockSpan(
                startIndex,
                endIndex,
                blockType,
                indentLevel,
                listMarker,
                backgroundColor
            )
        )
    }

    /**
     * Creates a RubySpan for ruby text (furigana) annotations.
     *
     * This method creates a ruby text annotation that maps base text
     * characters to their pronunciation guide. Ruby text is typically
     * displayed above (horizontal text) or to the right (vertical text)
     * of the base characters.
     *
     * @param startIndex The starting character index (inclusive), 0-based
     * @param endIndex The ending character index (exclusive), 0-based
     * @param rubyText The pronunciation text (hiragana or katakana)
     */
    fun addRubySpan(startIndex: Int, endIndex: Int, rubyText: String) {
        rubySpans.add(RubySpan(startIndex, endIndex, rubyText))
    }

    /**
     * Builds the final StyledText with validation.
     *
     * This method constructs the final StyledText object containing the
     * accumulated text and all span annotations. It validates that all
     * span indices are within the text length bounds.
     *
     * Validation Rules:
     * - All span startIndex >= 0
     * - All span endIndex <= text.length
     * - All span startIndex <= endIndex
     *
     * @return StyledText containing the accumulated text and spans
     * @throws IllegalStateException if any span has invalid indices
     */
    fun build(): StyledText {
        val text = textBuilder.toString()
        val textLength = text.length

        // Validate all spans
        val errors = mutableListOf<String>()

        // Validate BlockSpans
        blockSpans.forEachIndexed { index, span ->
            if (span.startIndex < 0) {
                errors.add("BlockSpan[$index] has negative startIndex: ${span.startIndex}")
            }
            if (span.endIndex > textLength) {
                errors.add("BlockSpan[$index] endIndex ${span.endIndex} exceeds text length $textLength")
            }
            if (span.startIndex > span.endIndex) {
                errors.add("BlockSpan[$index] has startIndex ${span.startIndex} > endIndex ${span.endIndex}")
            }
        }

        // Validate StyledSpans
        styledSpans.forEachIndexed { index, span ->
            if (span.startIndex < 0) {
                errors.add("StyledSpan[$index] has negative startIndex: ${span.startIndex}")
            }
            if (span.endIndex > textLength) {
                errors.add("StyledSpan[$index] endIndex ${span.endIndex} exceeds text length $textLength")
            }
            if (span.startIndex > span.endIndex) {
                errors.add("StyledSpan[$index] has startIndex ${span.startIndex} > endIndex ${span.endIndex}")
            }
        }

        // Validate RubySpans
        rubySpans.forEachIndexed { index, span ->
            if (span.startIndex < 0) {
                errors.add("RubySpan[$index] has negative startIndex: ${span.startIndex}")
            }
            if (span.endIndex > textLength) {
                errors.add("RubySpan[$index] endIndex ${span.endIndex} exceeds text length $textLength")
            }
            if (span.startIndex > span.endIndex) {
                errors.add("RubySpan[$index] has startIndex ${span.startIndex} > endIndex ${span.endIndex}")
            }
        }

        // If there are validation errors, throw exception with diagnostic information
        if (errors.isNotEmpty()) {
            val errorMessage = buildString {
                appendLine("Invalid span indices detected:")
                appendLine("Text length: $textLength")
                appendLine("Errors:")
                errors.forEach { error ->
                    appendLine("  - $error")
                }
                appendLine("Total spans: ${blockSpans.size} block, ${styledSpans.size} styled, ${rubySpans.size} ruby")
            }
            throw IllegalStateException(errorMessage)
        }

        // Convert lists to arrays (null for empty arrays as per SDK requirements)
        // Sort spans by startIndex so they appear in document order.
        val sortedBlockSpans = blockSpans.sortedBy { it.startIndex }
        val sortedStyledSpans = styledSpans.sortedBy { it.startIndex }
        val sortedRubySpans = rubySpans.sortedBy { it.startIndex }

        val blockSpanArray = if (sortedBlockSpans.isEmpty()) null else sortedBlockSpans.toTypedArray()
        val styledSpanArray = if (sortedStyledSpans.isEmpty()) null else sortedStyledSpans.toTypedArray()
        val rubySpanArray = if (sortedRubySpans.isEmpty()) null else sortedRubySpans.toTypedArray()

        return StyledText(
            text = text,
            blockSpans = blockSpanArray,
            styledSpans = styledSpanArray,
            rubySpans = rubySpanArray
        )
    }
}
