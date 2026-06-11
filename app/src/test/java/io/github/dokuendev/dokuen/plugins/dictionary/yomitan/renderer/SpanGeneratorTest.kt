package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SpanGenerator class.
 *
 * Tests the basic text accumulation, index tracking, and newline functionality
 * implemented in SpanGenerator.
 */
class SpanGeneratorTest {

    @Test
    fun `initial state has zero index`() {
        val generator = SpanGenerator()
        assertEquals(0, generator.getCurrentIndex())
    }

    @Test
    fun `appendText increases current index`() {
        val generator = SpanGenerator()
        generator.appendText("hello")
        assertEquals(5, generator.getCurrentIndex())
    }

    @Test
    fun `appendText multiple times accumulates correctly`() {
        val generator = SpanGenerator()
        generator.appendText("hello")
        assertEquals(5, generator.getCurrentIndex())

        generator.appendText(" world")
        assertEquals(11, generator.getCurrentIndex())
    }

    @Test
    fun `appendText with empty string does not change index`() {
        val generator = SpanGenerator()
        generator.appendText("")
        assertEquals(0, generator.getCurrentIndex())

        generator.appendText("test")
        generator.appendText("")
        assertEquals(4, generator.getCurrentIndex())
    }

    @Test
    fun `newline adds single character`() {
        val generator = SpanGenerator()
        generator.newline()
        assertEquals(1, generator.getCurrentIndex())
    }

    @Test
    fun `newline after text increases index by one`() {
        val generator = SpanGenerator()
        generator.appendText("paragraph")
        val beforeNewline = generator.getCurrentIndex()
        generator.newline()
        assertEquals(beforeNewline + 1, generator.getCurrentIndex())
    }

    @Test
    fun `multiple newlines accumulate correctly`() {
        val generator = SpanGenerator()
        generator.newline()
        assertEquals(1, generator.getCurrentIndex())
        generator.newline()
        assertEquals(2, generator.getCurrentIndex())
        generator.newline()
        assertEquals(3, generator.getCurrentIndex())
    }

    @Test
    fun `complex text accumulation scenario`() {
        val generator = SpanGenerator()

        // Simulate building a definition entry
        generator.appendText("Definition: ")
        assertEquals(12, generator.getCurrentIndex())

        generator.appendText("A sample word")
        assertEquals(25, generator.getCurrentIndex())

        generator.newline()
        assertEquals(26, generator.getCurrentIndex())

        generator.appendText("Example: ")
        assertEquals(35, generator.getCurrentIndex())

        generator.appendText("Usage in a sentence.")
        assertEquals(55, generator.getCurrentIndex())
    }

    @Test
    fun `getCurrentIndex reflects actual character count`() {
        val generator = SpanGenerator()

        val text1 = "日本語"  // 3 characters
        generator.appendText(text1)
        assertEquals(3, generator.getCurrentIndex())

        val text2 = "test"  // 4 characters
        generator.appendText(text2)
        assertEquals(7, generator.getCurrentIndex())
    }

    @Test
    fun `appendText preserves whitespace`() {
        val generator = SpanGenerator()
        generator.appendText("   ")
        assertEquals(3, generator.getCurrentIndex())

        generator.appendText("\t")
        assertEquals(4, generator.getCurrentIndex())
    }

    @Test
    fun `whitespace in text is counted correctly`() {
        val generator = SpanGenerator()
        generator.appendText("hello world")
        assertEquals(11, generator.getCurrentIndex())

        generator.appendText("  multiple  spaces  ")
        assertEquals(31, generator.getCurrentIndex())
    }

    @Test
    fun `unicode characters counted as single characters`() {
        val generator = SpanGenerator()

        // Japanese characters
        generator.appendText("こんにちは")  // 5 characters
        assertEquals(5, generator.getCurrentIndex())

        // Emoji (may be counted as 1 or 2 depending on encoding, but should be consistent)
        val beforeEmoji = generator.getCurrentIndex()
        generator.appendText("😀")
        val afterEmoji = generator.getCurrentIndex()
        assertTrue(afterEmoji > beforeEmoji)  // Should increase by at least 1
    }

    @Test
    fun `mixed content types`() {
        val generator = SpanGenerator()

        generator.appendText("English")
        assertEquals(7, generator.getCurrentIndex())

        generator.appendText(" and ")
        assertEquals(12, generator.getCurrentIndex())

        generator.appendText("日本語")
        assertEquals(15, generator.getCurrentIndex())

        generator.newline()
        assertEquals(16, generator.getCurrentIndex())

        generator.appendText("123")
        assertEquals(19, generator.getCurrentIndex())
    }

