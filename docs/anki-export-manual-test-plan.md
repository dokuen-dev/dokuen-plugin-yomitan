# Anki Export: Manual Test Plan

A device-level parity procedure for the plugin's Anki export, run side by side against desktop
Yomitan. It is written so that two different testers produce identical results, not merely similar
ones: every deck, note type, card format, dictionary revision, and lookup word is fixed in advance.

The document has two parts:

- **Part 1, Procedure**: the steps to follow, in order, from start to finish. Only this part is
  needed while testing.
- **Part 2, Reference**: why the plan is built the way it is, the test data, what the two scripts
  do, and every check the check script makes, with its expected values.

Every step in Part 1 has an ID such as `7.13`, and every check in Part 2 has one such as `C2.7`.
Quote the ID when reporting an issue.

See [`anki-export.md`](anki-export.md) for the architecture of the feature and the accepted gaps
this plan refers to.

---

# Part 1: Procedure

Follow the phases in order and do every step exactly as written. Each phase says where it is done.

Throughout:

- **"Scan `X`"** means looking up `X` from the test page that setup serves (R5), where `X` is on a
  line of its own: on desktop, in the page in the desktop browser, hold **Shift** and move the
  mouse pointer onto the first character of `X`; on Android, in the page in Chrome, use Dokuen to
  select exactly `X` (its characters only, not the `。` after it). Every lookup of the plan is a
  scan: Dokuen has no dictionary search, and scanning the same text gives both platforms the same
  query and sentence.
- **"Add `E` via `F`"** means: in the lookup's results, on the entry `E` (shown as
  headword【reading】), click the card format `F`'s button (desktop) or tap its **Add: F** link
  (plugin). The result has other entries too; always use the one named.
- **Changing a plugin setting** (anything in its dashboard) is only possible while Dokuen's overlay,
  which holds the furigana overlay and the dictionary window, is hidden. When it is shown, first tap
  Dokuen's floating scan button to hide it; the session keeps running. Make the change, then tap the
  floating button to scan again. A result shown before the change is gone, so a step that then taps
  a link says to scan the word again.
