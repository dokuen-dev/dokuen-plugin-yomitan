package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a single glossary array entry (as stored raw in TermEntity.glossary) into the
 * strings the original Yomitan's Anki template helpers produce for it:
 * [convertGlossaryEntry] mirrors `formatGlossary` (HTML) and [convertGlossaryEntryPlain]
 * mirrors `formatGlossaryPlain` (plain text with `<br>` line breaks).
 *
 * Structured content is rebuilt the way the original's StructuredContentGenerator builds
 * it and then serialized the way its Anki renderer normalizes it: a wrapping `<span>`,
 * `data-sc-*` attributes from each node's `data`, automatic `lang` detection on elements
 * that directly contain Japanese or Chinese text, inline styles from each node's `style`,
 * the fixed class-based styles for tables, links rendered with their external-link icon
 * placeholder, and newlines turned into `<br>`. Images are not embedded (this port does not
 * transfer dictionary media to AnkiDroid), so an image renders as a bare `<img>` carrying
 * its path and size.
 */
object AnkiGlossaryHtmlConverter {

    fun convertGlossaryEntry(element: JsonElement): String {
        return when (element) {
            is JsonPrimitive -> if (element.isString) stringToMultiLineHtml(escapeAngleBrackets(element.content)) else ""
            is JsonObject -> when (element.stringOrNull("type")) {
                "structured-content" -> serialize(createStructuredContent(element["content"]))
                "text" -> element.stringOrNull("text")?.let { stringToMultiLineHtml(escapeAngleBrackets(it)) } ?: ""
                "image" -> imageHtml(element)
                else -> ""
            }

            else -> ""
        }
    }

    fun convertGlossaryEntryPlain(element: JsonElement): String {
        return when (element) {
            is JsonPrimitive -> if (element.isString) escapeAngleBrackets(element.content) else ""
            is JsonObject -> when (element.stringOrNull("type")) {
                "structured-content" -> {
                    val glossaryStrings = extractGlossaryData(element)
                    if (glossaryStrings.isNotEmpty()) {
                        glossaryStrings.joinToString("<br>\n")
                    } else {
                        structuredContentText(createStructuredContent(element["content"]))
                    }
                }

                "text" -> element.stringOrNull("text")?.let { escapeAngleBrackets(it) } ?: ""
                else -> ""
            }

            else -> ""
        }
    }

    // DOM model

    private sealed class Node

    private class TextNode(val text: String) : Node()

    /** Pre-rendered markup inserted verbatim. */
    private class RawNode(val html: String) : Node()

    private class ElementNode(val tag: String) : Node() {
        /** Attributes in the order the original sets them; "style" is a position placeholder. */
        val attributes = LinkedHashMap<String, String>()
        val inlineStyle = LinkedHashMap<String, String>()
        var classStyle: String = ""
        val children = mutableListOf<Node>()

        fun setAttribute(name: String, value: String) {
            attributes[name] = value
        }

        fun setStyle(property: String, value: String) {
            inlineStyle[property] = value
            if (!attributes.containsKey("style")) attributes["style"] = ""
        }
    }

    private val voidTags = setOf("br", "img", "hr")

    private fun serialize(node: Node): String {
        val sb = StringBuilder()
        appendNode(sb, node)
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: Node) {
        when (node) {
            is TextNode -> {
                val parts = node.text.split('\n')
                parts.forEachIndexed { index, part ->
                    if (index > 0) sb.append("<br>")
                    sb.append(escapeText(part))
                }
            }

            is RawNode -> sb.append(node.html)
            is ElementNode -> {
                sb.append('<').append(node.tag)
                val inlineCss = node.inlineStyle.entries.joinToString(" ") { (k, v) -> "$k: $v;" }
                val style = node.classStyle + inlineCss
                val attributes = LinkedHashMap(node.attributes)
                if (style.isNotEmpty()) attributes["style"] = style else attributes.remove("style")
                for ((name, value) in attributes) {
                    sb.append(' ').append(name).append("=\"").append(escapeAttribute(value)).append('"')
                }
                sb.append('>')
                if (node.tag in voidTags) return
                node.children.forEach { appendNode(sb, it) }
                sb.append("</").append(node.tag).append('>')
            }
        }
    }

    // StructuredContentGenerator

    private fun createStructuredContent(content: JsonElement?): ElementNode {
        val node = ElementNode("span")
        appendStructuredContent(node, content, null)
        return node
    }