    @Test
    fun `building definition with paragraphs`() {
        val generator = SpanGenerator()

        // First paragraph
        generator.appendText("First definition paragraph.")
        generator.newline()

        // Second paragraph
        generator.appendText("Second definition paragraph.")
        generator.newline()

        // Should have 2 newlines + text length
        val expectedLength = "First definition paragraph.".length +
                "Second definition paragraph.".length + 2
        assertEquals(expectedLength, generator.getCurrentIndex())
    }

    @Test
    fun `index tracking for span creation preparation`() {
        val generator = SpanGenerator()

        // Simulate tracking where a styled span should be created
        val startIndex = generator.getCurrentIndex()
        assertEquals(0, startIndex)

        generator.appendText("bold text")
        val endIndex = generator.getCurrentIndex()
        assertEquals(9, endIndex)

        // Verify we captured the correct range for a potential StyledSpan
        assertEquals(9, endIndex - startIndex)
    }

    @Test
    fun `index tracking for nested elements`() {
        val generator = SpanGenerator()

        // Outer element starts
        val outerStart = generator.getCurrentIndex()
        generator.appendText("Before ")

        // Inner element starts
        val innerStart = generator.getCurrentIndex()
        generator.appendText("inner")
        val innerEnd = generator.getCurrentIndex()

        // Continue outer element
        generator.appendText(" after")
        val outerEnd = generator.getCurrentIndex()

        // Verify indices
        assertEquals(0, outerStart)
        assertEquals(7, innerStart)
        assertEquals(12, innerEnd)
        assertEquals(18, outerEnd)
    }

    @Test
    fun `getCurrentIndex is consistent with StringBuilder length semantics`() {
        val generator = SpanGenerator()

        // Empty
        assertEquals(0, generator.getCurrentIndex())

        // Add one character
        generator.appendText("a")
        assertEquals(1, generator.getCurrentIndex())

        // Add another character
        generator.appendText("b")
        assertEquals(2, generator.getCurrentIndex())

        // The index always points to where the NEXT character would go
        val nextInsertPosition = generator.getCurrentIndex()
        generator.appendText("c")
        assertEquals(nextInsertPosition + 1, generator.getCurrentIndex())
    }

    // ========================================
    // Tests for Span Creation Methods
    // ========================================

    @Test
    fun `addStyledSpan creates styled span with correct range`() {
        val generator = SpanGenerator()
        generator.appendText("hello world")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFF000000.toInt(),
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add styled span for "hello" (indices 0-5)
        generator.addStyledSpan(0, 5, style)

        // For now, just verify no exception is thrown
        assertEquals(11, generator.getCurrentIndex())
    }

    @Test
    fun `addStyledSpan with overlapping ranges`() {
        val generator = SpanGenerator()
        generator.appendText("bold and italic")

        val boldStyle = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        val italicStyle = InlineStyle(
            bold = false,
            italic = true,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add overlapping styled spans
        generator.addStyledSpan(0, 15, boldStyle)  // entire text
        generator.addStyledSpan(9, 15, italicStyle)  // "italic" part

        // Multiple spans can overlap the same range
        assertEquals(15, generator.getCurrentIndex())
    }

    @Test
    fun `addStyledSpan with link URL`() {
        val generator = SpanGenerator()
        generator.appendText("click here")

        val linkStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFF0000FF.toInt(),
            textBackgroundColor = 0,
            hoverText = "Visit website",
            linkUrl = "lookup:日本"
        )

        generator.addStyledSpan(6, 10, linkStyle)  // "here"
        assertEquals(10, generator.getCurrentIndex())
    }

    @Test
    fun `addBlockSpan creates block span with normal type`() {
        val generator = SpanGenerator()
        generator.appendText("This is a paragraph.")
        generator.newline()

        // Add block span for normal paragraph (blockType = 0)
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = 21,
            blockType = 0,
            backgroundColor = 0,
            listMarker = null,
            indentLevel = 0
        )

