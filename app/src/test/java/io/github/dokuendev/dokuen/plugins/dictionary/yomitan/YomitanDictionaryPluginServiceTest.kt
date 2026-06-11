package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import io.github.dokuendev.dokuenreader.dictionary.DictionaryException
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class YomitanDictionaryPluginServiceTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val dictTitle = "Test Dict"

    @Before
    fun setUp() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            db = AppDatabase.getDatabase(app)

            withContext(Dispatchers.IO) {
                db.clearAllTables()

                // Insert mock dictionary summary with all required fields
                db.dictionaryDao().insertDictionary(
                    DictionaryEntity(
                        title = dictTitle,
                        revision = "1.0",
                        sequenced = true,
                        version = 3,
                        importDate = System.currentTimeMillis(),
                        prefixWildcardsSupported = true,
                        styles = "",
                        counts = null,
                        importSuccess = true,
                        author = "Test Author",
                        description = "Test Description",
                        attribution = "Test Attribution"
                    )
                )

                // Insert mock terms (homophones for 湯: ゆ and とう)
                db.termDao().bulkAdd(
                    listOf(
                        TermEntity(
                            dictionary = dictTitle,
                            expression = "湯",
                            reading = "ゆ",
                            definitionTags = "",
                            rules = "",
                            score = 100.0,
                            sequence = 1,
                            termTags = "",
                            glossary = "[\"hot water\"]"
                        ),
                        TermEntity(
                            dictionary = dictTitle,
                            expression = "湯",
                            reading = "とう",
                            definitionTags = "",
                            rules = "",
                            score = 50.0,
                            sequence = 2,
                            termTags = "",
                            glossary = "[\"hot spring / bath\"]"
                        ),
                        TermEntity(
                            dictionary = dictTitle,
                            expression = "水入り",
                            reading = "みずいり",
                            definitionTags = "",
                            rules = "",
                            score = 200.0,
                            sequence = 3,
                            termTags = "",
                            glossary = "[\"water entry\"]"
                        )
                    )
                )
            }

            // Set shared preference for active dictionaries
            val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
            prefs.edit()
                .putString("installed_dictionaries", dictTitle)
                .putString("active_dictionaries", dictTitle)
                .commit()
        }
    }

    @Test
    fun testInternalLinkLookupWithExactReading() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }

            val initResult = service.onInitialize(config)
            assertNotNull(initResult)

            // 1. Look up with primary_reading = ゆ (URL-encoded to %E3%82%86)
            val queryUrl = "?query=%E6%B9%AF&wildcards=off&primary_reading=%E3%82%86"
            val result = service.onLookup(queryUrl, 0, queryUrl.length)

            assertNotNull(result)
            assertEquals(1, result.entries.size)
            assertEquals("湯", result.entries[0].headword)
            assertTrue(result.entries[0].body.text.contains("hot water"))

            // 2. Look up with primary_reading = とう (URL-encoded to %E3%81%A8%E3%81%86)
            val queryUrl2 = "?query=%E6%B9%AF&wildcards=off&primary_reading=%E3%81%A8%E3%81%86"
            val result2 = service.onLookup(queryUrl2, 0, queryUrl2.length)

            assertNotNull(result2)
            assertEquals(1, result2.entries.size)
            assertEquals("湯", result2.entries[0].headword)
            assertTrue(result2.entries[0].body.text.contains("hot spring / bath"))
        }
    }

    @Test
    fun testInternalLinkLookupWithWildcardPrefix() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }

            service.onInitialize(config)

            // Prefix wildcard search for "水*" (URL-encoded to %E6%B0%B4*)
            val queryUrl = "?query=%E6%B0%B4*&wildcards=on"
            val result = service.onLookup(queryUrl, 0, queryUrl.length)

            assertNotNull(result)
            assertEquals(1, result.entries.size)
            assertEquals("水入り", result.entries[0].headword)
            assertTrue(result.entries[0].body.text.contains("water entry"))
        }
    }

    @Test
    fun testLookupNoActiveDictionary() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            // Clear active dictionaries
            val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
            prefs.edit().remove("active_dictionaries").commit()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
            }
            service.onInitialize(config)

            try {
                service.onLookup("湯", 0, 1)
                fail("Expected DictionaryException to be thrown")
            } catch (e: DictionaryException) {
                assertTrue(e.message!!.contains("No dictionaries have been installed or enabled yet."))
            }
        }
    }

    @Test
    fun testLookupNoResults() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            try {
                // "猫" is not in our mock database
                service.onLookup("猫", 0, 1)
                fail("Expected DictionaryException to be thrown")
            } catch (e: DictionaryException) {
                assertTrue(e.message!!.contains("No definitions found"))
            }
        }
    }

    @Test
    fun testDarkModeInitialization() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "dark")
                putString("active_dictionary", dictTitle)
            }
            val initResult = service.onInitialize(config)
            assertNotNull(initResult)
            for (field in initResult::class.java.declaredFields) {
                println("INITRESULT FIELD: " + field.name + " of type " + field.type.name)
            }
            for (method in initResult::class.java.declaredMethods) {
                println("INITRESULT METHOD: " + method.name + " returning " + method.returnType.name)
            }
        }
    }

    @Test
    fun testLookupKanjiEntries() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // Insert a mock KanjiEntity
            withContext(Dispatchers.IO) {
                db.kanjiDao().bulkAdd(
                    listOf(
                        KanjiEntity(
                            dictionary = dictTitle,
                            character = "湯",
                            onyomi = "トウ",
                            kunyomi = "ゆ",
                            tags = "grade3",
                            meanings = "[\"hot water\"]",
                            stats = "{\"stroke_count\":\"12\"}"
                        )
                    )
                )
            }

            // Look up "湯" where no term exists for that exact character (Wait, in setUp() "湯" term exists. Let's look up a different character, e.g. "木")
            withContext(Dispatchers.IO) {
                db.kanjiDao().bulkAdd(
                    listOf(
                        KanjiEntity(
                            dictionary = dictTitle,
                            character = "木",
                            onyomi = "モク",
                            kunyomi = "き",
                            tags = "",
                            meanings = "[\"tree\"]",
                            stats = null
                        )
                    )
                )
            }

            // Look up "木", which only exists in the kanji table, not the terms table
            val result = service.onLookup("木", 0, 1)
            assertNotNull(result)
            assertEquals(1, result.entries.size)
            assertEquals("木", result.entries[0].headword)
            assertTrue(result.entries[0].body.text.contains("tree"))
        }
    }

    @Test
    fun testLookupScanAndDeinflect() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // Look up "水入り" via scan (non-link query)
            val result = service.onLookup("水入りです", 0, 3)
            assertNotNull(result)
            assertTrue(result.entries.isNotEmpty())
            val matched = result.entries.find { it.headword == "水入り" }
            assertNotNull("Should find scan match for 水入り", matched)
            assertTrue(matched!!.body.text.contains("water entry"))
        }
    }

    @Test
    fun testLookupSortingAndPrioritization() = runBlocking {
        val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

        val dict1 = "Priority Dict"
        val dict2 = "Secondary Dict"

        val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
        prefs.edit()
            .putString("installed_dictionaries", "$dict1,$dict2")
            .putString("active_dictionaries", "$dict1,$dict2")
            .commit()

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(
                DictionaryEntity(
                    title = dict1, revision = "1.0", sequenced = true, version = 3,
                    importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
                    styles = "", counts = null, importSuccess = true, author = "Auth",
                    description = "Desc", attribution = "Attr"
                )
            )
            db.dictionaryDao().insertDictionary(
                DictionaryEntity(
                    title = dict2, revision = "1.0", sequenced = true, version = 3,
                    importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
                    styles = "", counts = null, importSuccess = true, author = "Auth",
                    description = "Desc", attribution = "Attr"
                )
            )

            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = dict2, expression = "食べさせられた", reading = "たべさせられた",
                        definitionTags = "", rules = "", score = 10.0, sequence = 1, termTags = "",
                        glossary = "[\"to be made to eat (long match)\"]"
                    ),
                    TermEntity(
                        dictionary = dict1, expression = "食べる", reading = "たべる",
                        definitionTags = "", rules = "", score = 100.0, sequence = 2, termTags = "",
                        glossary = "[\"to eat (dict1 high score)\"]"
                    ),
                    TermEntity(
                        dictionary = dict1, expression = "食べる", reading = "たべる",
                        definitionTags = "", rules = "", score = 20.0, sequence = 3, termTags = "",
                        glossary = "[\"to eat (dict1 low score)\"]"
                    ),
                    TermEntity(
                        dictionary = dict2, expression = "食べる", reading = "たべる",
                        definitionTags = "", rules = "", score = 50.0, sequence = 4, termTags = "",
                        glossary = "[\"to eat (dict2 match)\"]"
                    )
                )
            )
        }

        val config = Bundle().apply {
            putString(PluginHostConfigKeys.UI_THEME, "light")
        }
        service.onInitialize(config)

        val result = service.onLookup("食べさせられたです", 0, 7)
        assertNotNull(result)
        assertEquals(2, result.entries.size)

        assertEquals("食べさせられた", result.entries[0].headword)
        assertTrue(result.entries[0].body.text.contains("long match"))

        assertEquals("食べる", result.entries[1].headword)
        assertTrue(result.entries[1].body.text.contains("dict1 high score"))
        assertTrue(result.entries[1].body.text.contains("dict1 low score"))
        assertTrue(result.entries[1].body.text.contains("dict2 match"))
    }

    @Test
    fun testLookupCursorEndIndexSelection() = runBlocking {
        val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

        val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
        prefs.edit()
            .putString("installed_dictionaries", dictTitle)
            .putString("active_dictionaries", dictTitle)
            .commit()

        withContext(Dispatchers.IO) {
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = dictTitle, expression = "短い", reading = "みじかい",
                        definitionTags = "", rules = "", score = 100.0, sequence = 10, termTags = "",
                        glossary = "[\"short\"]"
                    ),
                    TermEntity(
                        dictionary = dictTitle, expression = "短い言葉", reading = "みじかいことば",
                        definitionTags = "", rules = "", score = 50.0, sequence = 11, termTags = "",
                        glossary = "[\"short word\"]"
                    )
                )
            )
        }

        val config = Bundle().apply {
            putString(PluginHostConfigKeys.UI_THEME, "light")
        }
        service.onInitialize(config)

        // Select the longer word "短い言葉" (length 4).
        // Since cursorEndIndex is 4, minLen should be 4.
        // It scans lengths down to 4.
        // The word "短い" (length 2) should NOT be in the results.
        val result1 = service.onLookup("短い言葉です", 0, 4)
        assertNotNull(result1)

        val foundShortWord = result1.entries.any { it.headword == "短い" }
        assertTrue("Shorter word '短い' should not be found when cursor selection length is 4", !foundShortWord)

        val foundLongWord = result1.entries.any { it.headword == "短い言葉" }
        assertTrue("Longer word '短い言葉' should be found", foundLongWord)
    }

    @Test
    fun testKanjiLinkLookupRoutesToKanjiDictionary() {
        // When the user taps a kanji character in a term headword, TermEntryRenderer
        // produces a linkUrl of "lookup:?kanji=<char>". The host strips the "lookup:"
        // prefix and calls onLookup("?kanji=木", 0, len). This must route through
        // lookupInternalLink -> lookupKanjiDirect - NOT through scanAndDeinflectLookup
        // or the term path - even if a term entry for that character also exists.
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // Insert a kanji entry for 湯 (a term entry for 湯 already exists in setUp())
            withContext(Dispatchers.IO) {
                db.kanjiDao().bulkAdd(
                    listOf(
                        KanjiEntity(
                            dictionary = dictTitle,
                            character = "湯",
                            onyomi = "トウ",
                            kunyomi = "ゆ",
                            tags = "",
                            meanings = "[\"hot water (kanji entry)\"]",
                            stats = null
                        )
                    )
                )
            }

            // The contextText arriving at onLookup is exactly what was after "lookup:"
            // in the linkUrl produced by appendKanjiLinkHeadword.
            val contextText = "?kanji=湯"
            val result = service.onLookup(contextText, 0, contextText.length)

            assertNotNull(result)
            assertEquals(1, result.entries.size)
            assertEquals("湯", result.entries[0].headword)
            // Must come from the kanji entry, not any term entry
            assertTrue(
                "Result should be from the kanji entry",
                result.entries[0].body.text.contains("hot water (kanji entry)")
            )
        }
    }

    @Test
    fun testKanjiLinkLookupReturnsNotFoundWhenKanjiDictInstalledButCharacterMissing() {
        // A kanji dictionary IS installed (countKanji > 0) but has no entry for 湯.
        // This is the normal "character not in dictionary" case, which should produce
        // a WORD_NOT_FOUND exception — the same as any other failed lookup.
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // Insert a kanji entry for a different character (日) so that countKanji > 0,
            // making the active dictionary recognisable as a kanji dictionary.
            // 湯 intentionally has no kanji entry.
            withContext(Dispatchers.IO) {
                db.kanjiDao().bulkAdd(
                    listOf(
                        KanjiEntity(
                            dictionary = dictTitle,
                            character = "日",
                            onyomi = "ニチ ジツ",
                            kunyomi = "ひ",
                            tags = "",
                            meanings = """[" sun ", " day "]""",
                            stats = null
                        )
                    )
                )
            }

            val contextText = "?kanji=湯"
            try {
                service.onLookup(contextText, 0, contextText.length)
                fail("Expected DictionaryException for character missing from kanji dictionary")
            } catch (e: DictionaryException) {
                assertTrue(e.message!!.contains("No definitions found"))
            }
        }
    }

    @Test
    fun testKanjiLinkLookupReturnsInformationalEntryWhenNoKanjiDictInstalled() {
        // No kanji dictionary is installed at all (countKanji == 0 for all active dicts).
        // The user tapped a ?kanji= link that we produced, so instead of a WORD_NOT_FOUND
        // error we return a success result with a message explaining what to do.
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // setUp() inserts only term entries, no kanji entries — so countKanji == 0.
            val contextText = "?kanji=湯"
            val result = service.onLookup(contextText, 0, contextText.length)

            assertNotNull(result)
            assertEquals("Should return exactly one informational entry", 1, result.entries.size)

            val entry = result.entries[0]
            assertEquals("湯", entry.headword)
            assertTrue(
                "Body should explain that no kanji dictionary is installed",
                entry.body.text.contains("No kanji dictionary")
            )
            assertTrue(
                "Body should instruct user to install a kanji dictionary",
                entry.body.text.contains("install")
            )
        }
    }

    @Test
    fun testInternalLinkPreservesAllQueryParameters() {
        // Regression test: before the fix, convertHrefToLinkUrl stripped all parameters
        // except "query", so primary_reading and wildcards were silently discarded.
        // Now the full ?-href is passed verbatim to lookupInternalLink, which parses
        // all parameters via parseQueryParams.
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            // The href "?query=%E6%B9%AF&wildcards=off&primary_reading=%E3%82%86"
            // decodes to: query=湯, wildcards=off, primary_reading=ゆ
            // primary_reading=ゆ should select only the "hot water" sense, not "hot spring"
            val contextText = "?query=%E6%B9%AF&wildcards=off&primary_reading=%E3%82%86"
            val result = service.onLookup(contextText, 0, contextText.length)

            assertNotNull(result)
            assertEquals(1, result.entries.size)
            assertEquals("湯", result.entries[0].headword)
            assertTrue(
                "primary_reading=ゆ should select the 'hot water' sense",
                result.entries[0].body.text.contains("hot water")
            )
        }
    }

    @Test
    fun testDeinflectionMultiRules() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            withContext(Dispatchers.IO) {
                db.termDao().bulkAdd(
                    listOf(
                        TermEntity(
                            dictionary = dictTitle,
                            expression = "言う",
                            reading = "いう",
                            definitionTags = "",
                            rules = "v5u",
                            score = 100.0,
                            sequence = 11,
                            termTags = "",
                            glossary = "[\"to say\"]"
                        )
                    )
                )
            }

            // Look up "言われました"
            val result = service.onLookup("言われました", 0, 5)
            assertNotNull(result)
            assertTrue(result.entries.isNotEmpty())
            val matched = result.entries.find { it.headword == "言う" }
            assertNotNull("Should find deinflected match for 言う", matched)

            val bodyText = matched!!.body.text
            println("--- Multi-rule Styled Spans Info ---")
            println("Body Text: '$bodyText'")
            matched.body.styledSpans?.forEachIndexed { idx, span ->
                val substring = if (span.startIndex in 0..bodyText.length && span.endIndex in 0..bodyText.length) {
                    bodyText.substring(span.startIndex, span.endIndex)
                } else {
                    "OUT OF BOUNDS (${span.startIndex} to ${span.endIndex})"
                }
                println("Span #$idx: range=[${span.startIndex}, ${span.endIndex}), text='$substring', hoverText='${span.style.hoverText}'")
            }
            println("-------------------------")

            val styledSpans = matched.body.styledSpans
            assertNotNull("styledSpans should not be null", styledSpans)

            val taSpan = styledSpans!!.find { it.startIndex == 15 && it.endIndex == 19 }
            assertNotNull("Should find a styled span for '-た'", taSpan)
            assertEquals(
                "1. Indicates a reality that has happened in the past.\n" +
                        "2. Indicates the completion of an action.\n" +
                        "3. Indicates the confirmation of a matter.\n" +
                        "4. Indicates the speaker's confidence that the action will definitely be fulfilled.\n" +
                        "5. Indicates the events that occur before the main clause are represented as relative past.\n" +
                        "6. Indicates a mild imperative/command.\n" +
                        "Usage: Attach た to the continuative form (連用形) of verbs after euphonic change form, かった to the stem of i-adjectives.",
                taSpan!!.style.hoverText
            )

            val masuSpan = styledSpans.find { it.startIndex == 19 && it.endIndex == 25 }
            assertNotNull("Should find a styled span for '-ます'", masuSpan)
            assertEquals(
                "Polite conjugation of verbs and adjectives.\n" +
                        "Usage: Attach ます to the continuative form (連用形) of verbs.",
                masuSpan!!.style.hoverText
            )

            val reruSpan = styledSpans.find { it.startIndex == 25 && it.endIndex == 30 }
            assertNotNull("Should find a styled span for '-れる'", reruSpan)
            assertEquals(
                "1. Indicates an action received from an action performer.\n" +
                        "2. Expresses respect for the subject of action performer.\n" +
                        "Usage: Attach れる to the irrealis form (未然形) of godan verbs.",
                reruSpan!!.style.hoverText
            )
        }
    }

    @Test
    fun testDeinflectionPreservesAndShiftsBlockSpans() {
        runBlocking {
            val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()

            val config = Bundle().apply {
                putString(PluginHostConfigKeys.UI_THEME, "light")
                putString("active_dictionary", dictTitle)
            }
            service.onInitialize(config)

            withContext(Dispatchers.IO) {
                db.termDao().bulkAdd(
                    listOf(
                        TermEntity(
                            dictionary = dictTitle,
                            expression = "食べる",
                            reading = "たべる",
                            definitionTags = "",
                            rules = "v1",
                            score = 100.0,
                            sequence = 10,
                            termTags = "",
                            glossary = "[\"to eat\"]"
                        )
                    )
                )
            }

            // Look up "食べた" (past tense of "食べる")
            val result = service.onLookup("食べたです", 0, 3)
            assertNotNull(result)
            assertTrue(result.entries.isNotEmpty())
            val matched = result.entries.find { it.headword == "食べる" }
            assertNotNull("Should find deinflected match for 食べる", matched)

            val bodyText = matched!!.body.text
            assertTrue(bodyText.contains("to eat"))

            val badgeText = service.getString(R.string.deinflected_badge)
            val expectedBadge = "$badgeText -た\n"
            val shift = expectedBadge.length
            assertTrue(bodyText.startsWith(expectedBadge))

            val blockSpans = matched.body.blockSpans
            assertNotNull("blockSpans should not be null", blockSpans)
            assertTrue("blockSpans should not be empty", blockSpans!!.isNotEmpty())

            val origGlossaryIndex = bodyText.indexOf("to eat")
            val matchingSpan = blockSpans.find { span ->
                span.startIndex >= shift
            }
            assertNotNull("Should find a shifted block span", matchingSpan)
            assertEquals(
                "Shifted block span startIndex should be correct",
                origGlossaryIndex,
                matchingSpan!!.startIndex
            )

            // Verify styled spans contain the deinflection rule parts and their hover text
            val styledSpans = matched.body.styledSpans
            assertNotNull("styledSpans should not be null", styledSpans)
            assertTrue("styledSpans should not be empty", styledSpans!!.isNotEmpty())

            println("--- Styled Spans Info ---")
            println("Body Text: '$bodyText'")
            styledSpans!!.forEachIndexed { idx, span ->
                val substring = if (span.startIndex in 0..bodyText.length && span.endIndex in 0..bodyText.length) {
                    bodyText.substring(span.startIndex, span.endIndex)
                } else {
                    "OUT OF BOUNDS (${span.startIndex} to ${span.endIndex})"
                }
                println("Span #$idx: range=[${span.startIndex}, ${span.endIndex}), text='$substring', hoverText='${span.style.hoverText}'")
            }
            println("-------------------------")

            val ruleStart = badgeText.length
            val ruleEnd = ruleStart + 1 + "-た".length
            val ruleSpan = styledSpans.find { span ->
                span.startIndex == ruleStart && span.endIndex == ruleEnd
            }
            assertNotNull("Should find a styled span for the deinflection rule part", ruleSpan)

            val expectedHoverText = "1. Indicates a reality that has happened in the past.\n" +
                    "2. Indicates the completion of an action.\n" +
                    "3. Indicates the confirmation of a matter.\n" +
                    "4. Indicates the speaker's confidence that the action will definitely be fulfilled.\n" +
                    "5. Indicates the events that occur before the main clause are represented as relative past.\n" +
                    "6. Indicates a mild imperative/command.\n" +
                    "Usage: Attach た to the continuative form (連用形) of verbs after euphonic change form, かった to the stem of i-adjectives."

            assertEquals("Hover text should match rule description", expectedHoverText, ruleSpan!!.style.hoverText)
        }
    }
}

