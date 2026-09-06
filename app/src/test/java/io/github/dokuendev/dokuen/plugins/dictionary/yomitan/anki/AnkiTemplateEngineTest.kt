package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected strings are what the original Yomitan's default field templates
 * (ext/data/templates/default-anki-field-templates.handlebars) produce under default options:
 * "group" result output mode, non-compact tags and non-compact glossaries.
 */
class AnkiTemplateEngineTest {

    private val div = "<div style=\"text-align: left;\" class=\"yomitan-glossary\">"

    private val jitendex = AnkiDefinition(
        dictionary = "Jitendex",
        definitionTags = listOf(AnkiTagItem("v1", "Ichidan verb"), AnkiTagItem("vt", "transitive verb")),
        glossary = listOf("to eat", "to live on"),
        glossaryPlain = listOf("to eat", "to live on")
    )
    private val jmdict = AnkiDefinition(
        dictionary = "JMdict",
        glossary = listOf("eat"),
        glossaryPlain = listOf("eat")
    )

    private val termData = AnkiNoteData(
        type = AnkiEntryType.TERM,
        expression = "食べる",
        reading = "たべる",
        definitions = listOf(jitendex, jmdict),
        termTags = listOf(AnkiTagItem("common", "")),
        wordClasses = listOf("v1"),
        frequencies = listOf(AnkiFrequencyItem(dictionaryAlias = "BCCWJ", frequency = 123.0)),
        dictionary = "Jitendex",
        cloze = AnkiClozeData(
            sentence = "彼は毎日食べる。",
            prefix = "彼は毎日",
            body = "食べる",
            bodyKana = "たべる",
            suffix = "。"
        )
    )

    private val kanjiData = AnkiNoteData(
        type = AnkiEntryType.KANJI,
        character = "食",
        glossary = listOf("eat", "food"),
        kunyomi = listOf("た.べる", "く.う"),
        onyomi = listOf("ショク", "ジキ"),
        dictionary = "KANJIDIC",
        strokeCount = "9"
    )

    private fun render(template: String, data: AnkiNoteData = termData) =
        AnkiTemplateEngine.renderField(template, data).text

    @Test
    fun testSimpleMarkerSubstitution() {
        val result = AnkiTemplateEngine.renderField("{expression}", termData)
        assertEquals("食べる", result.text)
        assertTrue(result.unknownMarkers.isEmpty())
    }

    @Test
    fun testLiteralTextMixedWithMultipleMarkers() {
        assertEquals("食べる (たべる)", render("{expression} ({reading})"))
    }

    @Test
    fun testEscapedMarkersUseHandlebarsEscaping() {
        val data = AnkiNoteData(type = AnkiEntryType.TERM, expression = "A&B<'=\">", reading = "`")
        assertEquals("A&amp;B&lt;&#x27;&#x3D;&quot;&gt;", render("{expression}", data))
        assertEquals("&#x60;", render("{reading}", data))
    }

    @Test
    fun testGlossaryGroupsEveryDefinitionWithTagAndDictionaryPrefix() {
        assertEquals(
            "${div}<ol>" +
                    "<li data-dictionary=\"Jitendex\"><i>(v1, vt, Jitendex)</i> <ul><li>to eat</li><li>to live on</li></ul></li>" +
                    "<li data-dictionary=\"JMdict\"><i>(JMdict)</i> eat</li>" +
                    "</ol></div>",
            render("{glossary}")
        )
    }

    @Test
    fun testGlossaryBriefOmitsThePrefixButKeepsAllItems() {
        assertEquals(
            "${div}<ol>" +
                    "<li data-dictionary=\"Jitendex\"><ul><li>to eat</li><li>to live on</li></ul></li>" +
                    "<li data-dictionary=\"JMdict\">eat</li>" +
                    "</ol></div>",
            render("{glossary-brief}")
        )
    }

    @Test
    fun testGlossaryNoDictionaryKeepsTagsOnly() {
        assertEquals(
            "${div}<ol>" +
                    "<li data-dictionary=\"Jitendex\"><i>(v1, vt)</i> <ul><li>to eat</li><li>to live on</li></ul></li>" +
                    "<li data-dictionary=\"JMdict\">eat</li>" +
                    "</ol></div>",
            render("{glossary-no-dictionary}")
        )
    }