        assertEquals(21, generator.getCurrentIndex())
    }

    @Test
    fun `addBlockSpan creates list item with marker`() {
        val generator = SpanGenerator()
        generator.appendText("First item")
        generator.newline()

        // Add block span for list item (blockType = 1) with marker
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = 11,
            blockType = 1,
            backgroundColor = 0,
            listMarker = "①",
            indentLevel = 0
        )

        assertEquals(11, generator.getCurrentIndex())
    }

    @Test
    fun `addBlockSpan with background color`() {
        val generator = SpanGenerator()
        generator.appendText("Highlighted paragraph")
        generator.newline()

        // Add block span with background color
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = 22,
            blockType = 0,
            backgroundColor = 0xFFFFFF00.toInt(),  // Yellow background
            listMarker = null,
            indentLevel = 0
        )

        assertEquals(22, generator.getCurrentIndex())
    }

    @Test
    fun `addBlockSpan with indent level`() {
        val generator = SpanGenerator()
        generator.appendText("Nested list item")
        generator.newline()

        // Add block span with indentation
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = 17,
            blockType = 1,
            backgroundColor = 0,
            listMarker = "•",
            indentLevel = 2
        )

        assertEquals(17, generator.getCurrentIndex())
    }

    @Test
    fun `addBlockSpan for code block`() {
        val generator = SpanGenerator()
        val codeText = "function test() { return true; }"
        generator.appendText(codeText)
        generator.newline()

        val currentIndex = generator.getCurrentIndex()
        val expectedIndex = codeText.length + 1  // +1 for newline

        // Add block span for code block (blockType = 2)
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = currentIndex,
            blockType = 2,
            backgroundColor = 0xFFF5F5F5.toInt(),
            listMarker = null,
            indentLevel = 0
        )

        assertEquals(expectedIndex, currentIndex)
    }

    @Test
    fun `addRubySpan creates ruby annotation`() {
        val generator = SpanGenerator()
        generator.appendText("日本")

        // Add ruby span for pronunciation
        generator.addRubySpan(
            startIndex = 0,
            endIndex = 2,
            rubyText = "にほん"
        )

        assertEquals(2, generator.getCurrentIndex())
    }

    @Test
    fun `addRubySpan for single character`() {
        val generator = SpanGenerator()
        generator.appendText("日")

        // Single character ruby annotation
        generator.addRubySpan(
            startIndex = 0,
            endIndex = 1,
            rubyText = "にち"
        )

        assertEquals(1, generator.getCurrentIndex())
    }

    @Test
    fun `addRubySpan with multiple sequential annotations`() {
        val generator = SpanGenerator()
        generator.appendText("日曜日")

        // Multiple sequential ruby elements
        generator.addRubySpan(0, 1, "にち")
        generator.addRubySpan(1, 2, "よう")
        generator.addRubySpan(2, 3, "び")

        assertEquals(3, generator.getCurrentIndex())
    }

    @Test
    fun `complex scenario with mixed span types`() {
        val generator = SpanGenerator()

        // Build complex content with multiple span types
        generator.appendText("Definition: ")
        val defStart = generator.getCurrentIndex()
        generator.appendText("日本")
        val defEnd = generator.getCurrentIndex()
        generator.appendText(" means Japan")
        generator.newline()

        // Add ruby span for kanji
        generator.addRubySpan(defStart, defEnd, "にほん")

        // Add styled span for entire definition
        val style = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFF333333.toInt(),
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(defStart, generator.getCurrentIndex() - 1, style)

        // Add block span for the paragraph
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = generator.getCurrentIndex(),
            blockType = 0,
            backgroundColor = 0,
            listMarker = null,
            indentLevel = 0
        )

        assertEquals("Definition: 日本 means Japan\n".length, generator.getCurrentIndex())
    }

    @Test
    fun `list with multiple items and markers`() {
        val generator = SpanGenerator()

        // First list item
        val item1Start = generator.getCurrentIndex()
        generator.appendText("First item")
        generator.newline()
        val item1End = generator.getCurrentIndex()
        generator.addBlockSpan(item1Start, item1End, 1, 0, "①", 0)

        // Second list item
        val item2Start = generator.getCurrentIndex()
        generator.appendText("Second item")
        generator.newline()
        val item2End = generator.getCurrentIndex()
        generator.addBlockSpan(item2Start, item2End, 1, 0, "②", 0)

        // Third list item
        val item3Start = generator.getCurrentIndex()
        generator.appendText("Third item")
        generator.newline()
        val item3End = generator.getCurrentIndex()
        generator.addBlockSpan(item3Start, item3End, 1, 0, "③", 0)

        assertTrue(generator.getCurrentIndex() > 0)
    }

    @Test
    fun `styled spans with various InlineStyle properties`() {
        val generator = SpanGenerator()
        generator.appendText("Styled text example")

        // Test bold style
        val boldStyle = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, 6, boldStyle)

        // Test italic style
        val italicStyle = InlineStyle(
            bold = false,
            italic = true,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(7, 11, italicStyle)

        // Test fontSize
        val largeStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.5f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(12, 19, largeStyle)

        assertEquals(19, generator.getCurrentIndex())
    }

    @Test
    fun `ruby span with katakana pronunciation`() {
        val generator = SpanGenerator()
        generator.appendText("東京")

        // Katakana pronunciation (on-reading)
        generator.addRubySpan(0, 2, "トウキョウ")

        assertEquals(2, generator.getCurrentIndex())
    }

    @Test
    fun `empty text with span annotations`() {
        val generator = SpanGenerator()

        // Edge case: create spans on empty text
        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, 0, style)

        generator.addBlockSpan(0, 0, 0, 0, null, 0)
        generator.addRubySpan(0, 0, "")

        assertEquals(0, generator.getCurrentIndex())
    }

    // ========================================
    // Tests for Build Method with Validation
    // ========================================

    @Test
    fun `build creates StyledText with valid spans`() {
        val generator = SpanGenerator()
        generator.appendText("Hello world")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, 5, style)

        val styledText = generator.build()

        assertEquals("Hello world", styledText.text)
        assertNotNull(styledText.styledSpans)
        assertEquals(1, styledText.styledSpans?.size)
    }

    @Test
    fun `build with empty text returns StyledText with no spans`() {
        val generator = SpanGenerator()
        val styledText = generator.build()

        assertEquals("", styledText.text)
        assertNull(styledText.blockSpans)
        assertNull(styledText.styledSpans)
        assertNull(styledText.rubySpans)
    }

    @Test
    fun `build with all span types creates complete StyledText`() {
        val generator = SpanGenerator()
        generator.appendText("日本 means Japan")
        generator.newline()

        // Add ruby span
        generator.addRubySpan(0, 2, "にほん")

        // Add styled span
        val style = InlineStyle(
            bold = false,
            italic = true,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(9, 14, style)

        // Add block span
        generator.addBlockSpan(0, generator.getCurrentIndex(), 0, 0, null, 0)

        val styledText = generator.build()

        assertEquals("日本 means Japan\n", styledText.text)
        assertNotNull(styledText.blockSpans)
        assertNotNull(styledText.styledSpans)
        assertNotNull(styledText.rubySpans)
        assertEquals(1, styledText.blockSpans?.size)
        assertEquals(1, styledText.styledSpans?.size)
        assertEquals(1, styledText.rubySpans?.size)
    }

    @Test
    fun `build validates StyledSpan with endIndex exceeding text length`() {
        val generator = SpanGenerator()
        generator.appendText("short")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        // Add span with endIndex beyond text length (invalid)
        generator.addStyledSpan(0, 10, style)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("Invalid span indices detected"))
        assertTrue(exception.message!!.contains("Text length: 5"))
        assertTrue(exception.message!!.contains("StyledSpan[0] endIndex 10 exceeds text length 5"))
    }

    @Test
    fun `build validates StyledSpan with negative startIndex`() {
        val generator = SpanGenerator()
        generator.appendText("text")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        // Add span with negative startIndex (invalid)
        generator.addStyledSpan(-1, 4, style)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("StyledSpan[0] has negative startIndex: -1"))
    }

    @Test
    fun `build validates StyledSpan with startIndex greater than endIndex`() {
        val generator = SpanGenerator()
        generator.appendText("text content")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        // Add span with startIndex > endIndex (invalid)
        generator.addStyledSpan(10, 5, style)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("StyledSpan[0] has startIndex 10 > endIndex 5"))
    }

    @Test
    fun `build validates BlockSpan with endIndex exceeding text length`() {
        val generator = SpanGenerator()
        generator.appendText("paragraph")

        // Add block span with endIndex beyond text length (invalid)
        generator.addBlockSpan(0, 20, 0, 0, null, 0)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("BlockSpan[0] endIndex 20 exceeds text length 9"))
    }

    @Test
    fun `build validates BlockSpan with negative startIndex`() {
        val generator = SpanGenerator()
        generator.appendText("paragraph")

        // Add block span with negative startIndex (invalid)
        generator.addBlockSpan(-5, 9, 0, 0, null, 0)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("BlockSpan[0] has negative startIndex: -5"))
    }

    @Test
    fun `build validates BlockSpan with startIndex greater than endIndex`() {
        val generator = SpanGenerator()
        generator.appendText("paragraph text")

        // Add block span with startIndex > endIndex (invalid)
        generator.addBlockSpan(10, 3, 0, 0, null, 0)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("BlockSpan[0] has startIndex 10 > endIndex 3"))
    }

    @Test
    fun `build validates RubySpan with endIndex exceeding text length`() {
        val generator = SpanGenerator()
        generator.appendText("日")

        // Add ruby span with endIndex beyond text length (invalid)
        generator.addRubySpan(0, 5, "にち")

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("RubySpan[0] endIndex 5 exceeds text length 1"))
    }

    @Test
    fun `build validates RubySpan with negative startIndex`() {
        val generator = SpanGenerator()
        generator.appendText("日本")

        // Add ruby span with negative startIndex (invalid)
        generator.addRubySpan(-1, 2, "にほん")

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("RubySpan[0] has negative startIndex: -1"))
    }

    @Test
    fun `build validates RubySpan with startIndex greater than endIndex`() {
        val generator = SpanGenerator()
        generator.appendText("日本語")

        // Add ruby span with startIndex > endIndex (invalid)
        generator.addRubySpan(2, 1, "ご")

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        assertTrue(exception.message!!.contains("RubySpan[0] has startIndex 2 > endIndex 1"))
    }

    @Test
    fun `build validates multiple invalid spans and reports all errors`() {
        val generator = SpanGenerator()
        generator.appendText("test")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add multiple invalid spans
        generator.addStyledSpan(-1, 10, style)  // negative start, end exceeds
        generator.addBlockSpan(5, 2, 0, 0, null, 0)  // start > end
        generator.addRubySpan(0, 20, "てすと")  // end exceeds

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        val message = exception.message!!
        // Should contain multiple error messages
        assertTrue(message.contains("StyledSpan[0] has negative startIndex: -1"))
        assertTrue(message.contains("StyledSpan[0] endIndex 10 exceeds text length 4"))
        assertTrue(message.contains("BlockSpan[0] has startIndex 5 > endIndex 2"))
        assertTrue(message.contains("RubySpan[0] endIndex 20 exceeds text length 4"))
    }

    @Test
    fun `build with valid boundary spans succeeds`() {
        val generator = SpanGenerator()
        generator.appendText("exact")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add spans with exact boundary indices (valid)
        generator.addStyledSpan(0, 5, style)  // entire text
        generator.addBlockSpan(0, 5, 0, 0, null, 0)  // entire text
        generator.addRubySpan(0, 5, "えぐざくと")  // entire text

        val styledText = generator.build()

        assertEquals("exact", styledText.text)
        assertEquals(1, styledText.styledSpans?.size)
        assertEquals(1, styledText.blockSpans?.size)
        assertEquals(1, styledText.rubySpans?.size)
    }

    @Test
    fun `build with zero-length spans succeeds`() {
        val generator = SpanGenerator()
        generator.appendText("content")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add zero-length spans (valid - startIndex == endIndex)
        generator.addStyledSpan(3, 3, style)
        generator.addBlockSpan(3, 3, 0, 0, null, 0)
        generator.addRubySpan(3, 3, "")

        val styledText = generator.build()

        assertEquals("content", styledText.text)
        assertEquals(1, styledText.styledSpans?.size)
        assertEquals(1, styledText.blockSpans?.size)
        assertEquals(1, styledText.rubySpans?.size)
    }

    @Test
    fun `build error message includes diagnostic information`() {
        val generator = SpanGenerator()
        generator.appendText("short")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, 100, style)

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        val message = exception.message!!
        // Verify diagnostic information is included
        assertTrue(message.contains("Invalid span indices detected"))
        assertTrue(message.contains("Text length: 5"))
        assertTrue(message.contains("Total spans:"))
        assertTrue(message.contains("Errors:"))
    }

    @Test
    fun `build with complex valid structure succeeds`() {
        val generator = SpanGenerator()

        // Build a complex structure
        generator.appendText("Header")
        generator.newline()
        val paragraphStart = generator.getCurrentIndex()
        generator.appendText("First paragraph with ")
        val boldStart = generator.getCurrentIndex()
        generator.appendText("bold")
        val boldEnd = generator.getCurrentIndex()
        generator.appendText(" and ")
        val kanjiStart = generator.getCurrentIndex()
        generator.appendText("日本")
        val kanjiEnd = generator.getCurrentIndex()
        generator.appendText(" text.")
        generator.newline()

        // Add spans
        val boldStyle = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(boldStart, boldEnd, boldStyle)

        generator.addRubySpan(kanjiStart, kanjiEnd, "にほん")

        generator.addBlockSpan(0, 7, 0, 0, null, 0)  // Header block
        generator.addBlockSpan(paragraphStart, generator.getCurrentIndex(), 0, 0, null, 0)  // Paragraph block

        val styledText = generator.build()

        assertTrue(styledText.text.isNotEmpty())
        assertEquals(2, styledText.blockSpans?.size)
        assertEquals(1, styledText.styledSpans?.size)
        assertEquals(1, styledText.rubySpans?.size)
    }

    @Test
    fun `build converts empty span lists to null`() {
        val generator = SpanGenerator()
        generator.appendText("plain text")

        // Don't add any spans
        val styledText = generator.build()

        assertEquals("plain text", styledText.text)
        assertNull(styledText.blockSpans)
        assertNull(styledText.styledSpans)
        assertNull(styledText.rubySpans)
    }

    @Test
    fun `build preserves all span properties correctly`() {
        val generator = SpanGenerator()
        generator.appendText("styled content")
        generator.newline()

        // Add spans with specific properties
        val style = InlineStyle(
            bold = true,
            italic = true,
            fontSize = 1.5f,
            foregroundColor = 0xFF0000FF.toInt(),
            textBackgroundColor = 0xFFFFFF00.toInt(),
            hoverText = "Hover tooltip",
            linkUrl = "lookup:test"
        )
        generator.addStyledSpan(0, 6, style)

        generator.addBlockSpan(0, 15, 1, 0xFFEEEEEE.toInt(), "•", 2)
        generator.addRubySpan(7, 14, "こんてんと")

        val styledText = generator.build()

        // Verify spans preserve all properties
        val styledSpan = styledText.styledSpans!![0]
        assertEquals(0, styledSpan.startIndex)
        assertEquals(6, styledSpan.endIndex)
        assertEquals(true, styledSpan.style.bold)
        assertEquals(true, styledSpan.style.italic)
        assertEquals(1.5f, styledSpan.style.fontSize)
        assertEquals("Hover tooltip", styledSpan.style.hoverText)
        assertEquals("lookup:test", styledSpan.style.linkUrl)

        val blockSpan = styledText.blockSpans!![0]
        assertEquals(1, blockSpan.blockType)
        assertEquals("•", blockSpan.listMarker)
        assertEquals(2, blockSpan.indentLevel)

        val rubySpan = styledText.rubySpans!![0]
        assertEquals(7, rubySpan.startIndex)
        assertEquals(14, rubySpan.endIndex)
        assertEquals("こんてんと", rubySpan.rubyText)
    }

    // ========================================
    // Additional Edge Case Tests
    // ========================================

    @Test
    fun `large text accumulation handles thousands of characters`() {
        val generator = SpanGenerator()

        // Accumulate a large amount of text
        repeat(1000) {
            generator.appendText("Line $it with some content to test large accumulation.\n")
        }

        // The index should track correctly even with large text
        assertTrue(generator.getCurrentIndex() > 50000)

        val styledText = generator.build()
        assertTrue(styledText.text.length > 50000)
    }

    @Test
    fun `many overlapping styled spans with different properties`() {
        val generator = SpanGenerator()
        generator.appendText("The quick brown fox jumps over the lazy dog")

        // Add multiple overlapping spans with different styles
        val boldStyle = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val italicStyle = InlineStyle(
            bold = false,
            italic = true,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val colorStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFF0000FF.toInt(),
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val linkStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = "lookup:fox"
        )

        // Overlapping spans covering different ranges
        generator.addStyledSpan(0, 10, boldStyle)  // "The quick "
        generator.addStyledSpan(4, 20, italicStyle)  // "quick brown fox"
        generator.addStyledSpan(10, 25, colorStyle)  // "brown fox jumps"
        generator.addStyledSpan(16, 19, linkStyle)  // "fox"

        val styledText = generator.build()

        assertEquals(4, styledText.styledSpans?.size)

        // Verify all spans are valid
        styledText.styledSpans!!.forEach { span ->
            assertTrue(span.startIndex >= 0)
            assertTrue(span.endIndex <= styledText.text.length)
            assertTrue(span.startIndex <= span.endIndex)
        }
    }

    @Test
    fun `adjacent but non-overlapping spans`() {
        val generator = SpanGenerator()
        generator.appendText("First Second Third")

        val style1 = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val style2 = InlineStyle(
            bold = false,
            italic = true,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val style3 = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.5f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Adjacent spans: [0,5] [6,12] [13,18]
        generator.addStyledSpan(0, 5, style1)    // "First"
        generator.addStyledSpan(6, 12, style2)   // "Second"
        generator.addStyledSpan(13, 18, style3)  // "Third"

        val styledText = generator.build()

        assertEquals("First Second Third", styledText.text)
        assertEquals(3, styledText.styledSpans?.size)

        // Verify spans are properly separated
        val spans = styledText.styledSpans!!
        assertEquals(5, spans[0].endIndex)
        assertEquals(6, spans[1].startIndex)
        assertEquals(12, spans[1].endIndex)
        assertEquals(13, spans[2].startIndex)
    }

    @Test
    fun `all block types are correctly created`() {
        val generator = SpanGenerator()

        // Normal paragraph (blockType=0)
        generator.appendText("Normal paragraph")
        generator.newline()
        generator.addBlockSpan(0, 17, 0, 0, null, 0)

        val p1End = generator.getCurrentIndex()

        // List item (blockType=1)
        generator.appendText("List item")
        generator.newline()
        generator.addBlockSpan(p1End, generator.getCurrentIndex(), 1, 0, "•", 1)

        val p2End = generator.getCurrentIndex()

        // Code block (blockType=2)
        generator.appendText("code block")
        generator.newline()
        generator.addBlockSpan(p2End, generator.getCurrentIndex(), 2, 0xFFF5F5F5.toInt(), null, 0)

        val styledText = generator.build()

        assertEquals(3, styledText.blockSpans?.size)
        assertEquals(0, styledText.blockSpans!![0].blockType)
        assertEquals(1, styledText.blockSpans!![1].blockType)
        assertEquals(2, styledText.blockSpans!![2].blockType)
    }

    @Test
    fun `interleaved ruby and styled spans`() {
        val generator = SpanGenerator()
        generator.appendText("漢字 with furigana")

        // Add ruby span for kanji
        generator.addRubySpan(0, 2, "かんじ")

        // Add styled span that overlaps with ruby span
        val boldStyle = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, 7, boldStyle)  // Covers "漢字 with"

        val styledText = generator.build()

        assertEquals("漢字 with furigana", styledText.text)
        assertEquals(1, styledText.rubySpans?.size)
        assertEquals(1, styledText.styledSpans?.size)

        // Both spans should coexist
        assertEquals(0, styledText.rubySpans!![0].startIndex)
        assertEquals(2, styledText.rubySpans!![0].endIndex)
        assertEquals(0, styledText.styledSpans!![0].startIndex)
        assertEquals(7, styledText.styledSpans!![0].endIndex)
    }

    @Test
    fun `span at exact text boundaries`() {
        val generator = SpanGenerator()
        generator.appendText("exact")

        val textLength = generator.getCurrentIndex()
        assertEquals(5, textLength)

        // Span covering entire text exactly
        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        generator.addStyledSpan(0, textLength, style)
        generator.addBlockSpan(0, textLength, 0, 0, null, 0)
        generator.addRubySpan(0, textLength, "えぐざくと")

        val styledText = generator.build()

        // Should succeed with no validation errors
        assertEquals("exact", styledText.text)
        assertEquals(1, styledText.styledSpans?.size)
        assertEquals(1, styledText.blockSpans?.size)
        assertEquals(1, styledText.rubySpans?.size)
    }

    @Test
    fun `multiple validation errors with detailed messages`() {
        val generator = SpanGenerator()
        generator.appendText("short")

        val style = InlineStyle(
            bold = true,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        // Add multiple different types of invalid spans
        generator.addStyledSpan(-5, 3, style)  // Negative start
        generator.addStyledSpan(2, 100, style)  // End exceeds
        generator.addBlockSpan(10, 5, 0, 0, null, 0)  // Start > end
        generator.addRubySpan(-2, 50, "test")  // Negative start AND end exceeds

        val exception = assertThrows(IllegalStateException::class.java) {
            generator.build()
        }

        val message = exception.message!!

        // Verify comprehensive error reporting
        assertTrue(message.contains("Invalid span indices detected"))
        assertTrue(message.contains("Text length: 5"))
        assertTrue(message.contains("StyledSpan[0] has negative startIndex: -5"))
        assertTrue(message.contains("StyledSpan[1] endIndex 100 exceeds text length 5"))
        assertTrue(message.contains("BlockSpan[0] has startIndex 10 > endIndex 5"))
        assertTrue(message.contains("RubySpan[0] has negative startIndex: -2"))
        assertTrue(message.contains("RubySpan[0] endIndex 50 exceeds text length 5"))
        assertTrue(message.contains("Total spans:"))
    }

    @Test
    fun `special characters and symbols in text`() {
        val generator = SpanGenerator()

        // Test various special characters
        generator.appendText("Special: !@#$%^&*()")
        generator.newline()
        generator.appendText("Quotes: \"'`")
        generator.newline()
        generator.appendText("Math: ∑∫√π")
        generator.newline()

        val expectedLength = "Special: !@#$%^&*()".length +
                "Quotes: \"'`".length +
                "Math: ∑∫√π".length + 3  // 3 newlines

        assertEquals(expectedLength, generator.getCurrentIndex())

        val styledText = generator.build()
        assertTrue(styledText.text.contains("!@#$%^&*()"))
        assertTrue(styledText.text.contains("\"'`"))
        assertTrue(styledText.text.contains("∑∫√π"))
    }

    @Test
    fun `empty list marker in BlockSpan is valid`() {
        val generator = SpanGenerator()
        generator.appendText("Item with empty marker")
        generator.newline()

        // List item with empty string marker (not null)
        generator.addBlockSpan(0, generator.getCurrentIndex(), 1, 0, "", 0)

        val styledText = generator.build()

        assertEquals(1, styledText.blockSpans?.size)
        assertEquals("", styledText.blockSpans!![0].listMarker)
    }

    @Test
    fun `all color channels including alpha in InlineStyle`() {
        val generator = SpanGenerator()
        generator.appendText("colored text")

        // Test various ARGB color values
        val style1 = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFFFF0000.toInt(),
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )  // Opaque red
        val style2 = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0x80FF0000.toInt(),
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )  // Semi-transparent red
        val style3 = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0xFFFFFF00.toInt(),
            hoverText = null,
            linkUrl = null
        )  // Yellow background

        generator.addStyledSpan(0, 4, style1)
        generator.addStyledSpan(4, 8, style2)
        generator.addStyledSpan(8, 12, style3)

        val styledText = generator.build()

        assertEquals(3, styledText.styledSpans?.size)
        assertEquals(0xFFFF0000.toInt(), styledText.styledSpans!![0].style.foregroundColor)
        assertEquals(0x80FF0000.toInt(), styledText.styledSpans!![1].style.foregroundColor)
        assertEquals(0xFFFFFF00.toInt(), styledText.styledSpans!![2].style.textBackgroundColor)
    }

    @Test
    fun `deeply nested indent levels`() {
        val generator = SpanGenerator()

        // Simulate deeply nested list structure
        for (level in 0..5) {
            generator.appendText("Item at level $level")
            generator.newline()
            generator.addBlockSpan(
                generator.getCurrentIndex() - "Item at level $level\n".length,
                generator.getCurrentIndex(),
                1,
                0,
                "•",
                level
            )
        }

        val styledText = generator.build()

        assertEquals(6, styledText.blockSpans?.size)

        // Verify indent levels are preserved
        for (i in 0..5) {
            assertEquals(i, styledText.blockSpans!![i].indentLevel)
        }
    }

    @Test
    fun `fontSize scale factors with various values`() {
        val generator = SpanGenerator()
        generator.appendText("Small Normal Large Huge")

        val smallStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 0.8f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val normalStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val largeStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.5f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )
        val hugeStyle = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 2.0f,
            foregroundColor = 0,
            textBackgroundColor = 0,
            hoverText = null,
            linkUrl = null
        )

        generator.addStyledSpan(0, 5, smallStyle)    // "Small"
        generator.addStyledSpan(6, 12, normalStyle)  // "Normal"
        generator.addStyledSpan(13, 18, largeStyle)  // "Large"
        generator.addStyledSpan(19, 23, hugeStyle)   // "Huge"

        val styledText = generator.build()

        assertEquals(4, styledText.styledSpans?.size)
        assertEquals(0.8f, styledText.styledSpans!![0].style.fontSize)
        assertEquals(1.0f, styledText.styledSpans!![1].style.fontSize)
        assertEquals(1.5f, styledText.styledSpans!![2].style.fontSize)
        assertEquals(2.0f, styledText.styledSpans!![3].style.fontSize)
    }

    @Test
    fun `hoverText and linkUrl in same InlineStyle`() {
        val generator = SpanGenerator()
        generator.appendText("interactive link")

        // Span with both hoverText and linkUrl
        val style = InlineStyle(
            bold = false,
            italic = false,
            fontSize = 1.0f,
            foregroundColor = 0xFF0000FF.toInt(),
            textBackgroundColor = 0,
            hoverText = "Click to look up this term",
            linkUrl = "lookup:日本語"
        )

        generator.addStyledSpan(0, 16, style)

        val styledText = generator.build()

        assertEquals(1, styledText.styledSpans?.size)
        assertEquals("Click to look up this term", styledText.styledSpans!![0].style.hoverText)
        assertEquals("lookup:日本語", styledText.styledSpans!![0].style.linkUrl)
    }

    @Test
    fun `sequential ruby spans without gaps`() {
        val generator = SpanGenerator()
        generator.appendText("東京都")

        // Three consecutive ruby spans, no gaps
        generator.addRubySpan(0, 1, "とう")
        generator.addRubySpan(1, 2, "きょう")
        generator.addRubySpan(2, 3, "と")

        val styledText = generator.build()

        assertEquals(3, styledText.rubySpans?.size)

        // Verify they are consecutive with no overlaps or gaps
        assertEquals(0, styledText.rubySpans!![0].startIndex)
        assertEquals(1, styledText.rubySpans!![0].endIndex)
        assertEquals(1, styledText.rubySpans!![1].startIndex)
        assertEquals(2, styledText.rubySpans!![1].endIndex)
        assertEquals(2, styledText.rubySpans!![2].startIndex)
        assertEquals(3, styledText.rubySpans!![2].endIndex)
    }

    @Test
    fun `block span with all properties set`() {
        val generator = SpanGenerator()
        generator.appendText("Complete block span")
        generator.newline()

        // BlockSpan with all properties populated
        generator.addBlockSpan(
            startIndex = 0,
            endIndex = generator.getCurrentIndex(),
            blockType = 1,
            backgroundColor = 0xFFF0F0F0.toInt(),
            listMarker = "①",
            indentLevel = 3
        )

        val styledText = generator.build()

        val blockSpan = styledText.blockSpans!![0]
        assertEquals(0, blockSpan.startIndex)
        assertEquals(20, blockSpan.endIndex)
        assertEquals(1, blockSpan.blockType)
        assertEquals(0xFFF0F0F0.toInt(), blockSpan.backgroundColor)
        assertEquals("①", blockSpan.listMarker)
        assertEquals(3, blockSpan.indentLevel)
    }
}
