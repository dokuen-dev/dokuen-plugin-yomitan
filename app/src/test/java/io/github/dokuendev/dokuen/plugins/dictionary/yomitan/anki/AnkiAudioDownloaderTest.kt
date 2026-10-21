package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AnkiAudioDownloaderTest {

    private companion object {
        const val JPOD101 = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?"
        const val TABERU_JPOD101 = JPOD101 + "kanji=%E9%A3%9F%E3%81%B9%E3%82%8B&kana=%E3%81%9F%E3%81%B9%E3%82%8B"
        val JPOD101_ONLY = listOf(AnkiAudioSource(AnkiAudioSourceType.JPOD101))
    }

    private fun download(
        http: FakeAnkiHttp,
        sources: List<AnkiAudioSource> = JPOD101_ONLY,
        term: String = "食べる",
        reading: String = "たべる",
        timeoutMillis: Int? = null,
        language: String = "ja",
        enableDefaultAudioSources: Boolean = false
    ) = AnkiAudioDownloader(http).downloadTermAudio(
        sources,
        term,
        reading,
        timeoutMillis,
        language,
        enableDefaultAudioSources
    )

    @Test
    fun jpod101UrlHasTheTermAndReading() {
        assertEquals(
            listOf(TABERU_JPOD101),
            AnkiAudioDownloader(FakeAnkiHttp()).termAudioUrls(JPOD101_ONLY[0], "食べる", "たべる", "ja")
        )
    }

    @Test
    fun jpod101DropsTheTermOfAKanaWord() {
        assertEquals(
            listOf(JPOD101 + "kana=%E3%81%99%E3%81%94%E3%81%84"),
            AnkiAudioDownloader(FakeAnkiHttp()).termAudioUrls(JPOD101_ONLY[0], "すごい", "すごい", "ja")
        )
    }

    @Test
    fun jpod101WithoutReadingSendsOnlyKanji() {
        assertEquals(
            listOf(JPOD101 + "kanji=%E9%A3%9F"),
            AnkiAudioDownloader(FakeAnkiHttp()).termAudioUrls(JPOD101_ONLY[0], "食", "", "ja")
        )
    }

    @Test
    fun theFirstValidDownloadIsReturned() {
        val http = FakeAnkiHttp(TABERU_JPOD101 to FakeAnkiHttp.Reply.audio(byteArrayOf(9, 8), "audio/mpeg"))

        val result = download(http, timeoutMillis = 1500)

        result as AnkiAudioResult.Audio
        assertArrayEquals(byteArrayOf(9, 8), result.data)
        assertEquals("audio/mpeg", result.contentType)
        assertEquals(1500, http.requests.single().timeoutMillis)
    }

    @Test
    fun onlyJpod101sPlaceholderFileIsInvalid() {
        // The real placeholder is not in the repository, so the check is pointed at the hash of a
        // stand-in body.
        val body = "placeholder".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val downloader = AnkiAudioDownloader(FakeAnkiHttp())

        assertTrue(downloader.isAudioBinaryValid(body, AnkiAudioSourceType.JPOD101))
        assertEquals(
            false,
            downloader.isAudioBinaryValid(body, AnkiAudioSourceType.JPOD101, invalidJpod101Sha256 = digest)
        )
        assertTrue(downloader.isAudioBinaryValid(body, AnkiAudioSourceType.CUSTOM, invalidJpod101Sha256 = digest))
    }

    @Test
    fun aFailedSourceFallsThroughToTheNext() {
        val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://localhost/{term}/{reading}.mp3")
        val http = FakeAnkiHttp(
            TABERU_JPOD101 to FakeAnkiHttp.Reply.status(404),
            "http://localhost/食べる/たべる.mp3" to FakeAnkiHttp.Reply.audio(byteArrayOf(7))
        )

        val result = download(http, sources = JPOD101_ONLY + custom)

        assertArrayEquals(byteArrayOf(7), (result as AnkiAudioResult.Audio).data)
    }

    @Test
    fun customUrlReplacesOnlyItsThreeTags() {
        val source = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://x/{term}?r={reading}&l={language}&o={other}")

        assertEquals(
            listOf("http://x/食べる?r=たべる&l=ja&o={other}"),
            AnkiAudioDownloader(FakeAnkiHttp()).termAudioUrls(source, "食べる", "たべる", "ja")
        )
    }

    @Test
    fun customJsonListsItsUrls() {
        val source = AnkiAudioSource(AnkiAudioSourceType.CUSTOM_JSON, "http://localhost:5050/?term={term}")
        val http = FakeAnkiHttp(
            "http://localhost:5050/?term=食べる" to FakeAnkiHttp.Reply.text(
                """{"type":"audioSourceList","audioSources":[{"name":"a","url":"http://a/1.mp3"},{"url":"http://a/2.mp3"}]}"""
            )
        )

        assertEquals(
            listOf("http://a/1.mp3", "http://a/2.mp3"),
            AnkiAudioDownloader(http).termAudioUrls(source, "食べる", "たべる", "ja")
        )
    }

    @Test
    fun customJsonThatBreaksTheSchemaGivesNoUrls() {
        val bodies = listOf(
            """{"type":"other","audioSources":[]}""",
            """{"type":"audioSourceList"}""",
            """{"type":"audioSourceList","audioSources":[{"name":"a"}]}""",
            """{"type":"audioSourceList","audioSources":[{"url":1}]}""",
            """{"type":"audioSourceList","audioSources":[{"url":"u","extra":1}]}""",
            """{"type":"audioSourceList","audioSources":[],"extra":1}""",
            """not json"""
        )
        for (body in bodies) {
            val http = FakeAnkiHttp("http://j/" to FakeAnkiHttp.Reply.text(body))
            val source = AnkiAudioSource(AnkiAudioSourceType.CUSTOM_JSON, "http://j/")

            assertEquals(body, emptyList<String>(), AnkiAudioDownloader(http).termAudioUrls(source, "a", "a", "ja"))
        }
    }

    @Test
    fun customJsonWithAnErrorStatusGivesNoUrls() {
        val http = FakeAnkiHttp("http://j/" to FakeAnkiHttp.Reply.status(500))
        val source = AnkiAudioSource(AnkiAudioSourceType.CUSTOM_JSON, "http://j/")

        assertEquals(emptyList<String>(), AnkiAudioDownloader(http).termAudioUrls(source, "a", "a", "ja"))
    }

    @Test
    fun defaultSourcesAreTriedAfterTheConfiguredOnes() {
        val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://c/{term}")
        val http = FakeAnkiHttp(TABERU_JPOD101 to FakeAnkiHttp.Reply.audio(byteArrayOf(5)))

        val result = download(http, sources = listOf(custom), enableDefaultAudioSources = true)

        assertArrayEquals(byteArrayOf(5), (result as AnkiAudioResult.Audio).data)
        assertEquals(listOf("http://c/食べる", TABERU_JPOD101), http.requests.map { it.url })
    }

    @Test
    fun noValidAudioIsNoAudio() {
        val http = FakeAnkiHttp(TABERU_JPOD101 to FakeAnkiHttp.Reply.status(404))

        assertSame(AnkiAudioResult.NoAudio, download(http))
    }

    @Test
    fun noSourcesMakesNoRequest() {
        val http = FakeAnkiHttp()

        assertSame(AnkiAudioResult.NoAudio, download(http, sources = emptyList()))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aTimedOutDownloadIsReportedEvenWhenOthersFailedToConnect() {
        val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://unreachable/{term}")
        val http = FakeAnkiHttp(TABERU_JPOD101 to FakeAnkiHttp.Reply.timeout())

        assertSame(AnkiAudioResult.TimedOut, download(http, sources = JPOD101_ONLY + custom, timeoutMillis = 100))
    }

    @Test
    fun everyRequestFailingToConnectIsOffline() {
        assertSame(AnkiAudioResult.Offline, download(FakeAnkiHttp(), enableDefaultAudioSources = true))
    }

    @Test
    fun oneResponseMeansNotOffline() {
        val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "http://c/{term}")
        val http = FakeAnkiHttp("http://c/食べる" to FakeAnkiHttp.Reply.status(404))

        assertSame(AnkiAudioResult.NoAudio, download(http, sources = JPOD101_ONLY + custom))
    }

    private val languagePod101Japanese = "https://www.japanesepod101.com/learningcenter/reference/dictionary_post"

    private val languagePod101Page = """
        <div class="dc-result-row">
          <span class="dc-vocab">食べる</span><span class="dc-vocab_kana">たべる</span>
          <audio><source src="/audio/taberu.mp3" type="audio/mpeg"></audio>
        </div>
        <div class="dc-result-row">
          <span class="dc-vocab">食べる</span><span class="dc-vocab_kana">くべる</span>
          <audio><source src="https://cdn.example/kuberu.mp3"></audio>
        </div>
        <div class="dc-result-row"><span class="dc-vocab_kana">たべる</span></div>
        <div class="dc-result-row">
          <span class="dc-vocab_kana">たべる</span>
          <audio><source src="/audio/taberu.mp3"></audio>
        </div>
    """.trimIndent()

    @Test
    fun languagePod101PostsTheTermAndKeepsRowsWithTheReading() {
        val http = FakeAnkiHttp(languagePod101Japanese to FakeAnkiHttp.Reply.text(languagePod101Page))
        val source = AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101)

        val urls = AnkiAudioDownloader(http).termAudioUrls(source, "食べる", "たべる", "ja")

        // The くべる row has the wrong reading, the third has no audio, and the fourth repeats the first.
        assertEquals(listOf("https://www.japanesepod101.com/audio/taberu.mp3"), urls)
        assertEquals(
            FakeAnkiHttp.Request(
                "POST",
                languagePod101Japanese,
                mapOf(
                    "post" to "dictionary_reference",
                    "match_type" to "exact",
                    "search_query" to "食べる",
                    "vulgar" to "true"
                ),
                AnkiAudioDownloader.LOOKUP_TIMEOUT_MILLIS
            ),
            http.requests.single()
        )
    }

    @Test
    fun languagePod101KeepsEveryReadingOfAKanaWord() {
        val http = FakeAnkiHttp(languagePod101Japanese to FakeAnkiHttp.Reply.text(languagePod101Page))
        val source = AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101)

        val urls = AnkiAudioDownloader(http).termAudioUrls(source, "たべる", "たべる", "ja")

        assertEquals(listOf("https://www.japanesepod101.com/audio/taberu.mp3", "https://cdn.example/kuberu.mp3"), urls)
    }

    @Test
    fun languagePod101ResolvesAgainstTheFinalUrl() {
        val http = FakeAnkiHttp(
            languagePod101Japanese to FakeAnkiHttp.Reply.text(
                languagePod101Page,
                finalUrl = "https://redirected.example/x/y"
            )
        )
        val source = AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101)

        assertEquals(
            "https://redirected.example/audio/taberu.mp3",
            AnkiAudioDownloader(http).termAudioUrls(source, "食べる", "たべる", "ja").first()
        )
    }

    @Test
    fun languagePod101MatchesTheTermForOtherLanguages() {
        val page = """
            <div class="dc-result-row"><span class="dc-vocab">cat</span><audio><source src="/cat.mp3"></audio></div>
            <div class="dc-result-row"><span class="dc-vocab">cats</span><audio><source src="/cats.mp3"></audio></div>
        """.trimIndent()
        val url = "https://www.englishclass101.com/learningcenter/reference/dictionary_post"
        val http = FakeAnkiHttp(url to FakeAnkiHttp.Reply.text(page))

        assertEquals(
            listOf("https://www.englishclass101.com/cat.mp3"),
            AnkiAudioDownloader(http).termAudioUrls(
                AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101),
                "cat",
                "",
                "en"
            )
        )
    }

    @Test
    fun languagePod101HasNoSiteForSomeLanguages() {
        val http = FakeAnkiHttp()

        assertEquals(
            emptyList<String>(),
            AnkiAudioDownloader(http).termAudioUrls(
                AnkiAudioSource(AnkiAudioSourceType.LANGUAGE_POD_101),
                "saluton",
                "",
                "eo"
            )
        )
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun jishoFindsTheAudioElementOfTheTermAndReading() {
        val page = """
            <audio id="audio_食べる:たべる"><source src="//d1vjc5dkcd3yh2.cloudfront.net/audio/abc.mp3" type="audio/mpeg"></audio>
            <audio id="audio_食べる:くべる"><source src="//d1vjc5dkcd3yh2.cloudfront.net/audio/other.mp3"></audio>
        """.trimIndent()
        val http = FakeAnkiHttp("https://jisho.org/search/食べる" to FakeAnkiHttp.Reply.text(page))

        assertEquals(
            listOf("https://d1vjc5dkcd3yh2.cloudfront.net/audio/abc.mp3"),
            AnkiAudioDownloader(http).termAudioUrls(
                AnkiAudioSource(AnkiAudioSourceType.JISHO),
                "食べる",
                "たべる",
                "ja"
            )
        )
    }

    @Test
    fun lookupsUseTheLookupTimeoutAndDownloadsTheSetting() {
        val page = """<audio id="audio_食べる:たべる"><source src="https://cdn.example/abc.mp3"></audio>"""
        val http = FakeAnkiHttp(
            "https://jisho.org/search/食べる" to FakeAnkiHttp.Reply.text(page),
            "https://cdn.example/abc.mp3" to FakeAnkiHttp.Reply.audio()
        )

        download(http, sources = listOf(AnkiAudioSource(AnkiAudioSourceType.JISHO)), timeoutMillis = 1500)

        assertEquals(
            listOf(AnkiAudioDownloader.LOOKUP_TIMEOUT_MILLIS, 1500),
            http.requests.map { it.timeoutMillis }
        )
    }

    @Test
    fun aTimedOutLookupIsAnUnreachableSourceNotATimedOutDownload() {
        val jisho = listOf(AnkiAudioSource(AnkiAudioSourceType.JISHO))
        val timedOut = "https://jisho.org/search/食べる" to FakeAnkiHttp.Reply.timeout()

        assertSame(AnkiAudioResult.Offline, download(FakeAnkiHttp(timedOut), sources = jisho))
        assertSame(
            AnkiAudioResult.NoAudio,
            download(
                FakeAnkiHttp(timedOut, TABERU_JPOD101 to FakeAnkiHttp.Reply.status(404)),
                sources = jisho + JPOD101_ONLY
            )
        )
    }

    @Test
    fun jishoWithoutTheAudioElementGivesNoUrls() {
        val http = FakeAnkiHttp("https://jisho.org/search/食べる" to FakeAnkiHttp.Reply.text("<p>none</p>"))

        assertEquals(
            emptyList<String>(),
            AnkiAudioDownloader(http).termAudioUrls(
                AnkiAudioSource(AnkiAudioSourceType.JISHO),
                "食べる",
                "たべる",
                "ja"
            )
        )
    }

    private val commons = "https://commons.wikimedia.org/w/api.php?action=query&format=json"

    private fun fileInfo(title: String) = "$commons&titles=$title&prop=imageinfo&iiprop=user|url&origin=*"

    private fun fileInfoReply(url: String, user: String) =
        FakeAnkiHttp.Reply.text("""{"query":{"pages":{"1":{"imageinfo":[{"url":"$url","user":"$user"}]}}}}""")

    @Test
    fun linguaLibreKeepsFilesOfTheTermByTheirUploader() {
        val search =
            "$commons&list=search&srsearch=intitle:/-chat.wav/i+incategory:\"Lingua_Libre_pronunciation-fra\"&srnamespace=6&origin=*"
        val titles = listOf(
            "File:LL-Q150 (fra)-Alice-chat.wav",
            "File:LL-Q150 (fra)-Bob-chats.wav",
            "File:LL-Q150 (fra)-Eve-chat.wav"
        )
        val http = FakeAnkiHttp(
            search to FakeAnkiHttp.Reply.text(
                """{"query":{"search":[${titles.joinToString(",") { "{\"title\":\"$it\"}" }}]}}"""
            ),
            fileInfo(titles[0]) to fileInfoReply("https://upload.wikimedia.org/a.wav", "Alice"),
            fileInfo(titles[1]) to fileInfoReply("https://upload.wikimedia.org/b.wav", "Bob"),
            // Uploaded by someone other than the user its name gives.
            fileInfo(titles[2]) to fileInfoReply("https://upload.wikimedia.org/c.wav", "Mallory")
        )

        assertEquals(
            listOf("https://upload.wikimedia.org/a.wav"),
            AnkiAudioDownloader(http).termAudioUrls(AnkiAudioSource(AnkiAudioSourceType.LINGUA_LIBRE), "chat", "", "fr")
        )
    }

    @Test
    fun wiktionaryKeepsFilesOfTheTermInAnyRegion() {
        val search =
            "$commons&list=search&srsearch=intitle:/en(-[a-zA-Z]{2})?-cat[0123456789]*.ogg/i&srnamespace=6&origin=*"
        val titles = listOf("File:En-us-cat.ogg", "File:En-us-category.ogg", "File:En-cat2.ogg")
        val http = FakeAnkiHttp(
            search to FakeAnkiHttp.Reply.text(
                """{"query":{"search":[${titles.joinToString(",") { "{\"title\":\"$it\"}" }}]}}"""
            ),
            fileInfo(titles[0]) to fileInfoReply("https://upload.wikimedia.org/us.ogg", "A"),
            fileInfo(titles[1]) to fileInfoReply("https://upload.wikimedia.org/category.ogg", "B"),
            fileInfo(titles[2]) to fileInfoReply("https://upload.wikimedia.org/2.ogg", "C")
        )

        assertEquals(
            listOf("https://upload.wikimedia.org/us.ogg", "https://upload.wikimedia.org/2.ogg"),
            AnkiAudioDownloader(http).termAudioUrls(AnkiAudioSource(AnkiAudioSourceType.WIKTIONARY), "cat", "", "en")
        )
    }

    @Test
    fun aFailedFileLookupKeepsTheFilesFoundBeforeIt() {
        // The original looks the files up with Promise.all, so one failure loses them all there.
        val search =
            "$commons&list=search&srsearch=intitle:/en(-[a-zA-Z]{2})?-cat[0123456789]*.ogg/i&srnamespace=6&origin=*"
        val http = FakeAnkiHttp(
            search to FakeAnkiHttp.Reply.text(
                """{"query":{"search":[{"title":"File:En-cat.ogg"},{"title":"File:En-us-cat.ogg"},{"title":"File:En-cat2.ogg"}]}}"""
            ),
            fileInfo("File:En-cat.ogg") to fileInfoReply("https://upload.wikimedia.org/1.ogg", "A"),
            fileInfo("File:En-cat2.ogg") to fileInfoReply("https://upload.wikimedia.org/2.ogg", "C")
        )

        assertEquals(
            listOf("https://upload.wikimedia.org/1.ogg"),
            AnkiAudioDownloader(http).termAudioUrls(AnkiAudioSource(AnkiAudioSourceType.WIKTIONARY), "cat", "", "en")
        )
    }

    @Test
    fun aDownloadableFirstFileIsTakenWithoutLookingUpTheRest() {
        val search =
            "$commons&list=search&srsearch=intitle:/en(-[a-zA-Z]{2})?-cat[0123456789]*.ogg/i&srnamespace=6&origin=*"
        val titles = listOf("File:En-cat.ogg", "File:En-us-cat.ogg", "File:En-cat2.ogg")
        val http = FakeAnkiHttp(
            search to FakeAnkiHttp.Reply.text(
                """{"query":{"search":[${titles.joinToString(",") { "{\"title\":\"$it\"}" }}]}}"""
            ),
            fileInfo(titles[0]) to fileInfoReply("https://upload.wikimedia.org/1.ogg", "A"),
            fileInfo(titles[1]) to fileInfoReply("https://upload.wikimedia.org/us.ogg", "B"),
            fileInfo(titles[2]) to fileInfoReply("https://upload.wikimedia.org/2.ogg", "C"),
            "https://upload.wikimedia.org/1.ogg" to FakeAnkiHttp.Reply.audio(contentType = "audio/ogg")
        )

        val result = download(
            http,
            sources = listOf(AnkiAudioSource(AnkiAudioSourceType.WIKTIONARY)),
            term = "cat",
            reading = "",
            language = "en"
        )

        assertTrue(result is AnkiAudioResult.Audio)
        assertEquals(
            listOf(search, fileInfo(titles[0]), "https://upload.wikimedia.org/1.ogg"),
            http.requests.map { it.url })
    }

    @Test
    fun aFileThatFailsToDownloadMovesOnToTheNextFile() {
        val search =
            "$commons&list=search&srsearch=intitle:/en(-[a-zA-Z]{2})?-cat[0123456789]*.ogg/i&srnamespace=6&origin=*"
        val titles = listOf("File:En-cat.ogg", "File:En-us-cat.ogg")
        val http = FakeAnkiHttp(
            search to FakeAnkiHttp.Reply.text(
                """{"query":{"search":[${titles.joinToString(",") { "{\"title\":\"$it\"}" }}]}}"""
            ),
            fileInfo(titles[0]) to fileInfoReply("https://upload.wikimedia.org/1.ogg", "A"),
            fileInfo(titles[1]) to fileInfoReply("https://upload.wikimedia.org/us.ogg", "B"),
            "https://upload.wikimedia.org/1.ogg" to FakeAnkiHttp.Reply.status(404),
            "https://upload.wikimedia.org/us.ogg" to FakeAnkiHttp.Reply.audio(byteArrayOf(9), "audio/ogg")
        )

        val result = download(
            http,
            sources = listOf(AnkiAudioSource(AnkiAudioSourceType.WIKTIONARY)),
            term = "cat",
            reading = "",
            language = "en"
        )

        assertArrayEquals(byteArrayOf(9), (result as AnkiAudioResult.Audio).data)
        assertEquals(
            listOf(
                search,
                fileInfo(titles[0]),
                "https://upload.wikimedia.org/1.ogg",
                fileInfo(titles[1]),
                "https://upload.wikimedia.org/us.ogg"
            ),
            http.requests.map { it.url }
        )
    }

    @Test
    fun invalidCustomUrlIsNoAudioNotOffline() {
        val custom = AnkiAudioSource(AnkiAudioSourceType.CUSTOM, "{term}")
        val http = FakeAnkiHttp("食べる" to FakeAnkiHttp.Reply.failing(IllegalArgumentException("Invalid URL")))

        assertSame(AnkiAudioResult.NoAudio, download(http, sources = listOf(custom)))
    }
}
