# Anki Export: Reference Capture

The [test plan](test-plan.md) compares the plugin's Anki export with desktop Yomitan's, the
reference. Desktop's side changes only when a new desktop Yomitan version or new dictionary
revisions are adopted, so it is captured once, by this procedure, and committed:

- desktop's notes, as `scripts/anki_test_plan_reference.json` (the golden file), which the test
  plan's check script compares the plugin's notes with;
- what desktop shows on screen (labels, options, entry lists, messages, and button states), as
  values written into the test plan's steps and its R7.

Do this again only when one of those changes. Follow the phases in order and do every step exactly
as written: the golden file is only useful if every note is added in the order given here.

Steps have IDs such as `D4.13`; the test plan's own steps have no `D`. "Record" means write the
value down for "Updating the test plan" at the end.

Throughout:

- **"Scan `X`"** means: in the test page that `reference-setup` serves
  (`http://localhost:8766/anki-test-page.html`, open in the desktop browser), hold **Shift** and
  move the mouse pointer onto the first character of `X`, which is on a line of its own.
- **"Add `E` via `F`"** means: in the popup, on the entry `E` (shown as headword【reading】), click
  the card format `F`'s button. The popup has other entries too; always use the one named.
- If you add a note by mistake, delete that note right away in Anki's **Browse**: the golden file
  identifies notes by the order they were added.

## Phase D1: Before you start

Already installed: Anki with the AnkiConnect add-on, Python 3, a checkout of this repository, and a
browser with Yomitan (scanning with **Shift**, its default) and the five dictionaries of the test
plan's R3 imported.

- [ ] **D1.1** In Yomitan's settings, **Dictionaries**: confirm the five dictionaries of R3 are imported, each at exactly R3's revision. If a revision differs, this capture is for new dictionary revisions: see "Updating the test plan".
- [ ] **D1.2** Note Yomitan's version (on its settings page, or in the browser's extensions page). The golden file records it. (AnkiConnect reports only its own API version, not Anki's, so the golden file does not record Anki's version.)
- [ ] **D1.3** Start Anki.
- [ ] **D1.4** In Yomitan's settings, **Backup** → **Export settings**. Note where the file is saved.

## Phase D2: Setup (desktop terminal)

- [ ] **D2.1** In the repository checkout, run `python scripts/anki_test_plan.py reference-setup --yomitan-settings <the file from D1.4>`. If it reports notes left in `AuditTest` by an earlier capture, run it again with `--clean` added, which deletes them from desktop Anki.
- [ ] **D2.2** Confirm it prints **Setup complete**. Leave it running until D8.1: it serves the test page that every scan uses.
- [ ] **D2.3** Wait until every dictionary import has finished, which is when Yomitan's settings page no longer shows an import under **Tasks in progress**. A dictionary can be listed before its import has finished, and an import updates the settings when it finishes, so settings imported before then can be left with a dictionary disabled. Then, in Yomitan's settings, **Backup** → **Import settings**, and import `yomitan-settings-anki-test-plan.json` from the folder `reference-setup` wrote (`anki-test-plan-reports/<date-time>-reference-setup/`). This sets desktop's dictionaries, frequency sorting, duplicate settings, audio settings (no sources, with the default sources enabled, and no idle download timeout), and the 5 card formats of the test plan's R2. In **Dictionaries**, confirm the five dictionaries of R3 are enabled, in R3's order, and that every other dictionary is disabled.
- [ ] **D2.4** In the **Anki** section, open **Configure Anki flashcards…**. Confirm it lists `AuditVocab`, `AuditKanjiFmt`, `AuditSub`, `AuditQuoteKey`, and `AuditBadMarker`, in that order.

## Phase D3: Settings

Open Yomitan's settings at the **Anki** section, with advanced settings shown. Record what each
step asks for; the test plan's Phase 5 checks that the plugin matches it.

