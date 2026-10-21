package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale

/** What [AnkiAudioDownloader.downloadTermAudio] found. */
sealed interface AnkiAudioResult {
    /** The first valid audio file, with its bytes and the Content-Type it was served with. */
    class Audio(val data: ByteArray, val contentType: String?) : AnkiAudioResult

    /**
     * No source had valid audio. In that case, the original leaves `{audio}` empty without an
     * error.
     */
    data object NoAudio : AnkiAudioResult

    /**
     * No source had valid audio, and a download was cancelled by the idle timeout. The original
     * means to report this case as its `audio-download-idle-timeout` error but never does, so it
     * leaves `{audio}` empty without an error, as for [NoAudio].
     */
    data object TimedOut : AnkiAudioResult

    /** Every request failed without getting a response, as when the device has no network connection. */
    data object Offline : AnkiAudioResult
}

/**
 * Port of the original's AudioDownloader (ext/js/media/audio-downloader.js), for the sources it
 * can export to Anki. Each source gives candidate URLs (the original's `url` infos), and the first
 * candidate that downloads valid audio wins. Candidates are looked up only as they are needed, so a
 * source stops making requests once one of its files downloads. Requests are blocking, so call
 * downloadTermAudio only off the main thread.
 */
class AnkiAudioDownloader(private val http: AnkiHttp) {

    companion object {
        /** The SHA-256 of the file JapanesePod101 serves for a word it has no audio for. */
        private const val JPOD101_INVALID_AUDIO_SHA256 =
            "ae6398b5a27bc8c0a771df6c907ade794be15518174773c58c7c7ddd17098906"
        private val CUSTOM_URL_TAG = Regex("\\{([^}]*)\\}")

        /**
         * The idle timeout of the lookups that find the candidate URLs. Not to be confused with the
         * download timeout, which is a user setting. The original has no timeout for this step, but
         * here a lookup that never answers would hold the Anki write indefinitely. A lookup that
         * times out counts as a source that could not be reached, not as a timed-out download,
         * since the download timeout setting does not apply to it.
         */
        internal const val LOOKUP_TIMEOUT_MILLIS = 30_000
    }

    /**
     * The original's downloadTermAudio, without a preferred audio index. That index comes from the
     * original's popup audio menu, which this port does not have. [sources], followed by the
     * language's default sources if [enableDefaultAudioSources], are tried in order. If
     * [timeoutMillis] is given, an audio file download is cancelled after that long without
     * progress. The lookups that find the files use [LOOKUP_TIMEOUT_MILLIS] instead.
     */
    fun downloadTermAudio(
        sources: List<AnkiAudioSource>,
        term: String,
        reading: String,
        timeoutMillis: Int?,
        language: String,
        enableDefaultAudioSources: Boolean
    ): AnkiAudioResult {
        val counted = CountingHttp(http)
        val required =
            if (enableDefaultAudioSources) AnkiLanguages.requiredAudioSources(language, sources) else emptyList()
        val languageInfo = AnkiLanguages.of(language)
        for (source in sources + required) {
            for (url in termAudioUrls(counted, source, term, reading, languageInfo)) {
                try {
                    val response = counted.download(url, timeoutMillis)
                    if (response.isOk && isAudioBinaryValid(response.body, source.type)) {
                        return AnkiAudioResult.Audio(response.body, response.contentType)
                    }
                } catch (_: Exception) {
                    // As in the original, a failed download moves on to the next candidate.
                }
            }
        }
        return when {
            counted.timeouts > 0 -> AnkiAudioResult.TimedOut
            counted.requests > 0 && counted.connectionFailures == counted.requests -> AnkiAudioResult.Offline
            else -> AnkiAudioResult.NoAudio
        }
    }

    /** The candidate URLs [source] gives for [term] and [reading] in the session [language]. */
    internal fun termAudioUrls(source: AnkiAudioSource, term: String, reading: String, language: String): List<String> =
        termAudioUrls(http, source, term, reading, AnkiLanguages.of(language)).toList()

