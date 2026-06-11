package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryDao
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaDao
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TagMetaEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermMetaDao
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.CssProcessor
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.renderer.YomitanRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

object DebugGenerator {
    @JvmStatic
    fun main() = runBlocking {
        println("================================================================================")
        println("RUNNING LOCAL DEBUG ENTRY GENERATOR")
        println("================================================================================")

        val projectRoot = findProjectRoot()
        if (projectRoot == null) {
            println("ERROR: Could not locate project root.")
            return@runBlocking
        }
        println("Project root found at: ${projectRoot.absolutePath}")

        // 1. Render Term Entry
        val termDir = File(projectRoot, "app/src/test/resources/testcases/term_rendering")
        val entriesFile = File(termDir, "entries.json")
        val stylesFile = File(termDir, "styles.css")
        if (entriesFile.exists() && stylesFile.exists()) {
            println("Rendering term entries from entries.json...")
            val indexFile = File(termDir, "index.json")
            val indexJson = if (indexFile.exists()) {
                Json.parseToJsonElement(indexFile.readText()).jsonObject
            } else {
                null
            }
            val dictionaryTitle = indexJson?.get("title")?.jsonPrimitive?.content ?: "Jitendex.org"
            val terms = parseTermEntries(entriesFile.readText(), dictionaryTitle)
            val styles = stylesFile.readText()

            // Setup mock DB for tag metadata & term metadata
            val mockDatabase = mock(AppDatabase::class.java)
            val mockTagDao = mock(TagMetaDao::class.java)
            val mockTermMetaDao = mock(TermMetaDao::class.java)
            val mockDictionaryDao = mock(DictionaryDao::class.java)
            `when`(mockDatabase.tagMetaDao()).thenReturn(mockTagDao)
            `when`(mockDatabase.termMetaDao()).thenReturn(mockTermMetaDao)
            `when`(mockDatabase.dictionaryDao()).thenReturn(mockDictionaryDao)

            // Setup mock DictionaryEntity from indexJson
            val mockDictionaryEntity = if (indexJson != null) {
                val title = indexJson["title"]?.jsonPrimitive?.content ?: "Jitendex.org"
                val revision = indexJson["revision"]?.jsonPrimitive?.content ?: "1"
                val author = indexJson["author"]?.jsonPrimitive?.contentOrNull
                val url = indexJson["url"]?.jsonPrimitive?.contentOrNull
                val description = indexJson["description"]?.jsonPrimitive?.contentOrNull
                val attribution = indexJson["attribution"]?.jsonPrimitive?.contentOrNull
                val countsStr = """{"terms":{"total":430822}}"""

                DictionaryEntity(
                    title = title,
                    revision = revision,
                    sequenced = indexJson["sequenced"]?.jsonPrimitive?.booleanOrNull ?: false,
                    version = indexJson["format"]?.jsonPrimitive?.intOrNull ?: 3,
                    importDate = System.currentTimeMillis(),
                    prefixWildcardsSupported = false,
                    styles = "",
                    counts = countsStr,
                    importSuccess = true,
                    author = author,
                    url = url,
                    description = description,
                    attribution = attribution
                )
            } else {
                null
            }
            `when`(mockDictionaryDao.getDictionaryByTitle(dictionaryTitle)).thenReturn(mockDictionaryEntity)

            // Load term tag metadata dynamically
            val tagMetaList = mutableListOf<TagMetaEntity>()

            // 1. Try to load from index.json tagMeta block (older formats)
            val indexTagMeta = indexJson?.get("tagMeta")?.jsonObject
            if (indexTagMeta != null) {
                for ((name, value) in indexTagMeta) {
                    val obj = value.jsonObject
                    val category = obj["category"]?.jsonPrimitive?.content ?: ""
                    val order = obj["order"]?.jsonPrimitive?.intOrNull ?: 0
                    val notes = obj["notes"]?.jsonPrimitive?.content ?: ""
                    val score = obj["score"]?.jsonPrimitive?.intOrNull ?: 0
                    tagMetaList.add(
                        TagMetaEntity(
                            dictionary = dictionaryTitle,
                            name = name,
                            category = category,
                            order = order,
                            notes = notes,
                            score = score
                        )
                    )
                }
            }

            // 2. Try to load from tag_bank_1.json
            val termTagBankFile = File(termDir, "tag_bank_1.json").takeIf { it.exists() }
            if (termTagBankFile != null) {
                println("Loading term tags from: ${termTagBankFile.name}")
                tagMetaList.addAll(parseTagBank(termTagBankFile.readText(), dictionaryTitle))
            } else {
                println("INFO: No term tag bank file found (tag_bank_1.json). Using empty tag metadata.")
            }

            `when`(mockTagDao.findAllForDictionary(dictionaryTitle)).thenReturn(tagMetaList)
            `when`(mockTermMetaDao.findByExpressionBulk(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(
                emptyList()
            )

            val yomitanRenderer = createYomitanRenderer(mockDatabase)

            val renderedEntries = terms.map { term ->
                yomitanRenderer.renderTermEntry(term, styles)
            }

            val jsonOutput = DictionaryEntrySerializer.toJson(renderedEntries)
            val outputFile = File(projectRoot, "debug_entry.json")
            outputFile.writeText(jsonOutput)
            println("Successfully generated ${renderedEntries.size} term entry/entries in: ${outputFile.absolutePath}")
        } else {
            println("WARNING: entries.json or styles.css not found.")
        }

        // 2. Render Kanji Entry
        val kanjiDir = File(projectRoot, "app/src/test/resources/testcases/kanji_rendering")
        val kanjiEntriesFile = File(kanjiDir, "entries.json")
        val kanjiTagBankFile = File(kanjiDir, "tag_bank_1.json")
        if (kanjiEntriesFile.exists() && kanjiTagBankFile.exists()) {
            println("Rendering kanji entry from entries.json...")
            val kanji = parseKanjiEntry(kanjiEntriesFile.readText())
            val tags = parseTagBank(kanjiTagBankFile.readText())

            // Setup mock DB for tag metadata & term metadata
            val mockDatabase = mock(AppDatabase::class.java)
            val mockTagDao = mock(TagMetaDao::class.java)
            val mockDictionaryDao = mock(DictionaryDao::class.java)
            `when`(mockDatabase.tagMetaDao()).thenReturn(mockTagDao)
            `when`(mockDatabase.dictionaryDao()).thenReturn(mockDictionaryDao)
            `when`(mockTagDao.findAllForDictionary("Test Kanji Dictionary")).thenReturn(tags)
            `when`(mockDictionaryDao.getDictionaryByTitle("Test Kanji Dictionary")).thenReturn(
                DictionaryEntity(
                    title = "Test Kanji Dictionary",
                    revision = "1",
                    sequenced = false,
                    version = 3,
                    importDate = System.currentTimeMillis(),
                    prefixWildcardsSupported = false,
                    styles = "",
                    counts = """{"kanji":{"total":1}}""",
                    importSuccess = true,
                    description = "Test Kanji Dictionary Description"
                )
            )

            val yomitanRenderer = createYomitanRenderer(mockDatabase)

            val renderedKanji = yomitanRenderer.renderKanjiEntry(kanji, "")

            val jsonOutput = DictionaryEntrySerializer.toJson(listOf(renderedKanji))
            val outputFile = File(projectRoot, "debug_kanji_entry.json")
            outputFile.writeText(jsonOutput)
            println("Successfully generated 1 kanji entry in: ${outputFile.absolutePath}")
        } else {
            println("WARNING: entries.json or tag_bank_1.json not found.")
        }

        println("================================================================================")
        println("GENERATION COMPLETED SUCCESSFULLY")
        println("================================================================================")
    }

    private fun createYomitanRenderer(mockDatabase: AppDatabase): YomitanRenderer {
        val cssProcessor = CssProcessor()
        return YomitanRenderer(mockDatabase, cssProcessor)
    }

    private fun findProjectRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun parseTermEntries(json: String, dictionaryTitle: String): List<TermEntity> {
        val trimmed = json.trim()
        val hasDoubleBracket = trimmed.startsWith("[") && trimmed.substring(1).trimStart().startsWith("[")
        val validJson = if (trimmed.endsWith(",")) {
            "[" + trimmed.dropLast(1) + "]"
        } else if (trimmed.startsWith("[") && !hasDoubleBracket) {
            "[$trimmed]"
        } else {
            trimmed
        }

        val jsonArray = Json.parseToJsonElement(validJson).jsonArray
        return jsonArray.map { element ->
            val entryArray = element.jsonArray
            val expression = entryArray[0].jsonPrimitive.content
            val reading = entryArray[1].jsonPrimitive.content
            val definitionTags = entryArray[2].jsonPrimitive.contentOrNull
            val rules = entryArray[3].jsonPrimitive.content
            val score = entryArray[4].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
            val glossary = entryArray[5].toString()
            val sequence = entryArray.getOrNull(6)?.jsonPrimitive?.longOrNull
            val termTags = entryArray.getOrNull(7)?.jsonPrimitive?.contentOrNull

            TermEntity(
                dictionary = dictionaryTitle,
                expression = expression,
                reading = reading,
                definitionTags = definitionTags,
                rules = rules,
                score = score,
                glossary = glossary,
                sequence = sequence,
                termTags = termTags
            )
        }
    }

    private fun parseKanjiEntry(json: String): KanjiEntity {
        val trimmed = json.trim()
        val validJson = if (trimmed.endsWith(",")) trimmed.dropLast(1) else trimmed
        val jsonArray = Json.parseToJsonElement(validJson).jsonArray
        val character = jsonArray[0].jsonPrimitive.content
        val onyomi = jsonArray[1].jsonPrimitive.content
        val kunyomi = jsonArray[2].jsonPrimitive.content
        val tags = jsonArray[3].jsonPrimitive.contentOrNull ?: ""
        val meanings = jsonArray[4].toString()
        val stats = jsonArray[5].toString()

        return KanjiEntity(
            dictionary = "Test Kanji Dictionary",
            character = character,
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = tags,
            meanings = meanings,
            stats = stats
        )
    }

    private fun parseTagBank(json: String, dictionaryTitle: String = "Test Kanji Dictionary"): List<TagMetaEntity> {
        val trimmed = json.trim()
        val validJson = if (trimmed.endsWith(",")) trimmed.dropLast(1) else trimmed
        val jsonArray = Json.parseToJsonElement(validJson).jsonArray
        return jsonArray.map { element ->
            val tagArray = element.jsonArray
            val name = tagArray[0].jsonPrimitive.content
            val category = tagArray[1].jsonPrimitive.content
            val order = tagArray[2].jsonPrimitive.intOrNull ?: 0
            val notes = tagArray[3].jsonPrimitive.content
            val score = tagArray[4].jsonPrimitive.intOrNull ?: 0

            TagMetaEntity(
                dictionary = dictionaryTitle,
                name = name,
                category = category,
                order = order,
                notes = notes,
                score = score
            )
        }
    }
}