- Everything a step does not ask you to confirm is checked by the check script in Phase 10.
- If you add a note by mistake, delete that note right away (desktop: Anki's **Browse**; Android:
  AnkiDroid's card browser). The check script identifies notes by the order they were added.
- AnkiDroid's card browser only searches the deck selected in it. Whenever a step counts notes in
  it, first choose **All decks** in its deck menu.
- To see a note's stored field values, use the check script's report rather than AnkiDroid's note
  editor, which does not show them exactly (see "Reading a note's fields by hand" in R6).

## Phase 1: Before you start

Already installed (this plan does not cover installing them): on desktop, Anki with the AnkiConnect
add-on, Python 3, `adb`, a checkout of this repository with what its README lists for building it,
and a browser with Yomitan (scanning with **Shift**, its default) and the five dictionaries of R3
imported; on Android, AnkiDroid (from the Play Store, first-run
setup done), the host app (`dokuen-android`), and this plugin with the five dictionaries of R3
imported, enabled in R3's order (Up/Down arrows), no other dictionary enabled, and no sort dictionary
chosen.

- [ ] **1.1** Desktop: start Anki.
- [ ] **1.2** Desktop: in Yomitan's settings, **Backup** → **Export settings**. Note where the file is saved.
- [ ] **1.3** Connect the device to the desktop with USB debugging on. In a terminal, confirm `adb devices` lists it.
- [ ] **1.4** Android: open the host app and confirm its dashboard lists the plugin.

## Phase 2: Setup script (desktop terminal)

- [ ] **2.1** In the repository checkout, run `python scripts/anki_test_plan.py setup --yomitan-settings <the file from 1.2>`. If it reports notes left in `AuditTest` by an earlier run, run it again with `--clean` added, which deletes them on both platforms.
- [ ] **2.2** Confirm it prints **Setup complete**. If it prints **Setup found problems** instead, fix each problem it lists and run it again.
- [ ] **2.3** Leave it running until the end of Phase 12. It serves the test page (R5) that every lookup scans, and has opened it in the desktop browser and in Chrome on the device. (If you stop and resume another day, `python scripts/anki_test_plan.py serve` serves it again.)

## Phase 3: Plugin defaults, AnkiDroid and permissions (Android)

Setup reset the plugin's Anki settings to the defaults.

- [ ] **3.1** Desktop terminal: run these two commands. The first withdraws the plugin's AnkiDroid permission (and closes the plugin if it is open); the second uninstalls AnkiDroid but keeps its collection, which comes back when AnkiDroid is installed again in 3.3.
  - `adb shell pm revoke io.github.dokuendev.dokuen.plugins.dictionary.yomitan com.ichi2.anki.permission.READ_WRITE_DATABASE`
  - `adb shell pm uninstall -k --user 0 com.ichi2.anki`
- [ ] **3.2** Android: open the plugin's dashboard, **Anki Export** tab. Confirm it shows only the **Enable Anki export** switch, off, and nothing below it: the other settings and the card formats appear only once it is on.
- [ ] **3.3** Tap **Enable Anki export**. Confirm an "AnkiDroid required" dialog appears with an **Install AnkiDroid** button. Tap the button and confirm the Play Store (or a browser) opens at AnkiDroid. Install AnkiDroid from the Play Store (if a browser opened, from the Play Store app), then return to the plugin's **Anki Export** tab.
- [ ] **3.4** Tap **Enable Anki export**. Confirm an "Allow Anki access" dialog appears, and tap **Continue**. Confirm Android's permission prompt appears, and tap **Don't allow**. Confirm the switch stays off and a row reading "Anki export needs AnkiDroid access to add cards." appears with a **Grant permission** button.
- [ ] **3.5** Tap **Grant permission**, then **Continue**, and allow the permission. Confirm **Enable Anki export** turns on.
- [ ] **3.6** In Android's Settings → Apps → (this plugin) → Permissions, revoke the AnkiDroid permission. Return to the plugin. Confirm **Enable Anki export** is still on and a "Permission needed" row with a **Grant permission** button is shown.
- [ ] **3.7** Tap **Grant permission**, then **Continue**, and allow the permission. Confirm **Enable Anki export** is on and no permission row is shown.
- [ ] **3.8** Confirm the tab now shows the defaults, from top to bottom:
  - **Card tags**: `dokuen-yomitan`
  - **Check for card duplicates**: on
  - **Check for duplicates across all models**: off
  - **Duplicate card scope**: **Collection**
  - **When a duplicate is detected**: **Allow adding**
  - **Card Formats**: exactly three, none with a deck or note type:
    - **Expression** (Term, Big Circle)
    - **Reading** (Term, Small Circle)
    - **Kanji** (Kanji, Big Circle)
- [ ] **3.9** Confirm **Show card tags and flags**, or anything like it, is nowhere in the Anki Export tab.

## Phase 4: Desktop Yomitan settings (desktop)

- [ ] **4.1** In Yomitan's settings, **Backup** → **Import settings**, and import `yomitan-settings-anki-test-plan.json` from the folder setup wrote (`anki-test-plan-reports/<date-time>-setup/`). This sets desktop's dictionaries, frequency sorting, duplicate settings, and the 5 card formats (R2).
- [ ] **4.2** In the **Anki** section, open **Configure Anki flashcards…**. Confirm it lists `AuditVocab`, `AuditKanjiFmt`, `AuditSub`, `AuditQuoteKey`, and `AuditBadMarker`, in that order.

## Phase 5: Plugin card formats (Android)

In the plugin's **Anki Export** tab, build the 5 card formats in the tab-strip editor. Every value
is saved as you type; there is no Save button. For each format, set the note type **last**:
choosing it fills in every field, and changing the type afterwards would not refill them.

- [ ] **5.1** Open the **Expression** format's tab. Set name `AuditVocab`, type **Term**, icon **Big Circle**, deck `AuditTest`, then note type `AuditTerm`. Confirm 44 field rows appear, named as the note type's fields (starting `Expression`, `Reading`, `Glossary`). Confirm every field shows `{` + its own name in lower case + `}` (`Expression` shows `{expression}`, `furigana-plain` shows `{furigana-plain}`), except `single-frequency-number`, which stays empty (R2). Note any other field that differs, then type the correct value into it. Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **5.2** Open the **Reading** format's tab. Set name `AuditKanjiFmt`, type **Kanji**, icon **Diamond**, deck `AuditTest`, then note type `AuditKanji`. Confirm 11 field rows appear and every field shows `{` + its own name in lower case + `}` (`Character` shows `{character}`). Note any field that differs, then type the correct value into it.
- [ ] **5.3** Open the **Kanji** format's tab. Set name `AuditSub`, type **Term**, icon **Square**, deck `AuditTest::Sub`, then note type `AuditTerm`. Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **5.4** Tap **+** to add a format. Set name `AuditQuoteKey`, type **Term**, icon **Small Circle**, deck `AuditTest`, then note type `AuditTerm`. Set `Expression` to `{expression}"` (the marker, then one double quote, with no space: 13 characters). Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **5.5** Tap **+**. Set name `AuditBadMarker`, type **Term**, icon **Square**, deck `AuditTest`, then note type `AuditTerm`. Set `Reading` to `{expresion}` (misspelled on purpose, with one `s`). Set `single-frequency-number` to `{single-frequency-number}`.

## Phase 6: Settings side by side (desktop and Android)

Open desktop Yomitan's settings at the **Anki** section, with advanced settings shown, next to the
plugin's **Anki Export** tab. Steps 6.1 to 6.15 compare the two; R7 explains each expected
difference.

- [ ] **6.1** **Enable Anki integration**: desktop's toggle at the top of the Anki section; the plugin's **Enable** switch. Confirm both are on.
- [ ] **6.2** **AnkiConnect server address and API key**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.3** **Card tags**: a text field on both. Confirm both label it **Card tags**. Its value is not compared: the default tag differs on purpose (R7).
- [ ] **6.4** **Check for card duplicates**: a toggle on both. Confirm both label it **Check for card duplicates**.
- [ ] **6.5** **Check for duplicates across all models**: a toggle on both, shown while **Check for card duplicates** is on. Confirm both label it **Check for duplicates across all models**.
- [ ] **6.6** **Duplicate card scope**: confirm both offer **Collection**, **Deck**, and **Deck root**, worded the same.
- [ ] **6.7** **When a duplicate is detected**: confirm both offer **Prevent adding**, **Allow overwriting**, and **Allow adding**, worded the same.
- [ ] **6.8** **Screenshot format and quality**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.9** **Idle download timeout**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.10** **Suspend new cards**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.11** **Note viewer window** (Browse or Edit): a choice on desktop. Confirm the plugin has no equivalent.
- [ ] **6.12** **Show card tags and flags**: on desktop. Confirm the plugin has no equivalent (see also 3.9).
- [ ] **6.13** **Force Anki sync on adding card**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.14** **Configure Anki flashcards…**: a dialog on desktop, the always-visible tab-strip editor on the plugin. Confirm both list the same five formats in the same order, and that neither has a **Save** or **Cancel** button: every edit is kept as it is made. (The check script confirms that the plugin kept every value, C1.)
- [ ] **6.15** **Configure Anki card templates…**: on desktop, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **6.16** On both, turn **Check for card duplicates** off. Confirm the scope and behavior controls disappear on both. Turn it back on. Confirm they reappear on both.
- [ ] **6.17** Plugin: turn **Check for duplicates across all models** on. Put the plugin in the background, reopen it, and confirm it is still on. Turn it back off.
- [ ] **6.18** On both, set "When a duplicate is detected" to **Allow overwriting**. Each field of each card format now has an overwrite-mode menu of its own: on desktop, next to the field in **Configure Anki flashcards…**; on the plugin, labeled **Overwrite**, below the field's value in the format's tab (scroll past the deck and note type to the field rows). Open `AuditVocab`'s `Expression` field's menu on both. Confirm the plugin's offers exactly, in this order, what desktop's offers: **Skip**, **Prepend**, **Append**, **Fill if empty**, **Overwrite if available**, **Overwrite**.
- [ ] **6.19** Plugin, still in **Allow overwriting**: in `AuditVocab`, set the `Glossary` field's **Overwrite** menu to **Append**, and confirm every other field's reads **Fill if empty**. (Desktop's came from 4.1.)
- [ ] **6.20** On both, set "When a duplicate is detected" back to **Allow adding**. Confirm both now read:
  - **Check for card duplicates**: on
  - **Check for duplicates across all models**: off
  - **Duplicate card scope**: **Collection**
  - **When a duplicate is detected**: **Allow adding**
- [ ] **6.21** On both, try to add a sixth card format. Confirm both refuse with a message, rather than doing nothing or crashing, and that no sixth format is left behind.

## Phase 7: Add the notes (desktop)

Add these notes on desktop, in exactly this order, scanning the test page in the desktop browser
(`http://localhost:8766/anki-test-page.html`).

- [ ] **7.1** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **7.2** Scan `動き出す`. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **7.3** Scan `お見舞い`. Add お見舞い【おみまい】 via `AuditVocab`.
- [ ] **7.4** Scan `食べさせられた`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **7.5** Scan `牛`. Add 牛【ぎゅう】 (not 牛【うし】) via `AuditVocab`.
- [ ] **7.6** Scan `京都`. Confirm the 京都【きょうと】 entry shows two definitions, Jitendex's first and JMnedict's second. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **7.7** Scan `学習辞典`. Add 学習辞典【がくしゅうじてん】 via `AuditVocab`.
- [ ] **7.8** Scan `時雨`. Add 時雨【しぐれ】 (not 時雨【じう】) via `AuditVocab`.
- [ ] **7.9** Scan `鳥居`. Add 鳥居【とりい】 via `AuditVocab`.
- [ ] **7.10** Scan `千鳥`. Add 千鳥【ちどり】 via `AuditVocab`.
- [ ] **7.11** Scan `順路`. Add 順路【じゅんろ】 via `AuditVocab`.
- [ ] **7.12** Scan `記号`. Add 記号【きごう】 via `AuditVocab`.
- [ ] **7.13** Scan `學校`. Write down every entry the result shows, in order (headword and dictionary of each). Add the entry whose headword is `學校` via `AuditVocab`.
- [ ] **7.14** Scan `食べる`. In the popup, click `食` in the 食べる【たべる】 entry's headword. Add the 食 kanji entry via `AuditKanjiFmt`.
- [ ] **7.15** In the test page's paragraph, hold **Shift** and move the mouse pointer onto `動`, the first character of `動き出す`. In the popup, add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **7.16** In the same popup, click `動` in the 動き出す【うごきだす】 entry's headword. Add the 動 kanji entry via `AuditKanjiFmt`.
- [ ] **7.17** In Yomitan's settings, **Anki** section: clear **Card tags**, then type exactly `jlpt-n3, vocab;core2k listening` (a comma and space, then a semicolon with no space, then a plain space). Click outside the field. Confirm it now reads `jlpt-n3 vocab core2k listening`: desktop rewrites the tags one space apart when the field loses focus.
- [ ] **7.18** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **7.19** In Yomitan's settings, clear **Card tags** so it is empty.
- [ ] **7.20** Scan `食べる`. Add 食べる【たべる】 via `AuditBadMarker`. Confirm the note is added and an error names the unknown marker: `An error occurred:`, then on a line of its own `Template render error for {expresion}`. Write down where it is shown.
- [ ] **7.21** In Yomitan's settings, **Dictionaries**: move `JMnedict [2026-09-25]` above `Jitendex.org [2026-05-05]`.
- [ ] **7.22** Scan `京都`. Confirm the 京都【きょうと】 entry now shows JMnedict's definition first. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **7.23** In **Dictionaries**, move `Jitendex.org [2026-05-05]` back above `JMnedict [2026-09-25]`. Confirm the order is Jitendex, JMnedict, KANJIDIC, JPDBv2㋕, BCCWJ.

## Phase 8: Add the notes (Android)

The same notes on the plugin, in the same order, scanning the test page in Chrome.

- [ ] **8.1** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **8.2** Scan `動き出す`. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **8.3** Scan `お見舞い`. Add お見舞い【おみまい】 via `AuditVocab`.
- [ ] **8.4** Scan `食べさせられた`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **8.5** Scan `牛`. Add 牛【ぎゅう】 (not 牛【うし】) via `AuditVocab`.
- [ ] **8.6** Scan `京都`. Confirm the 京都【きょうと】 entry shows two definitions, Jitendex's first and JMnedict's second. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **8.7** Scan `学習辞典`. Add 学習辞典【がくしゅうじてん】 via `AuditVocab`.
- [ ] **8.8** Scan `時雨`. Add 時雨【しぐれ】 (not 時雨【じう】) via `AuditVocab`.
- [ ] **8.9** Scan `鳥居`. Add 鳥居【とりい】 via `AuditVocab`.
- [ ] **8.10** Scan `千鳥`. Confirm the scan read `鳥`, not `島`, and that the entry's reading is ちどり. If not, correct the misread character with Dokuen's OCR repair feature and look it up again. Add 千鳥【ちどり】 via `AuditVocab`.
- [ ] **8.11** Scan `順路`. Add 順路【じゅんろ】 via `AuditVocab`.
- [ ] **8.12** Scan `記号`. Add 記号【きごう】 via `AuditVocab`.
- [ ] **8.13** Scan `學校`. Confirm the result shows the same entries, in the same order, as you wrote down in 7.13; any difference is a finding. Add the entry whose headword is `學校` via `AuditVocab`.
- [ ] **8.14** Scan `食べる`. Tap `食` in the 食べる【たべる】 entry's headword. Add the 食 kanji entry via `AuditKanjiFmt`.
- [ ] **8.15** In the test page's paragraph, use Dokuen to select exactly `動き出す` (the four characters, nothing before or after) in the middle sentence. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **8.16** In the same result, tap `動` in the 動き出す【うごきだす】 entry's headword. Add the 動 kanji entry via `AuditKanjiFmt`.
- [ ] **8.17** Hide Dokuen's overlay (see "Changing a plugin setting" at the top of Part 1; the same applies to every later step that changes a plugin setting). In the plugin's **Anki Export** tab: clear **Card tags**, then type exactly `jlpt-n3, vocab;core2k listening`.
- [ ] **8.18** Still in **Card tags**, type one more `, ` (comma and space) at the end. Confirm the text stays as you typed it while you type: it is neither wiped nor rewritten.
- [ ] **8.19** Press the keyboard's **Done** key. Confirm the field now reads `jlpt-n3 vocab core2k listening`, as desktop's did in 7.17.
- [ ] **8.20** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **8.21** Hide the overlay. In the **Anki Export** tab, clear **Card tags** so it is empty, and press **Done**.
- [ ] **8.22** Scan `食べる`. Add 食べる【たべる】 via `AuditBadMarker`. Confirm the note is added: instead of relabeling the link **Added**, the host shows the message `Note added to Anki, but card format "AuditBadMarker" uses unknown marker(s): {expresion}` in its own message area (a result the plugin refreshes cannot also carry a message, see R7). Compare it with desktop's message in 7.20: the general shape should match, not the wording or where it is shown.
- [ ] **8.23** Hide the overlay. In the plugin's **Dictionaries** tab, use the arrows to move `JMnedict [2026-09-25]` above `Jitendex.org [2026-05-05]`.
- [ ] **8.24** Scan `京都`. Confirm the 京都【きょうと】 entry now shows JMnedict's definition first. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **8.25** Hide the overlay. In **Dictionaries**, move `Jitendex.org [2026-05-05]` back above `JMnedict [2026-09-25]`. Confirm the order is Jitendex, JMnedict, KANJIDIC, JPDBv2㋕, BCCWJ.

## Phase 9: Duplicate detection (Android)

All on Android: settings in the plugin's **Anki Export** tab, notes in AnkiDroid (with **All decks**
chosen in its card browser). Use `AuditVocab` unless a step names another format. Every step that
sets something in the plugin hides Dokuen's overlay first (see "Changing a plugin setting" at the
top of Part 1), so each tap after a setting change is on a new scan, whose links read **Add**.

