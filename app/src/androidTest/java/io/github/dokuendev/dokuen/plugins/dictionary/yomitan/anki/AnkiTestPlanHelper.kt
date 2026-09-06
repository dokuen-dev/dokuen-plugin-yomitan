package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Android side of scripts/anki_test_plan.py, which sets up and checks the manual Anki export
 * test plan (docs/anki-export-manual-test-plan.md). Each action writes its result as JSON to a
 * file in the app's files directory, which the script reads back with `run-as`.
 *
 * These are instrumented tests only so that they run inside the plugin's own process, which holds
 * the AnkiDroid permission and can read the plugin's settings and database; the adb shell can do
 * neither. Every action is skipped unless the "action" instrumentation argument names it, so
 * running the other instrumented tests runs none of them:
 *
 * ```
 * adb shell am instrument -w -e class io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiTestPlanHelper -e action dumpNotes -e query deck:AuditTest io.github.dokuendev.dokuen.plugins.dictionary.yomitan.test/androidx.test.runner.AndroidJUnitRunner
 * adb exec-out run-as io.github.dokuendev.dokuen.plugins.dictionary.yomitan cat files/anki-test-plan-dumpNotes.json
 * ```
 *
 * Like any `am instrument` run, this restarts the plugin's process.
 */
@RunWith(AndroidJUnit4::class)
class AnkiTestPlanHelper {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(AnkiDroidClient.PERMISSION)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private fun requireAction(name: String) {
        assumeTrue("The \"action\" argument is not $name", arguments.getString("action") == name)
    }

    private fun argument(name: String): String {
        val value = arguments.getString(name)
        assertTrue("No \"$name\" argument given", !value.isNullOrBlank())
        return value!!
    }

    private fun client(): AnkiDroidClient =
        AnkiDroidClient(context).also { assertTrue("AnkiDroid is not installed", it.isAnkiDroidInstalled()) }

    private fun write(action: String, result: JSONObject) {
        File(context.filesDir, "anki-test-plan-$action.json").writeText(result.toString(), Charsets.UTF_8)
    }

    /** Every note matching the "query" argument, an Anki search. */
    @Test
    fun dumpNotes() {
        requireAction("dumpNotes")
        val query = argument("query")
        val client = client()
        val models = client.getModelList()
        val fieldNames = mutableMapOf<Long, List<String>>()
        val notes = JSONArray()
        for (note in client.findNotes(query).sortedBy { it.id }) {
            val names = fieldNames.getOrPut(note.modelId) { client.getFieldList(note.modelId) }
            notes.put(
                JSONObject()
                    .put("id", note.id)
                    .put("modelName", models[note.modelId] ?: "")
                    .put("fieldNames", JSONArray(names))
                    .put("fields", JSONArray(note.fields))
                    .put("tags", JSONArray(note.tags.sorted()))
            )
        }
        write("dumpNotes", JSONObject().put("query", query).put("notes", notes))
    }

    /** Deletes every note matching the "query" argument, an Anki search. */
    @Test
    fun deleteNotes() {
        requireAction("deleteNotes")
        val query = argument("query")
        var deleted = 0
        for (note in client().findNotes(query)) {
            deleted += context.contentResolver.delete(
                Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, note.id.toString()),
                null,
                null
            )
        }
        write("deleteNotes", JSONObject().put("query", query).put("deleted", deleted))
    }

    /**
     * Creates the decks and note types described by the "spec" argument (base64 of
     * `{"decks": [names], "models": [{"name", "fields": [names], "front", "back"}]}`) that do not
     * exist yet. An existing note type is left as it is; the result lists its fields so the caller
     * can check them.
     */
    @Test
    fun setupCollection() {
        requireAction("setupCollection")
        val spec = JSONObject(String(Base64.decode(argument("spec"), Base64.DEFAULT), Charsets.UTF_8))
        val client = client()
        val api = AddContentApi(context)

        val decks = JSONObject()
        val existingDecks = client.getDeckList()
        val deckNames = spec.getJSONArray("decks")
        for (i in 0 until deckNames.length()) {
            val name = deckNames.getString(i)
            val id = existingDecks.entries.firstOrNull { it.value == name }?.key ?: api.addNewDeck(name)
            decks.put(name, id)
        }

        val models = JSONObject()
        val existingModels = client.getModelList()
        val modelSpecs = spec.getJSONArray("models")
        for (i in 0 until modelSpecs.length()) {
            val model = modelSpecs.getJSONObject(i)
            val name = model.getString("name")
            val fields = model.getJSONArray("fields").let { array -> Array(array.length()) { array.getString(it) } }
            var id = existingModels.entries.firstOrNull { it.value == name }?.key
            val created = id == null
            if (id == null) {
                id = api.addNewCustomModel(
                    name,
                    fields,
                    arrayOf("Card 1"),
                    arrayOf(model.getString("front")),
                    arrayOf(model.getString("back")),
                    null,
                    null,
                    null
                )
            }
            models.put(
                name,
                JSONObject()
                    .put("id", id)
                    .put("created", created)
                    .put("fields", JSONArray(if (id == null) emptyList() else client.getFieldList(id)))
            )
        }
        write("setupCollection", JSONObject().put("decks", decks).put("models", models))
    }

    /** Removes the plugin's stored Anki settings, so it starts over from the defaults. */
    @Test
    fun resetAnkiSettings() {
        requireAction("resetAnkiSettings")
        val removed = context.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("anki_settings")
            .commit()
        write("resetAnkiSettings", JSONObject().put("removed", removed))
    }

    /** The plugin's installed dictionaries, their search order and its stored Anki settings. */
    @Test
    fun dumpPluginState() {
        requireAction("dumpPluginState")
        val dictionaries = JSONArray()
        runBlocking { AppDatabase.getDatabase(context).dictionaryDao().getAllDictionaries() }.forEach {
            dictionaries.put(JSONObject().put("title", it.title).put("revision", it.revision))
        }
        val prefs = context.getSharedPreferences("yomitan_prefs", Context.MODE_PRIVATE)
        fun list(key: String) = JSONArray((prefs.getString(key, "") ?: "").split(",").filter { it.isNotEmpty() })
        write(
            "dumpPluginState",
            JSONObject()
                .put("dictionaries", dictionaries)
                .put("activeDictionaries", list("active_dictionaries"))
                .put("sortFrequencyDictionary", prefs.getString("sort_frequency_dictionary", "") ?: "")
                .put("ankiSettings", prefs.getString("anki_settings", null)?.let { JSONObject(it) } ?: JSONObject.NULL)
        )
    }
}