    /**
     * The original's getTermAudioInfoList, looked up lazily. Each lookup runs only once the
     * candidates before it have been tried. A failed lookup ends the source's candidates and keeps
     * the ones found before it.
     */
    private fun termAudioUrls(
        http: AnkiHttp,
        source: AnkiAudioSource,
        term: String,
        reading: String,
        language: AnkiLanguage
    ): Sequence<String> {
        val urls = sequence {
            yieldAll(
                when (source.type) {
                    AnkiAudioSourceType.JPOD101 -> jpod101Urls(term, reading).asSequence()
                    AnkiAudioSourceType.LANGUAGE_POD_101 -> languagePod101Urls(
                        http,
                        term,
                        reading,
                        language
                    ).asSequence()

                    AnkiAudioSourceType.JISHO -> jishoUrls(http, term, reading).asSequence()
                    AnkiAudioSourceType.LINGUA_LIBRE -> linguaLibreUrls(http, term, language)
                    AnkiAudioSourceType.WIKTIONARY -> wiktionaryUrls(http, term, language)
                    AnkiAudioSourceType.CUSTOM -> sequenceOf(customUrl(source.url, term, reading, language))
                    AnkiAudioSourceType.CUSTOM_JSON -> customJsonUrls(
                        http,
                        source.url,
                        term,
                        reading,
                        language
                    ).asSequence()
                }
            )
        }.iterator()
        return generateSequence {
            try {
                if (urls.hasNext()) urls.next() else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The original's _getInfoJpod101. A kana word is looked up by its reading alone. */
    private fun jpod101Urls(term: String, reading: String): List<String> {
        var kanji = term
        var kana = reading
        if (kana == kanji && isStringEntirelyKana(kanji)) {
            kana = kanji
            kanji = ""
        }
        val params = buildList {
            if (kanji.isNotEmpty()) add("kanji" to kanji)
            if (kana.isNotEmpty()) add("kana" to kana)
        }
        val query = params.joinToString("&") { (name, value) -> "$name=${AnkiUrls.encodeComponent(value)}" }
        return listOf("https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?$query")
    }

    /** The original's _getInfoLanguagePod101. */
    private fun languagePod101Urls(
        http: AnkiHttp,
        term: String,
        reading: String,
        language: AnkiLanguage
    ): List<String> {
        val response = http.postForm(
            languagePod101FetchUrl(language.name),
            linkedMapOf(
                "post" to "dictionary_reference",
                "match_type" to "exact",
                "search_query" to term,
                "vulgar" to "true"
            ),
            LOOKUP_TIMEOUT_MILLIS
        )
        val dom = Jsoup.parse(response.text())
        val urls = LinkedHashSet<String>()
        for (row in dom.getElementsByClass("dc-result-row")) {
            try {
                val audio = row.getElementsByTag("audio").firstOrNull() ?: continue
                val source = audio.getElementsByTag("source").firstOrNull() ?: continue
                if (!source.hasAttr("src")) continue
                if (!isLanguagePod101RowValid(language.name, row, term, reading)) continue
                urls += AnkiUrls.resolve(source.attr("src"), response.url)
            } catch (_: Exception) {
                // As in the original, a row that cannot be read is skipped.
            }
        }
        return urls.toList()
    }

    /**
     * The original's _validateLanguagePod101Row. Text is compared as the DOM's textContent, which
     * is jsoup's wholeText(), not its whitespace-normalizing text().
     */
    private fun isLanguagePod101RowValid(language: String, row: Element, term: String, reading: String): Boolean {
        if (language == "Japanese") {
            val htmlReading = row.getElementsByClass("dc-vocab_kana").firstOrNull()?.wholeText() ?: return false
            if (htmlReading.isEmpty()) return false
            if (reading != term && reading != htmlReading) return false
        } else {
            val vocab = row.getElementsByClass("dc-vocab").firstOrNull() ?: return false
            if (term != vocab.wholeText()) return false
        }
        return true
    }

    /** The original's _getLanguagePod101FetchUrl and _getLanguagePod101PodOrClass. */
    private fun languagePod101FetchUrl(language: String): String {
        val podOrClass = when (language) {
            "Afrikaans", "Arabic", "Bulgarian", "Dutch", "Filipino", "Finnish", "French", "German", "Greek",
            "Hebrew", "Hindi", "Hungarian", "Indonesian", "Italian", "Japanese", "Persian", "Polish",
            "Portuguese", "Romanian", "Russian", "Spanish", "Swahili", "Swedish", "Thai", "Urdu",
            "Vietnamese" -> "pod"

            "Cantonese", "Chinese", "Czech", "Danish", "English", "Korean", "Norwegian", "Turkish" -> "class"
            else -> throw IllegalArgumentException("Invalid language for LanguagePod101")
        }
        return "https://www.${language.lowercase(Locale.ROOT)}${podOrClass}101.com/learningcenter/reference/dictionary_post"
    }

    /**
     * The original's _getInfoJisho, which finds the audio element that Jisho gives the term and
     * reading.
     */
    private fun jishoUrls(http: AnkiHttp, term: String, reading: String): List<String> {
        val response = http.get("https://jisho.org/search/$term", LOOKUP_TIMEOUT_MILLIS)
        val audio = Jsoup.parse(response.text()).getElementById("audio_$term:$reading")
        val source = audio?.getElementsByTag("source")?.firstOrNull()
        if (source != null && source.hasAttr("src")) {
            return listOf(AnkiUrls.resolve(source.attr("src"), response.url))
        }
        throw IllegalStateException("Failed to find audio URL")
    }

    /**
     * The original's _getInfoLinguaLibre. As in the original, the term goes into the search and
     * the file name pattern unescaped; a term that makes an invalid pattern gives no URLs.
     */
    private fun linguaLibreUrls(http: AnkiHttp, term: String, language: AnkiLanguage): Sequence<String> {
        val iso639Part3 = language.iso639Part3
        val searchCategory = "incategory:\"Lingua_Libre_pronunciation-$iso639Part3\""
        val searchString = "-$term.wav"
        val fetchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search" +
                "&srsearch=intitle:/$searchString/i+$searchCategory&srnamespace=6&origin=*"
        return wikimediaCommonsUrls(http, fetchUrl) { fileName, fileUser ->
            Regex("^File:LL-Q\\d+\\s+\\($iso639Part3\\)-$fileUser-$term\\.wav$", RegexOption.IGNORE_CASE)
                .containsMatchIn(fileName)
        }
    }

    /** The original's _getInfoWiktionary, with the term unescaped as in [linguaLibreUrls]. */
    private fun wiktionaryUrls(http: AnkiHttp, term: String, language: AnkiLanguage): Sequence<String> {
        val iso = language.iso
        val searchString = "$iso(-[a-zA-Z]{2})?-$term[0123456789]*.ogg"
        val fetchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search" +
                "&srsearch=intitle:/$searchString/i&srnamespace=6&origin=*"
        val validFileName = Regex("^File:$iso(-\\w\\w)?-$term\\d*\\.ogg$", RegexOption.IGNORE_CASE)
        return wikimediaCommonsUrls(http, fetchUrl) { fileName, _ -> validFileName.containsMatchIn(fileName) }
    }

    /**
     * The original's _getInfoWikimediaCommons, which gives the files a Commons search finds. Each
     * file is looked up for its URL and uploader and kept when [isValid] accepts its name and
     * uploader. The original looks up every file at once with Promise.all before it tries any of
     * them, and one failed lookup loses them all. Here each file is looked up only once the ones
     * before it have been tried, so a file that downloads ends the lookups, and a failed lookup
     * keeps the files found before it.
     */
    private fun wikimediaCommonsUrls(
        http: AnkiHttp,
        fetchUrl: String,
        isValid: (fileName: String, fileUser: String) -> Boolean
    ): Sequence<String> = sequence {
        val search = Json.parseToJsonElement(http.get(fetchUrl, LOOKUP_TIMEOUT_MILLIS).text()).jsonObject
            .getValue("query").jsonObject.getValue("search").jsonArray
        for (result in search) {
            val title = result.jsonObject.getValue("title").jsonPrimitive.content
            val fileInfoUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
                    "&titles=$title&prop=imageinfo&iiprop=user|url&origin=*"
            val pages = Json.parseToJsonElement(http.get(fileInfoUrl, LOOKUP_TIMEOUT_MILLIS).text()).jsonObject
                .getValue("query").jsonObject.getValue("pages").jsonObject
            for (page in pages.values) {
                val info = page.jsonObject.getValue("imageinfo").jsonArray[0].jsonObject
                val fileUrl = info.getValue("url").jsonPrimitive.content
                val fileUser = info.getValue("user").jsonPrimitive.content
                if (isValid(title, fileUser)) yield(fileUrl)
            }
        }
    }

    /** The original's isStringEntirelyKana (ext/js/language/ja/japanese.js). */
    private fun isStringEntirelyKana(text: String): Boolean {
        if (text.isEmpty()) return false
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (!AnkiJapanese.isCodePointKana(codePoint)) return false
            i += Character.charCount(codePoint)
        }
        return true
    }

    /**
     * The original's _getCustomUrl. It replaces `{term}`, `{reading}`, and `{language}` (the ISO
     * code) and leaves any other `{...}` as it is.
     */
    private fun customUrl(url: String, term: String, reading: String, language: AnkiLanguage): String {
        val values = mapOf("term" to term, "reading" to reading, "language" to language.iso)
        return CUSTOM_URL_TAG.replace(url) { match -> values[match.groupValues[1]] ?: match.value }
    }

    /** The original's _getInfoCustomJson. */
    private fun customJsonUrls(
        http: AnkiHttp,
        url: String,
        term: String,
        reading: String,
        language: AnkiLanguage
    ): List<String> {
        val response = http.get(customUrl(url, term, reading, language), LOOKUP_TIMEOUT_MILLIS)
        check(response.isOk) { "Invalid response: ${response.status}" }
        return parseCustomAudioList(response.text())
    }

    /**
     * The `url` of each item of a custom audio list that matches the original's
     * custom-audio-list-schema.json. Such a list is an object with exactly `type` (the string
     * `audioSourceList`) and `audioSources`, which is an array of objects that each have a string
     * `url`, an optional string `name`, and nothing else. Throws for anything else.
     */
    private fun parseCustomAudioList(text: String): List<String> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Not an object")
        require(root.keys == setOf("type", "audioSources")) { "Unexpected keys" }
        val type = root["type"] as? JsonPrimitive
        require(type != null && type.isString && type.content == "audioSourceList") { "Unexpected type" }
        val items = root["audioSources"] as? JsonArray ?: throw IllegalArgumentException("No audioSources array")
        return items.map { item ->
            val entry = item as? JsonObject ?: throw IllegalArgumentException("Not an object")
            require(entry.keys.all { it == "url" || it == "name" }) { "Unexpected keys" }
            val name = entry["name"]
            require(name == null || (name is JsonPrimitive && name.isString)) { "Invalid name" }
            val entryUrl = entry["url"] as? JsonPrimitive
            require(entryUrl != null && entryUrl.isString) { "Invalid url" }
            entryUrl.content
        }
    }

    /** The original's _isAudioBinaryValid. Only JapanesePod101 serves a placeholder for missing audio. */
    internal fun isAudioBinaryValid(
        data: ByteArray,
        type: AnkiAudioSourceType,
        invalidJpod101Sha256: String = JPOD101_INVALID_AUDIO_SHA256
    ): Boolean = when (type) {
        AnkiAudioSourceType.JPOD101 -> sha256(data) != invalidJpod101Sha256
        else -> true
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /**
     * Counts the requests made and how they failed, which tells a timed-out download or a missing
     * network connection apart from audio that does not exist. The sources' lookups go through
     * [get] and [postForm], and the audio file downloads through [download].
     */
    private class CountingHttp(private val http: AnkiHttp) : AnkiHttp {
        var requests = 0

        /** Downloads cancelled by the download timeout (not the lookup timeout). */
        var timeouts = 0

        /**
         * Requests that got no response for a reason other than a download's timeout. A
         * lookup that timed out counts here (see [LOOKUP_TIMEOUT_MILLIS]).
         */
        var connectionFailures = 0

        override fun get(url: String, timeoutMillis: Int?): AnkiHttpResponse =
            count(isDownload = false) { http.get(url, timeoutMillis) }

        override fun postForm(url: String, fields: Map<String, String>, timeoutMillis: Int?): AnkiHttpResponse =
            count(isDownload = false) { http.postForm(url, fields, timeoutMillis) }

        fun download(url: String, timeoutMillis: Int?): AnkiHttpResponse =
            count(isDownload = true) { http.get(url, timeoutMillis) }

        private inline fun count(isDownload: Boolean, request: () -> AnkiHttpResponse): AnkiHttpResponse {
            requests++
            try {
                return request()
            } catch (e: SocketTimeoutException) {
                if (isDownload) timeouts++ else connectionFailures++
                throw e
            } catch (e: IOException) {
                connectionFailures++
                throw e
            }
        }
    }
}
