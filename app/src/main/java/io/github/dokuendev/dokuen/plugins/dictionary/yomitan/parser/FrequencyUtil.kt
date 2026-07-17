package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class FrequencyItem(
    val dictionary: String,
    val expression: String,
    val reading: String? = null,
    val frequency: Double,
    val displayValue: String? = null
)

data class GroupedFrequencyBadge(
    val dictionary: String,
    val values: List<String>,
    val formattedBadge: String
)

data class RawFrequency(
    val dictionary: String,
    val jsonData: String,
    val expression: String,
    val reading: String? = null
)

object FrequencyUtil {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Common and rare Japanese words used to auto-detect whether a dictionary's frequency
     * values are rank-based (smaller = more common) or occurrence-based (larger = more
     * common), by comparing raw values between the two groups.
     *
     * Verbatim from yomitan/ext/js/pages/settings/sort-frequency-dictionary-controller.js's
     * `_getFrequencyOrder` (the 'ja' word lists - this app is Japanese-only).
     */
    val MORE_COMMON_JAPANESE_TERMS = listOf("来る", "言う", "出る", "入る", "方", "男", "女", "今", "何", "時")
    val LESS_COMMON_JAPANESE_TERMS = listOf("行なう", "論じる", "過す", "行方", "人口", "猫", "犬", "滝", "理", "暁")

    /**
     * Detect whether a dictionary's frequency values are occurrence-based (result `1`,
     * descending sort - larger values are more common) or rank-based (result `-1`, ascending
     * sort - smaller values are more common), given the raw frequency values found for each
     * [MORE_COMMON_JAPANESE_TERMS] / [LESS_COMMON_JAPANESE_TERMS] term (each inner list holds
     * every value found for that term; an empty list means no data was found for it).
     * Returns `0` when the signal is ambiguous, mirroring `_getFrequencyOrder`'s
     * `Math.sign(result)` exactly - callers should leave the sort order unchanged in that case.
     */
    fun detectFrequencyOrderSign(
        moreCommonTermValues: List<List<Double>>,
        lessCommonTermValues: List<List<Double>>
    ): Int {
        var result = 0
        for (moreValues in moreCommonTermValues) {
            if (moreValues.isEmpty()) continue
            val moreMin = moreValues.min()
            val moreMax = moreValues.max()
            for (lessValues in lessCommonTermValues) {
                if (lessValues.isEmpty()) continue
                val lessMin = lessValues.min()
                val lessMax = lessValues.max()
                result += sign(moreMax - lessMin) + sign(moreMin - lessMax)
            }
        }
        return sign(result.toDouble())
    }

    private fun sign(value: Double): Int = when {
        value > 0.0 -> 1
        value < 0.0 -> -1
        else -> 0
    }