    @Test
    fun testGlossaryFirstVariantsUseOnlyTheFirstDefinition() {
        assertEquals(
            "$div<i>(v1, vt, Jitendex)</i> <ul><li>to eat</li><li>to live on</li></ul></div>",
            render("{glossary-first}")
        )
        assertEquals("$div<ul><li>to eat</li><li>to live on</li></ul></div>", render("{glossary-first-brief}"))
        assertEquals(
            "$div<i>(v1, vt)</i> <ul><li>to eat</li><li>to live on</li></ul></div>",
            render("{glossary-first-no-dictionary}")
        )
    }

    private val styledTermData = termData.copy(
        definitions = listOf(jitendex.copy(styles = AnkiScopedStyles(".g a { }", ".g [d] a { }")), jmdict)
    )

    @Test
    fun testGlossaryFollowsEachStyledDefinitionWithItsDictionaryScopedStyles() {
        assertEquals(
            "${div}<ol>" +
                    "<li data-dictionary=\"Jitendex\"><i>(v1, vt, Jitendex)</i> <ul><li>to eat</li><li>to live on</li></ul></li>" +
                    "<style>.g [d] a { }</style>" +
                    "<li data-dictionary=\"JMdict\"><i>(JMdict)</i> eat</li>" +
                    "</ol></div>",
            render("{glossary}", styledTermData)
        )
        assertEquals(
            "${div}<ol><li data-dictionary=\"Jitendex\"><ul><li>to eat</li><li>to live on</li></ul></li>" +
                    "<style>.g [d] a { }</style><li data-dictionary=\"JMdict\">eat</li></ol></div>",
            render("{glossary-brief}", styledTermData)
        )
    }

    @Test
    fun testGlossaryFirstUsesTheGlossaryScopedStyles() {
        assertEquals(
            "$div<i>(v1, vt, Jitendex)</i> <ul><li>to eat</li><li>to live on</li></ul><style>.g a { }</style></div>",
            render("{glossary-first}", styledTermData)
        )
        assertEquals(
            "$div<i>(JMdict)</i> eat</div>",
            render("{glossary-first}", termData.copy(definitions = listOf(jmdict, styledTermData.definitions[0])))
        )
    }

    @Test
    fun testPlainGlossaryHasNoStyles() {
        assertEquals(render("{glossary-plain}"), render("{glossary-plain}", styledTermData))
    }

    @Test
    fun testSearchQueryIsEscapedWithNewlinesAsLineBreaks() {
        assertEquals("", render("{search-query}"))
        assertEquals("食べて", render("{search-query}", termData.copy(searchQuery = "食べて")))
        assertEquals("a&lt;b&gt;<br>c", render("{search-query}", termData.copy(searchQuery = "a<b>\nc")))
        assertEquals("食", render("{search-query}", kanjiData.copy(searchQuery = "食")))
    }

    @Test
    fun testGlossaryPlainVariants() {
        assertEquals("(Jitendex)<br>to eat<br>to live on<br>(JMdict)<br>eat", render("{glossary-plain}"))
        assertEquals("to eat<br>to live on<br>eat", render("{glossary-plain-no-dictionary}"))
        // As on desktop, the kanji branch writes the meanings with no separator between them.
        assertEquals("(KANJIDIC)<br>eatfood", render("{glossary-plain}", kanjiData))
        assertEquals("eatfood", render("{glossary-plain-no-dictionary}", kanjiData))
    }

    @Test
    fun testKanjiGlossary() {
        assertEquals("$div<ol><li>eat</li><li>food</li></ol></div>", render("{glossary}", kanjiData))
        assertEquals("${div}eat</div>", render("{glossary}", kanjiData.copy(glossary = listOf("eat"))))
        assertEquals("$div</div>", render("{glossary-first}", kanjiData))
    }

    @Test
    fun testTagsMergeHeadwordAndDefinitionTags() {
        assertEquals("common, v1, vt", render("{tags}"))
        assertEquals("", render("{tags}", kanjiData))
    }

    @Test
    fun testClozeMarkers() {
        assertEquals("彼は毎日", render("{cloze-prefix}"))
        assertEquals("食べる", render("{cloze-body}"))
        assertEquals("たべる", render("{cloze-body-kana}"))
        assertEquals("。", render("{cloze-suffix}"))
        assertEquals("彼は毎日食べる。", render("{sentence}"))
    }

    @Test
    fun testFrequenciesMarker() {
        assertEquals("<ul style=\"text-align: left;\"><li>BCCWJ: 123</li></ul>", render("{frequencies}"))
        val withDisplay = termData.copy(frequencies = listOf(AnkiFrequencyItem("JPDB", 500.0, "500㋕")))
        assertEquals("<ul style=\"text-align: left;\"><li>JPDB: 500㋕</li></ul>", render("{frequencies}", withDisplay))
        assertEquals("", render("{frequencies}", termData.copy(frequencies = emptyList())))
    }

