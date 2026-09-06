package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

/**
 * A dictionary's styles.css, scoped the two ways the original embeds it in its glossary
 * markers (anki-note-data-creator.js, getTermDictionaryEntryCommonInfo): [glossaryScoped]
 * limits every rule to `.yomitan-glossary` (the original's glossaryScopedStyles, used by
 * `{glossary-first}`), and [dictionaryScoped] additionally to the definition's own
 * `<li data-dictionary="...">` (dictScopedStyles, used by `{glossary}`). Both are empty when
 * the CSS has no style rules, in which case no `<style>` element is written.
 */
data class AnkiScopedStyles(val glossaryScoped: String = "", val dictionaryScoped: String = "")

/**
 * Port of the original's CSS scoping for Anki glossary fields (sanitizeCSS followed by
 * addScopeToCssLegacy, ext/js/core/utilities.js): each top-level style rule's selectors are
 * prefixed with the scope, rules nested inside a style rule (CSS nesting) stay inside it
 * unchanged, and every other top-level rule (`@media`, `@font-face`, `@import` and so on) is
 * dropped. Rules are written one per line as `selector { name: value; ... }`.
 *
 * The original parses and re-serializes the CSS with the browser's own CSS engine. This port
 * has none, so it uses a small parser that only understands the structure needed for scoping:
 * comments are removed and whitespace is collapsed, but values keep their original spelling
 * (a browser would, for example, write `#fff` as `rgb(255, 255, 255)`), and declarations a
 * browser would reject are kept. The browser that renders the card ignores those the same way,
 * so the card looks the same.
 *
 * Intentional deviation: a selector list is split at its top-level commas only. The original
 * splits the serialized selector text at every comma, which breaks a selector that has a comma
 * inside `:is()`/`:not()` or a quoted attribute value, and, for the dictionary scope, every
 * selector of a dictionary whose title contains a comma.
 */
object AnkiDictionaryStyles {
    private const val GLOSSARY_SCOPE = ".yomitan-glossary"

    fun scope(css: String, dictionary: String): AnkiScopedStyles {
        if (css.isBlank()) return AnkiScopedStyles()
        val rules = CssParser(css).parseStyleSheet()
        if (rules.isEmpty()) return AnkiScopedStyles()
        // The original's addDictionaryScopeToCss escapes the title for the attribute selector.
        val escapedTitle = dictionary.replace("\\", "\\\\").replace("\"", "\\\"")
        val dictionaryScope = "$GLOSSARY_SCOPE [data-dictionary=\"$escapedTitle\"]"
        return AnkiScopedStyles(
            glossaryScoped = rules.joinToString("\n") { it.serialize(GLOSSARY_SCOPE) },
            dictionaryScoped = rules.joinToString("\n") { it.serialize(dictionaryScope) }
        )
    }

    private sealed interface CssItem {
        fun serialize(): String
    }

    private class Declaration(val name: String, val value: String) : CssItem {
        override fun serialize(): String = "$name: $value;"
    }

    /** A rule inside a style rule's block: a nested style rule or a nested at-rule. */
    private class NestedRule(val prelude: String, val items: List<CssItem>) : CssItem {
        override fun serialize(): String = "$prelude ${serializeBlock(items)}"
    }

    private class StyleRule(val selectors: List<String>, val items: List<CssItem>) {
        fun serialize(scope: String): String =
            selectors.joinToString(", ") { "$scope $it" } + " " + serializeBlock(items)
    }

    private fun serializeBlock(items: List<CssItem>): String =
        if (items.isEmpty()) "{ }" else items.joinToString(" ", prefix = "{ ", postfix = " }") { it.serialize() }

    private class CssParser(css: String) {
        private val text = stripComments(css)
        private var pos = 0

        fun parseStyleSheet(): List<StyleRule> {
            val rules = mutableListOf<StyleRule>()
            while (true) {
                skipWhitespace()
                if (pos >= text.length) break
                if (text[pos] == '@') {
                    skipAtRule()
                    continue
                }
                val index = findTopLevel(text, pos, "{}")
                if (index >= text.length) break
                if (text[index] == '}') {
                    // A stray closing brace: skip it.
                    pos = index + 1
                    continue
                }
                val prelude = text.substring(pos, index)
                pos = index + 1
                val items = parseBlock()
                val selectors = splitSelectorList(prelude)
                if (selectors.isNotEmpty()) rules.add(StyleRule(selectors, items))
            }
            return rules
        }

