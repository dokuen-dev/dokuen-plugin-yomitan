package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.parser

/**
 * A word class a rule applies to or produces, such as `v1` (ichidan verb) or `-て` (a form that only
 * some later rules accept). A condition with sub-conditions stands for all of them.
 */
internal data class TransformCondition(
    val id: String,
    /** Whether a dictionary entry's part of speech can name this condition. */
    val isDictionaryForm: Boolean,
    /** Space-separated ids of the conditions this one stands for; empty if it has none. */
    val subConditions: String
)

/** One conjugation, such as the causative, with the rules that undo it. */
internal data class Transform(
    /** The original's key for it, which its test cases and inflection rule chains name. */
    val id: String,
    /** The original's English name. */
    val name: String,
    /** The original's Japanese name, shown when its interface is in Japanese, if it has one. */
    val japaneseName: String?,
    /** The original's English description. */
    val description: String?,
    val rules: List<TransformRule>
) {
    /**
     * The name this port shows: the original's Japanese name, written with `-` in place of its `～`
     * (`～れる` becomes `-れる`), or its English name when it has no Japanese one. These are the
     * more compact of the two.
     */
    val displayName: String get() = japaneseName?.replace('～', '-') ?: name
}

/**
 * Undoes one ending: text ending in [inflected] (or, for a whole-word rule, text equal to it) has it
 * replaced with [deinflected]. The rule only applies to text whose word class is one of
 * [conditionsIn] (space-separated), and the result's word class is [conditionsOut].
 */
internal data class TransformRule(
    val wholeWord: Boolean,
    val inflected: String,
    val deinflected: String,
    val conditionsIn: String,
    val conditionsOut: String
)

internal fun suffix(inflected: String, deinflected: String, conditionsIn: String, conditionsOut: String) =
    TransformRule(false, inflected, deinflected, conditionsIn, conditionsOut)

internal fun wholeWord(inflected: String, deinflected: String, conditionsIn: String, conditionsOut: String) =
    TransformRule(true, inflected, deinflected, conditionsIn, conditionsOut)

/** One step of an inflection rule chain, as shown to the user. */
data class InflectionRule(val name: String, val description: String?)

data class DeinflectionResult(
    val term: String,
    /**
     * The word classes [term] can have, as flags ([JapaneseDeinflector.conditionFlagsFromPartsOfSpeech]
     * turns a dictionary entry's parts of speech into the same flags). 0 for the text as given, which
     * any entry matches.
     */
    val conditions: Int,
    /** The original's keys of the conjugations undone to reach [term], innermost first. */
    val transformIds: List<String>,
    /** The same conjugations as shown to the user, innermost first. */
    val rules: List<InflectionRule>
)

/**
 * A port of the original's deinflector (LanguageTransformer in ext/js/language/language-transformer.js)
 * with its Japanese rules (ext/js/language/ja/japanese-transforms.js, generated into
 * [JapaneseTransforms]).
 */
class JapaneseDeinflector {

    private class CompiledRule(val rule: TransformRule, val conditionsIn: Int, val conditionsOut: Int)

    private class CompiledTransform(val transform: Transform, val rules: List<CompiledRule>)

    private class TraceFrame(val transform: CompiledTransform, val ruleIndex: Int, val text: String)

    private class TransformedText(val text: String, val conditions: Int, val trace: List<TraceFrame>)

    private val conditionFlags: Map<String, Int> = conditionFlagsMap(JapaneseTransforms.conditions)

    private val partOfSpeechFlags: Map<String, Int> =
        JapaneseTransforms.conditions.filter { it.isDictionaryForm }
            .associate { it.id to conditionFlags.getValue(it.id) }

    private val transforms: List<CompiledTransform> = JapaneseTransforms.transforms.map { transform ->
        CompiledTransform(
            transform,
            transform.rules.map { rule -> CompiledRule(rule, flags(rule.conditionsIn), flags(rule.conditionsOut)) }
        )
    }