- [ ] **9.1** AnkiDroid: in the card browser, search `"Expression:椅子" or "Expression:鉛筆"`. Confirm it finds no notes.
- [ ] **9.2** Plugin: set scope **Collection**, across all models off, "When a duplicate is detected" = **Allow adding**. Scan `椅子` and tap **Add: AuditVocab** on 椅子【いす】. Confirm it succeeds: the link now reads **Added: AuditVocab**.
- [ ] **9.3** Set **Prevent adding**. Scan `椅子` again and tap **Add: AuditVocab**. Confirm "This note already exists in Anki." In AnkiDroid, confirm exactly 1 `椅子` note exists.
- [ ] **9.4** Set **Allow overwriting**. Scan `椅子` again and tap **Add: AuditVocab**. Confirm it succeeds.
- [ ] **9.5** Set **Allow adding**. Scan `椅子` again and tap **Add: AuditVocab**. In AnkiDroid, confirm 2 `椅子` notes now exist. Leave both.
- [ ] **9.6** AnkiDroid: open **Add**. Type `AuditTerm`, deck `AuditTest`, Expression `鉛筆`, Reading `えんぴつ`, Glossary `seed-deck`, every other field empty, tag `audit-fixture-dup-scope`. Save. (This is "Seed-Deck".)
- [ ] **9.7** Same again, but deck `AuditTest::Sub` and Glossary `seed-sub` ("Seed-Sub"). In the card browser, confirm exactly 2 `鉛筆` notes exist, one per deck.
- [ ] **9.8** Plugin: set **Prevent adding**, scope **Collection**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists".
- [ ] **9.9** Set scope **Deck**. Scan `鉛筆` again and tap **Add: AuditVocab**. Confirm "already exists" (Seed-Deck is in `AuditTest` itself).
- [ ] **9.10** AnkiDroid: search `"Expression:鉛筆" deck:AuditTest -deck:AuditTest::*` and delete that note (Seed-Deck). Search `"Expression:鉛筆"` and confirm exactly 1 note remains (Seed-Sub).
- [ ] **9.11** Plugin, scope still **Deck**: scan `鉛筆` and tap **Add: AuditVocab**. Confirm it is **not** a duplicate and a note is added (Seed-Sub is in a sub-deck, which **Deck** excludes).
- [ ] **9.12** AnkiDroid: delete the note just added, leaving only Seed-Sub.
- [ ] **9.13** Plugin: set scope **Deck root**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists" (**Deck root** includes `AuditTest::Sub`).
- [ ] **9.14** AnkiDroid: delete Seed-Sub, then create Seed-Deck again as in 9.6.
- [ ] **9.15** Plugin, scope **Deck root**: scan `鉛筆` and tap **Add: AuditSub** (whose deck is `AuditTest::Sub`). Confirm "already exists" (the search covers the root deck `AuditTest`, which holds Seed-Deck).
- [ ] **9.16** Set scope **Deck**. Scan `鉛筆` again and tap **Add: AuditSub**. Confirm it is **not** a duplicate and a note is added to `AuditTest::Sub` (Seed-Deck is outside `AuditTest::Sub`).
- [ ] **9.17** AnkiDroid: delete every `鉛筆` note. Open **Add**: type `AuditKanji` (a different note type), deck `AuditTest`, Character `鉛筆`, Kunyomi, Onyomi, and Glossary `n/a`, every other field empty. Save.
- [ ] **9.18** Plugin: set scope **Collection**, across all models off. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm it is **not** a duplicate (a different note type, not checked) and a note is added.
- [ ] **9.19** AnkiDroid: delete the note just added. Plugin: turn across all models **on**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists": across all models compares each note's first field, whatever its name, so the `AuditKanji` note, whose first field `Character` is `鉛筆`, is a duplicate.
- [ ] **9.20** Plugin: turn across all models off and set **Allow adding**. Scan `手紙` and tap **Add: AuditQuoteKey** on 手紙【てがみ】 (its Expression becomes `手紙"`). Confirm it succeeds.
- [ ] **9.21** Set **Prevent adding**. Scan `手紙` again and tap **Add: AuditQuoteKey**. Confirm "already exists". In AnkiDroid, search `手紙` and confirm exactly 1 note exists (the duplicate check found the note despite the quote in its first field).
- [ ] **9.22** Plugin: set **Allow adding**, scope **Collection**, across all models off.

## Phase 10: Check script (desktop terminal)

- [ ] **10.1** With Anki running on desktop, open a second terminal (setup is still serving the test page in the first) and run `python scripts/anki_test_plan.py check` in the repository checkout.
- [ ] **10.2** For every result that is not a PASS, look at its values in the report (`anki-test-plan-reports/<date-time>-check/report.md`) and the check it names in R8. Record: check ID, note, field, desktop value, plugin value, and whether it is a known exception from R9 (Y: which one / N: new). This table and the report folder are the parity evidence to keep.
- [ ] **10.3** Android: stop Dokuen's session (tap **Stop** on Dokuen's main page, or drag the floating button onto the X) and start a new one. Hiding the overlay is not enough here. The check script read the plugin's notes and settings by running its test helper inside the plugin's process, which restarts the plugin without the host noticing (R6); a new session reconnects the host to it.

## Phase 11: Icons and link states (desktop and Android)

