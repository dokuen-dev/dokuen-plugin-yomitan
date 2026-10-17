package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser.InflectionRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Builds [AnkiNoteData] from database rows the same way the original Yomitan's translator and
 * anki-note-data-creator.js shape a dictionary entry for its Anki templates in the default
 * "group" result mode: one definition per term row, tags expanded against the tag bank and
 * sorted by tag order then name, and frequencies ordered by dictionary priority.
 */
object AnkiNoteDataBuilder {
    private val json = Json { ignoreUnknownKeys = true }
    private val numberRegex = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

    /**
     * @param terms every term row shown for one grouped headword (same expression and
     *   reading), in display order.
     * @param tagMetaList tag-bank rows for every dictionary that appears in [terms].
     * @param termMetaList term-meta rows for the headword's expression across the active
     *   dictionaries; only frequency rows are used.
     * @param dictionaryOrder active dictionary titles in priority order, used to order
     *   frequencies the way the original does.
     * @param inflectionRuleChains the distinct inflection rule chains that matched this
     *   headword; an empty chain means an uninflected match.
     * @param dictionaryStyles the raw styles.css of each dictionary in [terms], by title;
     *   a dictionary without one can be left out.
     * @param searchQuery the lookup's query, for `{search-query}`.
     * @param frequencyModes the `frequencyMode` each frequency dictionary's index.json declares,
     *   by title; a dictionary that declares none can be left out.
     */
    fun buildTermNoteData(
        terms: List<TermEntity>,
        tagMetaList: List<TagMetaEntity>,
        termMetaList: List<TermMetaEntity>,
        cloze: AnkiClozeData?,
        dictionaryOrder: List<String> = emptyList(),
        inflectionRuleChains: List<List<InflectionRule>> = emptyList(),
        dictionaryStyles: Map<String, String> = emptyMap(),
        searchQuery: String = "",
        frequencyModes: Map<String, String> = emptyMap()
    ): AnkiNoteData {
        require(terms.isNotEmpty()) { "A term note needs at least one term row" }
        // As in the original's translator (_getDeinflections), a glossary item that is an array,
        // a redirect of the form [term, [inflection rules]], is not part of the definition, and a
        // term row left without a glossary item is not a definition at all.
        val definitionTerms = terms.filter { term -> parseArray(term.glossary).any { it !is JsonArray } }
            .ifEmpty { terms }
        val primary = definitionTerms.first()
        val tagIndex = tagMetaList.associateBy { it.dictionary to it.name }
        val scopedStyles = HashMap<String, AnkiScopedStyles>()

        val definitions = definitionTerms.map { term ->
            val glossaryElements = parseArray(term.glossary).filterNot { it is JsonArray }
            AnkiDefinition(
                dictionary = term.dictionary,
                dictionaryAlias = term.dictionary,
                definitionTags = expandTags(splitField(term.definitionTags), term.dictionary, tagIndex),
                glossary = glossaryElements.map {
                    runCatching { AnkiGlossaryHtmlConverter.convertGlossaryEntry(it) }.getOrDefault("")
                },
                glossaryPlain = glossaryElements.map {
                    runCatching { AnkiGlossaryHtmlConverter.convertGlossaryEntryPlain(it) }.getOrDefault("")
                },
                styles = scopedStyles.getOrPut(term.dictionary) {
                    AnkiDictionaryStyles.scope(dictionaryStyles[term.dictionary] ?: "", term.dictionary)
                }
            )
        }

        val termTags = sortAndMerge(
            definitionTerms.flatMap { term ->
                splitField(term.termTags).map {
                    createTag(
                        it,
                        term.dictionary,
                        tagIndex
                    )
                }
            }
        ).map { AnkiTagItem(it.name, it.notes) }

        val wordClasses = LinkedHashSet<String>()
        definitionTerms.forEach { term -> wordClasses.addAll(splitField(term.rules)) }

        val frequencies = termMetaList
            .filter { it.mode == "freq" && it.expression == primary.expression }
            .mapNotNull { meta -> termFrequency(meta, primary.reading, frequencyModes[meta.dictionary]) }
            .let { sortByDictionaryOrder(it, dictionaryOrder) }

        return AnkiNoteData(
            type = AnkiEntryType.TERM,
            expression = primary.expression,
            reading = primary.reading,
            definitions = definitions,
            termTags = termTags,
            wordClasses = wordClasses.toList(),
            inflectionRuleChains = inflectionRuleChains,
            frequencies = frequencies,
            dictionary = primary.dictionary,
            dictionaryAlias = primary.dictionary,
            cloze = cloze,
            searchQuery = searchQuery
        )
    }

