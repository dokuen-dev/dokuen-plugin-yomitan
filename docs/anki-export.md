# Anki Export

The plugin can export term and kanji lookups directly to [AnkiDroid](https://github.com/ankidroid/Anki-Android),
using free-text `{marker}` field templates that recognize the full standard marker set from the
original desktop Yomitan extension.

## Why the plugin owns this itself

The host app already has its own, separate, fixed-format Anki export with a single hardcoded note
schema. This plugin's Anki export is a second, independent integration: it talks to AnkiDroid
directly, owns its own marker/template engine, and has its own settings UI, coexisting with the
host's built-in button. A user who wants this plugin's richer templates can disable the host's
Anki toggle in host settings.

This structurally mirrors the original desktop Yomitan, which owns AnkiConnect, templating, and
settings as one self-contained unit with no browser/extension split.

## Architecture

All Anki-specific code lives under the `anki` subpackage (sibling to `db`, `parser`, `renderer`,
`importer`):

| File | Responsibility |
|---|---|
| `AnkiSettings.kt` | Data model: `AnkiSettings`, `AnkiCardFormat`, `AnkiField`, and the `AnkiEntryType`/`AnkiDuplicateScope`/`AnkiDuplicateBehavior`/`AnkiFieldOverwriteMode`/`AnkiCardIcon` enums. |
| `AnkiSettingsStore.kt` | Persists `AnkiSettings` as a JSON blob under the `anki_settings` key in the existing `yomitan_prefs` SharedPreferences file, writing every value even when it equals the default (so changing a default later never changes an existing user's settings). Falls back to the defaults (see "Settings UI") if the stored JSON is missing or corrupted. |
| `AnkiTemplateEngine.kt` | Pure-Kotlin marker/template engine: resolves `{marker}` tokens in a field's template text against an `AnkiNoteData`. No Android dependency. |
| `AnkiNoteData.kt` | The data carrier the template engine reads from: `AnkiNoteData` (one per popup entry, holding every definition of the term group), `AnkiClozeData`, `AnkiFrequencyItem`, `AnkiTagItem`. |
| `AnkiNoteDataBuilder.kt` | Maps Room DB entities (`TermEntity`, `KanjiEntity`, `TagMetaEntity`, `TermMetaEntity`, kanji metadata) into an `AnkiNoteData`, and builds the cloze data. |
| `AnkiGlossaryHtmlConverter.kt` | Converts a raw glossary entry (as stored in `TermEntity.glossary`) into the HTML (`formatGlossary`) or plain text (`formatGlossaryPlain`) the original's Anki helpers produce. |
| `AnkiDictionaryStyles.kt` | Scopes a dictionary's `styles.css` for the `<style>` elements the glossary markers embed, like the original's `addScopeToCssLegacy`. |
| `AnkiSentenceExtractor.kt` | Port of the original's sentence extraction: narrows the host's context text (a whole OCR block) to the sentence around the matched word. |
| `AnkiFieldMerger.kt` | Combines an existing note field with a new value per the field's overwrite mode. |
| `AnkiStandardMarkers.kt` | The original's standard marker list per entry type, used by the settings UI's marker menu. |
| `AnkiFieldDefaults.kt` | The original's default field values (first field gets `{expression}`/`{character}`, later fields are matched to markers by name aliases). |
| `AnkiDroidClient.kt` | Wraps `com.github.ankidroid:Anki-Android:api`'s `AddContentApi` for deck/model/field discovery and note add/update, plus a SQL query of `FlashCardsContract.Note.CONTENT_URI_V2` for the duplicate check (`AddContentApi`'s own duplicate search has no deck scope and covers one note type only). |
| `AnkiFieldChecksum.kt` | Anki's first-field checksum, which the duplicate check matches notes by. |
| `TagsTextUtil.kt` | Parses the free-text "Card tags" field and reconciles it with upstream state without clobbering in-progress typing. |

Outside this package, `MainActivity.kt` / `MainViewModel.kt` hold the settings UI (see "Settings UI")
and `YomitanDictionaryPluginService.kt` holds the lookup-time and tap-time integration described next.

## Action links and tap handling

`YomitanDictionaryPluginService` appends one `action:` link per configured card format (matching the
entry's type, and skipping formats with no deck or note type chosen yet) after each rendered lookup
entry, via `appendAnkiActionLinks`. The link is
`action:anki_add?token=<lookup>&entry=<n>&format=<i>`: an opaque token naming the lookup, the index of
the linked entry within that lookup, and the index of the format.

**Link cache.** Each lookup's links are stored together, in an in-memory cache (`ankiLookups`,
thread-safe) keyed by the lookup's token. A lookup's record holds its rendered entries (rewritten
when a tap relabels a link, see "After a tap") and, for each linked entry, a snapshot taken when the
link was rendered:

- the exact ordered list of card formats that the link's `format=` index refers to, and the settings in
  force at that time, so a tap resolves to the format the user saw even if settings changed in between;
- the cheap lookup-time inputs (term or kanji rows, scan position, active dictionaries) needed to build
  the note data.

The note data itself (tag and frequency queries, glossary conversion, cloze) is built on the first tap
and then reused, so lookups that are never exported pay none of that cost.

A lookup's record also holds the sentences its kanji headword links refer to (see "Marker/template
engine"), so a lookup is recorded when it has action links or such sentences.

**Why old links must stay valid.** The host keeps a back/forward history of earlier results without
querying the plugin again, and can run lookups concurrently. Links from older lookups therefore have to
stay tappable. The host does not tell the plugin when it discards a result, so the cache is bounded by
count rather than by lifetime: it keeps the 10 most recent lookups that produced links and is cleared
when the session shuts down. Eviction removes a whole lookup at a time, so every entry of a retained
lookup stays tappable however many entries it has (a kana scan or a wildcard search can return dozens).
A link whose lookup has been evicted produces a "stale link" error. So does a link rendered by an
earlier instance of the service, for example before the plugin process was restarted while the host
still showed the result: each instance starts its tokens with its own random prefix, so a new
instance, whose token counter starts over, never mistakes an old token for one of its own lookups.

**Settings.** They are reloaded from the store at the start of every lookup (the plugin session is
initialized once, so edits made in the settings screen would otherwise never be seen). Whether export is
enabled is always checked against the live setting, both when rendering links and when a link is
tapped.

**Tap handling.** `onExecuteCustomAction` resolves the link to its snapshot, renders the template of
each field the note type currently has (an unrecognized marker does not abort: it is written into the
field as `{<marker>-render-error}` and reported after the note is saved, see "Marker/template engine"),
and calls into `AnkiDroidClient` to add or update the note (see "Duplicate detection"). Before
rendering, it checks that the format's note type and deck still exist in AnkiDroid: AnkiDroid
accepts a note for a deck id that no longer exists and puts its cards in no deck, where no deck
list shows them, so a deleted deck or note type is reported instead, as AnkiConnect reports a deck
or note type it cannot find. On success it returns a `CustomActionResult.UpdateResult` re-rendering
the lookup's entries with the tapped link relabeled (see "After a tap"). The host replaces its whole
displayed result with what an `UpdateResult` carries, so the entries returned are always those of the
lookup that produced the link, never the most recent lookup's. A warning about the write (unknown
markers, or images that could not be stored or were denied) is the `UpdateResult`'s message, which the
host shows along with the refreshed entries. If that lookup has been evicted in the meantime, the
warning or a plain "Note added"/"Note updated" message is returned instead. Failures (AnkiDroid
missing, permission missing, the format's deck or note type deleted, add or update failed, export
disabled, stale link) are thrown as `DictionaryException`s, which the host shows as an error message.

