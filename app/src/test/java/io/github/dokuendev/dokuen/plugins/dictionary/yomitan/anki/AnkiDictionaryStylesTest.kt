package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected strings follow the original's addScopeToCssLegacy (ext/js/core/utilities.js) as
 * used by anki-note-data-creator.js: glossaryScoped is scoped to `.yomitan-glossary`, and
 * dictionaryScoped to `.yomitan-glossary [data-dictionary="<title>"]`.
 */
class AnkiDictionaryStylesTest {

    private fun dictionaryScoped(css: String, dictionary: String = "Jitendex") =
        AnkiDictionaryStyles.scope(css, dictionary).dictionaryScoped

    private fun glossaryScoped(css: String) = AnkiDictionaryStyles.scope(css, "Jitendex").glossaryScoped

    @Test
    fun testScopesEachSelectorOfEachRule() {
        val css = "span[title] {\n    cursor: help;\n}\n\nli.a,\nli.b {\n    padding-left: 0.25em;\n}\n"
        assertEquals(
            ".yomitan-glossary [data-dictionary=\"Jitendex\"] span[title] { cursor: help; }\n" +
                    ".yomitan-glossary [data-dictionary=\"Jitendex\"] li.a, " +
                    ".yomitan-glossary [data-dictionary=\"Jitendex\"] li.b { padding-left: 0.25em; }",
            dictionaryScoped(css)
        )
        assertEquals(
            ".yomitan-glossary span[title] { cursor: help; }\n" +
                    ".yomitan-glossary li.a, .yomitan-glossary li.b { padding-left: 0.25em; }",
            glossaryScoped(css)
        )
    }

    @Test
    fun testNestedRulesStayInsideTheirScopedParent() {
        val css = "li[data-sc-content=\"sense\"] {\n  padding-left: 0.25em;\n" +
                "  & ul[data-sc-content=\"glossary\"] { list-style-type: none; }\n}"
        assertEquals(
            ".yomitan-glossary li[data-sc-content=\"sense\"] { padding-left: 0.25em; " +
                    "& ul[data-sc-content=\"glossary\"] { list-style-type: none; } }",
            glossaryScoped(css)
        )
    }

    @Test
    fun testTopLevelAtRulesAreDropped() {
        val css = "@charset \"UTF-8\";\n@import url(\"x.css\");\n" +
                "@media (max-width: 10px) { a { color: red; } }\n" +
                "@font-face { font-family: X; src: url(x.woff); }\n" +
                "b { color: blue; }"
        assertEquals(".yomitan-glossary b { color: blue; }", glossaryScoped(css))
    }

    @Test
    fun testOnlyAtRulesOrNoCssProduceNoStyles() {
        assertEquals(AnkiScopedStyles(), AnkiDictionaryStyles.scope("", "Jitendex"))
        assertEquals(AnkiScopedStyles(), AnkiDictionaryStyles.scope("  \n", "Jitendex"))
        assertEquals(AnkiScopedStyles(), AnkiDictionaryStyles.scope("@media print { a { color: red; } }", "Jitendex"))
        assertEquals(AnkiScopedStyles(), AnkiDictionaryStyles.scope("/* only a comment */", "Jitendex"))
    }

    @Test
    fun testCommentsAreRemovedButNotInsideStrings() {
        val css = "/* header */ a /* x */ { content: \"/* kept */\"; /* gone */ color: red; }"
        assertEquals(".yomitan-glossary a { content: \"/* kept */\"; color: red; }", glossaryScoped(css))
    }

    @Test
    fun testWhitespaceImportantAndCombinatorsAreNormalized() {
        val css = "div>span+b~i{COLOR :  red  !  IMPORTANT ;margin:0 auto}"
        assertEquals(
            ".yomitan-glossary div > span + b ~ i { color: red !important; margin: 0 auto; }",
            glossaryScoped(css)
        )
    }

    @Test
    fun testAttributeValuesAndPseudoClassArgumentsAreLeftAsWritten() {
        assertEquals(
            ".yomitan-glossary li:nth-child(2n+1) { color: red; }",
            glossaryScoped("li:nth-child(2n+1) { color: red; }")
        )
        assertEquals(
            ".yomitan-glossary [data-x~=\"a>b\"] { color: red; }",
            glossaryScoped("[data-x~=\"a>b\"] { color: red; }")
        )
    }