    @Test
    fun testSingleFrequencyNumberConcatenatesFirstRowPerDictionary() {
        val data = termData.copy(
            frequencies = listOf(
                AnkiFrequencyItem("BCCWJ", 64.0),
                AnkiFrequencyItem("BCCWJ", 7.0),
                AnkiFrequencyItem("JPDB", 0.0, "256㋕"),
                AnkiFrequencyItem("Zero", 0.0)
            )
        )
        // The original prints each number with no separator; rows with no positive value are skipped.
        assertEquals("64256", render("{single-frequency-number}", data))
        assertEquals("", render("{single-frequency-number}", termData.copy(frequencies = emptyList())))
        assertEquals("", render("{single-frequency-number}", kanjiData))
    }

    @Test
    fun testSentenceFuriganaMarkersFallBackToPlainSentence() {
        assertEquals("彼は毎日食べる。", render("{sentence-furigana}"))
        assertEquals("彼は毎日食べる。", render("{sentence-furigana-plain}"))
        assertEquals("", render("{sentence-furigana}", termData.copy(cloze = null)))
        assertEquals("", render("{sentence-furigana-plain}", termData.copy(cloze = null)))
    }

    @Test
    fun testSentenceFuriganaFallbackIsNotHtmlEscaped() {
        val data = termData.copy(cloze = AnkiClozeData("a <b> & c", "", "", "", ""))
        assertEquals("a <b> & c", render("{sentence-furigana}", data))
    }

    @Test
    fun testFrequencyAggregates() {
        val data = termData.copy(
            frequencies = listOf(
                AnkiFrequencyItem("BCCWJ", 64.0),
                AnkiFrequencyItem("BCCWJ", 7.0),
                AnkiFrequencyItem("JPDB", 0.0, "256㋕")
            )
        )
        // Only the first row per dictionary counts: 64 and 256.
        assertEquals("102", render("{frequency-harmonic-rank}", data))
        assertEquals("102", render("{frequency-harmonic-occurrence}", data))
        assertEquals("160", render("{frequency-average-rank}", data))
        assertEquals("160", render("{frequency-average-occurrence}", data))

        val none = termData.copy(frequencies = emptyList())
        assertEquals("9999999", render("{frequency-harmonic-rank}", none))
        assertEquals("0", render("{frequency-harmonic-occurrence}", none))
        assertEquals("9999999", render("{frequency-average-rank}", none))
        assertEquals("0", render("{frequency-average-occurrence}", none))
    }

    @Test
    fun testFrequencyAggregatesSkipDictionariesOfTheOtherMode() {
        // 食べる in JPDB (rank-based) and BCCWJ (no mode declared): as in the original, the rank
        // markers count both, and the occurrence markers skip JPDB.
        val data = termData.copy(
            frequencies = listOf(
                AnkiFrequencyItem("JPDB", 184.0, frequencyMode = "rank-based"),
                AnkiFrequencyItem("BCCWJ", 199.0),
                AnkiFrequencyItem("BCCWJ", 266.0)
            )
        )
        assertEquals("191", render("{frequency-harmonic-rank}", data))
        assertEquals("191", render("{frequency-average-rank}", data))
        assertEquals("199", render("{frequency-harmonic-occurrence}", data))
        assertEquals("199", render("{frequency-average-occurrence}", data))
        assertEquals("184199", render("{single-frequency-number}", data))

        val occurrence = termData.copy(
            frequencies = listOf(AnkiFrequencyItem("Counts", 5000.0, frequencyMode = "occurrence-based"))
        )
        assertEquals("9999999", render("{frequency-harmonic-rank}", occurrence))
        assertEquals("5000", render("{frequency-harmonic-occurrence}", occurrence))
    }

    @Test
    fun testPartOfSpeechReproducesTheOriginalSeparatorPlacement() {
        assertEquals("Ichidan verb", render("{part-of-speech}"))
        assertEquals(
            "Ichidan verbvt, ",
            render("{part-of-speech}", termData.copy(wordClasses = listOf("v1", "vt", "v1")))
        )
        assertEquals("Unknown", render("{part-of-speech}", termData.copy(wordClasses = emptyList())))
        assertEquals("", render("{part-of-speech}", kanjiData))
    }

