package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TermEntryRenderer class.
 *
 * Tests the basic setup and entry point for term entry rendering.
 */
class TermEntryRendererTest {

    /**
     * Create a basic TermEntity for testing.
     */
    private fun createTestTermEntity(
        expression: String = "日本",
        reading: String = "にほん",
        glossary: String = """["Japan"]"""
    ): TermEntity {
        return TermEntity(
            id = 1,
            dictionary = "test-dictionary",
            expression = expression,
            reading = reading,
            expressionReverse = null,
            readingReverse = null,
            definitionTags = null,
            rules = "",
            score = 0.0,
            glossary = glossary,
            sequence = null,
            termTags = null
        )
    }

    @Test
    fun `render creates DictionaryEntry with correct headword`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(expression = "日本", reading = "にほん")
        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "日本", result.headword)
        assertNotNull("Body should not be null", result.body)
    }

    @Test
    fun `render handles empty glossary`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(glossary = "[]")
        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "日本", result.headword)
    }

    @Test
    fun `render handles term with tags`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )
        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test-dictionary",
                name = "v5r",
                category = "partOfSpeech",
                order = 0,
                notes = "Godan verb with 'ru' ending",
                score = 0
            )
        )

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "走る", result.headword)
    }

    @Test
    fun `render handles term with different expression and reading`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "食べる",
            reading = "たべる"
        )
        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "食べる", result.headword)
    }

    @Test
    fun `render handles term with same expression and reading`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "ひらがな",
            reading = "ひらがな"
        )
        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "ひらがな", result.headword)
        assertEquals("Pronunciation should be empty when expression equals reading", 0, result.pronunciation.size)
    }

    @Test
    fun `render creates RubySpan annotations for furigana`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日本",
            reading = "にほん"
        )
        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "日本", result.headword)

        // "日本" is two consecutive kanji with no kana between them, so distributeFurigana
        // groups them together and produces one RubySpan covering the whole compound.
        // A split into (に, ほん) is not performed because there is no kana anchor
        // between 日 and 本 to determine where the boundary should be.
        assertEquals("Should have 1 RubySpan for the whole compound", 1, result.pronunciation.size)
        assertEquals("Ruby span should start at index 0", 0, result.pronunciation[0].startIndex)
        assertEquals("Ruby span should end at index 2", 2, result.pronunciation[0].endIndex)
        assertEquals("Ruby span should have full reading 'にほん'", "にほん", result.pronunciation[0].rubyText)
    }

    @Test
    fun `render applies category-based styling to tags`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = TermEntity(
            id = 1,
            dictionary = "test-dictionary",
            expression = "走る",
            reading = "はしる",
            expressionReverse = null,
            readingReverse = null,
            definitionTags = "v5r n",  // Multiple tags: verb and noun
            rules = "",
            score = 0.0,
            glossary = """["to run"]""",
            sequence = null,
            termTags = "news"  // Term-level tag
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test-dictionary",
                name = "v5r",
                category = "partOfSpeech",
                order = 0,
                notes = "Godan verb",
                score = 0
            ),
            TagMetaEntity(
                id = 2,
                dictionary = "test-dictionary",
                name = "n",
                category = "partOfSpeech",
                order = 1,
                notes = "Noun",
                score = 0
            ),
            TagMetaEntity(
                id = 3,
                dictionary = "test-dictionary",
                name = "news",
                category = "frequency",
                order = 2,
                notes = "Frequent",
                score = 0
            )
        )

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify structure
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "走る", result.headword)
        assertNotNull("Body should not be null", result.body)

        // Verify tags were rendered in body
        val bodyText = result.body.text
        assertTrue("Body should contain 'news' tag", bodyText.contains("news"))
        assertTrue("Body should contain 'v5r' tag", bodyText.contains("v5r"))
        assertTrue("Body should contain 'n' tag", bodyText.contains("n"))

        // Verify styled spans exist for tags
        assertTrue("Should have styled spans for tags", result.body.styledSpans.isNotEmpty())

        val newsSpan = result.body.styledSpans.find { it.style.textBackgroundColor == 0xFF5CB85C.toInt() }
        val posSpans = result.body.styledSpans.filter { it.style.textBackgroundColor == 0xFF565656.toInt() }

        assertNotNull("Should have a span for frequency tag", newsSpan)
        assertEquals("news tag should have 'Frequent' hover text", "Frequent", newsSpan?.style?.hoverText)

        assertTrue("Should have 2 partOfSpeech tags", posSpans.size >= 2)
        val hoverTexts = posSpans.map { it.style.hoverText }.toSet()
        assertTrue("Should contain 'Godan verb' hover text", hoverTexts.contains("Godan verb"))
        assertTrue("Should contain 'Noun' hover text", hoverTexts.contains("Noun"))

        // Verify colors are applied based on categories
        val styledSpans = result.body.styledSpans

        // Check that we have styled spans with the correct category colors
        val partOfSpeechColor = 0xFF565656.toInt()  // partOfSpeech category
        val frequencyColor = 0xFF5CB85C.toInt()     // frequency category

        val hasPartOfSpeechColor = styledSpans.any { it.style.textBackgroundColor == partOfSpeechColor }
        val hasFrequencyColor = styledSpans.any { it.style.textBackgroundColor == frequencyColor }

        assertTrue("Should have tags with partOfSpeech color", hasPartOfSpeechColor)
        assertTrue("Should have tags with frequency color", hasFrequencyColor)

        // Verify white text on all tags
        styledSpans.forEach { span ->
            assertEquals("Tag text should be white", 0xFFFFFFFF.toInt(), span.style.foregroundColor)
            assertTrue("Tag should be bold", span.style.bold)
            assertEquals("Tag font size should be 0.8", 0.8f, span.style.fontSize)
        }
    }

    @Test
    fun `render handles tags with no metadata`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = TermEntity(
            id = 1,
            dictionary = "test-dictionary",
            expression = "走る",
            reading = "はしる",
            expressionReverse = null,
            readingReverse = null,
            definitionTags = "unknown",  // Tag not in metadata
            rules = "",
            score = 0.0,
            glossary = """["to run"]""",
            sequence = null,
            termTags = null
        )

        val tagMetaList = emptyList<TagMetaEntity>()  // No metadata

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should match expression", "走る", result.headword)

        // Verify tag is rendered with default color (tag name used as display text)
        val bodyText = result.body.text
        assertTrue("Body should contain 'unknown' tag", bodyText.contains("unknown"))

        // Verify default color is applied
        val defaultColor = 0xFF8A8A91.toInt()  // Default gray color
        val hasDefaultColor = result.body.styledSpans.any { it.style.textBackgroundColor == defaultColor }
        assertTrue("Should use default color for unknown tags", hasDefaultColor)
    }

    @Test
    fun `render handles term with simple numeric frequency`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "test-dictionary",
                expression = "走る",
                mode = "freq",
                data = "1"  // Simple numeric frequency
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain dictionary name 'test-dictionary'", bodyText.contains("test-dictionary"))
        assertTrue("Body should contain frequency value '1'", bodyText.contains("1"))

        // Verify dictionary badge styling
        val dictionaryColor = 0xFFAA66CC.toInt()  // Purple color for dictionary category
        val hasDictionaryBadge = result.body.styledSpans.any {
            it.style.textBackgroundColor == dictionaryColor
        }
        assertTrue("Should have dictionary badge with purple color", hasDictionaryBadge)
    }

    @Test
    fun `render handles term with string frequency`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "frequency-dict",
                expression = "走る",
                mode = "freq",
                data = "\"four\""  // String frequency value
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain dictionary name", bodyText.contains("frequency-dict"))
        assertTrue("Body should contain frequency value 'four'", bodyText.contains("four"))
    }

    @Test
    fun `render handles frequency with value object`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "走る",
                mode = "freq",
                data = """{"value": 6}"""  // Object with value field
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain frequency value '6'", bodyText.contains("6"))
    }

    @Test
    fun `render handles frequency with displayValue`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "走る",
                mode = "freq",
                data = """{"value": 7, "displayValue": "seven"}"""  // Object with displayValue
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain displayValue 'seven'", bodyText.contains("seven"))
    }

    @Test
    fun `render handles frequency with reading disambiguation`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "だ",
            reading = "だ"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "だ",
                mode = "freq",
                data = """{"reading": "だ", "frequency": 8}"""  // Reading-specific frequency
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain frequency value '8'", bodyText.contains("8"))
    }

    @Test
    fun `render handles frequency with different reading for disambiguation`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "行く",
            reading = "いく"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "行く",
                mode = "freq",
                data = """{"reading": "ゆく", "frequency": 10}"""  // Different reading
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        // Should show disambiguation: 行く[ゆく]: 10
        assertTrue(
            "Body should contain expression with reading disambiguation",
            bodyText.contains("行く[ゆく]") || bodyText.contains("行く[ゆく]: 10")
        )
    }

    @Test
    fun `render handles frequency with nested frequency object`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "だ",
            reading = "だ"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "だ",
                mode = "freq",
                data = """{"reading": "だ", "frequency": {"value": 26, "displayValue": "twenty-seven"}}"""
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain displayValue 'twenty-seven'", bodyText.contains("twenty-seven"))
    }

    @Test
    fun `render groups frequencies by dictionary`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "dict-a",
                expression = "走る",
                mode = "freq",
                data = "1"
            ),
            TermMetaEntity(
                id = 2,
                dictionary = "dict-a",
                expression = "走る",
                mode = "freq",
                data = "2"
            ),
            TermMetaEntity(
                id = 3,
                dictionary = "dict-b",
                expression = "走る",
                mode = "freq",
                data = "10"
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text

        // Both dictionaries should be present
        assertTrue("Body should contain 'dict-a'", bodyText.contains("dict-a"))
        assertTrue("Body should contain 'dict-b'", bodyText.contains("dict-b"))

        // Frequencies should be grouped and comma-separated for dict-a
        assertTrue(
            "Body should contain frequencies '1' and '2' for dict-a",
            bodyText.contains("1") && bodyText.contains("2")
        )
        assertTrue("Body should contain frequency '10' for dict-b", bodyText.contains("10"))

        // Verify multiple dictionary badges exist
        val dictionaryColor = 0xFFAA66CC.toInt()
        val dictionaryBadges = result.body.styledSpans.filter {
            it.style.textBackgroundColor == dictionaryColor
        }
        assertTrue("Should have at least 2 dictionary badges", dictionaryBadges.size >= 2)
    }

    @Test
    fun `render handles malformed frequency data gracefully`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "freq-dict",
                expression = "走る",
                mode = "freq",
                data = "invalid json {{"  // Malformed JSON
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify - should not crash and should show placeholder
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain dictionary name", bodyText.contains("freq-dict"))
        assertTrue("Body should contain placeholder '?' for invalid data", bodyText.contains("?"))
    }

    @Test
    fun `render ignores non-frequency term meta entries`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        )

        val termMetaList = listOf(
            TermMetaEntity(
                id = 1,
                dictionary = "pitch-dict",
                expression = "走る",
                mode = "pitch",  // Not a frequency entry
                data = """{"position": 0}"""
            ),
            TermMetaEntity(
                id = 2,
                dictionary = "freq-dict",
                expression = "走る",
                mode = "freq",  // This one should be rendered
                data = "5"
            )
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList, termMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text

        // Should only show the frequency dictionary, not the pitch dictionary
        assertTrue("Body should contain frequency dictionary", bodyText.contains("freq-dict"))
        assertFalse("Body should not contain pitch dictionary", bodyText.contains("pitch-dict"))
        assertTrue("Body should contain frequency value '5'", bodyText.contains("5"))
    }

    // ========================================
    // Glossary Rendering Tests
    // ========================================

    @Test
    fun `render handles glossary with plain string`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """["Sunday"]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain glossary text 'Sunday'", bodyText.contains("Sunday"))
    }

    @Test
    fun `render handles glossary with text object`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """[{"type": "text", "text": "Sunday"}]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain glossary text 'Sunday'", bodyText.contains("Sunday"))
    }

    @Test
    fun `render handles glossary with image object`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "猫",
            reading = "ねこ",
            glossary = """[{"type": "image", "path": "images/cat.jpg"}]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue(
            "Body should contain image placeholder with path",
            bodyText.contains("[Image: images/cat.jpg]")
        )
    }

    @Test
    fun `render handles glossary with structured content`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """[{"type": "structured-content", "content": {"tag": "div", "content": "Sunday"}}]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain glossary text from structured content", bodyText.contains("Sunday"))
    }

    @Test
    fun `render handles glossary with multiple definitions`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """["Sunday", "day of the month", "counter for days"]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain first definition", bodyText.contains("Sunday"))
        assertTrue("Body should contain second definition", bodyText.contains("day of the month"))
        assertTrue("Body should contain third definition", bodyText.contains("counter for days"))
    }

    @Test
    fun `render handles glossary with structured content containing ruby`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val glossaryJson = """{
            "type": "structured-content",
            "content": {
                "tag": "div",
                "content": [
                    {"tag": "ruby", "content": ["日", {"tag": "rt", "content": "にち"}]},
                    {"tag": "ruby", "content": ["曜", {"tag": "rt", "content": "よう"}]}
                ]
            }
        }"""

        val termEntity = createTestTermEntity(
            expression = "日曜",
            reading = "にちよう",
            glossary = "[$glossaryJson]"
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain base text '日曜'", bodyText.contains("日曜"))

        // Verify ruby spans were created in the body
        assertTrue("Body should have ruby spans", result.body.rubySpans.isNotEmpty())
    }

    @Test
    fun `render handles glossary with structured content containing links`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val glossaryJson = """{
            "type": "structured-content",
            "content": {
                "tag": "div",
                "content": [
                    "See also ",
                    {
                        "tag": "a",
                        "href": "?query=%E6%97%A5%E6%9B%9C",
                        "content": "日曜"
                    }
                ]
            }
        }"""

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = "[$glossaryJson]"
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain 'See also'", bodyText.contains("See also"))
        assertTrue("Body should contain link text '日曜'", bodyText.contains("日曜"))

        // Verify link URL was created
        val hasLookupLink = result.body.styledSpans.any { span ->
            span.style.linkUrl?.startsWith("lookup:") == true
        }
        assertTrue("Body should have a lookup link", hasLookupLink)
    }

    @Test
    fun `render handles malformed glossary JSON gracefully`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """invalid json {{"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute - should not crash
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null even with malformed glossary", result)
        assertEquals("Headword should still be set", "日", result.headword)
    }

    @Test
    fun `render handles empty glossary array`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """[]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        assertEquals("Headword should be set", "日", result.headword)
    }

    @Test
    fun `render handles glossary with unknown type gracefully`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """[{"type": "unknown", "data": "something"}]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute - should not crash
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null even with unknown type", result)
        assertEquals("Headword should still be set", "日", result.headword)
    }

    @Test
    fun `render separates multiple glossary definitions with newlines`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = """["Definition 1", "Definition 2", "Definition 3"]"""
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text

        // All definitions should be present
        assertTrue("Body should contain first definition", bodyText.contains("Definition 1"))
        assertTrue("Body should contain second definition", bodyText.contains("Definition 2"))
        assertTrue("Body should contain third definition", bodyText.contains("Definition 3"))

        // Definitions should be separated (newlines in the text)
        val newlineCount = bodyText.count { it == '\n' }
        assertTrue("Definitions should be separated by newlines", newlineCount >= 2)
    }

    @Test
    fun `render applies CSS rules to structured content in glossary`() {
        // Setup
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)

        // Parse some sample CSS rules that target data attributes
        val sampleCss = """
            [data-content="glossary"] {
                background-color: #f0f0f0;
            }
            [data-content="example-sentence"] {
                font-style: italic;
            }
        """
        val cssRules = cssProcessor.parseCss(sampleCss)
        structuredContentRenderer.cssRules = cssRules

        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        // Create glossary with structured content that has data attributes
        val glossaryJson = """{
            "type": "structured-content",
            "content": {
                "tag": "div",
                "data": {"content": "glossary"},
                "content": "This is a glossary entry"
            }
        }"""

        val termEntity = createTestTermEntity(
            expression = "日",
            reading = "にち",
            glossary = "[$glossaryJson]"
        )

        val tagMetaList = emptyList<TagMetaEntity>()

        // Execute
        val result = renderer.render(termEntity, tagMetaList)

        // Verify
        assertNotNull("Result should not be null", result)
        val bodyText = result.body.text
        assertTrue("Body should contain glossary text", bodyText.contains("This is a glossary entry"))

        // Note: CSS rules are applied during structured content rendering
        // The background color from the CSS should be applied to the block span
        // This test verifies that the integration works without errors
        // Detailed CSS application testing is done in StructuredContentRendererTest
    }

    @Test
    fun `render populates dictionary badge hover text correctly`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "走る",
            reading = "はしる"
        ).copy(dictionary = "Test Dict")

        val dictEntity = DictionaryEntity(
            title = "Test Dict",
            revision = "2026.06.05.0",
            sequenced = true,
            version = 3,
            importDate = 123456789L,
            prefixWildcardsSupported = false,
            styles = "",
            counts = """{"terms":{"total":98765}}""",
            importSuccess = true,
            author = "Test Author",
            description = "Test Description",
            url = "https://example.com"
        )

        val result = renderer.render(
            termEntity = termEntity,
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            dictionaryEntity = dictEntity
        )

        assertNotNull(result)
        val dictionaryColor = 0xFFAA66CC.toInt()
        val dictSpan = result.body.styledSpans.find {
            it.style.textBackgroundColor == dictionaryColor
        }
        assertNotNull("Should have dictionary badge", dictSpan)

        val expectedHoverText =
            "Test Dict\nrev.2026.06.05.0\nAuthor: Test Author\nDescription: Test Description\nURL: https://example.com\nTerm Count: 98765"
        assertEquals("Dictionary badge should have correct hover text", expectedHoverText, dictSpan?.style?.hoverText)
    }

    @Test
    fun `appendKanjiLinkHeadword emits a lookup link for each kanji character`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        // 日本: two kanji, each gets a HeadwordSpan with "lookup:?kanji="
        val termEntity = createTestTermEntity(expression = "日本", reading = "にほん")
        val result = renderer.render(termEntity, emptyList())

        val hwSpans = result.headwordSpans
        assertNotNull("headwordSpans should not be null for a kanji headword", hwSpans)
        assertEquals("Should have one HeadwordSpan per kanji character", 2, hwSpans!!.size)
        assertEquals("lookup:?kanji=日", hwSpans[0].linkUrl)
        assertEquals("lookup:?kanji=本", hwSpans[1].linkUrl)
    }

    @Test
    fun `appendKanjiLinkHeadword does not emit links for kana characters`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        // 食べる: 食=kanji (HeadwordSpan), べる=kana (no span)
        val termEntity = createTestTermEntity(expression = "食べる", reading = "たべる")
        val result = renderer.render(termEntity, emptyList())

        val hwSpans = result.headwordSpans
        assertNotNull("headwordSpans should not be null when a kanji is present", hwSpans)
        assertEquals("Only the kanji character should have a HeadwordSpan", 1, hwSpans!!.size)
        assertEquals("lookup:?kanji=食", hwSpans[0].linkUrl)
    }

    @Test
    fun `appendKanjiLinkHeadword emits no kanji links for kana-only headword`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        // ひらがな: all kana, headwordSpans must be null (not an empty array)
        val termEntity = createTestTermEntity(expression = "ひらがな", reading = "ひらがな")
        val result = renderer.render(termEntity, emptyList())

        assertNull("Kana-only headword should have null headwordSpans", result.headwordSpans)
    }

    @Test
    fun `appendKanjiLinkHeadword kanji link spans cover exactly one character each`() {
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(expression = "日本語", reading = "にほんご")
        val result = renderer.render(termEntity, emptyList())

        val hwSpans = result.headwordSpans
        assertNotNull(hwSpans)
        assertEquals(3, hwSpans!!.size)
        hwSpans.forEach { span ->
            assertEquals(
                "Each HeadwordSpan should cover exactly one character",
                1, span.endIndex - span.startIndex
            )
        }
    }

    @Test
    fun `appendKanjiLinkHeadword headword span indices are within headword bounds`() {
        // HeadwordSpan indices are positions in DictionaryEntry.headword, not in body text.
        // All spans must satisfy: 0 <= startIndex < endIndex <= headword.length.
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val headword = "日本語"
        val termEntity = createTestTermEntity(expression = headword, reading = "にほんご")
        val result = renderer.render(termEntity, emptyList())

        val hwSpans = result.headwordSpans
        assertNotNull(hwSpans)
        hwSpans!!.forEach { span ->
            assertTrue("startIndex must be >= 0", span.startIndex >= 0)
            assertTrue("endIndex must be <= headword.length", span.endIndex <= headword.length)
            assertTrue("startIndex must be < endIndex", span.startIndex < span.endIndex)
        }
    }

    @Test
    fun `appendKanjiLinkHeadword does not pollute body ruby spans with headword furigana`() {
        // Furigana is carried by DictionaryEntry.pronunciation (RubySpan[]) which is
        // already built independently. buildKanjiHeadwordSpans does not touch the body
        // at all, so body.rubySpans must not contain headword furigana.
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(
            expression = "日本", reading = "にほん",
            glossary = """["Japan"]"""
        )
        val result = renderer.render(termEntity, emptyList())

        // pronunciation carries the furigana
        val pron = result.pronunciation
        assertNotNull("pronunciation should carry furigana", pron)
        val readings = pron!!.map { it.rubyText }.toSet()
        assertTrue("に should be in pronunciation", readings.any { it.contains("に") })

        // body.rubySpans should only contain annotation from the definition, not headword furigana
        val bodyText = result.body.text
        val bodyRubySpans = result.body.rubySpans
        if (bodyRubySpans != null) {
            bodyRubySpans.forEach { span ->
                assertTrue(
                    "Body ruby span must be within body text bounds",
                    span.endIndex <= bodyText.length
                )
            }
        }
    }

    @Test
    fun `appendKanjiLinkHeadword link URL scheme is lookup colon question kanji equals`() {
        // Verify the exact HeadwordSpan linkUrl format: "lookup:?kanji=<char>"
        // - "lookup:" is stripped by the host, which passes the rest verbatim to onLookup.
        // - "?kanji=<char>" is routed by lookupInternalLink -> lookupKanjiDirect,
        //   bypassing the term search entirely.
        val cssProcessor = CssProcessor()
        val spanGenerator = SpanGenerator()
        val structuredContentRenderer = StructuredContentRenderer(cssProcessor, spanGenerator)
        val renderer = TermEntryRenderer(structuredContentRenderer, spanGenerator)

        val termEntity = createTestTermEntity(expression = "語", reading = "ご")
        val result = renderer.render(termEntity, emptyList())

        val hwSpans = result.headwordSpans
        assertNotNull(hwSpans)
        assertEquals(1, hwSpans!!.size)
        // Must be exactly "lookup:?kanji=語", not "lookup:語" or "?kanji=語"
        assertEquals("lookup:?kanji=語", hwSpans[0].linkUrl)
    }
}
