package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictionaryDaoTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(app)
        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. DictionaryDao Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testDictionaryDaoInsertUpdateReads() = runBlocking {
        val dictDao = db.dictionaryDao()

        // Insert initial summary
        val initialEntity = DictionaryEntity(
            title = "JMdict",
            revision = "1.0",
            sequenced = true,
            version = 3,
            importDate = 1000L,
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = false
        )
        val generatedId = dictDao.insertDictionary(initialEntity)
        assertTrue(generatedId > 0)

        // Verify dictionaryExists and getDictionaryByTitle / getDictionaryById
        assertEquals(1, dictDao.dictionaryExists("JMdict"))
        assertEquals(0, dictDao.dictionaryExists("NonExistent"))

        val fetchedByTitle = dictDao.getDictionaryByTitle("JMdict")
        assertNotNull(fetchedByTitle)
        assertEquals(generatedId, fetchedByTitle?.id)
        assertEquals("JMdict", fetchedByTitle?.title)
        assertEquals(false, fetchedByTitle?.importSuccess)

        val fetchedById = dictDao.getDictionaryById(generatedId)
        assertNotNull(fetchedById)
        assertEquals("JMdict", fetchedById?.title)

        // Update dictionary record
        val updatedEntity = fetchedByTitle!!.copy(
            styles = "body { color: red; }",
            counts = """{"terms": 100}""",
            importSuccess = true
        )
        dictDao.updateDictionary(updatedEntity)

        val reFetched = dictDao.getDictionaryByTitle("JMdict")
        assertNotNull(reFetched)
        assertEquals(true, reFetched?.importSuccess)
        assertEquals("body { color: red; }", reFetched?.styles)

        // getAllDictionaries
        val allDicts = dictDao.getAllDictionaries()
        assertEquals(1, allDicts.size)
        assertEquals("JMdict", allDicts[0].title)
    }

    @Test
    fun testDictionaryDaoGetFrequencyDictionaries() = runBlocking {
        val dictDao = db.dictionaryDao()

        // A normal term dictionary: no term_meta "freq" rows at all - not a frequency
        // dictionary, regardless of the (decorative, upstream-unused) frequencyMode field.
        dictDao.insertDictionary(
            DictionaryEntity(
                title = "Standard Dict",
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 1000L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = """{"terms":{"total":100},"termMeta":{"total":10,"pitch":10},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}""",
                importSuccess = true,
                frequencyMode = null
            )
        )
        // A real frequency dictionary that never declares frequencyMode (e.g. BCCWJ) - must
        // still be recognized, matching yomitan's own settings-page criterion
        // (counts.termMeta.freq > 0), not the frequencyMode field.
        dictDao.insertDictionary(
            DictionaryEntity(
                title = "BCCWJ",
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 1000L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = """{"terms":{"total":0},"termMeta":{"total":50,"freq":50},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}""",
                importSuccess = true,
                frequencyMode = null
            )
        )
        dictDao.insertDictionary(
            DictionaryEntity(
                title = "JPDB Freq",
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 1000L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = """{"terms":{"total":0},"termMeta":{"total":20,"freq":20},"kanji":{"total":0},"kanjiMeta":{"total":0},"tagMeta":{"total":0},"media":{"total":0}}""",
                importSuccess = true,
                frequencyMode = "rank-based"
            )
        )
        dictDao.insertDictionary(
            DictionaryEntity(
                title = "Innocent Freq (Failed Import)",
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 1000L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = null,
                importSuccess = false,
                frequencyMode = "occurrence-based"
            )
        )

        val freqDicts = dictDao.getFrequencyDictionaries()
        assertEquals(2, freqDicts.size)
        assertEquals(setOf("BCCWJ", "JPDB Freq"), freqDicts.map { it.title }.toSet())
    }

    @Test
    fun testDictionaryDaoCounts() = runBlocking {
        val dictDao = db.dictionaryDao()
        val dictName = "Count Test Dict"

        // Populate items across tables
        db.termDao().bulkAdd(
            listOf(
                TermEntity(
                    dictionary = dictName,
                    expression = "犬",
                    reading = "いぬ",
                    definitionTags = null,
                    rules = "",
                    score = 10.0,
                    glossary = "[\"dog\"]",
                    sequence = 1L,
                    termTags = null
                )
            )
        )
        db.termMetaDao().bulkAdd(
            listOf(
                TermMetaEntity(dictionary = dictName, expression = "犬", mode = "freq", data = "100")
            )
        )
        db.kanjiDao().bulkAdd(
            listOf(
                KanjiEntity(
                    dictionary = dictName,
                    character = "犬",
                    onyomi = "ケン",
                    kunyomi = "いぬ",
                    tags = "",
                    meanings = "[\"dog\"]",
                    stats = null
                )
            )
        )
        db.kanjiMetaDao().bulkAdd(
            listOf(
                KanjiMetaEntity(dictionary = dictName, character = "犬", mode = "freq", data = "1")
            )
        )
        db.tagMetaDao().bulkAdd(
            listOf(
                TagMetaEntity(dictionary = dictName, name = "n", category = "pos", notes = "", order = 1, score = 1)
            )
        )
        db.mediaDao().bulkAdd(
            listOf(
                MediaEntity(
                    dictionary = dictName,
                    path = "image.png",
                    mediaType = "image/png",
                    width = 10,
                    height = 10,
                    content = byteArrayOf(1, 2)
                )
            )
        )

        assertEquals(1L, dictDao.countTerms(dictName))
        assertEquals(1L, dictDao.countTermMeta(dictName))
        assertEquals(1L, dictDao.countKanji(dictName))
        assertEquals(1L, dictDao.countKanjiMeta(dictName))
        assertEquals(1L, dictDao.countTagMeta(dictName))
        assertEquals(1L, dictDao.countMedia(dictName))

        assertEquals(0L, dictDao.countTerms("Other Dict"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TermDao Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testTermDaoInsertAndBulkQueries() = runBlocking {
        val termDao = db.termDao()
        val dict = "Term Dict"

        val terms = listOf(
            TermEntity(
                dictionary = dict,
                expression = "食べる",
                reading = "たべる",
                definitionTags = null,
                rules = "v1",
                score = 100.0,
                glossary = "[\"to eat\"]",
                sequence = 10L,
                termTags = null,
                expressionReverse = "るべ食",
                readingReverse = "るべた"
            ),
            TermEntity(
                dictionary = dict,
                expression = "食す",
                reading = "しょくす",
                definitionTags = null,
                rules = "v5",
                score = 50.0,
                glossary = "[\"to eat\"]",
                sequence = 11L,
                termTags = null,
                expressionReverse = "す食",
                readingReverse = "すくょし"
            ),
            TermEntity(
                dictionary = dict,
                expression = "飲む",
                reading = "のむ",
                definitionTags = null,
                rules = "v5",
                score = 90.0,
                glossary = "[\"to drink\"]",
                sequence = 12L,
                termTags = null,
                expressionReverse = "む飲",
                readingReverse = "むの"
            )
        )
        termDao.bulkAdd(terms)

        // findByExpression
        val eatByExpr = termDao.findByExpression("食べる", listOf(dict))
        assertEquals(1, eatByExpr.size)
        assertEquals("たべる", eatByExpr[0].reading)

        // findByReading
        val eatByReading = termDao.findByReading("たべる", listOf(dict))
        assertEquals(1, eatByReading.size)
        assertEquals("食べる", eatByReading[0].expression)

        // findByExpressionOrReadingBulk
        val bulkMatches = termDao.findByExpressionOrReadingBulk(listOf("食べる", "のむ"), listOf(dict))
        assertEquals(2, bulkMatches.size)

        // deleteByDictionary
        termDao.deleteByDictionary(dict)
        val emptyMatches = termDao.findByExpression("食べる", listOf(dict))
        assertTrue(emptyMatches.isEmpty())
    }

    @Test
    fun testTermDaoPrefixAndSuffixQueries() = runBlocking {
        val termDao = db.termDao()
        val dict = "Prefix Suffix Dict"

        val terms = listOf(
            TermEntity(
                dictionary = dict,
                expression = "学校",
                reading = "がっこう",
                definitionTags = null,
                rules = "",
                score = 10.0,
                glossary = "[\"school\"]",
                sequence = 1L,
                termTags = null,
                expressionReverse = "校学",
                readingReverse = "うこうっが"
            ),
            TermEntity(
                dictionary = dict,
                expression = "学生",
                reading = "がくせい",
                definitionTags = null,
                rules = "",
                score = 10.0,
                glossary = "[\"student\"]",
                sequence = 2L,
                termTags = null,
                expressionReverse = "生学",
                readingReverse = "いせくが"
            )
        )
        termDao.bulkAdd(terms)

        // findByExpressionPrefix (e.g. prefix "学", prefixEnd "学\uFFFF")
        val exprPrefixMatches = termDao.findByExpressionPrefix("学", "学\uFFFF", listOf(dict))
        assertEquals(2, exprPrefixMatches.size)

        // findByReadingPrefix (e.g. prefix "がっ", prefixEnd "がっ\uFFFF")
        val readingPrefixMatches = termDao.findByReadingPrefix("がっ", "がっ\uFFFF", listOf(dict))
        assertEquals(1, readingPrefixMatches.size)
        assertEquals("学校", readingPrefixMatches[0].expression)

        // findByExpressionSuffix (caller reverses query string, e.g. suffix "生" -> reverse "生")
        val exprSuffixMatches = termDao.findByExpressionSuffix("生", "生\uFFFF", listOf(dict))
        assertEquals(1, exprSuffixMatches.size)
        assertEquals("学生", exprSuffixMatches[0].expression)

        // findByReadingSuffix (e.g. suffix "せい" -> reverse "いせ")
        val readingSuffixMatches = termDao.findByReadingSuffix("いせ", "いせ\uFFFF", listOf(dict))
        assertEquals(1, readingSuffixMatches.size)
        assertEquals("学生", readingSuffixMatches[0].expression)
    }

    @Test
    fun testTermDaoFindExactAndSequence() = runBlocking {
        val termDao = db.termDao()
        val dict = "Exact Dict"

        val terms = listOf(
            TermEntity(
                dictionary = dict,
                expression = "橋",
                reading = "はし",
                definitionTags = null,
                rules = "",
                score = 10.0,
                glossary = "[\"bridge\"]",
                sequence = 100L,
                termTags = null
            ),
            TermEntity(
                dictionary = dict,
                expression = "箸",
                reading = "はし",
                definitionTags = null,
                rules = "",
                score = 10.0,
                glossary = "[\"chopsticks\"]",
                sequence = 101L,
                termTags = null
            )
        )
        termDao.bulkAdd(terms)

        val exactMatch = termDao.findExact("橋", "はし", listOf(dict))
        assertEquals(1, exactMatch.size)
        assertEquals("橋", exactMatch[0].expression)

        val seqMatch = termDao.findBySequence(101L, dict)
        assertEquals(1, seqMatch.size)
        assertEquals("箸", seqMatch[0].expression)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. TermMetaDao & KanjiMetaDao Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testTermMetaDaoBulkQueries() = runBlocking {
        val termMetaDao = db.termMetaDao()
        val dict = "TermMeta Dict"

        val metas = listOf(
            TermMetaEntity(dictionary = dict, expression = "食べる", mode = "freq", data = "100"),
            TermMetaEntity(dictionary = dict, expression = "たべる", mode = "pitch", data = "{\"pitch\":1}"),
            TermMetaEntity(dictionary = dict, expression = "飲む", mode = "freq", data = "200")
        )
        termMetaDao.bulkAdd(metas)

        // findByExpressionBulk
        val exprBulk = termMetaDao.findByExpressionBulk(listOf("食べる", "飲む"), listOf(dict))
        assertEquals(2, exprBulk.size)

        // findByExpressionAndReadingBulk
        val exprReadingBulk = termMetaDao.findByExpressionAndReadingBulk(
            expressions = listOf("食べる"),
            readings = listOf("たべる"),
            dictionaries = listOf(dict)
        )
        assertEquals(2, exprReadingBulk.size)

        // deleteByDictionary
        termMetaDao.deleteByDictionary(dict)
        val emptyBulk = termMetaDao.findByExpressionBulk(listOf("食べる"), listOf(dict))
        assertTrue(emptyBulk.isEmpty())
    }

    @Test
    fun testKanjiDaoAndKanjiMetaDaoQueries() = runBlocking {
        val kanjiDao = db.kanjiDao()
        val kanjiMetaDao = db.kanjiMetaDao()
        val dict = "Kanji Dict"

        kanjiDao.bulkAdd(
            listOf(
                KanjiEntity(
                    dictionary = dict,
                    character = "日",
                    onyomi = "ニチ",
                    kunyomi = "ひ",
                    tags = "JLPT_N5",
                    meanings = "[\"day\", \"sun\"]",
                    stats = null
                ),
                KanjiEntity(
                    dictionary = dict,
                    character = "本",
                    onyomi = "ホン",
                    kunyomi = "もと",
                    tags = "JLPT_N5",
                    meanings = "[\"book\", \"origin\"]",
                    stats = null
                )
            )
        )

        kanjiMetaDao.bulkAdd(
            listOf(
                KanjiMetaEntity(dictionary = dict, character = "日", mode = "freq", data = "1"),
                KanjiMetaEntity(dictionary = dict, character = "本", mode = "freq", data = "2")
            )
        )

        // findByCharacterBulk kanji
        val kanjiMatches = kanjiDao.findByCharacterBulk(listOf("日", "本"), listOf(dict))
        assertEquals(2, kanjiMatches.size)

        // findByCharacterBulk kanjiMeta
        val kanjiMetaMatches = kanjiMetaDao.findByCharacterBulk(listOf("日"), listOf(dict))
        assertEquals(1, kanjiMetaMatches.size)
        assertEquals("1", kanjiMetaMatches[0].data)

        // deleteByDictionary
        kanjiDao.deleteByDictionary(dict)
        kanjiMetaDao.deleteByDictionary(dict)

        assertTrue(kanjiDao.findByCharacterBulk(listOf("日"), listOf(dict)).isEmpty())
        assertTrue(kanjiMetaDao.findByCharacterBulk(listOf("日"), listOf(dict)).isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. TagMetaDao & MediaDao Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testTagMetaDaoQueries() = runBlocking {
        val tagMetaDao = db.tagMetaDao()
        val dict = "Tag Dict"

        val tags = listOf(
            TagMetaEntity(
                dictionary = dict,
                name = "v1",
                category = "partOfSpeech",
                notes = "Ichidan verb",
                order = 1,
                score = 100
            ),
            TagMetaEntity(
                dictionary = dict,
                name = "P",
                category = "popularity",
                notes = "Popular term",
                order = 2,
                score = 200
            )
        )
        tagMetaDao.bulkAdd(tags)

        // findByNameBulk
        val bulkTags = tagMetaDao.findByNameBulk(listOf("v1", "P"), listOf(dict))
        assertEquals(2, bulkTags.size)

        // findTagForTitle
        val singleTag = tagMetaDao.findTagForTitle("v1", dict)
        assertNotNull(singleTag)
        assertEquals("partOfSpeech", singleTag?.category)

        // findAllForDictionary
        val allTags = tagMetaDao.findAllForDictionary(dict)
        assertEquals(2, allTags.size)

        // deleteByDictionary
        tagMetaDao.deleteByDictionary(dict)
        assertNull(tagMetaDao.findTagForTitle("v1", dict))
    }

    @Test
    fun testMediaDaoQueries() = runBlocking {
        val mediaDao = db.mediaDao()
        val dict = "Media Dict"

        val mediaItems = listOf(
            MediaEntity(
                dictionary = dict,
                path = "img/cat.png",
                mediaType = "image/png",
                width = 100,
                height = 100,
                content = byteArrayOf(1, 2, 3)
            ),
            MediaEntity(
                dictionary = dict,
                path = "img/dog.png",
                mediaType = "image/png",
                width = 200,
                height = 200,
                content = byteArrayOf(4, 5, 6)
            )
        )
        mediaDao.bulkAdd(mediaItems)

        // getMediaBulk
        val bulkMedia = mediaDao.getMediaBulk(listOf("img/cat.png", "img/dog.png"), listOf(dict))
        assertEquals(2, bulkMedia.size)

        // getMedia single
        val singleMedia = mediaDao.getMedia("img/cat.png", dict)
        assertNotNull(singleMedia)
        assertEquals("image/png", singleMedia?.mediaType)

        // getAllForDictionary
        val allMedia = mediaDao.getAllForDictionary(dict)
        assertEquals(2, allMedia.size)

        // deleteByDictionary
        mediaDao.deleteByDictionary(dict)
        assertNull(mediaDao.getMedia("img/cat.png", dict))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. DictionaryDeletionDao Cascading Delete Test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testDictionaryDeletionDaoCascadesAllTables() = runBlocking {
        val deletionDao = db.dictionaryDeletionDao()
        val targetDict = "Delete All Tables Dict"
        val keepDict = "Keep All Tables Dict"

        db.dictionaryDao().insertDictionary(
            DictionaryEntity(
                title = targetDict,
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 0L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = null,
                importSuccess = true
            )
        )
        db.dictionaryDao().insertDictionary(
            DictionaryEntity(
                title = keepDict,
                revision = "1.0",
                sequenced = false,
                version = 3,
                importDate = 0L,
                prefixWildcardsSupported = true,
                styles = "",
                counts = null,
                importSuccess = true
            )
        )

        db.termDao().bulkAdd(
            listOf(
                TermEntity(
                    dictionary = targetDict,
                    expression = "A",
                    reading = "a",
                    definitionTags = null,
                    rules = "",
                    score = 0.0,
                    glossary = "[]",
                    sequence = null,
                    termTags = null
                )
            )
        )
        db.termMetaDao()
            .bulkAdd(listOf(TermMetaEntity(dictionary = targetDict, expression = "A", mode = "freq", data = "1")))
        db.kanjiDao().bulkAdd(
            listOf(
                KanjiEntity(
                    dictionary = targetDict,
                    character = "A",
                    onyomi = "",
                    kunyomi = "",
                    tags = "",
                    meanings = "[]",
                    stats = null
                )
            )
        )
        db.kanjiMetaDao()
            .bulkAdd(listOf(KanjiMetaEntity(dictionary = targetDict, character = "A", mode = "freq", data = "1")))
        db.tagMetaDao().bulkAdd(
            listOf(
                TagMetaEntity(
                    dictionary = targetDict,
                    name = "t1",
                    category = "c1",
                    notes = "",
                    order = 1,
                    score = 1
                )
            )
        )
        db.mediaDao().bulkAdd(
            listOf(
                MediaEntity(
                    dictionary = targetDict,
                    path = "p1",
                    mediaType = "image/png",
                    width = 1,
                    height = 1,
                    content = byteArrayOf(1)
                )
            )
        )

        db.termDao().bulkAdd(
            listOf(
                TermEntity(
                    dictionary = keepDict,
                    expression = "B",
                    reading = "b",
                    definitionTags = null,
                    rules = "",
                    score = 0.0,
                    glossary = "[]",
                    sequence = null,
                    termTags = null
                )
            )
        )
        db.termMetaDao()
            .bulkAdd(listOf(TermMetaEntity(dictionary = keepDict, expression = "B", mode = "freq", data = "2")))

        // Execute atomic deletion
        deletionDao.deleteDictionary(targetDict)

        // Verify targetDict removed across all tables
        assertEquals(0, db.dictionaryDao().dictionaryExists(targetDict))
        assertEquals(0L, db.dictionaryDao().countTerms(targetDict))
        assertEquals(0L, db.dictionaryDao().countTermMeta(targetDict))
        assertEquals(0L, db.dictionaryDao().countKanji(targetDict))
        assertEquals(0L, db.dictionaryDao().countKanjiMeta(targetDict))
        assertEquals(0L, db.dictionaryDao().countTagMeta(targetDict))
        assertEquals(0L, db.dictionaryDao().countMedia(targetDict))

        // Verify keepDict untouched
        assertEquals(1, db.dictionaryDao().dictionaryExists(keepDict))
        assertEquals(1L, db.dictionaryDao().countTerms(keepDict))
        assertEquals(1L, db.dictionaryDao().countTermMeta(keepDict))
    }
}
