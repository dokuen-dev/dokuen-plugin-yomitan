package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryEntrySerializerTest {

    @Test
    fun testSerializeDictionaryEntry() {
        // Create a sample entry with all fields populated
        val entry = DictionaryEntry(
            headword = "日本",
            pronunciation = arrayOf(
                RubySpan(
                    startIndex = 0,
                    endIndex = 1,
                    rubyText = "に"
                ),
                RubySpan(
                    startIndex = 1,
                    endIndex = 2,
                    rubyText = "ほん"
                )
            ),
            body = StyledText(
                text = "Japan; the country",
                blockSpans = arrayOf(
                    BlockSpan(
                        startIndex = 0,
                        endIndex = 18,
                        blockType = 0,
                        indentLevel = 0,
                        listMarker = null,
                        backgroundColor = 0
                    )
                ),
                styledSpans = arrayOf(
                    StyledSpan(
                        startIndex = 0,
                        endIndex = 5,
                        style = InlineStyle(
                            bold = true,
                            italic = false,
                            fontSize = 1.2f,
                            foregroundColor = 0xFF000000.toInt(),
                            textBackgroundColor = 0,
                            hoverText = null,
                            linkUrl = null
                        )
                    )
                ),
                rubySpans = null
            )
        )

        // Serialize to JSON
        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify JSON is not empty
        assertNotNull(json)
        assertTrue(json.isNotEmpty())

        // Verify JSON contains expected fields
        assertTrue(json.contains("\"headword\""))
        assertTrue(json.contains("\"日本\""))
        assertTrue(json.contains("\"pronunciation\""))
        assertTrue(json.contains("\"body\""))
        assertTrue(json.contains("\"text\""))
    }

    @Test
    fun testSerializeStyledTextWithAllSpans() {
        val entry = DictionaryEntry(
            headword = "test",
            pronunciation = null,
            body = StyledText(
                text = "This is a test.",
                blockSpans = arrayOf(
                    BlockSpan(
                        startIndex = 0,
                        endIndex = 15,
                        blockType = 1, // list-item
                        indentLevel = 1,
                        listMarker = "①",
                        backgroundColor = 0xFFFFE0E0.toInt()
                    )
                ),
                styledSpans = arrayOf(
                    StyledSpan(
                        startIndex = 5,
                        endIndex = 7,
                        style = InlineStyle(
                            bold = false,
                            italic = true,
                            fontSize = 1.0f,
                            foregroundColor = 0xFF0000FF.toInt(),
                            textBackgroundColor = 0xFFFFFF00.toInt(),
                            hoverText = "Hover text",
                            linkUrl = "lookup:test"
                        )
                    )
                ),
                rubySpans = arrayOf(
                    RubySpan(
                        startIndex = 10,
                        endIndex = 14,
                        rubyText = "てすと"
                    )
                )
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify all span types are serialized
        assertTrue(json.contains("\"blockSpans\""))
        assertTrue(json.contains("\"styledSpans\""))
        assertTrue(json.contains("\"rubySpans\""))
        assertTrue(json.contains("\"listMarker\""))
        assertTrue(json.contains("\"①\""))
        assertTrue(json.contains("\"hoverText\""))
        assertTrue(json.contains("\"linkUrl\""))
        assertTrue(json.contains("\"てすと\""))
    }

    @Test
    fun testSerializeInlineStyleAllFields() {
        val entry = DictionaryEntry(
            headword = "style",
            pronunciation = null,
            body = StyledText(
                text = "styled",
                blockSpans = null,
                styledSpans = arrayOf(
                    StyledSpan(
                        startIndex = 0,
                        endIndex = 6,
                        style = InlineStyle(
                            bold = true,
                            italic = true,
                            fontSize = 1.5f,
                            foregroundColor = 0xFF123456.toInt(),
                            textBackgroundColor = 0xFFABCDEF.toInt(),
                            hoverText = "This is hover text",
                            linkUrl = "https://example.com"
                        )
                    )
                ),
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify all InlineStyle fields are serialized
        assertTrue(json.contains("\"bold\""))
        assertTrue(json.contains("\"italic\""))
        assertTrue(json.contains("\"fontSize\""))
        assertTrue(json.contains("\"foregroundColor\""))
        assertTrue(json.contains("\"textBackgroundColor\""))
        assertTrue(json.contains("\"hoverText\""))
        assertTrue(json.contains("\"This is hover text\""))
        assertTrue(json.contains("\"linkUrl\""))
        assertTrue(json.contains("\"https://example.com\""))
    }

    @Test
    fun testSerializeBlockSpanAllFields() {
        val entry = DictionaryEntry(
            headword = "block",
            pronunciation = null,
            body = StyledText(
                text = "block text",
                blockSpans = arrayOf(
                    BlockSpan(
                        startIndex = 0,
                        endIndex = 10,
                        blockType = 2, // code-block
                        indentLevel = 3,
                        listMarker = "•",
                        backgroundColor = 0xFFF0F0F0.toInt()
                    )
                ),
                styledSpans = null,
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify all BlockSpan fields are serialized
        assertTrue(json.contains("\"startIndex\""))
        assertTrue(json.contains("\"endIndex\""))
        assertTrue(json.contains("\"blockType\""))
        assertTrue(json.contains("\"indentLevel\""))
        assertTrue(json.contains("\"listMarker\""))
        assertTrue(json.contains("\"backgroundColor\""))
        // The bullet character may be escaped in JSON (e.g., "\u2022"), so just check that listMarker is present with a value
        assertTrue(json.contains("\"listMarker\"") && !json.contains("\"listMarker\": null"))
    }

    @Test
    fun testSerializeRubySpan() {
        val entry = DictionaryEntry(
            headword = "漢字",
            pronunciation = arrayOf(
                RubySpan(
                    startIndex = 0,
                    endIndex = 1,
                    rubyText = "かん"
                ),
                RubySpan(
                    startIndex = 1,
                    endIndex = 2,
                    rubyText = "じ"
                )
            ),
            body = StyledText(
                text = "Kanji characters",
                blockSpans = null,
                styledSpans = null,
                rubySpans = arrayOf(
                    RubySpan(
                        startIndex = 0,
                        endIndex = 5,
                        rubyText = "かんじ"
                    )
                )
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify RubySpan fields are serialized
        assertTrue(json.contains("\"startIndex\""))
        assertTrue(json.contains("\"endIndex\""))
        assertTrue(json.contains("\"rubyText\""))
        assertTrue(json.contains("\"かん\""))
        assertTrue(json.contains("\"じ\""))
        assertTrue(json.contains("\"かんじ\""))
    }

    @Test
    fun testSerializeNullableFields() {
        // Test entry with nullable fields set to null
        val entry = DictionaryEntry(
            headword = "simple",
            pronunciation = null,
            body = StyledText(
                text = "simple text",
                blockSpans = null,
                styledSpans = null,
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(entry))

        // Verify entry is serialized without nullable fields
        assertTrue(json.contains("\"headword\""))
        assertTrue(json.contains("\"simple\""))
        assertTrue(json.contains("\"body\""))
        assertTrue(json.contains("\"text\""))
        assertTrue(json.contains("\"simple text\""))
    }

    @Test
    fun testRoundTripSerialization() {
        // Create a complex entry
        val originalEntry = DictionaryEntry(
            headword = "複雑",
            pronunciation = arrayOf(
                RubySpan(0, 1, "ふく"),
                RubySpan(1, 2, "ざつ")
            ),
            body = StyledText(
                text = "Complex; complicated",
                blockSpans = arrayOf(
                    BlockSpan(0, 20, 1, 0, "①", 0xFFF5F5F5.toInt())
                ),
                styledSpans = arrayOf(
                    StyledSpan(
                        0,
                        7,
                        InlineStyle(
                            bold = true,
                            italic = false,
                            fontSize = 1.2f,
                            foregroundColor = 0xFF000000.toInt(),
                            textBackgroundColor = 0,
                            hoverText = "Complex word",
                            linkUrl = "lookup:complex"
                        )
                    )
                ),
                rubySpans = arrayOf(
                    RubySpan(0, 7, "ふくざつ")
                )
            )
        )

        // Serialize
        val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))

        // Deserialize
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        // Verify we got one entry back
        assertEquals(1, deserializedEntries.size)
        val deserializedEntry = deserializedEntries[0]

        // Verify all fields match
        assertEquals(originalEntry.headword, deserializedEntry.headword)

        // Verify pronunciation
        assertNotNull(deserializedEntry.pronunciation)
        assertEquals(originalEntry.pronunciation!!.size, deserializedEntry.pronunciation!!.size)
        assertEquals(originalEntry.pronunciation!![0].rubyText, deserializedEntry.pronunciation!![0].rubyText)
        assertEquals(originalEntry.pronunciation!![1].rubyText, deserializedEntry.pronunciation!![1].rubyText)

        // Verify body text
        assertEquals(originalEntry.body.text, deserializedEntry.body.text)

        // Verify blockSpans
        assertNotNull(deserializedEntry.body.blockSpans)
        assertEquals(1, deserializedEntry.body.blockSpans!!.size)
        assertEquals(originalEntry.body.blockSpans!![0].blockType, deserializedEntry.body.blockSpans!![0].blockType)
        assertEquals(originalEntry.body.blockSpans!![0].listMarker, deserializedEntry.body.blockSpans!![0].listMarker)

        // Verify styledSpans
        assertNotNull(deserializedEntry.body.styledSpans)
        assertEquals(1, deserializedEntry.body.styledSpans!!.size)
        assertEquals(originalEntry.body.styledSpans!![0].style.bold, deserializedEntry.body.styledSpans!![0].style.bold)
        assertEquals(
            originalEntry.body.styledSpans!![0].style.hoverText,
            deserializedEntry.body.styledSpans!![0].style.hoverText
        )
        assertEquals(
            originalEntry.body.styledSpans!![0].style.linkUrl,
            deserializedEntry.body.styledSpans!![0].style.linkUrl
        )

        // Verify rubySpans
        assertNotNull(deserializedEntry.body.rubySpans)
        assertEquals(1, deserializedEntry.body.rubySpans!!.size)
        assertEquals(originalEntry.body.rubySpans!![0].rubyText, deserializedEntry.body.rubySpans!![0].rubyText)
    }

    @Test
    fun testSerializeMultipleEntries() {
        val entries = listOf(
            DictionaryEntry(
                headword = "first",
                pronunciation = null,
                body = StyledText("First entry", null, null, null)
            ),
            DictionaryEntry(
                headword = "second",
                pronunciation = null,
                body = StyledText("Second entry", null, null, null)
            ),
            DictionaryEntry(
                headword = "third",
                pronunciation = null,
                body = StyledText("Third entry", null, null, null)
            )
        )

        val json = DictionaryEntrySerializer.toJson(entries)

        // Verify all entries are in JSON
        assertTrue(json.contains("\"first\""))
        assertTrue(json.contains("\"second\""))
        assertTrue(json.contains("\"third\""))
        assertTrue(json.contains("First entry"))
        assertTrue(json.contains("Second entry"))
        assertTrue(json.contains("Third entry"))

        // Deserialize and verify count
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)
        assertEquals(3, deserializedEntries.size)
    }

    @Test
    fun testDeserializeEmptyArray() {
        val json = "[]"
        val entries = DictionaryEntrySerializer.fromJson(json)

        assertNotNull(entries)
        assertEquals(0, entries.size)
    }

    @Test
    fun testRoundTripMinimalEntry() {
        // Test minimal entry with only required fields
        val originalEntry = DictionaryEntry(
            headword = "test",
            pronunciation = null,
            body = StyledText(
                text = "test definition",
                blockSpans = null,
                styledSpans = null,
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        assertEquals(1, deserializedEntries.size)
        val deserializedEntry = deserializedEntries[0]

        assertEquals(originalEntry.headword, deserializedEntry.headword)
        assertNull(deserializedEntry.pronunciation)
        assertEquals(originalEntry.body.text, deserializedEntry.body.text)
        assertNull(deserializedEntry.body.blockSpans)
        assertNull(deserializedEntry.body.styledSpans)
        assertNull(deserializedEntry.body.rubySpans)
    }

    @Test
    fun testRoundTripWithOnlyBlockSpans() {
        // Test entry with only blockSpans populated
        val originalEntry = DictionaryEntry(
            headword = "blocks",
            pronunciation = null,
            body = StyledText(
                text = "line one\nline two",
                blockSpans = arrayOf(
                    BlockSpan(0, 8, 0, 0, null, 0),
                    BlockSpan(9, 17, 1, 1, "•", 0xFFEEEEEE.toInt())
                ),
                styledSpans = null,
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        assertEquals(1, deserializedEntries.size)
        val deserializedEntry = deserializedEntries[0]

        assertEquals(originalEntry.headword, deserializedEntry.headword)
        assertEquals(originalEntry.body.text, deserializedEntry.body.text)
        assertNotNull(deserializedEntry.body.blockSpans)
        assertEquals(2, deserializedEntry.body.blockSpans!!.size)
        assertEquals(originalEntry.body.blockSpans!![0].blockType, deserializedEntry.body.blockSpans!![0].blockType)
        assertEquals(originalEntry.body.blockSpans!![1].listMarker, deserializedEntry.body.blockSpans!![1].listMarker)
        assertEquals(
            originalEntry.body.blockSpans!![1].backgroundColor,
            deserializedEntry.body.blockSpans!![1].backgroundColor
        )
    }

    @Test
    fun testRoundTripWithOnlyStyledSpans() {
        // Test entry with only styledSpans populated
        val originalEntry = DictionaryEntry(
            headword = "styled",
            pronunciation = null,
            body = StyledText(
                text = "bold italic both",
                blockSpans = null,
                styledSpans = arrayOf(
                    StyledSpan(
                        0,
                        4,
                        InlineStyle(
                            bold = true,
                            italic = false,
                            fontSize = 1.0f,
                            foregroundColor = 0xFF000000.toInt(),
                            textBackgroundColor = 0,
                            hoverText = null,
                            linkUrl = null
                        )
                    ),
                    StyledSpan(
                        5,
                        11,
                        InlineStyle(
                            bold = false,
                            italic = true,
                            fontSize = 1.0f,
                            foregroundColor = 0xFF000000.toInt(),
                            textBackgroundColor = 0,
                            hoverText = null,
                            linkUrl = null
                        )
                    ),
                    StyledSpan(
                        12,
                        16,
                        InlineStyle(
                            bold = true,
                            italic = true,
                            fontSize = 1.2f,
                            foregroundColor = 0xFF0000FF.toInt(),
                            textBackgroundColor = 0xFFFFFF00.toInt(),
                            hoverText = "hover",
                            linkUrl = "link"
                        )
                    )
                ),
                rubySpans = null
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        assertEquals(1, deserializedEntries.size)
        val deserializedEntry = deserializedEntries[0]

        assertEquals(originalEntry.body.text, deserializedEntry.body.text)
        assertNotNull(deserializedEntry.body.styledSpans)
        assertEquals(3, deserializedEntry.body.styledSpans!!.size)

        // Check first span (bold)
        assertEquals(originalEntry.body.styledSpans!![0].style.bold, deserializedEntry.body.styledSpans!![0].style.bold)
        assertEquals(
            originalEntry.body.styledSpans!![0].style.italic,
            deserializedEntry.body.styledSpans!![0].style.italic
        )

        // Check second span (italic)
        assertEquals(originalEntry.body.styledSpans!![1].style.bold, deserializedEntry.body.styledSpans!![1].style.bold)
        assertEquals(
            originalEntry.body.styledSpans!![1].style.italic,
            deserializedEntry.body.styledSpans!![1].style.italic
        )

        // Check third span (both + colors + hover + link)
        assertEquals(originalEntry.body.styledSpans!![2].style.bold, deserializedEntry.body.styledSpans!![2].style.bold)
        assertEquals(
            originalEntry.body.styledSpans!![2].style.italic,
            deserializedEntry.body.styledSpans!![2].style.italic
        )
        assertEquals(
            originalEntry.body.styledSpans!![2].style.fontSize,
            deserializedEntry.body.styledSpans!![2].style.fontSize,
            0.01f
        )
        assertEquals(
            originalEntry.body.styledSpans!![2].style.foregroundColor,
            deserializedEntry.body.styledSpans!![2].style.foregroundColor
        )
        assertEquals(
            originalEntry.body.styledSpans!![2].style.textBackgroundColor,
            deserializedEntry.body.styledSpans!![2].style.textBackgroundColor
        )
        assertEquals(
            originalEntry.body.styledSpans!![2].style.hoverText,
            deserializedEntry.body.styledSpans!![2].style.hoverText
        )
        assertEquals(
            originalEntry.body.styledSpans!![2].style.linkUrl,
            deserializedEntry.body.styledSpans!![2].style.linkUrl
        )
    }

    @Test
    fun testRoundTripWithOnlyRubySpans() {
        // Test entry with only rubySpans populated (no pronunciation on entry)
        val originalEntry = DictionaryEntry(
            headword = "漢字",
            pronunciation = null,
            body = StyledText(
                text = "漢字の例文",
                blockSpans = null,
                styledSpans = null,
                rubySpans = arrayOf(
                    RubySpan(0, 1, "かん"),
                    RubySpan(1, 2, "じ"),
                    RubySpan(3, 5, "れいぶん")
                )
            )
        )

        val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        assertEquals(1, deserializedEntries.size)
        val deserializedEntry = deserializedEntries[0]

        assertEquals(originalEntry.body.text, deserializedEntry.body.text)
        assertNull(deserializedEntry.pronunciation)
        assertNotNull(deserializedEntry.body.rubySpans)
        assertEquals(3, deserializedEntry.body.rubySpans!!.size)
        assertEquals(originalEntry.body.rubySpans!![0].rubyText, deserializedEntry.body.rubySpans!![0].rubyText)
        assertEquals(originalEntry.body.rubySpans!![1].rubyText, deserializedEntry.body.rubySpans!![1].rubyText)
        assertEquals(originalEntry.body.rubySpans!![2].rubyText, deserializedEntry.body.rubySpans!![2].rubyText)
    }

    @Test
    fun testRoundTripMultipleComplexEntries() {
        // Test multiple entries with various field combinations
        val entries = listOf(
            DictionaryEntry(
                headword = "first",
                pronunciation = arrayOf(RubySpan(0, 5, "ふぁーすと")),
                body = StyledText("First entry", null, null, null)
            ),
            DictionaryEntry(
                headword = "second",
                pronunciation = null,
                body = StyledText(
                    text = "Second entry with styles",
                    blockSpans = arrayOf(BlockSpan(0, 24, 1, 0, "①", 0)),
                    styledSpans = arrayOf(
                        StyledSpan(
                            0,
                            6,
                            InlineStyle(
                                bold = true,
                                italic = false,
                                fontSize = 1.0f,
                                foregroundColor = 0,
                                textBackgroundColor = 0,
                                hoverText = null,
                                linkUrl = null
                            )
                        )
                    ),
                    rubySpans = null
                )
            ),
            DictionaryEntry(
                headword = "漢字",
                pronunciation = arrayOf(
                    RubySpan(0, 1, "かん"),
                    RubySpan(1, 2, "じ")
                ),
                body = StyledText(
                    text = "Third entry complete",
                    blockSpans = arrayOf(BlockSpan(0, 20, 0, 0, null, 0xFFFFFFFF.toInt())),
                    styledSpans = arrayOf(
                        StyledSpan(
                            0,
                            5,
                            InlineStyle(
                                bold = true,
                                italic = true,
                                fontSize = 1.5f,
                                foregroundColor = 0xFF000000.toInt(),
                                textBackgroundColor = 0xFFFFFF00.toInt(),
                                hoverText = "hover",
                                linkUrl = "lookup:third"
                            )
                        )
                    ),
                    rubySpans = arrayOf(RubySpan(0, 5, "さーど"))
                )
            )
        )

        val json = DictionaryEntrySerializer.toJson(entries)
        val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

        assertEquals(3, deserializedEntries.size)

        // Verify first entry
        assertEquals(entries[0].headword, deserializedEntries[0].headword)
        assertNotNull(deserializedEntries[0].pronunciation)
        assertEquals(1, deserializedEntries[0].pronunciation!!.size)
        assertEquals(entries[0].pronunciation!![0].rubyText, deserializedEntries[0].pronunciation!![0].rubyText)

        // Verify second entry
        assertEquals(entries[1].headword, deserializedEntries[1].headword)
        assertNull(deserializedEntries[1].pronunciation)
        assertNotNull(deserializedEntries[1].body.blockSpans)
        assertNotNull(deserializedEntries[1].body.styledSpans)
        assertEquals(entries[1].body.blockSpans!![0].listMarker, deserializedEntries[1].body.blockSpans!![0].listMarker)

        // Verify third entry (complete with all fields)
        assertEquals(entries[2].headword, deserializedEntries[2].headword)
        assertNotNull(deserializedEntries[2].pronunciation)
        assertEquals(2, deserializedEntries[2].pronunciation!!.size)
        assertNotNull(deserializedEntries[2].body.blockSpans)
        assertNotNull(deserializedEntries[2].body.styledSpans)
        assertNotNull(deserializedEntries[2].body.rubySpans)
        assertEquals(
            entries[2].body.styledSpans!![0].style.hoverText,
            deserializedEntries[2].body.styledSpans!![0].style.hoverText
        )
        assertEquals(
            entries[2].body.styledSpans!![0].style.linkUrl,
            deserializedEntries[2].body.styledSpans!![0].style.linkUrl
        )
    }

    @Test
    fun testRoundTripPreservesFloatPrecision() {
        // Test that fontSize float values are preserved accurately
        val testFontSizes = listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 3.14159f)

        for (fontSize in testFontSizes) {
            val originalEntry = DictionaryEntry(
                headword = "font-test",
                pronunciation = null,
                body = StyledText(
                    text = "font size test",
                    blockSpans = null,
                    styledSpans = arrayOf(
                        StyledSpan(
                            0,
                            14,
                            InlineStyle(
                                bold = false,
                                italic = false,
                                fontSize = fontSize,
                                foregroundColor = 0,
                                textBackgroundColor = 0,
                                hoverText = null,
                                linkUrl = null
                            )
                        )
                    ),
                    rubySpans = null
                )
            )

            val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
            val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

            assertEquals(1, deserializedEntries.size)
            val deserializedEntry = deserializedEntries[0]

            assertNotNull(deserializedEntry.body.styledSpans)
            assertEquals(1, deserializedEntry.body.styledSpans!!.size)

            // Allow small floating-point precision tolerance
            assertEquals(
                "fontSize $fontSize not preserved accurately",
                fontSize,
                deserializedEntry.body.styledSpans!![0].style.fontSize,
                0.0001f
            )
        }
    }

    @Test
    fun testRoundTripPreservesColorValues() {
        // Test that ARGB color values are preserved accurately
        val testColors = listOf(
            0x00000000, // Transparent
            0xFF000000.toInt(), // Black
            0xFFFFFFFF.toInt(), // White
            0xFF123456.toInt(), // Custom color
            0x80ABCDEF.toInt(), // Semi-transparent
            0xFF00FF00.toInt()  // Green
        )

        for (color in testColors) {
            val originalEntry = DictionaryEntry(
                headword = "color-test",
                pronunciation = null,
                body = StyledText(
                    text = "color test",
                    blockSpans = null,
                    styledSpans = arrayOf(
                        StyledSpan(
                            0,
                            10,
                            InlineStyle(
                                bold = false,
                                italic = false,
                                fontSize = 1.0f,
                                foregroundColor = color,
                                textBackgroundColor = color,
                                hoverText = null,
                                linkUrl = null
                            )
                        )
                    ),
                    rubySpans = null
                )
            )

            val json = DictionaryEntrySerializer.toJson(listOf(originalEntry))
            val deserializedEntries = DictionaryEntrySerializer.fromJson(json)

            assertEquals(1, deserializedEntries.size)
            val deserializedEntry = deserializedEntries[0]

            assertNotNull(deserializedEntry.body.styledSpans)
            assertEquals(
                "foregroundColor $color not preserved",
                color,
                deserializedEntry.body.styledSpans!![0].style.foregroundColor
            )
            assertEquals(
                "textBackgroundColor $color not preserved",
                color,
                deserializedEntry.body.styledSpans!![0].style.textBackgroundColor
            )
        }
    }
}
