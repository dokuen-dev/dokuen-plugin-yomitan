package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiFieldChecksumTest {

    // Expected values are the first 32 bits of the SHA-1 of the stripped text, computed
    // independently (Python's hashlib).

    @Test
    fun checksumIsTheFirst32BitsOfTheSha1() {
        assertEquals(567984260L, AnkiFieldChecksum.checksum("食べる"))
        assertEquals(3084630053L, AnkiFieldChecksum.checksum("手紙\""))
    }

    @Test
    fun checksumIgnoresMarkup() {
        assertEquals(567984260L, AnkiFieldChecksum.checksum("<b>食べる</b>"))
        assertEquals(567984260L, AnkiFieldChecksum.checksum("食べる<!-- note --><style>b { color: red; }</style>"))
        assertEquals(2463288064L, AnkiFieldChecksum.checksum("a &amp; b"))
        assertEquals(1943979411L, AnkiFieldChecksum.checksum("x&nbsp;y"))
    }

    @Test
    fun mediaTagsKeepTheirFileName() {
        assertEquals(1137858730L, AnkiFieldChecksum.checksum("<img src=\"file.jpg\">"))
        assertEquals(1137858730L, AnkiFieldChecksum.checksum("<img alt='x' src='file.jpg' width=\"1\">"))
    }

    @Test
    fun strippingDecodesCharacterReferences() {
        assertEquals(
            "<a> \"b\" 'c' é 𠮷", AnkiFieldChecksum.stripHtmlPreservingMediaFilenames(
                "&lt;a&gt; &quot;b&quot; &apos;c&apos; &#233; &#x20BB7;"
            )
        )
        assertEquals("&unknown;", AnkiFieldChecksum.stripHtmlPreservingMediaFilenames("&unknown;"))
    }
}
