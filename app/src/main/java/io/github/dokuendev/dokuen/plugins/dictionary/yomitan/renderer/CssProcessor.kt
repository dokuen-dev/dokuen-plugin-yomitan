package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import android.util.Log
import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import kotlin.math.roundToInt

/**
 * CSS processor for parsing CSS text and extracting rules.
 * This is a focused parser for the subset of CSS needed by the Yomitan rendering engine.
 *
 * Supported CSS features:
 * - Simple selectors: tag, [attribute="value"], .class
 * - Compound selectors: tag[attribute="value"].class
 * - Multiple selectors: selector1, selector2
 * - Declaration blocks with property-value pairs
 * - Basic error handling with line and column information
 *
 * NOT supported (intentionally simplified):
 * - Descendant/child combinators (space, >, +, ~)
 * - Pseudo-classes and pseudo-elements (:hover, ::before, etc.)
 * - Advanced CSS functions (color-mix, var, calc)
 * - Nested rules (& syntax)
 * - @-rules (media queries, keyframes, etc.)
 *
 * These limitations are acceptable because:
 * 1. We're converting to a flat text model, not rendering HTML/CSS
 * 2. Dictionary CSS primarily uses data attributes for matching
 * 3. Complex features would be stripped or approximated anyway
 */
class CssProcessor {

    companion object {
        private const val TAG = "CssProcessor"
    }

    /**
     * Named color map for common CSS color names.
     * Maps color names to their hex RGB values.
     */
    private val namedColors = mapOf(
        "black" to 0x000000,
        "white" to 0xFFFFFF,
        "red" to 0xFF0000,
        "green" to 0x008000,
        "blue" to 0x0000FF,
        "yellow" to 0xFFFF00,
        "cyan" to 0x00FFFF,
        "magenta" to 0xFF00FF,
        "gray" to 0x808080,
        "grey" to 0x808080,
        "silver" to 0xC0C0C0,
        "maroon" to 0x800000,
        "olive" to 0x808000,
        "lime" to 0x00FF00,
        "aqua" to 0x00FFFF,
        "teal" to 0x008080,
        "navy" to 0x000080,
        "fuchsia" to 0xFF00FF,
        "purple" to 0x800080,
        "orange" to 0xFFA500,
        "brown" to 0xA52A2A,
        "goldenrod" to 0xDAA520,
        "coral" to 0xFF7F50,
        "crimson" to 0xDC143C,
        "darkblue" to 0x00008B,
        "darkgreen" to 0x006400,
        "darkgray" to 0xA9A9A9,
        "darkgrey" to 0xA9A9A9,
        "darkred" to 0x8B0000,
        "dodgerblue" to 0x1E90FF,
        "gold" to 0xFFD700,
        "indianred" to 0xCD5C5C,
        "lightblue" to 0xADD8E6,
        "lightcoral" to 0xF08080,
        "lightgray" to 0xD3D3D3,
        "lightgrey" to 0xD3D3D3,
        "lightgreen" to 0x90EE90,
        "lightyellow" to 0xFFFFE0,
        "limegreen" to 0x32CD32,
        "orangered" to 0xFF4500,
        "pink" to 0xFFC0CB,
        "plum" to 0xDDA0DD,
        "royalblue" to 0x4169E1,
        "salmon" to 0xFA8072,
        "seagreen" to 0x2E8B57,
        "sienna" to 0xA0522D,
        "skyblue" to 0x87CEEB,
        "slategray" to 0x708090,
        "slategrey" to 0x708090,
        "steelblue" to 0x4682B4,
        "tan" to 0xD2B48C,
        "tomato" to 0xFF6347,
        "turquoise" to 0x40E0D0,
        "violet" to 0xEE82EE,
        "wheat" to 0xF5DEB3,
        "transparent" to 0x00000000
    )

    /**
     * Convert a CSS color value to an ARGB integer.
     *
     * Supported formats:
     * - Hex: #RRGGBB or #RGB (alpha defaults to 0xFF = opaque)
     * - RGB: rgb(r, g, b) (alpha defaults to 0xFF = opaque)
     * - RGBA: rgba(r, g, b, a) (alpha is preserved)
     * - Named colors: red, blue, green, etc.
     *
     * Unsupported formats return 0 (transparent) and log a warning.
     *
     * @param colorValue The CSS color value string
     * @return ARGB integer (0xAARRGGBB) or 0 for unsupported formats
     */
    fun convertColor(colorValue: String): Int {
        val trimmed = colorValue.trim().lowercase()

        // Try hex format: #RRGGBB or #RGB
        if (trimmed.startsWith("#")) {
            return parseHexColor(trimmed) ?: run {
                Log.w(TAG, "Invalid hex color format: $colorValue")
                0
            }
        }

        // Try rgb() or rgba() format
        if (trimmed.startsWith("rgb(") || trimmed.startsWith("rgba(")) {
            return parseRgbColor(trimmed) ?: run {
                Log.w(TAG, "Invalid rgb/rgba color format: $colorValue")
                0
            }
        }

        // Try named color
        val namedColorRgb = namedColors[trimmed]
        if (namedColorRgb != null) {
            // Named colors are opaque (alpha = 0xFF)
            return 0xFF000000.toInt() or namedColorRgb
        }

        // Try color-mix format
        if (trimmed.startsWith("color-mix(")) {
            return parseColorMix(trimmed)
        }

        // Try var() format
        if (trimmed.startsWith("var(")) {
            if (trimmed.contains("--text-color") || trimmed.contains("--fg")) {
                return 0xFF777777.toInt()
            }
            val commaIndex = trimmed.lastIndexOf(',')
            if (commaIndex != -1) {
                val fallback = trimmed.substring(commaIndex + 1).replace(")", "").trim()
                return convertColor(fallback)
            }
        }

        // Unsupported format
        Log.w(TAG, "Unsupported color format: $colorValue")
        return 0
    }