    @Test
    fun testSelectorListIsSplitOnlyAtTopLevelCommas() {
        // The original splits at every comma, breaking both of these selectors.
        assertEquals(
            ".yomitan-glossary :is(a, b) { color: red; }\n.yomitan-glossary [title=\"a,b\"] { color: red; }",
            glossaryScoped(":is(a, b) { color: red; }\n[title=\"a,b\"] { color: red; }")
        )
    }

    @Test
    fun testDictionaryTitleIsEscapedInTheAttributeSelector() {
        assertEquals(
            ".yomitan-glossary [data-dictionary=\"A \\\"B\\\" \\\\ C\"] a { color: red; }",
            dictionaryScoped("a { color: red; }", "A \"B\" \\ C")
        )
    }

    @Test
    fun testCustomPropertiesKeepTheirCaseAndInvalidDeclarationsAreDropped() {
        assertEquals(
            ".yomitan-glossary a { --Main-Color: #FFF; color: var(--Main-Color); }",
            glossaryScoped("a { --Main-Color: #FFF; no-colon; color: var(--Main-Color); empty: ; }")
        )
    }

    @Test
    fun testEmptyRuleAndUnterminatedCssDoNotBreakParsing() {
        assertEquals(".yomitan-glossary a { }", glossaryScoped("a {}"))
        assertEquals(".yomitan-glossary a { color: red; }", glossaryScoped("} a { color: red"))
    }

    /** Jitendex's own styles.css (from jitendex-yomitan), shared with the renderer tests. */
    private fun jitendexCss(): String =
        javaClass.classLoader!!.getResource("testcases/term_rendering/styles.css")!!.readText()

    private val jitendexPrefix = ".yomitan-glossary [data-dictionary=\"Jitendex\"] "

    @Test
    fun testJitendexDoublyNestedRuleIsWrittenExactly() {
        // Two selectors, a comment with quotes before the rule, and a rule nested two levels deep.
        assertEquals(
            "${jitendexPrefix}div[data-sc-content=\"forms\"], ${jitendexPrefix}li[data-sc-content=\"forms\"] { " +
                    "& ul { font-size: 1.2em; list-style-type: circle; } " +
                    "& table { margin-top: 0.2em; } " +
                    "& tr { border-width: 1px; } " +
                    "& th { border-width: 1px; font-weight: normal; } " +
                    "& td { border-width: 1px; text-align: center; " +
                    "& span { clip-path: circle(); display: block; font-weight: bold; padding: 0 0.5em; } } }",
            dictionaryScoped(jitendexCss()).lines()
                .single { it.startsWith("${jitendexPrefix}div[data-sc-content=\"forms\"]") }
        )
    }

    @Test
    fun testJitendexPseudoElementRuleIsWrittenExactly() {
        assertEquals(
            "${jitendexPrefix}td[data-sc-class=\"form-pri\"] > span { color: white; " +
                    "background: radial-gradient(green 50%, white 100%); &::before { content: \"\u25B3\"; } }",
            dictionaryScoped(jitendexCss()).lines()
                .single { it.startsWith("${jitendexPrefix}td[data-sc-class=\"form-pri\"]") }
        )
    }

    @Test
    fun testRealDictionaryStylesheetScopesEveryTopLevelRule() {
        val scoped = dictionaryScoped(jitendexCss()).lines()
        assertTrue(scoped.isNotEmpty())
        for (rule in scoped) {
            for (selector in rule.substringBefore(" {").split(", ")) {
                assertTrue(
                    "Unscoped selector in: $rule",
                    selector.startsWith(".yomitan-glossary [data-dictionary=\"Jitendex\"] ")
                )
            }
            assertTrue("Unbalanced rule: $rule", rule.count { it == '{' } == rule.count { it == '}' })
        }
        assertTrue(scoped.any { it.contains("& ul[data-sc-content=\"glossary\"] { list-style-type: none;") })
        assertFalse(scoped.any { it.contains("/*") })
    }
}
