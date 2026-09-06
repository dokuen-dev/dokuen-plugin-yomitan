package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTextUtilTest {

    @Test
    fun testTypedTagsAreShownOneSpaceApart() {
        // What the original's settings page shows once the field loses focus.
        val tags = TagsTextUtil.parseTags("jlpt-n3, vocab;core2k listening")
        assertEquals(listOf("jlpt-n3", "vocab", "core2k", "listening"), tags)
        assertEquals("jlpt-n3 vocab core2k listening", TagsTextUtil.formatTags(tags))
        assertEquals("", TagsTextUtil.formatTags(emptyList()))
    }

    @Test
    fun testParseTagsSplitsAndTrims() {
        val result = TagsTextUtil.parseTags("a, b,c ,  d")
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun testParseTagsDropsEmptyEntriesFromTrailingComma() {
        val result = TagsTextUtil.parseTags("a, b, ")
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun testParseTagsOnEmptyStringReturnsEmptyList() {
        assertTrue(TagsTextUtil.parseTags("").isEmpty())
    }

    @Test
    fun testParseTagsSplitsOnSpaceOnly() {
        val result = TagsTextUtil.parseTags("a b c")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun testParseTagsSplitsOnMixedSpaceAndComma() {
        val result = TagsTextUtil.parseTags("a b,c  , d")
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun testParseTagsSplitsOnSemicolon() {
        val result = TagsTextUtil.parseTags("a;b; c ;d")
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun testShouldResyncFromUpstreamFalseWhenParsedLocalMatchesUpstream() {
        assertFalse(TagsTextUtil.shouldResyncFromUpstream("a, b", listOf("a", "b")))
    }

    @Test
    fun testShouldResyncFromUpstreamFalseWhileMidTypingNewTag() {
        // User has typed "a, b, " (trailing comma+space, about to type a third tag).
        // It parses down to ["a", "b"], which matches upstream, so we must NOT resync
        // and clobber what they're typing.
        assertFalse(TagsTextUtil.shouldResyncFromUpstream("a, b, ", listOf("a", "b")))
    }

    @Test
    fun testShouldResyncFromUpstreamTrueWhenUpstreamGenuinelyDiffers() {
        // Upstream just loaded fresh values that don't match what's currently in the text field.
        assertTrue(TagsTextUtil.shouldResyncFromUpstream("a, b", listOf("x", "y")))
    }

    @Test
    fun testShouldResyncFromUpstreamTrueWhenLocalTextIsEmptyButUpstreamHasTags() {
        assertTrue(TagsTextUtil.shouldResyncFromUpstream("", listOf("a")))
    }
}