    /**
     * Whether a dictionary's stored `counts` JSON shows it actually contains frequency data.
     *
     * Mirrors yomitan's own recognition rule for the "Frequency sorting dictionary" picker
     * (ext/js/pages/settings/sort-frequency-dictionary-controller.js#_updateDictionaryOptions):
     * `counts.termMeta.freq > 0` (term frequency) or `counts.kanjiMeta.freq > 0` (kanji
     * frequency) - the index.json `frequencyMode` field is never consulted for this, since
     * yomitan itself never reads that field anywhere except to display it.
     */
    fun hasFrequencyData(countsJson: String?): Boolean {
        if (countsJson.isNullOrEmpty()) return false
        return try {
            val counts = json.parseToJsonElement(countsJson).jsonObject
            val termMetaFreq = counts["termMeta"]?.jsonObject?.get("freq")?.jsonPrimitive?.longOrNull ?: 0L
            val kanjiMetaFreq = counts["kanjiMeta"]?.jsonObject?.get("freq")?.jsonPrimitive?.longOrNull ?: 0L
            termMetaFreq > 0L || kanjiMetaFreq > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parse frequency data JSON payload from termMeta or kanjiMeta store.
     */
    fun parseFrequency(
        jsonData: String,
        expression: String = "",
        reading: String? = null
    ): FrequencyItem {
        return try {
            when (val element = json.parseToJsonElement(jsonData)) {
                is JsonPrimitive -> {
                    val str = element.content
                    val num = str.toDoubleOrNull() ?: extractFirstNumber(str) ?: 0.0
                    FrequencyItem(
                        dictionary = "",
                        expression = expression,
                        reading = reading,
                        frequency = num,
                        displayValue = str
                    )
                }

                is JsonObject -> {
                    val freqReading = element["reading"]?.let {
                        if (it is JsonPrimitive) it.content else null
                    }

                    var displayStr: String? = null
                    var numVal: Double? = null

                    if (element.containsKey("frequency")) {
                        when (val freq = element["frequency"]!!) {
                            is JsonPrimitive -> {
                                displayStr = freq.content
                                numVal = freq.content.toDoubleOrNull() ?: extractFirstNumber(freq.content)
                            }

                            is JsonObject -> {
                                val dv = freq["displayValue"]?.let { if (it is JsonPrimitive) it.content else null }
                                val v = freq["value"]?.let { if (it is JsonPrimitive) it.content else null }
                                displayStr = dv
                                numVal = v?.toDoubleOrNull()
                                    ?: dv?.let { extractFirstNumber(it) }
                                            ?: v?.let { extractFirstNumber(it) }
                            }

                            else -> {}
                        }
                    } else if (element.containsKey("value")) {
                        val dv = element["displayValue"]?.let { if (it is JsonPrimitive) it.content else null }
                        val v = element["value"]?.let { if (it is JsonPrimitive) it.content else null }
                        displayStr = dv
                        numVal = v?.toDoubleOrNull()
                            ?: dv?.let { extractFirstNumber(it) }
                                    ?: v?.let { extractFirstNumber(it) }
                    }

                    val rawValStr =
                        displayStr ?: numVal?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                        ?: "?"
                    val finalDisplayStr = if (freqReading != null && reading != null && freqReading != reading) {
                        "$expression[$freqReading]: $rawValStr"
                    } else {
                        rawValStr
                    }

                    FrequencyItem(
                        dictionary = "",
                        expression = expression,
                        reading = freqReading ?: reading,
                        frequency = numVal ?: extractFirstNumber(rawValStr) ?: 0.0,
                        displayValue = finalDisplayStr
                    )
                }

                else -> FrequencyItem(
                    dictionary = "",
                    expression = expression,
                    reading = reading,
                    frequency = 0.0,
                    displayValue = "?"
                )
            }
        } catch (_: Exception) {
            FrequencyItem(
                dictionary = "",
                expression = expression,
                reading = reading,
                frequency = 0.0,
                displayValue = "?"
            )
        }
    }

    private fun extractFirstNumber(text: String): Double? {
        val match = """\d+(\.\d+)?""".toRegex().find(text)
        return match?.value?.toDoubleOrNull()
    }

    /**
     * Group raw frequencies by dictionary and create formatted badges `[DictName] freqValue`.
     */
    fun groupFrequencies(
        rawFrequencies: List<RawFrequency>,
        expression: String,
        reading: String? = null
    ): List<GroupedFrequencyBadge> {
        val grouped = rawFrequencies.groupBy { it.dictionary }
        val result = mutableListOf<GroupedFrequencyBadge>()

        for ((dictName, list) in grouped) {
            val displayValues = mutableListOf<String>()
            for (raw in list) {
                val effectiveReading = reading ?: raw.reading
                val item = parseFrequency(raw.jsonData, expression, effectiveReading)
                if (effectiveReading != null && item.reading != null && item.reading != effectiveReading) {
                    // This row's embedded reading belongs to a different headword - skip it,
                    // mirroring yomitan/translator.js's _addTermMeta reading-match filter.
                    continue
                }
                if (item.displayValue != null) {
                    displayValues.add(item.displayValue)
                }
            }
            if (displayValues.isNotEmpty()) {
                val formatted = "[$dictName] ${displayValues.joinToString(", ")}"
                result.add(GroupedFrequencyBadge(dictName, displayValues, formatted))
            }
        }
        return result
    }

    /**
     * Calculate a headword's frequency sort key using one specific, user-chosen dictionary.
     *
     * Mirrors yomitan/ext/js/language/translator.js's _updateSortFrequencies exactly: only
     * rows from [dictionary] count (every other dictionary is ignored, never blended in -
     * this is deliberately not a harmonic mean); the raw minimum value is used directly when
     * [ascending] (smaller = more common, e.g. rank-based dictionaries), or the negated raw
     * maximum when descending (larger = more common, e.g. occurrence-based dictionaries).
     * When no matching row exists, returns yomitan's own sentinel: `Long.MAX_VALUE` for
     * ascending (sorts last) or `0.0` for descending.
     */
    fun calculateSortFrequencyOrder(
        rawFrequencies: List<RawFrequency>,
        reading: String,
        dictionary: String,
        ascending: Boolean
    ): Double {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (raw in rawFrequencies) {
            if (raw.dictionary != dictionary) continue
            val parsed = parseFrequency(raw.jsonData, raw.expression, reading)
            if (parsed.reading != null && parsed.reading != reading) continue
            min = minOf(min, parsed.frequency)
            max = maxOf(max, parsed.frequency)
        }
        if (min > max) {
            return if (ascending) Long.MAX_VALUE.toDouble() else 0.0
        }
        return if (ascending) min else -max
    }
}