    private fun appendStructuredContent(container: ElementNode, content: JsonElement?, language: String?) {
        when (content) {
            null, JsonNull -> return
            is JsonPrimitive -> {
                if (!content.isString) return
                val text = content.content
                if (text.isEmpty()) return
                container.children.add(TextNode(text))
                if (language == null) {
                    languageFromText(text)?.let { container.setAttribute("lang", it) }
                }
            }

            is JsonArray -> content.forEach { appendStructuredContent(container, it, language) }
            is JsonObject -> createGenericElement(content, language)?.let { container.children.add(it) }
        }
    }

    private fun createGenericElement(content: JsonObject, language: String?): Node? {
        return when (val tag = content.stringOrNull("tag")) {
            "br" -> createElement(tag, content, language, CellType.SIMPLE, hasChildren = false, hasStyle = false)
            "ruby", "rt", "rp" -> createElement(
                tag,
                content,
                language,
                CellType.SIMPLE,
                hasChildren = true,
                hasStyle = false
            )

            "table" -> {
                val container = ElementNode("div")
                container.classStyle = "display:block;"
                val table = createElement(tag, content, language, CellType.TABLE, hasChildren = true, hasStyle = false)
                table.classStyle = "table-layout:auto;border-collapse:collapse;"
                container.children.add(table)
                container
            }

            "thead", "tbody", "tfoot", "tr" -> createElement(
                tag,
                content,
                language,
                CellType.TABLE,
                hasChildren = true,
                hasStyle = false
            ).also {
                if (tag == "thead" || tag == "tfoot") it.classStyle = "font-weight:bold;"
            }

            "th", "td" -> createElement(
                tag,
                content,
                language,
                CellType.TABLE_CELL,
                hasChildren = true,
                hasStyle = true
            ).also {
                it.classStyle = (if (tag == "th") "font-weight:bold;" else "") + TABLE_CELL_STYLE
            }

            "div", "span", "ol", "ul", "li", "details", "summary" ->
                createElement(tag, content, language, CellType.SIMPLE, hasChildren = true, hasStyle = true)

            "img" -> RawNode(imageHtml(content))
            "a" -> createLinkElement(content, language)
            else -> null
        }
    }

    private enum class CellType { SIMPLE, TABLE, TABLE_CELL }

    private const val TABLE_CELL_STYLE =
        "border-style:solid;padding:0.25em;vertical-align:top;border-width:1px;border-color:currentColor;"

