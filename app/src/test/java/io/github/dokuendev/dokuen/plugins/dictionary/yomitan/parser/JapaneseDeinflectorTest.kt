package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser

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
        val results = deinflector.deinflect("言われました")
        val candidates = results.map { it.term }
        assertTrue("Should deinflect '言われました' to '言う'", candidates.contains("言う"))
    }

    @Test
    fun testAllYomitanTransforms() {
        val testStream =
            JapaneseDeinflectorTest::class.java.getResourceAsStream("/testcases/japanese-transforms.test.js")
        if (testStream == null) {
            fail("Yomitan test file '/testcases/japanese-transforms.test.js' not found in resources.")
            return
        }

        val lines = testStream.bufferedReader().readLines()
        val validRegex = """valid:\s*(true|false)""".toRegex()
        val testRegex = """term:\s*'([^']*)',\s*source:\s*'([^']*)'""".toRegex()
        var currentValid = true
        var testCount = 0
        var failCount = 0
        val failures = mutableListOf<String>()

        for (line in lines) {
            val validMatch = validRegex.find(line)
            if (validMatch != null) {
                currentValid = validMatch.groupValues[1] == "true"
            }

            if (!currentValid) continue

            val match = testRegex.find(line) ?: continue
            val term = match.groupValues[1]
            val source = match.groupValues[2]

            if (term.isEmpty() || source.isEmpty()) continue

            testCount++
            val results = deinflector.deinflect(source)
            val candidates = results.map { it.term }
            val hasCandidate = candidates.contains(term)

            if (!hasCandidate) {
                failCount++
                if (failures.size < 50) {
                    failures.add("Source: '$source' -> Expected: '$term' (not in $candidates)")
                }
            }
        }

        println("Ran $testCount deinflection tests from Yomitan suite. Failures: $failCount")
        if (failures.isNotEmpty()) {
            println("First 50 failures:")
            failures.forEach { println("  $it") }
            fail("$failCount / $testCount deinflection tests failed!")
        }
    }
}
