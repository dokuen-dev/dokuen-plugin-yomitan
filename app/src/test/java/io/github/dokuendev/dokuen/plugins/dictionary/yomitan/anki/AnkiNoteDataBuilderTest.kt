package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiNoteDataBuilderTest {

    private fun term(
        dictionary: String,
        glossary: String,
        definitionTags: String = "",
        termTags: String = "",
        rules: String = ""
    ) = TermEntity(
        dictionary = dictionary,
        expression = "食べる",
        reading = "たべる",
        definitionTags = definitionTags,
        rules = rules,
        score = 0.0,
        glossary = glossary,
        sequence = 1,
        termTags = termTags
    )

    private fun tag(dictionary: String, name: String, category: String, order: Int, notes: String = "") =
        TagMetaEntity(
            dictionary = dictionary,
            name = name,
            category = category,
            order = order,
            notes = notes,
            score = 0
        )

    @Test
    fun testEveryTermRowBecomesADefinitionInDisplayOrder() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(
                term("Jitendex", "[\"to eat\", \"to live on\"]"),
                term("JMdict", "[\"eat\"]")
            ),
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            cloze = null
        )

        assertEquals(AnkiEntryType.TERM, noteData.type)
        assertEquals("食べる", noteData.expression)
        assertEquals("たべる", noteData.reading)
        assertEquals("Jitendex", noteData.dictionary)
        assertEquals("Jitendex", noteData.dictionaryAlias)
        assertEquals(listOf("Jitendex", "JMdict"), noteData.definitions.map { it.dictionary })
        assertEquals(listOf("to eat", "to live on"), noteData.definitions[0].glossary)
        assertEquals(listOf("eat"), noteData.definitions[1].glossaryPlain)
    }

    @Test
    fun testEachDefinitionGetsItsOwnDictionarysScopedStyles() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("Jitendex", "[\"a\"]"), term("JMdict", "[\"b\"]"), term("Jitendex", "[\"c\"]")),
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            cloze = null,
            dictionaryStyles = mapOf("Jitendex" to "a { color: red; }", "JMdict" to "")
        )

        val jitendexStyles = AnkiDictionaryStyles.scope("a { color: red; }", "Jitendex")
        assertEquals(listOf(jitendexStyles, AnkiScopedStyles(), jitendexStyles), noteData.definitions.map { it.styles })
        assertEquals(
            ".yomitan-glossary [data-dictionary=\"Jitendex\"] a { color: red; }",
            noteData.definitions[0].styles.dictionaryScoped
        )
    }

    @Test
    fun testSearchQueryIsPassedThrough() {
        val term = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("Jitendex", "[]")),
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            cloze = null,
            searchQuery = "食べて"
        )
        assertEquals("食べて", term.searchQuery)
        val kanji = AnkiNoteDataBuilder.buildKanjiNoteData(
            KanjiEntity(
                dictionary = "KANJIDIC", character = "食", onyomi = "", kunyomi = "",
                tags = "", meanings = "[]", stats = null
            ),
            searchQuery = "食"
        )
        assertEquals("食", kanji.searchQuery)
    }

    @Test
    fun testDefinitionTagsIncludeTagsWithoutMetaAndSortByOrderThenName() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("Jitendex", "[\"to eat\"]", definitionTags = "v1 vt obscure v1")),
            tagMetaList = listOf(
                tag("Jitendex", "v1", "partOfSpeech", 0, "Ichidan verb"),
                tag("Jitendex", "vt", "partOfSpeech", -3, "transitive verb")
            ),
            termMetaList = emptyList(),
            cloze = null
        )

        assertEquals(
            listOf(
                AnkiTagItem("vt", "transitive verb"),
                AnkiTagItem("obscure", ""),
                AnkiTagItem("v1", "Ichidan verb")
            ),
            noteData.definitions[0].definitionTags
        )
    }

    @Test
    fun testTermTagsAreMergedAcrossRows() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(
                term("Jitendex", "[\"a\"]", termTags = "⭐ common"),
                term("JMdict", "[\"b\"]", termTags = "common")
            ),
            tagMetaList = listOf(tag("Jitendex", "⭐", "popular", -10), tag("Jitendex", "common", "frequent", -2)),
            termMetaList = emptyList(),
            cloze = null
        )

        // JMdict's "common" has no meta (category "default"), so it is a different tag from
        // Jitendex's "common" (category "frequent") and is not merged into it.
        assertEquals(listOf("⭐", "common", "common"), noteData.termTags.map { it.name })
    }

    @Test
    fun testWordClassesAreTheUnionOfRowRules() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("A", "[]", rules = "v1"), term("B", "[]", rules = "v1 vt")),
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            cloze = null
        )
        assertEquals(listOf("v1", "vt"), noteData.wordClasses)
    }

    @Test
    fun testFrequenciesFollowOriginalParsingReadingFilterAndDictionaryOrder() {
        val metas = listOf(
            TermMetaEntity(
                dictionary = "JPDB",
                expression = "食べる",
                mode = "freq",
                data = "{\"reading\": \"たべる\", \"frequency\": {\"value\": 500, \"displayValue\": \"500㋕\"}}"
            ),
            TermMetaEntity(
                dictionary = "JPDB",
                expression = "食べる",
                mode = "freq",
                data = "{\"reading\": \"くらべる\", \"frequency\": 9}"
            ),
            TermMetaEntity(dictionary = "BCCWJ", expression = "食べる", mode = "freq", data = "1500"),
            TermMetaEntity(dictionary = "Other", expression = "食べる", mode = "freq", data = "\"12 (rare)\""),
            TermMetaEntity(dictionary = "BCCWJ", expression = "食べる", mode = "pitch", data = "{}")
        )

        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("Jitendex", "[]")),
            tagMetaList = emptyList(),
            termMetaList = metas,
            cloze = null,
            dictionaryOrder = listOf("Jitendex", "BCCWJ", "JPDB", "Other")
        )

        assertEquals(
            listOf(
                AnkiFrequencyItem(dictionaryAlias = "BCCWJ", frequency = 1500.0, displayValue = null),
                AnkiFrequencyItem(dictionaryAlias = "JPDB", frequency = 500.0, displayValue = "500㋕"),
                AnkiFrequencyItem(dictionaryAlias = "Other", frequency = 12.0, displayValue = "12 (rare)")
            ),
            noteData.frequencies
        )
    }

    @Test
    fun testFrequenciesCarryTheirDictionarysFrequencyMode() {
        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(term("Jitendex", "[\"to eat\"]")),
            tagMetaList = emptyList(),
            termMetaList = listOf(
                TermMetaEntity(dictionary = "JPDB", expression = "食べる", mode = "freq", data = "184"),
                TermMetaEntity(dictionary = "BCCWJ", expression = "食べる", mode = "freq", data = "199")
            ),
            cloze = null,
            dictionaryOrder = listOf("Jitendex", "JPDB", "BCCWJ"),
            frequencyModes = mapOf("JPDB" to "rank-based")
        )

        assertEquals(listOf("rank-based", null), noteData.frequencies.map { it.frequencyMode })
    }

    @Test
    fun testRedirectGlossaryItemsAreNotPartOfTheDefinition() {
        // A Jitendex redirect entry: a structured-content item, then a [term, [rules]] item.
        val redirect = term(
            "Jitendex",
            "[{\"type\": \"structured-content\", \"content\": \"see 叙述用法\"}, [\"叙述用法\", [\"redirected\"]]]"
        )
        val redirectOnly = term("Other", "[[\"叙述用法\", []]]", termTags = "ignored")

        val noteData = AnkiNoteDataBuilder.buildTermNoteData(
            terms = listOf(redirectOnly, redirect),
            tagMetaList = emptyList(),
            termMetaList = emptyList(),
            cloze = null
        )

        // As in the original, the row with nothing but a redirect is no definition at all.
        assertEquals(listOf("Jitendex"), noteData.definitions.map { it.dictionary })
        assertEquals("Jitendex", noteData.dictionary)
        assertEquals(emptyList<AnkiTagItem>(), noteData.termTags)
        assertEquals(1, noteData.definitions.single().glossary.size)
        assertEquals(listOf("see 叙述用法"), noteData.definitions.single().glossaryPlain)
    }

    @Test
    fun testBuildKanjiNoteDataWithStrokeCountAndFrequency() {
        val kanji = KanjiEntity(
            dictionary = "KANJIDIC",
            character = "食",
            onyomi = "ショク ジキ",
            kunyomi = "た.べる く.う",
            tags = "",
            meanings = "[\"eat\", \"food\"]",
            stats = "{\"strokes\": \"9\", \"grade\": \"2\"}"
        )

        val noteData = AnkiNoteDataBuilder.buildKanjiNoteData(
            kanji = kanji,
            tagMetaList = listOf(tag("KANJIDIC", "strokes", "misc", 0, "Stroke count")),
            kanjiMetaList = listOf(KanjiMetaEntity(dictionary = "KFreq", character = "食", mode = "freq", data = "321"))
        )

        assertEquals(AnkiEntryType.KANJI, noteData.type)
        assertEquals("食", noteData.character)
        assertEquals(listOf("eat", "food"), noteData.glossary)
        assertEquals(listOf("た.べる", "く.う"), noteData.kunyomi)
        assertEquals(listOf("ショク", "ジキ"), noteData.onyomi)
        assertEquals("KANJIDIC", noteData.dictionary)
        assertEquals("9", noteData.strokeCount)
        assertEquals(listOf(AnkiFrequencyItem(dictionaryAlias = "KFreq", frequency = 321.0)), noteData.frequencies)
    }

    @Test
    fun testKanjiStrokeCountNeedsMiscStrokesTagMeta() {
        val kanji = KanjiEntity(
            dictionary = "KANJIDIC", character = "食", onyomi = "", kunyomi = "", tags = "",
            meanings = "[]", stats = "{\"strokes\": \"9\"}"
        )
        assertNull(AnkiNoteDataBuilder.buildKanjiNoteData(kanji).strokeCount)
    }
}