    private fun flags(conditionIds: String): Int =
        conditionIds.split(' ').filter { it.isNotEmpty() }.fold(0) { flags, id ->
            flags or (conditionFlags[id] ?: error("Unknown condition $id"))
        }

    /** The flags of the word class named [conditionType], such as `v1`; 0 for an unknown one. */
    fun conditionFlagsFromConditionType(conditionType: String): Int = conditionFlags[conditionType] ?: 0

    /** The flags of a dictionary entry's space-separated parts of speech; unknown ones add nothing. */
    fun conditionFlagsFromPartsOfSpeech(partsOfSpeech: String): Int =
        partsOfSpeech.split(' ').fold(0) { flags, partOfSpeech -> flags or (partOfSpeechFlags[partOfSpeech] ?: 0) }

    /**
     * Every text [word] can be deinflected to, one result per path, starting with [word] itself: the
     * same text can appear more than once, reached through different conjugations and so with
     * different word classes. Like the original's LanguageTransformer.transform, it breadth-first applies
     * every rule whose conditions match, and skips a rule that would repeat itself on the same text.
     */
    fun deinflect(word: String): List<DeinflectionResult> {
        val results = mutableListOf(TransformedText(word, 0, emptyList()))
        var i = 0
        while (i < results.size) {
            val current = results[i++]
            val text = current.text
            for (transform in transforms) {
                transform.rules.forEachIndexed { ruleIndex, compiled ->
                    if (!conditionsMatch(current.conditions, compiled.conditionsIn)) return@forEachIndexed
                    val rule = compiled.rule
                    val deinflected = when {
                        rule.wholeWord -> if (text == rule.inflected) rule.deinflected else null
                        text.endsWith(rule.inflected) ->
                            text.substring(0, text.length - rule.inflected.length) + rule.deinflected

                        else -> null
                    } ?: return@forEachIndexed
                    val isCycle =
                        current.trace.any { it.transform === transform && it.ruleIndex == ruleIndex && it.text == text }
                    if (isCycle) return@forEachIndexed
                    val trace = listOf(TraceFrame(transform, ruleIndex, text)) + current.trace
                    results.add(TransformedText(deinflected, compiled.conditionsOut, trace))
                }
            }
        }
        return results.map { result ->
            DeinflectionResult(
                term = result.text,
                conditions = result.conditions,
                transformIds = result.trace.map { it.transform.transform.id },
                rules = result.trace.map {
                    InflectionRule(
                        it.transform.transform.displayName,
                        it.transform.transform.description
                    )
                }
            )
        }
    }

    companion object {
        /**
         * Whether text with word classes [currentConditions] can be what [nextConditions] asks for: any
         * text matches when [currentConditions] is 0 (the text as given), otherwise they must share one.
         */
        fun conditionsMatch(currentConditions: Int, nextConditions: Int): Boolean =
            currentConditions == 0 || (currentConditions and nextConditions) != 0

        /**
         * One flag per condition without sub-conditions, and for each other condition the flags of its
         * sub-conditions, as the original's _getConditionFlagsMap assigns them.
         */
        private fun conditionFlagsMap(conditions: List<TransformCondition>): Map<String, Int> {
            val flags = mutableMapOf<String, Int>()
            var nextFlagIndex = 0
            var targets = conditions
            while (targets.isNotEmpty()) {
                val nextTargets = mutableListOf<TransformCondition>()
                for (condition in targets) {
                    val subConditions = condition.subConditions.split(' ').filter { it.isNotEmpty() }
                    if (subConditions.isEmpty()) {
                        check(nextFlagIndex < Int.SIZE_BITS) { "Maximum number of conditions was exceeded" }
                        flags[condition.id] = 1 shl nextFlagIndex++
                    } else if (subConditions.all { it in flags }) {
                        flags[condition.id] = subConditions.fold(0) { value, id -> value or flags.getValue(id) }
                    } else {
                        nextTargets.add(condition)
                    }
                }
                check(nextTargets.size < targets.size) { "Cycle in sub-conditions of ${nextTargets.map { it.id }}" }
                targets = nextTargets
            }
            return flags
        }
    }
}