    private fun parseColorMix(colorMixValue: String): Int {
        // Extract content inside color-mix(...)
        val content = colorMixValue.substring("color-mix(".length, colorMixValue.length - 1)
        // Expecting: "in srgb, <color1> [weight1], <color2> [weight2]"
        val parts = splitTopLevelCommas(content)
        if (parts.size >= 3) {
            val comp1 = parseColorMixPart(parts[1])
            val comp2 = parseColorMixPart(parts[2])

            var w1 = comp1.second
            var w2 = comp2.second

            if (w1 == null && w2 == null) {
                w1 = 50.0
                w2 = 50.0
            } else if (w1 != null && w2 == null) {
                w2 = 100.0 - w1
            } else if (w1 == null && w2 != null) {
                w1 = 100.0 - w2
            }

            val total = w1!! + w2!!
            val w1Norm = if (total > 0) w1 / total else 0.5
            val w2Norm = if (total > 0) w2 / total else 0.5

            val a1 = (comp1.first shr 24) and 0xFF
            val r1 = (comp1.first shr 16) and 0xFF
            val g1 = (comp1.first shr 8) and 0xFF
            val b1 = comp1.first and 0xFF

            val a2 = (comp2.first shr 24) and 0xFF
            val r2 = (comp2.first shr 16) and 0xFF
            val g2 = (comp2.first shr 8) and 0xFF
            val b2 = comp2.first and 0xFF

            // If one of the colors is fully transparent, treat it as setting the alpha of the other color
            if (a2 == 0) {
                val mixedAlpha = (a1 * w1Norm).roundToInt().coerceIn(0, 255)
                return (mixedAlpha shl 24) or (comp1.first and 0x00FFFFFF)
            }
            if (a1 == 0) {
                val mixedAlpha = (a2 * w2Norm).roundToInt().coerceIn(0, 255)
                return (mixedAlpha shl 24) or (comp2.first and 0x00FFFFFF)
            }

            // General blend
            val mixedA = (a1 * w1Norm + a2 * w2Norm).roundToInt().coerceIn(0, 255)
            val mixedR = (r1 * w1Norm + r2 * w2Norm).roundToInt().coerceIn(0, 255)
            val mixedG = (g1 * w1Norm + g2 * w2Norm).roundToInt().coerceIn(0, 255)
            val mixedB = (b1 * w1Norm + b2 * w2Norm).roundToInt().coerceIn(0, 255)

            return (mixedA shl 24) or (mixedR shl 16) or (mixedG shl 8) or mixedB
        } else if (parts.size == 2) {
            val colorPart = parts[1].trim()
            val spaceIndex = colorPart.lastIndexOf(' ')
            if (spaceIndex != -1) {
                val colorStr = colorPart.substring(0, spaceIndex).trim()
                val percentStr = colorPart.substring(spaceIndex + 1).trim().removeSuffix("%")
                val percent = percentStr.toDoubleOrNull() ?: 5.0
                val alpha = (percent * 255.0 / 100.0).roundToInt().coerceIn(0, 255)

                val baseColor = resolveBaseColor(colorStr)
                return (alpha shl 24) or (baseColor and 0x00FFFFFF)
            }
        }
        return 0
    }

    private fun parseColorMixPart(part: String): Pair<Int, Double?> {
        val trimmed = part.trim()
        if (trimmed.lowercase() == "transparent") {
            return Pair(0, null)
        }

        val spaceIndex = trimmed.lastIndexOf(' ')
        if (spaceIndex != -1) {
            val weightStr = trimmed.substring(spaceIndex + 1).trim()
            if (weightStr.endsWith("%")) {
                val pct = weightStr.removeSuffix("%").toDoubleOrNull()
                if (pct != null) {
                    val colorStr = trimmed.substring(0, spaceIndex).trim()
                    val color = resolveBaseColor(colorStr)
                    return Pair(color, pct)
                }
            }
        }

        val color = resolveBaseColor(trimmed)
        return Pair(color, null)
    }

