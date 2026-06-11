package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.DictionaryEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.KanjiEntity
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.TermEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(app)
        Dispatchers.setMain(testDispatcher)

        runBlocking {
            withContext(Dispatchers.IO) {
                db.clearAllTables()
            }
        }
    }

    @Test
    fun testInitialStateAndLoadInstalled() = runBlocking {
        // Prepare dictionary items in database
        val dict1 = DictionaryEntity(
            title = "A Dictionary",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )
        val dict2 = DictionaryEntity(
            title = "B Dictionary",
            revision = "2.0",
            sequenced = true,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict1)
            db.dictionaryDao().insertDictionary(dict2)

            // Insert some mock terms and kanji so counts are loaded
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = "A Dictionary",
                        expression = "単語",
                        reading = "たんご",
                        definitionTags = null,
                        rules = "",
                        score = 1.0,
                        glossary = "[\"word\"]",
                        sequence = null,
                        termTags = null
                    )
                )
            )
            db.kanjiDao().bulkAdd(
                listOf(
                    KanjiEntity(
                        dictionary = "B Dictionary",
                        character = "漢",
                        onyomi = "カン",
                        kunyomi = "",
                        tags = "",
                        meanings = "[\"kanji\"]",
                        stats = null
                    )
                )
            )
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(false, uiState.isLoading)
        assertEquals(2, uiState.installedDictionaries.size)

        // Verify custom formatted stats counts
        val firstDict = uiState.installedDictionaries[0]
        assertEquals("A Dictionary", firstDict.meta.title)
        assertEquals("1 term imported", firstDict.countText)

        val secondDict = uiState.installedDictionaries[1]
        assertEquals("B Dictionary", secondDict.meta.title)
        assertEquals("1 kanji imported", secondDict.countText)

        // Verify shared preferences cache was updated
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("installed_dictionaries", "")
        assertEquals("A Dictionary,B Dictionary", cached)
    }

    @Test
    fun testDeleteDictionary() = runBlocking {
        val dict = DictionaryEntity(
            title = "ToDelete Dict",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict)
            db.termDao().bulkAdd(
                listOf(
                    TermEntity(
                        dictionary = "ToDelete Dict",
                        expression = "単語",
                        reading = "たんご",
                        definitionTags = null,
                        rules = "",
                        score = 1.0,
                        glossary = "[\"word\"]",
                        sequence = null,
                        termTags = null
                    )
                )
            )
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(1, uiState.installedDictionaries.size)

        viewModel.deleteDictionary(dict)

        var uiStateDel = viewModel.uiState.value
        var attemptsDel = 0
        while ((uiStateDel.isLoading || uiStateDel.progressState != null) && attemptsDel < 100) {
            delay(50.milliseconds)
            uiStateDel = viewModel.uiState.value
            attemptsDel++
        }

        // Verify database is cleared and installed dictionaries list is empty
        assertEquals(0, viewModel.uiState.value.installedDictionaries.size)
        withContext(Dispatchers.IO) {
            val terms = db.termDao().findByExpression("単語", listOf("ToDelete Dict"))
            assertEquals(0, terms.size)
        }

        // Verify SharedPreferences is cleared
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("installed_dictionaries", "")
        assertEquals("", cached)
        val activeCached = prefs.getString("active_dictionaries", "")
        assertEquals("", activeCached)
    }

    @Test
    fun testToggleDictionaryActive() = runBlocking {
        val dict = DictionaryEntity(
            title = "Toggle Dict",
            revision = "1.0",
            sequenced = false,
            version = 3,
            importDate = System.currentTimeMillis(),
            prefixWildcardsSupported = true,
            styles = "",
            counts = null,
            importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict)
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        // By default, since it is the only one installed, it is set as active
        assertEquals(true, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))

        // Toggle it off
        viewModel.toggleDictionaryActive("Toggle Dict")
        assertEquals(false, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))

        // Toggle it back on
        viewModel.toggleDictionaryActive("Toggle Dict")
        assertEquals(true, viewModel.uiState.value.activeDictionaries.contains("Toggle Dict"))
    }

    @Test
    fun testClearError() = runBlocking {
        val viewModel = MainViewModel(app)

        assertNull(viewModel.uiState.value.errorDialogState)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorDialogState)
    }

    @Test
    fun testDictionaryReorderingAndAppending() = runBlocking {
        val dict1 = DictionaryEntity(
            title = "Dict A", revision = "1.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )
        val dict2 = DictionaryEntity(
            title = "Dict B", revision = "2.0", sequenced = true, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )

        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict1)
            db.dictionaryDao().insertDictionary(dict2)
        }

        val viewModel = MainViewModel(app)

        var uiState = viewModel.uiState.value
        var attempts = 0
        while (uiState.isLoading && attempts < 100) {
            delay(50.milliseconds)
            uiState = viewModel.uiState.value
            attempts++
        }

        assertEquals(2, uiState.installedDictionaries.size)
        // Default alphabetical order: Dict A, Dict B
        assertEquals("Dict A", uiState.installedDictionaries[0].meta.title)
        assertEquals("Dict B", uiState.installedDictionaries[1].meta.title)

        // 1. Move Dict A (fromIndex 0) to index 1 (swapping with Dict B)
        viewModel.moveDictionary(0, 1)

        val uiStateReordered = viewModel.uiState.value
        assertEquals("Dict B", uiStateReordered.installedDictionaries[0].meta.title)
        assertEquals("Dict A", uiStateReordered.installedDictionaries[1].meta.title)

        // Verify shared preferences cache was updated with the custom order
        val prefs = app.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        assertEquals("Dict B,Dict A", prefs.getString("installed_dictionaries", ""))

        // Both are active by default
        assertEquals(true, uiStateReordered.activeDictionaries.contains("Dict A"))
        assertEquals(true, uiStateReordered.activeDictionaries.contains("Dict B"))

        // Toggle Dict A off so it's not active
        viewModel.toggleDictionaryActive("Dict A")
        assertEquals(false, viewModel.uiState.value.activeDictionaries.contains("Dict A"))

        // 2. Install a new dictionary: Dict C
        val dict3 = DictionaryEntity(
            title = "Dict C", revision = "3.0", sequenced = false, version = 3,
            importDate = System.currentTimeMillis(), prefixWildcardsSupported = true,
            styles = "", counts = null, importSuccess = true
        )
        withContext(Dispatchers.IO) {
            db.dictionaryDao().insertDictionary(dict3)
        }

        // 3. Reload dictionaries
        viewModel.loadInstalledDictionaries()

        var uiStateReloaded = viewModel.uiState.value
        var reloadAttempts = 0
        while (uiStateReloaded.isLoading && reloadAttempts < 100) {
            delay(50.milliseconds)
            uiStateReloaded = viewModel.uiState.value
            reloadAttempts++
        }

        // Verify that Dict C is appended at the bottom, after custom ordered Dict B and Dict A
        assertEquals(3, uiStateReloaded.installedDictionaries.size)
        assertEquals("Dict B", uiStateReloaded.installedDictionaries[0].meta.title)
        assertEquals("Dict A", uiStateReloaded.installedDictionaries[1].meta.title)
        assertEquals("Dict C", uiStateReloaded.installedDictionaries[2].meta.title) // Bottom!

        // Verify that Dict C is active by default, and Dict A remains toggled off (inactive)
        assertEquals(true, uiStateReloaded.activeDictionaries.contains("Dict B"))
        assertEquals(false, uiStateReloaded.activeDictionaries.contains("Dict A"))
        assertEquals(true, uiStateReloaded.activeDictionaries.contains("Dict C")) // Active by default!
    }
}
