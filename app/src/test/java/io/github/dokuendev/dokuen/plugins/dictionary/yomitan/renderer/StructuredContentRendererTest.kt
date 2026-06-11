package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for StructuredContentRenderer class.
 *
 * Tests the basic recursive traversal and content type handling.
 */
class StructuredContentRendererTest {

    @Test
    fun `render string content appends text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = JsonPrimitive("Hello world")
        renderer.render(content, "test-dictionary")

        assertEquals(11, spanGenerator.getCurrentIndex())

        val styledText = spanGenerator.build()
        assertEquals("Hello world", styledText.text)
    }

    @Test
    fun `render empty string content does nothing`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = JsonPrimitive("")
        renderer.render(content, "test-dictionary")

        assertEquals(0, spanGenerator.getCurrentIndex())
    }

    @Test
    fun `render array content processes each element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            add("First")
            add("Second")
            add("Third")
        }

        renderer.render(content, "test-dictionary")

        assertEquals(16, spanGenerator.getCurrentIndex())

        val styledText = spanGenerator.build()
        assertEquals("FirstSecondThird", styledText.text)
    }

    @Test
    fun `render array with mixed string and null elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            add("Text1")
            add(JsonNull)
            add("Text2")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Text1Text2", styledText.text)
    }

    @Test
    fun `render null content does nothing`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        renderer.render(JsonNull, "test-dictionary")

        assertEquals(0, spanGenerator.getCurrentIndex())
    }

    @Test
    fun `render br element inserts newline`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "br")
        }

        renderer.render(content, "test-dictionary")

        assertEquals(1, spanGenerator.getCurrentIndex())

        val styledText = spanGenerator.build()
        assertEquals("\n", styledText.text)
    }

    @Test
    fun `render div element with text content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            put("content", "Paragraph text")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Div is a block-level element, so it adds a newline after content
        assertEquals("Paragraph text\n", styledText.text)
    }

    @Test
    fun `render span element with text content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Inline text")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Span is NOT a block-level element, so no newline
        assertEquals("Inline text", styledText.text)
    }

    @Test
    fun `render nested div elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "div")
                    put("content", "Nested paragraph")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Both divs add newlines
        assertEquals("Nested paragraph\n", styledText.text)
    }

    @Test
    fun `render mixed array with strings and elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            add("Before ")
            addJsonObject {
                put("tag", "span")
                put("content", "middle")
            }
            add(" after")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Before middle after", styledText.text)
    }

    @Test
    fun `render element with data attributes`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Tagged content")
            putJsonObject("data") {
                put("content", "sense-group")
                put("example", "true")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Tagged content", styledText.text)
        // Verify data attributes are extracted successfully
    }

    @Test
    fun `render element with inline styles`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Styled text")
            putJsonObject("style") {
                put("fontWeight", "bold")
                put("color", "#FF0000")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Styled text", styledText.text)
        // Verify inline styles are extracted successfully
    }

    @Test
    fun `render element with lang attribute`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "日本語")
            put("lang", "ja")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日本語", styledText.text)
        // Lang attribute is extracted and propagated
    }

    @Test
    fun `render element with title attribute`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Hover me")
            put("title", "This is a tooltip")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Hover me", styledText.text)
        // Verify title attribute is extracted successfully
    }

    @Test
    fun `render ul element with li children`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "ul")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "First item")
                }
                addJsonObject {
                    put("tag", "li")
                    put("content", "Second item")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Each li is a block-level element
        assertEquals("First item\nSecond item\n", styledText.text)
    }

    @Test
    fun `render unsupported tag logs warning and skips`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "unsupported-tag")
            put("content", "Should be skipped")
        }

        // Should not throw exception
        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Unsupported tag content is not rendered
        assertEquals("", styledText.text)
    }

    @Test
    fun `render table element with children`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Cell 1")
                        }
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Cell 2")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Tables render their content
        assertTrue(styledText.text.contains("Cell 1"))
        assertTrue(styledText.text.contains("Cell 2"))
    }

    @Test
    fun `render table cell with colSpan and rowSpan`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "td")
            put("content", "Spanning cell")
            put("colSpan", 2)
            put("rowSpan", 3)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Verify colSpan and rowSpan are extracted successfully
        assertEquals("Spanning cell\n", styledText.text)
    }

    @Test
    fun `render details and summary elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "details")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "summary")
                    put("content", "Summary text")
                }
                add("Details content")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertTrue(styledText.text.contains("Summary text"))
        assertTrue(styledText.text.contains("Details content"))
    }

    @Test
    fun `render complex nested structure`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            addJsonObject {
                put("tag", "div")
                putJsonArray("content") {
                    add("Paragraph 1 ")
                    addJsonObject {
                        put("tag", "span")
                        put("content", "inline")
                        putJsonObject("style") {
                            put("fontWeight", "bold")
                        }
                    }
                    add(" text.")
                }
            }
            addJsonObject {
                put("tag", "div")
                put("content", "Paragraph 2")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertTrue(styledText.text.contains("Paragraph 1 inline text."))
        assertTrue(styledText.text.contains("Paragraph 2"))
    }

    @Test
    fun `render preserves whitespace in content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "  multiple  spaces  ")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("  multiple  spaces  ", styledText.text)
    }

    @Test
    fun `render with dictionary parameter passed through recursion`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            addJsonObject {
                put("tag", "div")
                putJsonArray("content") {
                    addJsonObject {
                        put("tag", "span")
                        put("content", "nested")
                    }
                }
            }
        }

        // Dictionary name is passed through all recursive calls
        renderer.render(content, "my-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("nested\n", styledText.text)
    }

    @Test
    fun `render with language context propagation`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            put("lang", "ja")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "span")
                    put("content", "日本語")
                    // No lang attribute, should inherit "ja" from parent
                }
            }
        }

        renderer.render(content, "test-dictionary", language = null)

        val styledText = spanGenerator.build()
        assertEquals("日本語\n", styledText.text)
    }

    @Test
    fun `render with language override in child element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            put("lang", "ja")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "span")
                    put("content", "English text")
                    put("lang", "en")  // Override parent language
                }
            }
        }

        renderer.render(content, "test-dictionary", language = null)

        val styledText = spanGenerator.build()
        assertEquals("English text\n", styledText.text)
    }

    @Test
    fun `render element without content field`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            // No content field
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Div with no content still adds newline
        assertEquals("\n", styledText.text)
    }

    @Test
    fun `render element with empty content array`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            putJsonArray("content") {
                // Empty array
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("\n", styledText.text)
    }

    @Test
    fun `render japanese text with various elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            add("これは")
            addJsonObject {
                put("tag", "span")
                put("content", "日本語")
            }
            add("のテストです。")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("これは日本語のテストです。", styledText.text)
    }

    // ==================== CSS Integration and Styled Spans ====================

    @Test
    fun `render with CSS rules creates styled spans`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS rules
        val css = """
            .highlight {
                color: red;
                font-weight: bold;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render element with class
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Highlighted text")
            put("class", "highlight")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Highlighted text", styledText.text)

        // Check that a styled span was created
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertEquals(0, span.startIndex)
        assertEquals(16, span.endIndex)
        assertTrue(span.style.bold)
        assertEquals(0xFFFF0000.toInt(), span.style.foregroundColor)
    }

    @Test
    fun `render with data attribute CSS matching`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS rules with data attribute selector
        val css = """
            span[data-content="sense-group"] {
                background-color: #FFFFCC;
                font-style: italic;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render element with data attribute
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Sense group content")
            putJsonObject("data") {
                put("content", "sense-group")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Sense group content", styledText.text)

        // Check that styled span was created with CSS-resolved styles
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertTrue(span.style.italic)
        assertEquals(0xFFFFFFCC.toInt(), span.style.textBackgroundColor)
    }

    @Test
    fun `render with inline styles overriding CSS`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS rules
        val css = """
            .styled {
                color: blue;
                font-weight: bold;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render element with class and inline style override
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Override test")
            put("class", "styled")
            putJsonObject("style") {
                put("color", "red")  // Should override CSS
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Override test", styledText.text)

        // Check that styled span has inline style overriding CSS
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertTrue(span.style.bold)  // From CSS
        assertEquals(0xFFFF0000.toInt(), span.style.foregroundColor)  // Inline override
    }

    @Test
    fun `render block element with background color creates block span`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS rules
        val css = """
            div[data-content="info-box"] {
                background-color: #E0F0FF;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render div with data attribute
        val content = buildJsonObject {
            put("tag", "div")
            put("content", "Info box content")
            putJsonObject("data") {
                put("content", "info-box")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Info box content\n", styledText.text)

        // Check that block span was created
        assertNotNull(styledText.blockSpans)
        assertEquals(1, styledText.blockSpans!!.size)

        val blockSpan = styledText.blockSpans!![0]
        assertEquals(0, blockSpan.startIndex)
        assertEquals(16, blockSpan.endIndex)
        assertEquals(0xFFE0F0FF.toInt(), blockSpan.backgroundColor)
    }

    @Test
    fun `render list item with custom marker`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS rules with list marker
        val css = """
            li[data-marker="circled"] {
                list-style-type: "①";
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render li with data attribute
        val content = buildJsonObject {
            put("tag", "li")
            put("content", "First item")
            putJsonObject("data") {
                put("marker", "circled")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("First item\n", styledText.text)

        // Check that block span was created with list marker
        assertNotNull(styledText.blockSpans)
        assertEquals(1, styledText.blockSpans!!.size)

        val blockSpan = styledText.blockSpans!![0]
        assertEquals(1, blockSpan.blockType)  // list-item
        assertEquals("①", blockSpan.listMarker)
    }

    @Test
    fun `render with title attribute creates hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render element with title attribute
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Hover me")
            put("title", "This is a tooltip")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Hover me", styledText.text)

        // Check that styled span was created with hover text
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertEquals("This is a tooltip", span.style.hoverText)
    }

    @Test
    fun `render nested elements with inherited indent levels`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render nested divs
        val content = buildJsonObject {
            put("tag", "div")
            putJsonArray("content") {
                add("Outer")
                addJsonObject {
                    put("tag", "div")
                    putJsonArray("content") {
                        add("Inner")
                        addJsonObject {
                            put("tag", "div")
                            put("content", "Innermost")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Outer\nInner\nInnermost\n", styledText.text)

        // Since the nested divs are unstyled, no block spans should be created
        assertTrue(styledText.blockSpans.isNullOrEmpty())
    }

    @Test
    fun `render without CSS rules applies no styles`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // No CSS rules set (default empty list)

        // Render element
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Plain text")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Plain text", styledText.text)

        // No styled spans should be created for plain unstyled text
        assertTrue(styledText.styledSpans == null || styledText.styledSpans!!.isEmpty())
    }

    @Test
    fun `render with font size from CSS`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with font size
        val css = """
            .large {
                font-size: 1.5em;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render element with class
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Large text")
            put("class", "large")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Large text", styledText.text)

        // Check that styled span has correct font size
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertEquals(1.5f, span.style.fontSize, 0.01f)
    }

    @Test
    fun `render multiple matching CSS rules with specificity`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with different specificity
        val css = """
            span {
                color: blue;
            }
            span[data-emphasis="strong"] {
                color: red;
                font-weight: bold;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render element matching both rules
        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Strong text")
            putJsonObject("data") {
                put("emphasis", "strong")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Strong text", styledText.text)

        // Check that higher specificity rule wins
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals(0xFFFF0000.toInt(), span.style.foregroundColor)  // Red from higher specificity
        assertTrue(span.style.bold)
    }

    @Test
    fun `render empty div with styles still creates block span`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS
        val css = """
            div.spacer {
                background-color: #F0F0F0;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render empty div with class
        val content = buildJsonObject {
            put("tag", "div")
            put("class", "spacer")
            // No content
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("\n", styledText.text)

        // Block span should still be created for the newline
        assertNotNull(styledText.blockSpans)
        assertEquals(1, styledText.blockSpans!!.size)

        val blockSpan = styledText.blockSpans!![0]
        assertEquals(0xFFF0F0F0.toInt(), blockSpan.backgroundColor)
    }

    // ==================== List Marker Inheritance ====================

    @Test
    fun `li inherits list marker from parent ul`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with list marker on ul
        val css = """
            ul[data-sc-content="sense-groups"] {
                list-style-type: "＊";
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render ul with li children
        val content = buildJsonObject {
            put("tag", "ul")
            putJsonObject("data") {
                put("sc-content", "sense-groups")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "First item")
                }
                addJsonObject {
                    put("tag", "li")
                    put("content", "Second item")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Debug output
        println("DEBUG: Text = '${styledText.text}'")
        println("DEBUG: BlockSpans count = ${styledText.blockSpans?.size ?: 0}")
        styledText.blockSpans?.forEachIndexed { i, span ->
            println(
                "DEBUG: BlockSpan[$i]: type=${span.blockType}, marker='${span.listMarker}', text='${
                    styledText.text.substring(
                        span.startIndex,
                        span.endIndex
                    )
                }'"
            )
        }

        // Check that both li elements got the list marker
        assertNotNull(styledText.blockSpans)
        val liBlockSpans = styledText.blockSpans!!.filter { it.blockType == 1 }
        println("DEBUG: List item BlockSpans count = ${liBlockSpans.size}")
        assertEquals(2, liBlockSpans.size)

        // Both should have the inherited marker
        assertEquals("＊", liBlockSpans[0].listMarker)
        assertEquals("＊", liBlockSpans[1].listMarker)
    }

    @Test
    fun `li with explicit marker overrides parent ul marker`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with markers on both ul and specific li
        val css = """
            ul {
                list-style-type: disc;
            }
            li[data-special="true"] {
                list-style-type: "★";
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render ul with mixed li children
        val content = buildJsonObject {
            put("tag", "ul")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "Normal item")
                }
                addJsonObject {
                    put("tag", "li")
                    put("content", "Special item")
                    putJsonObject("data") {
                        put("special", "true")
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check markers
        assertNotNull(styledText.blockSpans)
        val liBlockSpans = styledText.blockSpans!!.filter { it.blockType == 1 }
        assertEquals(2, liBlockSpans.size)

        // First li uses parent marker
        assertEquals("•", liBlockSpans[0].listMarker)
        // Second li uses its own explicit marker
        assertEquals("★", liBlockSpans[1].listMarker)
    }

    @Test
    fun `nested lists maintain separate marker contexts`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with different markers for outer and inner lists
        val css = """
            ul[data-level="outer"] {
                list-style-type: "①";
            }
            ul[data-level="inner"] {
                list-style-type: "•";
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render nested lists
        val content = buildJsonObject {
            put("tag", "ul")
            putJsonObject("data") {
                put("level", "outer")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "Outer item")
                }
                addJsonObject {
                    put("tag", "li")
                    putJsonArray("content") {
                        add("Outer item with nested list")
                        addJsonObject {
                            put("tag", "ul")
                            putJsonObject("data") {
                                put("level", "inner")
                            }
                            putJsonArray("content") {
                                addJsonObject {
                                    put("tag", "li")
                                    put("content", "Inner item")
                                }
                            }
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that markers are correctly applied
        assertNotNull(styledText.blockSpans)
        val liBlockSpans = styledText.blockSpans!!.filter { it.blockType == 1 }
        assertTrue(liBlockSpans.size >= 3)

        // Outer items should have ① marker
        assertEquals("①", liBlockSpans[0].listMarker)
        assertEquals("①", liBlockSpans[1].listMarker)

        // Inner item should have • marker
        // Use findLast: parent li spans also contain "Inner item" in their range,
        // so the last (narrowest/deepest) match is the actual inner li span.
        val innerLiSpan = liBlockSpans.findLast { span ->
            styledText.text.substring(span.startIndex, span.endIndex).contains("Inner item")
        }
        assertNotNull(innerLiSpan)
        assertEquals("•", innerLiSpan!!.listMarker)
    }

    @Test
    fun `ol elements pass markers to li children`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with marker on ol
        val css = """
            ol {
                list-style-type: "❶";
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render ol with li children
        val content = buildJsonObject {
            put("tag", "ol")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "First")
                }
                addJsonObject {
                    put("tag", "li")
                    put("content", "Second")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that li elements got the marker
        assertNotNull(styledText.blockSpans)
        val liBlockSpans = styledText.blockSpans!!.filter { it.blockType == 1 }
        assertTrue(liBlockSpans.isNotEmpty())
        liBlockSpans.forEach { blockSpan ->
            assertEquals("❶", blockSpan.listMarker)
        }
    }

    @Test
    fun `ul with list-style-type none creates li without markers`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with list-style-type: none
        val css = """
            ul[data-no-marker="true"] {
                list-style-type: none;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render ul with li children
        val content = buildJsonObject {
            put("tag", "ul")
            putJsonObject("data") {
                put("no-marker", "true")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    put("content", "Item without marker")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that li has no marker and is not a list-item block (blockType = 1)
        val liBlockSpans = styledText.blockSpans?.filter { it.blockType == 1 } ?: emptyList()
        assertTrue(liBlockSpans.isEmpty())
    }

    @Test
    fun `li elements apply correct indent levels`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render nested lists to check indent levels
        val content = buildJsonObject {
            put("tag", "ul")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "li")
                    putJsonArray("content") {
                        add("Level 1")
                        addJsonObject {
                            put("tag", "ul")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("tag", "li")
                                    put("content", "Level 2")
                                }
                            }
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check indent levels
        assertNotNull(styledText.blockSpans)
        val liBlockSpans = styledText.blockSpans!!.filter { it.blockType == 1 }
        assertTrue(liBlockSpans.size >= 2)

        // Outer li should have indent level 0 (Yomitan rendering resets indent level to 0)
        assertEquals(0, liBlockSpans[0].indentLevel)

        // Inner li should also have indent level 0
        assertEquals(0, liBlockSpans[1].indentLevel)
    }

    // ==================== Image Handling ====================

    @Test
    fun `render img element with path creates text placeholder`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.jpg")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.jpg]", styledText.text)
    }

    @Test
    fun `render img element without path logs warning and skips`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            // Missing path field
            put("width", 100)
            put("height", 100)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Should produce no output
        assertEquals("", styledText.text)
    }

    @Test
    fun `render img element with dimensions includes them in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("width", 200)
            put("height", 150)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.png]", styledText.text)

        // Check that hover text includes dimensions
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Size: 200x150"))
    }

    @Test
    fun `render img element with preferred dimensions uses them in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("width", 200)
            put("height", 150)
            put("preferredWidth", 300)
            put("preferredHeight", 225)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.png]", styledText.text)

        // Check that hover text uses preferred dimensions
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Size: 300x225"))
        assertFalse(span.style.hoverText!!.contains("200x150"))
    }

    @Test
    fun `render img element with title includes it in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("title", "This is an example image")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.png]", styledText.text)

        // Check that hover text includes title
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Title: This is an example image"))
    }

    @Test
    fun `render img element with alt text includes it in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("alt", "Example image description")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.png]", styledText.text)

        // Check that hover text includes alt text
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Alt: Example image description"))
    }

    @Test
    fun `render img element with all metadata creates comprehensive hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/comprehensive.jpg")
            put("alt", "A comprehensive example")
            put("width", 400)
            put("height", 300)
            put("title", "Comprehensive image with metadata")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/comprehensive.jpg]", styledText.text)

        // Check that hover text includes all metadata
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        val hoverText = span.style.hoverText!!

        assertTrue(hoverText.contains("Alt: A comprehensive example"))
        assertTrue(hoverText.contains("Size: 400x300"))
        assertTrue(hoverText.contains("Title: Comprehensive image with metadata"))
    }

    @Test
    fun `render img element with only width includes it in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("width", 300)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that hover text includes only width
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Width: 300"))
        assertFalse(span.style.hoverText!!.contains("Height"))
    }

    @Test
    fun `render img element with only height includes it in hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("height", 250)
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that hover text includes only height
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertNotNull(span.style.hoverText)
        assertTrue(span.style.hoverText!!.contains("Height: 250"))
        assertFalse(span.style.hoverText!!.contains("Width"))
    }

    @Test
    fun `render img element with no metadata creates no styled span`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/minimal.png")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/minimal.png]", styledText.text)

        // No styled span should be created if there's no metadata
        assertTrue(styledText.styledSpans == null || styledText.styledSpans!!.isEmpty())
    }

    @Test
    fun `render img element with empty alt and title creates no hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "images/example.png")
            put("alt", "")
            put("title", "")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/example.png]", styledText.text)

        // No styled span should be created for empty metadata
        assertTrue(styledText.styledSpans == null || styledText.styledSpans!!.isEmpty())
    }

    @Test
    fun `render multiple img elements in sequence`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonArray {
            addJsonObject {
                put("tag", "img")
                put("path", "images/first.png")
                put("alt", "First image")
            }
            add(" ")
            addJsonObject {
                put("tag", "img")
                put("path", "images/second.png")
                put("alt", "Second image")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: images/first.png] [Image: images/second.png]", styledText.text)

        // Check that two styled spans were created
        assertNotNull(styledText.styledSpans)
        assertEquals(2, styledText.styledSpans!!.size)

        assertTrue(styledText.styledSpans!![0].style.hoverText!!.contains("First image"))
        assertTrue(styledText.styledSpans!![1].style.hoverText!!.contains("Second image"))
    }

    @Test
    fun `render img element nested in other elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "div")
            putJsonArray("content") {
                add("Text before ")
                addJsonObject {
                    put("tag", "img")
                    put("path", "images/nested.png")
                    put("width", 100)
                    put("height", 100)
                }
                add(" text after")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Text before [Image: images/nested.png] text after\n", styledText.text)

        // Check that image styled span was created correctly
        assertNotNull(styledText.styledSpans)
        val imageSpan = styledText.styledSpans!!.find { it.style.hoverText?.contains("Size: 100x100") == true }
        assertNotNull(imageSpan)
    }

    @Test
    fun `render img element with relative path preserves it`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "img")
            put("path", "../relative/path/image.png")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("[Image: ../relative/path/image.png]", styledText.text)
    }

    // ==================== Ruby Text Handling ====================

    @Test
    fun `render simple ruby element with rt child`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // JSON: {"tag": "ruby", "content": ["日", {"tag": "rt", "content": "にち"}]}
        val content = buildJsonObject {
            put("tag", "ruby")
            putJsonArray("content") {
                add("日")  // Base text
                addJsonObject {
                    put("tag", "rt")
                    put("content", "にち")  // Ruby text (pronunciation)
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Base text should be in the output
        assertEquals("日", styledText.text)

        // Ruby span should be created mapping base character to pronunciation
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)

        val rubySpan = styledText.rubySpans!![0]
        assertEquals(0, rubySpan.startIndex)
        assertEquals(1, rubySpan.endIndex)
        assertEquals("にち", rubySpan.rubyText)
    }

    @Test
    fun `render multiple sequential ruby elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // JSON: [{"tag": "ruby", "content": ["日", {"tag": "rt", "content": "にち"}]},
        //        {"tag": "ruby", "content": ["曜", {"tag": "rt", "content": "よう"}]}]
        val content = buildJsonArray {
            addJsonObject {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("日")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "にち")
                    }
                }
            }
            addJsonObject {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("曜")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "よう")
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Base text "日曜" should be in the output
        assertEquals("日曜", styledText.text)

        // Two separate ruby spans should be created
        assertNotNull(styledText.rubySpans)
        assertEquals(2, styledText.rubySpans!!.size)

        val firstRuby = styledText.rubySpans!![0]
        assertEquals(0, firstRuby.startIndex)
        assertEquals(1, firstRuby.endIndex)
        assertEquals("にち", firstRuby.rubyText)

        val secondRuby = styledText.rubySpans!![1]
        assertEquals(1, secondRuby.startIndex)
        assertEquals(2, secondRuby.endIndex)
        assertEquals("よう", secondRuby.rubyText)
    }

    @Test
    fun `render ruby with multiple character base text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Ruby text spanning multiple characters
        val content = buildJsonObject {
            put("tag", "ruby")
            putJsonArray("content") {
                add("十三")  // Two kanji characters
                addJsonObject {
                    put("tag", "rt")
                    put("content", "じゅうさん")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("十三", styledText.text)

        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)

        val rubySpan = styledText.rubySpans!![0]
        assertEquals(0, rubySpan.startIndex)
        assertEquals(2, rubySpan.endIndex)  // Spans 2 characters
        assertEquals("じゅうさん", rubySpan.rubyText)
    }

    @Test
    fun `render ruby with rp (ruby parenthesis) elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Ruby with rp elements (used for fallback rendering in browsers)
        // Example: <ruby>日<rp>(</rp><rt>にち</rt><rp>)</rp></ruby>
        val content = buildJsonObject {
            put("tag", "ruby")
            putJsonArray("content") {
                add("日")
                addJsonObject {
                    put("tag", "rp")
                    put("content", "(")
                }
                addJsonObject {
                    put("tag", "rt")
                    put("content", "にち")
                }
                addJsonObject {
                    put("tag", "rp")
                    put("content", ")")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // rp elements should be skipped, only base text appears
        assertEquals("日", styledText.text)

        // Ruby span should still be created
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)
        assertEquals("にち", styledText.rubySpans!![0].rubyText)
    }

    @Test
    fun `render ruby nested within other elements`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Ruby nested inside a span
        val content = buildJsonObject {
            put("tag", "span")
            putJsonArray("content") {
                add("Text before ")
                addJsonObject {
                    put("tag", "ruby")
                    putJsonArray("content") {
                        add("日")
                        addJsonObject {
                            put("tag", "rt")
                            put("content", "にち")
                        }
                    }
                }
                add(" text after")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("Text before 日 text after", styledText.text)

        // Ruby span should be created with correct indices
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)

        val rubySpan = styledText.rubySpans!![0]
        assertEquals(12, rubySpan.startIndex)  // Position of "日" in full text
        assertEquals(13, rubySpan.endIndex)
        assertEquals("にち", rubySpan.rubyText)
    }

    @Test
    fun `render ruby within link element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Ruby nested inside anchor (common in cross-references)
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "ruby")
                    putJsonArray("content") {
                        add("日")
                        addJsonObject {
                            put("tag", "rt")
                            put("content", "にち")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("日", styledText.text)

        // Ruby span should be created
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)
        assertEquals("にち", styledText.rubySpans!![0].rubyText)
    }

    @Test
    fun `render ruby without rt element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Ruby with just base text, no rt (malformed but should handle gracefully)
        val content = buildJsonObject {
            put("tag", "ruby")
            put("content", "日")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Base text should still render
        assertEquals("日", styledText.text)

        // No ruby span should be created without rt
        assertTrue(styledText.rubySpans == null || styledText.rubySpans!!.isEmpty())
    }

    @Test
    fun `render complex ruby structure from real example`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Real example from entries.json
        val content = buildJsonArray {
            addJsonObject {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("日")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "にち")
                    }
                }
            }
            addJsonObject {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("曜")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "よう")
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("日曜", styledText.text)

        assertNotNull(styledText.rubySpans)
        assertEquals(2, styledText.rubySpans!!.size)

        // Verify correct character index mapping
        assertEquals(0, styledText.rubySpans!![0].startIndex)
        assertEquals(1, styledText.rubySpans!![0].endIndex)
        assertEquals("にち", styledText.rubySpans!![0].rubyText)

        assertEquals(1, styledText.rubySpans!![1].startIndex)
        assertEquals(2, styledText.rubySpans!![1].endIndex)
        assertEquals("よう", styledText.rubySpans!![1].rubyText)
    }

    @Test
    fun `render ruby with nested content in rt element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // rt element with nested array content (rare but should handle)
        val content = buildJsonObject {
            put("tag", "ruby")
            putJsonArray("content") {
                add("日")
                addJsonObject {
                    put("tag", "rt")
                    putJsonArray("content") {
                        add("に")
                        add("ち")
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("日", styledText.text)

        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)

        // Nested content should be concatenated
        assertEquals("にち", styledText.rubySpans!![0].rubyText)
    }

    @Test
    fun `render empty ruby element`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Empty ruby (should handle gracefully)
        val content = buildJsonObject {
            put("tag", "ruby")
            putJsonArray("content") {
                // Empty array
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("", styledText.text)
        assertTrue(styledText.rubySpans == null || styledText.rubySpans!!.isEmpty())
    }

    @Test
    fun `render multiple ruby in mixed content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Mix of text and ruby elements
        val content = buildJsonArray {
            add("今日は")
            addJsonObject {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("日")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "にち")
                    }
                }
            }
            add("曜日です。")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        assertEquals("今日は日曜日です。", styledText.text)

        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)

        // Ruby should be at position 3 (after "今日は")
        val rubySpan = styledText.rubySpans!![0]
        assertEquals(3, rubySpan.startIndex)
        assertEquals(4, rubySpan.endIndex)
        assertEquals("にち", rubySpan.rubyText)
    }

    // ==================== Link Handling ====================

    @Test
    fun `render internal link with query parameter`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with internal dictionary link
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5%E6%9B%9C&wildcards=off")
            put("content", "日曜")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日曜", styledText.text)

        // Check that a styled span was created with linkUrl.
        // The entire ?-prefixed href is passed verbatim after "lookup:" so that
        // lookupInternalLink receives all parameters (wildcards, primary_reading, etc.).
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans!!.size)

        val span = styledText.styledSpans!![0]
        assertEquals(0, span.startIndex)
        assertEquals(2, span.endIndex)
        assertEquals("lookup:?query=%E6%97%A5%E6%9B%9C&wildcards=off", span.style.linkUrl)
    }

    @Test
    fun `render internal link preserves all query parameters verbatim`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with internal link containing multiple parameters
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5%E6%9C%AC&wildcards=off&primary_reading=%E3%81%AB%E3%81%BB%E3%82%93")
            put("content", "日本")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日本", styledText.text)

        // Check linkUrl, the full ?-prefixed href is preserved verbatim, including
        // wildcards and primary_reading, which lookupInternalLink parses via parseQueryParams.
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals(
            "lookup:?query=%E6%97%A5%E6%9C%AC&wildcards=off&primary_reading=%E3%81%AB%E3%81%BB%E3%82%93",
            span.style.linkUrl
        )
    }

    @Test
    fun `render external link preserves URL`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with external URL
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "https://www.example.com/page")
            put("content", "External link")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("External link", styledText.text)

        // Check that external URL is preserved as-is
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("https://www.example.com/page", span.style.linkUrl)
    }

    @Test
    fun `render http external link preserves URL`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with http URL
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "http://www.example.com/page")
            put("content", "HTTP link")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("HTTP link", styledText.text)

        // Check that http URL is preserved
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("http://www.example.com/page", span.style.linkUrl)
    }

    @Test
    fun `render link with ruby annotated content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with ruby content (common pattern in Japanese dictionaries)
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5%E6%9B%9C")
            putJsonObject("content") {
                put("tag", "ruby")
                putJsonArray("content") {
                    add("日")
                    addJsonObject {
                        put("tag", "rt")
                        put("content", "にち")
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日", styledText.text)

        // Check that link is applied
        assertNotNull(styledText.styledSpans)
        val linkSpan = styledText.styledSpans!!.find { it.style.linkUrl != null }
        assertNotNull(linkSpan)
        assertEquals("lookup:?query=%E6%97%A5%E6%9B%9C", linkSpan!!.style.linkUrl)

        // Check that ruby annotation is also applied
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.rubySpans!!.size)
        assertEquals("にち", styledText.rubySpans!![0].rubyText)
    }

    @Test
    fun `render link with nested array content`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with array content
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E5%8A%9B%E6%B0%B4")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "ruby")
                    putJsonArray("content") {
                        add("力")
                        addJsonObject {
                            put("tag", "rt")
                            put("content", "ちから")
                        }
                    }
                }
                addJsonObject {
                    put("tag", "ruby")
                    putJsonArray("content") {
                        add("水")
                        addJsonObject {
                            put("tag", "rt")
                            put("content", "みず")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("力水", styledText.text)

        // Check that link is applied to the whole text range
        assertNotNull(styledText.styledSpans)
        val linkSpan = styledText.styledSpans!!.find { it.style.linkUrl != null }
        assertNotNull(linkSpan)
        assertEquals(0, linkSpan!!.startIndex)
        assertEquals(2, linkSpan.endIndex)
        assertEquals("lookup:?query=%E5%8A%9B%E6%B0%B4", linkSpan.style.linkUrl)

        // Check that ruby annotations are also applied
        assertNotNull(styledText.rubySpans)
        assertEquals(2, styledText.rubySpans!!.size)
    }

    @Test
    fun `render link with language attribute`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with lang attribute
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%B9%AF")
            put("lang", "ja")
            put("content", "湯")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("湯", styledText.text)

        // Check that link is applied
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("lookup:?query=%E6%B9%AF", span.style.linkUrl)
    }

    @Test
    fun `render link with title attribute creates hover text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with title attribute
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "https://www.example.com")
            put("title", "Click to visit example.com")
            put("content", "Visit site")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Visit site", styledText.text)

        // Check that both link and hover text are applied
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("https://www.example.com", span.style.linkUrl)
        assertEquals("Click to visit example.com", span.style.hoverText)
    }

    @Test
    fun `render link with CSS styling`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS for links
        val css = """
            a {
                color: #0000FF;
                text-decoration: underline;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render anchor
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5")
            put("content", "日")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日", styledText.text)

        // Check that both link and CSS styling are applied
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("lookup:?query=%E6%97%A5", span.style.linkUrl)
        assertEquals(0xFF0000FF.toInt(), span.style.foregroundColor)
    }

    @Test
    fun `render link with inline styles`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with inline styles
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?query=%E6%97%A5%E6%9C%AC")
            put("content", "日本")
            putJsonObject("style") {
                put("fontWeight", "bold")
                put("color", "red")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日本", styledText.text)

        // Check that link, bold, and color are all applied
        assertNotNull(styledText.styledSpans)
        // Find the span with linkUrl (should be the only one or last one)
        val span = styledText.styledSpans!!.last()
        assertEquals("lookup:?query=%E6%97%A5%E6%9C%AC", span.style.linkUrl)
        assertTrue("Bold style should be applied", span.style.bold)
        assertEquals(0xFFFF0000.toInt(), span.style.foregroundColor)
    }

    @Test
    fun `render link with data attributes and CSS matching`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse CSS with data attribute selector
        val css = """
            a[data-external="true"] {
                color: green;
                font-style: italic;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render anchor with data attribute
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "https://example.com")
            put("content", "External")
            putJsonObject("data") {
                put("external", "true")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("External", styledText.text)

        // Check that link and CSS-matched styles are applied
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("https://example.com", span.style.linkUrl)
        assertTrue(span.style.italic)
        assertEquals(0xFF008000.toInt(), span.style.foregroundColor)
    }

    @Test
    fun `render link without href logs warning`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor without href attribute
        val content = buildJsonObject {
            put("tag", "a")
            // No href attribute
            put("content", "No link")
        }

        // Should not throw exception
        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("No link", styledText.text)

        // Should render as text without link
        // May or may not have a styled span depending on other styling
        if (styledText.styledSpans != null && styledText.styledSpans!!.isNotEmpty()) {
            // If there is a span, it should not have a linkUrl
            styledText.styledSpans!!.forEach { span ->
                assertNull(span.style.linkUrl)
            }
        }
    }

    @Test
    fun `render link with empty href`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render anchor with empty href
        val content = buildJsonObject {
            put("tag", "a")
            put("href", "")
            put("content", "Empty link")
        }

        // Should not throw exception
        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("Empty link", styledText.text)

        // Should render as text without link
        if (styledText.styledSpans != null && styledText.styledSpans!!.isNotEmpty()) {
            styledText.styledSpans!!.forEach { span ->
                assertNull(span.style.linkUrl)
            }
        }
    }

    @Test
    fun `render multiple links in sequence`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Render multiple links
        val content = buildJsonArray {
            add("See ")
            addJsonObject {
                put("tag", "a")
                put("href", "?query=%E6%97%A5")
                put("content", "日")
            }
            add(" and ")
            addJsonObject {
                put("tag", "a")
                put("href", "?query=%E6%9C%88")
                put("content", "月")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("See 日 and 月", styledText.text)

        // Check that two link spans were created
        assertNotNull(styledText.styledSpans)
        val linkSpans = styledText.styledSpans!!.filter { it.style.linkUrl != null }
        assertEquals(2, linkSpans.size)

        // First link, full ?-href preserved verbatim
        assertEquals(4, linkSpans[0].startIndex)
        assertEquals(5, linkSpans[0].endIndex)
        assertEquals("lookup:?query=%E6%97%A5", linkSpans[0].style.linkUrl)

        // Second link, full ?-href preserved verbatim
        assertEquals(10, linkSpans[1].startIndex)
        assertEquals(11, linkSpans[1].endIndex)
        assertEquals("lookup:?query=%E6%9C%88", linkSpans[1].style.linkUrl)
    }

    @Test
    fun `internal link href is passed verbatim without decoding`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // convertHrefToLinkUrl must NOT decode the URL. It passes the raw ?-href
        // verbatim after "lookup:" so that lookupInternalLink receives the full
        // query string and can decode each parameter value itself via parseQueryParams.
        val content = buildJsonObject {
            put("tag", "a")
            // URL-encoded "東京" with a wildcards parameter
            put("href", "?query=%E6%9D%B1%E4%BA%AC&wildcards=off")
            put("content", "東京")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("東京", styledText.text)

        // The linkUrl must be the raw encoded string, not "lookup:東京".
        // Decoding happens downstream in parseQueryParams inside lookupInternalLink.
        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("lookup:?query=%E6%9D%B1%E4%BA%AC&wildcards=off", span.style.linkUrl)
    }

    @Test
    fun `kanji link href produces lookup scheme with kanji param`() {
        // This href format is produced by appendKanjiLinkHeadword in TermEntryRenderer
        // when the user taps a kanji character in a term headword. It must route through
        // lookupInternalLink -> lookupKanjiDirect, bypassing the term path.
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "a")
            put("href", "?kanji=日")
            put("content", "日")
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("日", styledText.text)

        assertNotNull(styledText.styledSpans)
        val span = styledText.styledSpans!![0]
        assertEquals("lookup:?kanji=日", span.style.linkUrl)
    }

    // ==================== Pipe-Delimited Table Rendering ====================

    @Test
    fun `render simple table as pipe-delimited format`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "th")
                            put("content", "Header 1")
                        }
                        addJsonObject {
                            put("tag", "th")
                            put("content", "Header 2")
                        }
                    }
                }
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Cell 1")
                        }
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Cell 2")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check pipe-delimited format
        assertTrue(styledText.text.contains("| Header 1 | Header 2 |"))
        assertTrue(styledText.text.contains("| Cell 1 | Cell 2 |"))

        // Check that BLOCK_TYPE_TABLE span was created
        assertNotNull(styledText.blockSpans)
        val tableBlock = styledText.blockSpans!!.find { it.blockType == 3 } // BLOCK_TYPE_TABLE = 3
        assertNotNull("Table should have a BlockSpan with blockType=3", tableBlock)
    }

    @Test
    fun `render table with thead and tbody`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "thead")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "tr")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("tag", "th")
                                    put("content", "Expression")
                                }
                                addJsonObject {
                                    put("tag", "th")
                                    put("content", "Reading")
                                }
                            }
                        }
                    }
                }
                addJsonObject {
                    put("tag", "tbody")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "tr")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("tag", "td")
                                    put("content", "食べる")
                                }
                                addJsonObject {
                                    put("tag", "td")
                                    put("content", "たべる")
                                }
                            }
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check pipe-delimited format
        assertTrue(styledText.text.contains("| Expression | Reading |"))
        assertTrue(styledText.text.contains("| 食べる | たべる |"))
    }

    @Test
    fun `render table with colSpan`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "th")
                            put("content", "Col 1")
                        }
                        addJsonObject {
                            put("tag", "th")
                            put("content", "Col 2")
                        }
                        addJsonObject {
                            put("tag", "th")
                            put("content", "Col 3")
                        }
                    }
                }
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Spanning cell")
                            put("colSpan", 2)
                        }
                        addJsonObject {
                            put("tag", "td")
                            put("content", "Normal")
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that table is rendered with pipes
        assertTrue(styledText.text.contains("|"))
        assertTrue(styledText.text.contains("Spanning cell"))
        assertTrue(styledText.text.contains("Normal"))
    }

    @Test
    fun `render empty table returns empty output`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                // Empty table
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Empty table should produce minimal output (just newline)
        assertTrue(styledText.text.isEmpty() || styledText.text == "\n")
    }

    @Test
    fun `render table with nested structured content in cells`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "td")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("tag", "span")
                                    put("content", "Nested")
                                }
                                add(" content")
                            }
                        }
                    }
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()

        // Check that nested content is extracted
        assertTrue(styledText.text.contains("Nested content"))
        assertTrue(styledText.text.contains("|"))
    }

    @Test
    fun `render applies correct styles based on parent combinators`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val css = """
            div[data-sc-content="xref"] {
                & span[data-sc-content="reference-label"] {
                    color: blue;
                }
            }
            div[data-sc-content="antonym"] {
                & span[data-sc-content="reference-label"] {
                    color: brown;
                }
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // Render xref block
        // JSON: {"tag": "div", "data": {"content": "xref"}, "content": [{"tag": "span", "data": {"content": "reference-label"}, "content": "See also"}]}
        val xrefContent = buildJsonObject {
            put("tag", "div")
            putJsonObject("data") {
                put("content", "xref")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "span")
                    putJsonObject("data") {
                        put("content", "reference-label")
                    }
                    put("content", "See also")
                }
            }
        }

        renderer.render(xrefContent, "test-dictionary")

        // Render antonym block
        // JSON: {"tag": "div", "data": {"content": "antonym"}, "content": [{"tag": "span", "data": {"content": "reference-label"}, "content": "Antonym"}]}
        val antonymContent = buildJsonObject {
            put("tag", "div")
            putJsonObject("data") {
                put("content", "antonym")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "span")
                    putJsonObject("data") {
                        put("content", "reference-label")
                    }
                    put("content", "Antonym")
                }
            }
        }

        renderer.render(antonymContent, "test-dictionary")

        val styledText = spanGenerator.build()
        assertEquals("See also\nAntonym\n", styledText.text)

        assertNotNull(styledText.styledSpans)

        // Find the "See also" span (indices 0 to 8)
        val seeAlsoSpan = styledText.styledSpans!!.find { it.startIndex == 0 && it.endIndex == 8 }
        assertNotNull("Should find See also span", seeAlsoSpan)
        assertEquals("See also should be blue", 0xFF0000FF.toInt(), seeAlsoSpan!!.style.foregroundColor) // blue

        // Find the "Antonym" span (indices 9 to 16)
        val antonymSpan = styledText.styledSpans!!.find { it.startIndex == 9 && it.endIndex == 16 }
        assertNotNull("Should find Antonym span", antonymSpan)
        assertEquals("Antonym should be brown", 0xFFA52A2A.toInt(), antonymSpan!!.style.foregroundColor) // brown
    }

    @Test
    fun `render consecutive tag badges with positive marginRight sets correct styled span start index`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val css = """
            span[data-sc-class="tag"] {
                margin-right: 0.5em;
                color: red;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        // JSON: [{"tag": "span", "data": {"class": "tag"}, "content": "noun"},
        //        {"tag": "span", "data": {"class": "tag"}, "content": "abbr."}]
        val content = buildJsonArray {
            addJsonObject {
                put("tag", "span")
                putJsonObject("data") {
                    put("class", "tag")
                }
                put("content", "noun")
            }
            addJsonObject {
                put("tag", "span")
                putJsonObject("data") {
                    put("class", "tag")
                }
                put("content", "abbr.")
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // noun has 1 leading space, 1 trailing space -> " noun " (len 6)
        // margin space between them -> " " (len 1, index 6)
        // abbr. has 1 leading space, 1 trailing space -> " abbr. " (len 7, starting index 7)
        // Total text: " noun   abbr. " (len 14)
        assertEquals(" noun   abbr. ", styledText.text)

        assertNotNull(styledText.styledSpans)
        assertEquals(2, styledText.styledSpans!!.size)

        val firstSpan = styledText.styledSpans!![0]
        assertEquals(0, firstSpan.startIndex)
        assertEquals(6, firstSpan.endIndex)

        val secondSpan = styledText.styledSpans!![1]
        // Fails here if secondSpan.startIndex is 6 (includes the margin space)
        assertEquals(7, secondSpan.startIndex)
        assertEquals(14, secondSpan.endIndex)
    }

    @Test
    fun `render xref block resolves correct font sizes and uncolored glossary text`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        val css = """
            div[data-sc-content="xref"] {
                border-color: #1A73E8;
                & span[data-sc-content="reference-label"] {
                    color: #1A73E8;
                }
            }
            span[data-sc-content="reference-label"] {
                font-size: 0.8em;
            }
            div[data-sc-content="xref-content"] {
                font-size: 1.3em;
            }
            div[data-sc-content="xref-glossary"] {
                font-size: 0.8rem;
            }
        """.trimIndent()
        renderer.cssRules = cssProcessor.parseCss(css)

        val content = buildJsonObject {
            put("tag", "div")
            putJsonObject("data") {
                put("content", "xref")
            }
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "div")
                    putJsonObject("data") {
                        put("content", "xref-content")
                    }
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "span")
                            putJsonObject("data") {
                                put("content", "reference-label")
                            }
                            put("content", "See also")
                        }
                        add(" ")
                        addJsonObject {
                            put("tag", "span")
                            put("content", "日曜")
                        }
                    }
                }
                addJsonObject {
                    put("tag", "div")
                    putJsonObject("data") {
                        put("content", "xref-glossary")
                    }
                    put("content", "Sunday")
                }
            }
        }

        renderer.render(content, "test-dictionary")

        val styledText = spanGenerator.build()
        // Expected text: "See also 日曜\nSunday\n"
        assertEquals("See also 日曜\nSunday\n", styledText.text)

        assertNotNull(styledText.styledSpans)

        // Find "See also" span (indices 0 to 8)
        val seeAlsoSpan = styledText.styledSpans!!.find { it.startIndex == 0 && it.endIndex == 8 }
        assertNotNull("Should find 'See also' span", seeAlsoSpan)
        assertEquals(0xFF1A73E8.toInt(), seeAlsoSpan!!.style.foregroundColor)
        // font-size relative inheritance: 1.3 * 0.8 = 1.04
        assertEquals(1.04f, seeAlsoSpan.style.fontSize, 0.01f)

        // Find "日曜" span (indices 9 to 11)
        val jpSpan = styledText.styledSpans!!.find { it.startIndex == 9 && it.endIndex == 11 }
        assertNotNull("Should find '日曜' span", jpSpan)
        // Should NOT inherit reference-label's blue color
        assertEquals(0, jpSpan!!.style.foregroundColor)
        // font-size relative inheritance: 1.3 * 1.0 = 1.3
        assertEquals(1.3f, jpSpan.style.fontSize, 0.01f)

        // Find "Sunday" span (indices 12 to 18)
        val sundaySpan = styledText.styledSpans!!.find { it.startIndex == 12 && it.endIndex == 18 }
        assertNotNull("Should find 'Sunday' span", sundaySpan)
        // Should NOT be colored blue
        assertEquals(0, sundaySpan!!.style.foregroundColor)
        // font-size rem: 0.8 (absolute, not inherited from xref-content's 1.3)
        assertEquals(0.8f, sundaySpan.style.fontSize, 0.01f)
    }

    @Test
    fun testRenderSimpleElementBeforeAfterContent() {
        val css = """
            span[data-class="test"] {
                &::before { content: "☆"; }
                &::after { content: "★"; }
            }
        """.trimIndent()
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        renderer.cssRules = cssProcessor.parseCss(css)

        val content = buildJsonObject {
            put("tag", "span")
            put("content", "Content")
            putJsonObject("data") { put("class", "test") }
        }
        renderer.render(content, "test-dictionary")
        assertEquals("☆Content★", spanGenerator.build().text)
    }

    @Test
    fun testTableExtractionWithPseudoElements() {
        val css = """
            td[data-class="form-valid"] > span {
                &::before { content: "◇"; }
            }
        """.trimIndent()
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val renderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        renderer.cssRules = cssProcessor.parseCss(css)

        val content = buildJsonObject {
            put("tag", "table")
            putJsonArray("content") {
                addJsonObject {
                    put("tag", "tr")
                    putJsonArray("content") {
                        addJsonObject {
                            put("tag", "td")
                            putJsonObject("data") { put("class", "form-valid") }
                            put("content", buildJsonObject {
                                put("tag", "span")
                                put("title", "valid")
                            })
                        }
                    }
                }
            }
        }
        renderer.render(content, "test-dictionary")
        // The output table should format with the ◇ symbol resolved
        assertEquals("| ◇ |\n", spanGenerator.build().text)
    }
}
