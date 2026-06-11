package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for KanjiEntryRenderer class.
 */
class KanjiEntryRendererTest {

    private fun createTestKanjiEntity(
        character: String = "日",
        onyomi: String = "ニチ ジツ",
        kunyomi: String = "ひ -び -か",
        tags: String = "",
        meanings: String = """["sun", "day"]""",
        stats: String? = """{"grade": "1", "freq": "1", "strokes": "4", "jlpt": "4"}"""
    ): KanjiEntity {
        return KanjiEntity(
            id = 1,
            dictionary = "test-dictionary",
            character = character,
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = tags,
            meanings = meanings,
            stats = stats
        )
    }

    @Test
    fun `render creates DictionaryEntry with correct headword`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(character = "日")
        val result = renderer.render(kanjiEntity, emptyList())

        assertNotNull(result)
        assertEquals("日", result.headword)
        assertNull(result.pronunciation)
    }

    @Test
    fun `render displays meanings in main table`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            character = "日",
            meanings = """["day", "sun", "Japan"]"""
        )

        val result = renderer.render(kanjiEntity, emptyList())
        val body = result.body

        assertTrue(body.text.contains("| Meaning |"))
        assertTrue(body.text.contains("1. day<br>2. sun<br>3. Japan"))

        val tableSpans = body.blockSpans?.filter { it.blockType == 3 } ?: emptyList()
        assertTrue("Should have a main table block span", tableSpans.isNotEmpty())
    }

    @Test
    fun `render handles empty meanings array`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            meanings = """[]"""
        )
        val result = renderer.render(kanjiEntity, emptyList())

        assertFalse(result.body.text.contains("| Meaning |"))
    }

    @Test
    fun `render handles single meaning`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            meanings = """["day"]"""
        )
        val result = renderer.render(kanjiEntity, emptyList())

        assertTrue(result.body.text.contains("| Meaning |"))
        assertTrue(result.body.text.contains("1. day"))
    }

    @Test
    fun `render applies bold styling to main table headers`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity()
        val result = renderer.render(kanjiEntity, emptyList())

        val body = result.body
        val meaningIndex = body.text.indexOf("Meaning")
        assertTrue(meaningIndex >= 0)

        val headerSpan = body.styledSpans!!.find {
            it.startIndex == meaningIndex && it.endIndex == meaningIndex + "Meaning".length
        }

        assertNotNull(headerSpan)
        assertTrue(headerSpan!!.style.bold)
    }

    @Test
    fun `render displays readings in main table`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            onyomi = "ニチ ジツ",
            kunyomi = "ひ -び -か"
        )
        val result = renderer.render(kanjiEntity, emptyList())
        val body = result.body

        assertTrue(body.text.contains("| Readings |"))
        assertTrue(body.text.contains("ニチ<br>ジツ<br><br>ひ<br>-び<br>-か"))
    }

    @Test
    fun `render omits kun-readings when empty`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            onyomi = "ニチ ジツ",
            kunyomi = ""
        )
        val result = renderer.render(kanjiEntity, emptyList())
        val body = result.body

        assertTrue(body.text.contains("| Readings |"))
        assertTrue(body.text.contains("ニチ<br>ジツ"))
        assertFalse(body.text.contains("<br><br>"))
    }

    @Test
    fun `render omits on-readings when empty`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            onyomi = "",
            kunyomi = "ひ -び"
        )
        val result = renderer.render(kanjiEntity, emptyList())
        val body = result.body

        assertTrue(body.text.contains("| Readings |"))
        assertTrue(body.text.contains("ひ<br>-び"))
        assertFalse(body.text.contains("<br><br>"))
    }

    @Test
    fun `render handles readings with whitespace`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            onyomi = "  ニチ   ジツ  ",
            kunyomi = " ひ  -び "
        )
        val result = renderer.render(kanjiEntity, emptyList())
        val body = result.body

        assertTrue(body.text.contains("ニチ<br>ジツ<br><br>ひ<br>-び"))
    }

    @Test
    fun `render displays statistics table with frequency, grade, jlpt, and strokes`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"freq": "1", "grade": "1", "jlpt": "4", "strokes": "4"}"""
        )

        // @formatter:off
        val tagMetaList = listOf(
            TagMetaEntity(id = 1, dictionary = "test", name = "freq", category = "misc", notes = "Frequency", order = 0, score = 0),
            TagMetaEntity(id = 2, dictionary = "test", name = "grade", category = "misc", notes = "Grade level", order = 1, score = 0),
            TagMetaEntity(id = 3, dictionary = "test", name = "jlpt", category = "misc", notes = "JLPT level", order = 2, score = 0),
            TagMetaEntity(id = 4, dictionary = "test", name = "strokes", category = "misc", notes = "Stroke count", order = 3, score = 0)
        )
        // @formatter:on

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        assertTrue(body.text.contains("| Statistics |"))
        assertTrue(body.text.contains("Frequency: 1<br>Grade level: 1<br>JLPT level: 4<br>Stroke count: 4"))
    }

    @Test
    fun `render displays classifications section when data is present`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"deroo": "3878"}"""
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test",
                name = "deroo",
                category = "class",
                notes = "2001 Kanji",
                order = 0,
                score = 0
            )
        )

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        assertTrue(body.text.contains("| Classifications |  |"))
        assertTrue(body.text.contains("| 2001 Kanji | 3878 |"))

        val classSpans = body.blockSpans?.filter { it.blockType == 3 } ?: emptyList()
        assertTrue("Should have multiple table block spans", classSpans.size >= 2)
    }

    @Test
    fun `render displays codepoints section when data is present`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"jis208": "1-38-92"}"""
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test",
                name = "jis208",
                category = "code",
                notes = "JIS X",
                order = 0,
                score = 0
            )
        )

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        assertTrue(body.text.contains("| Codepoints |  |"))
        assertTrue(body.text.contains("| JIS X | 1-38-92 |"))
    }

    @Test
    fun `render displays dictionary indices section when data is present`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"heisig": "12"}"""
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test",
                name = "heisig",
                category = "index",
                notes = "Heisig",
                order = 0,
                score = 0
            )
        )

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        assertTrue(body.text.contains("| Dictionary Indices |  |"))
        assertTrue(body.text.contains("| Heisig | 12 |"))
    }

    @Test
    fun `render sorts stats within each optional section by tag meta order`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"skip": "4-4-1", "deroo": "3878"}"""
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test",
                name = "deroo",
                category = "class",
                notes = "2001 Kanji",
                order = 0,
                score = 0
            ),
            TagMetaEntity(
                id = 2,
                dictionary = "test",
                name = "skip",
                category = "class",
                notes = "SKIP code",
                order = 2,
                score = 0
            )
        )

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        val derooIndex = body.text.indexOf("2001 Kanji | 3878")
        val skipIndex = body.text.indexOf("SKIP code | 4-4-1")

        assertTrue(derooIndex >= 0)
        assertTrue(skipIndex >= 0)
        assertTrue(derooIndex < skipIndex)
    }

    @Test
    fun `render sorts statistics alphabetically by display name when order matches`() {
        val spanGenerator = SpanGenerator()
        val renderer = KanjiEntryRenderer(spanGenerator)

        val kanjiEntity = createTestKanjiEntity(
            stats = """{"freq": "1", "grade": "1"}"""
        )

        val tagMetaList = listOf(
            TagMetaEntity(
                id = 1,
                dictionary = "test",
                name = "grade",
                category = "misc",
                notes = "Grade level",
                order = 0,
                score = 0
            ),
            TagMetaEntity(
                id = 2,
                dictionary = "test",
                name = "freq",
                category = "misc",
                notes = "Frequency",
                order = 0,
                score = 0
            )
        )

        val result = renderer.render(kanjiEntity, tagMetaList)
        val body = result.body

        val freqIndex = body.text.indexOf("Frequency: 1")
        val gradeIndex = body.text.indexOf("Grade level: 1")

        assertTrue(freqIndex >= 0)
        assertTrue(gradeIndex >= 0)
        assertTrue(freqIndex < gradeIndex)
    }
}
