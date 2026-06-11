package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssProcessorTest {

    private val processor = CssProcessor()

    @Test
    fun `parse empty CSS returns empty list`() {
        val rules = processor.parseCss("")
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `parse CSS with only whitespace returns empty list`() {
        val rules = processor.parseCss("   \n  \t  ")
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `parse simple tag selector`() {
        val css = "div { color: red; }"
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("div", rule.selector.tag)
        assertEquals(0, rule.selector.dataAttributes.size)
        assertEquals(0, rule.selector.classes.size)
        assertEquals("red", rule.declarations["color"])
    }

    @Test
    fun `parse tag with data attribute selector`() {
        val css = """span[data-content="foo"] { font-weight: bold; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("span", rule.selector.tag)
        assertEquals("foo", rule.selector.dataAttributes["data-content"])
        assertEquals("bold", rule.declarations["font-weight"])
    }

    @Test
    fun `parse tag with single-quoted attribute value`() {
        val css = """span[data-content='bar'] { color: blue; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("span", rule.selector.tag)
        assertEquals("bar", rule.selector.dataAttributes["data-content"])
    }

    @Test
    fun `parse class selector`() {
        val css = ".tag { background: yellow; }"
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertNull(rule.selector.tag)
        assertTrue(rule.selector.classes.contains("tag"))
        assertEquals("yellow", rule.declarations["background"])
    }

    @Test
    fun `parse compound selector with tag, attribute, and class`() {
        val css = """span[data-sc-class="tag"].highlight { color: red; background: white; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("span", rule.selector.tag)
        assertEquals("tag", rule.selector.dataAttributes["data-sc-class"])
        assertTrue(rule.selector.classes.contains("highlight"))
        assertEquals("red", rule.declarations["color"])
        assertEquals("white", rule.declarations["background"])
    }

    @Test
    fun `parse multiple selectors with comma`() {
        val css = """
            div[data-content="a"],
            span[data-content="b"] {
                margin: 10px;
            }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(2, rules.size)
        assertEquals("div", rules[0].selector.tag)
        assertEquals("a", rules[0].selector.dataAttributes["data-content"])
        assertEquals("10px", rules[0].declarations["margin"])

        assertEquals("span", rules[1].selector.tag)
        assertEquals("b", rules[1].selector.dataAttributes["data-content"])
        assertEquals("10px", rules[1].declarations["margin"])
    }

    @Test
    fun `parse multiple declarations`() {
        val css = """
            .box {
                color: red;
                font-size: 14px;
                font-weight: bold;
                background-color: #FF0000;
            }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("red", rule.declarations["color"])
        assertEquals("14px", rule.declarations["font-size"])
        assertEquals("bold", rule.declarations["font-weight"])
        assertEquals("#FF0000", rule.declarations["background-color"])
    }

    @Test
    fun `parse CSS with comments`() {
        val css = """
            /* This is a comment */
            div { color: blue; }
            /* Another comment
               spanning multiple lines */
            span { font-size: 12px; }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(2, rules.size)
        assertEquals("div", rules[0].selector.tag)
        assertEquals("blue", rules[0].declarations["color"])
        assertEquals("span", rules[1].selector.tag)
        assertEquals("12px", rules[1].declarations["font-size"])
    }

    @Test
    fun `parse declaration with function value`() {
        val css = """div { background: rgb(255, 0, 0); }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertEquals("rgb(255, 0, 0)", rules[0].declarations["background"])
    }

    @Test
    fun `parse declaration with nested function value`() {
        val css = """div { color: color-mix(in srgb, red 50%, blue); }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val value = rules[0].declarations["color"]
        assertTrue(value?.contains("color-mix") == true)
    }

    @Test
    fun `parse list-style-type with quoted string`() {
        val css = """ul[data-sc-content="list"] { list-style-type: "①"; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("ul", rule.selector.tag)
        assertEquals("list", rule.selector.dataAttributes["data-sc-content"])
        // The value should contain the quoted string
        val value = rule.declarations["list-style-type"]
        assertTrue(value?.contains("①") == true)
    }

    @Test
    fun `ignore pseudo-classes in selector`() {
        val css = """li:first-child { margin-top: 0; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertEquals("li", rules[0].selector.tag)
        assertEquals("0", rules[0].declarations["margin-top"])
    }

    @Test
    fun `ignore pseudo-elements in selector`() {
        val css = """div::before { content: "prefix"; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertEquals("div", rules[0].selector.tag)
    }

    @Test
    fun `skip @-rules`() {
        val css = """
            @media (max-width: 600px) {
                div { color: red; }
            }
            span { color: blue; }
        """.trimIndent()
        val rules = processor.parseCss(css)

        // Should only parse the span rule, skipping @media
        assertEquals(1, rules.size)
        assertEquals("span", rules[0].selector.tag)
        assertEquals("blue", rules[0].declarations["color"])
    }

    @Test
    fun `parse multiple rules`() {
        val css = """
            div { color: red; }
            span { font-size: 14px; }
            .tag { background: yellow; }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(3, rules.size)
        assertEquals("div", rules[0].selector.tag)
        assertEquals("span", rules[1].selector.tag)
        assertTrue(rules[2].selector.classes.contains("tag"))
    }

    @Test
    fun `handle declaration without trailing semicolon`() {
        val css = """div { color: red }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertEquals("red", rules[0].declarations["color"])
    }

    @Test
    fun `handle multiple classes in selector`() {
        val css = """.class1.class2.class3 { color: red; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val selector = rules[0].selector
        assertTrue(selector.classes.contains("class1"))
        assertTrue(selector.classes.contains("class2"))
        assertTrue(selector.classes.contains("class3"))
    }

    @Test
    fun `handle multiple data attributes in selector`() {
        val css = """div[data-foo="a"][data-bar="b"] { color: red; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val selector = rules[0].selector
        assertEquals("a", selector.dataAttributes["data-foo"])
        assertEquals("b", selector.dataAttributes["data-bar"])
    }

    @Test
    fun `ignore non-data attributes`() {
        val css = """span[title="hello"][data-content="world"] { color: red; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val selector = rules[0].selector
        // Should only include data-content, not title
        assertEquals(1, selector.dataAttributes.size)
        assertEquals("world", selector.dataAttributes["data-content"])
    }

    @Test
    fun `handle escaped characters in attribute value`() {
        val css = """div[data-content="foo\"bar"] { color: red; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        // The escaped quote should be preserved
        val value = rules[0].selector.dataAttributes["data-content"]
        assertTrue(value?.contains("foo\"bar") == true)
    }

    @Test
    fun `handle CSS with no spaces`() {
        val css = """div{color:red;font-size:14px;}"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertEquals("red", rules[0].declarations["color"])
        assertEquals("14px", rules[0].declarations["font-size"])
    }

    @Test
    fun `handle CSS with excessive whitespace`() {
        val css = """
            div   [  data-content  =  "foo"  ]   .class   {
                color   :   red   ;
                font-size   :   14px   ;
            }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("div", rule.selector.tag)
        assertEquals("foo", rule.selector.dataAttributes["data-content"])
        assertTrue(rule.selector.classes.contains("class"))
    }

    @Test(expected = CssParseException::class)
    fun `throw exception for malformed CSS with unclosed brace`() {
        val css = """div { color: red;"""
        processor.parseCss(css)
    }

    @Test
    fun `handle real-world example from styles_css`() {
        val css = """
            span[data-sc-content="part-of-speech-info"] {
                background-color: #565656;
                color: white;
            }
        """.trimIndent()
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("span", rule.selector.tag)
        assertEquals("part-of-speech-info", rule.selector.dataAttributes["data-sc-content"])
        assertEquals("#565656", rule.declarations["background-color"])
        assertEquals("white", rule.declarations["color"])
    }

    @Test
    fun `handle universal selector`() {
        val css = """* { margin: 0; }"""
        val rules = processor.parseCss(css)

        assertEquals(1, rules.size)
        assertNull(rules[0].selector.tag) // * is treated as "match any tag"
        assertEquals("0", rules[0].declarations["margin"])
    }

    @Test
    fun `specificity calculation for tag selector`() {
        val selector = CssSelector(tag = "div")
        assertEquals(1, selector.specificity())
    }

    @Test
    fun `specificity calculation for class selector`() {
        val selector = CssSelector(classes = setOf("foo"))
        assertEquals(10, selector.specificity())
    }

    @Test
    fun `specificity calculation for attribute selector`() {
        val selector = CssSelector(dataAttributes = mapOf("data-content" to "foo"))
        assertEquals(10, selector.specificity())
    }

    @Test
    fun `specificity calculation for compound selector`() {
        val selector = CssSelector(
            tag = "div",
            dataAttributes = mapOf("data-foo" to "a", "data-bar" to "b"),
            classes = setOf("class1", "class2")
        )
        // 1 (tag) + 20 (2 attributes) + 20 (2 classes) = 41
        assertEquals(41, selector.specificity())
    }

    @Test
    fun `selector matching with tag only`() {
        val selector = CssSelector(tag = "div")
        assertTrue(selector.matches("div", emptyMap(), emptySet()))
        assertFalse(selector.matches("span", emptyMap(), emptySet()))
    }

    @Test
    fun `selector matching with data attribute`() {
        val selector = CssSelector(
            tag = "div",
            dataAttributes = mapOf("data-content" to "foo")
        )
        assertTrue(selector.matches("div", mapOf("data-content" to "foo"), emptySet()))
        assertFalse(selector.matches("div", mapOf("data-content" to "bar"), emptySet()))
        assertFalse(selector.matches("div", emptyMap(), emptySet()))
    }

    @Test
    fun `selector matching with class`() {
        val selector = CssSelector(classes = setOf("tag"))
        assertTrue(selector.matches("div", emptyMap(), setOf("tag")))
        assertTrue(selector.matches("div", emptyMap(), setOf("tag", "other")))
        assertFalse(selector.matches("div", emptyMap(), emptySet()))
        assertFalse(selector.matches("div", emptyMap(), setOf("other")))
    }

    @Test
    fun `selector matching with null tag matches any tag`() {
        val selector = CssSelector(
            tag = null,
            classes = setOf("highlight")
        )
        assertTrue(selector.matches("div", emptyMap(), setOf("highlight")))
        assertTrue(selector.matches("span", emptyMap(), setOf("highlight")))
        assertTrue(selector.matches("any-tag", emptyMap(), setOf("highlight")))
    }

    @Test
    fun `selector matching with compound selector`() {
        val selector = CssSelector(
            tag = "span",
            dataAttributes = mapOf("data-content" to "foo"),
            classes = setOf("tag")
        )
        assertTrue(
            selector.matches(
                "span",
                mapOf("data-content" to "foo"),
                setOf("tag")
            )
        )
        assertFalse(
            selector.matches(
                "span",
                mapOf("data-content" to "foo"),
                emptySet() // missing class
            )
        )
        assertFalse(
            selector.matches(
                "span",
                emptyMap(), // missing attribute
                setOf("tag")
            )
        )
    }

    // Color conversion tests
    @Test
    fun `convert hex color with 6 digits`() {
        val color = processor.convertColor("#FF0000")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert hex color with 3 digits`() {
        val color = processor.convertColor("#F00")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert hex color lowercase`() {
        val color = processor.convertColor("#ff0000")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert hex color mixed case`() {
        val color = processor.convertColor("#Ff00Ff")
        assertEquals(0xFF_FF_00_FF.toInt(), color)
    }

    @Test
    fun `convert hex color with whitespace`() {
        val color = processor.convertColor("  #00FF00  ")
        assertEquals(0xFF_00_FF_00.toInt(), color)
    }

    @Test
    fun `convert rgb color with integers`() {
        val color = processor.convertColor("rgb(255, 0, 0)")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert rgb color with spaces`() {
        val color = processor.convertColor("rgb(  0  ,  255  ,  0  )")
        assertEquals(0xFF_00_FF_00.toInt(), color)
    }

    @Test
    fun `convert rgb color with no spaces`() {
        val color = processor.convertColor("rgb(0,0,255)")
        assertEquals(0xFF_00_00_FF.toInt(), color)
    }

    @Test
    fun `convert rgb color with percentages`() {
        val color = processor.convertColor("rgb(100%, 0%, 0%)")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert rgb color with partial percentages`() {
        val color = processor.convertColor("rgb(50%, 50%, 50%)")
        // 50% of 255 = 127.5, rounded to 127 (0x7F)
        assertEquals(0xFF_7F_7F_7F.toInt(), color)
    }

    @Test
    fun `convert rgba color with float alpha`() {
        val color = processor.convertColor("rgba(255, 0, 0, 0.5)")
        // Alpha 0.5 = 127.5, rounded to 127 (0x7F)
        assertEquals(0x7F_FF_00_00, color)
    }

    @Test
    fun `convert rgba color with full opacity`() {
        val color = processor.convertColor("rgba(0, 255, 0, 1.0)")
        assertEquals(0xFF_00_FF_00.toInt(), color)
    }

    @Test
    fun `convert rgba color with zero opacity`() {
        val color = processor.convertColor("rgba(255, 0, 0, 0.0)")
        assertEquals(0x00_FF_00_00, color)
    }

    @Test
    fun `convert rgba color with percentage alpha`() {
        val color = processor.convertColor("rgba(255, 0, 0, 50%)")
        // 50% of 255 = 127.5, rounded to 127 (0x7F)
        assertEquals(0x7F_FF_00_00, color)
    }

    @Test
    fun `convert named color red`() {
        val color = processor.convertColor("red")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert named color blue`() {
        val color = processor.convertColor("blue")
        assertEquals(0xFF_00_00_FF.toInt(), color)
    }

    @Test
    fun `convert named color green`() {
        val color = processor.convertColor("green")
        assertEquals(0xFF_00_80_00.toInt(), color) // CSS green is 0x008000, not 0x00FF00
    }

    @Test
    fun `convert named color white`() {
        val color = processor.convertColor("white")
        assertEquals(0xFF_FF_FF_FF.toInt(), color)
    }

    @Test
    fun `convert named color black`() {
        val color = processor.convertColor("black")
        assertEquals(0xFF_00_00_00.toInt(), color)
    }

    @Test
    fun `convert named color brown`() {
        val color = processor.convertColor("brown")
        assertEquals(0xFF_A5_2A_2A.toInt(), color)
    }

    @Test
    fun `convert named color with uppercase`() {
        val color = processor.convertColor("RED")
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert named color with mixed case`() {
        val color = processor.convertColor("Blue")
        assertEquals(0xFF_00_00_FF.toInt(), color)
    }

    @Test
    fun `convert invalid hex color returns transparent`() {
        val color = processor.convertColor("#GGG")
        assertEquals(0, color)
    }

    @Test
    fun `convert invalid hex color with wrong length returns transparent`() {
        val color = processor.convertColor("#FFFF")
        assertEquals(0, color)
    }

    @Test
    fun `convert invalid rgb color returns transparent`() {
        val color = processor.convertColor("rgb(255, 255)")
        assertEquals(0, color)
    }

    @Test
    fun `convert invalid rgba color returns transparent`() {
        val color = processor.convertColor("rgba(255, 0, 0)")
        assertEquals(0, color)
    }

    @Test
    fun `convert unsupported color format returns transparent`() {
        val color = processor.convertColor("hsl(120, 100%, 50%)")
        assertEquals(0, color)
    }

    @Test
    fun `convert unknown named color returns transparent`() {
        val color = processor.convertColor("unknowncolor")
        assertEquals(0, color)
    }

    @Test
    fun `convert empty string returns transparent`() {
        val color = processor.convertColor("")
        assertEquals(0, color)
    }

    @Test
    fun `convert rgb with values out of range clamps to 0-255`() {
        val color = processor.convertColor("rgb(300, -10, 128)")
        // 300 -> 255, -10 -> 0, 128 -> 128
        assertEquals(0xFF_FF_00_80.toInt(), color)
    }

    @Test
    fun `convert rgba with alpha out of range clamps to 0-255`() {
        val color = processor.convertColor("rgba(255, 0, 0, 1.5)")
        // Alpha 1.5 * 255 = 382.5, clamped to 255
        assertEquals(0xFF_FF_00_00.toInt(), color)
    }

    @Test
    fun `convert color mix with multiple colors and variables`() {
        // 1. Mixing transparent with a color (existing behavior)
        val colorAlpha = processor.convertColor("color-mix(in srgb, #1a73e8 5%, transparent)")
        assertEquals(0x0D1A73E8, colorAlpha)

        // 2. Mixing two colors without explicit weights (default 50% / 50%)
        val colorMixSimple = processor.convertColor("color-mix(in srgb, red, blue)")
        // Red = 0xFFFF0000, Blue = 0xFF0000FF
        // Mixed: A = 0xFF, R = 128, G = 0, B = 128 (0xFF800080)
        assertEquals(0xFF800080.toInt(), colorMixSimple)

        // 3. Mixing lime with a variable containing var fallback
        val colorMixVar = processor.convertColor("color-mix(in srgb, lime, var(--text-color, var(--fg, #333)))")
        // lime = 0xFF00FF00
        // var(--text-color, var(--fg, #333)) resolves to 0xFF777777
        // 50% / 50% mix:
        // A = 255
        // R = 0.5 * 0 + 0.5 * 119 = 59.5 -> 60 (0x3C)
        // G = 0.5 * 255 + 0.5 * 119 = 187 (0xBB)
        // B = 0.5 * 0 + 0.5 * 119 = 59.5 -> 60 (0x3C)
        // Expected: 0xFF3CBB3C
        assertEquals(0xFF3CBB3C.toInt(), colorMixVar)
    }

    // Font size conversion tests
    @Test
    fun `convert px font size to scale factor`() {
        val scale = processor.convertFontSize("14px")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert larger px font size to scale factor`() {
        val scale = processor.convertFontSize("28px")
        assertEquals(2.0f, scale, 0.001f)
    }

    @Test
    fun `convert smaller px font size to scale factor`() {
        val scale = processor.convertFontSize("7px")
        assertEquals(0.5f, scale, 0.001f)
    }

    @Test
    fun `convert em font size to scale factor`() {
        val scale = processor.convertFontSize("1.5em")
        assertEquals(1.5f, scale, 0.001f)
    }

    @Test
    fun `convert rem font size to scale factor`() {
        val scale = processor.convertFontSize("1.2rem")
        assertEquals(1.2f, scale, 0.001f)
    }

    @Test
    fun `convert percentage font size to scale factor`() {
        val scale = processor.convertFontSize("150%")
        assertEquals(1.5f, scale, 0.001f)
    }

    @Test
    fun `convert 100 percent font size to scale factor`() {
        val scale = processor.convertFontSize("100%")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert font size with whitespace`() {
        val scale = processor.convertFontSize("  14px  ")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert font size with uppercase units`() {
        val scale = processor.convertFontSize("14PX")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert invalid px font size returns default`() {
        val scale = processor.convertFontSize("invalidpx")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert invalid em font size returns default`() {
        val scale = processor.convertFontSize("invalidem")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert invalid rem font size returns default`() {
        val scale = processor.convertFontSize("invalidrem")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert invalid percentage font size returns default`() {
        val scale = processor.convertFontSize("invalid%")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert unsupported font size format returns default`() {
        val scale = processor.convertFontSize("14pt")
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun `convert empty font size returns default`() {
        val scale = processor.convertFontSize("")
        assertEquals(1.0f, scale, 0.001f)
    }

    // Font weight conversion tests
    @Test
    fun `convert font weight 700 to bold`() {
        val bold = processor.convertFontWeight("700")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight 800 to bold`() {
        val bold = processor.convertFontWeight("800")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight 900 to bold`() {
        val bold = processor.convertFontWeight("900")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight 400 to not bold`() {
        val bold = processor.convertFontWeight("400")
        assertFalse(bold)
    }

    @Test
    fun `convert font weight 600 to not bold`() {
        val bold = processor.convertFontWeight("600")
        assertFalse(bold)
    }

    @Test
    fun `convert font weight bold to bold`() {
        val bold = processor.convertFontWeight("bold")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight bolder to bold`() {
        val bold = processor.convertFontWeight("bolder")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight normal to not bold`() {
        val bold = processor.convertFontWeight("normal")
        assertFalse(bold)
    }

    @Test
    fun `convert font weight lighter to not bold`() {
        val bold = processor.convertFontWeight("lighter")
        assertFalse(bold)
    }

    @Test
    fun `convert font weight with whitespace`() {
        val bold = processor.convertFontWeight("  700  ")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight with uppercase`() {
        val bold = processor.convertFontWeight("BOLD")
        assertTrue(bold)
    }

    @Test
    fun `convert font weight with mixed case`() {
        val bold = processor.convertFontWeight("Bold")
        assertTrue(bold)
    }

    @Test
    fun `convert invalid font weight returns false`() {
        val bold = processor.convertFontWeight("invalid")
        assertFalse(bold)
    }

    @Test
    fun `convert empty font weight returns false`() {
        val bold = processor.convertFontWeight("")
        assertFalse(bold)
    }

    // Font style conversion tests
    @Test
    fun `convert font style italic to italic`() {
        val italic = processor.convertFontStyle("italic")
        assertTrue(italic)
    }

    @Test
    fun `convert font style oblique to italic`() {
        val italic = processor.convertFontStyle("oblique")
        assertTrue(italic)
    }

    @Test
    fun `convert font style normal to not italic`() {
        val italic = processor.convertFontStyle("normal")
        assertFalse(italic)
    }

    @Test
    fun `convert font style with whitespace`() {
        val italic = processor.convertFontStyle("  italic  ")
        assertTrue(italic)
    }

    @Test
    fun `convert font style with uppercase`() {
        val italic = processor.convertFontStyle("ITALIC")
        assertTrue(italic)
    }

    @Test
    fun `convert font style with mixed case`() {
        val italic = processor.convertFontStyle("Oblique")
        assertTrue(italic)
    }

    @Test
    fun `convert invalid font style returns false`() {
        val italic = processor.convertFontStyle("invalid")
        assertFalse(italic)
    }

    @Test
    fun `convert empty font style returns false`() {
        val italic = processor.convertFontStyle("")
        assertFalse(italic)
    }

    // List marker extraction tests
    @Test
    fun `extract list marker from quoted string with double quotes`() {
        val marker = processor.extractListMarker("\"①\"")
        assertEquals("①", marker)
    }

    @Test
    fun `extract list marker from quoted string with single quotes`() {
        val marker = processor.extractListMarker("'①'")
        assertEquals("①", marker)
    }

    @Test
    fun `extract list marker from quoted full-width asterisk`() {
        val marker = processor.extractListMarker("\"＊\"")
        assertEquals("＊", marker)
    }

    @Test
    fun `extract list marker from Unicode escape 4 digits`() {
        val marker = processor.extractListMarker("\"\\2460\"")
        assertEquals("①", marker) // U+2460 is circled digit one
    }

    @Test
    fun `extract list marker from Unicode escape with u prefix`() {
        val marker = processor.extractListMarker("\"\\u2460\"")
        assertEquals("①", marker)
    }

    @Test
    fun `extract list marker from Unicode escape with braces`() {
        val marker = processor.extractListMarker("\"\\u{2460}\"")
        assertEquals("①", marker)
    }

    @Test
    fun `extract list marker from named value disc`() {
        val marker = processor.extractListMarker("disc")
        assertEquals("•", marker)
    }

    @Test
    fun `extract list marker from named value circle`() {
        val marker = processor.extractListMarker("circle")
        assertEquals("◦", marker)
    }

    @Test
    fun `extract list marker from named value square`() {
        val marker = processor.extractListMarker("square")
        assertEquals("▪", marker)
    }

    @Test
    fun `extract list marker from named value decimal`() {
        val marker = processor.extractListMarker("decimal")
        assertEquals("decimal", marker)
    }

    @Test
    fun `extract list marker from named value none`() {
        val marker = processor.extractListMarker("none")
        assertEquals("none", marker)
    }

    @Test
    fun `extract list marker with uppercase named value`() {
        val marker = processor.extractListMarker("DISC")
        assertEquals("•", marker)
    }

    @Test
    fun `extract list marker with mixed case named value`() {
        val marker = processor.extractListMarker("Circle")
        assertEquals("◦", marker)
    }

    @Test
    fun `extract list marker with whitespace`() {
        val marker = processor.extractListMarker("  disc  ")
        assertEquals("•", marker)
    }

    @Test
    fun `extract list marker from complex Unicode example`() {
        val marker = processor.extractListMarker("\"\\u{1F4A9}\"")
        assertEquals("💩", marker) // U+1F4A9 is pile of poo emoji
    }

    @Test
    fun `extract list marker from multiple Unicode escapes in quoted string`() {
        val marker = processor.extractListMarker("\"\\2460\\2461\"")
        assertEquals("①②", marker) // Should preserve both
    }

    @Test
    fun `extract list marker from quoted string with escaped quote`() {
        val marker = processor.extractListMarker("\"\\\"test\\\"\"")
        assertEquals("\"test\"", marker) // Escaped quotes should be preserved
    }

    @Test
    fun `extract list marker from quoted string with backslash`() {
        val marker = processor.extractListMarker("\"\\\\\"")
        assertEquals("\\", marker) // Escaped backslash
    }

    @Test
    fun `extract list marker from unsupported format returns null`() {
        val marker = processor.extractListMarker("unsupported-format")
        assertNull(marker)
    }

    @Test
    fun `extract list marker from empty string returns null`() {
        val marker = processor.extractListMarker("")
        assertNull(marker)
    }

    @Test
    fun `extract list marker from bare Unicode escape without quotes`() {
        val marker = processor.extractListMarker("\\2460")
        assertEquals("①", marker)
    }

    @Test
    fun `extract list marker handles invalid Unicode escape gracefully`() {
        val marker = processor.extractListMarker("\"\\uGGGG\"")
        // Invalid hex digits should be kept as-is or processed best-effort
        assertNotNull(marker) // Should return something, not crash
    }

    @Test
    fun `process unicode escapes with mixed text and escapes`() {
        val marker = processor.extractListMarker("\"A\\2460B\\2461C\"")
        assertEquals("A①B②C", marker)
    }

    @Test
    fun `process unicode escapes with text and no escapes`() {
        val marker = processor.extractListMarker("\"plain text\"")
        assertEquals("plain text", marker)
    }

    @Test
    fun `unicode escape with 5 digit code point`() {
        val marker = processor.extractListMarker("\"\\u{1F600}\"")
        assertEquals("😀", marker) // U+1F600 is grinning face emoji
    }

    @Test
    fun `unicode escape with 6 digit code point`() {
        val marker = processor.extractListMarker("\"\\u{10FFFF}\"")
        // U+10FFFF is the maximum valid Unicode code point
        assertNotNull(marker) // Should process without crashing
    }

    // resolveStyles tests
    @Test
    fun `resolveStyles with no matching rules returns default style`() {
        val rules = processor.parseCss("div { color: red; }")
        val resolved = processor.resolveStyles(
            tag = "span",
            cssRules = rules
        )

        assertEquals(false, resolved.bold)
        assertEquals(false, resolved.italic)
        assertEquals(1.0f, resolved.fontSize, 0.001f)
        assertEquals(0, resolved.foregroundColor)
        assertEquals(0, resolved.textBackgroundColor)
        assertEquals(0, resolved.backgroundColor)
        assertNull(resolved.listMarker)
        assertEquals(0, resolved.blockType)
        assertNull(resolved.hoverText)
    }

    @Test
    fun `resolveStyles with matching tag selector applies style`() {
        val rules = processor.parseCss(
            """
            div {
                color: #FF0000;
                font-weight: bold;
                font-size: 16px;
            }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "div",
            cssRules = rules
        )

        assertTrue(resolved.bold)
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor)
        assertEquals(16f / 14f, resolved.fontSize, 0.001f) // 16px / 14 base
    }

    @Test
    fun `resolveStyles with matching attribute selector applies style`() {
        val rules = processor.parseCss(
            """
            span[data-content="foo"] {
                font-style: italic;
                background-color: yellow;
            }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-content" to "foo"),
            cssRules = rules
        )

        assertTrue(resolved.italic)
        assertEquals(0xFFFFFF00.toInt(), resolved.backgroundColor)
    }

    @Test
    fun `resolveStyles with matching class selector applies style`() {
        val rules = processor.parseCss(
            """
            .highlight {
                background-color: #FFFF00;
            }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "span",
            classes = setOf("highlight"),
            cssRules = rules
        )

        assertEquals(0xFFFFFF00.toInt(), resolved.backgroundColor)
    }

    @Test
    fun `resolveStyles with compound selector applies style`() {
        val rules = processor.parseCss(
            """
            span[data-content="tag"].highlight {
                color: red;
                font-weight: bold;
            }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-content" to "tag"),
            classes = setOf("highlight"),
            cssRules = rules
        )

        assertTrue(resolved.bold)
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor)
    }

    @Test
    fun `resolveStyles with multiple matching rules applies by specificity`() {
        val rules = processor.parseCss(
            """
            span { color: blue; }
            span[data-content="foo"] { color: red; }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-content" to "foo"),
            cssRules = rules
        )

        // Attribute selector has higher specificity, should be red
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor)
    }

    @Test
    fun `resolveStyles with equal specificity applies last rule`() {
        val rules = processor.parseCss(
            """
            .class1 { color: blue; }
            .class2 { color: red; }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "span",
            classes = setOf("class1", "class2"),
            cssRules = rules
        )

        // Both have equal specificity, last one wins
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor)
    }

    @Test
    fun `resolveStyles inline styles override CSS rules`() {
        val rules = processor.parseCss(
            """
            div { color: blue; font-weight: bold; }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "div",
            inlineStyle = mapOf("color" to "red"),
            cssRules = rules
        )

        // Inline style should override
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor)
        // But bold from CSS should still apply
        assertTrue(resolved.bold)
    }

    @Test
    fun `resolveStyles with list-style-type sets blockType and marker`() {
        val rules = processor.parseCss(
            """
            li { list-style-type: "①"; }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "li",
            cssRules = rules
        )

        assertEquals(1, resolved.blockType) // list-item
        assertEquals("①", resolved.listMarker)
    }

    @Test
    fun `resolveStyles merges properties from multiple matching rules`() {
        val rules = processor.parseCss(
            """
            div { color: red; }
            .box { background-color: yellow; }
            [data-content="foo"] { font-weight: bold; }
        """.trimIndent()
        )

        val resolved = processor.resolveStyles(
            tag = "div",
            dataAttributes = mapOf("data-content" to "foo"),
            classes = setOf("box"),
            cssRules = rules
        )

        // All properties should be merged
        assertEquals(0xFFFF0000.toInt(), resolved.foregroundColor) // color from div
        assertEquals(0xFFFFFF00.toInt(), resolved.backgroundColor) // background from .box
        assertTrue(resolved.bold) // font-weight from [data-content]
    }

    @Test
    fun `resolveStyles with no rules and no inline style returns default`() {
        val resolved = processor.resolveStyles(
            tag = "div",
            cssRules = emptyList()
        )

        assertEquals(false, resolved.bold)
        assertEquals(false, resolved.italic)
        assertEquals(1.0f, resolved.fontSize, 0.001f)
        assertEquals(0, resolved.foregroundColor)
        assertEquals(0, resolved.textBackgroundColor)
        assertEquals(0, resolved.backgroundColor)
        assertNull(resolved.listMarker)
        assertEquals(0, resolved.blockType)
        assertNull(resolved.hoverText)
    }

    @Test
    fun `toInlineStyle converts ResolvedStyle correctly`() {
        val resolved = ResolvedStyle(
            bold = true,
            italic = true,
            fontSize = 1.5f,
            foregroundColor = 0xFFFF0000.toInt(),
            textBackgroundColor = 0xFFFFFF00.toInt(),
            hoverText = "Hover text"
        )

        val inlineStyle = processor.toInlineStyle(resolved, linkUrl = "lookup:test")

        assertEquals(true, inlineStyle.bold)
        assertEquals(true, inlineStyle.italic)
        assertEquals(1.5f, inlineStyle.fontSize, 0.001f)
        assertEquals(0xFFFF0000.toInt(), inlineStyle.foregroundColor)
        assertEquals(0xFFFFFF00.toInt(), inlineStyle.textBackgroundColor)
        assertEquals("Hover text", inlineStyle.hoverText)
        assertEquals("lookup:test", inlineStyle.linkUrl)
    }

    @Test
    fun `toBlockSpan converts ResolvedStyle correctly`() {
        val resolved = ResolvedStyle(
            blockType = 1,
            backgroundColor = 0xFFEEEEEE.toInt(),
            listMarker = "①"
        )

        val blockSpan = processor.toBlockSpan(resolved, 0..10, indentLevel = 2)

        assertNotNull(blockSpan)
        assertEquals(0, blockSpan!!.startIndex)
        assertEquals(11, blockSpan.endIndex) // IntRange is inclusive, endIndex is exclusive
        assertEquals(1, blockSpan.blockType)
        assertEquals(0xFFEEEEEE.toInt(), blockSpan.backgroundColor)
        assertEquals("①", blockSpan.listMarker)
        assertEquals(2, blockSpan.indentLevel)
    }

    @Test
    fun `toBlockSpan returns null when no block properties are set`() {
        val resolved = ResolvedStyle(
            bold = true,
            foregroundColor = 0xFFFF0000.toInt()
        )

        val blockSpan = processor.toBlockSpan(resolved, 0..10)

        assertNull(blockSpan)
    }

    @Test
    fun `resolveStyles handles background vs background-color property`() {
        val rules1 = processor.parseCss("div { background-color: red; }")
        val resolved1 = processor.resolveStyles(tag = "div", cssRules = rules1)
        assertEquals(0xFFFF0000.toInt(), resolved1.backgroundColor)

        val rules2 = processor.parseCss("div { background: blue; }")
        val resolved2 = processor.resolveStyles(tag = "div", cssRules = rules2)
        assertEquals(0xFF0000FF.toInt(), resolved2.backgroundColor)
    }

    @Test
    fun `toInlineStyle with default values creates correct InlineStyle`() {
        val resolved = ResolvedStyle()
        val inlineStyle = processor.toInlineStyle(resolved)

        assertEquals(false, inlineStyle.bold)
        assertEquals(false, inlineStyle.italic)
        assertEquals(1.0f, inlineStyle.fontSize, 0.001f)
        assertEquals(0, inlineStyle.foregroundColor)
        assertEquals(0, inlineStyle.textBackgroundColor)
        assertNull(inlineStyle.hoverText)
        assertNull(inlineStyle.linkUrl)
    }

    @Test
    fun `toInlineStyle maps all ResolvedStyle fields to InlineStyle correctly`() {
        // Test mapping of bold
        val boldStyle = ResolvedStyle(bold = true)
        assertEquals(true, processor.toInlineStyle(boldStyle).bold)

        // Test mapping of italic
        val italicStyle = ResolvedStyle(italic = true)
        assertEquals(true, processor.toInlineStyle(italicStyle).italic)

        // Test mapping of fontSize
        val fontSizeStyle = ResolvedStyle(fontSize = 2.0f)
        assertEquals(2.0f, processor.toInlineStyle(fontSizeStyle).fontSize, 0.001f)

        // Test mapping of foregroundColor
        val fgColorStyle = ResolvedStyle(foregroundColor = 0xFF123456.toInt())
        assertEquals(0xFF123456.toInt(), processor.toInlineStyle(fgColorStyle).foregroundColor)

        // Test mapping of textBackgroundColor
        val bgColorStyle = ResolvedStyle(textBackgroundColor = 0xFF654321.toInt())
        assertEquals(0xFF654321.toInt(), processor.toInlineStyle(bgColorStyle).textBackgroundColor)

        // Test mapping of hoverText
        val hoverStyle = ResolvedStyle(hoverText = "Test hover")
        assertEquals("Test hover", processor.toInlineStyle(hoverStyle).hoverText)

        // Test mapping of linkUrl (passed as parameter)
        val defaultStyle = ResolvedStyle()
        assertEquals("lookup:word", processor.toInlineStyle(defaultStyle, "lookup:word").linkUrl)
    }

    @Test
    fun `toBlockSpan maps all ResolvedStyle fields to BlockSpan correctly`() {
        // Test mapping of blockType
        val blockTypeStyle = ResolvedStyle(blockType = 1)
        val blockSpan1 = processor.toBlockSpan(blockTypeStyle, 0..10)
        assertNotNull(blockSpan1)
        assertEquals(1, blockSpan1!!.blockType)

        // Test mapping of backgroundColor
        val bgColorStyle = ResolvedStyle(backgroundColor = 0xFFAABBCC.toInt())
        val blockSpan2 = processor.toBlockSpan(bgColorStyle, 0..10)
        assertNotNull(blockSpan2)
        assertEquals(0xFFAABBCC.toInt(), blockSpan2!!.backgroundColor)

        // Test mapping of listMarker
        val listMarkerStyle = ResolvedStyle(blockType = 1, listMarker = "②")
        val blockSpan3 = processor.toBlockSpan(listMarkerStyle, 0..10)
        assertNotNull(blockSpan3)
        assertEquals("②", blockSpan3!!.listMarker)

        // Test mapping of indentLevel (passed as parameter)
        val indentStyle = ResolvedStyle(blockType = 1)
        val blockSpan4 = processor.toBlockSpan(indentStyle, 0..10, indentLevel = 3)
        assertNotNull(blockSpan4)
        assertEquals(3, blockSpan4!!.indentLevel)

        // Test mapping of text range
        val rangeStyle = ResolvedStyle(blockType = 1)
        val blockSpan5 = processor.toBlockSpan(rangeStyle, 5..15)
        assertNotNull(blockSpan5)
        assertEquals(5, blockSpan5!!.startIndex)
        assertEquals(16, blockSpan5.endIndex) // IntRange is inclusive, BlockSpan is exclusive
    }

    @Test
    fun `end-to-end CSS to SDK mapping for inline styles`() {
        // CSS with bold, italic, fontSize, colors
        val css = """
            .styled {
                font-weight: bold;
                font-style: italic;
                font-size: 18px;
                color: #FF0000;
            }
        """.trimIndent()

        val rules = processor.parseCss(css)
        val resolved = processor.resolveStyles(
            tag = "span",
            classes = setOf("styled"),
            cssRules = rules
        )

        val inlineStyle = processor.toInlineStyle(resolved, linkUrl = "lookup:example")

        // Verify all mappings
        assertTrue(inlineStyle.bold)
        assertTrue(inlineStyle.italic)
        assertEquals(18f / 14f, inlineStyle.fontSize, 0.01f) // 18px / 14px base
        assertEquals(0xFFFF0000.toInt(), inlineStyle.foregroundColor)
        assertEquals("lookup:example", inlineStyle.linkUrl)
    }

    @Test
    fun `end-to-end CSS to SDK mapping for block styles`() {
        // CSS with block-level properties
        val css = """
            .list-item {
                background-color: #F0F0F0;
                list-style-type: "③";
            }
        """.trimIndent()

        val rules = processor.parseCss(css)
        val resolved = processor.resolveStyles(
            tag = "li",
            classes = setOf("list-item"),
            cssRules = rules
        )

        // List items should have blockType = 1
        val resolvedWithBlockType = resolved.copy(blockType = 1)
        val blockSpan = processor.toBlockSpan(resolvedWithBlockType, 10..25, indentLevel = 1)

        assertNotNull(blockSpan)
        assertEquals(10, blockSpan!!.startIndex)
        assertEquals(26, blockSpan.endIndex) // Exclusive end
        assertEquals(1, blockSpan.blockType)
        assertEquals(0xFFF0F0F0.toInt(), blockSpan.backgroundColor)
        assertEquals("③", blockSpan.listMarker)
        assertEquals(1, blockSpan.indentLevel)
    }

    @Test
    fun `toBlockSpan returns null when indentLevel is 0 and no block properties`() {
        val resolved = ResolvedStyle(
            bold = true,
            fontSize = 1.2f
        )

        val blockSpan = processor.toBlockSpan(resolved, 0..10, indentLevel = 0)

        assertNull("BlockSpan should be null when no block-level properties are set", blockSpan)
    }

    @Test
    fun `toBlockSpan creates span when indentLevel is non-zero even without other block properties`() {
        val resolved = ResolvedStyle()

        val blockSpan = processor.toBlockSpan(resolved, 0..10, indentLevel = 2)

        assertNotNull("BlockSpan should be created when indentLevel > 0", blockSpan)
        assertEquals(2, blockSpan!!.indentLevel)
        assertEquals(0, blockSpan.blockType)
        assertEquals(0, blockSpan.backgroundColor)
        assertNull(blockSpan.listMarker)
    }

    @Test
    fun `verify Style to SDK mapping`() {
        val boldResolved = ResolvedStyle(bold = true)
        assertTrue(
            "Bold should map to InlineStyle.bold",
            processor.toInlineStyle(boldResolved).bold
        )

        val italicResolved = ResolvedStyle(italic = true)
        assertTrue(
            "Italic should map to InlineStyle.italic",
            processor.toInlineStyle(italicResolved).italic
        )

        val fontSizeResolved = ResolvedStyle(fontSize = 1.5f)
        assertEquals(
            "FontSize should map as scale factor",
            1.5f, processor.toInlineStyle(fontSizeResolved).fontSize, 0.001f
        )

        val fgColorResolved = ResolvedStyle(foregroundColor = 0xFFAABBCC.toInt())
        assertEquals(
            "Foreground color should map to InlineStyle.foregroundColor",
            0xFFAABBCC.toInt(), processor.toInlineStyle(fgColorResolved).foregroundColor
        )

        val textBgResolved = ResolvedStyle(textBackgroundColor = 0x80112233.toInt())
        assertEquals(
            "Text background color should map to InlineStyle.textBackgroundColor",
            0x80112233.toInt(), processor.toInlineStyle(textBgResolved).textBackgroundColor
        )

        val blockBgResolved = ResolvedStyle(backgroundColor = 0xFFEEEEEE.toInt())
        val blockSpan = processor.toBlockSpan(blockBgResolved, 0..10)
        assertNotNull("Block background color should create BlockSpan", blockSpan)
        assertEquals(
            "Block background color should map to BlockSpan.backgroundColor",
            0xFFEEEEEE.toInt(), blockSpan!!.backgroundColor
        )

        val listResolved = ResolvedStyle(blockType = 1, listMarker = "④")
        val listSpan = processor.toBlockSpan(listResolved, 0..10)
        assertEquals(
            "List marker should map to BlockSpan.listMarker and blockType=1",
            "④", listSpan!!.listMarker
        )
        assertEquals(
            "List marker should map to BlockSpan.blockType=1",
            1, listSpan.blockType
        )

        val indentResolved = ResolvedStyle(blockType = 1)
        val indentSpan = processor.toBlockSpan(indentResolved, 0..10, indentLevel = 4)
        assertEquals(
            "Indent level should map to BlockSpan.indentLevel",
            4, indentSpan!!.indentLevel
        )

        val hoverResolved = ResolvedStyle(hoverText = "Tooltip text")
        assertEquals(
            "Hover text should map to InlineStyle.hoverText",
            "Tooltip text", processor.toInlineStyle(hoverResolved).hoverText
        )

        val linkResolved = ResolvedStyle()
        assertEquals(
            "Link URL should map to InlineStyle.linkUrl with lookup: prefix",
            "lookup:日本語", processor.toInlineStyle(linkResolved, "lookup:日本語").linkUrl
        )

        val defaultResolved = ResolvedStyle()
        val defaultInline = processor.toInlineStyle(defaultResolved)
        assertFalse("Default bold should be false", defaultInline.bold)
        assertFalse("Default italic should be false", defaultInline.italic)
        assertEquals("Default fontSize should be 1.0", 1.0f, defaultInline.fontSize, 0.001f)
        assertEquals("Default foregroundColor should be 0", 0, defaultInline.foregroundColor)

        val alphaResolved = ResolvedStyle(
            foregroundColor = 0x80FF0000.toInt(),
            textBackgroundColor = 0x40FFFF00.toInt()
        )
        val alphaInline = processor.toInlineStyle(alphaResolved)
        assertEquals(
            "Alpha channel should be preserved in foreground",
            0x80, (alphaInline.foregroundColor ushr 24) and 0xFF
        )
        assertEquals(
            "Alpha channel should be preserved in text background",
            0x40, (alphaInline.textBackgroundColor ushr 24) and 0xFF
        )
    }

    @Test
    fun `parse nested CSS rules and resolve styles matching parent selector`() {
        val css = """
            div[data-sc-content="xref"] {
                & span[data-sc-content="reference-label"] {
                    color: blue;
                }
            }
            div[data-sc-content="antonym"] {
                & span[data-sc-content="reference-label"] {
                    color: red;
                }
            }
        """.trimIndent()

        val rules = processor.parseCss(css)

        // Filter for nested rules we care about
        val spanRules = rules.filter { it.selector.tag == "span" }
        assertEquals(2, spanRules.size)

        // Rule 0
        assertEquals("span", spanRules[0].selector.tag)
        assertEquals("reference-label", spanRules[0].selector.dataAttributes["data-sc-content"])
        assertNotNull(spanRules[0].selector.parentSelector)
        assertEquals("div", spanRules[0].selector.parentSelector?.tag)
        assertEquals("xref", spanRules[0].selector.parentSelector?.dataAttributes?.get("data-sc-content"))
        assertEquals("blue", spanRules[0].declarations["color"])

        // Rule 1
        assertEquals("span", spanRules[1].selector.tag)
        assertEquals("reference-label", spanRules[1].selector.dataAttributes["data-sc-content"])
        assertNotNull(spanRules[1].selector.parentSelector)
        assertEquals("div", spanRules[1].selector.parentSelector?.tag)
        assertEquals("antonym", spanRules[1].selector.parentSelector?.dataAttributes?.get("data-sc-content"))
        assertEquals("red", spanRules[1].declarations["color"])

        // Now verify resolution
        val xrefStyle = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-sc-content" to "reference-label"),
            classes = emptySet(),
            cssRules = rules,
            parentStack = listOf(
                ElementContext(
                    tag = "div",
                    dataAttributes = mapOf("data-sc-content" to "xref")
                )
            )
        )
        assertEquals(0xFF0000FF.toInt(), xrefStyle.foregroundColor) // blue

        val antonymStyle = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-sc-content" to "reference-label"),
            classes = emptySet(),
            cssRules = rules,
            parentStack = listOf(
                ElementContext(
                    tag = "div",
                    dataAttributes = mapOf("data-sc-content" to "antonym")
                )
            )
        )
        assertEquals(0xFFFF0000.toInt(), antonymStyle.foregroundColor) // red

        val defaultStyle = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-sc-content" to "reference-label"),
            classes = emptySet(),
            cssRules = rules,
            parentStack = emptyList()
        )
        assertEquals(0, defaultStyle.foregroundColor) // should not match either nested rule
    }

    @Test
    fun `resolveStyles resolves font size relative inheritance correctly for em and rem`() {
        val parentStyle = ResolvedStyle(fontSize = 1.5f)

        // 1. Child font-size = 0.8em (relative) -> should multiply
        val resolvedEm = processor.resolveStyles(
            tag = "span",
            inlineStyle = mapOf("font-size" to "0.8em"),
            parentStyles = parentStyle
        )
        assertEquals(1.2f, resolvedEm.fontSize, 0.01f) // 1.5 * 0.8 = 1.2

        // 2. Child font-size = 0.8rem (absolute) -> should NOT multiply
        val resolvedRem = processor.resolveStyles(
            tag = "span",
            inlineStyle = mapOf("font-size" to "0.8rem"),
            parentStyles = parentStyle
        )
        assertEquals(0.8f, resolvedRem.fontSize, 0.01f) // remains 0.8
    }

    @Test
    fun testCssSelectorPseudoElementSpecificity() {
        val selector = CssSelector(tag = "span", pseudoElement = "before")
        assertEquals(2, selector.specificity()) // tag(1) + pseudoElement(1)
    }

    @Test
    fun testParsePseudoElementSelector() {
        val css = "span::before { content: \"◇\"; }"
        val rules = processor.parseCss(css)
        assertEquals(1, rules.size)
        assertEquals("span", rules[0].selector.tag)
        assertEquals("before", rules[0].selector.pseudoElement)
        assertEquals("\"◇\"", rules[0].declarations["content"])
    }

    @Test
    fun testParseNestedPseudoElementSelector() {
        val css = """
            span {
                color: red;
                &::before {
                    content: "◇";
                }
            }
        """.trimIndent()
        val rules = processor.parseCss(css)
        // Expect 2 rules: span, and span::before
        val pseudoRule = rules.find { it.selector.pseudoElement == "before" }
        assertNotNull(pseudoRule)
        assertEquals("span", pseudoRule!!.selector.tag)
        assertNull(pseudoRule.selector.parentSelector)
    }

    @Test
    fun testResolvePseudoElementStyles() {
        val css = """
            span[data-class="tag"] {
                color: blue;
                &::before {
                    content: "◇";
                }
            }
        """.trimIndent()
        val rules = processor.parseCss(css)
        val resolvedNormal = processor.resolveStyles(
            tag = "span",
            dataAttributes = mapOf("data-class" to "tag"),
            cssRules = rules
        )
        // Normal resolution should ignore the pseudo-element rule
        assertNull(resolvedNormal.content)

        val resolvedPseudo = processor.resolvePseudoElementStyles(
            pseudoType = "before",
            tag = "span",
            dataAttributes = mapOf("data-class" to "tag"),
            parentStyles = resolvedNormal,
            cssRules = rules
        )
        assertNotNull(resolvedPseudo)
        assertEquals("◇", resolvedPseudo!!.content)
    }
}
