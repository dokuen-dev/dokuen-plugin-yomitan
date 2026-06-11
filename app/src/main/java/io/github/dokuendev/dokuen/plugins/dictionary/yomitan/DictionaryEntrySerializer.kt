package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import io.github.dokuendev.dokuenreader.dictionary.BlockSpan
import io.github.dokuendev.dokuenreader.dictionary.DictionaryEntry
import io.github.dokuendev.dokuenreader.dictionary.HeadwordSpan
import io.github.dokuendev.dokuenreader.dictionary.InlineStyle
import io.github.dokuendev.dokuenreader.dictionary.RubySpan
import io.github.dokuendev.dokuenreader.dictionary.StyledSpan
import io.github.dokuendev.dokuenreader.dictionary.StyledText
import org.json.JSONArray
import org.json.JSONObject

object DictionaryEntrySerializer {

    fun toJson(entries: List<DictionaryEntry>): String {
        val jsonArray = JSONArray()
        for (entry in entries) {
            jsonArray.put(entryToJson(entry))
        }
        return jsonArray.toString(4)
    }

    private fun entryToJson(entry: DictionaryEntry): JSONObject {
        val json = JSONObject()
        json.put("headword", entry.headword)

        entry.pronunciation?.let { pronunciationArray ->
            val pronJson = JSONArray()
            for (span in pronunciationArray) {
                pronJson.put(rubySpanToJson(span))
            }
            json.put("pronunciation", pronJson)
        }

        entry.headwordSpans?.let { hwSpans ->
            val hwJson = JSONArray()
            for (span in hwSpans) {
                hwJson.put(headwordSpanToJson(span))
            }
            json.put("headwordSpans", hwJson)
        }

        json.put("body", styledTextToJson(entry.body))
        return json
    }

    private fun headwordSpanToJson(span: HeadwordSpan): JSONObject {
        val json = JSONObject()
        json.put("startIndex", span.startIndex)
        json.put("endIndex", span.endIndex)
        json.put("linkUrl", span.linkUrl)
        return json
    }

    private fun rubySpanToJson(span: RubySpan): JSONObject {
        val json = JSONObject()
        json.put("startIndex", span.startIndex)
        json.put("endIndex", span.endIndex)
        json.put("rubyText", span.rubyText)
        return json
    }

    private fun styledTextToJson(styledText: StyledText): JSONObject {
        val json = JSONObject()
        json.put("text", styledText.text)

        styledText.blockSpans?.let { spans ->
            val spansJson = JSONArray()
            for (span in spans) {
                spansJson.put(blockSpanToJson(span))
            }
            json.put("blockSpans", spansJson)
        }

        styledText.styledSpans?.let { spans ->
            val spansJson = JSONArray()
            for (span in spans) {
                spansJson.put(styledSpanToJson(span))
            }
            json.put("styledSpans", spansJson)
        }

        styledText.rubySpans?.let { rubies ->
            val rubiesJson = JSONArray()
            for (ruby in rubies) {
                rubiesJson.put(rubySpanToJson(ruby))
            }
            json.put("rubySpans", rubiesJson)
        }

        return json
    }

    private fun styledSpanToJson(span: StyledSpan): JSONObject {
        val json = JSONObject()
        json.put("startIndex", span.startIndex)
        json.put("endIndex", span.endIndex)
        json.put("style", inlineStyleToJson(span.style))
        return json
    }

    private fun inlineStyleToJson(style: InlineStyle): JSONObject {
        val json = JSONObject()
        json.put("bold", style.bold)
        json.put("italic", style.italic)
        json.put("fontSize", style.fontSize.toDouble())
        json.put("foregroundColor", style.foregroundColor)
        json.put("textBackgroundColor", style.textBackgroundColor)
        style.hoverText?.let { json.put("hoverText", it) }
        style.linkUrl?.let { json.put("linkUrl", it) }
        return json
    }

    private fun blockSpanToJson(span: BlockSpan): JSONObject {
        val json = JSONObject()
        json.put("startIndex", span.startIndex)
        json.put("endIndex", span.endIndex)
        json.put("blockType", span.blockType)
        json.put("indentLevel", span.indentLevel)
        span.listMarker?.let { json.put("listMarker", it) }
        json.put("backgroundColor", span.backgroundColor)
        return json
    }

    fun fromJson(jsonStr: String): List<DictionaryEntry> {
        // Handle both a bare array and a wrapped {"entries": [...]} object, i.e. DictionaryResult
        val jsonArray = try {
            JSONArray(jsonStr)
        } catch (e: org.json.JSONException) {
            JSONObject(jsonStr).getJSONArray("entries")
        }
        val entries = mutableListOf<DictionaryEntry>()
        for (i in 0 until jsonArray.length()) {
            entries.add(jsonToEntry(jsonArray.getJSONObject(i)))
        }
        return entries
    }

