# Changelog

## [1.3.0]

### Added

- More supported Anki field markers (requires Dokuen version 1.6.0 or newer):
    - Image and clipboard fields: `{screenshot}`, `{clipboard-image}`, and `{clipboard-text}`.
      Images require Dokuen Pro; without it, the note is still written but with the image fields
      empty.
    - `{sentence-furigana}` and `{sentence-furigana-plain}` now include the sentence's readings,
      as ruby HTML and Anki's bracket notation, respectively. (Previously both wrote the plain
      sentence without readings.)
- A new "Images" settings card selects the screenshot behavior, the image format (PNG or JPEG), and
  the JPEG quality.

### Changed

- After an Anki export with a warning, the warning is now shown along with the relabeled action
  links, rather than in place of them.

## [1.2.0]

### Added

- Custom Anki Export: export term and kanji lookups to AnkiDroid using configurable card formats.
    - Free-text `{marker}` field templates recognizing the full standard marker set from the
      original Yomitan extension (expression/reading/furigana, glossary variants, tags,
      frequencies, sentence/cloze, kanji fields), with graceful empty-string degradation for
      markers that the Dokuen API doesn't have backing subsystems for yet (audio, screenshot,
      pitch accents, etc.).
    - Context-aware sentence and cloze extraction (`{sentence}`, `{cloze-prefix}`, `{cloze-body}`,
      `{cloze-body-kana}`, `{cloze-suffix}`) from OCR context blocks, with kanji headword links
      carrying sentence context and search query forward into kanji lookups.
    - Per-run furigana distribution matching the original's backtracking segmentation algorithm.
    - Structured-content-to-HTML glossary conversion (lists, tables, links, ruby, collapsible
      spoiler blocks) with dictionary-scoped `styles.css` embedded in `<style>` elements.
    - Deck-scoped duplicate detection (Collection, Deck, Deck root, check across all models) matching
      Anki's first-field checksum (`csum`).
    - Configurable duplicate behaviors (Prevent adding, Allow overwriting, Allow adding) with
      per-field overwrite modes (Fill if empty, Overwrite if available, Overwrite, Append, Prepend, Skip).
    - In-lookup action links with card format shape glyphs (●, ◉, ■, ◆), updating to "Added" /
      "Overwritten" after an export and supporting follow-up taps per duplicate settings.
    - Duplicate checking that dynamically recolors an entry's action links by duplicate state
      (add, add duplicate, overwrite, prevented) using the original extension's icon colors.
    - A tab-strip, live-editing card-format settings UI (up to 5 formats) with install/permission
      flow for enabling AnkiDroid access.
    - See `docs/anki-export.md` for the full architecture and known gaps versus the original
      extension.

## [1.1.1]

### Fixed

- Deinflection now follows the original Yomitan extension's rules, including their word-class
  conditions:
    - Conjugated forms are labelled correctly (e.g. 食べさせられた is now "potential or passive"
      (-られる) rather than the godan passive (-れる)).
    - Impossible conjugation chains are no longer accepted.
    - A conjugated form only matches entries whose parts of speech allow it, so conjugated lookups
      no longer match nouns or other unrelated words.
    - Added rules that were previously missing (the のたまう-type -て/-た forms, -ふ, and -やがる).
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
