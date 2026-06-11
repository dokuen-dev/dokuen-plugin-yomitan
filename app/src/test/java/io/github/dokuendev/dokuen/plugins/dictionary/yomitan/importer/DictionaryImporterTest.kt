package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DictionaryImporterTest {

    @Test
    fun testImportDictionary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        // Clear database first on IO thread
        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        // Build a mock ZIP file in memory
        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        // 1. Add index.json
        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Test Dictionary",
                "author": "Test Author",
                "revision": "2.0",
                "description": "A test yomitan dictionary",
                "format": 3,
                "sequenced": true,
                "attribution": "Contributors"
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // 2. Add term_bank_1.json
        val termEntry = ZipEntry("term_bank_1.json")
        zipOutputStream.putNextEntry(termEntry)
        val termJson = """
            [
                ["食べる", "たべる", "v1", "v1", 100.0, ["to eat"], 1, "P"],
                ["犬", "いぬ", "n", "", 80.0, ["dog"], 2, ""]
            ]
        """.trimIndent()
        zipOutputStream.write(termJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // 3. Add tag_bank_1.json
        val tagEntry = ZipEntry("tag_bank_1.json")
        zipOutputStream.putNextEntry(tagEntry)
        val tagJson = """
            [
                ["v1", "partOfSpeech", 15, "Ichidan verb", 3],
                ["P", "popular", -1, "Popular word", 1]
            ]
        """.trimIndent()
        zipOutputStream.write(tagJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        // Run the importer
        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)
        val meta = result.summary!!
        assertEquals("Test Dictionary", meta.title)
        assertEquals("Test Author", meta.author)
        assertEquals("2.0", meta.revision)
        assertEquals(3, meta.version)
        assertEquals(true, meta.sequenced)
        assertEquals("Contributors", meta.attribution)
        assertEquals(true, meta.importSuccess)

        // Verify terms in database using TermDao
        val terms = db.termDao().findByExpression("食べる", listOf(meta.title))
        assertEquals(1, terms.size)
        assertEquals("たべる", terms[0].reading)
        assertEquals("v1", terms[0].definitionTags)
        assertEquals("[\"to eat\"]", terms[0].glossary)

        val dogs = db.termDao().findByExpression("犬", listOf(meta.title))
        assertEquals(1, dogs.size)
        assertEquals("いぬ", dogs[0].reading)
        assertEquals("[\"dog\"]", dogs[0].glossary)

        // Verify tags in database using TagMetaDao
        val v1Tag = db.tagMetaDao().findTagForTitle("v1", meta.title)
        assertNotNull(v1Tag)
        assertEquals("partOfSpeech", v1Tag!!.category)
        assertEquals("Ichidan verb", v1Tag.notes)
        assertEquals(15, v1Tag.order)
        assertEquals(3, v1Tag.score)

        val pTag = db.tagMetaDao().findTagForTitle("P", meta.title)
        assertNotNull(pTag)
        assertEquals("popular", pTag!!.category)
        assertEquals("Popular word", pTag.notes)
        assertEquals(-1, pTag.order)
        assertEquals(1, pTag.score)
    }

    @Test
    fun testImportProgressReporting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Progress Dict",
                "author": "Author",
                "revision": "1.0",
                "format": 3,
                "sequenced": false
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        val termEntry = ZipEntry("term_bank_1.json")
        zipOutputStream.putNextEntry(termEntry)
        val termJson = """
            [
                ["食べる", "たべる", "v1", "v1", 100.0, ["to eat"], 1, "P"]
            ]
        """.trimIndent()
        zipOutputStream.write(termJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        val tagEntry = ZipEntry("tag_bank_1.json")
        zipOutputStream.putNextEntry(tagEntry)
        val tagJson = """
            [
                ["v1", "partOfSpeech", 15, "Ichidan verb", 3]
            ]
        """.trimIndent()
        zipOutputStream.write(tagJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader) { current, total ->
            progressCalls.add(Pair(current, total))
        }

        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())
        assertNotNull(result.summary)
        assertTrue(result.summary!!.importSuccess)

        // Verify that progress calls never overshot total count
        for (call in progressCalls) {
            assertTrue("Progress ${call.first} must be <= total ${call.second}", call.first <= call.second)
        }

        // Verify the expected progress callbacks sequence
        val expected = listOf(
            Pair(0, 2),     // Step 3 initial progress
            Pair(0, 3000),   // Step 5 progress count reset
            Pair(1000, 3000), // Step 6 term media
            Pair(2000, 3000), // Step 6 term list
            Pair(3000, 3000)  // Step 10 tag bank
        )
        assertEquals(expected, progressCalls)
    }

    @Test
    fun testImportDictionaryV1() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        // 1. index.json for V1 format
        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "V1 Dict",
                "author": "V1 Author",
                "revision": "1.0",
                "format": 1,
                "sequenced": false
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // 2. term_bank_1.json for V1 format (rest of array are glossary strings)
        val termEntry = ZipEntry("term_bank_1.json")
        zipOutputStream.putNextEntry(termEntry)
        val termJson = """
            [
                ["犬", "いぬ", "n", "", 80.0, "dog", "canine"]
            ]
        """.trimIndent()
        zipOutputStream.write(termJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // 3. kanji_bank_1.json for V1 format (rest of array are meanings strings)
        val kanjiEntry = ZipEntry("kanji_bank_1.json")
        zipOutputStream.putNextEntry(kanjiEntry)
        val kanjiJson = """
            [
                ["木", "モク", "き", "grade1", "tree", "wood"]
            ]
        """.trimIndent()
        zipOutputStream.write(kanjiJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)
        assertEquals("V1 Dict", result.summary!!.title)
        assertEquals(true, result.summary!!.importSuccess)

        // Verify V1 term
        val terms = db.termDao().findByExpression("犬", listOf("V1 Dict"))
        assertEquals(1, terms.size)
        assertEquals("いぬ", terms[0].reading)
        assertEquals("[\"dog\",\"canine\"]", terms[0].glossary)

        // Verify V1 kanji
        val kanjiMatches = db.kanjiDao().findByCharacterBulk(listOf("木"), listOf("V1 Dict"))
        assertEquals(1, kanjiMatches.size)
        assertEquals("モク", kanjiMatches[0].onyomi)
        assertEquals("き", kanjiMatches[0].kunyomi)
        assertEquals("[\"tree\",\"wood\"]", kanjiMatches[0].meanings)
    }

    @Test
    fun testImportKanjiAndKanjiMetaV3() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Kanji Dict",
                "author": "Kanji Author",
                "revision": "3.0",
                "format": 3,
                "sequenced": false
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // kanji_bank_1.json for V3
        val kanjiEntry = ZipEntry("kanji_bank_1.json")
        zipOutputStream.putNextEntry(kanjiEntry)
        val kanjiJson = """
            [
                ["水", "スイ", "みず", "grade1", ["water", "fluid"], {"stroke_count": "4"}]
            ]
        """.trimIndent()
        zipOutputStream.write(kanjiJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // kanji_meta_bank_1.json
        val kanjiMetaEntry = ZipEntry("kanji_meta_bank_1.json")
        zipOutputStream.putNextEntry(kanjiMetaEntry)
        val kanjiMetaJson = """
            [
                ["水", "freq", 100]
            ]
        """.trimIndent()
        zipOutputStream.write(kanjiMetaJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)
        assertEquals("Kanji Dict", result.summary!!.title)

        // Verify V3 kanji
        val kanjis = db.kanjiDao().findByCharacterBulk(listOf("水"), listOf("Kanji Dict"))
        assertEquals(1, kanjis.size)
        assertEquals("スイ", kanjis[0].onyomi)
        assertEquals("みず", kanjis[0].kunyomi)
        assertEquals("[\"water\",\"fluid\"]", kanjis[0].meanings)
        assertEquals("{\"stroke_count\":\"4\"}", kanjis[0].stats)

        // Verify kanji metadata
        val metas = db.kanjiMetaDao().findByCharacterBulk(listOf("水"), listOf("Kanji Dict"))
        assertEquals(1, metas.size)
        assertEquals("freq", metas[0].mode)
        assertEquals("100", metas[0].data)
    }

    @Test
    fun testImportTermMetaV3() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "TermMeta Dict",
                "format": 3,
                "revision": "1.0",
                "sequenced": true
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // term_meta_bank_1.json
        val metaEntry = ZipEntry("term_meta_bank_1.json")
        zipOutputStream.putNextEntry(metaEntry)
        val metaJson = """
            [
                ["食べる", "pitch", {"position": 2}]
            ]
        """.trimIndent()
        zipOutputStream.write(metaJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)

        val metas = db.termMetaDao().findByExpressionBulk(listOf("食べる"), listOf("TermMeta Dict"))
        assertEquals(1, metas.size)
        assertEquals("pitch", metas[0].mode)
        assertEquals("{\"position\":2}", metas[0].data)
    }

    @Test
    fun testImportWithMedia() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Media Dict",
                "format": 3,
                "revision": "1.0",
                "sequenced": false
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // term_bank_1.json with image glossary item
        val termEntry = ZipEntry("term_bank_1.json")
        zipOutputStream.putNextEntry(termEntry)
        val termJson = """
            [
                ["写真", "しゃしん", "n", "", 100.0, [
                    {
                        "type": "image",
                        "path": "media/camera.svg"
                    }
                ], 1, ""]
            ]
        """.trimIndent()
        zipOutputStream.write(termJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        // media/camera.svg (Avoids BitmapFactory decoding by using svg format)
        val mediaEntry = ZipEntry("media/camera.svg")
        zipOutputStream.putNextEntry(mediaEntry)
        val dummySvgBytes = "<svg></svg>".toByteArray(Charsets.UTF_8)
        zipOutputStream.write(dummySvgBytes)
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)
        assertEquals(true, result.summary!!.importSuccess)

        // Verify media entity saved in database
        val media = db.mediaDao().getMedia("media/camera.svg", "Media Dict")
        assertNotNull(media)
        assertEquals("image/svg+xml", media!!.mediaType)
        assertEquals(0, media.width)
        assertEquals(0, media.height)
    }

    @Test
    fun testImportDuplicateDictionary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        // Insert a dictionary first
        db.dictionaryDao().insertDictionary(
            DictionaryEntity(
                title = "Duplicate Dict",
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = System.currentTimeMillis(),
                prefixWildcardsSupported = true,
                styles = "",
                counts = null,
                importSuccess = true
            )
        )

        // Build index.json with the same title
        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Duplicate Dict",
                "format": 3,
                "revision": "2.0",
                "sequenced": false
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        // The result should have null summary and an exception in errors
        assertEquals(null, result.summary)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].message!!.contains("already imported"))
    }

    @Test
    fun testImportOldIndexTagMeta() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val byteOutputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOutputStream)

        // index.json containing tagMeta block
        val indexEntry = ZipEntry("index.json")
        zipOutputStream.putNextEntry(indexEntry)
        val indexJson = """
            {
                "title": "Old TagMeta Dict",
                "format": 3,
                "revision": "1.0",
                "sequenced": false,
                "tagMeta": {
                    "v1": {
                        "category": "partOfSpeech",
                        "order": 15,
                        "notes": "Ichidan verb",
                        "score": 3
                    }
                }
            }
        """.trimIndent()
        zipOutputStream.write(indexJson.toByteArray(Charsets.UTF_8))
        zipOutputStream.closeEntry()

        zipOutputStream.close()

        val mediaLoader = AndroidMediaLoader()
        val importer = DictionaryImporter(app, db, mediaLoader)
        val result = importer.importDictionary(ByteArrayInputStream(byteOutputStream.toByteArray()), ImportDetails())

        assertNotNull(result.summary)
        assertEquals("Old TagMeta Dict", result.summary!!.title)

        // Verify tag v1 is imported into database
        val tag = db.tagMetaDao().findTagForTitle("v1", "Old TagMeta Dict")
        assertNotNull(tag)
        assertEquals("partOfSpeech", tag!!.category)
        assertEquals("Ichidan verb", tag.notes)
        assertEquals(15, tag.order)
        assertEquals(3, tag.score)
    }
}