- [ ] **D3.1** Confirm **Enable Anki integration**, the toggle at the top of the Anki section, is on.
- [ ] **D3.2** Confirm the AnkiConnect server address and API key settings are under advanced settings.
- [ ] **D3.3** Record the label of the tags text field (expected: **Card tags**).
- [ ] **D3.4** Record the label of the duplicate checking toggle (expected: **Check for card duplicates**).
- [ ] **D3.5** Record the label of the toggle shown while duplicate checking is on (expected: **Check for duplicates across all models**).
- [ ] **D3.6** Record the options of **Duplicate card scope**, worded and ordered as shown (expected: **Collection**, **Deck**, **Deck root**).
- [ ] **D3.7** Record the options of **When a duplicate is detected**, worded and ordered as shown (expected: **Prevent adding**, **Allow overwriting**, **Allow adding**).
- [ ] **D3.8** Confirm the screenshot format and quality settings are under advanced settings.
- [ ] **D3.9** Confirm the idle download timeout setting is under advanced settings.
- [ ] **D3.10** Confirm **Suspend new cards** is under advanced settings.
- [ ] **D3.11** Confirm there is a note viewer window choice (**Browse** or **Edit**).
- [ ] **D3.12** Confirm there is a **Show card tags and flags** setting.
- [ ] **D3.13** Confirm **Force Anki sync on adding card** is under advanced settings.
- [ ] **D3.14** Open **Configure Anki flashcards…**. Confirm it lists the five formats of D2.4 in that order, and that it has no **Save** or **Cancel** button: every edit is kept as it is made.
- [ ] **D3.15** Confirm **Configure Anki card templates…** is under advanced settings.
- [ ] **D3.16** Turn **Check for card duplicates** off. Confirm the scope and behavior controls disappear. Turn it back on. Confirm they reappear.
- [ ] **D3.17** Set "When a duplicate is detected" to **Allow overwriting**. In **Configure Anki flashcards…**, open the overwrite-mode menu next to `AuditVocab`'s `Expression` field. Record its entries, in order (expected: **Skip**, **Prepend**, **Append**, **Fill if empty**, **Overwrite if available**, **Overwrite**).
- [ ] **D3.18** Set "When a duplicate is detected" back to **Allow adding**. Confirm desktop now reads:
  - **Check for card duplicates**: on
  - **Check for duplicates across all models**: off
  - **Duplicate card scope**: **Collection**
  - **When a duplicate is detected**: **Allow adding**
- [ ] **D3.19** Try to add a sixth card format. Confirm desktop refuses with a message, rather than doing nothing or crashing, and that no sixth format is left behind. Record the message.
- [ ] **D3.20** In the **Audio** section, open **Configure audio playback sources…**. Record the dialog's title, the label of the default sources toggle and its description (expected: **Enable Default Audio Sources**, with no description), and the explanation of the source order (expected: "When searching for audio, the sources are checked in order until the first valid source is found. This allows for selecting a fallback source if the first choice is not available.").
- [ ] **D3.21** Confirm the source list is empty and that the default sources toggle is on. Record what the dialog shows in place of the empty list (expected: nothing, because desktop's stylesheet always hides the dialog's "No audio sources enabled" element). Record the add button's label (expected: **Add**).
- [ ] **D3.22** Click **Add** three times. Record the types added, in order (expected: JapanesePod101, Jisho.org, LanguagePod101). Open the first source's type menu and record its options, in order (expected: JapanesePod101, LanguagePod101, Jisho.org, (Commons) Lingua Libre, (Commons) Wiktionary, Text-to-speech ⚠️, Text-to-speech (Kana reading) ⚠️, Custom URL, Custom URL (JSON)). Remove the three sources, using the menu on each source.
- [ ] **D3.23** Click **Add**, and choose **Custom URL** for the new source. Confirm a URL field appears. Remove the source.
- [ ] **D3.24** In the **Anki** section, with advanced settings shown, record the idle download timeout's label and description (expected: **Idle download timeout (in milliseconds)**, "The maximum time before an idle download will be cancelled; 0 = no limit.") and its value (expected: `0`).

## Phase D4: Add the notes

Add these notes in exactly this order.

Steps D4.24 to D4.35 change desktop's audio settings. A change applies to the next add, so each
of those steps sets the audio sources, then scans and adds its note. D4.35 restores the defaults,
so the notes added after Phase D4 are unaffected. To set the sources in a step, open
**Configure audio playback sources…** in the **Audio** section, remove any sources left over, set
**Enable Default Audio Sources** as the step says, and use **Add** for each source named, in
order. For a custom URL, choose **Custom URL** (or **Custom URL (JSON)**) as the source's type and
type the URL into its field.

