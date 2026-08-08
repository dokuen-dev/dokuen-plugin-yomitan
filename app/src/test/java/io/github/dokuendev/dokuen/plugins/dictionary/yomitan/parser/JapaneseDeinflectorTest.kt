package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JapaneseDeinflectorTest {

    private val deinflector = JapaneseDeinflector()

    @Test
    fun testDeinflectSpotCheck() {
        // This case should be covered by testAllYomitanTransforms below. This is a second check in case there's an
        // issue with loading or parsing the test resource file. I.e., if both tests fail then we know it's a bug in
        // the deinflector, and if only one fails then we know it's a test harness issue.
        val godan = deinflector.conditionFlagsFromConditionType("v5")
        val result = deinflector.deinflect("言われました").find {
            it.term == "言う" && JapaneseDeinflector.conditionsMatch(it.conditions, godan)
        }
        assertTrue("Should deinflect '言われました' to the godan verb '言う'", result != null)
        assertEquals(listOf("passive", "-ます", "-た"), result!!.transformIds)
        assertEquals(listOf("-れる", "-ます", "-た"), result.rules.map { it.name })
    }

    @Test
    fun testIchidanPassiveIsNotTheGodanPassive() {
        // 食べさせる is an ichidan verb, so its passive is -られる ("potential or passive"), never the godan
        // -れる: the godan passive's result is a godan verb, which the causative cannot have produced.
        val ichidan = deinflector.conditionFlagsFromConditionType("v1")
        val chains = deinflector.deinflect("食べさせられた")
            .filter { it.term == "食べる" && JapaneseDeinflector.conditionsMatch(it.conditions, ichidan) }
            .map { it.transformIds }
        assertEquals(listOf(listOf("causative", "potential or passive", "-た")), chains)
    }

    /**
     * Runs the original Yomitan's own deinflection test cases (test/language/japanese-transforms.test.js) the
     * way its harness (test/fixtures/language-transformer-test.js, hasTermReasons) does: a case with
     * `valid: true` passes only if some result has the expected term, a word class matching the case's `rule`
     * (when it gives one) and exactly the expected chain of transforms, innermost first (when it gives one);
     * a case with `valid: false` passes only if no such result exists.
     */
    @Test
    fun testAllYomitanTransforms() {
        val testStream =
            JapaneseDeinflectorTest::class.java.getResourceAsStream("/testcases/japanese-transforms.test.js")
        if (testStream == null) {
            fail("Yomitan test file '/testcases/japanese-transforms.test.js' not found in resources.")
            return
        }

        val categoryRegex = """category:\s*'([^']*)'""".toRegex()
        val validRegex = """valid:\s*(true|false)""".toRegex()
        val testRegex =
            """term:\s*'([^']*)',\s*source:\s*'([^']*)',\s*rule:\s*(null|'[^']*'),\s*reasons:\s*(null|\[[^\]]*\])"""
                .toRegex()
        val quotedRegex = """'([^']*)'""".toRegex()

        var category = ""
        var valid = true
        var testCount = 0
        val failures = mutableListOf<String>()

        for (line in testStream.bufferedReader().readLines()) {
            categoryRegex.find(line)?.let { category = it.groupValues[1] }
            validRegex.find(line)?.let { valid = it.groupValues[1] == "true" }
            val match = testRegex.find(line) ?: continue
            val (term, source, ruleText, reasonsText) = match.destructured
            testCount++

            val rule = quotedRegex.find(ruleText)?.groupValues?.get(1)
            val reasons =
                if (reasonsText == "null") null else quotedRegex.findAll(reasonsText).map { it.groupValues[1] }.toList()
            val ruleFlags = rule?.let { deinflector.conditionFlagsFromConditionType(it) }
            val results = deinflector.deinflect(source).filter { it.term == term }
            val has = results.any { result ->
                (ruleFlags == null || JapaneseDeinflector.conditionsMatch(result.conditions, ruleFlags)) &&
                        (reasons == null || result.transformIds == reasons)
            }
            if (has == valid) continue

            val got = results.joinToString(" | ") { it.transformIds.joinToString(" « ") }.ifEmpty { "(term not found)" }
            failures.add(
                "[$category] $source -> $term ($rule): ${if (valid) "expected" else "must not have"} " +
                        "${reasons?.joinToString(" « ") ?: "any chain"}, got $got"
            )
        }

        println("Ran $testCount deinflection tests from Yomitan suite. Failures: ${failures.size}")
        failures.forEach { println("  $it") }
        assertTrue("No test cases were parsed", testCount > 0)
        if (failures.isNotEmpty()) fail("${failures.size} / $testCount deinflection tests failed!")
    }
}