**After a tap.** A link whose tap added a note reads "Added: `<format>`", and one whose tap overwrote
an existing note reads "Overwritten: `<format>`" (the shape glyph stays). Only the tapped format's link
changes; the entry's other links keep their labels. The label is rewritten in place
(`restyleAnkiLinks`), shifting the spans after it, and the write is remembered in the link's
snapshot, so later refreshes of the same lookup keep showing it.

The tap also recolors the entry's links (see "Link colors"). The relabeled link stays tappable, and
a second tap goes through the same path as the first. With
duplicate checking on (the default), the note now counts as a duplicate, so that reproduces what the
original does to its button after an add
(`_addNewAnkiNote` in `display-anki.js` calls `_updateSaveButtonForDuplicateBehavior` with the new
note's id):

| "When a duplicate is detected" | Original's button after an add | Second tap on the link |
|---|---|---|
| Prevent adding | disabled ("Duplicate notes are disabled") | reports that the note already exists and writes nothing |
| Allow overwriting | "Overwrite `<format>` note" | overwrites the note; the link then reads "Overwritten" |
| Allow adding | "Add duplicate `<format>` note" | adds another note; the link keeps reading "Added" |

A link cannot be disabled, so under "Prevent adding" the tap explains instead of doing nothing (the
link is gray, the original's disabled look, from then on).
The labels are past tense because they report what the tap did, which the original shows with its
"View added note" button (see "Deliberate differences").

## Marker/template engine

Markers use the same syntax as the original: `\{([\p{Letter}\p{Number}_-]+)\}`. There are no
Handlebars conditionals, loops, or helpers. A field's value is literal text with `{marker}` tokens
substituted in place. Only a genuinely unrecognized marker name is a template error. Every standard
marker from the original's `getStandardFieldMarkers()` resolves to something, even if that something
is currently an empty string. As in the original, a template error does not stop the export: the
unknown marker is written into the field as `{<marker>-render-error}`, the note is still added or
updated, and the result message names the unknown markers. The message comes with the refreshed
entries, so the link is relabeled and recolored as after any other write.

Each supported marker reproduces the output of the matching partial in the original's default
field templates (`ext/data/templates/default-anki-field-templates.handlebars`) under its default
options: the "group" result mode (one note per popup entry, with every definition of the term
group), non-compact tags, and non-compact glossaries. Values the original prints with `{{...}}` are
HTML-escaped the way Handlebars escapes them.

**Markers with real content** (`AnkiTemplateEngine.supportedMarkers`):

`expression`, `reading`, `furigana`, `furigana-plain`, `character`, `dictionary`,
`dictionary-alias` (the dictionary title, the original's default), `tags` (headword tags, then each
definition's tags), `part-of-speech`, `conjugation`, `kunyomi`, `onyomi`, `onyomi-hiragana`,
`stroke-count`, `sentence`, `sentence-furigana` and `sentence-furigana-plain` (from Dokuen's
readings, see below), `cloze-prefix`, `cloze-body`, `cloze-body-kana`, `cloze-suffix`,
`search-query`, `frequencies`, `single-frequency-number`,
`frequency-harmonic-rank`, `frequency-harmonic-occurrence`,
`frequency-average-rank`, `frequency-average-occurrence`, `glossary` (+ `-brief`,
`-no-dictionary`, `-plain`, `-plain-no-dictionary`, `-first`, `-first-brief`,
`-first-no-dictionary`), `screenshot`, `clipboard-image`, and `clipboard-text`.

`{sentence}` and the cloze markers use the sentence around the match (see `AnkiSentenceExtractor`),
not the whole context text. The host sends the whole OCR block that contains the selection as
context when the selection is contiguous and lies entirely within that one block (the selection
itself can be as short as one word). For a selection that is not contiguous or crosses block
boundaries, sending a block is not feasible, so the context is just the tapped text and the
sentence is that text. Text that sits close together and flows as ordinary running text, such as a
paragraph on a web page, is recognized as a single block.

`{search-query}` is the lookup's query, as in the original's popup (`context.fullQuery`, which falls
back to the popup's query). For a scan, it is the scanned text cut to the longest match of any result
(the same for every entry of the lookup, as `text-scanner.js` cuts it). For an internal `?query=`
link, it is the link's query. For a kanji lookup, it is the character, except for a kanji reached from a term's
headword (below), which keeps the term lookup's query when that is longer than the kanji, as the
original's `_createSearchParams` (`display.js`) carries the full query into the kanji lookup.

A kanji reached by tapping a kanji in a term's headword also uses that term's sentence, as the
original's kanji links carry the popup's sentence into the kanji lookup (`_onKanjiLookup` in
`display.js`). The host hands a link target to `onLookup` verbatim and caps link length, so neither
can travel in the link itself. Instead, when export is enabled and a complete kanji card format
exists, the service appends `&origin_ref=<token>.<index>` to each such link, pointing at what the
link cache holds for the term: its match, if it was scanned, and its lookup's query. If that lookup
has been evicted, the kanji opens normally, but its note has no sentence and its query is the
character. Internal `?query=` lookups have no sentence to pass on, only their query.

`{conjugation}` names each conjugation undone to reach the headword the way the popup's
deinflection badge does, by its compact Japanese name (`-せる・させる « -られる « -た` for
食べさせられた; see "Deliberate differences").

`{screenshot}` and `{clipboard-image}` print the original's partials, `<img src="FILE" />` with
the AnkiDroid media file name, or nothing without an image. Their images come from Dokuen
(SDK 0.7.0 `CustomActionContext.requestImage`) when a link is tapped, after the duplicate
decision, and only for markers in fields the write keeps (`AnkiMediaNeeds`): every field
when adding a note; when overwriting, not a Skip field, and a "Fill if empty" field only if
it is empty. The screenshot is requested first, interactively when "{screenshot} behavior" is
"Show crop UI" (Dokuen's crop UI, where the user can also pick a file, continue without an
image, or cancel), then the clipboard image, without UI. Cancelling writes nothing and skips
the clipboard. The screenshot is encoded per "Image format" (PNG, or JPEG at "JPEG quality");
the clipboard image is always PNG (see "Deliberate differences"). Each is stored through
`AnkiDroidClient.addImage` (the plugin's FileProvider and AnkiDroid's media provider), asking for
`dokuen_screenshot_<date>.png` and `dokuen_clipboard_image_<date>.png` (the original's names
with a Dokuen prefix, see "Deliberate differences", and the date as `generateAnkiNoteMediaFileName`
writes it, zero-based month included), and the marker uses the name AnkiDroid stored it under. The
file name is escaped twice in `src`, as in the original, where `getMedia` escapes it and the
double-brace output escapes it again. An image that cannot be encoded or that AnkiDroid does not
accept leaves its marker empty and is reported after the save, like an unknown marker. Dokuen
versions older than SDK 0.7.0 provide no images. As of SDK 0.7.0 Dokuen denies images to users
without Dokuen Pro (`ImageRequestResult.Denied`); the note is written with the image markers empty,
no further image is requested, and the message after the save names the markers left empty, for
example "Note added successfully, but with empty {screenshot} field (images require Dokuen Pro)."

`{clipboard-text}` prints the clipboard's text (`CustomActionContext.requestClipboardText`),
requested after the images under the same rules, and also when images were denied (Dokuen does
not gate it on Pro). As the original's `clipboard-text` partial does, the text is HTML-escaped
(once: the partial's output is triple-brace) and each line break becomes `<br>` followed by the
line break. CRLF and lone CR line breaks are first turned into LF, as the original's paste into a
`<textarea>` does when it reads the clipboard. Dokuen hands over plain text cut
to 10,000 UTF-16 code units; older Dokuen versions provide none, so the marker is empty there.

`{sentence-furigana}` and `{sentence-furigana-plain}` print the cloze sentence with furigana, as
the original's `createFuriganaHtml` (a `<span class="term">` per term, `<ruby>TEXT<rt>READING</rt></ruby>`
per reading) and `createFuriganaPlain` (` TEXT[READING]` per reading, leading whitespace trimmed),
unescaped. The readings come from Dokuen (SDK 0.8.0 `CustomActionContext.requestReadings`): on a
tap, before any field is rendered, the plugin sends the cloze sentence if a current field of the
format uses either marker, the session language is `ja`, and the sentence is at most
`CustomActionContext.MAX_READINGS_TEXT_LENGTH` (500) UTF-16 code units. Each word Dokuen returns
is a term, and each stretch of text no word covers is one term without a reading
(`AnkiSentenceFurigana`). As the original's `getReading` does, a segment whose text is a term
note's own expression shows the note's reading. The answer is kept with the entry's note data, so
later taps of the same entry do not ask again. When Dokuen gives no readings (older than SDK 0.8.0,
a refused request, or Dokuen still starting), both markers print the plain sentence, which is what the
original prints without furigana data, and the next tap asks again. The readings request is made
before the image and clipboard text requests.

The duplicate check's key is the first field rendered with the sentence furigana (when Dokuen gave
it) and with the image and clipboard text markers empty.

The four frequency aggregates (`{frequency-harmonic-rank}`, `{frequency-harmonic-occurrence}`,
`{frequency-average-rank}`, `{frequency-average-occurrence}`) take the first value of each
dictionary, as the original's `getFrequencyNumbers` does, and skip a dictionary whose index.json
declares the other `frequencyMode`: the rank markers skip `occurrence-based` dictionaries, the
occurrence markers `rank-based` ones, and both keep a dictionary that declares no mode.

**Markers that resolve to an empty string** (`AnkiTemplateEngine.stubMarkers`) because the
backing subsystem doesn't exist in the plugin yet: `audio`, `pitch-accents`,
`pitch-accent-graphs`, `pitch-accent-graphs-jj`, `pitch-accent-positions`,
`pitch-accent-categories`,
`phonetic-transcriptions`, `popup-selection-text`, `url`, and `document-title`.

**Out of scope entirely:** per-installed-dictionary dynamic markers (`single-glossary-<dict>` and
variants, `single-frequency-<dict>`), and the original's raw-Handlebars "Configure Anki card
templates" power-user editor (custom partials, `regexReplace`/`op` helpers, user-defined markers).
Templates that only use the built-in marker set port over unmodified from desktop. Templates built
around the raw-Handlebars editor's custom markers do not.

### Furigana distribution

`{furigana}`/`{furigana-plain}` split the expression into contiguous kana/non-kana runs and align
the reading against each run independently (`segmentizeFurigana`), matching the original's
`distributeFurigana` (`ext/js/language/ja/japanese.js` in the original source). A word with
multiple separate kanji runs (e.g. 動き出す/うごきだす) gets one `<ruby>` annotation per run, not
one span covering the whole differing middle. This is a full port of the original's backtracking
search (`AnkiJapanese.distributeFurigana` in `AnkiTemplateEngine.kt`), including its behavior of
refusing to pick a split when the reading can be divided more than one way. Like the original, it
falls back to a single segment holding the whole expression and reading when the runs can't be
aligned against the reading (or the alignment is ambiguous). `{cloze-body-kana}` uses the port of
`distributeFuriganaInflected` in the same object.

### Glossary structured-content conversion

`AnkiGlossaryHtmlConverter` rebuilds structured content the way the original's
`StructuredContentGenerator` does and serializes it the way the original's Anki renderer does: a
wrapping `<span>`, `data-sc-*` attributes from each node's `data`, automatic `lang` detection on
elements that directly hold Japanese or Chinese text, inline styles from each node's `style`,
the fixed class-based styles for tables, links with their external-link icon placeholder, and
newlines turned into `<br>`. A plain-text glossary item (a string or a `text` item) has only `<`
and `>` escaped, as the original's `_escapeExpression` does, so `&` passes through unchanged. In
`{glossary}` its newlines are also turned into `<br>`. A glossary item that is an array, a redirect
of the form `[term, [inflection rules]]`, is left out, and a term row with nothing else in its
glossary is not a definition at all: the original's translator removes both before any note is
built (`_getDeinflections` in `translator.js`). `{glossary-plain}` uses the `formatGlossaryPlain`
rules (plain text, `(dictionary)<br>` prefix per definition). Images are not embedded, because this
plugin doesn't write dictionary media into the Anki note. An image renders as a bare `<img>`
carrying its path and size (never a `src`), so it has no visual representation, only descriptive
alt text. (The original stores the image in Anki's media folder and writes a link to it around a
stack of spans holding the `<img>`.)

The `{glossary}` marker itself wraps the definitions in the original's default markup: a
`<div style="text-align: left;" class="yomitan-glossary">` holding an `<ol>` with one
`<li data-dictionary="...">` per definition, each starting with an `<i>(tags, dictionary)</i>`
prefix (omitted by `-brief`; the dictionary part is omitted by `-no-dictionary`) followed by the
items as a `<ul>` when there is more than one.

When the definition's dictionary has a `styles.css`, each `<li>` is followed by a `<style>` element
holding that CSS, with every rule's selectors prefixed by
`.yomitan-glossary [data-dictionary="<title>"]` so it only applies to that dictionary's
definitions. `{glossary-first}` follows its single definition with the same CSS prefixed by
`.yomitan-glossary` only. As in the original (`addScopeToCssLegacy`), only top-level style rules are
kept: `@media`, `@font-face`, and other at-rules are dropped, and rules nested inside a style rule
(CSS nesting, which Jitendex uses) stay inside their parent. `{glossary-plain}` and kanji glossaries
have no `<style>`. The CSS comes from `DictionaryEntity.styles` and is scoped when a link is first
tapped, by `AnkiDictionaryStyles`.

## Duplicate detection

Duplicate handling follows the original's model:

- `checkForDuplicates` gates whether the duplicate search runs at all. It runs when a link is
  tapped, for the tapped format and then for the entry's other formats to color their links (see
  "Link colors"), never at lookup/render time (see "Known limitations").
- A note is a duplicate when its first field, whatever that field is named, has the same checksum
  as the new note's first field: Anki's `csum`, the first 32 bits of the SHA-1 of the field's text
  with its HTML stripped and media file names kept (`AnkiFieldChecksum`). This is how AnkiConnect's
  add-time check (`isNoteDuplicateOrEmptyInScope`), and so the original, decides it; the original
  never matches the field by name. A first field that is empty once its HTML is stripped has no
  duplicates.
- `duplicateScope` (`COLLECTION`/`DECK`/`DECK_ROOT`) and `duplicateScopeCheckAllModels` limit which
  notes count, via `AnkiDroidClient.findDuplicateNotes`, which queries
  `FlashCardsContract.Note.CONTENT_URI_V2`, whose selection is SQL against AnkiDroid's notes table.
  The note type is matched by id (`mid=<id>`), omitted when checking all models, so renaming it in
  AnkiDroid can't break the check. `COLLECTION` has no deck condition. `DECK` counts a note with a
  card in the format's own deck, and `DECK_ROOT` one with a card in the top-level ancestor of that
  deck (the part of its name before the first `::`, like the original's `getRootDeckName`) or any
  deck below it, as AnkiConnect's `checkChildren` does. The deck ids come from AnkiDroid's live
  deck list, so renaming a deck can't break the check either.
- `duplicateBehavior`: `PREVENT` blocks the add and reports the note already exists; `OVERWRITE`
  merges the newly rendered field values into the existing duplicate note per each field's
  `overwriteMode` (`COALESCE`/`COALESCE_NEW`/`OVERWRITE`/`APPEND`/`PREPEND`/`SKIP`, see
  `AnkiFieldMerger`; append and prepend concatenate with no separator, like the original), and then
  updates it. Only a duplicate of the format's own note type is overwritten (fields are merged by
  position, which is meaningless across note types), so when "check across all models" finds only
  notes of other note types, a new note is added instead;
  `ALWAYS_ADD` adds a new note unconditionally.

## Settings UI

Reached from the dashboard's "Anki Export" tab (a `HorizontalPager` page alongside "Dictionaries"):
an enable switch gated on a real AnkiDroid install/permission flow (never turns on without a
successful connection), global card tags and duplicate-handling controls, and a tab-strip
card-format editor with one tab per format (name, type, icon, deck, model, and a field-mapping row
per real field name fetched live from the chosen model). There is no Save/Cancel: every control writes to
settings the instant it changes, matching the original's own live-editing interaction model rather
than a staged dialog. There can be at most 5 formats. The only confirm/cancel dialog anywhere in this UI is
deleting a card format. The card tags field shows the tags one space apart, and, like the original's
(`splitTags` and `joinTags` in `generic-setting-controller.js`), rewrites the typed text that way when
it loses focus, which the keyboard's Done key also causes. While it has focus, the text stays as
typed (`TagsTextUtil`). Each edit is applied to the format as currently stored
(`MainViewModel.updateAnkiCardFormat` with a transform), not to a copy the editor read earlier, so an
edit never undoes a change that landed in between, such as a field list fetched from AnkiDroid.

The defaults are the original's (`options-schema.json`), except the card tag: export off, card tags
`dokuen-yomitan` (see "Deliberate differences"), duplicate checking on with scope Collection and
"When a duplicate is detected" set to "Allow adding" (`ALWAYS_ADD`), and three card formats with no
deck or note type chosen yet: Expression (Term, Big Circle), Reading (Term, Small Circle), and Kanji
(Kanji, Big Circle). A format only gets action links
once it has both a deck and a note type. A format added with "+" is named `Format <n>`, where n is the
new format count, as in the original. A format whose name is emptied also shows `Format <n>` on its
tab, with n its position, like the original's tab label.

Each field row has a marker menu listing the original's standard markers for the format's type
(`AnkiStandardMarkers`), with the marker the field would get by default in bold. Choosing a
note type fills fields the way the original does (`AnkiFieldDefaults`): a same-named field keeps its
value, the first field defaults to `{expression}`/`{character}`, and later fields are matched to a
standard marker by name (`reading`, `definition`/`meaning` for `glossary`, and so on).
`{single-frequency-number}` renders like any other marker but, as in the original, is not a
standard marker: the menu does not list it and no field defaults to it, so it has to be typed. The
first field is
flagged if it contains no marker. When "Allow overwriting" is selected, each field row also has an
overwrite-mode menu, listed in the original's order: Skip, Prepend, Append, Fill if empty, Overwrite
if available, and Overwrite (the declaration order of `AnkiFieldOverwriteMode`). When enabling fails because the AnkiDroid permission was denied,
the switch stays off and a status row with a retry button (or, after a permanent denial, an "Open
settings" button) stays visible.

An "Images" card (shown with the card formats) holds "{screenshot} behavior" (Full screen, the
default because desktop Yomitan never prompts, or Show crop UI), "Image format" (PNG, the original's
default, or JPEG), and, for JPEG, "JPEG quality" (default 92), the original's `anki.screenshot`
options. They are read live at tap time, not from the link's settings snapshot.

Each format's action-link label is prefixed with a plain shape glyph identifying the format (●
Big Circle, ◉ Small Circle, ■ Square, and ◆ Diamond, matching the original's 4 icon shapes). As in
the original, the shape identifies the card format and the color (`InlineStyle.foregroundColor`)
shows the link's duplicate state (see "Link colors"). The label reads "Add: `<format>`" until a tap
on the link writes a note (see "After a tap"). The links are listed one per line below the entry,
at 0.75 times the dictionary text size (`InlineStyle.fontSize`).

### Link colors

A link's color says what tapping it would do about an existing duplicate, like the original's
add button icons (`_updateSaveButtonForDuplicateBehavior` in `display-anki.js`, the `data-icon`
rules in `ext/css/display.css`, and the icon SVGs in `ext/images`). The original's icons are all
filled with the same green. Its duplicate variants pass that green through an SVG color filter,
and a disabled button is drawn in grayscale. The plugin uses the resulting colors
(`AnkiLinkStatus`):

| State | Original's icon | Color |
|---|---|---|
| No duplicate, or not checked yet | `big-circle.svg` etc. | green `#6FB558` |
| Duplicate, "Allow adding" (or "Allow overwriting" with duplicates of other note types only, where the tap adds a note) | `add-duplicate-*.svg`, "green-to-purple" filter (hue rotated 180 degrees) | purple `#D08AE7` |
| Duplicate of the format's own note type, "Allow overwriting" | `overwrite-*.svg`, "green-to-orange" filter (hue rotated 280 degrees, saturated 2) | orange `#FF8727` |
| Duplicate, "Prevent adding" | the plain icon on a disabled button, `grayscale(100%)` | gray `#9F9F9F` |

The filter colors are computed from `#6FB558` in sRGB, as the SVGs specify
(`color-interpolation-filters="sRGB"`). The disabled button is also drawn at 25% opacity; the plugin
leaves that out, since it would make a text label hard to read.

The original checks every button of the popup eagerly. The plugin cannot afford that (see "Known
limitations"), so every link starts green and is checked only once the user taps a link of the
same entry, when an AnkiDroid round-trip is expected anyway:

- The tapped format's own duplicate search (the one the tap runs before writing) decides what the
  tap does. After a successful write, its link shows the state the new note puts it in without
  another query: the written note is a duplicate of the format's own note type, so its color is
  gray, orange, or purple per "When a duplicate is detected", as the original's button turns after
  an add. With duplicate checking off, or when the first field renders empty (neither is
  searched), it is purple, since the next tap adds another note.
- Every other format of the same entry is then searched the way a tap on it would search
  (`checkOtherAnkiLinks`: its note type's first field, rendered with the entry's note data, and the
  settings snapshot the tapped link uses), one `findDuplicateNotes` query per format, plus a
  field-list query per note type the tapped format does not share. A format that is not searched
  (duplicate checking off, empty first field) or whose search fails keeps its color.
- Links of other entries and other lookups are not checked and keep their colors.
- A tap refused under "Prevent adding" returns a message rather than a refresh. Its link turns
  gray at the next refresh, and the other links are not checked on that tap.

The colors are stored in the link's snapshot with the labels, so they persist across refreshes of
the same lookup. They reflect AnkiDroid when the entry's last tap ran: a note added or removed in
AnkiDroid afterwards is not noticed until the next tap on that entry.

## Known limitations

Features of the original that this plugin does not have. Each is either a limitation of the AnkiDroid
API surface or a deliberate scope boundary:

- **No AnkiConnect server address / API key setting.** Structurally inapplicable: this plugin talks
  to AnkiDroid directly via `AddContentApi`/`FlashCardsContract`, which has no server address or API
  key concept at all.
- **No "Suspend new cards" / "Force Anki sync on adding card" settings.** `AddContentApi` exposes no
  method for suspending cards or forcing a sync.
- **No raw-Handlebars "Configure Anki card templates" editor**, and no user-defined custom markers.
  In the original this is an advanced, power-user feature. The plugin uses the smaller
  free-text-marker-substitution model instead of a full template-partial engine.
- **Duplicate state shows only after a link of the entry has been tapped.** The original checks
  every button of the popup eagerly and colors it by its duplicate state (and changes its tooltip)
  before anything is clicked. Doing that would mean one `findDuplicateNotes` ContentProvider
  round-trip per configured card format on every single lookup, an ongoing hot-path cost. The
  plugin draws every link green until the user taps one of an entry's links, and then colors that
  entry's links (see "Link colors"). Duplicate handling itself is fully correct at tap time
  regardless. The labels of untapped links always read "Add: `<format>`"; only the color changes.
- **No "Show card tags and flags" setting** (the original's `displayTagsAndFlags`, a 4-valued
  setting controlling whether existing-note tags/flags preview before tapping Add). A preview is
  only useful before the first tap, so it would need the per-card-format, per-lookup AnkiDroid
  round-trip that the duplicate check avoids. It is deferred for the same reason (documented in
  `AnkiSettings.kt`).
- **`{audio}`, the pitch accent markers, and the page-context markers stay empty** until the
  plugin has the matching subsystem.
- **Only English strings exist.** User-facing strings are in `strings.xml`, but the project has no
  `values-*` locale directory yet, so nothing is translated.

## Deliberate differences from, and quirks kept from, the original

**Differences.**

- **Sentence furigana comes from Dokuen, not from the user's dictionaries.** The original builds
  `{sentence-furigana}` with its scanning parser (`_textParseScanning` in `backend.js`): a full
  dictionary search at each position, using the installed Yomitan dictionaries. The plugin uses
  Dokuen's furigana, so a reading can differ from desktop's. Porting the scanning parser would
  mean thousands of database queries per tap.
- **Sentence furigana terms follow Dokuen's words.** Where the original's longest-match scan
  would group text differently (for example, one dictionary match spanning two of Dokuen's words
  that no correction joined), the `<span class="term">` boundaries differ. Ruby placement is the
  same in the common case, and `{sentence-furigana-plain}` has no term boundaries.
- **The duplicate check finds notes whose first field has sentence furigana.** The original
  searches for duplicates with a note rendered without media (`requirements: []` in
  `display-anki.js`), and it treats sentence furigana as media. When the first field uses
  `{sentence-furigana}` or `{sentence-furigana-plain}`, desktop therefore searches for the plain
  sentence, while the note it saves has the readings. Anki's checksum keeps the text of the
  readings, so desktop never finds such a note as a duplicate. The plugin asks Dokuen for the
  readings before it renders the key, so the key matches the note it saves and an existing note is
  found. When Dokuen gives no readings, the key is the plain sentence, as on desktop.
- **`{glossary-plain}` and `{glossary-plain-no-dictionary}` escape a leftover `>` as `&gt;`.** The
  original's `_getText` (`anki-template-renderer.js`) escapes it as `&rt;`, which is not an HTML
  entity (a typo), so desktop shows the literal text `&rt;` in the field. Text nodes are already
  escaped before that step, so this only occurs when an attribute value in the structured content
  contains a `>` (the tag-stripping step stops at it and leaves the rest of the tag behind). Covered
  by `AnkiGlossaryHtmlConverterTest`.
- **`{conjugation}` uses the compact Japanese names of the conjugations.** The original prints
  their English names (`causative « potential or passive « -た`, from
  `getUserFacingInflectionRules`). The plugin prints the names its popup shows
  (`-せる・させる « -られる « -た`), the original's Japanese names with `-` in place of `～`
  (`Transform.displayName`), which are shorter on a phone screen.
- **The default card tag is `dokuen-yomitan`, not `yomitan`.** Intentional: notes Yomitan for Dokuen
  adds can be told apart from notes desktop Yomitan adds when both write to the same synced collection.
  Users who want the original's tag can change the Card tags setting.
- **The CSS in glossary `<style>` elements is not re-serialized by a browser.** The original parses
  and rewrites the dictionary's CSS with the browser's CSS engine, which also normalizes values
  (`#fff` becomes `rgb(255, 255, 255)`) and drops declarations it rejects. `AnkiDictionaryStyles`
  only removes comments, collapses whitespace, and writes each rule as `selector { name: value; }`,
  so values keep their original spelling and rejected declarations stay in the text. The browser
  that renders the card ignores those the same way, so cards look the same, but the field text can
  differ from desktop's.
- **A dictionary CSS selector list is split only at its top-level commas.** The original splits the
  selector text at every comma before prefixing each part, which breaks selectors with a comma
  inside `:is()`/`:not()` or a quoted attribute value, and every selector when the dictionary's
  title contains a comma (the title is part of the prefix).
- **The notes a duplicate check finds are the ones it acts on.** The original decides whether a
  note is a duplicate with AnkiConnect's checksum check, but finds the notes to overwrite or view
  with a separate browser search, `"<first field name>:<key>"` with `"` stripped from the key,
  which misses a duplicate whose first field has another name or contains a `"`. The plugin
  overwrites a note the checksum check found.
- **Only the note type's current fields are rendered and sent.** The original renders every field
  stored in the card format (`anki-note-builder.js`) and sends them all by name, including fields
  the note type no longer has. AnkiDroid's API has no field names: `AddContentApi.addNote` and
  `updateNoteFields` take the fields as a positional array in the note type's field order (joined
  into `FlashCardsContract`'s `FLDS` column), so a stored field the note type no longer has has no
  slot and can never be written. The plugin therefore renders only the fields the note type currently
  has. Rendering the others anyway would only report marker errors in text that never reaches the
  note. Stored values for such fields stay in the settings but are ignored, and the field list is
  re-synced whenever a format's editor opens, which prunes them.
- **A written note is marked on the link itself rather than with a separate "View note" button.**
  The original's add button is an icon, of which only the image and tooltip change after an add,
  and a separate "View added note" button appears that opens the note in Anki. AnkiDroid cannot be
  opened from the plugin (the host does not let it start activities), so there is no such button.
  The tapped link's label says what happened instead.
- **With duplicate checking off, a second tap adds another note whatever the duplicate behavior.**
  The original applies the duplicate behavior to its button after an add even when duplicate
  checking is off (with "Allow overwriting" the button then does nothing, since it has no duplicate
  to overwrite). This version's settings screen hides the behavior while checking is off, so it does
  not apply then: every tap adds a note.
- **`{clipboard-image}` is re-encoded as PNG.** The original stores the clipboard image in its
  own format, which in Chrome is PNG (it reads the image as a pasted `data:image/png` URL), and
  ignores the screenshot format setting. Dokuen hands the plugin decoded pixels (possibly scaled
  down), so the plugin always encodes them as PNG.
- **Media file names carry AnkiDroid's suffix.** AnkiConnect stores a file under the name the
  original asks for. AnkiDroid's media provider (`insertMediaFile` in its `CardContentProvider`)
  instead creates the file with `File.createTempFile(preferredName + "_", ...)`, so the stored
  name gets a random number before the extension
  (`dokuen_screenshot_2026-00-05-03-04-05-006_123456789.png`), and takes its extension
  from Android's MIME table, which gives `.jpg` for JPEG where the original writes `.jpeg`.
  AnkiDroid has no other way to add media.
- **A repeated clipboard image is stored again.** The original remembers the last clipboard
  image it stored and, when the clipboard still holds the same image, stores it again under the
  same name, so the notes share one file. Through AnkiDroid each add stores a new file (see the
  previous point).
- **Images are requested only for fields the write keeps.** The original collects the media a
  card format uses from all of its fields, and on an overwrite still captures and stores the
  image of a Skip field, or of a "Fill if empty" field that is not empty, before the merge
  drops it. The plugin does not ask for those (so Dokuen shows no crop UI for them); the
  resulting note is the same.
- **The screenshot is the image Dokuen OCR'ed**, not a capture of a browser tab, optionally
  cropped or replaced by a file in Dokuen's crop UI.
- **Media file names start with `dokuen_`.** The original names its images
  `yomitan_browser_screenshot_<date>` and `yomitan_clipboard_image_<date>`. The plugin uses
  `dokuen_screenshot_<date>` and `dokuen_clipboard_image_<date>` instead, since the images come
  from Dokuen rather than a browser tab, and the names show which app added them to the
  collection. The date part is the same as the original's.

**Quirks kept for parity.** These may look like defects, but they mirror the original's settings page
and note builder, so users moving between the two get the same behavior. Change them only if
diverging from the original is the goal.

- **Changing a card format's type (Term/Kanji) does not rebuild its fields.** Only changing the
  note type rebuilds them (`anki-controller.js`, the model-change handler). A format switched from
  Term to Kanji keeps its old field values, such as `{expression}` in the first field, until the
  user edits them.
- **The last card format cannot be deleted.** The original disables the delete button and ignores the
  click while only one format exists (`anki-controller.js`, `_onCardFormatDeleteClick` and the
  `cardFormats.length <= 1` check), and this plugin does the same.
- **Nested quotes can stop sentence extraction from ending at a terminator.** The original's
  `extractSentence` (`text-source-generator.js`) pushes the matching quote it must look for onto
  the front of its quote stack and compares against the front, but removes a match from the back. With nested quotes
  such as `「A『B』C」` the wrong entry is removed, the stack never empties, and the scan continues
  past the sentence terminators on that side (up to the scan extent or a newline).
  `AnkiSentenceExtractor` does the same, so `{sentence}` and the cloze markers match desktop.
- **A kanji note made from a headword kanji link splits its cloze at the term, not the kanji.**
  The original's `getCloze` (`anki-note-data-creator.js`) cuts the sentence it carried over at the
  term's offset, with a body as long as the kanji, so for 水入り followed through 入 the body is 水
  and the suffix starts at 入. This version does the same.

## Adding or changing a marker

1. Add the resolver to `AnkiTemplateEngine.supportedMarkers` (or the name to `stubMarkers` if there is
   nothing to back it yet). Match the corresponding partial in the original's
   `default-anki-field-templates.handlebars`, including which values are HTML-escaped.
2. If it is a new standard marker of the original, add it to `AnkiStandardMarkers` so the settings
   UI's marker menu offers it, in the original's order (`getStandardFieldMarkers` in
   `ext/js/data/anki-template-util.js`), and check `AnkiFieldDefaults` for name aliases.
3. If the data comes from the database, extend `AnkiNoteData` and `AnkiNoteDataBuilder`.
4. Add cases to `AnkiTemplateEngineTest` (and the builder's test), and update the marker lists in this
   document and in the manual test plan.

## Testing

Unit tests live under `app/src/test/java/.../anki/` (`AnkiSettingsStoreTest`,
`AnkiTemplateEngineTest`, `AnkiGlossaryHtmlConverterTest`, `AnkiDictionaryStylesTest`, `AnkiNoteDataBuilderTest`,
`AnkiSentenceExtractorTest`, `AnkiSentenceFuriganaTest`, `AnkiFieldMergerTest`, `AnkiFieldDefaultsTest`,
`AnkiStandardMarkersTest`, `AnkiDroidClientTest`, `AnkiFieldChecksumTest`, `TagsTextUtilTest`,
`AnkiMediaNeedsTest`, `AnkiMediaFileNamesTest`) plus, at the top level,
`AnkiE2ETest.kt` (the `onLookup` → action-link → `onExecuteCustomAction` round trip) and
`AnkiExecutionTest.kt` (tapping a link against a mocked `AnkiDroidClient`: every duplicate
behavior, a deleted deck or note type, the link labels and colors a tap leaves, the link cache,
settings changing between render and tap, the sentence and query a scanned term, a tapped
kanji, and a kanji reached through a headword link each get, and the sentence readings, image, and
clipboard text requests a tap makes, including cancelled and denied images, through `FakeActionContext`,
a fake host). All pure-logic classes (the template engine, the glossary converter, the CSS scoping,
the tag-text utility) have plain JUnit coverage with no Android dependency; everything touching
`Context`/SharedPreferences/the service class uses Robolectric. All of these run on the JVM.

`app/src/androidTest/.../anki/AnkiDroidDeviceTest.kt` runs against the AnkiDroid installed on a
device. It checks that AnkiDroid accepts the duplicate check's SQL and that it finds what it
should (deck, deck-root, and note-type scoping, keys with quotes, wildcards, Japanese text and
markup, and that `AnkiFieldChecksum` matches the checksums AnkiDroid stores), and that notes,
including an installed dictionary's `{glossary}` with its `<style>` element, round-trip unchanged.
It writes to the device's AnkiDroid collection: it creates the decks `DokuenPluginTest` and
`DokuenPluginTest::Sub` and keeps them (AnkiDroid's API cannot delete decks), and adds notes
tagged `dokuen-plugin-test`, which it deletes afterwards (it fails if any are left). Don't run it
with `connectedAndroidTest`, which uninstalls the app when it finishes and so deletes its installed
dictionaries and settings. Install both APKs and run it with `am instrument` instead:

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.dokuendev.dokuen.plugins.dictionary.yomitan.anki.AnkiDroidDeviceTest io.github.dokuendev.dokuen.plugins.dictionary.yomitan.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` restarts the plugin's process, which the host does not recover from on its own:
restart the plugin's service in the host afterwards.

For the manual, device-level verification procedure (setup, pinned revisions of five real
dictionaries as test data, and step-by-step parity checks against desktop Yomitan), see
[`anki-export-manual-test-plan.md`](anki-export-manual-test-plan.md).

That plan is driven by `scripts/anki_test_plan.py`. Its `setup` command, run before the manual
steps, builds and installs the plugin and its test helper, creates the plan's decks and note types
on both platforms, resets the plugin's Anki settings to the defaults, checks the plugin's
dictionaries, writes the desktop Yomitan settings to import, and serves the plan's test page, which
every lookup of the plan scans, to both platforms. Its `check` command, run after them, reads
the plugin's stored Anki settings and every note the plan added from both platforms and makes the
plan's checks, reporting each result under the plan's check ID.

The script reaches desktop Anki through AnkiConnect. The adb shell has no permission to query
AnkiDroid's provider or to read the plugin's settings, so on Android it runs
`AnkiTestPlanHelper`, instrumented tests in `app/src/androidTest/.../anki/` that run in the
plugin's own process. Each test is one action (dump or delete the notes matching a search, create
decks and note types, reset the plugin's Anki settings, dump the plugin's dictionaries and
settings), chosen by the `action` instrumentation argument and skipped unless named, so running
the other instrumented tests runs none of them. Each writes its result as JSON to the app's files directory, which the script reads
back with `run-as`. Like any `am instrument` run, each restarts the plugin's process.
