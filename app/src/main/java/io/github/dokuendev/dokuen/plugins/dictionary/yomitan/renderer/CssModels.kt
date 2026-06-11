package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

/**
 * Represents a CSS rule with a selector and property declarations.
 * This is used by the CSS processor to parse and apply styles.
 *
 * @param selector The CSS selector that determines which elements this rule applies to
 * @param declarations Map of CSS property names to values (e.g., "color" -> "#FF0000")
 */
data class CssRule(
    val selector: CssSelector,
    val declarations: Map<String, String>
)

/**
 * Represents a CSS selector that can match against elements.
 * Supports tag names, data attributes, and classes.
 *
 * Specificity calculation:
 * - Tag selector: specificity = 1
 * - Class selector: specificity = 10 per class
 * - Attribute selector: specificity = 10 per attribute
 *
 * @param tag Element tag name (e.g., "div", "span"), null matches any tag
 * @param dataAttributes Map of data attribute key-value pairs to match (e.g., "content" -> "sense-group")
 * @param classes Set of CSS class names to match
 * @param customSpecificity Optional override for specificity value
 */
data class ElementContext(
    val tag: String,
    val dataAttributes: Map<String, String> = emptyMap(),
    val classes: Set<String> = emptySet()
)

data class CssSelector(
    val tag: String? = null,
    val dataAttributes: Map<String, String> = emptyMap(),
    val classes: Set<String> = emptySet(),
    val customSpecificity: Int? = null,
    val parentSelector: CssSelector? = null,
    val pseudoElement: String? = null,
    val combinator: String? = null
) {
    /**
     * Calculate CSS specificity for this selector.
     * Higher specificity takes precedence when multiple rules match.
     */
    fun specificity(): Int {
        if (customSpecificity != null) {
            return customSpecificity
        }
        val tagSpecificity = if (tag != null) 1 else 0
        val pseudoSpecificity = if (pseudoElement != null) 1 else 0
        val classSpecificity = classes.size * 10
        val attributeSpecificity = dataAttributes.size * 10
        val parentSpecificity = parentSelector?.specificity() ?: 0
        return tagSpecificity + pseudoSpecificity + classSpecificity + attributeSpecificity + parentSpecificity
    }

    /**
     * Check if this selector matches the given element metadata.
     *
     * @param elementTag Element tag name
     * @param elementData Element data attributes
     * @param elementClasses Element CSS classes
     * @param parentStack Stack of parent elements context for ancestor matching
     * @return true if all constraints match
     */
    fun matches(
        elementTag: String,
        elementData: Map<String, String>,
        elementClasses: Set<String>,
        parentStack: List<ElementContext> = emptyList()
    ): Boolean {
        // Tag must match if specified
        if (tag != null && tag != elementTag) {
            return false
        }

        // All data attributes must match
        for ((key, value) in dataAttributes) {
            if (elementData[key] != value) {
                return false
            }
        }

        // All classes must be present
        if (!elementClasses.containsAll(classes)) {
            return false
        }

        // Parent selector must match an ancestor in parentStack
        if (parentSelector != null) {
            if (combinator == ">") {
                val parent = parentStack.lastOrNull() ?: return false
                val newStack = parentStack.dropLast(1)
                if (!parentSelector.matches(parent.tag, parent.dataAttributes, parent.classes, newStack)) {
                    return false
                }
            } else {
                var matched = false
                for (i in parentStack.indices.reversed()) {
                    val ancestor = parentStack[i]
                    val newStack = parentStack.subList(0, i)
                    if (parentSelector.matches(ancestor.tag, ancestor.dataAttributes, ancestor.classes, newStack)) {
                        matched = true
                        break
                    }
                }
                if (!matched) return false
            }
        }

        return true
    }
}

/**
 * Resolved style properties after applying CSS rules to an element.
 * These properties are then converted to Dokuen SDK InlineStyle and BlockSpan.
 *
 * All color values are stored as ARGB integers (0xAARRGGBB).
 * Font size is a scale factor where 1.0 = 100%.
 *
 * @param bold Whether text should be bold
 * @param italic Whether text should be italic
 * @param fontSize Scale factor for font size (1.0 = 100%, 1.5 = 150%, etc.)
 * @param foregroundColor Text color as ARGB integer
 * @param textBackgroundColor Text background color as ARGB integer
 * @param backgroundColor Block background color as ARGB integer
 * @param listMarker Custom list marker string (e.g., "①", "•", "1.")
 * @param blockType Block type: 0=normal, 1=list-item, 2=code-block
 * @param hoverText Tooltip text shown on hover
 */
data class ResolvedStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float = 1.0f,
    val foregroundColor: Int = 0,
    val textBackgroundColor: Int = 0,
    val backgroundColor: Int = 0,
    val listMarker: String? = null,
    val blockType: Int = 0,
    val hoverText: String? = null,
    val marginRight: String? = null,
    val content: String? = null
)
