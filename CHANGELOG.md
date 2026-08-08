# Changelog

## [1.1.1]

### Fixed

- Deinflection now follows the original Yomitan extension's rules, including their word-class
  conditions:
    - Conjugated forms are labelled correctly (e.g. 食べさせられた is now "potential or passive"
      (-られる) rather than the godan passive (-れる)).
    - Impossible conjugation chains are no longer accepted.
    - A conjugated form only matches entries whose parts of speech allow it, so conjugated lookups
      no longer match nouns or other unrelated words.
    - Added rules that were previously missing (the のたまう-type -て/-た forms, -ふ and -やがる).
- Deinflection chains are shown innermost first, joined with "«", as in the original extension, and
  hover descriptions come from the original's rule data.

## [1.1.0]

### Added

- Support for frequency dictionaries:
    - Import standalone Yomitan frequency archives (e.g. BCCWJ, JPDBv2, Jiten) to display
      per-dictionary frequency badges inline with definitions.
    - Optionally choose an installed frequency dictionary to sort lookup results by, with automatic
      detection of rank-based vs. occurrence-based frequency data.
    - Streaming bank parser in `DictionaryImporter` for importing frequency-only dictionaries.
    - Frequency badge rendering in `TermEntryRenderer` and `KanjiEntryRenderer`.
    - Download presets for BCCWJ, JPDBv2, and Jiten.

## [1.0.0]

### Added

- Initial release of the Yomitan dictionary plugin for Dokuen.
- Full import of Yomitan term, kanji, and tag banks from ZIP archives.
- Rich HTML-to-StyledText rendering of structured glossary content including definitions, lists,
  bullet points, tags, and CSS styles.
- Interactive kanji links: tapping a character in a headword opens the kanji entry with stroke order
  diagram and kanji stats.
- Compose-based management UI with dark-mode theme for installing presets, importing local ZIPs,
  downloading via custom URLs, and reordering or deleting dictionaries.
