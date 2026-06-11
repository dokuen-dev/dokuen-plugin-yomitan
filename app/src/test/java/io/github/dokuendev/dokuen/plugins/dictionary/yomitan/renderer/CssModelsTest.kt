package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssSelectorTest {

    @Test
    fun `specificity calculation for tag selector`() {
        val selector = CssSelector(tag = "div")
        assertEquals(1, selector.specificity())
    }

    @Test
    fun `specificity calculation for single class selector`() {
        val selector = CssSelector(classes = setOf("tag"))
        assertEquals(10, selector.specificity())
    }

    @Test
    fun `specificity calculation for multiple class selectors`() {
        val selector = CssSelector(classes = setOf("tag", "important"))
        assertEquals(20, selector.specificity())
    }

    @Test
    fun `specificity calculation for single attribute selector`() {
        val selector = CssSelector(dataAttributes = mapOf("content" to "sense-group"))
        assertEquals(10, selector.specificity())
    }

    @Test
    fun `specificity calculation for multiple attribute selectors`() {
        val selector = CssSelector(
            dataAttributes = mapOf(
                "content" to "sense-group",
                "example" to "true"
            )
        )
        assertEquals(20, selector.specificity())
    }

    @Test
    fun `specificity calculation for compound selector`() {
        val selector = CssSelector(
            tag = "div",
            classes = setOf("tag"),
            dataAttributes = mapOf("category" to "partOfSpeech")
        )
        assertEquals(21, selector.specificity()) // 1 (tag) + 10 (class) + 10 (attribute)
    }

    @Test
    fun `matches tag only selector`() {
        val selector = CssSelector(tag = "div")
        assertTrue(selector.matches("div", emptyMap(), emptySet()))
        assertFalse(selector.matches("span", emptyMap(), emptySet()))
    }

    @Test
    fun `matches class only selector`() {
        val selector = CssSelector(classes = setOf("tag"))
        assertTrue(selector.matches("div", emptyMap(), setOf("tag")))
        assertTrue(selector.matches("div", emptyMap(), setOf("tag", "other")))
        assertFalse(selector.matches("div", emptyMap(), setOf("other")))
        assertFalse(selector.matches("div", emptyMap(), emptySet()))
    }

    @Test
    fun `matches attribute only selector`() {
        val selector = CssSelector(dataAttributes = mapOf("content" to "sense-group"))
        assertTrue(selector.matches("div", mapOf("content" to "sense-group"), emptySet()))
        assertTrue(selector.matches("div", mapOf("content" to "sense-group", "other" to "value"), emptySet()))
        assertFalse(selector.matches("div", mapOf("content" to "glossary"), emptySet()))
        assertFalse(selector.matches("div", emptyMap(), emptySet()))
    }

    @Test
    fun `matches compound selector - all constraints must match`() {
        val selector = CssSelector(
            tag = "span",
            classes = setOf("tag"),
            dataAttributes = mapOf("category" to "partOfSpeech")
        )

        // All match
        assertTrue(
            selector.matches(
                "span",
                mapOf("category" to "partOfSpeech"),
                setOf("tag")
            )
        )

        // Wrong tag
        assertFalse(
            selector.matches(
                "div",
                mapOf("category" to "partOfSpeech"),
                setOf("tag")
            )
        )

        // Missing class
        assertFalse(
            selector.matches(
                "span",
                mapOf("category" to "partOfSpeech"),
                emptySet()
            )
        )

        // Wrong attribute value
        assertFalse(
            selector.matches(
                "span",
                mapOf("category" to "frequency"),
                setOf("tag")
            )
        )
    }

    @Test
    fun `empty selector matches any element`() {
        val selector = CssSelector()
        assertTrue(selector.matches("div", emptyMap(), emptySet()))
        assertTrue(selector.matches("span", mapOf("any" to "value"), setOf("any")))
    }

    @Test
    fun `null tag in selector acts as wildcard`() {
        val selector = CssSelector(
            tag = null,
            dataAttributes = mapOf("content" to "test")
        )
        assertTrue(selector.matches("div", mapOf("content" to "test"), emptySet()))
        assertTrue(selector.matches("span", mapOf("content" to "test"), emptySet()))
        assertTrue(selector.matches("p", mapOf("content" to "test"), emptySet()))
    }
}

class CssRuleTest {

    @Test
    fun `CssRule creation with selector and declarations`() {
        val selector = CssSelector(tag = "div")
        val declarations = mapOf("color" to "#FF0000", "font-size" to "14px")
        val rule = CssRule(selector, declarations)

        assertEquals(selector, rule.selector)
        assertEquals(declarations, rule.declarations)
    }

    @Test
    fun `CssRule with empty declarations`() {
        val selector = CssSelector(tag = "div")
        val rule = CssRule(selector, emptyMap())

        assertEquals(selector, rule.selector)
        assertTrue(rule.declarations.isEmpty())
    }
}

class ResolvedStyleTest {

    @Test
    fun `default ResolvedStyle has correct default values`() {
        val style = ResolvedStyle()

        assertFalse(style.bold)
        assertFalse(style.italic)
        assertEquals(1.0f, style.fontSize, 0.001f)
        assertEquals(0, style.foregroundColor)
        assertEquals(0, style.textBackgroundColor)
        assertEquals(0, style.backgroundColor)
        assertNull(style.listMarker)
        assertEquals(0, style.blockType)
        assertNull(style.hoverText)
    }

    @Test
    fun `ResolvedStyle with custom values`() {
        val style = ResolvedStyle(
            bold = true,
            italic = true,
            fontSize = 1.5f,
            foregroundColor = 0xFF000000.toInt(),
            textBackgroundColor = 0xFFFFFF00.toInt(),
            backgroundColor = 0xFFFF0000.toInt(),
            listMarker = "①",
            blockType = 1,
            hoverText = "Tooltip text"
        )

        assertTrue(style.bold)
        assertTrue(style.italic)
        assertEquals(1.5f, style.fontSize, 0.001f)
        assertEquals(0xFF000000.toInt(), style.foregroundColor)
        assertEquals(0xFFFFFF00.toInt(), style.textBackgroundColor)
        assertEquals(0xFFFF0000.toInt(), style.backgroundColor)
        assertEquals("①", style.listMarker)
        assertEquals(1, style.blockType)
        assertEquals("Tooltip text", style.hoverText)
    }

    @Test
    fun `ResolvedStyle ARGB color values`() {
        val style = ResolvedStyle(
            foregroundColor = 0xFFFF0000.toInt(), // Red with full alpha
            textBackgroundColor = 0x80FFFF00.toInt(), // Yellow with 50% alpha
            backgroundColor = 0x00FF00FF.toInt() // Magenta with 0 alpha (transparent)
        )

        // Verify ARGB format (0xAARRGGBB)
        assertEquals(0xFFFF0000.toInt(), style.foregroundColor)
        assertEquals(0x80FFFF00.toInt(), style.textBackgroundColor)
        assertEquals(0x00FF00FF.toInt(), style.backgroundColor)
    }
}
