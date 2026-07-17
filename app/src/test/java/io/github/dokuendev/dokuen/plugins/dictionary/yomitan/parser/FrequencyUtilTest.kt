package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyUtilTest {

    @Test
    fun testParseFrequencyData() {
        // Simple numeric primitive
        val item1 = FrequencyUtil.parseFrequency("100", "日本", "にほん")
        assertNotNull(item1)
        assertEquals(100.0, item1.frequency, 0.001)
        assertEquals("100", item1.displayValue)

        // Simple string primitive
        val item2 = FrequencyUtil.parseFrequency("\"50\"", "日本", "にほん")
        assertNotNull(item2)
        assertEquals(50.0, item2.frequency, 0.001)

        // Object with value and displayValue
        val item3 = FrequencyUtil.parseFrequency("""{"value": 25, "displayValue": "25 (rank)"}""", "日本", "にほん")
        assertNotNull(item3)
        assertEquals(25.0, item3.frequency, 0.001)
        assertEquals("25 (rank)", item3.displayValue)

        // Object with matching reading
        val item4 = FrequencyUtil.parseFrequency("""{"reading": "にほん", "frequency": 12}""", "日本", "にほん")
        assertNotNull(item4)
        assertEquals(12.0, item4.frequency, 0.001)

        // Object with mismatching reading -> should format with reading disambiguation
        val item5 = FrequencyUtil.parseFrequency("""{"reading": "やまと", "frequency": 12}""", "日本", "にほん")
        assertNotNull(item5)
        assertEquals("日本[やまと]: 12", item5.displayValue)
    }

    @Test
    fun testParseFrequencyFormatsWholeNumberValueWithoutTrailingDecimal() {
        // No explicit displayValue, and the JSON literal itself has a trailing ".0" (a
        // dictionary can write "value": 26.0 instead of "value": 26). displayStr must not
        // fall back to the raw JSON text ("26.0"), or the intentional integer-vs-decimal
        // formatting for numVal ("26") never gets a chance to run.
        val item = FrequencyUtil.parseFrequency("""{"value": 26.0}""", "打", "だ")
        assertEquals(26.0, item.frequency, 0.001)
        assertEquals("26", item.displayValue)
    }

    @Test
    fun testGroupFrequenciesByDictionary() {
        val input = listOf(
            RawFrequency("DictA", """{"value": 100}""", "日本", "にほん"),
            RawFrequency("DictA", """{"value": 200}""", "日本", "にほん"),
            RawFrequency("DictB", """{"value": 50, "displayValue": "N1"}""", "日本", "にほん")
        )

        val grouped = FrequencyUtil.groupFrequencies(input, "日本", "にほん")
        assertEquals(2, grouped.size)

        // DictA group badge text should be "[DictA] 100, 200" or "[DictA] 100"
        val dictABadge = grouped.find { it.dictionary == "DictA" }
        assertNotNull(dictABadge)
        assertEquals("[DictA] 100, 200", dictABadge!!.formattedBadge)

        // DictB group badge text
        val dictBBadge = grouped.find { it.dictionary == "DictB" }
        assertNotNull(dictBBadge)
        assertEquals("[DictB] N1", dictBBadge!!.formattedBadge)
    }

    @Test
    fun testGroupFrequenciesExcludesEntriesWithMismatchedEmbeddedReading() {
        // Same headword is rendered with reading "にほん". A term_meta row that embeds a
        // *different* reading belongs to a different headword entirely and must not be
        // shown here - mirroring yomitan/translator.js's _addTermMeta:
        // `if (hasReading && data.reading !== reading) { continue; }`, applied before a
        // frequency value is ever attached to a headword's display list.
        val input = listOf(
            RawFrequency("DictA", """{"reading": "にほん", "value": 100}""", "日本", "にほん"),
            RawFrequency("DictA", """{"reading": "やまと", "value": 999}""", "日本", "にほん")
        )

        val grouped = FrequencyUtil.groupFrequencies(input, "日本", "にほん")

        assertEquals(1, grouped.size)
        val dictABadge = grouped.find { it.dictionary == "DictA" }
        assertNotNull(dictABadge)
        assertEquals("[DictA] 100", dictABadge!!.formattedBadge)
    }

    @Test
    fun testCalculateSortFrequencyOrderUsesSingleDictionaryRawValueNotHarmonicMean() {
        // Mirrors yomitan/translator.js's _updateSortFrequencies exactly: only rows from the
        // one chosen dictionary count (others are ignored, not blended in), and the raw
        // min/max value is used directly - never averaged.
        val rawFrequencies = listOf(
            RawFrequency("ChosenDict", "100", "犬", null),
            RawFrequency("ChosenDict", "50", "犬", null),
            // A different dictionary's value must have zero effect on the result.
            RawFrequency("OtherDict", "1", "犬", null)
        )

        val ascending =
            FrequencyUtil.calculateSortFrequencyOrder(rawFrequencies, "いぬ", "ChosenDict", ascending = true)
        // ascending -> the minimum raw value from ChosenDict only: min(100, 50) = 50.
        assertEquals(50.0, ascending, 0.001)

        val descending =
            FrequencyUtil.calculateSortFrequencyOrder(rawFrequencies, "いぬ", "ChosenDict", ascending = false)
        // descending -> the negated maximum raw value from ChosenDict only: -max(100, 50) = -100.
        assertEquals(-100.0, descending, 0.001)
    }

    @Test
    fun testCalculateSortFrequencyOrderSentinelWhenChosenDictionaryHasNoData() {
        val rawFrequencies = listOf(RawFrequency("OtherDict", "1", "犬", null))

        // No matching row for "ChosenDict" -> sentinel values matching
        // yomitan/translator.js's _updateSortFrequencies exactly: MAX_SAFE_INTEGER for
        // ascending (sorts last), 0 for descending.
        val ascending =
            FrequencyUtil.calculateSortFrequencyOrder(rawFrequencies, "いぬ", "ChosenDict", ascending = true)
        assertEquals(Long.MAX_VALUE.toDouble(), ascending, 0.001)

        val descending =
            FrequencyUtil.calculateSortFrequencyOrder(rawFrequencies, "いぬ", "ChosenDict", ascending = false)
        assertEquals(0.0, descending, 0.001)
    }

    @Test
    fun testCalculateSortFrequencyOrderExcludesMismatchedReading() {
        val rawFrequencies = listOf(
            RawFrequency("ChosenDict", """{"reading": "やまと", "frequency": 5}""", "日本", null)
        )

        // The only row's embedded reading (やまと) doesn't match にほん - treated as no data.
        val ascending =
            FrequencyUtil.calculateSortFrequencyOrder(rawFrequencies, "にほん", "ChosenDict", ascending = true)
        assertEquals(Long.MAX_VALUE.toDouble(), ascending, 0.001)
    }

    @Test
    fun testDetectFrequencyOrderSignForOccurrenceBasedData() {
        // Mirrors yomitan's sort-frequency-dictionary-controller.js _getFrequencyOrder: common
        // words with LARGER raw values than rare words indicates occurrence-based data
        // (descending order gives the most common word first).
        val moreCommon = listOf(listOf(9000.0), listOf(8000.0))
        val lessCommon = listOf(listOf(5.0), listOf(3.0))

        val sign = FrequencyUtil.detectFrequencyOrderSign(moreCommon, lessCommon)
        assertEquals(1, sign)
    }

    @Test
    fun testDetectFrequencyOrderSignForRankBasedData() {
        // Common words with SMALLER raw values (better ranks) than rare words indicates
        // rank-based data (ascending order gives the most common word first).
        val moreCommon = listOf(listOf(5.0), listOf(3.0))
        val lessCommon = listOf(listOf(9000.0), listOf(8000.0))

        val sign = FrequencyUtil.detectFrequencyOrderSign(moreCommon, lessCommon)
        assertEquals(-1, sign)
    }

    @Test
    fun testDetectFrequencyOrderSignIgnoresTermsWithNoData() {
        // Terms with no matching frequency data (empty list) must not contribute -
        // mirrors yomitan's `if (!details1.hasValue) { continue; }`.
        val moreCommon = listOf(emptyList(), listOf(3.0))
        val lessCommon = listOf(emptyList(), listOf(9000.0))

        val sign = FrequencyUtil.detectFrequencyOrderSign(moreCommon, lessCommon)
        assertEquals(-1, sign)
    }

    @Test
    fun testDetectFrequencyOrderSignReturnsZeroWhenNoData() {
        val sign = FrequencyUtil.detectFrequencyOrderSign(listOf(emptyList()), listOf(emptyList()))
        assertEquals(0, sign)
    }

    @Test
    fun testHasFrequencyDataMatchesYomitanSettingsPageCriterion() {
        // yomitan's own settings.html sort-frequency-dictionary picker
        // (_updateDictionaryOptions in sort-frequency-dictionary-controller.js) recognizes a
        // dictionary as a frequency dictionary purely by `counts.termMeta.freq > 0` /
        // `counts.kanjiMeta.freq > 0` - never by the (optional, purely decorative) index.json
        // `frequencyMode` field.
        val withTermFreq =
            """{"terms":{"total":0},"termMeta":{"total":5,"freq":5},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        assertTrue(FrequencyUtil.hasFrequencyData(withTermFreq))

        val withKanjiFreq =
            """{"terms":{"total":0},"termMeta":{"total":0},"kanji":{"total":0},"kanjiMeta":{"total":3,"freq":3},"tagMeta":{"total":0},"media":{"total":0}}"""
        assertTrue(FrequencyUtil.hasFrequencyData(withKanjiFreq))

        // A normal term dictionary (e.g. Jitendex) with termMeta rows that are all "pitch"/"ipa",
        // no "freq" - not a frequency dictionary.
        val pitchOnly =
            """{"terms":{"total":100},"termMeta":{"total":10,"pitch":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}"""
        assertFalse(FrequencyUtil.hasFrequencyData(pitchOnly))

        assertFalse(FrequencyUtil.hasFrequencyData(null))
        assertFalse(FrequencyUtil.hasFrequencyData(""))
        assertFalse(FrequencyUtil.hasFrequencyData("not json"))
    }
}