    /**
     * Split a string on commas, but only at the top level (not inside parentheses).
     * e.g. "in srgb, var(--fg, #333) 5%, transparent" -> ["in srgb", " var(--fg, #333) 5%", " transparent"]
     */
    private fun splitTopLevelCommas(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in input) {
            when (ch) {
                '(' -> {
                    depth++; current.append(ch)
                }

                ')' -> {
                    depth--; current.append(ch)
                }

                ',' if depth == 0 -> {
                    result.add(current.toString()); current.clear()
                }

                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun resolveBaseColor(colorStr: String): Int {
        val trimmed = colorStr.trim().lowercase()
        if (trimmed.contains("--text-color") || trimmed.contains("--fg")) {
            return 0xFF777777.toInt()
        }
        if (trimmed.startsWith("var(")) {
            val commaIndex = trimmed.lastIndexOf(',')
            if (commaIndex != -1) {
                val fallback = trimmed.substring(commaIndex + 1).replace(")", "").trim()
                return convertColor(fallback)
            }
        }
        return convertColor(colorStr)
    }

    /**
     * Parse hex color format: #RRGGBB or #RGB
     * Returns ARGB integer with 0xFF alpha (opaque) or null if invalid.
     */
    private fun parseHexColor(hex: String): Int? {
        if (!hex.startsWith("#")) return null

        val hexDigits = hex.substring(1)

        return when (hexDigits.length) {
            // #RGB format - expand to #RRGGBB
            3 -> {
                val r = hexDigits[0].toString().repeat(2)
                val g = hexDigits[1].toString().repeat(2)
                val b = hexDigits[2].toString().repeat(2)
                val rgb = (r + g + b).toIntOrNull(16) ?: return null
                0xFF000000.toInt() or rgb
            }
            // #RRGGBB format
            6 -> {
                val rgb = hexDigits.toIntOrNull(16) ?: return null
                0xFF000000.toInt() or rgb
            }
            // Invalid length
            else -> null
        }
    }

    /**
     * Parse rgb() or rgba() format.
     * Examples:
     * - rgb(255, 0, 0) -> 0xFFFF0000
     * - rgba(255, 0, 0, 0.5) -> 0x7FFF0000
     * - rgb(100%, 0%, 0%) -> 0xFFFF0000
     *
     * Returns ARGB integer or null if invalid.
     */
    private fun parseRgbColor(rgb: String): Int? {
        val isRgba = rgb.startsWith("rgba(")
        val prefix = if (isRgba) "rgba(" else "rgb("

        if (!rgb.endsWith(")")) return null

        // Extract the content between parentheses
        val content = rgb.substring(prefix.length, rgb.length - 1)
        val parts = content.split(",").map { it.trim() }

        // rgb() needs 3 parts, rgba() needs 4 parts
        val expectedParts = if (isRgba) 4 else 3
        if (parts.size != expectedParts) return null

        // Parse r, g, b (can be 0-255 or percentages)
        val r = parseColorComponent(parts[0]) ?: return null
        val g = parseColorComponent(parts[1]) ?: return null
        val b = parseColorComponent(parts[2]) ?: return null

        // Parse alpha (if rgba)
        val a = if (isRgba) {
            parseAlphaComponent(parts[3]) ?: return null
        } else {
            255 // Opaque for rgb()
        }

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Parse a color component value (r, g, or b).
     * Can be 0-255 or a percentage (0%-100%).
     * Returns value in range 0-255 or null if invalid.
     */
    private fun parseColorComponent(value: String): Int? {
        val trimmed = value.trim()

        // Percentage format: 50%, 100%, etc.
        if (trimmed.endsWith("%")) {
            val percentage = trimmed.substring(0, trimmed.length - 1).toDoubleOrNull() ?: return null
            val component = (percentage / 100.0 * 255.0).toInt()
            return component.coerceIn(0, 255)
        }

        // Integer format: 0-255
        val component = trimmed.toIntOrNull() ?: return null
        return component.coerceIn(0, 255)
    }

    /**
     * Parse an alpha component value.
     * Can be 0.0-1.0 or a percentage (0%-100%).
     * Returns value in range 0-255 or null if invalid.
     */
    private fun parseAlphaComponent(value: String): Int? {
        val trimmed = value.trim()

        // Percentage format: 50%, 100%, etc.
        if (trimmed.endsWith("%")) {
            val percentage = trimmed.substring(0, trimmed.length - 1).toDoubleOrNull() ?: return null
            val alpha = (percentage / 100.0 * 255.0).toInt()
            return alpha.coerceIn(0, 255)
        }

        // Float format: 0.0-1.0
        val alpha = trimmed.toDoubleOrNull() ?: return null
        val alphaInt = (alpha * 255.0).toInt()
        return alphaInt.coerceIn(0, 255)
    }

    /**
     * Convert a CSS font-size value to a scale factor.
     *
     * Supported formats:
     * - px values: divided by base size 14 (e.g., "14px" -> 1.0, "28px" -> 2.0)
     * - em values: used directly as scale factor (e.g., "1.5em" -> 1.5)
     * - rem values: used directly as scale factor (e.g., "1.2rem" -> 1.2)
     * - percentage values: divided by 100 (e.g., "150%" -> 1.5)
     *
     * Unsupported formats return 1.0 (base size) and log a warning.
     *
     * @param fontSizeValue The CSS font-size value string
     * @return Scale factor where 1.0 = 100% (14px base size)
     */
    fun convertFontSize(fontSizeValue: String): Float {
        val trimmed = fontSizeValue.trim().lowercase()

        // Handle px values: divide by base size 14
        if (trimmed.endsWith("px")) {
            val pxValue = trimmed.substring(0, trimmed.length - 2).toFloatOrNull()
            if (pxValue != null) {
                return pxValue / 14.0f
            } else {
                Log.w(TAG, "Invalid px font-size format: $fontSizeValue")
                return 1.0f
            }
        }

        // Handle rem values: use directly as scale factor (check before em!)
        if (trimmed.endsWith("rem")) {
            val remValue = trimmed.substring(0, trimmed.length - 3).toFloatOrNull()
            if (remValue != null) {
                return remValue
            } else {
                Log.w(TAG, "Invalid rem font-size format: $fontSizeValue")
                return 1.0f
            }
        }

        // Handle em values: use directly as scale factor
        if (trimmed.endsWith("em")) {
            val emValue = trimmed.substring(0, trimmed.length - 2).toFloatOrNull()
            if (emValue != null) {
                return emValue
            } else {
                Log.w(TAG, "Invalid em font-size format: $fontSizeValue")
                return 1.0f
            }
        }

        // Handle percentage values: divide by 100
        if (trimmed.endsWith("%")) {
            val percentValue = trimmed.substring(0, trimmed.length - 1).toFloatOrNull()
            if (percentValue != null) {
                return percentValue / 100.0f
            } else {
                Log.w(TAG, "Invalid percentage font-size format: $fontSizeValue")
                return 1.0f
            }
        }

        // Unsupported format
        Log.w(TAG, "Unsupported font-size format: $fontSizeValue")
        return 1.0f
    }

    /**
     * Convert a CSS font-weight value to a boolean bold flag.
     *
     * Font-weight values ≥700 are considered bold.
     * Named values "bold" and "bolder" map to true.
     * Numeric values < 700 and named values "normal", "lighter" map to false.
     *
     * @param fontWeightValue The CSS font-weight value string
     * @return true if bold, false otherwise
     */
    fun convertFontWeight(fontWeightValue: String): Boolean {
        val trimmed = fontWeightValue.trim().lowercase()

        // Handle named values
        when (trimmed) {
            "bold", "bolder" -> return true
            "normal", "lighter" -> return false
        }

        // Handle numeric values
        val numericValue = trimmed.toIntOrNull()
        if (numericValue != null) {
            return numericValue >= 700
        }

        // Unsupported or invalid format, default to false
        Log.w(TAG, "Unsupported font-weight format: $fontWeightValue, defaulting to false")
        return false
    }

    /**
     * Convert a CSS font-style value to a boolean italic flag.
     *
     * Font-style values "italic" and "oblique" map to true.
     * Value "normal" maps to false.
     *
     * @param fontStyleValue The CSS font-style value string
     * @return true if italic, false otherwise
     */
    fun convertFontStyle(fontStyleValue: String): Boolean {
        val trimmed = fontStyleValue.trim().lowercase()

        return when (trimmed) {
            "italic", "oblique" -> true
            "normal" -> false
            else -> {
                Log.w(TAG, "Unsupported font-style format: $fontStyleValue, defaulting to false")
                false
            }
        }
    }

    /**
     * Extract a custom list marker from CSS list-style-type property.
     *
     * Supported formats:
     * - Quoted strings: "①" or '①' -> "①"
     * - Unicode escapes: "\2460" -> "①" (circled digit one)
     * - Named values: disc, circle, square, decimal -> standard markers
     *
     * @param listStyleTypeValue The CSS list-style-type value string
     * @return The marker string, or null if none/unsupported
     */
    fun extractListMarker(listStyleTypeValue: String): String? {
        val trimmed = listStyleTypeValue.trim()

        // Handle quoted strings: "①" or '①'
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))
        ) {
            // Extract the content between quotes
            val quoted = trimmed.substring(1, trimmed.length - 1)
            // Process any Unicode escapes within the quoted string
            return processUnicodeEscapes(quoted)
        }

        // Handle named values
        return when (trimmed.lowercase()) {
            "disc" -> "•"
            "circle" -> "◦"
            "square" -> "▪"
            "decimal" -> "decimal"
            "none" -> "none"
            else -> {
                // Check if it's a bare Unicode escape (unlikely but possible)
                if (trimmed.startsWith("\\")) {
                    processUnicodeEscapes(trimmed)
                } else {
                    Log.w(TAG, "Unsupported list-style-type format: $listStyleTypeValue")
                    null
                }
            }
        }
    }

