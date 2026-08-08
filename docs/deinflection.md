# Deinflection

Looking up a conjugated word (食べさせられた) finds its dictionary form (食べる) by undoing
conjugations, as the original Yomitan does. The rules and the algorithm are the original's, ported
as directly as possible, so a lookup finds the same entries through the same conjugations.

## Where it lives

| File | Responsibility |
|---|---|
| `parser/JapaneseTransforms.kt` | The original's Japanese rules (`ext/js/language/ja/japanese-transforms.js`): its word classes ("conditions") and, for each conjugation ("transform"), its key, English and Japanese names, English description and rules. **Generated; do not edit.** The header names the original's commit it was generated from. |
| `parser/JapaneseDeinflector.kt` | The data types the generated file uses, and a port of the original's `LanguageTransformer` (`ext/js/language/language-transformer.js`). |
| `scripts/generate_japanese_transforms.mjs` | Writes `JapaneseTransforms.kt`. |
| `YomitanDictionaryPluginService.kt` | Looks up what the deinflector produces and shows the chain of conjugations above the entry. |

To update the rules to a newer original, run, from this repository's root and with Node:

```
node scripts/generate_japanese_transforms.mjs <path to a Yomitan checkout>
```

The original builds most of its rules with helper functions, so the script loads its file and reads
the resulting data rather than parsing the source. It stops with an error on anything the generated
format cannot hold, such as a rule type other than the suffix and whole-word rules Japanese uses.

## How it works

Every rule undoes one ending (a suffix, or a whole word), and carries two sets of word classes: the
classes the text must have for the rule to apply (`conditionsIn`), and the class the result has
(`conditionsOut`). The passive rule that turns 取られる into 取る, for example, applies to an ichidan
verb and produces a godan verb. A class can stand for several others (`v` is every kind of verb), and
each class becomes a bit, so a set of classes is a bit mask.

`JapaneseDeinflector.deinflect` works breadth-first from the text as given, which has no class (0) and
so matches every rule. It applies every rule whose `conditionsIn` shares a class with the current
text's, skipping a rule that would repeat itself on the same text, as the original does. It returns
every path, not one per text: the same text can be reached through different conjugations, and so
with different classes. Each result carries its classes, the original's keys of the conjugations
undone (innermost first, as in the original's traces), and their names and descriptions.

The lookup groups the results by text and looks each text up once. An entry is only a match through a
path whose classes it allows: its parts of speech (the dictionary's rule identifiers, such as `v1` or
`adj-i`) are turned into the same bits, and the two must share one, unless the path has no class
(the text as given, which matches any entry). So a noun, which has no such part of speech, is never
reached through a verb ending, and 食べさせられた reaches the ichidan verb 食べる through
causative, then "potential or passive", then -た, never through the godan passive. This is the
original's `_matchEntriesToDeinflections` with its default `partsOfSpeechFilter`.

## How the chain is shown

Above a deinflected entry, the conjugations undone are shown innermost first, joined with `«`, as in
the original: 食べさせられた shows `-せる・させる « -られる « -た`. Each name has the original's
English description as its hover text.

The names are the original's Japanese ones, which it shows when its interface is in Japanese, with
`-` in place of their `～` (`～れる` becomes `-れる`), or its English name for a conjugation with no
Japanese one. They are more compact than the English names (`potential or passive`), which suits the
mobile layout. Several of the original's conjugations share a Japanese name, so they also share one
here: every Kansai-ben conjugation is `関西弁`.

## Differences from the original

- **One chain per entry.** When several paths reach the same entry, the original lists every chain;
  this port shows only the first, which is the shortest (the deinflector is breadth-first).
- **The part-of-speech filter is always on.** The original lets it be turned off per dictionary
  (`partsOfSpeechFilter`, on by default); this port has no such setting.

## Tests

`JapaneseDeinflectorTest.testAllYomitanTransforms` runs the original's own test cases
(`test/language/japanese-transforms.test.js`, copied to `app/src/test/resources/testcases/`) the way
its harness does (`hasTermReasons` in `test/fixtures/language-transformer-test.js`): a valid case
passes only if some result has the expected text, a class matching the expected one, and exactly
the expected chain; an invalid case passes only if none does.