    @Test
    fun testConjugation() {
        assertEquals("", render("{conjugation}"))
        assertEquals(
            "-た",
            render(
                "{conjugation}",
                termData.copy(
                    inflectionRuleChains = listOf(
                        listOf(
                            io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule(
                                "-た",
                                null
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            "<ul><li>-て « -いる</li></ul>",
            render(
                "{conjugation}",
                termData.copy(
                    inflectionRuleChains = listOf(
                        emptyList(),
                        listOf(
                            io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule("-て", null),
                            io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule("-いる", null)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun testDictionaryAndAlias() {
        assertEquals("Jitendex", render("{dictionary}"))
        assertEquals("Jitendex", render("{dictionary-alias}"))
    }

    @Test
    fun testStrokeCount() {
        assertEquals("Stroke count: 9", render("{stroke-count}", kanjiData))
        assertEquals("Stroke count: Unknown", render("{stroke-count}", kanjiData.copy(strokeCount = null)))
        assertEquals("Stroke count: Unknown", render("{stroke-count}"))
    }

    @Test
    fun testUnsupportedMarkerResolvesToEmptyStringNotError() {
        val result = AnkiTemplateEngine.renderField("front:{expression} audio:{audio}", termData)
        assertEquals("front:食べる audio:", result.text)
        assertTrue(result.unknownMarkers.isEmpty())
    }

    @Test
    fun testEveryStubMarkerResolvesToEmpty() {
        val stubMarkers = listOf(
            "audio", "screenshot", "clipboard-image", "clipboard-text",
            "pitch-accents", "pitch-accent-graphs", "pitch-accent-graphs-jj",
            "pitch-accent-positions", "pitch-accent-categories",
            "phonetic-transcriptions", "popup-selection-text",
            "url", "document-title"
        )
        for (marker in stubMarkers) {
            val result = AnkiTemplateEngine.renderField("{$marker}", termData)
            assertEquals("Marker '$marker' should resolve to empty", "", result.text)
            assertTrue("Marker '$marker' should not be reported unknown", result.unknownMarkers.isEmpty())
        }
    }

    @Test
    fun testUnknownMarkerIsReportedAndRenderedAsError() {
        val result = AnkiTemplateEngine.renderField("{totally-made-up-marker}", termData)
        assertEquals("{totally-made-up-marker-render-error}", result.text)
        assertEquals(listOf("totally-made-up-marker"), result.unknownMarkers)
    }

    @Test
    fun testOnyomiHiraganaMarkerConvertsKatakanaToHiragana() {
        assertEquals("しょく, じき", render("{onyomi-hiragana}", kanjiData))
    }

    @Test
    fun testKanjiMarkers() {
        assertEquals("食", render("{character}", kanjiData))
        assertEquals("た.べる, く.う", render("{kunyomi}", kanjiData))
        assertEquals("ショク, ジキ", render("{onyomi}", kanjiData))
    }

    @Test
    fun testFuriganaWrapsOnlyTheKanjiSegment() {
        assertEquals("<ruby>食<rt>た</rt></ruby>べる", render("{furigana}"))
        assertEquals("食[た]べる", render("{furigana-plain}"))
    }

    @Test
    fun testFuriganaSegmentsEachKanjiRunIndependently() {
        val data = AnkiNoteData(type = AnkiEntryType.TERM, expression = "動き出す", reading = "うごきだす")
        assertEquals("<ruby>動<rt>うご</rt></ruby>き<ruby>出<rt>だ</rt></ruby>す", render("{furigana}", data))
        // furiganaPlain puts a space before every bracketed segment after the first.
        assertEquals("動[うご]き 出[だ]す", render("{furigana-plain}", data))
    }

    @Test
    fun testFuriganaFallsBackToWholeTermWhenSegmentationCannotAlign() {
        val data = AnkiNoteData(type = AnkiEntryType.TERM, expression = "食べる", reading = "ぜんぜんちがう")
        assertEquals("<ruby>食べる<rt>ぜんぜんちがう</rt></ruby>", render("{furigana}", data))
    }

    @Test
    fun testFuriganaOnKatakanaTermWithHiraganaReading() {
        val data = AnkiNoteData(type = AnkiEntryType.TERM, expression = "トマト", reading = "とまと")
        assertEquals("<ruby>トマト<rt>とまと</rt></ruby>", render("{furigana}", data))
    }

    @Test
    fun testFuriganaRefusesAmbiguousSplits() {
        // The reading splits validly two ways (き|の|きのき and きのき|の|き), so the original
        // gives up and puts one ruby over the whole term.
        val data = AnkiNoteData(type = AnkiEntryType.TERM, expression = "木の木", reading = "きのきのき")
        assertEquals("<ruby>木の木<rt>きのきのき</rt></ruby>", render("{furigana}", data))
    }
}