    /**
     * Process Unicode escape sequences in a string.
     * Converts sequences like \2460 to their character equivalents.
     *
     * Supported formats:
     * - \XXXX (4 hex digits, e.g., \2460)
     * - \uXXXX (4 hex digits with 'u' prefix, e.g., \u2460)
     * - \u{XXXXX} (1-6 hex digits in braces, e.g., \u{1F600})
     *
     * @param text The text containing potential Unicode escapes
     * @return The text with escapes converted to characters
     */
    private fun processUnicodeEscapes(text: String): String {
        if (!text.contains('\\')) {
            return text
        }

        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length) {
                when {
                    // \u{XXXXX} format (1-6 hex digits in braces)
                    text[i + 1] == 'u' && i + 2 < text.length && text[i + 2] == '{' -> {
                        val closeIndex = text.indexOf('}', i + 3)
                        if (closeIndex != -1) {
                            val hexDigits = text.substring(i + 3, closeIndex)
                            val codePoint = hexDigits.toIntOrNull(16)
                            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                                result.append(String(Character.toChars(codePoint)))
                                i = closeIndex + 1
                                continue
                            }
                        }
                        // Invalid format, keep as-is
                        result.append(text[i])
                        i++
                    }
                    // \uXXXX format (4 hex digits with 'u' prefix)
                    text[i + 1] == 'u' && i + 5 < text.length -> {
                        val hexDigits = text.substring(i + 2, i + 6)
                        val codePoint = hexDigits.toIntOrNull(16)
                        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                            result.append(String(Character.toChars(codePoint)))
                            i += 6
                            continue
                        }
                        // Invalid format, keep as-is
                        result.append(text[i])
                        i++
                    }
                    // \XXXX format (4 hex digits without 'u' prefix)
                    i + 4 < text.length && text.substring(i + 1, i + 5)
                        .all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> {
                        val hexDigits = text.substring(i + 1, i + 5)
                        val codePoint = hexDigits.toIntOrNull(16)
                        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                            result.append(String(Character.toChars(codePoint)))
                            i += 5
                            continue
                        }
                        // Invalid format, keep as-is
                        result.append(text[i])
                        i++
                    }
                    // Other escape sequences (like \\, \", \') - keep the escaped character
                    else -> {
                        result.append(text[i + 1])
                        i += 2
                    }
                }
            } else {
                result.append(text[i])
                i++
            }
        }

        return result.toString()
    }

    /**
     * Parse CSS text into a list of CssRule objects.
     *
     * @param cssText The CSS text to parse
     * @return List of parsed CSS rules
     * @throws CssParseException if the CSS is malformed
     */
    fun parseCss(cssText: String): List<CssRule> {
        val rules = mutableListOf<CssRule>()
        val parser = CssParser(cssText)

        try {
            while (!parser.isAtEnd()) {
                parser.skipWhitespaceAndComments()
                if (parser.isAtEnd()) break

                // Parse selector(s)
                val selectors = parser.parseSelectors()
                if (selectors.isEmpty()) {
                    // Might be an @-rule or other construct we don't support
                    parser.skipUntilClosingBrace()
                    continue
                }

                // Parse declaration block (which will populate rules list for nested rules)
                val parentSpecificity = selectors.maxOfOrNull { it.specificity() } ?: 0
                val declarations = parser.parseDeclarationBlock(rules, selectors, parentSpecificity)

                // Create a rule for each selector
                for (selector in selectors) {
                    rules.add(CssRule(selector, declarations))
                }
            }
        } catch (e: Exception) {
            val pos = parser.getPosition()
            throw CssParseException(
                "CSS parsing failed at line ${pos.line}, column ${pos.column}: ${e.message}",
                pos.line,
                pos.column,
                e
            )
        }

        return rules
    }

    /**
     * Internal parser class that maintains parsing state.
     */
    private class CssParser(private val css: String) {
        private var pos = 0
        private var line = 1
        private var column = 1

        fun isAtEnd(): Boolean = pos >= css.length

        fun getPosition(): Position = Position(line, column)

        private fun peek(): Char? = if (isAtEnd()) null else css[pos]

        private fun advance(): Char {
            val ch = css[pos]
            pos++
            if (ch == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return ch
        }

        @Suppress("SameParameterValue")
        private fun peekAhead(offset: Int): Char? {
            val index = pos + offset
            return if (index >= css.length) null else css[index]
        }

        fun skipWhitespaceAndComments() {
            while (!isAtEnd()) {
                when (peek()) {
                    ' ', '\t', '\r', '\n' -> advance()
                    '/' -> {
                        if (peekAhead(1) == '*') {
                            // Block comment
                            advance() // /
                            advance() // *
                            while (!isAtEnd()) {
                                if (peek() == '*' && peekAhead(1) == '/') {
                                    advance() // *
                                    advance() // /
                                    break
                                }
                                advance()
                            }
                        } else {
                            break
                        }
                    }

                    else -> break
                }
            }
        }

        /**
         * Parse comma-separated selectors.
         */
        fun parseSelectors(): List<CssSelector> {
            val selectors = mutableListOf<CssSelector>()
            val currentSelectorStr = StringBuilder()
            var inBrackets = false
            var inQuotes = false
            var quoteChar = ' '

            while (!isAtEnd()) {
                val ch = peek() ?: break
                if (inQuotes) {
                    if (ch == '\\') {
                        currentSelectorStr.append(advance())
                        currentSelectorStr.append(advance())
                    } else {
                        if (ch == quoteChar) {
                            inQuotes = false
                        }
                        currentSelectorStr.append(advance())
                    }
                } else {
                    when (ch) {
                        '"', '\'' -> {
                            inQuotes = true
                            quoteChar = ch
                            currentSelectorStr.append(advance())
                        }

                        '[' -> {
                            inBrackets = true
                            currentSelectorStr.append(advance())
                        }

                        ']' -> {
                            inBrackets = false
                            currentSelectorStr.append(advance())
                        }

                        ',' -> {
                            if (!inBrackets) {
                                advance() // consume comma
                                val parsed = parseSelectorChain(currentSelectorStr.toString())
                                if (parsed != null) {
                                    selectors.add(parsed)
                                }
                                currentSelectorStr.setLength(0)
                            } else {
                                currentSelectorStr.append(advance())
                            }
                        }

                        '{' -> {
                            if (!inBrackets) {
                                // We reached the start of the declaration block!
                                // Don't consume '{' so parseDeclarationBlock can see it
                                break
                            } else {
                                currentSelectorStr.append(advance())
                            }
                        }

                        else -> {
                            currentSelectorStr.append(advance())
                        }
                    }
                }
            }

            // Process the last selector in the list
            if (currentSelectorStr.isNotEmpty()) {
                val parsed = parseSelectorChain(currentSelectorStr.toString())
                if (parsed != null) {
                    selectors.add(parsed)
                }
            }

            return selectors
        }

        fun parseSelectorChain(selectorStr: String): CssSelector? {
            val parser = CssParser(selectorStr)
            parser.skipWhitespaceAndComments()

            var current: CssSelector? = null

            while (!parser.isAtEnd()) {
                val hasLeadingSpace = parser.peek()?.isWhitespace() == true
                parser.skipWhitespaceAndComments()
                if (parser.isAtEnd()) break

                val ch = parser.peek()
                val combinator: String?
                if (ch == '>') {
                    parser.advance()
                    combinator = ">"
                    parser.skipWhitespaceAndComments()
                } else if (ch == '+' || ch == '~') {
                    parser.advance()
                    combinator = ch.toString()
                    parser.skipWhitespaceAndComments()
                } else if (hasLeadingSpace && current != null && (ch?.isLetter() == true || ch == '*' || ch == '&')) {
                    combinator = " "
                } else {
                    combinator = null
                }

                val nextSelector = parser.parseNextCompoundSelector() ?: break

                current = if (current == null) {
                    nextSelector
                } else if (combinator != null) {
                    nextSelector.copy(
                        parentSelector = current,
                        combinator = combinator
                    )
                } else {
                    // No combinator (e.g., whitespace before class/attr/pseudo). Merge into current.
                    current.copy(
                        dataAttributes = current.dataAttributes + nextSelector.dataAttributes,
                        classes = current.classes + nextSelector.classes,
                        pseudoElement = nextSelector.pseudoElement ?: current.pseudoElement
                    )
                }
            }

            return current
        }

        fun parseNextCompoundSelector(): CssSelector? {
            var tag: String? = null
            val dataAttributes = mutableMapOf<String, String>()
            val classes = mutableSetOf<String>()
            var pseudoElement: String? = null
            var parsedSomething = false

            // Parse tag name (optional)
            val firstChar = peek()
            if (firstChar?.isLetter() == true) {
                tag = parseIdentifier()
                parsedSomething = true
            } else if (firstChar == '*') {
                advance()
                tag = null // Universal selector
                parsedSomething = true
            }

            // Parse attribute selectors and classes
            while (!isAtEnd()) {
                val ch = peek() ?: break
                if (ch.isWhitespace() || ch == '>' || ch == '+' || ch == '~' || ch == ',' || ch == '{') {
                    break
                }

                when (ch) {
                    '[' -> {
                        val attr = parseAttributeSelector()
                        if (attr != null && attr.first.startsWith("data-")) {
                            dataAttributes[attr.first] = attr.second
                            parsedSomething = true
                        }
                    }

                    '.' -> {
                        advance()
                        val className = parseIdentifier()
                        if (className.isNotEmpty()) {
                            classes.add(className)
                            parsedSomething = true
                        }
                    }

                    ':' -> {
                        advance()
                        if (peek() == ':') advance()
                        val pseudoName = parseIdentifier()
                        if (pseudoName.lowercase() in setOf("before", "after")) {
                            pseudoElement = pseudoName.lowercase()
                            parsedSomething = true
                        }
                        if (peek() == '(') {
                            var depth = 1
                            advance()
                            while (!isAtEnd() && depth > 0) {
                                when (advance()) {
                                    '(' -> depth++
                                    ')' -> depth--
                                }
                            }
                        }
                    }

                    else -> {
                        advance()
                    }
                }
            }

            // If we parsed nothing, return null
            if (tag == null && dataAttributes.isEmpty() && classes.isEmpty() && !parsedSomething) {
                return null
            }

            return CssSelector(tag, dataAttributes, classes, pseudoElement = pseudoElement)
        }

        /**
         * Parse an attribute selector: [attr="value"] or [attr='value']
         * Returns pair of (attribute-name, value) or null
         */
        fun parseAttributeSelector(): Pair<String, String>? {
            advance() // skip [
            skipWhitespaceAndComments()

            val attrName = parseIdentifier()
            if (attrName.isEmpty()) {
                // Malformed, skip to ]
                skipUntil(']')
                return null
            }

            skipWhitespaceAndComments()

            // Check for operator (=, ~=, |=, ^=, $=, *=)
            var operator = ""
            if (peek() in listOf('=', '~', '|', '^', '$', '*')) {
                operator += advance()
                if (peek() == '=') {
                    operator += advance()
                }
            }

            // We only care about exact match (=)
            if (operator != "=") {
                // Skip to ]
                skipUntil(']')
                return null
            }

            skipWhitespaceAndComments()

            // Parse value (must be quoted)
            val value = parseString() ?: ""

            skipWhitespaceAndComments()
            if (peek() == ']') {
                advance()
            }

            return Pair(attrName, value)
        }

        /**
         * Parse an identifier (alphanumeric, hyphens, underscores).
         */
        fun parseIdentifier(): String {
            val sb = StringBuilder()
            while (!isAtEnd()) {
                val ch = peek()
                if (ch != null && (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '*')) {
                    sb.append(advance())
                } else {
                    break
                }
            }
            return sb.toString()
        }

        /**
         * Parse a quoted string: "value" or 'value'
         */
        fun parseString(): String? {
            val quote = peek()
            if (quote != '"' && quote != '\'') {
                return null
            }
            advance() // skip opening quote

            val sb = StringBuilder()
            while (!isAtEnd()) {
                val ch = peek()
                if (ch == quote) {
                    advance() // skip closing quote
                    break
                } else if (ch == '\\') {
                    advance()
                    // Simple escape handling
                    val escaped = peek()
                    if (escaped != null) {
                        sb.append(advance())
                    }
                } else {
                    sb.append(advance())
                }
            }
            return sb.toString()
        }

        /**
         * Check if there is an open brace '{' before a semicolon or closing brace.
         */
        private fun hasOpenBraceBeforeSemicolonOrBrace(): Boolean {
            var i = pos
            while (i < css.length) {
                val ch = css[i]
                if (ch == '{') return true
                if (ch == ';' || ch == '}') return false
                i++
            }
            return false
        }

        /**
         * Parse a declaration block: { property: value; property: value; }
         */
        fun parseDeclarationBlock(
            rules: MutableList<CssRule>,
            parentSelectors: List<CssSelector> = emptyList(),
            parentSpecificity: Int = 0
        ): Map<String, String> {
            val declarations = mutableMapOf<String, String>()

            skipWhitespaceAndComments()
            if (peek() != '{') {
                throw IllegalStateException("Expected '{' at start of declaration block")
            }
            advance() // skip {

            var foundClosingBrace = false
            while (!isAtEnd()) {
                skipWhitespaceAndComments()
                val ch = peek()
                if (ch == '}') {
                    advance()
                    foundClosingBrace = true
                    break
                }

                if (ch == '&') {
                    // Nested rule!
                    advance() // skip &
                    // Parse nested selectors
                    val nestedSelectors = parseSelectors()
                    // Calculate combined specificity
                    val currentMaxNestedSpecificity = nestedSelectors.maxOfOrNull { it.specificity() } ?: 0
                    val combinedSpecificity = parentSpecificity + currentMaxNestedSpecificity
                    // Parse nested declaration block recursively
                    val nestedDeclarations = parseDeclarationBlock(rules, nestedSelectors, combinedSpecificity)
                    // Create and add nested rules
                    for (nestedSelector in nestedSelectors) {
                        if (parentSelectors.isEmpty()) {
                            val selectorWithCombinedSpecificity = nestedSelector.copy(
                                customSpecificity = parentSpecificity + nestedSelector.specificity()
                            )
                            rules.add(CssRule(selectorWithCombinedSpecificity, nestedDeclarations))
                        } else {
                            for (parentSel in parentSelectors) {
                                val selectorWithCombinedSpecificity = if (nestedSelector.pseudoElement != null &&
                                    nestedSelector.tag == null &&
                                    nestedSelector.classes.isEmpty() &&
                                    nestedSelector.dataAttributes.isEmpty()
                                ) {
                                    parentSel.copy(
                                        pseudoElement = nestedSelector.pseudoElement,
                                        customSpecificity = parentSel.specificity() + nestedSelector.specificity()
                                    )
                                } else {
                                    nestedSelector.copy(
                                        customSpecificity = parentSel.specificity() + nestedSelector.specificity(),
                                        parentSelector = parentSel
                                    )
                                }
                                rules.add(CssRule(selectorWithCombinedSpecificity, nestedDeclarations))
                            }
                        }
                    }
                    continue
                }

                // Parse property name
                val property = parseIdentifier()
                if (property.isEmpty()) {
                    // Might be another nested selector starting without &
                    if (hasOpenBraceBeforeSemicolonOrBrace()) {
                        val nestedSelectors = parseSelectors()
                        val currentMaxNestedSpecificity = nestedSelectors.maxOfOrNull { it.specificity() } ?: 0
                        val combinedSpecificity = parentSpecificity + currentMaxNestedSpecificity
                        val nestedDeclarations = parseDeclarationBlock(rules, nestedSelectors, combinedSpecificity)
                        for (nestedSelector in nestedSelectors) {
                            if (parentSelectors.isEmpty()) {
                                val selectorWithCombinedSpecificity = nestedSelector.copy(
                                    customSpecificity = parentSpecificity + nestedSelector.specificity()
                                )
                                rules.add(CssRule(selectorWithCombinedSpecificity, nestedDeclarations))
                            } else {
                                for (parentSel in parentSelectors) {
                                    val selectorWithCombinedSpecificity = if (nestedSelector.pseudoElement != null &&
                                        nestedSelector.tag == null &&
                                        nestedSelector.classes.isEmpty() &&
                                        nestedSelector.dataAttributes.isEmpty()
                                    ) {
                                        parentSel.copy(
                                            pseudoElement = nestedSelector.pseudoElement,
                                            customSpecificity = parentSel.specificity() + nestedSelector.specificity()
                                        )
                                    } else {
                                        nestedSelector.copy(
                                            customSpecificity = parentSel.specificity() + nestedSelector.specificity(),
                                            parentSelector = parentSel
                                        )
                                    }
                                    rules.add(CssRule(selectorWithCombinedSpecificity, nestedDeclarations))
                                }
                            }
                        }
                    } else {
                        skipUntilSemicolonOrBrace()
                    }
                    continue
                }

                skipWhitespaceAndComments()
                if (peek() != ':') {
                    // Malformed declaration, skip it
                    skipUntilSemicolonOrBrace()
                    continue
                }
                advance() // skip :

                skipWhitespaceAndComments()

                // Parse value (everything until ; or })
                val value = parseDeclarationValue()

                declarations[property] = value.trim()

                skipWhitespaceAndComments()
                if (peek() == ';') {
                    advance()
                }
            }

            if (!foundClosingBrace) {
                throw IllegalStateException("Unclosed declaration block - missing '}'")
            }

            return declarations
        }

        /**
         * Parse a declaration value (everything until ; or })
         */
        private fun parseDeclarationValue(): String {
            val sb = StringBuilder()
            var parenDepth = 0
            var braceDepth = 0

            while (!isAtEnd()) {
                when (val ch = peek()) {
                    '(' -> {
                        parenDepth++
                        sb.append(advance())
                    }

                    ')' -> {
                        parenDepth--
                        sb.append(advance())
                    }

                    '{' -> {
                        braceDepth++
                        sb.append(advance())
                    }

                    '}' -> {
                        if (braceDepth > 0) {
                            braceDepth--
                            sb.append(advance())
                        } else {
                            // End of declaration block
                            break
                        }
                    }

                    ';' -> {
                        if (parenDepth == 0 && braceDepth == 0) {
                            // End of declaration
                            break
                        } else {
                            sb.append(advance())
                        }
                    }

                    '"', '\'' -> {
                        val str = parseString()
                        if (str != null) {
                            sb.append(ch).append(str).append(ch)
                        } else {
                            sb.append(advance())
                        }
                    }

                    else -> sb.append(advance())
                }
            }

            return sb.toString()
        }

        /**
         * Skip until we find a closing brace }
         * This method first finds the opening { if not already at one,
         * then skips to the matching closing }
         */
        fun skipUntilClosingBrace() {
            // First, skip to find the opening brace
            while (!isAtEnd() && peek() != '{') {
                advance()
            }

            if (isAtEnd()) return

            // Now we're at {, skip it and track nesting depth
            advance() // skip the opening {
            var depth = 1

            while (!isAtEnd() && depth > 0) {
                when (peek()) {
                    '{' -> {
                        depth++
                        advance()
                    }

                    '}' -> {
                        depth--
                        advance()
                    }

                    else -> advance()
                }
            }
        }

        /**
         * Skip until ; or }
         */
        private fun skipUntilSemicolonOrBrace() {
            while (!isAtEnd()) {
                when (peek()) {
                    ';', '}' -> break
                    else -> advance()
                }
            }
        }

        /**
         * Skip until a specific character
         */
        @Suppress("SameParameterValue")
        private fun skipUntil(target: Char) {
            while (!isAtEnd() && peek() != target) {
                advance()
            }
            if (peek() == target) {
                advance()
            }
        }
    }

    /**
     * Position in CSS text (line and column numbers, 1-indexed).
     */
    data class Position(val line: Int, val column: Int)

    /**
     * Resolve styles for an element by matching CSS rules and merging with inline styles.
     *
     * This method:
     * 1. Matches CSS selectors against element metadata (tag, data attributes, classes)
     * 2. Calculates specificity for each matching rule
     * 3. Applies rules with highest specificity first, then by declaration order
     * 4. Merges inline styles (which have higher priority than CSS rules)
     * 5. Returns a ResolvedStyle object with all resolved properties
     *
     * @param tag Element tag name (e.g., "div", "span")
     * @param dataAttributes Map of data attributes (e.g., "content" -> "sense-group")
     * @param classes Set of CSS class names
     * @param inlineStyle Map of inline style declarations from the element's "style" attribute
     * @param parentStyles Parent element's resolved styles (for inheritance, currently unused)
     * @param cssRules List of CSS rules to match against
     * @return ResolvedStyle object with all resolved properties
     */
    fun resolveStyles(
        tag: String,
        dataAttributes: Map<String, String> = emptyMap(),
        classes: Set<String> = emptySet(),
        inlineStyle: Map<String, String>? = null,
        parentStyles: ResolvedStyle? = null,
        cssRules: List<CssRule> = emptyList(),
        parentStack: List<ElementContext> = emptyList()
    ): ResolvedStyle {
        // Find all matching rules
        val matchingRules = cssRules.filter { rule ->
            rule.selector.pseudoElement == null && rule.selector.matches(tag, dataAttributes, classes, parentStack)
        }

        // Sort by specificity (ascending), then by declaration order
        // Rules appearing later in the CSS have higher precedence when specificity is equal
        val sortedRules = matchingRules.sortedBy { it.selector.specificity() }

        // Merge declarations from all matching rules
        val mergedDeclarations = mutableMapOf<String, String>()
        for (rule in sortedRules) {
            mergedDeclarations.putAll(rule.declarations)
        }

        // Apply inline styles (highest priority)
        if (inlineStyle != null) {
            mergedDeclarations.putAll(inlineStyle)
        }

        // Convert CSS declarations to ResolvedStyle properties
        val resolved = buildResolvedStyle(mergedDeclarations)

        // Calculate resolved font size with inheritance and relative unit support
        val rawFontSizeValue = mergedDeclarations["font-size"]
        val resolvedFontSize = if (rawFontSizeValue != null) {
            val trimmed = rawFontSizeValue.trim().lowercase()
            val isRelative = (trimmed.endsWith("em") && !trimmed.endsWith("rem")) || trimmed.endsWith("%")
            if (isRelative && parentStyles != null) {
                resolved.fontSize * parentStyles.fontSize
            } else {
                resolved.fontSize
            }
        } else {
            parentStyles?.fontSize ?: 1.0f
        }

        val resolvedWithFont = resolved.copy(fontSize = resolvedFontSize)

        // li elements with a list marker are always blockType=1 (list-item).
        // buildResolvedStyle is tag-agnostic, so we apply this semantic rule here
        // where the tag is known. ul/ol elements are intentionally excluded. Their
        // marker is inherited by child li elements, not applied to themselves.
        return if (tag == "li" && resolvedWithFont.listMarker != null && resolvedWithFont.listMarker != "none" && resolvedWithFont.blockType == 0) {
            resolvedWithFont.copy(blockType = 1)
        } else {
            resolvedWithFont
        }
    }

    fun resolvePseudoElementStyles(
        pseudoType: String,
        tag: String,
        dataAttributes: Map<String, String> = emptyMap(),
        classes: Set<String> = emptySet(),
        parentStyles: ResolvedStyle? = null,
        cssRules: List<CssRule> = emptyList(),
        parentStack: List<ElementContext> = emptyList()
    ): ResolvedStyle? {
        val matchingRules = cssRules.filter { rule ->
            rule.selector.pseudoElement == pseudoType &&
                    rule.selector.matches(tag, dataAttributes, classes, parentStack)
        }
        if (matchingRules.isEmpty()) return null

        val sortedRules = matchingRules.sortedBy { it.selector.specificity() }
        val mergedDeclarations = mutableMapOf<String, String>()
        for (rule in sortedRules) {
            mergedDeclarations.putAll(rule.declarations)
        }

        val resolved = buildResolvedStyle(mergedDeclarations)

        val rawFontSizeValue = mergedDeclarations["font-size"]
        val resolvedFontSize = if (rawFontSizeValue != null) {
            val trimmed = rawFontSizeValue.trim().lowercase()
            val isRelative = (trimmed.endsWith("em") && !trimmed.endsWith("rem")) || trimmed.endsWith("%")
            if (isRelative && parentStyles != null) {
                resolved.fontSize * parentStyles.fontSize
            } else {
                resolved.fontSize
            }
        } else {
            parentStyles?.fontSize ?: 1.0f
        }

        return resolved.copy(fontSize = resolvedFontSize)
    }

    /**
     * Build a ResolvedStyle from CSS declarations.
     * Converts CSS property values to the appropriate types for ResolvedStyle.
     */
    private fun buildResolvedStyle(declarations: Map<String, String>): ResolvedStyle {
        var bold = false
        var italic = false
        var fontSize = 1.0f
        var foregroundColor = 0
        val textBackgroundColor = 0
        var backgroundColor = 0
        var listMarker: String? = null
        val blockType = 0
        var hoverText: String? = null
        var marginRight: String? = null
        var content: String? = null

        for ((property, value) in declarations) {
            when (property.lowercase()) {
                "font-weight" -> {
                    bold = convertFontWeight(value)
                }

                "font-style" -> {
                    italic = convertFontStyle(value)
                }

                "font-size" -> {
                    fontSize = convertFontSize(value)
                }

                "color" -> {
                    foregroundColor = convertColor(value)
                }

                "background-color", "background" -> {
                    // For block-level elements, this becomes backgroundColor
                    // For inline elements, it could be textBackgroundColor
                    // We'll store it as backgroundColor by default
                    backgroundColor = convertColor(value)
                }

                "list-style-type" -> {
                    listMarker = extractListMarker(value)
                }

                "title" -> {
                    hoverText = value
                }

                "margin-right" -> {
                    marginRight = value
                }

                "content" -> {
                    content = extractContentString(value)
                }
                // Add more property mappings as needed
            }
        }

        return ResolvedStyle(
            bold = bold,
            italic = italic,
            fontSize = fontSize,
            foregroundColor = foregroundColor,
            textBackgroundColor = textBackgroundColor,
            backgroundColor = backgroundColor,
            listMarker = listMarker,
            blockType = blockType,
            hoverText = hoverText,
            marginRight = marginRight,
            content = content
        )
    }

    private fun extractContentString(contentValue: String): String? {
        val trimmed = contentValue.trim()
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))
        ) {
            val quoted = trimmed.substring(1, trimmed.length - 1)
            return processUnicodeEscapes(quoted)
        }
        if (trimmed == "none" || trimmed == "normal") return null
        return processUnicodeEscapes(trimmed)
    }

    /**
     * Convert ResolvedStyle to Dokuen SDK InlineStyle.
     *
     * @param resolved The resolved style properties
     * @param linkUrl Optional link URL for the span
     * @return InlineStyle object for use in StyledSpan
     */
    fun toInlineStyle(resolved: ResolvedStyle, linkUrl: String? = null): InlineStyle {
        return InlineStyle(
            bold = resolved.bold,
            italic = resolved.italic,
            fontSize = resolved.fontSize,
            foregroundColor = resolved.foregroundColor,
            textBackgroundColor = resolved.textBackgroundColor,
            hoverText = resolved.hoverText,
            linkUrl = linkUrl
        )
    }

    /**
     * Convert ResolvedStyle to Dokuen SDK BlockSpan.
     *
     * @param resolved The resolved style properties
     * @param textRange The text range this block spans (startIndex to endIndex)
     * @param indentLevel The indentation level (default 0)
     * @return BlockSpan object or null if no block properties are set
     */
    fun toBlockSpan(
        resolved: ResolvedStyle,
        textRange: IntRange,
        indentLevel: Int = 0
    ): BlockSpan? {
        val effectiveListMarker =
            if (resolved.listMarker == "none" || resolved.listMarker == "decimal") null else resolved.listMarker
        val effectiveBlockType =
            if (resolved.blockType == 0 && resolved.listMarker != null && resolved.listMarker != "none") {
                1
            } else {
                resolved.blockType
            }

        // Only create a BlockSpan if there are block-level properties to apply
        if (effectiveBlockType == 0 && resolved.backgroundColor == 0 && indentLevel == 0) {
            return null
        }

        return BlockSpan(
            startIndex = textRange.first,
            endIndex = textRange.last + 1, // IntRange is inclusive, BlockSpan endIndex is exclusive
            blockType = effectiveBlockType,
            indentLevel = indentLevel,
            listMarker = effectiveListMarker,
            backgroundColor = resolved.backgroundColor
        )
    }
}

/**
 * Exception thrown when CSS parsing fails.
 */
class CssParseException(
    message: String,
    val line: Int,
    val column: Int,
    cause: Throwable? = null
) : Exception(message, cause)
