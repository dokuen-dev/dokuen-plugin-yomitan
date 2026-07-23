package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.AndroidMediaLoader
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.DictionaryImporter
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.importer.ImportDetails
import io.github.dokuendev.dokuenreader.plugin.core.PluginHostConfigKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Reproduces the frequency-dictionary feature end-to-end against the actual dictionaries
 * this app offers for download (the URLs in [MainViewModel]'s presets), imported and
 * looked up through the real production pipeline (DictionaryImporter -> Room ->
 * onLookup -> YomitanRenderer) - no fabricated data, no mocks standing in for the DB or
 * renderer.
 */
@RunWith(RobolectricTestRunner::class)
class FreqE2ETest {

    /**
     * Downloads [url] and caches the bytes under the JVM temp directory so repeated test
     * runs on the same machine don't re-download; the download itself always goes through
     * the real network the first time, from the real URL baked into the app.
     */
    private fun fetchReal(url: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val cacheKey = digest.joinToString("") { "%02x".format(it) }.take(16)
        val cacheFile = File(System.getProperty("java.io.tmpdir"), "dokuen_dict_cache_$cacheKey.zip")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.readBytes()
        }

        var connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        var redirects = 0
        while (connection.responseCode in 300..399 && redirects < 5) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            connection = (URL(location).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            redirects++
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        cacheFile.writeBytes(bytes)
        return bytes
    }

    @Test
    fun `jpdb plus jitendex badges`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val importer = DictionaryImporter(app, db, AndroidMediaLoader())

        val jitendexBytes = fetchReal(MainViewModel.PRESETS_JITENDEX)
        val jitendexResult = importer.importDictionary(ByteArrayInputStream(jitendexBytes), ImportDetails())
        assertNotNull("Jitendex import should succeed", jitendexResult.summary)
        assertTrue("Jitendex import should report success", jitendexResult.summary!!.importSuccess)
        assertTrue("Jitendex import should report no errors: ${jitendexResult.errors}", jitendexResult.errors.isEmpty())
        val jitendexTitle = jitendexResult.summary.title

        val jpdbBytes = fetchReal(MainViewModel.PRESETS_JPDB)
        val jpdbResult = importer.importDictionary(ByteArrayInputStream(jpdbBytes), ImportDetails())
        assertNotNull("JPDB import should succeed", jpdbResult.summary)
        assertTrue("JPDB import should report success", jpdbResult.summary!!.importSuccess)
        assertTrue("JPDB import should report no errors: ${jpdbResult.errors}", jpdbResult.errors.isEmpty())
        val jpdbTitle = jpdbResult.summary.title

        val prefs = app.getSharedPreferences("yomitan_prefs", Application.MODE_PRIVATE)
        prefs.edit()
            .putString("installed_dictionaries", "$jitendexTitle,$jpdbTitle")
            .putString("active_dictionaries", "$jitendexTitle,$jpdbTitle")
            .commit()

        val service = Robolectric.buildService(YomitanDictionaryPluginService::class.java).create().get()
        val config = Bundle().apply {
            putString(PluginHostConfigKeys.UI_THEME, "light")
        }
        service.onInitialize(config)

        // "犬" (dog) is a common, high-frequency word; "行方" (whereabouts) is real but far
        // rarer. A correct frequency-sorted lookup should rank 犬 as more frequent than 行方.
        val commonResult = service.onLookup("犬", 0, 1)
        val rareResult = service.onLookup("行方", 0, 2)

        println("=== 犬 lookup body ===\n${commonResult.entries.first().body.text}\n")
        println("=== 行方 lookup body ===\n${rareResult.entries.first().body.text}\n")

        assertTrue(
            "Common word 犬 should show a JPDB frequency badge",
            commonResult.entries.first().body.text.contains(jpdbTitle)
        )
        assertTrue(
            "Rarer word 行方 should show a JPDB frequency badge",
            rareResult.entries.first().body.text.contains(jpdbTitle)
        )
    }

    @Test
    fun `bccwj recognized as freq dict`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDatabase.getDatabase(app)

        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        val importer = DictionaryImporter(app, db, AndroidMediaLoader())

        val bccwjBytes = fetchReal(MainViewModel.PRESETS_BCCWJ)
        val bccwjResult = importer.importDictionary(ByteArrayInputStream(bccwjBytes), ImportDetails())
        assertNotNull("BCCWJ import should succeed", bccwjResult.summary)
        assertTrue("BCCWJ import should report success", bccwjResult.summary!!.importSuccess)
        val bccwjTitle = bccwjResult.summary.title

        val storedEntity = db.dictionaryDao().getDictionaryByTitle(bccwjTitle)
        assertNotNull("BCCWJ should be stored in the DB", storedEntity)
        println("=== Real BCCWJ index.json frequencyMode as imported: ${storedEntity!!.frequencyMode} ===")

        val recognizedAsFrequencyDict = db.dictionaryDao().getFrequencyDictionaries().any { it.title == bccwjTitle }
        println("=== Is real BCCWJ recognized by getFrequencyDictionaries()? $recognizedAsFrequencyDict ===")

        // BCCWJ's real index.json (a genuine, actively-used frequency dictionary) does not
        // declare a "frequencyMode" field at all. If getFrequencyDictionaries() is used to
        // decide which active dictionaries contribute to frequency-based sort scoring, a
        // real frequency dictionary silently failing this check would mean installing it
        // has no effect on lookup ordering - contradicting its entire purpose - even though
        // its badge still renders (the renderer doesn't filter on frequencyMode at all).
        assertTrue(
            "BCCWJ (frequencyMode=${storedEntity.frequencyMode}) should be usable as a " +
                    "frequency dictionary for sorting, the same as it is for badge rendering",
            recognizedAsFrequencyDict
        )
    }
}
