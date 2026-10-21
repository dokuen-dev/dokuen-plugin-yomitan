package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiUrlsTest {

    @Test
    fun toAsciiEncodesWhatABrowserEncodes() {
        assertEquals(
            "https://jisho.org/search/%E9%A3%9F%E3%81%B9%E3%82%8B",
            AnkiUrls.toAscii("https://jisho.org/search/食べる")
        )
        assertEquals("https://x.org/a%20b%22c", AnkiUrls.toAscii("https://x.org/a b\"c"))
    }

    @Test
    fun toAsciiLeavesEscapesAndQuerySyntaxAlone() {
        val url = "https://x.org/w/api.php?srsearch=intitle:/-chat.wav/i+x&titles=%41&origin=*"
        assertEquals(url, AnkiUrls.toAscii(url))
    }

    @Test
    fun formEncodeMatchesUrlSearchParams() {
        assertEquals(
            "post=dictionary_reference&search_query=%E9%A3%9F%E3%81%B9%E3%82%8B&q=a+b%26c",
            AnkiUrls.formEncode(
                linkedMapOf(
                    "post" to "dictionary_reference",
                    "search_query" to "食べる",
                    "q" to "a b&c"
                )
            )
        )
    }

    @Test
    fun resolveHandlesRelativeAndProtocolRelativeUrls() {
        assertEquals(
            "https://d1vjc5dkcd3yh2.cloudfront.net/audio/x.mp3",
            AnkiUrls.resolve("//d1vjc5dkcd3yh2.cloudfront.net/audio/x.mp3", "https://jisho.org/search/x")
        )
        assertEquals(
            "https://www.japanesepod101.com/audio/a.mp3",
            AnkiUrls.resolve("/audio/a.mp3", "https://www.japanesepod101.com/learningcenter/reference/dictionary_post")
        )
        assertEquals("https://cdn.example/b.mp3", AnkiUrls.resolve("https://cdn.example/b.mp3", "https://x.org/"))
    }
}
