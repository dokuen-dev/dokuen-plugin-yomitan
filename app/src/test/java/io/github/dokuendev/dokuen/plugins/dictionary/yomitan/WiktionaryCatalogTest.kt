package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiktionaryCatalogTest {

    private val base = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict"

    // A trimmed copy of the release metadata's shape, with "db" and the totals left out of most nodes.
    private val catalog = WiktionaryCatalog.parse(
        """
        {
          "date": "2026-10-22",
          "db": { "ja": { "size": "354.7 MB" } },
          "dicts": {
            "glossary": {
              "size": "235.1 MB",
              "sources": { "ja": { "targets": { "af": "31.4 KB" } } }
            },
            "ipa": {
              "sources": { "ja": { "targets": { "de": "11.2 KB", "en": "1.1 MB" } } }
            },
            "ipa-merged": {
              "sources": { "all": { "targets": { "ja": "1.7 MB" } } }
            },
            "main": {
              "sources": {
                "ja": { "size": "42.5 MB", "count": 2, "targets": { "de": "38.9 KB", "en": "15.9 MB" } },
                "zlw-ocs": { "targets": { "en": "20.0 KB" } }
              }
            }
          }
        }
        """
    )

    @Test
    fun targetsComeFromEveryPairType() {
        assertEquals(setOf("af", "de", "en"), catalog.availableTargets("ja"))
    }

    @Test
    fun theMergedIpaDictionariesDoNotAddTargets() {
        assertTrue(catalog.availableTargets("all").isEmpty())
    }

    @Test
    fun aPairWithoutPairDictionariesHasOnlyTheSourcesMergedIpa() {
        assertEquals(
            listOf(WiktionaryDictionaryType.IPA_MERGED),
            catalog.dictionariesFor("ja", "fr").map { it.type }
        )
        assertTrue(catalog.dictionariesFor("zlw-ocs", "de").isEmpty())
    }

    @Test
    fun dictionariesForAPairAreInDisplayOrderWithTheirTitlesAndUrls() {
        assertEquals(
            listOf(
                WiktionaryDictionary(
                    WiktionaryDictionaryType.MAIN,
                    "wty-ja-en",
                    "15.9 MB",
                    "$base/ja/en/wty-ja-en.zip"
                ),
                WiktionaryDictionary(
                    WiktionaryDictionaryType.IPA,
                    "wty-ja-en-ipa",
                    "1.1 MB",
                    "$base/ja/en/wty-ja-en-ipa.zip"
                ),
                WiktionaryDictionary(
                    WiktionaryDictionaryType.IPA_MERGED,
                    "wty-ja-ipa",
                    "1.7 MB",
                    "$base/all/ja/wty-ja-ipa.zip"
                )
            ),
            catalog.dictionariesFor("ja", "en")
        )
    }

    @Test
    fun glossaryDictionariesUseTheGlossSuffix() {
        assertEquals(
            listOf("$base/ja/af/wty-ja-af-gloss.zip", "$base/all/ja/wty-ja-ipa.zip"),
            catalog.dictionariesFor("ja", "af").map { it.url }
        )
    }

    @Test
    fun hyphenatedLanguageCodesAreKeptWhole() {
        assertEquals(
            listOf("$base/zlw-ocs/en/wty-zlw-ocs-en.zip"),
            catalog.dictionariesFor("zlw-ocs", "en").map { it.url }
        )
    }

    @Test
    fun everyLanguageCodeIsUnique() {
        val codes = WiktionaryDictionaries.LANGUAGES.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(WiktionaryDictionaries.DEFAULT_SOURCE in codes)
    }
}
