package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected strings follow the original Yomitan's formatGlossary / formatGlossaryPlain
 * helpers (ext/js/templates/anki-template-renderer.js) applied to the DOM its
 * StructuredContentGenerator builds.
 */
class AnkiGlossaryHtmlConverterTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun html(raw: String) = AnkiGlossaryHtmlConverter.convertGlossaryEntry(json.parseToJsonElement(raw))
    private fun plain(raw: String) = AnkiGlossaryHtmlConverter.convertGlossaryEntryPlain(json.parseToJsonElement(raw))

    private val tableCellStyle =
        "border-style:solid;padding:0.25em;vertical-align:top;border-width:1px;border-color:currentColor;"

    @Test
    fun testPlainStringEntryEscapesOnlyAngleBracketsWithNewlinesAsBreaks() {
        // formatGlossary escapes only < and > in string glossary items; & passes through.
        assertEquals("a & b &lt; c &gt; d", html("\"a & b < c > d\""))
        assertEquals("&lt;n&gt;<br>A&lt;-&gt;B", html("\"<n>\\nA<->B\""))
        assertEquals("line1<br>line2", html("\"line1\\nline2\""))
    }

    @Test
    fun testTextTypeEntryUsesMultiLineHtml() {
        assertEquals("one<br>two", html("""{"type": "text", "text": "one\ntwo"}"""))
    }

    @Test
    fun testTextTypeEntryEscapesOnlyAngleBrackets() {
        assertEquals("&lt;n&gt; &amp;<br>x", html("""{"type": "text", "text": "<n> &amp;\nx"}"""))
    }

    @Test
    fun testStructuredContentIsWrappedInSpan() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "ul", "content": [
              {"tag": "li", "content": "first"},
              {"tag": "li", "content": "second"}
            ]}}
        """.trimIndent()
        assertEquals("<span><ul><li>first</li><li>second</li></ul></span>", html(raw))
    }

    @Test
    fun testJapaneseTextGetsLangAttributeOnItsContainer() {
        val raw = """{"type": "structured-content", "content": {"tag": "span", "content": "日本"}}"""
        assertEquals("<span><span lang=\"ja\">日本</span></span>", html(raw))
    }

    @Test
    fun testExplicitLangSuppressesAutomaticDetection() {
        val raw =
            """{"type": "structured-content", "content": {"tag": "span", "lang": "en", "content": {"tag": "span", "content": "日本"}}}"""
        assertEquals("<span><span lang=\"en\"><span>日本</span></span></span>", html(raw))
    }

    @Test
    fun testDataAttributesAndInlineStyle() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "div", "data": {"content": "glossary"},
              "style": {"fontWeight": "bold", "marginTop": 0.5}, "content": "x"}}
        """.trimIndent()
        assertEquals(
            "<span><div data-sc-content=\"glossary\" style=\"font-weight: bold; margin-top: 0.5em;\">x</div></span>",
            html(raw)
        )
    }

    @Test
    fun testNewlinesInsideStructuredTextBecomeBreaks() {
        val raw = """{"type": "structured-content", "content": {"tag": "div", "content": "a\nb"}}"""
        assertEquals("<span><div>a<br>b</div></span>", html(raw))
    }

    @Test
    fun testStructuredTextIsEscapedLikeInnerHtml() {
        val raw = """{"type": "structured-content", "content": {"tag": "span", "content": "a & \"b\" < c"}}"""
        assertEquals("<span><span>a &amp; \"b\" &lt; c</span></span>", html(raw))
    }

    @Test
    fun testExternalLinkHasTextSpanAndHiddenIcon() {
        val raw =
            """{"type": "structured-content", "content": {"tag": "a", "href": "https://example.com", "content": "link"}}"""
        assertEquals(
            "<span><a href=\"https://example.com\"><span>link</span><span style=\"display:none;\"></span></a></span>",
            html(raw)
        )
    }

    @Test
    fun testInternalLinkPointsToHash() {
        val raw = """{"type": "structured-content", "content": {"tag": "a", "href": "?query=x", "content": "x"}}"""
        assertEquals("<span><a href=\"#\"><span>x</span></a></span>", html(raw))
    }

    @Test
    fun testAnchorHrefWithQuoteIsEscaped() {
        val raw =
            """{"type": "structured-content", "content": {"tag": "a", "href": "https://example.com?q=\"test\"", "content": "link"}}"""
        assertEquals(
            "<span><a href=\"https://example.com?q=&quot;test&quot;\"><span>link</span><span style=\"display:none;\"></span></a></span>",
            html(raw)
        )
    }

    @Test
    fun testUnknownTagIsDropped() {
        // The original's _createStructuredContentGenericElement returns null for tags it does
        // not know, so the element and everything inside it is omitted.
        val raw = """{"type": "structured-content", "content": {"tag": "video", "content": "hidden note"}}"""
        assertEquals("<span></span>", html(raw))
    }

    @Test
    fun testTableGetsContainerAndClassStyles() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "table", "content":
              {"tag": "tr", "content": {"tag": "td", "content": "merged", "colSpan": 2, "rowSpan": 3}}
            }}
        """.trimIndent()
        assertEquals(
            "<span><div style=\"display:block;\"><table style=\"table-layout:auto;border-collapse:collapse;\">" +
                    "<tr><td colspan=\"2\" rowspan=\"3\" style=\"$tableCellStyle\">merged</td></tr></table></div></span>",
            html(raw)
        )
    }

    @Test
    fun testTfootIsBold() {
        val raw = """
            {"type": "structured-content", "content":
              {"tag": "tfoot", "content": {"tag": "tr", "content": {"tag": "th", "content": "footer"}}}
            }
        """.trimIndent()
        assertEquals(
            "<span><tfoot style=\"font-weight:bold;\"><tr><th style=\"font-weight:bold;$tableCellStyle\">footer</th></tr></tfoot></span>",
            html(raw)
        )
    }

    @Test
    fun testDetailsAndSummaryArePreserved() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "details", "content": [
              {"tag": "summary", "content": "spoiler summary"},
              "hidden content"
            ]}}
        """.trimIndent()
        assertEquals(
            "<span><details><summary>spoiler summary</summary>hidden content</details></span>",
            html(raw)
        )
    }

    @Test
    fun testImagePreservesWidthAndHeight() {
        val rendered =
            html("""{"type": "structured-content", "content": {"tag": "img", "path": "img/test.png", "width": 32, "height": 32}}""")
        assertTrue("Expected width=\"32\" in: $rendered", rendered.contains("width=\"32\""))
        assertTrue("Expected height=\"32\" in: $rendered", rendered.contains("height=\"32\""))
    }

    @Test
    fun testPlainStringEscapesOnlyAngleBrackets() {
        assertEquals("a & b\nc", plain("\"a & b\\nc\""))
        assertEquals("A&lt;-&gt;B", plain("\"A<->B\""))
    }

    @Test
    fun testPlainTextTypeEscapesOnlyAngleBrackets() {
        assertEquals("&lt;n&gt; & x", plain("""{"type": "text", "text": "<n> & x"}"""))
    }

    @Test
    fun testPlainExtractsGlossaryTaggedNodes() {
        val raw = """
            {"type": "structured-content", "content": [{"tag": "ul", "content": [
              {"tag": "li", "data": {"content": "glossary"}, "content": "to eat"},
              {"tag": "li", "data": {"content": "glossary"}, "content": ["to ", {"tag": "span", "content": "live"}]}
            ]}, {"tag": "div", "data": {"content": "notes"}, "content": "ignored"}]}
        """.trimIndent()
        assertEquals("to eat<br>\nto <br>\nlive", plain(raw))
    }

    @Test
    fun testPlainFallsBackToTextOfWholeContent() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "div", "content": [
              "食べる", {"tag": "ruby", "content": ["字", {"tag": "rt", "content": "じ"}]},
              {"tag": "ul", "content": [{"tag": "li", "content": "one"}, {"tag": "li", "content": "two"}]}
            ]}}
        """.trimIndent()
        assertEquals("食べる 字[じ]<br>\none<br>\ntwo", plain(raw))
    }

    /**
     * Intentional deviation from the original, which writes `&rt;` (not a real entity) for a
     * `>` left in the flattened text. A `>` inside an attribute value is the case that leaves
     * one behind, because the tag-stripping regexes stop at it. This port writes `&gt;`.
     */
    @Test
    fun testPlainEscapesLeftoverGreaterThanAsGtEntityNotRt() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "span", "data": {"foo": "x>y"}, "content": "text"}}
        """.trimIndent()
        val result = plain(raw)
        assertEquals("y\"&gt;text", result)
        assertFalse("Must not emit the original's &rt; typo: $result", result.contains("&rt;"))
    }

    @Test
    fun testPlainEscapesTextGreaterThanAndLessThanInContent() {
        val raw = """
            {"type": "structured-content", "content": {"tag": "div", "content": "a > b < c"}}
        """.trimIndent()
        assertEquals("a &gt; b &lt; c", plain(raw))
    }
}