The colors of R7: green (can add), orange (a duplicate exists and would be overwritten), purple (a
duplicate exists and another note would be added), gray (a duplicate exists and adding is
prevented).

- [ ] **11.1** On both, set "When a duplicate is detected" to **Allow overwriting** (on the plugin, with Dokuen's overlay hidden).
- [ ] **11.2** Desktop: scan `猫`, and then `犬` and, in its popup, click `犬` in the 犬【いぬ】 entry's headword (words no step adds, so no duplicate exists). Confirm the **AuditVocab**, **AuditSub**, **AuditQuoteKey**, and **AuditBadMarker** buttons (on 猫【ねこ】) and the **AuditKanjiFmt** button (on the 犬 kanji entry) are all the same green, told apart only by shape.
- [ ] **11.3** Plugin: scan `猫`, and then `犬` and tap `犬` in the 犬【いぬ】 entry's headword. Confirm **AuditVocab** is prefixed ●, **AuditSub** and **AuditBadMarker** ■, **AuditQuoteKey** ◉, and **AuditKanjiFmt** ◆, all in the same green.
- [ ] **11.4** Desktop: scan `食べる` (notes for it now exist). On 食べる【たべる】, confirm the **AuditVocab**, **AuditSub**, and **AuditBadMarker** buttons show their overwrite (orange) variant and **AuditQuoteKey** is green (no note's first field is `食べる"`). Click **AuditQuoteKey**. Confirm its button turns orange too.
- [ ] **11.5** Plugin: scan `食べる`. Confirm every link on 食べる【たべる】 is green and reads **Add**: the plugin checks for duplicates only once a link of the entry is tapped (R7). Tap **Add: AuditQuoteKey**. Confirm it now reads **Added: AuditQuoteKey**, and that it and the **AuditVocab**, **AuditSub**, and **AuditBadMarker** links are orange, as desktop's buttons ended in 11.4.
- [ ] **11.6** Tap **Added: AuditQuoteKey**. Confirm it now reads **Overwritten: AuditQuoteKey** and stays orange: like desktop's overwrite button, the second tap overwrote the note the first one added.
- [ ] **11.7** On both, set "When a duplicate is detected" to **Allow adding** (on the plugin, with Dokuen's overlay hidden). Desktop: scan `食べる`, and confirm the four buttons on 食べる【たべる】 show their add-duplicate (purple) variant. Plugin: scan `食べる` and tap **Add: AuditVocab**. Confirm it reads **Added: AuditVocab**, and that it and the other three links are purple, like desktop's buttons.
- [ ] **11.8** Plugin: confirm there is no link to view a note in AnkiDroid ("View in Anki", "Edit", or similar) anywhere (R7).

## Phase 12: Errors and edge cases (Android, then desktop)

- [ ] **12.1** Plugin: scan `食べる`. Tap `食` in the 食べる【たべる】 entry's headword, then use the dictionary window's back button to return to 食べる. Tap **Add: AuditVocab**. Confirm it succeeds (a result you go back to keeps working links).
- [ ] **12.2** Scan `かける`. Confirm the result has more than 10 entries. Tap **Add: AuditVocab** on the **first** entry, then on the **last**. Confirm both succeed, rather than reporting an expired link (every entry of a large result keeps working links).
- [ ] **12.3** Scan `食べる`. Then do this 10 times: tap `食` in the 食べる【たべる】 entry's headword, which opens the 食 kanji entry, and tap the dictionary window's back button, which returns to 食べる's result. After the tenth time, with 食べる's result showing, tap **Add: AuditVocab**. Confirm the message "This Anki link has expired. Please look up the word again.", not a crash and no note added: the plugin keeps the links of its 10 most recent lookups only, and each kanji tap was a lookup.
- [ ] **12.4** AnkiDroid: delete the `AuditTest::Sub` deck. Plugin: scan `手紙` and tap **Add: AuditSub**. Confirm a message that `AuditSub`'s deck `AuditTest::Sub` no longer exists. In AnkiDroid, search `手紙` and confirm it still finds exactly 1 note (the one from 9.20), so none was added.
- [ ] **12.5** AnkiDroid: create the deck `AuditTest::Sub` again. Plugin: hide Dokuen's overlay, and choose the deck again as `AuditSub`'s deck.
- [ ] **12.6** Desktop: close Anki. Scan `食べる` and click **AuditVocab**. Write down the error desktop shows, and confirm its general shape (not its wording) is like the plugin's error messages in 12.3 and 12.4.
- [ ] **12.7** Desktop: in the setup script's window, press **Enter** to stop serving the test page.

## Phase 13: Finish (Android)

- [ ] **13.1** Plugin: delete card formats until exactly one is left. Confirm the delete control is disabled for the last one.

---

# Part 2: Reference

## R1. How the plan works

The plan compares the plugin with desktop Yomitan, the reference, by exporting the same lookups on
both and comparing the notes. It runs in three parts: the setup script (Phase 2), the manual steps
(Phases 3 to 9), and the check script (Phase 10), followed by checks of what only the screen shows
(Phases 11 to 13).

The note types give every marker the plan checks a field of its own, named after the marker, so a
single add records every marker at once and no step ever edits a card format's fields once they
are built. The check script reads the notes from both platforms and makes every check of their
fields and of the plugin's stored settings (R8); the tester adds notes and checks only what the
screen shows. Desktop, being the reference, gets its configuration from a settings file the setup
script writes rather than from the tester, so only the plugin's configuration is built by hand
(Phase 5), which is what tests its format editor.

Every lookup is a scan of the same test page (R5), on both platforms: Dokuen has no dictionary
search, and scanning the same text gives both platforms the same query and sentence, so the
sentence and query markers can be compared too.

The steps that change state are ordered so that nothing later undoes what the check script needs:
the AnkiDroid and permission flows (Phase 3) come before any note exists, every note the check
script reads is added in Phases 7 to 9, and the steps that add further notes or delete decks
(Phases 11 and 12) come after the check.

## R2. Fixed vocabulary

Do not substitute your own names or words for these.

| | |
|---|---|
| **Decks** | `AuditTest` (root), `AuditTest::Sub` (its sub-deck) |
| **Note types** | `AuditTerm`, 44 fields: `Expression`, `Reading`, `Glossary`, then one field per other term marker, named after it. `AuditKanji`, 11 fields: `Character`, `Kunyomi`, `Onyomi`, `Glossary`, then one field per other kanji marker the plan checks. The exact lists are `TERM_FIELDS` and `KANJI_FIELDS` in `scripts/anki_test_plan.py`. Both show the first field on the front of the card and `Glossary` on the back. |
| **Dictionaries** | Exactly 5, at pinned revisions and in a fixed order (R3). |
| **Card formats** | Exactly 5, in this order: |

| # | Format | Type | Icon | Deck / Note type | Field values that are not the default |
|---|---|---|---|---|---|
| 1 | `AuditVocab` | Term | Big Circle | `AuditTest` / `AuditTerm` | `single-frequency-number` = `{single-frequency-number}`. Overwrite modes: `Glossary` = **Append**, every other field = **Fill if empty**. |
| 2 | `AuditKanjiFmt` | Kanji | Diamond | `AuditTest` / `AuditKanji` | None. |
| 3 | `AuditSub` | Term | Square | `AuditTest::Sub` / `AuditTerm` | `single-frequency-number` = `{single-frequency-number}`. |
| 4 | `AuditQuoteKey` | Term | Small Circle | `AuditTest` / `AuditTerm` | `Expression` = `{expression}"` (the marker plus one literal trailing double quote, 13 characters); `single-frequency-number` = `{single-frequency-number}`. |
| 5 | `AuditBadMarker` | Term | Square | `AuditTest` / `AuditTerm` | `Reading` = `{expresion}` (misspelled on purpose); `single-frequency-number` = `{single-frequency-number}`. |

Every other field's value is its default. Because every field is named after its marker, the
default is the intended value: choosing a note type fills the first field with `{expression}`
(term) or `{character}` (kanji), and every other field with the marker it is named after. The one
exception is `single-frequency-number`: a field is only filled with a marker from the original's
standard marker list, which leaves that marker out, so the field stays empty on both platforms and
is typed by hand (see "Settings UI" in `anki-export.md`). The note type is chosen last because
changing a format's type does not refill its fields (see "Quirks kept for parity" in
`anki-export.md`).

Each format's role: `AuditVocab` and `AuditKanjiFmt` add the notes the check script compares;
`AuditSub` targets the sub-deck, for the deck-root scope test (9.15, 9.16) and the missing-deck
test (12.4); `AuditQuoteKey` puts a double quote into the first field, which the duplicate check
compares (9.20, 9.21); `AuditBadMarker` holds an unknown marker (7.20, 8.22). Together they use all four icon shapes
(11.2, 11.3).

## R3. Dictionaries

Every expected value in this plan comes from these five dictionaries, at exactly these revisions,
enabled on both platforms in exactly this order (1 is the highest priority):

| # | Title | Revision (`index.json`) | Contents |
|---|---|---|---|
| 1 | `Jitendex.org [2026-05-05]` | `2026.05.05.0` | Terms |
| 2 | `JMnedict [2026-09-25]` | `JMnedict.2026-09-25` | Terms (proper names) |
| 3 | `KANJIDIC [2026-147]` | `kanjidic2.2026-147` | Kanji |
| 4 | `JPDBv2㋕` | `JPDB v2.2 Frequency Kana 2024-10-13` | Term frequencies; its `index.json` declares `"frequencyMode": "rank-based"` |
| 5 | `BCCWJ` | `1` | Term frequencies, the combined short-unit and long-unit build (`BCCWJ_SUW_LUW_combined.zip`, since the revision `1` alone does not identify the build); its `index.json` declares no `frequencyMode` |

Jitendex, JMnedict, and KANJIDIC publish new revisions regularly, and entries change between
revisions, so do not use Yomitan's update check or re-import any dictionary during a run. No other
dictionary may be enabled on either platform: another term dictionary adds its definitions to the
same popup entries and so changes every glossary marker, and another frequency dictionary changes
the frequency markers. The order matters wherever an entry has definitions or frequencies from more
than one dictionary, so it must be identical on both platforms. Sorting results by a frequency
dictionary must be off on both platforms (desktop: no frequency-sorting dictionary selected; plugin:
no sort dictionary chosen), so the popup's entry order is the same on both. The setup script checks
all of this on the plugin, and writes it into desktop's settings file.

JPDB gives many words two values: in its own description, a value whose display ends in `㋕` "is
used to indicate a frequency for a hiragana reading and is ignored in the internal frequency". BCCWJ
combines short-unit and long-unit word frequencies, so it too can give a word two values.

Jitendex stores each entry's whole definition (senses, example sentences, cross-references, forms
table, attribution links) as a single structured-content glossary item, styled by the dictionary's
`styles.css`. Its multi-word tag names (`priority form`, `old kanji form`, and so on) separate the
words with a non-breaking space (U+00A0), not an ordinary space; the two look identical on screen,
but the check script compares bytes. JMnedict's glossaries are plain strings, and neither JMnedict
nor the frequency dictionaries have a `styles.css`.

JMnedict adds a separate name entry, with an empty reading, for several of the test words (`牛`,
`時雨`, `鳥居`, `千鳥`, `順路`, `京都`, `椅子`). Such an entry is never used: the steps always name the
entry with the reading.

## R4. Test words and notes

The notes the check script compares, the steps that add them, and why each word was chosen. All
words are from Jitendex unless noted.

| Note | Desktop / plugin step | Word (entry) | Why |
|---|---|---|---|
| T1 | 7.1 / 8.1 | 食べる【たべる】 | The main marker word (rule `v1`, tag `★`); furigana with one kanji run and a kana tail; nested lists, ruby, and external links (C7). |
| T2 | 7.2 / 8.2 | 動き出す【うごきだす】 | Furigana with two kanji runs separated by kana (rule `v5`, tags `★` and `priority form`). |
| T3 | 7.3 / 8.3 | お見舞い【おみまい】 | Furigana with a kana prefix (no tags, no rule); internal `?query=` cross-reference links (C7). |
| T4 | 7.4 / 8.4 | 食べる【たべる】, scanned as 食べさせられた | `conjugation`: the deconjugation chain. A separate note from T1, not an overwrite of it. |
| T5 | 7.5 / 8.5 | 牛【ぎゅう】 | Two Jitendex entries share this expression and reading, so the popup entry groups two definitions: the `-first` glossary variants. Scanning 牛 also shows a separate 牛【うし】, which is not used. |
| T6 | 7.6 / 8.6 | 京都【きょうと】 | In both Jitendex (tag `★`) and JMnedict (tag `place`, glossary `Kyōto (city, prefecture)` and `Kyoto`), so the popup entry groups definitions from two dictionaries (C5). |
| T7 | 7.7 / 8.7 | 学習辞典【がくしゅうじてん】 | No value in either frequency dictionary: the frequency markers' empty case. |
| 時雨 | 7.8 / 8.8 | 時雨【しぐれ】 | The forms table, with a header cell spanning two columns (C7). A separate 時雨【じう】 entry is not used. |
| 鳥居 | 7.9 / 8.9 | 鳥居【とりい】 | A graphic image (C7). |
| 千鳥 | 7.10 / 8.10 | 千鳥【ちどり】 | An inline glyph image (C7). |
| 順路 | 7.11 / 8.11 | 順路【じゅんろ】 | A literal `->` in an example sentence's translation (C7). |
| 記号 | 7.12 / 8.12 | 記号【きごう】 | A literal `&` in an example sentence's translation (C7). |
| 學校 | 7.13 / 8.13 | 學校 (old kanji form of 学校, no separate reading) | A redirect entry, whose second glossary item points at `学校` (C7). Its old form, 學, is a standard Traditional Chinese character, which OCR reads reliably. |
| K1 | 7.14 / 8.14 | 食 (KANJIDIC), reached from 食べる【たべる】's headword | Kanji markers, and the sentence and query a kanji carries over from the term it was reached from. (A scan of `食` alone shows the term 食【しょく】, not the kanji, on the plugin.) |
| T8 | 7.15 / 8.15 | 動き出す【うごきだす】, scanned in the test page's paragraph (R5) | `sentence`, the cloze markers, and `search-query` (C4). |
| K2 | 7.16 / 8.16 | 動 (KANJIDIC), reached from T8's headword | A kanji that carries the term's sentence (C6). |
| TG | 7.18 / 8.20 | 食べる【たべる】, with card tags set | Card tag parsing (C9). |
| TB | 7.20 / 8.22 | 食べる【たべる】, via `AuditBadMarker` | An unknown marker (C10). |
| T6b | 7.22 / 8.24 | 京都【きょうと】, with JMnedict above Jitendex | Definitions follow the dictionary order (C5). |
| 椅子 | 9.2 and 9.5 (plugin only) | 椅子【いす】 | The overwrite test (C8). |

Other words used only on the plugin, in Phases 9 and 12: `鉛筆`【えんぴつ】 (duplicate scopes;
its notes are deleted along the way), `手紙`【てがみ】 (the quote in the first field, and the
missing deck), and `かける` (a kana word whose scan shows more than 10 entries). `猫` and `犬` are
only scanned, in 11.2 and 11.3, to show the icons of formats that have no duplicate.

Frequency data for the notes whose frequency markers the plan checks:

| Word | `JPDBv2㋕` | `BCCWJ` |
|---|---|---|
| 食べる / たべる (T1) | `184` and `13390㋕` | `199` and `266` |
| 京都 / きょうと (T6) | `6051` | `1429` |
| 学習辞典 / がくしゅうじてん (T7) | none | none |

Most other test words also have frequency data, but their frequency markers are not checked.

Kanji, both from KANJIDIC:

- `食`: onyomi `ショク ジキ`, kunyomi `く.う く.らう た.べる は.む`, meanings "eat", "food", 9 strokes.
- `動`: onyomi `ドウ`, kunyomi `うご.く うご.かす`, meanings "move", "motion", "change", "confusion", "shift", "shake", 11 strokes.

## R5. The test page

Every lookup of the plan scans the test page that the setup script serves at
`http://localhost:8766/anki-test-page.html` (on the device through `adb reverse`) and opens in the
desktop browser and in Chrome on the device. If either page is closed, open that address again
while the setup script, or `python scripts/anki_test_plan.py serve`, is running. The page holds,
in large text:

1. The words of Phases 7 and 8, each on a line of its own followed by `。`: `食べる。`, `動き出す。`,
   `お見舞い。`, `食べさせられた。`, `牛。`, `京都。`, `学習辞典。`, `時雨。`, `鳥居。`, `千鳥。`,
   `順路。`, `記号。`, `學校。`.
2. The paragraph T8 and K2 are scanned from: three sentences, with no spaces and no line breaks.

   ```
   雨が降っていた。電車が動き出す前に座った。窓の外は暗かった。
   ```

3. The words only Phases 9, 11, and 12 scan, in the same way: `椅子。`, `鉛筆。`, `手紙。`, `猫。`,
   `犬。`, `かける。`.

Both platforms cut the text around the match at the nearest sentence terminator (`。`) or line
break, so a word scanned from its own line has that line as its sentence (`{sentence}` of T1 is
`食べる。`), and only the middle sentence of the paragraph may appear in T8's `{sentence}` and cloze
markers. On the plugin the sentence is cut from the OCR block the host sends as context. The host
sends the whole block whenever the selection is contiguous and lies entirely within one block,
however short the selection is. The paragraph is one block, because it is ordinary running text.

The plugin's steps select the whole word rather than tapping one character, so that the lookup and
`{search-query}` are the same whether the host looks up only the selected text or reads on from
the start of the selection; desktop's scan from the word's first character gives the same result.
The plugin's lookups go through the host's OCR: if Dokuen's selection does not show exactly the
word's characters (an OCR misreading), zoom the page in and select again.

## R6. The scripts

Both are commands of `scripts/anki_test_plan.py`, run from the repository checkout. Each run writes
a folder under `anki-test-plan-reports/`.

The script reaches desktop Anki through AnkiConnect. On Android it runs the test helper
(`AnkiTestPlanHelper`), instrumented tests that run inside the plugin's process, which holds the
AnkiDroid permission and can read the plugin's settings; the adb shell can do neither. Every run of
the helper restarts the plugin's process, which the host does not notice on its own, so the reader
session is restarted after the check (10.3).

**Setup** (Phase 2), in order:

1. Checks that `adb` reaches the device and that AnkiConnect answers.
2. Builds the plugin and its test helper and installs both (`--no-build` installs what is already built). `-r` keeps the plugin's dictionaries. The two must come from the same build: the test helper only runs against a plugin signed with the same key.
3. Stops if `AuditTest` holds notes, unless `--clean` was given, in which case it deletes them on both platforms.
4. Creates the decks and note types of R2 on both platforms: in desktop Anki through AnkiConnect, in AnkiDroid through the test helper. A note type that already exists is kept, but its fields must be exactly the listed ones.
5. Resets the plugin's Anki settings to the defaults, so that Phase 3 starts from them and Phase 5 from the three default formats.
6. Checks the plugin's dictionaries: the five of R3, at exactly those revisions, enabled in exactly that order, nothing else enabled, and no sort dictionary.
7. Writes `yomitan-settings-anki-test-plan.json`: the settings exported in 1.2, with desktop's dictionaries set up the same way (the five enabled in order, every other one disabled), frequency sorting off, duplicate checking on with scope Collection, not across models, and "Allow adding", and the 5 card formats of R2. It is imported in 4.1.
8. Serves the test page (R5) and opens it in the desktop browser and in Chrome on the device, until Enter is pressed (12.7).

**Check** (Phase 10) reads the plugin's stored Anki settings and every note in `AuditTest` (and its
sub-deck) from both platforms, and makes every check of R8, reporting each under its ID with one
of these statuses:

- **PASS**: the check holds.
- **FAIL**: the check does not hold. A finding, unless R9 lists it as a known exception.
- **RECORD**: a value this plan says to record rather than compare.
- **REVIEW**: the script can't decide; look at the values it shows (for example, CSS whose rules differ in more than value spelling).
- **MISSING**: a note a check needs was not found on one or both platforms.

It finds each note of R4 by its first field and the order it was added: T1 is the first `AuditTerm`
note whose `Expression` is `食べる`, T4 the second, TG the third and TB the fourth; T8 is the
second `動き出す` note; T6b the second `京都` note; and so on. That is why a note added by mistake
must be deleted. It also checks each term note's `Reading`, which catches a note added from the
wrong entry.

It prints the results, and its folder holds `report.md` (every result, the values behind each one
that isn't a PASS, and every field it compared with both platforms' values and the rule applied,
where each newline in a value is shown as `\n`), `report.json` (the full values), and the raw
notes and settings it read (`desktop-notes.json`, `android-notes.json`). HTML in the report's tables
is escaped, so that a Markdown renderer shows it rather than parsing it.

**Reading a note's fields by hand**, to look into something the check reports: the check compares
stored field values byte for byte, never rendered cards (a card shows only its first field and
`Glossary`, and renders HTML such as `<ruby>` instead of showing it). Compare values in the report,
not on screen: its "Values" section shows both platforms' stored values, and `desktop-notes.json`
and `android-notes.json` hold every field in full. Neither app's note editor shows a stored value
exactly:

- Desktop: in **Browse**, a field shows rendered HTML; its HTML editor (click into the field, then `Ctrl+Shift+X`) shows the HTML, but may lay it out differently.
- Android: AnkiDroid's note editor shows each `<br>` as a line break, and keeps a newline stored after it as a second line break. A field stored as `to eat<br>` followed by a newline and `to live on` therefore shows a blank line between the two that desktop's editor does not, although both platforms store the same text.

## R7. Expected differences in the settings and the UI

| Setting or behavior | Desktop | Plugin | Why |
|---|---|---|---|
| Enable Anki integration | Anki section, top toggle | Anki Export tab, Enable switch | Same meaning. The plugin never turns it on without a working AnkiDroid connection, hence the install and permission flow of Phase 3. |
| AnkiConnect address / API key | advanced | absent | There is no AnkiConnect protocol on Android. |
| Card tags | "Card tags" text field | "Card tags" text field | Same label, same comma, semicolon, and space parsing. Both rewrite the typed text as the tags one space apart when the field loses focus (on the plugin, also on the keyboard's **Done**). |
| Check for card duplicates | toggle | same | |
| Check duplicates across all models | toggle, shown when duplicates on | same | |
| Duplicate card scope | Collection / Deck / Deck root | same | The plugin runs a real deck-scoped search. |
| When a duplicate is detected | Prevent adding / Allow overwriting / Allow adding | same | |
| Screenshot format / quality | advanced | absent | There is no `{screenshot}` content yet. |
| Idle download timeout | advanced | absent | There is no `{audio}` content yet. |
| Suspend new cards | advanced toggle | absent | AnkiDroid's API has no equivalent. |
| Note viewer window (Browse/Edit) | select, and a "View added note" button after an add | absent: no link to a note | AnkiDroid has no API for opening its note editor, and on Android 14 and later a plugin cannot open AnkiDroid's card browser either (see `anki-export.md`). |
| Show card tags and flags | never/always/non-standard/custom + target tags | absent | It would need an AnkiDroid round trip per card format on every lookup (documented in `AnkiSettings.kt`). |
| Force Anki sync on adding card | advanced toggle | absent | AnkiDroid's API has no equivalent. |
| Configure Anki flashcards… | dialog | tab-strip, always-visible editor | Same content in a layout suited to the device; both save as you edit. |
| Configure Anki card templates… (raw Handlebars) | advanced dialog | absent | A documented non-goal. |
| Card format count | at most 5; the last cannot be deleted | same | |
| Defaults | tags `yomitan`; Allow adding; formats Expression, Reading, Kanji | tags `dokuen-yomitan`; otherwise the same | The tag differs on purpose, so notes from the two can be told apart (see `anki-export.md`). |
| After an add | the button changes to its duplicate variant (disabled under Prevent adding) | the link reads **Added: F** (**Overwritten: F** after an overwrite) in the color of its duplicate variant, and stays tappable; under Prevent adding a second tap reports that the note already exists | A link cannot be disabled; the label reports what the tap did (see "After a tap" in `anki-export.md`). |
| An add with an unknown marker | the note is added and its button changes; the error is shown | the note is added, and a message naming the marker is shown instead of the refreshed result | The host shows either a refreshed result or a message after a tap, not both (see "Marker/template engine" in `anki-export.md`). |
| Changing settings | at any time; an open popup keeps working | only while Dokuen's overlay (and so the dictionary window) is hidden | Dokuen does not let a plugin's settings change while its overlay is shown; a result from before the change is gone once the overlay is hidden. |
| Duplicate state before a tap | each button shows its duplicate variant as soon as the popup opens | every link is green and reads **Add** until a link of that entry is tapped; the tap then colors that entry's links | Checking on every lookup would cost an AnkiDroid query per format per lookup (see "Link colors" and "Known limitations" in `anki-export.md`). |
| Icons | shape identifies the format; color shows the duplicate state: green, purple (add duplicate), orange (overwrite), gray (disabled) | a glyph (● ◉ ■ ◆) identifies the format; the same colors, without the disabled button's transparency | See "Link colors" in `anki-export.md`. |

## R8. What the check script checks

Every field of each pair of notes must be identical on both platforms, except where a check below
says otherwise; the checks also give the exact value wherever it is known in advance.

### C1. The plugin's stored settings

- **C1.1** The plugin has exactly the 5 card formats of R2, in that order.
- **C1.2** Each format's type, deck, note type, and icon are as in R2.
- **C1.3** Each format's field values are as in R2.
- **C1.4** Each format's overwrite modes are as in R2.
- **C1.5** Export is on, and the duplicate settings are: checking on, scope Collection, across all models off, Allow adding (9.22).
- **C1.6** Card tags are empty (8.21).

### C2. Term notes (T1 to T8)

| ID | Field | Note | Expected |
|---|---|---|---|
| C2.1 | `Expression` | T1 | `食べる` on both. |
| C2.2 | `Reading` | T1 | `たべる` on both. |
| C2.3 | `furigana` | T1, T2, T3 | Exactly the word's block in C3, on both. |
| C2.4 | `furigana-plain` | T1, T2, T3 | Identical on both. |
| C2.5 | `dictionary` | T1 | `Jitendex.org [2026-05-05]` on both. |
| C2.6 | `dictionary-alias` | T1 | `Jitendex.org [2026-05-05]` on both. The plugin has no per-dictionary alias setting, so the alias is the dictionary title (the original's default). |
| C2.7 | `tags` | T1, T2, T3, T6 | Identical on both: every tag name, comma-separated, headword tags first, then each definition's tags, including tags that have no tag-bank entry. T1 includes `★`; T2 `★` and `priority form`; T3 is empty; T6 lists the tags of both definitions, `★` and `place`. |
| C2.8 | `part-of-speech` | all | For T1, exactly `Ichidan verb` on the plugin, derived from the rule code `v1`. Desktop's value is recorded for every note; it need not match, because desktop renders it from tags. |
| C2.9 | `conjugation` | T4 | The chain of conjugations undone from 食べさせられた to 食べる. On the plugin, exactly `-せる・させる « -られる « -た`, the compact Japanese names its popup shows. Desktop's value is recorded; it is expected to be `causative « potential or passive « -た`, the same chain under the original's English names (a deliberate difference, see "Deliberate differences" in `anki-export.md`). |
| C2.10 | `Glossary`, `glossary-brief`, `glossary-no-dictionary` | all | Identical on both, apart from the text of Jitendex's `<style>` elements and images (R9). Structured content itself is C7; T6 is C5. |
| C2.11 | `glossary-plain` | all | Identical on both, with no `<style>`. |
| C2.12 | `glossary-plain-no-dictionary` | T1 | Identical on both: the same text as T1's `glossary-plain`, without its `(Jitendex.org [2026-05-05])<br>` line. |
| C2.13 | `glossary-first`, `glossary-first-brief`, `glossary-first-no-dictionary` | T5, T6 | T5: only the first of 牛【ぎゅう】's two Jitendex definitions. T6: only the Jitendex definition. Each is followed by a `<style>` prefixed with `.yomitan-glossary` only, not with `[data-dictionary=...]`. Otherwise identical on both, apart from the `<style>` text. |
| C2.14 | `frequencies` | T1, T6, T7 | Identical on both, including the order of the dictionaries and values (R4). T7: empty on both. |
| C2.15 | `single-frequency-number` | T1, T6, T7 | Identical on both (each number printed with no separator). T7: empty on both. |
| C2.16 | `frequency-harmonic-rank`, `frequency-harmonic-occurrence`, `frequency-average-rank`, `frequency-average-occurrence` | T1, T6, T7 | Identical on both. Each takes the first value of each dictionary, skipping a dictionary whose declared `frequencyMode` is the other mode: JPDB declares `rank-based` and BCCWJ nothing, so the rank fields combine both (T1: 184 and 199, giving `191`) and the occurrence fields use BCCWJ's alone (T1: `199`). T7, which has no frequency data: the rank fields read `9999999` and the occurrence fields `0`. |
| C2.17 | `sentence`, `cloze-prefix`, `cloze-body`, `cloze-suffix`, `cloze-body-kana`, `search-query` | T8 | See C4.1. |
| C2.18 | `search-query` | T1 | `食べる` on both. |
| C2.19 | `sentence`, `cloze-prefix`, `cloze-body`, `cloze-suffix`, `cloze-body-kana` | T1 to T7 | Identical on both; the exact values are C4.4. |
| C2.20 | `sentence-furigana`, `sentence-furigana-plain` | all | On the plugin, each is exactly the note's own `sentence` field (empty when that is empty). Desktop's carries readings (`<ruby>` in `sentence-furigana`, `word[reading]` text in `sentence-furigana-plain`): the known deferral (see "Sentence furigana is not implemented" under Known limitations in `anki-export.md`), not a finding. |
| C2.21 | `audio`, `screenshot`, `clipboard-image`, `clipboard-text`, `popup-selection-text`, `url`, `document-title`, `phonetic-transcriptions`, `pitch-accents`, `pitch-accent-graphs`, `pitch-accent-graphs-jj`, `pitch-accent-positions`, `pitch-accent-categories` | all | Empty on the plugin: the markers have no backing subsystem yet, a documented scope boundary. Desktop may fill some of them, depending on its own state (clipboard contents, configured audio sources), so desktop's values are recorded, not compared. |
| C2.22 | every other field | all | Identical on both. |

### C3. Furigana

The expected `furigana` of T1, T2, and T3. Both platforms segment each kana/non-kana run of the
expression independently against the reading, so every word is expected to match between
platforms.

- **C3.1** T1, 食べる / たべる (one kanji run and a kana tail): `<ruby>食<rt>た</rt></ruby>べる`
- **C3.2** T2, 動き出す / うごきだす (two kanji runs separated by kana): `<ruby>動<rt>うご</rt></ruby>き<ruby>出<rt>だ</rt></ruby>す`
- **C3.3** T3, お見舞い / おみまい (a kana prefix): `お<ruby>見舞<rt>みま</rt></ruby>い`

### C4. The scanned sentence (T8)

- **C4.1** On both: `sentence` = `電車が動き出す前に座った。`, `cloze-prefix` = `電車が`, `cloze-body` = `動き出す`, `cloze-suffix` = `前に座った。`, `cloze-body-kana` = `うごきだす`, `search-query` = `動き出す` (the scanned text cut to the longest match).
- **C4.2** Neither `雨が降っていた。` nor `窓の外は暗かった。` appears in any field of either T8 note.
- **C4.3** On the plugin, `sentence-furigana` and `sentence-furigana-plain` are both exactly `電車が動き出す前に座った。`, with no `<ruby>` and no `[...]`.
- **C4.4** T1 to T7 and T6b, each scanned from a line of its own: on both, `sentence` is the line (the scanned text and `。`), `cloze-prefix` is empty, `cloze-body` and `search-query` are the scanned text, `cloze-suffix` is `。`, and `cloze-body-kana` is the scanned text's kana: T1 `たべる`, T2 `うごきだす`, T3 `おみまい`, T4 `たべさせられた` (scanned as 食べさせられた), T5 `ぎゅう`, T6 and T6b `きょうと`, T7 `がくしゅうじてん`.

### C5. Definitions from two dictionaries (T6, T6b)

- **C5.1** T6's `Glossary`: the `<ol>` holds two `<li data-dictionary=...>` elements: Jitendex's, followed by Jitendex's `<style>` (C7), then JMnedict's, with its own tags-and-title `<i>(...)</i>` prefix, its two plain-string glosses (`Kyōto (city, prefecture)`, `Kyoto`) in a `<ul>`, and no `<style>` after it, since JMnedict has no `styles.css`. Everything except the text of the Jitendex `<style>` matches desktop.
- **C5.2** T6's `glossary-plain`: both definitions, each after its own `(dictionary title)<br>` line, identically on both.
- **C5.3** T6b's `Glossary` lists JMnedict's definition first, then Jitendex's, identically on both; every other T6b field is compared as for T6.

### C6. Kanji notes (K1, K2)

| ID | Field | Note | Expected |
|---|---|---|---|
| C6.1 | `Character` | K1, K2 | `食` / `動` on both. |
| C6.2 | `Kunyomi` | K1, K2 | On the plugin, exactly `く.う, く.らう, た.べる, は.む` (K1) and `うご.く, うご.かす` (K2), comma-space joined. Desktop's is recorded; its separator need not be the same. |
| C6.3 | `Onyomi` | K1, K2 | On the plugin, exactly `ショク, ジキ` (K1) and `ドウ` (K2). Desktop's is recorded; its separator need not be the same. |
| C6.4 | `Glossary` | K1, K2 | Identical on both. |
| C6.5 | `onyomi-hiragana` | K1 | Exactly `しょく, じき` on the plugin (katakana `ショク ジキ` converted to hiragana), matching desktop's hiragana-converted onyomi. |
| C6.6 | `stroke-count` | K1, K2 | Exactly `Stroke count: 9` (K1) and `Stroke count: 11` (K2) on both. |
| C6.7 | `search-query` | K1, K2 | `食べる` / `動き出す` on both: a kanji reached from a term's headword keeps the term lookup's query, which is longer than the kanji (see `{search-query}` under "Marker/template engine" in `anki-export.md`). |
| C6.8 | `sentence`, `cloze-prefix`, `cloze-body`, `cloze-suffix` | K2 | Exactly `電車が動き出す前に座った。`, `電車が`, `動`, `き出す前に座った。` on both: the kanji carries the term's sentence, and the cloze is split at the term's position (see "Quirks kept for parity" in `anki-export.md`). |
| C6.9 | `sentence`, `cloze-prefix`, `cloze-body`, `cloze-suffix` | K1 | Exactly `食べる。`, empty, `食`, `べる。` on both, as for K2. |
| C6.10 | every other field | K1, K2 | Identical on both. |

### C7. Glossary structured content

Jitendex holds each entry's whole definition in one structured-content glossary item, so each of
these notes exercises a different part of the structured-content converter:

| Note | Structured content it exercises |
|---|---|
| T1 (食べる) | Nested `ul`/`ol`/`li` lists; `data` attributes (rendered as `data-sc-*`); inline `style` (`listStyleType`); `lang`; `title` on the tag spans; ruby in the example sentences; external links (`https://www.edrdg.org/...`, `https://tatoeba.org/...`). |
| T3 (お見舞い) | Internal cross-reference links (`?query=...&wildcards=off&primary_reading=...` in the dictionary, written as `href="#"` in the note) with ruby inside the link text. |
| 時雨 | The forms table, with a header cell spanning two columns (`colSpan: 2`). |
| 鳥居 | A graphic image, `jitendex/graphics/71041a8fdbbae3c62972906098bb9e65.avif`, 333 by 221.13 px, no alt text. |
| 千鳥 | An inline glyph image in the forms list, `jitendex/HanaMinA/09d34.svg`, 1 by 1 em, alt `鴴`. |
| 順路 | A literal `->` and literal `"` characters in an example sentence's English translation. |
| 記号 | A literal `&` in an example sentence's English translation. |
| 學校 | A redirect entry: a structured-content item linking to `学校`, followed by a second glossary item of the form `["学校", ["redirected from 學校"]]`, which is not part of the definition: the original's translator removes such items, so neither platform writes it. |

The `Glossary` field wraps each definition in the original's default markup: a
`<div style="text-align: left;" class="yomitan-glossary">` holding an `<ol>` with one
`<li data-dictionary="Jitendex.org [2026-05-05]">` per definition, which starts with an
`<i>(tags, Jitendex.org [2026-05-05])</i>` prefix followed by the definition's content. Every word
above has a single glossary item (學校's once its redirect item is left out), so its content
follows the prefix directly, not inside a `<ul>`. That wrapper should match desktop exactly (it is what `{glossary-brief}` and
`{glossary-no-dictionary}` vary).

Each `<li>` is followed by Jitendex's `styles.css` in a `<style>` element, scoped to that
dictionary. Jitendex's CSS has two comments, comma-separated selector lists, nested rules
(`& ul[data-sc-content="glossary"] { ... }` inside a parent rule), hex colors such as `#565656`, and
`color-mix()`/`var()` values, but no `@media` or other at-rules. Its text is expected to differ
from desktop's: desktop re-serializes the CSS through the browser, which respells values (for
example `#565656` becomes `rgb(86, 86, 86)`) and normalizes whitespace, and the plugin does not (see
"The CSS in glossary `<style>` elements is not re-serialized by a browser" in `anki-export.md`).

- **C7.1** The wrapper (`<div>`, `<ol>`, `<li data-dictionary=...>`, `<i>(...)</i>` prefix) matches desktop for every note in the table.
- **C7.2** T1: the whole definition matches desktop, including every `data-sc-*`, `style`, `lang`, and `title` attribute, the ruby, and the external links' `href`s.
- **C7.3** T3: every internal link's `href` is `#` on both, and the rest of the definition, including the ruby inside the link text, matches.
- **C7.4** 時雨: `colspan="2"` on the header cell on both, and the rest of the forms table matches.
- **C7.5** 鳥居: the plugin's image is a bare `<img>` with `alt` (the image's path in the dictionary), `width`, and `height` attributes but no `src`. Desktop stores the image in Anki's media folder and writes a link to that file around a stack of spans holding an `<img>` with a `src`. That is the known gap of R9, not a new finding: the rest of the `Glossary` is compared with each image blanked out on both, and both platforms' image markup is recorded.
- **C7.6** 千鳥: the same known gap for the inline glyph image.
- **C7.7** 順路: the translation's `->` appears as `-&gt;` on both, and the rest of the sentence, including its `"` characters, matches.
- **C7.8** 記号: the translation's `&` appears as `&amp;` on both.
- **C7.9** 學校: the `Glossary` field matches desktop: the redirect's structured content only, without the `["学校", [...]]` item. (Its popup entries are compared by hand in 8.13.)
- **C7.10** The plugin's `<style>` (T1): one follows each `<li>`; every top-level selector, including each part of a comma-separated list such as `li[data-sc-content="sense-group"], li[data-sc-content="forms"]`, is prefixed with `.yomitan-glossary [data-dictionary="Jitendex.org [2026-05-05]"]`; nested rules such as `& ul[data-sc-content="glossary"]` are still inside their parent rule; neither comment appears.
- **C7.11** Both platforms' `<style>` (T1) hold the same rules with the same selectors and property names. Differences in value spelling and whitespace are the known exception of R9; a missing rule, a different selector, or a nested rule moved out of its parent is a finding.
- **C7.12** T1's `glossary-first`: its `<style>` is prefixed with `.yomitan-glossary` only, not with the `[data-dictionary=...]` part.

### C8. Overwriting (椅子, plugin only)

- **C8.1** The first `椅子` note's `Glossary` is its original render followed by a second copy (9.4, Append mode). The other fields render the same on every add, so whether they were kept (Fill if empty) cannot be seen in the note.
- **C8.2** Exactly 2 `椅子` notes exist, and the second (9.5) is a new note with a single render.

### C9. Card tags (TG)

- **C9.1** On each platform exactly one note has the tag `jlpt-n3`, and its tags are exactly `jlpt-n3`, `vocab`, `core2k`, and `listening`.

### C10. Unknown marker (TB)

- **C10.1** TB is added on both (a template error does not stop the export), with its `Reading` field exactly `{expresion-render-error}` (see "Marker/template engine" in `anki-export.md`). The message is compared by hand (7.20, 8.22).

## R9. Known exceptions, and what the plan does not cover

Every field written by this feature should match desktop exactly, with these known, accepted
exceptions: the markers that still resolve to empty (audio, screenshot, clipboard, pitch accents,
phonetic transcriptions, page context), sentence furigana, `part-of-speech`, the conjugation names
in `conjugation` (C2.9), glossary images (the plugin stores no media, C7.5), and the value spelling
and whitespace of the CSS in the glossary's `<style>` elements (C7). Any other difference is a new finding.

The pinned dictionaries don't exercise these converter cases, so this plan does not check them:

- Dropping `@media`, `@font-face`, and other at-rules from dictionary CSS: Jitendex's `styles.css` has none.
- `<details>`/`<summary>` elements: Jitendex does not use them.
- Turning export off while a result is open, and then tapping one of its links: the plugin's settings cannot be reached while the dictionary window is open. `AnkiExecutionTest` covers it (`turningExportOffAfterRenderingRefusesTheTap`).
- Wildcard searches: Dokuen has no dictionary search. A large result's links are tested with a kana scan instead (12.2).
- The intentional `{glossary-plain}` deviation (a `>` inside an attribute value, which desktop leaves behind as the literal typo `&rt;` and the plugin as `&gt;`; see "Deliberate differences" in `anki-export.md`): no Jitendex entry has a `>` in an attribute value.
