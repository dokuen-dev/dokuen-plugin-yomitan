# Installing Dictionaries

The "Install Dictionary" section of the Dictionaries tab offers four ways to install a Yomitan
dictionary ZIP, all of which end in `DictionaryImporter`:

- **Download Presets**: a fixed list of Japanese dictionaries (`MainViewModel.PRESETS_*`), each
  downloaded and imported by `MainViewModel.downloadAndInstallPreset`.
- **Wiktionary Dictionaries**: dictionaries built from Wiktionary by
  [wiktionary-to-yomitan](https://github.com/yomidevs/wiktionary-to-yomitan) (wty), picked by
  language and installed the same way as the presets (see below).
- **Import Local ZIP File**: a ZIP the user picks from storage.
- **Download from Custom URL**: any ZIP URL the user enters.

## Wiktionary dictionaries

The card (`WiktionaryDictionariesCard`) has a source language dropdown, which starts on Japanese,
and a target language dropdown, which starts empty. The source is the language of the words and
the target is the language of the definitions. A sentence above them links "wty" to wty's
download page.

The card works from the release metadata that wty's download page itself uses
(`WiktionaryDictionaries.METADATA_URL`), which `MainViewModel.loadWiktionaryCatalog` fetches once
per session. If the fetch fails, the card offers a Retry button. `WiktionaryCatalog` reads, for
each kind of dictionary, which source and target languages it exists for and how large its ZIP
is. The card behaves as follows:

- The source dropdown lists every language. The target dropdown lists only the languages some
  dictionary covers for the chosen source. Changing the source keeps the selected target, even
  one the new source has no dictionary for, so that the user can switch back quickly without
  misclicks in one dropdown clearing the other.
- Once both languages are chosen, the card lists every dictionary there is for the pair, in the
  download page's order: Main, Glossary, IPA, and IPA merged (pronunciations from every Wiktionary
  edition, which depend only on the source). Each row shows the ZIP's size and is installed like
  a preset. A pair with no dictionary shows a message instead.
- A dictionary's title is its file name without `.zip` (for example `wty-ja-en`, `wty-ja-en-gloss`,
  `wty-ja-en-ipa`, and `wty-ja-ipa`), so a row shows as installed when a dictionary with that
  title is installed. `WiktionaryDictionaries.title` and `url` build both, following the download
  page's `buildUrl` in `download.js`.

The language list (`WiktionaryDictionaries.LANGUAGES`) is a copy of the download page's, with the
names it uses as string resources (`wiktionary_language_*`). A language wty adds later does not
appear until the list is updated. `WiktionaryCatalogTest` covers the metadata parsing, the
target filtering, the dictionaries listed for a pair, and their URLs.

## Deliberate differences from the original

- **Wiktionary dictionaries are installed from the app.** The original's Recommended Dictionaries
  dialog says "For non-English dictionaries, please refer to the list of available Wiktionary
  dictionaries.", with the last two words a link to wty's download page. There the user picks the
  languages, downloads the ZIPs, and imports them. The plugin instead lets the user select,
  download, and install the dictionaries directly from within the app. It keeps a shorter form of
  the guidance ("Non-English dictionaries from wty.") with a link to the same page.
- **Default source language.** The original download page starts with both the source and target
  language dropdowns empty. The plugin starts with Japanese selected for the source language by
  default.
- **Unfiltered source dropdown.** The download page also narrows the source dropdown to the
  languages that have a dictionary for the selected target. The plugin lists every source, so the
  source can be changed freely, and a pair with no dictionary shows a message instead.
- **Minor UI differences.** The plugin has no format dropdown (it always uses the Yomitan format),
  adds a description to each row, and rewords the status messages.