    fun buildKanjiNoteData(
        kanji: KanjiEntity,
        tagMetaList: List<TagMetaEntity> = emptyList(),
        kanjiMetaList: List<KanjiMetaEntity> = emptyList(),
        cloze: AnkiClozeData? = null,
        dictionaryOrder: List<String> = emptyList(),
        searchQuery: String = "",
        frequencyModes: Map<String, String> = emptyMap()
    ): AnkiNoteData {
        val meanings = parseArray(kanji.meanings).map { (it as? JsonPrimitive)?.content ?: it.toString() }

        val strokesMeta = tagMetaList.firstOrNull { it.dictionary == kanji.dictionary && it.name == "strokes" }
        val strokeCount = if (strokesMeta?.category == "misc") {
            kanji.stats?.let { raw ->
                (runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.get("strokes")
                    ?.let { value ->
                        if (value is JsonPrimitive) value.content else value.toString()
                    }
            }
        } else {
            null
        }

        val frequencies = kanjiMetaList
            .filter { it.mode == "freq" && it.character == kanji.character }
            .mapNotNull { meta ->
                val data = runCatching { json.parseToJsonElement(meta.data) }.getOrNull() ?: return@mapNotNull null
                val (value, displayValue) = frequencyInfo(data)
                AnkiFrequencyItem(
                    dictionaryAlias = meta.dictionary,
                    frequency = value,
                    displayValue = displayValue,
                    frequencyMode = frequencyModes[meta.dictionary]
                )
            }
            .let { sortByDictionaryOrder(it, dictionaryOrder) }

        return AnkiNoteData(
            type = AnkiEntryType.KANJI,
            character = kanji.character,
            glossary = meanings,
            kunyomi = splitField(kanji.kunyomi),
            onyomi = splitField(kanji.onyomi),
            dictionary = kanji.dictionary,
            dictionaryAlias = kanji.dictionary,
            frequencies = frequencies,
            strokeCount = strokeCount,
            cloze = cloze,
            searchQuery = searchQuery
        )
    }

    /**
     * The original's getCloze: splits [sentence] around the scanned text and derives the
     * body's kana form from the headword's [term] and [reading], so an inflected body such as
     * 食べた yields たべた rather than the dictionary-form reading. [originalText] is the
     * scanned surface text starting at the sentence offset. Kanji notes pass empty [term]
     * and [reading], which leaves the body unchanged.
     */
    fun buildCloze(
        sentence: AnkiSentenceExtractor.Sentence,
        originalText: String,
        term: String,
        reading: String
    ): AnkiClozeData {
        val chars = sentence.text.codePoints().toArray()
        val offset = sentence.offset.coerceIn(0, chars.size)
        val bodyEnd = (offset + originalText.codePointCount(0, originalText.length)).coerceAtMost(chars.size)
        fun slice(from: Int, to: Int) = String(chars, from, maxOf(to - from, 0))
        val body = slice(offset, bodyEnd)
        val bodyKana = AnkiJapanese.distributeFuriganaInflected(term, reading, body)
            .joinToString("") { it.reading.ifEmpty { it.text } }
        return AnkiClozeData(
            sentence = sentence.text,
            prefix = slice(0, offset),
            body = body,
            bodyKana = bodyKana,
            suffix = slice(bodyEnd, chars.size)
        )
    }

    private data class Tag(val name: String, val category: String, val order: Int, val notes: String)

    private fun splitField(field: String?): List<String> =
        (field ?: "").split(' ').filter { it.isNotEmpty() }

    private fun parseArray(raw: String): List<JsonElement> =
        (runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray) ?: emptyList()

    private fun createTag(name: String, dictionary: String, tagIndex: Map<Pair<String, String>, TagMetaEntity>): Tag {
        val meta = tagIndex[dictionary to name]
        return Tag(
            name = name,
            category = meta?.category?.takeIf { it.isNotEmpty() } ?: "default",
            order = meta?.order ?: 0,
            notes = meta?.notes ?: ""
        )
    }

    private fun expandTags(
        names: List<String>,
        dictionary: String,
        tagIndex: Map<Pair<String, String>, TagMetaEntity>
    ): List<AnkiTagItem> =
        sortAndMerge(names.map { createTag(it, dictionary, tagIndex) }).map { AnkiTagItem(it.name, it.notes) }

    /** The translator's _mergeSimilarTags (same name and category) followed by its order/name sort. */
    private fun sortAndMerge(tags: List<Tag>): List<Tag> {
        val merged = mutableListOf<Tag>()
        for (tag in tags) {
            val index = merged.indexOfFirst { it.name == tag.name && it.category == tag.category }
            if (index < 0) {
                merged.add(tag)
            } else {
                val existing = merged[index]
                merged[index] = existing.copy(
                    order = minOf(existing.order, tag.order),
                    notes = existing.notes.ifEmpty { tag.notes }
                )
            }
        }
        if (merged.size <= 1) return merged
        return merged.sortedWith(
            compareBy<Tag> { it.order }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
        )
    }

    private fun termFrequency(meta: TermMetaEntity, reading: String, frequencyMode: String?): AnkiFrequencyItem? {
        val data = runCatching { json.parseToJsonElement(meta.data) }.getOrNull() ?: return null
        val readingValue = ((data as? JsonObject)?.get("reading") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (readingValue != null && readingValue != reading) return null
        val frequencyData = if (readingValue != null) data["frequency"] ?: return null else data
        val (value, displayValue) = frequencyInfo(frequencyData)
        return AnkiFrequencyItem(
            dictionaryAlias = meta.dictionary,
            frequency = value,
            displayValue = displayValue,
            frequencyMode = frequencyMode
        )
    }

    /** The translator's _getFrequencyInfo: numeric value plus optional display string. */
    private fun frequencyInfo(frequency: JsonElement): Pair<Double, String?> {
        return when (frequency) {
            is JsonObject -> {
                val value = (frequency["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0
                val displayValue = (frequency["displayValue"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                value to displayValue
            }

            is JsonPrimitive -> if (frequency.isString) {
                val parsed = numberRegex.find(frequency.content)?.value?.toDoubleOrNull()
                (parsed?.takeIf { it.isFinite() } ?: 0.0) to frequency.content
            } else {
                (frequency.doubleOrNull ?: 0.0) to null
            }

            else -> 0.0 to null
        }
    }

    private fun sortByDictionaryOrder(
        items: List<AnkiFrequencyItem>,
        dictionaryOrder: List<String>
    ): List<AnkiFrequencyItem> {
        if (dictionaryOrder.isEmpty()) return items
        return items.sortedBy { item ->
            dictionaryOrder.indexOf(item.dictionary).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }
}