    private fun jsonToEntry(json: JSONObject): DictionaryEntry {
        val headword = json.getString("headword")
        val pronunciation = if (json.has("pronunciation")) {
            val pronJson = json.getJSONArray("pronunciation")
            val list = mutableListOf<RubySpan>()
            for (i in 0 until pronJson.length()) {
                list.add(jsonToRubySpan(pronJson.getJSONObject(i)))
            }
            list.toTypedArray()
        } else {
            null
        }
        val headwordSpans = if (json.has("headwordSpans")) {
            val hwJson = json.getJSONArray("headwordSpans")
            val list = mutableListOf<HeadwordSpan>()
            for (i in 0 until hwJson.length()) {
                list.add(jsonToHeadwordSpan(hwJson.getJSONObject(i)))
            }
            list.toTypedArray()
        } else {
            null
        }
        val body = jsonToStyledText(json.getJSONObject("body"))
        return DictionaryEntry(
            headword = headword,
            pronunciation = pronunciation,
            headwordSpans = headwordSpans,
            body = body
        )
    }

    private fun jsonToHeadwordSpan(json: JSONObject): HeadwordSpan {
        return HeadwordSpan(
            startIndex = json.getInt("startIndex"),
            endIndex = json.getInt("endIndex"),
            linkUrl = json.getString("linkUrl")
        )
    }

    private fun jsonToRubySpan(json: JSONObject): RubySpan {
        return RubySpan(
            startIndex = json.getInt("startIndex"),
            endIndex = json.getInt("endIndex"),
            rubyText = json.getString("rubyText")
        )
    }

    private fun jsonToStyledText(json: JSONObject): StyledText {
        val text = json.getString("text")
        val blockSpans = if (json.has("blockSpans")) {
            val spansJson = json.getJSONArray("blockSpans")
            val list = mutableListOf<BlockSpan>()
            for (i in 0 until spansJson.length()) {
                list.add(jsonToBlockSpan(spansJson.getJSONObject(i)))
            }
            list.toTypedArray()
        } else {
            null
        }
        val styledSpans = if (json.has("styledSpans")) {
            val spansJson = json.getJSONArray("styledSpans")
            val list = mutableListOf<StyledSpan>()
            for (i in 0 until spansJson.length()) {
                list.add(jsonToStyledSpan(spansJson.getJSONObject(i)))
            }
            list.toTypedArray()
        } else {
            null
        }
        val rubySpans = if (json.has("rubySpans")) {
            val rubiesJson = json.getJSONArray("rubySpans")
            val list = mutableListOf<RubySpan>()
            for (i in 0 until rubiesJson.length()) {
                list.add(jsonToRubySpan(rubiesJson.getJSONObject(i)))
            }
            list.toTypedArray()
        } else {
            null
        }
        return StyledText(
            text = text,
            blockSpans = blockSpans,
            styledSpans = styledSpans,
            rubySpans = rubySpans
        )
    }

    private fun jsonToStyledSpan(json: JSONObject): StyledSpan {
        return StyledSpan(
            startIndex = json.getInt("startIndex"),
            endIndex = json.getInt("endIndex"),
            style = jsonToInlineStyle(json.getJSONObject("style"))
        )
    }

    private fun jsonToInlineStyle(json: JSONObject): InlineStyle {
        return InlineStyle(
            bold = json.optBoolean("bold", false),
            italic = json.optBoolean("italic", false),
            fontSize = json.optDouble("fontSize", 1.0).toFloat(),
            foregroundColor = json.optInt("foregroundColor", 0),
            textBackgroundColor = json.optInt("textBackgroundColor", 0),
            hoverText = if (json.has("hoverText") && !json.isNull("hoverText")) json.getString("hoverText") else null,
            linkUrl = if (json.has("linkUrl") && !json.isNull("linkUrl")) json.getString("linkUrl") else null
        )
    }

    private fun jsonToBlockSpan(json: JSONObject): BlockSpan {
        return BlockSpan(
            startIndex = json.getInt("startIndex"),
            endIndex = json.getInt("endIndex"),
            blockType = json.optInt("blockType", 0),
            indentLevel = json.optInt("indentLevel", 0),
            listMarker = if (json.has("listMarker") && !json.isNull("listMarker")) json.getString("listMarker") else null,
            backgroundColor = json.optInt("backgroundColor", 0)
        )
    }
}