    private fun createElement(
        tag: String,
        content: JsonObject,
        inheritedLanguage: String?,
        type: CellType,
        hasChildren: Boolean,
        hasStyle: Boolean
    ): ElementNode {
        val node = ElementNode(tag)
        var language = inheritedLanguage
        (content["data"] as? JsonObject)?.let { setElementDataset(node, it) }
        content.stringOrNull("lang")?.let {
            node.setAttribute("lang", it)
            language = it
        }
        if (type == CellType.TABLE_CELL) {
            (content["colSpan"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let {
                node.setAttribute("colspan", formatNumber(it))
            }
            (content["rowSpan"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let {
                node.setAttribute("rowspan", formatNumber(it))
            }
        }
        if (hasStyle) {
            (content["style"] as? JsonObject)?.let { applyContentStyle(node, it) }
            content.stringOrNull("title")?.let { node.setAttribute("title", it) }
            if ((content["open"] as? JsonPrimitive)?.booleanOrNull == true) node.setAttribute("open", "")
        }
        if (hasChildren) appendStructuredContent(node, content["content"], language)
        return node
    }

    private fun createLinkElement(content: JsonObject, inheritedLanguage: String?): ElementNode {
        val href = content.stringOrNull("href") ?: ""
        val internal = href.startsWith("?")
        val node = ElementNode("a")
        val text = ElementNode("span")
        node.children.add(text)
        var language = inheritedLanguage
        content.stringOrNull("lang")?.let {
            node.setAttribute("lang", it)
            language = it
        }
        appendStructuredContent(text, content["content"], language)
        if (!internal) {
            val icon = ElementNode("span")
            icon.classStyle = "display:none;"
            node.children.add(icon)
        }
        node.setAttribute("href", if (internal) "#" else href)
        return node
    }

    private fun setElementDataset(node: ElementNode, data: JsonObject) {
        for ((rawKey, value) in data) {
            val key = "sc" + rawKey.replaceFirstChar { it.uppercaseChar() }
            // A dataset key with a hyphen followed by a lowercase letter is rejected by the DOM.
            if (Regex("-[a-z]").containsMatchIn(key)) continue
            val attributeName = "data-" + key.replace(Regex("[A-Z]")) { "-" + it.value.lowercase() }
            val stringValue = when (value) {
                is JsonPrimitive -> if (value.isString) value.content else value.toString()
                else -> value.toString()
            }
            node.setAttribute(attributeName, stringValue)
        }
    }

    private fun applyContentStyle(node: ElementNode, style: JsonObject) {
        fun str(key: String): String? = (style[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(key: String): Double? = (style[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        str("fontStyle")?.let { node.setStyle("font-style", it) }
        str("fontWeight")?.let { node.setStyle("font-weight", it) }
        str("fontSize")?.let { node.setStyle("font-size", it) }
        str("color")?.let { node.setStyle("color", it) }
        str("background")?.let { node.setStyle("background", it) }
        str("backgroundColor")?.let { node.setStyle("background-color", it) }
        str("verticalAlign")?.let { node.setStyle("vertical-align", it) }
        str("textAlign")?.let { node.setStyle("text-align", it) }
        str("textEmphasis")?.let { node.setStyle("text-emphasis", it) }
        str("textShadow")?.let { node.setStyle("text-shadow", it) }
        when (val line = style["textDecorationLine"]) {
            is JsonPrimitive -> if (line.isString) node.setStyle("text-decoration", line.content)
            is JsonArray -> node.setStyle("text-decoration", line.joinToString(" ") { it.jsonPrimitive.content })
            else -> {}
        }
        str("textDecorationStyle")?.let { node.setStyle("text-decoration-style", it) }
        str("textDecorationColor")?.let { node.setStyle("text-decoration-color", it) }
        str("borderColor")?.let { node.setStyle("border-color", it) }
        str("borderStyle")?.let { node.setStyle("border-style", it) }
        str("borderRadius")?.let { node.setStyle("border-radius", it) }
        str("borderWidth")?.let { node.setStyle("border-width", it) }
        str("clipPath")?.let { node.setStyle("clip-path", it) }
        str("margin")?.let { node.setStyle("margin", it) }
        for ((key, property) in listOf(
            "marginTop" to "margin-top",
            "marginLeft" to "margin-left",
            "marginRight" to "margin-right",
            "marginBottom" to "margin-bottom"
        )) {
            num(key)?.let { node.setStyle(property, formatNumber(it) + "em") }
            str(key)?.let { node.setStyle(property, it) }
        }
        str("padding")?.let { node.setStyle("padding", it) }
        str("paddingTop")?.let { node.setStyle("padding-top", it) }
        str("paddingLeft")?.let { node.setStyle("padding-left", it) }
        str("paddingRight")?.let { node.setStyle("padding-right", it) }
        str("paddingBottom")?.let { node.setStyle("padding-bottom", it) }
        str("wordBreak")?.let { node.setStyle("word-break", it) }
        str("whiteSpace")?.let { node.setStyle("white-space", it) }
        str("cursor")?.let { node.setStyle("cursor", it) }
        str("listStyleType")?.let { node.setStyle("list-style-type", it) }
    }

    private fun imageHtml(obj: JsonObject): String {
        val path = obj.stringOrNull("path") ?: ""
        val sizeAttrs = buildString {
            (obj["width"] as? JsonPrimitive)?.doubleOrNull?.let { append(" width=\"${formatNumber(it)}\"") }
            (obj["height"] as? JsonPrimitive)?.doubleOrNull?.let { append(" height=\"${formatNumber(it)}\"") }
        }
        return "<img alt=\"${escapeAttribute(path)}\"$sizeAttrs>"
    }

    // formatGlossaryPlain helpers

    /** The original's _extractGlossaryData: text of nodes tagged `data: {content: "glossary"}`. */
    private fun extractGlossaryData(structuredContent: JsonObject): List<String> {
        val queue = ArrayDeque<JsonElement>()
        structuredContent["content"]?.let { collectGlossaryNodes(listOf(it), queue) }
        return convertGlossaryNodes(queue)
    }

    private fun collectGlossaryNodes(content: List<JsonElement>, out: ArrayDeque<JsonElement>) {
        for (item in content) {
            when (item) {
                is JsonArray -> collectGlossaryNodes(item, out)
                is JsonObject -> {
                    val data = item["data"] as? JsonObject
                    if (data?.stringOrNull("content") == "glossary") {
                        out.addLast(item)
                        continue
                    }
                    item["content"]?.takeIf { isTruthy(it) }?.let { collectGlossaryNodes(listOf(it), out) }
                }

                else -> {}
            }
        }
    }

    private fun convertGlossaryNodes(content: ArrayDeque<JsonElement>): List<String> {
        val results = mutableListOf<String>()
        while (content.isNotEmpty()) {
            when (val item = content.removeFirst()) {
                is JsonPrimitive -> if (item.isString) results.add(item.content)
                is JsonArray -> results.addAll(convertGlossaryNodes(ArrayDeque(item)))
                is JsonObject -> {
                    val child = item["content"]?.takeIf { isTruthy(it) } ?: continue
                    if (item.stringOrNull("tag") == "ruby") {
                        results.add(structuredContentText(createStructuredContent(child)))
                        continue
                    }
                    results.addAll(convertGlossaryNodes(ArrayDeque(listOf(child))))
                }
            }
        }
        return results
    }

    /**
     * The original renderer's _getText: flattens serialized structured content to text.
     *
     * Intentional deviation: the original escapes a leftover `>` as `&rt;`, which is not an
     * HTML entity (a typo for `&gt;`), so it shows up literally in the Anki field. This port
     * emits the correct `&gt;` instead. Text nodes are already escaped by [serialize], so a
     * leftover `>` only occurs when an attribute value contains one (for example a `data`
     * value): the tag-stripping regexes stop at that `>` and leave the rest of the tag behind.
     */
    private fun structuredContentText(node: ElementNode): String {
        return serialize(node)
            .replace(Regex("<(div|li|ol|ul|br|details|summary|hr)(\\s.*?>|>)"), "\n")
            .replace(Regex("<(span|a|ruby)(\\s.*?>|>)"), " ")
            .replace(Regex("<rt(\\s.*?>|>)"), "[")
            .replace("</rt>", "]")
            .replace(Regex("<.*?>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("\n+"), "<br>")
            .replace(Regex("^(?:[\\s\\u3000]*<br>[\\s\\u3000]*|[\\s\\u3000])*"), "")  // "?U" doesn't work on Android
            .replace("<br>", "<br>\n")
    }

    // Shared helpers

    private fun isTruthy(element: JsonElement): Boolean = when (element) {
        JsonNull -> false
        is JsonPrimitive -> if (element.isString) element.content.isNotEmpty() else element.content != "false" && element.content != "0"
        else -> true
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringToMultiLineHtml(text: String): String = text.split('\n').joinToString("<br>")

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString() else value.toString()

    /**
     * Escapes only `<` and `>`, matching the original's `_escapeExpression`, which it applies to
     * plain-text glossary items. Like the original, it leaves `&` unescaped.
     */
    private fun escapeAngleBrackets(text: String): String = text.replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeText(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\u00A0", "&nbsp;")

    private fun escapeAttribute(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("\u00A0", "&nbsp;")

    private fun languageFromText(text: String): String? {
        var partiallyChinese = false
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            if (isJapaneseCodePoint(codePoint)) return "ja"
            if (isChineseCodePoint(codePoint)) partiallyChinese = true
        }
        return if (partiallyChinese) "zh" else null
    }

    private val cjkIdeographRanges = listOf(
        0x4E00..0x9FFF, 0x3400..0x4DBF, 0x20000..0x2A6DF, 0x2A700..0x2B73F, 0x2B740..0x2B81F,
        0x2B820..0x2CEAF, 0x2CEB0..0x2EBEF, 0x30000..0x3134F, 0x31350..0x323AF, 0x2EBF0..0x2EE5F,
        0xF900..0xFAFF, 0x2F800..0x2FA1F
    )
    private val fullwidthRanges = listOf(
        0xFF10..0xFF19, 0xFF21..0xFF3A, 0xFF41..0xFF5A, 0xFF01..0xFF0F, 0xFF1A..0xFF1F,
        0xFF3B..0xFF3F, 0xFF5B..0xFF60, 0xFFE0..0xFFEE
    )
    private val japaneseRanges = listOf(0x3040..0x309F, 0x30A0..0x30FF) + cjkIdeographRanges +
            listOf(0xFF66..0xFF9F, 0x30FB..0x30FC, 0xFF61..0xFF65, 0x3000..0x303F) + fullwidthRanges
    private val chineseRanges = cjkIdeographRanges + listOf(0x3000..0x303F) + fullwidthRanges +
            listOf(0x3100..0x312F, 0x31A0..0x31BF, 0x16FE0..0x16FFF, 0xFE50..0xFE6F, 0xFE10..0xFE1F)

    private fun isJapaneseCodePoint(codePoint: Int): Boolean = japaneseRanges.any { codePoint in it }

    private fun isChineseCodePoint(codePoint: Int): Boolean = chineseRanges.any { codePoint in it }
}