        /** Parses the contents of a block whose `{` was just consumed, through its `}`. */
        private fun parseBlock(): List<CssItem> {
            val items = mutableListOf<CssItem>()
            while (true) {
                skipWhitespace()
                if (pos >= text.length) return items
                when (text[pos]) {
                    '}' -> {
                        pos++
                        return items
                    }

                    ';' -> {
                        pos++
                        continue
                    }
                }
                val index = findTopLevel(text, pos, ";{}")
                if (index < text.length && text[index] == '{') {
                    val prelude = text.substring(pos, index)
                    pos = index + 1
                    val nestedItems = parseBlock()
                    val normalized = if (prelude.trimStart().startsWith("@")) {
                        collapseWhitespace(prelude)
                    } else {
                        splitSelectorList(prelude).joinToString(", ")
                    }
                    if (normalized.isNotEmpty()) items.add(NestedRule(normalized, nestedItems))
                } else {
                    parseDeclaration(text.substring(pos, index))?.let { items.add(it) }
                    // Leave a closing brace for the loop above.
                    pos = if (index < text.length && text[index] == ';') index + 1 else index
                }
            }
        }

        private fun parseDeclaration(raw: String): Declaration? {
            val colon = raw.indexOf(':')
            if (colon < 0) return null
            val name = raw.substring(0, colon).trim()
            if (name.isEmpty() || name.any { it.isWhitespace() }) return null
            val isCustomProperty = name.startsWith("--")
            val value = collapseWhitespace(raw.substring(colon + 1)).replace(importantPattern, " !important")
            if (value.isEmpty() && !isCustomProperty) return null
            return Declaration(if (isCustomProperty) name else name.lowercase(), value)
        }

        private fun skipAtRule() {
            val index = findTopLevel(text, pos, ";{}")
            if (index >= text.length) {
                pos = text.length
                return
            }
            pos = index + 1
            if (text[index] == '{') skipBlock()
        }

        private fun skipBlock() {
            var depth = 1
            while (depth > 0) {
                val index = findTopLevel(text, pos, "{}")
                if (index >= text.length) {
                    pos = text.length
                    return
                }
                if (text[index] == '{') depth++ else depth--
                pos = index + 1
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }

    private val importantPattern = Regex("\\s*!\\s*important$", RegexOption.IGNORE_CASE)

    /** Splits a selector list at its top-level commas and normalizes each selector. */
    private fun splitSelectorList(prelude: String): List<String> {
        val selectors = mutableListOf<String>()
        var start = 0
        while (start <= prelude.length) {
            val index = findTopLevel(prelude, start, ",")
            normalizeSelector(prelude.substring(start, index)).takeIf { it.isNotEmpty() }?.let { selectors.add(it) }
            start = index + 1
        }
        return selectors
    }

    /**
     * Collapses whitespace and puts single spaces around the top-level combinators `>`, `+`
     * and `~`, the way a browser writes selector text. Attribute selectors and parenthesized
     * arguments (such as `:nth-child(2n+1)`) are left as written.
     */
    private fun normalizeSelector(selector: String): String {
        val sb = StringBuilder()
        var depth = 0
        var i = 0
        while (i < selector.length) {
            val c = selector[i]
            when {
                c == '\\' -> {
                    sb.append(selector, i, minOf(i + 2, selector.length))
                    i += 2
                    continue
                }

                c == '"' || c == '\'' -> {
                    val end = skipString(selector, i)
                    sb.append(selector, i, end)
                    i = end
                    continue
                }

                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                depth == 0 && (c == '>' || c == '+' || c == '~') -> {
                    sb.append(' ').append(c).append(' ')
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return collapseWhitespace(sb.toString())
    }

    /** Collapses each run of whitespace outside strings to one space, and trims. */
    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
                i++
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            when (c) {
                '\\' -> {
                    sb.append(text, i, minOf(i + 2, text.length))
                    i += 2
                }

                '"', '\'' -> {
                    val end = skipString(text, i)
                    sb.append(text, i, end)
                    i = end
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * Index of the first character of [stops] at or after [from] that is outside strings,
     * escapes, parentheses and brackets, or `text.length` if there is none.
     */
    private fun findTopLevel(text: String, from: Int, stops: String): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    i += 2
                    continue
                }

                c == '"' || c == '\'' -> {
                    i = skipString(text, i)
                    continue
                }

                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                depth == 0 && c in stops -> return i
            }
            i++
        }
        return text.length
    }

    /**
     * The index just past the string starting at [start]. As in CSS, an unterminated string
     * ends at a newline.
     */
    private fun skipString(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    /** Replaces each comment outside a string with a space. */
    private fun stripComments(css: String): String {
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c == '\\' -> {
                    sb.append(css, i, minOf(i + 2, css.length))
                    i += 2
                }

                c == '"' || c == '\'' -> {
                    val end = skipString(css, i)
                    sb.append(css, i, end)
                    i = end
                }

                c == '/' && i + 1 < css.length && css[i + 1] == '*' -> {
                    val end = css.indexOf("*/", i + 2)
                    i = if (end < 0) css.length else end + 2
                    sb.append(' ')
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