- [ ] **D4.1** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **D4.2** Scan `動き出す`. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **D4.3** Scan `お見舞い`. Add お見舞い【おみまい】 via `AuditVocab`.
- [ ] **D4.4** Scan `食べさせられた`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **D4.5** Scan `牛`. Add 牛【ぎゅう】 (not 牛【うし】) via `AuditVocab`.
- [ ] **D4.6** Scan `京都`. Confirm the 京都【きょうと】 entry shows two definitions, Jitendex's first and JMnedict's second. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **D4.7** Scan `学習辞典`. Add 学習辞典【がくしゅうじてん】 via `AuditVocab`.
- [ ] **D4.8** Scan `時雨`. Add 時雨【しぐれ】 (not 時雨【じう】) via `AuditVocab`.
- [ ] **D4.9** Scan `鳥居`. Add 鳥居【とりい】 via `AuditVocab`.
- [ ] **D4.10** Scan `千鳥`. Add 千鳥【ちどり】 via `AuditVocab`.
- [ ] **D4.11** Scan `順路`. Add 順路【じゅんろ】 via `AuditVocab`.
- [ ] **D4.12** Scan `記号`. Add 記号【きごう】 via `AuditVocab`.
- [ ] **D4.13** Scan `學校`. Record every entry the popup shows, in order, as headword【reading】 and the dictionary of each. Add the entry whose headword is `學校` via `AuditVocab`.
- [ ] **D4.14** Scan `食べる`. In the popup, click `食` in the 食べる【たべる】 entry's headword. Add the 食 kanji entry via `AuditKanjiFmt`.
- [ ] **D4.15** In the test page's paragraph, hold **Shift** and move the mouse pointer onto `動`, the first character of `動き出す`. In the popup, add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **D4.16** In the same popup, click `動` in the 動き出す【うごきだす】 entry's headword. Add the 動 kanji entry via `AuditKanjiFmt`.
- [ ] **D4.17** In Yomitan's settings, **Anki** section: clear **Card tags**, then type exactly `jlpt-n3, vocab;core2k listening` (a comma and space, then a semicolon with no space, then a plain space). Click outside the field. Record what it now reads (expected: `jlpt-n3 vocab core2k listening`: desktop rewrites the tags one space apart when the field loses focus).
- [ ] **D4.18** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **D4.19** In Yomitan's settings, clear **Card tags** so it is empty.
- [ ] **D4.20** Scan `食べる`. Add 食べる【たべる】 via `AuditBadMarker`. Confirm the note is added and an error names the unknown marker. Record the error exactly (expected: `An error occurred:`, then on a line of its own `Template render error for {expresion}`) and where it is shown.
- [ ] **D4.21** In Yomitan's settings, **Dictionaries**: move `JMnedict [2026-09-25]` above `Jitendex.org [2026-05-05]`.
- [ ] **D4.22** Scan `京都`. Confirm the 京都【きょうと】 entry now shows JMnedict's definition first. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **D4.23** In **Dictionaries**, move `Jitendex.org [2026-05-05]` back above `JMnedict [2026-09-25]`. Confirm the order is Jitendex, JMnedict, KANJIDIC, JPDBv2㋕, BCCWJ.
- [ ] **D4.24** Set the sources to LanguagePod101 alone, with **Enable Default Audio Sources** off. Scan `記号`. Add 記号【きごう】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.25** Set the sources to Jisho.org alone, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.26** Set the sources to (Commons) Lingua Libre alone, with **Enable Default Audio Sources** off. Scan `牛`. Add 牛【ぎゅう】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.27** Set the sources to (Commons) Wiktionary alone, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Confirm the note is added (expected: the note's `audio` field is empty, and no error is shown, because desktop finds no Wiktionary file for any Japanese word).
- [ ] **D4.28** Set the sources to a Custom URL of `http://localhost:8766/audio/custom/{language}/{term}/{reading}` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.29** Set the sources to a Custom URL (JSON) of `http://localhost:8766/audio/list/{term}` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.30** Set the sources to a Custom URL of `http://localhost:8766/audio/missing`, then Jisho.org, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.31** Set the sources to a Custom URL of `http://localhost:8766/audio/missing` alone, with **Enable Default Audio Sources** on. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.32** Set the sources to a Custom URL of `http://localhost:8766/audio/file/unknown` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added.
- [ ] **D4.33** Set the sources to a Custom URL of `http://localhost:8766/audio/stall` alone, with **Enable Default Audio Sources** off. In the **Anki** section, with advanced settings shown, set the idle download timeout to `2000`. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added. Record whether desktop shows anything, and if so, what and where (expected: nothing, although desktop's code means to report the idle timeout).
- [ ] **D4.34** Set the idle download timeout back to `0`. Set the sources to a Custom URL of `http://localhost:8767/audio/closed` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added. Record whether desktop shows anything (expected: nothing).
- [ ] **D4.35** Remove every audio source, and turn **Enable Default Audio Sources** on. Confirm the idle download timeout is `0`. Nothing is scanned or added.

## Phase D5: Save the notes (desktop terminal)

- [ ] **D5.1** In a second terminal (`reference-setup` is still serving the test page in the first), run `python scripts/anki_test_plan.py reference-save --yomitan-version <the version from D1.2>`. Confirm it prints `Wrote 30 notes`. If it reports a problem instead, it has written nothing: add a missing note, or delete an extra one in Anki's **Browse**, and run it again.

## Phase D6: Link states

The colors of the test plan's R7: green (can add), orange (a duplicate exists and would be
overwritten), purple (a duplicate exists and another note would be added), gray (a duplicate
exists and adding is prevented).

- [ ] **D6.1** In Yomitan's settings, set "When a duplicate is detected" to **Allow overwriting**.
- [ ] **D6.2** Scan `猫`, and then `犬` and, in its popup, click `犬` in the 犬【いぬ】 entry's headword (words no step adds, so no duplicate exists). Record the color of the **AuditVocab**, **AuditSub**, **AuditQuoteKey**, and **AuditBadMarker** buttons (on 猫【ねこ】) and the **AuditKanjiFmt** button (on the 犬 kanji entry) (expected: all the same green, told apart only by shape).
- [ ] **D6.3** Scan `食べる` (notes for it now exist). On 食べる【たべる】, record which variant each of the **AuditVocab**, **AuditSub**, **AuditBadMarker**, and **AuditQuoteKey** buttons shows (expected: the first three their overwrite (orange) variant, and **AuditQuoteKey** green, since no note's first field is `食べる"`). Click **AuditQuoteKey**. Record its color now (expected: orange too).
- [ ] **D6.4** Set "When a duplicate is detected" to **Allow adding**. Scan `食べる`. Record which variant the four buttons on 食べる【たべる】 show (expected: their add-duplicate (purple) variant).

## Phase D7: An add without Anki

- [ ] **D7.1** Scan `食べる`, and leave its popup open. Close Anki. Without scanning again (a scan while Anki is closed shows no card format buttons, so record that too), click **AuditVocab**. Record the error desktop shows, exactly, and where it is shown (expected: `An error occurred:`, then on a line of its own `Anki connection failure`).

## Phase D8: Finish

- [ ] **D8.1** In the `reference-setup` window, press **Enter** to stop serving the test page.
- [ ] **D8.2** Update the test plan as below, and commit it together with `scripts/anki_test_plan_reference.json`.

## Updating the test plan

Write each recorded value into the test plan where it is stated as desktop's. Where a value
differs from the one the test plan states now, the plugin's expected behavior may change with
it: look at the steps that check it, and at the plugin.

| Recorded in | Goes into the test plan's |
|---|---|
| D3.1 to D3.16 | steps 5.1 to 5.16, and R7's Desktop column |
| D3.17 | step 5.18 (the menu's entries, in order) |
| D3.18 | step 5.20 |
| D3.19 | step 5.21, and R7's "Card format count" row |
| D3.20 to D3.24 | steps 3.8, 5.9, and 5.22 to 5.25, R7's audio rows, and the plugin's audio strings (if a value differs, fix the plugin's string to match desktop) |
| D4.13 | step 6.13 (the entry list), and the runner's `GAKKOU_ENTRIES` in `scripts/anki_test_plan_android.py` (headwords and readings, in order) |
| D4.17 | step 6.19 |
| D4.20 | step 6.22, and R7's "An add with an unknown marker" row |
| D4.33 | step 6.35, and R7's timeout message row |
| D4.34 | step 6.36 |
| D6.2, D6.3, and D6.4 | steps 9.2, 9.3, and 9.5, and R7's "Duplicate state before a tap" and "Icons" rows |
| D7.1 | R7, as desktop's error when Anki is not running (the plugin's own errors are steps 10.3 and 10.4) |

When the dictionary revisions changed (D1.1), also update the test plan's R3 and
`DICTIONARIES` in `scripts/anki_test_plan.py`, and look at every expected value of the test
plan's R8 that comes from a dictionary's contents.
