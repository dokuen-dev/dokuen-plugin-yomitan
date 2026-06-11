/*
 * Kotlin port of ext/js/dictionary/dictionary-importer.js
 *
 * Schema version handled: V1 (format=1) and V3 (format=3, default).
 * The "format" field may appear as either `format` or `version` in index.json.
 */

package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer

import android.content.Context
import androidx.room.withTransaction
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.R
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.MediaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

// ─────────────────────────────────────────────────────────────────────────────
// Public data types
// ─────────────────────────────────────────────────────────────────────────────

data class ImportResult(
    val summary: DictionaryEntity?,
    val errors: List<Exception>,
)

data class ImportDetails(
    /** Whether to populate expressionReverse / readingReverse columns. */
    val prefixWildcardsSupported: Boolean = true,
    val yomitanVersion: String = "0.0.0.0",
)

typealias ProgressCallback = (index: Int, count: Int) -> Unit

// ─────────────────────────────────────────────────────────────────────────────
// Media-loading abstraction (mirrors GenericMediaLoader)
// ─────────────────────────────────────────────────────────────────────────────

data class ImageDetails(
    val content: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageDetails

        if (width != other.width) return false
        if (height != other.height) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + content.contentHashCode()
        return result
    }
}

interface MediaLoader {
    /**
     * Decode raw bytes and return resolved dimensions plus (potentially
     * re-encoded) bytes.
     */
    suspend fun getImageDetails(bytes: ByteArray, mediaType: String): ImageDetails
}

// ─────────────────────────────────────────────────────────────────────────────
// DictionaryImporter
// ─────────────────────────────────────────────────────────────────────────────

class DictionaryImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val mediaLoader: MediaLoader,
    private val onProgress: ProgressCallback = { _, _ -> },
) {
    // JS: maxTransactionLength = 1000
    private val chunkSize = 1000

    private var progressIndex = 0
    private var progressCount = 0

    // ─── Entry point ─────────────────────────────────────────────────────────

    /**
     * JS: importDictionary(dictionaryDatabase, archiveContent, details)
     */
    suspend fun importDictionary(
        inputStream: InputStream,
        details: ImportDetails = ImportDetails(),
    ): ImportResult = withContext(Dispatchers.IO) {

        val errors = mutableListOf<Exception>()
        val tempFile = File.createTempFile("yomitan_import_", ".zip", context.cacheDir)

        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            db.withTransaction {
                ZipFile(tempFile).use { zipFile ->
                    // ── Step 2: read & validate index.json ───────────────────────────
                    val index = readAndValidateIndex(zipFile)
                    val dictionaryTitle = index.title
                    val version = index.version   // 1 or 3

                    if (db.dictionaryDao().dictionaryExists(dictionaryTitle) != 0) {
                        throw Exception(context.getString(R.string.error_dictionary_already_imported, dictionaryTitle))
                    }

                    // ── Step 3: discover bank files ───────────────────────────────────
                    val allEntries = zipFile.entries().asSequence().toList()
                    val termEntries = allEntries.filter { it.name.matches(Regex("term_bank_(\\d+)\\.json")) }
                        .sortedBy { bankNumber(it.name) }
                    val termMetaEntries = allEntries.filter { it.name.matches(Regex("term_meta_bank_(\\d+)\\.json")) }
                        .sortedBy { bankNumber(it.name) }
                    val kanjiEntries = allEntries.filter { it.name.matches(Regex("kanji_bank_(\\d+)\\.json")) }
                        .sortedBy { bankNumber(it.name) }
                    val kanjiMetaEntries = allEntries.filter { it.name.matches(Regex("kanji_meta_bank_(\\d+)\\.json")) }
                        .sortedBy { bankNumber(it.name) }
                    val tagEntries = allEntries.filter { it.name.matches(Regex("tag_bank_(\\d+)\\.json")) }
                        .sortedBy { bankNumber(it.name) }

                    progressNextStep(
                        termEntries.size + termMetaEntries.size + kanjiEntries.size +
                                kanjiMetaEntries.size + tagEntries.size
                    )

                    // ── Step 4 (implicit): validation skipped; full AJV port is out of
                    //    scope. Add your own schema validation before calling this.     ──

                    // ── Step 5: insert initial dictionary record ──────────────────────
                    val importDate = System.currentTimeMillis()
                    val initialSummary = buildInitialSummary(index, details, importDate)
                    val dictionaryId = db.dictionaryDao().insertDictionary(initialSummary)

                    // Progress budget doubles for term files (media + terms)
                    progressNextStep(
                        (termEntries.size * 2 + termMetaEntries.size + kanjiEntries.size +
                                kanjiMetaEntries.size + tagEntries.size) * chunkSize
                    )

                    // ── Step 6: term banks ────────────────────────────────────────────
                    val uniqueMediaPaths = mutableSetOf<String>()
                    var termsTotal = 0
                    var mediaTotal = 0

                    for (entry in termEntries) {
                        val requirements = mutableListOf<ImportRequirement>()
                        val termList = zipFile.getInputStream(entry).use { stream ->
                            if (version == 1)
                                readTermBankV1(stream, dictionaryTitle, details.prefixWildcardsSupported)
                            else
                                readTermBankV3(stream, dictionaryTitle, details.prefixWildcardsSupported, requirements)
                        }

                        // resolve requirements
                        val alreadyAdded = requirements.filter { it.sourcePath in uniqueMediaPaths }
                        val notAdded = requirements.filter { it.sourcePath !in uniqueMediaPaths }
                        uniqueMediaPaths.addAll(requirements.map { it.sourcePath })

                        // resolve already-added (must still update target to get correct path/dimensions)
                        resolveRequirements(alreadyAdded, zipFile)
                        val newMedia = resolveRequirements(notAdded, zipFile)

                        bulkAdd(newMedia) { chunk -> db.mediaDao().bulkAdd(chunk) }
                        mediaTotal += newMedia.size
                        progress()

                        bulkAdd(termList) { chunk -> db.termDao().bulkAdd(chunk) }
                        termsTotal += termList.size
                        progress()
                    }

                    // ── Step 7: termMeta banks ────────────────────────────────────
                    var termMetaTotal = 0
                    val termMetaCountsByMode = mutableMapOf<String, Int>()
                    for (entry in termMetaEntries) {
                        val metaList = zipFile.getInputStream(entry).use { stream ->
                            readTermMetaBank(stream, dictionaryTitle)
                        }
                        bulkAdd(metaList) { chunk -> db.termMetaDao().bulkAdd(chunk) }
                        termMetaTotal += metaList.size
                        for (m in metaList) termMetaCountsByMode.merge(m.mode, 1, Int::plus)
                        progress()
                    }

                    // ── Step 8: kanji banks ───────────────────────────────────────
                    var kanjiTotal = 0
                    for (entry in kanjiEntries) {
                        val kanjiList = zipFile.getInputStream(entry).use { stream ->
                            if (version == 1)
                                readKanjiBankV1(stream, dictionaryTitle)
                            else
                                readKanjiBankV3(stream, dictionaryTitle)
                        }
                        bulkAdd(kanjiList) { chunk -> db.kanjiDao().bulkAdd(chunk) }
                        kanjiTotal += kanjiList.size
                        progress()
                    }

                    // ── Step 9: kanjiMeta banks ───────────────────────────────────
                    var kanjiMetaTotal = 0
                    val kanjiMetaCountsByMode = mutableMapOf<String, Int>()
                    for (entry in kanjiMetaEntries) {
                        val metaList = zipFile.getInputStream(entry).use { stream ->
                            readKanjiMetaBank(stream, dictionaryTitle)
                        }
                        bulkAdd(metaList) { chunk -> db.kanjiMetaDao().bulkAdd(chunk) }
                        kanjiMetaTotal += metaList.size
                        for (m in metaList) kanjiMetaCountsByMode.merge(m.mode, 1, Int::plus)
                        progress()
                    }

                    // ── Step 10: tagMeta banks ────────────────────────────────────
                    var tagMetaTotal = 0
                    for (entry in tagEntries) {
                        val tagList = zipFile.getInputStream(entry).use { stream ->
                            readTagBank(stream, dictionaryTitle).toMutableList()
                        }
                        // JS: _addOldIndexTags(index, tagList, dictionaryTitle)
                        addOldIndexTags(index, tagList, dictionaryTitle)
                        bulkAdd(tagList) { chunk -> db.tagMetaDao().bulkAdd(chunk) }
                        tagMetaTotal += tagList.size
                        progress()
                    }
                    // If no tag files but index has tagMeta, add them now
                    if (tagEntries.isEmpty()) {
                        val tagList = mutableListOf<TagMetaEntity>()
                        addOldIndexTags(index, tagList, dictionaryTitle)
                        if (tagList.isNotEmpty()) {
                            bulkAdd(tagList) { chunk -> db.tagMetaDao().bulkAdd(chunk) }
                            tagMetaTotal += tagList.size
                        }
                    }

                    // ── Step 11: styles.css ───────────────────────────────────────
                    val stylesEntry = zipFile.getEntry("styles.css")
                    val styles = stylesEntry?.let { entry ->
                        zipFile.getInputStream(entry).use { stream ->
                            stream.bufferedReader().readText()
                        }
                    } ?: ""

                    // ── Step 12: update dictionary record ─────────────────────────
                    val counts = buildCounts(
                        termsTotal, termMetaTotal, termMetaCountsByMode,
                        kanjiTotal, kanjiMetaTotal, kanjiMetaCountsByMode,
                        tagMetaTotal, mediaTotal,
                    )
                    val finalSummary = initialSummary.copy(
                        id = dictionaryId,
                        counts = counts,
                        styles = styles,
                        importSuccess = true,
                    )
                    db.dictionaryDao().updateDictionary(finalSummary)

                    ImportResult(summary = finalSummary, errors = errors)
                }
            }
        } catch (e: Exception) {
            errors.add(e)
            ImportResult(summary = null, errors = errors)
        } finally {
            tempFile.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // index.json
    // ─────────────────────────────────────────────────────────────────────────

    private data class DictionaryIndex(
        val title: String,
        val revision: String,
        val version: Int,        // normalised from `format` or `version`
        val sequenced: Boolean,
        val author: String?,
        val url: String?,
        val description: String?,
        val attribution: String?,
        val frequencyMode: String?,
        val sourceLanguage: String?,
        val targetLanguage: String?,
        val minimumYomitanVersion: String?,
        val isUpdatable: Boolean?,
        val indexUrl: String?,
        val downloadUrl: String?,
        /** Inline tagMeta block from old-format index.json files. */
        val tagMeta: JsonObject?,
    )

    private fun readAndValidateIndex(zipFile: ZipFile): DictionaryIndex {
        val entry = zipFile.getEntry("index.json")
            ?: throw Exception(context.getString(R.string.error_no_index_found))
        val json = zipFile.getInputStream(entry).use { stream ->
            Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
        }

        val formatEl = json["format"]
        val versionEl = json["version"]
        val version = (formatEl as? JsonPrimitive)?.intOrNull
            ?: (versionEl as? JsonPrimitive)?.intOrNull
            ?: throw Exception(context.getString(R.string.error_unrecognized_format))

        val title = json["title"]?.jsonPrimitive?.content
            ?: throw Exception(context.getString(R.string.error_unrecognized_format))
        val revision = json["revision"]?.jsonPrimitive?.content
            ?: throw Exception(context.getString(R.string.error_unrecognized_format))

        return DictionaryIndex(
            title = title,
            revision = revision,
            version = version,
            sequenced = json["sequenced"]?.jsonPrimitive?.booleanOrNull ?: false,
            author = json["author"]?.jsonPrimitive?.content,
            url = json["url"]?.jsonPrimitive?.content,
            description = json["description"]?.jsonPrimitive?.content,
            attribution = json["attribution"]?.jsonPrimitive?.content,
            frequencyMode = json["frequencyMode"]?.jsonPrimitive?.content,
            sourceLanguage = json["sourceLanguage"]?.jsonPrimitive?.content,
            targetLanguage = json["targetLanguage"]?.jsonPrimitive?.content,
            minimumYomitanVersion = json["minimumYomitanVersion"]?.jsonPrimitive?.content,
            isUpdatable = json["isUpdatable"]?.jsonPrimitive?.booleanOrNull,
            indexUrl = json["indexUrl"]?.jsonPrimitive?.content,
            downloadUrl = json["downloadUrl"]?.jsonPrimitive?.content,
            tagMeta = json["tagMeta"]?.jsonObject,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Term bank parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * An image resolution requirement collected during glossary parsing.
     * Mirrors the ImportRequirement union type.
     */
    private data class ImportRequirement(
        val type: String,               // "image" | "structured-content-image"
        val target: JsonObject,
        val sourcePath: String,
        val expression: String,
        val reading: String,
        val dictionary: String
    )

    /**
     * JS: _convertTermBankEntryV1(entry, dictionary)
     *
     * V1: [expression, reading, definitionTags, rules, score, ...glossary]
     * glossary items are always plain strings in V1.
     * No sequence or termTags fields.
     */
    private fun convertTermBankEntryV1(
        arr: JsonArray,
        dictionary: String,
        prefixWildcardsSupported: Boolean,
    ): TermEntity {
        val expression = arr[0].jsonPrimitive.content
        var reading = arr[1].jsonPrimitive.content
        if (reading.isEmpty()) reading = expression
        val definitionTags = arr[2].asStringOrNull()
        val rules = arr[3].jsonPrimitive.content
        val score = arr[4].jsonPrimitive.double
        // V1: rest of array is glossary items (all strings)
        val glossary = JsonArray(arr.drop(5)).toString()

        return TermEntity(
            dictionary = dictionary,
            expression = expression,
            reading = reading,
            expressionReverse = if (prefixWildcardsSupported) expression.reversed() else null,
            readingReverse = if (prefixWildcardsSupported) reading.reversed() else null,
            definitionTags = definitionTags,
            rules = rules,
            score = score,
            glossary = glossary,
            sequence = null,
            termTags = null,
        )
    }

    /**
     * Parse a term_bank_N.json byte array.
     * Walks glossary items and collects image requirements for later resolution.
     *
     * JS: for (termFile of termFiles) { termList = _readFileSequence([termFile], _convertTermBankEntryV3) }
     *     followed by the glossary object-walk loop.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readTermBankV3(
        inputStream: InputStream,
        dictionary: String,
        prefixWildcardsSupported: Boolean,
        requirements: MutableList<ImportRequirement>,
    ): List<TermEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val expression = arr[0].jsonPrimitive.content
            var reading = arr[1].jsonPrimitive.content
            if (reading.isEmpty()) reading = expression
            val definitionTags = arr[2].asStringOrNull()
            val rules = arr[3].jsonPrimitive.content
            val score = arr[4].jsonPrimitive.double
            val glossaryArr = arr[5].jsonArray
            val sequence = arr.getOrNull(6)?.jsonPrimitive?.longOrNull
            val termTags = arr.getOrNull(7)?.jsonPrimitive?.content

            val processedGlossary = processGlossary(
                glossaryArr, expression, reading, dictionary, requirements
            )

            TermEntity(
                dictionary = dictionary,
                expression = expression,
                reading = reading,
                expressionReverse = if (prefixWildcardsSupported) expression.reversed() else null,
                readingReverse = if (prefixWildcardsSupported) reading.reversed() else null,
                definitionTags = definitionTags,
                rules = rules,
                score = score,
                glossary = processedGlossary.toString(),
                sequence = sequence,
                termTags = termTags,
            )
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readTermBankV1(
        inputStream: InputStream,
        dictionary: String,
        prefixWildcardsSupported: Boolean,
    ): List<TermEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            convertTermBankEntryV1(elem.jsonArray, dictionary, prefixWildcardsSupported)
        }
    }

    /**
     * JS: the glossary-walk loop after _convertTermBankEntryV3.
     *
     * For each glossary element that is a non-null, non-array object,
     * call _formatDictionaryTermGlossaryObject.
     */
    private fun processGlossary(
        glossary: JsonArray,
        expression: String,
        reading: String,
        dictionary: String,
        requirements: MutableList<ImportRequirement>,
    ): JsonArray {
        val result = glossary.map { item ->
            if (item is JsonObject) formatGlossaryObject(item, expression, reading, dictionary, requirements)
            else item
        }
        return JsonArray(result)
    }

    private fun formatGlossaryObject(
        data: JsonObject,
        expression: String,
        reading: String,
        dictionary: String,
        requirements: MutableList<ImportRequirement>,
    ): JsonElement {
        return when (data["type"]?.jsonPrimitive?.content) {
            "text" -> JsonPrimitive(data["text"]?.jsonPrimitive?.content ?: "")
            "image" -> {
                buildImageTarget(data, expression, reading, dictionary, requirements, "image")
            }

            "structured-content" -> {
                val content = data["content"] ?: JsonNull
                val processed = prepareStructuredContent(content, expression, reading, dictionary, requirements)
                JsonObject(mapOf("type" to JsonPrimitive("structured-content"), "content" to processed))
            }

            else -> data
        }
    }

    @Suppress("SameParameterValue")
    private fun buildImageTarget(
        source: JsonObject,
        expression: String,
        reading: String,
        dictionary: String,
        requirements: MutableList<ImportRequirement>,
        reqType: String,
    ): JsonObject {
        val path = source["path"]?.jsonPrimitive?.content ?: ""
        val target = JsonObject(mapOf("tag" to JsonPrimitive("img"), "path" to JsonPrimitive(path)))
        requirements.add(ImportRequirement(reqType, target, path, expression, reading, dictionary))
        return JsonObject(buildMap {
            put("type", JsonPrimitive("image"))
            put("path", JsonPrimitive(path))
            // Copy optional fields
            source["width"]?.let { put("width", it) }
            source["height"]?.let { put("height", it) }
            source["title"]?.let { put("title", it) }
            source["alt"]?.let { put("alt", it) }
            source["description"]?.let { put("description", it) }
            source["pixelated"]?.let { put("pixelated", it) }
            source["imageRendering"]?.let { put("imageRendering", it) }
            source["appearance"]?.let { put("appearance", it) }
            source["background"]?.let { put("background", it) }
            source["collapsed"]?.let { put("collapsed", it) }
            source["collapsible"]?.let { put("collapsible", it) }
        })
    }

    private fun prepareStructuredContent(
        content: JsonElement,
        expression: String,
        reading: String,
        dictionary: String,
        requirements: MutableList<ImportRequirement>,
    ): JsonElement {
        return when (content) {
            is JsonPrimitive -> content
            is JsonArray -> JsonArray(content.map {
                prepareStructuredContent(
                    it,
                    expression,
                    reading,
                    dictionary,
                    requirements
                )
            })

            is JsonObject -> {
                if (content["tag"]?.jsonPrimitive?.content == "img") {
                    val path = content["path"]?.jsonPrimitive?.content ?: ""
                    requirements.add(
                        ImportRequirement(
                            "structured-content-image",
                            JsonObject(mapOf("tag" to JsonPrimitive("img"), "path" to JsonPrimitive(path))),
                            path, expression, reading, dictionary
                        )
                    )
                    JsonObject(content.toMutableMap().apply {
                        put("path", JsonPrimitive(path))
                    })
                } else {
                    val childContent = content["content"]
                    if (childContent != null) {
                        JsonObject(content.toMutableMap().apply {
                            put(
                                "content",
                                prepareStructuredContent(childContent, expression, reading, dictionary, requirements)
                            )
                        })
                    } else content
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Media resolution
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveRequirements(
        requirements: List<ImportRequirement>,
        zipFile: ZipFile,
    ): List<MediaEntity> {
        val media = mutableMapOf<String, MediaEntity>()
        for (req in requirements) {
            val path = req.sourcePath
            if (media.containsKey(path)) continue
            val entry = zipFile.getEntry(path)
                ?: throw Exception(context.getString(R.string.error_image_not_found, path, req.expression))
            val mediaType = getImageMediaTypeFromFileName(path)
                ?: throw Exception(context.getString(R.string.error_unknown_media_type, path))
            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
            val details = mediaLoader.getImageDetails(bytes, mediaType)
            media[path] = MediaEntity(
                dictionary = req.dictionary,
                path = path,
                mediaType = mediaType,
                width = details.width,
                height = details.height,
                content = details.content,
            )
        }
        return media.values.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // termMeta bank parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: _convertTermMetaBankEntry(entry, dictionary)
     *
     * Array: [expression, mode, data]
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readTermMetaBank(inputStream: InputStream, dictionary: String): List<TermMetaEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val expr = arr[0].jsonPrimitive.content
            val mode = arr[1].jsonPrimitive.content
            val data = arr[2].toString()
            TermMetaEntity(dictionary = dictionary, expression = expr, mode = mode, data = data)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // kanji bank parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: _convertKanjiBankEntryV1(entry, dictionary)
     *
     * V1: [character, onyomi, kunyomi, tags, ...meanings]
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readKanjiBankV1(inputStream: InputStream, dictionary: String): List<KanjiEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val character = arr[0].jsonPrimitive.content
            val onyomi = arr[1].jsonPrimitive.content
            val kunyomi = arr[2].jsonPrimitive.content
            val tags = arr[3].jsonPrimitive.content
            val meanings = JsonArray(arr.drop(4)).toString()
            KanjiEntity(
                dictionary = dictionary, character = character,
                onyomi = onyomi, kunyomi = kunyomi, tags = tags,
                meanings = meanings, stats = null
            )
        }
    }

    /**
     * JS: _convertKanjiBankEntryV3(entry, dictionary)
     *
     * V3: [character, onyomi, kunyomi, tags, meanings[], stats{}]
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readKanjiBankV3(inputStream: InputStream, dictionary: String): List<KanjiEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val character = arr[0].jsonPrimitive.content
            val onyomi = arr[1].jsonPrimitive.content
            val kunyomi = arr[2].jsonPrimitive.content
            val tags = arr[3].jsonPrimitive.content
            val meanings = arr[4].jsonArray.toString()
            val stats = arr.getOrNull(5)?.takeIf { it !is JsonNull }?.toString()
            KanjiEntity(
                dictionary = dictionary, character = character,
                onyomi = onyomi, kunyomi = kunyomi, tags = tags,
                meanings = meanings, stats = stats
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // kanjiMeta bank parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: _convertKanjiMetaBankEntry(entry, dictionary)
     *
     * Array: [character, mode, data]
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readKanjiMetaBank(inputStream: InputStream, dictionary: String): List<KanjiMetaEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val char = arr[0].jsonPrimitive.content
            val mode = arr[1].jsonPrimitive.content
            val data = arr[2].toString()
            KanjiMetaEntity(dictionary = dictionary, character = char, mode = mode, data = data)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // tag bank parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: _convertTagBankEntry(entry, dictionary)
     *
     * Array: [name, category, order, notes, score]
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readTagBank(inputStream: InputStream, dictionary: String): List<TagMetaEntity> {
        val root = Json.decodeFromStream<JsonArray>(inputStream)
        return root.map { elem ->
            val arr = elem.jsonArray
            val name = arr[0].jsonPrimitive.content
            val category = arr[1].jsonPrimitive.content
            val order = arr[2].jsonPrimitive.intOrNull ?: 0
            val notes = arr[3].jsonPrimitive.content
            val score = arr[4].jsonPrimitive.intOrNull ?: 0
            TagMetaEntity(
                dictionary = dictionary, name = name, category = category,
                order = order, notes = notes, score = score
            )
        }
    }

    /**
     * JS: _addOldIndexTags(index, results, dictionary)
     *
     * Older dictionaries embed a `tagMeta` object directly in index.json
     * instead of (or in addition to) separate tag_bank_N.json files.
     * Each key is the tag name; the value is {category, order, notes, score}.
     */
    private fun addOldIndexTags(
        index: DictionaryIndex,
        results: MutableList<TagMetaEntity>,
        dictionary: String,
    ) {
        val tagMeta = index.tagMeta ?: return
        for ((name, value) in tagMeta) {
            val obj = value.jsonObject
            val category = obj["category"]?.jsonPrimitive?.content ?: ""
            val order = obj["order"]?.jsonPrimitive?.intOrNull ?: 0
            val notes = obj["notes"]?.jsonPrimitive?.content ?: ""
            val score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0
            results.add(
                TagMetaEntity(
                    dictionary = dictionary, name = name,
                    category = category, order = order, notes = notes, score = score
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Summary helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * JS: _createSummary(dictionaryTitle, version, index, summaryDetails)
     * (initial, incomplete version - importSuccess=false, counts=null)
     */
    private fun buildInitialSummary(
        index: DictionaryIndex,
        details: ImportDetails,
        importDate: Long,
    ) = DictionaryEntity(
        title = index.title,
        revision = index.revision,
        sequenced = index.sequenced,
        version = index.version,
        importDate = importDate,
        prefixWildcardsSupported = details.prefixWildcardsSupported,
        styles = "",
        counts = null,
        importSuccess = false,
        minimumYomitanVersion = index.minimumYomitanVersion,
        author = index.author,
        url = index.url,
        description = index.description,
        attribution = index.attribution,
        frequencyMode = index.frequencyMode,
        sourceLanguage = index.sourceLanguage,
        targetLanguage = index.targetLanguage,
        isUpdatable = index.isUpdatable,
        indexUrl = index.indexUrl,
        downloadUrl = index.downloadUrl,
    )

    /**
     * JS: SummaryCounts serialised as JSON.
     * {terms:{total}, termMeta:{total,freq?,pitch?,ipa?}, kanji:{total},
     *  kanjiMeta:{total,freq?}, tagMeta:{total}, media:{total}}
     */
    private fun buildCounts(
        termsTotal: Int,
        termMetaTotal: Int, termMetaModes: Map<String, Int>,
        kanjiTotal: Int,
        kanjiMetaTotal: Int, kanjiMetaModes: Map<String, Int>,
        tagMetaTotal: Int,
        mediaTotal: Int,
    ): String {
        fun modeMap(total: Int, modes: Map<String, Int>): JsonObject {
            val map = mutableMapOf<String, JsonElement>("total" to JsonPrimitive(total))
            modes.forEach { (k, v) -> map[k] = JsonPrimitive(v) }
            return JsonObject(map)
        }

        val obj = JsonObject(
            mapOf(
                "terms" to JsonObject(mapOf("total" to JsonPrimitive(termsTotal))),
                "termMeta" to modeMap(termMetaTotal, termMetaModes),
                "kanji" to JsonObject(mapOf("total" to JsonPrimitive(kanjiTotal))),
                "kanjiMeta" to modeMap(kanjiMetaTotal, kanjiMetaModes),
                "tagMeta" to JsonObject(mapOf("total" to JsonPrimitive(tagMetaTotal))),
                "media" to JsonObject(mapOf("total" to JsonPrimitive(mediaTotal))),
            )
        )
        return obj.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress
    // ─────────────────────────────────────────────────────────────────────────

    private fun progressNextStep(count: Int) {
        progressIndex = 0
        progressCount = count
        onProgress(progressIndex, progressCount)
    }

    private fun progress() {
        progressIndex = minOf(progressIndex + chunkSize, progressCount)
        onProgress(progressIndex, progressCount)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bulk-add helper
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun <T> bulkAdd(items: List<T>, insert: suspend (List<T>) -> Unit) {
        var i = 0
        while (i < items.size) {
            val end = minOf(i + chunkSize, items.size)
            insert(items.subList(i, end))
            i = end
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun bankNumber(filename: String): Int =
        Regex("_(\\d+)\\.json$").find(filename)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun JsonElement.asStringOrNull(): String? =
        if (this is JsonNull) null
        else (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    /** Mirrors getImageMediaTypeFromFileName in media-util.js */
    private fun getImageMediaTypeFromFileName(path: String): String? {
        return when (path.substringAfterLast('.').lowercase()) {
            "apng" -> "image/apng"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "ico", "cur" -> "image/x-icon"
            "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "webp" -> "image/webp"
            else -> null
        }
    }
}
