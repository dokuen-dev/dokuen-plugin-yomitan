package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Runs the Anki export against the AnkiDroid installed on the device, to check what the JVM
 * tests can only assume: that AnkiDroid accepts the SQL AnkiDroidClient.findDuplicateNotes
 * sends, that AnkiFieldChecksum matches the checksums AnkiDroid stores, and that
 * notes, including glossary HTML with its `<style>` element, round-trip through AnkiDroid's
 * provider unchanged.
 *
 * This writes to the device's real AnkiDroid collection. It creates the decks
 * "DokuenPluginTest" and "DokuenPluginTest::Sub" if they are missing and keeps them (AnkiDroid's
 * API cannot delete decks), and adds notes tagged "dokuen-plugin-test" to them, which it deletes
 * afterwards. Run it only against a collection that may be changed this way. It is skipped when
 * AnkiDroid is not installed or has no "Basic" note type.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidDeviceTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(AnkiDroidClient.PERMISSION)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var api: AddContentApi
    private lateinit var client: AnkiDroidClient

    /** Makes this run's note keys unique, so notes left behind by an earlier run never match. */
    private val run = UUID.randomUUID().toString().substring(0, 8)
    private val addedNotes = mutableListOf<Long>()
    private var rootDeck = 0L
    private var subDeck = 0L
    private var basicModel = 0L

    @Before
    fun setUp() {
        client = AnkiDroidClient(context)
        assumeTrue("AnkiDroid is not installed", client.isAnkiDroidInstalled())
        api = AddContentApi(context)
        val basic = client.getModelList().entries.firstOrNull { it.value == "Basic" }
        assumeTrue("AnkiDroid has no Basic note type", basic != null)
        basicModel = basic!!.key
        rootDeck = deck(ROOT_DECK)
        subDeck = deck("$ROOT_DECK::Sub")
        // A note in each test deck that no search below looks for, so a search that matches more
        // than it should fails instead of passing because the deck happens to be empty.
        add(rootDeck, "decoy-$run")
        add(subDeck, "decoy-$run")
    }

    @After
    fun tearDown() {
        for (id in addedNotes) {
            try {
                context.contentResolver.delete(
                    Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, id.toString()),
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete test note $id; find it in AnkiDroid with tag:$NOTE_TAG", e)
            }
        }
        // Leaving notes behind in someone's collection counts as a failure.
        val remaining = mutableListOf<Long>()
        context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI,
            arrayOf(FlashCardsContract.Note._ID),
            "tag:$NOTE_TAG",
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getLong(0).takeIf { it in addedNotes }?.let { remaining.add(it) }
        }
        assertEquals("Test notes left in AnkiDroid (tag:$NOTE_TAG)", emptyList<Long>(), remaining)
    }

    private fun deck(name: String): Long =
        client.getDeckList().entries.firstOrNull { it.value == name }?.key ?: api.addNewDeck(name)!!

    private fun add(deckId: Long, first: String, second: String = "", modelId: Long = basicModel): Long =
        client.addNote(modelId, deckId, listOf(first, second), setOf(NOTE_TAG))!!.also { addedNotes.add(it) }

    private fun find(
        key: String,
        scope: AnkiDuplicateScope,
        deckId: Long,
        modelId: Long = basicModel,
        checkAllModels: Boolean = false
    ): List<AnkiNoteInfo> =
        client.findDuplicateNotes(modelId, deckId, key, scope, checkAllModels)

    private fun findIds(key: String, scope: AnkiDuplicateScope, deckId: Long): List<Long> =
        find(key, scope, deckId).map { it.id }

    @Test
    fun deckAndModelListsIncludeTheTestDecks() {
        val decks = client.getDeckList()
        assertEquals(ROOT_DECK, decks[rootDeck])
        assertEquals("$ROOT_DECK::Sub", decks[subDeck])
        assertEquals(listOf("Front", "Back"), client.getFieldList(basicModel))
    }

    @Test
    fun collectionScopeFindsTheNoteInAnyDeck() {
        val id = add(subDeck, "collection-$run")
        assertEquals(listOf(id), findIds("collection-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(emptyList<Long>(), findIds("collection-$run-other", AnkiDuplicateScope.COLLECTION, rootDeck))
    }

    @Test
    fun deckScopeIsTheExactDeckWithoutSubDecks() {
        val id = add(subDeck, "deck-$run")
        assertEquals(emptyList<Long>(), findIds("deck-$run", AnkiDuplicateScope.DECK, rootDeck))
        assertEquals(listOf(id), findIds("deck-$run", AnkiDuplicateScope.DECK, subDeck))
    }

    @Test
    fun deckRootScopeIsTheWholeTopLevelTree() {
        val inRoot = add(rootDeck, "root-$run")
        val inSub = add(subDeck, "sub-$run")
        val elsewhere = add(AddContentApi.DEFAULT_DECK_ID, "elsewhere-$run")

        assertEquals(listOf(inRoot), findIds("root-$run", AnkiDuplicateScope.DECK_ROOT, subDeck))
        assertEquals(listOf(inSub), findIds("sub-$run", AnkiDuplicateScope.DECK_ROOT, rootDeck))
        assertEquals(emptyList<Long>(), findIds("elsewhere-$run", AnkiDuplicateScope.DECK_ROOT, subDeck))
        assertEquals(listOf(elsewhere), findIds("elsewhere-$run", AnkiDuplicateScope.COLLECTION, subDeck))
    }

    @Test
    fun noteTypeIsMatchedUnlessCheckingAllModels() {
        val otherModel = client.getModelList().keys.firstOrNull { it != basicModel }
        assumeTrue("AnkiDroid has only one note type", otherModel != null)
        val id = add(rootDeck, "model-$run")

        assertEquals(
            emptyList<AnkiNoteInfo>(),
            find("model-$run", AnkiDuplicateScope.COLLECTION, rootDeck, otherModel!!)
        )
        val found = find("model-$run", AnkiDuplicateScope.COLLECTION, rootDeck, otherModel, checkAllModels = true)
        assertEquals(listOf(id), found.map { it.id })
        assertEquals(basicModel, found.single().modelId)
    }

    @Test
    fun searchWildcardsInTheKeyMatchLiterally() {
        val star = add(rootDeck, "a*b-$run")
        add(rootDeck, "aXYb-$run")
        val underscore = add(rootDeck, "p_q-$run")
        add(rootDeck, "pzq-$run")
        val backslash = add(rootDeck, "c\\d-$run")

        assertEquals(listOf(star), findIds("a*b-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(listOf(underscore), findIds("p_q-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(listOf(backslash), findIds("c\\d-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
    }

    @Test
    fun aKeyWithQuotesMatchesOnlyTheSameText() {
        val quoted = add(rootDeck, "say \"hi\" $run")
        add(rootDeck, "say hi $run")

        assertEquals(listOf(quoted), findIds("say \"hi\" $run", AnkiDuplicateScope.COLLECTION, rootDeck))
    }

    @Test
    fun theChecksumMatchesAnkiDroidsForMarkupAndEntities() {
        // Anki's checksum is of the first field with its markup stripped, so each of these is
        // found by its plain text.
        val bold = add(rootDeck, "<b>bold</b>-$run")
        val entity = add(rootDeck, "a &amp; b&nbsp;c-$run")
        val image = add(rootDeck, "<img src=\"pic-$run.jpg\">")

        assertEquals(listOf(bold), findIds("bold-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(listOf(entity), findIds("a & b c-$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(listOf(image), findIds(" pic-$run.jpg ", AnkiDuplicateScope.COLLECTION, rootDeck))
    }

    @Test
    fun deckRootOfADeckThatNoLongerExistsFindsNothing() {
        add(rootDeck, "gone-$run")
        val missingDeck = (client.getDeckList().keys.maxOrNull() ?: 0L) + 1

        assertEquals(emptyList<Long>(), findIds("gone-$run", AnkiDuplicateScope.DECK_ROOT, missingDeck))
    }

    @Test
    fun japaneseAndHtmlKeysMatch() {
        val plain = add(rootDeck, "食べる$run")
        val html = add(rootDeck, "<ruby>食<rt>た</rt></ruby>べる$run")

        assertEquals(listOf(plain), findIds("食べる$run", AnkiDuplicateScope.COLLECTION, rootDeck))
        assertEquals(
            listOf(html),
            findIds("<ruby>食<rt>た</rt></ruby>べる$run", AnkiDuplicateScope.COLLECTION, rootDeck)
        )
    }

    @Test
    fun fieldsAndTagsRoundTripAndUpdate() {
        val id = add(rootDeck, "roundtrip-$run", "<b>x</b>\n<style>.a { color: red; }</style>")

        val note = find("roundtrip-$run", AnkiDuplicateScope.DECK, rootDeck).single()
        assertEquals(listOf("roundtrip-$run", "<b>x</b>\n<style>.a { color: red; }</style>"), note.fields)
        assertTrue(note.tags.toString(), NOTE_TAG in note.tags)
        // The tag search tearDown relies on to confirm cleanup finds the note.
        val tagged = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note._ID), "tag:$NOTE_TAG", null, null
        )!!.use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getLong(0) else null }.toList() }
        assertTrue(tagged.toString(), id in tagged)

        assertTrue(client.updateNoteFields(id, listOf("roundtrip-$run", "updated")))
        assertEquals(listOf("roundtrip-$run", "updated"), api.getNote(id).fields.toList())
    }

    @Test
    fun jitendexGlossaryIsStoredWithItsScopedStyles() = runBlocking {
        // Reads the plugin's own installed dictionaries; skipped if none has styles and 食べる.
        val db = AppDatabase.getDatabase(context)
        val dictionary = db.dictionaryDao().getAllDictionaries().firstOrNull { it.styles.isNotBlank() }
        assumeTrue("No installed dictionary has a styles.css", dictionary != null)
        val terms = db.termDao().findByExpression("食べる", listOf(dictionary!!.title))
        assumeTrue("${dictionary.title} has no entry for 食べる", terms.isNotEmpty())
        val data = AnkiNoteDataBuilder.buildTermNoteData(
            terms = terms,
            tagMetaList = db.tagMetaDao().findAllForDictionary(dictionary.title),
            termMetaList = emptyList(),
            cloze = null,
            dictionaryOrder = listOf(dictionary.title),
            dictionaryStyles = mapOf(dictionary.title to dictionary.styles)
        )
        val glossary = AnkiTemplateEngine.renderField("{glossary}", data).text
        val scope = ".yomitan-glossary [data-dictionary=\"${dictionary.title.replace("\"", "\\\"")}\"] "
        assertTrue(glossary.take(300), glossary.contains("</li><style>$scope"))

        val id = add(rootDeck, "jitendex-$run", glossary)

        assertEquals(glossary, api.getNote(id).fields[1])
    }

    @Test
    fun installedKanjiDictionaryNoteHasItsReadingsAndStrokeCount() = runBlocking {
        // Reads the plugin's own installed dictionaries; skipped if no kanji dictionary has 食.
        val db = AppDatabase.getDatabase(context)
        val dictionary =
            db.dictionaryDao().getAllDictionaries().firstOrNull { db.dictionaryDao().countKanji(it.title) > 0 }
        assumeTrue("No kanji dictionary is installed", dictionary != null)
        val kanji = db.kanjiDao().findByCharacterBulk(listOf("食"), listOf(dictionary!!.title)).firstOrNull()
        assumeTrue("${dictionary.title} has no entry for 食", kanji != null)
        val data = AnkiNoteDataBuilder.buildKanjiNoteData(
            kanji = kanji!!,
            tagMetaList = db.tagMetaDao().findAllForDictionary(dictionary.title),
            kanjiMetaList = db.kanjiMetaDao().findByCharacterBulk(listOf("食"), listOf(dictionary.title)),
            dictionaryOrder = listOf(dictionary.title),
            searchQuery = "食"
        )

        fun render(template: String) = AnkiTemplateEngine.renderField(template, data).text

        assertEquals("食", render("{character}"))
        assertEquals("食", render("{search-query}"))
        // KANJIDIC-style dictionaries keep the stroke count in the "strokes" stat, tagged misc.
        assertEquals("Stroke count: 9", render("{stroke-count}"))
        assertTrue(render("{onyomi}"), "ショク" in render("{onyomi}"))
        assertTrue(render("{onyomi-hiragana}"), "しょく" in render("{onyomi-hiragana}"))
        assertTrue(render("{kunyomi}"), "た.べる" in render("{kunyomi}"))
        assertTrue(render("{glossary}"), "eat" in render("{glossary}"))
        assertEquals(dictionary.title, render("{dictionary}"))
    }

    @Test
    fun addImageStoresAMediaFileANoteCanReference() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val stored = client.addImage(
            AnkiMediaFileNames.generate("dokuen_plugin_test", ".png", System.currentTimeMillis())
        ) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

        assertNotNull(stored)
        assertTrue(stored!!.startsWith("dokuen_plugin_test_"))
        assertTrue(stored.endsWith(".png"))
    }

    private companion object {
        const val TAG = "AnkiDroidDeviceTest"
        const val ROOT_DECK = "DokuenPluginTest"
        const val NOTE_TAG = "dokuen-plugin-test"
    }
}
