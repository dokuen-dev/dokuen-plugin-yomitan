# Anki Export: Manual Test Plan

A device-level parity procedure for the plugin's Anki export, against desktop Yomitan, the
reference. Desktop's notes and what it shows were captured once by the
[reference capture](reference-capture.md) and are committed, so this plan runs on Android only. It
is written so that two different testers produce identical results, not merely similar ones: every
deck, note type, card format, dictionary revision, and lookup word is fixed in advance.

The document has two parts:

- **Part 1, Procedure**: the steps to follow, in order, from start to finish. Only this part is
  needed while testing.
- **Part 2, Reference**: why the plan is built the way it is, the test data, what the two scripts
  do, and every check the check script makes, with its expected values.

Every step in Part 1 has an ID such as `7.13`, and every check in Part 2 has one such as `C2.7`.
Quote the ID when reporting an issue.

See [`architecture.md`](architecture.md) for the architecture of the feature and the accepted gaps
this plan refers to.

---

# Part 1: Procedure

Follow the phases in order and do every step exactly as written. Everything is done on the device,
apart from running the scripts in a desktop terminal.

Throughout:

- **"Scan `X`"** means looking up `X` from the test page that setup serves (R5), where `X` is on a
  line of its own: in the page in Chrome, use Dokuen to select exactly `X` (its characters only,
  not the `。` after it). Every lookup of the plan is a scan: Dokuen has no dictionary search, and
  desktop's notes were made by scanning the same page, so both have the same query and sentence.
- **"Add `E` via `F`"** means: in the lookup's results, on the entry `E` (shown as
  headword【reading】), tap the card format `F`'s **Add: F** link. The result has other entries
  too; always use the one named.
- **Changing a plugin setting** (anything in its dashboard) is only possible while Dokuen's overlay,
  which holds the furigana overlay and the dictionary window, is hidden. When it is shown, first tap
  Dokuen's floating scan button to hide it; the session keeps running. Make the change, then tap the
  floating button to scan again. A result shown before the change is gone, so a step that then taps
  a link says to scan the word again.
- Everything a step does not ask you to confirm is checked by the check script in Phase 8.
- If you add a note by mistake, delete that note right away in AnkiDroid's card browser. The check
  script identifies notes by the order they were added.
- AnkiDroid's card browser only searches the deck selected in it. Whenever a step counts notes in
  it, first choose **All decks** in its deck menu.
- To see a note's stored field values, use the check script's report rather than AnkiDroid's note
  editor, which does not show them exactly (see "Reading a note's fields by hand" in R6).
- Where a step states what desktop Yomitan shows, that is the reference, captured once by the
  reference capture (`reference-capture.md`); desktop is not needed for this plan.

## Phase 1: Before you start

Already installed (this plan does not cover installing them): on the desktop, Python 3, `adb`, and
a checkout of this repository with what its README lists for building it; on Android, AnkiDroid
(from the Play Store, first-run setup done), the host app (`dokuen-android`), and this plugin with
the five dictionaries of R3 imported, enabled in R3's order (Up/Down arrows), no other dictionary
enabled, and no sort dictionary chosen. The device also needs internet access for the online audio
sources that `{audio}` downloads from (1.3).

A release is covered by three full runs of this plan, each done from start to finish in one
configuration (R10):

- a host older than SDK 0.7.0, with or without Dokuen Pro;
- a host at SDK 0.8.0 or later, without Dokuen Pro;
- a host at SDK 0.8.0 or later, with Dokuen Pro.

The check script (8.1) prints the host's SDK level. Phases 11, 12, and 13 each say which
configuration they are for; skip the other two. On a host at SDK 0.7.0 or later without Dokuen
Pro, every add through a format whose note type has image fields (every format but
`AuditKanjiFmt`) also shows a message about them, except in the steps that expect another message
(6.22, 6.35, 6.36, 10.6, and 10.7). A write shows only one message, so in those steps the expected
message takes the place of the one about images.

- [ ] **1.1** Connect the device to the desktop with USB debugging on. In a terminal, confirm `adb devices` lists it.
- [ ] **1.2** Android: open the host app's **Settings**, **Plugin Manager**, **Dictionary Plugins**. Confirm it lists the plugin (**Yomitan**) and that the plugin is selected, so that Dokuen looks words up with it.
- [ ] **1.3** Confirm the device is online: in Android's Settings → Network & internet, the device is connected to Wi-Fi or mobile data, and Android does not mark the connection "No internet" or "Connected without internet" (a connection with neither mark is one that Android has validated as reaching the internet).

## Phase 2: Setup script (desktop terminal)

- [ ] **2.1** In the repository checkout, run `python scripts/anki_test_plan.py setup`. If it reports notes left in `AuditTest` by an earlier run, run it again with `--clean` added, which deletes them in AnkiDroid.
- [ ] **2.2** Confirm it prints **Setup complete**. If it prints **Setup found problems** instead, fix each problem it lists and run it again.
- [ ] **2.3** Leave it running until 14.1. It serves the test page (R5) that every scan uses, and has opened it in Chrome on the device. (If you stop and resume another day, `python scripts/anki_test_plan.py serve` serves it again.)

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
  - **Audio** card: **Enable Default Audio Sources** on, no sources (and no text in their place, as on desktop), and **Idle download timeout (in milliseconds)** `0`
- [ ] **3.9** Confirm **Show card tags and flags**, or anything like it, is nowhere in the Anki Export tab.

## Phase 4: Plugin card formats (Android)

In the plugin's **Anki Export** tab, build the 5 card formats in the tab-strip editor. Every value
is saved as you type; there is no Save button. For each format, set the note type **last**:
choosing it fills in every field, and changing the type afterwards would not refill them.

- [ ] **4.1** Open the **Expression** format's tab. Set name `AuditVocab`, type **Term**, icon **Big Circle**, deck `AuditTest`, then note type `AuditTerm`. Confirm 44 field rows appear, named as the note type's fields (starting `Expression`, `Reading`, `Glossary`). Confirm every field shows `{` + its own name in lower case + `}` (`Expression` shows `{expression}`, `furigana-plain` shows `{furigana-plain}`), except `single-frequency-number`, which stays empty (R2). Note any other field that differs, then type the correct value into it. Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **4.2** Open the **Reading** format's tab. Set name `AuditKanjiFmt`, type **Kanji**, icon **Diamond**, deck `AuditTest`, then note type `AuditKanji`. Confirm 11 field rows appear and every field shows `{` + its own name in lower case + `}` (`Character` shows `{character}`). Note any field that differs, then type the correct value into it.
- [ ] **4.3** Open the **Kanji** format's tab. Set name `AuditSub`, type **Term**, icon **Square**, deck `AuditTest::Sub`, then note type `AuditTerm`. Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **4.4** Tap **+** to add a format. Set name `AuditQuoteKey`, type **Term**, icon **Small Circle**, deck `AuditTest`, then note type `AuditTerm`. Set `Expression` to `{expression}"` (the marker, then one double quote, with no space: 13 characters). Set `single-frequency-number` to `{single-frequency-number}`.
- [ ] **4.5** Tap **+**. Set name `AuditBadMarker`, type **Term**, icon **Square**, deck `AuditTest`, then note type `AuditTerm`. Set `Reading` to `{expresion}` (misspelled on purpose, with one `s`). Set `single-frequency-number` to `{single-frequency-number}`.

## Phase 5: Settings (Android)

Open the plugin's **Anki Export** tab. Steps 5.1 to 5.15 compare it with desktop Yomitan's **Anki**
settings, with advanced settings shown, which each step states; R7 explains each expected
difference. Steps 5.22 to 5.25 compare the tab's **Audio** card with desktop's audio sources dialog
(**Configure audio playback sources…**, in desktop's **Audio** section).

- [ ] **5.1** **Enable Anki integration**: desktop's toggle at the top of its Anki section is on. Confirm the plugin's **Enable Anki export** switch is on.
- [ ] **5.2** **AnkiConnect server address and API key**: desktop has them, under advanced settings. Confirm the plugin has no equivalent.
- [ ] **5.3** **Card tags**: a text field, labeled **Card tags** on desktop. Confirm the plugin labels it **Card tags**. Its value is not compared: the default tag differs on purpose (R7).
- [ ] **5.4** **Check for card duplicates**: a toggle, labeled **Check for card duplicates** on desktop. Confirm the plugin labels it the same.
- [ ] **5.5** **Check for duplicates across all models**: a toggle, shown while **Check for card duplicates** is on, labeled **Check for duplicates across all models** on desktop. Confirm the plugin labels it the same.
- [ ] **5.6** **Duplicate card scope**: desktop offers **Collection**, **Deck**, and **Deck root**. Confirm the plugin offers the same, worded the same.
- [ ] **5.7** **When a duplicate is detected**: desktop offers **Prevent adding**, **Allow overwriting**, and **Allow adding**. Confirm the plugin offers the same, worded the same.
- [ ] **5.8** **Screenshot format and quality**: desktop has them under advanced settings. Confirm the plugin has them in the **Anki Export** tab's **Images** card: an **Image format** menu offering **PNG** and **JPEG**, and a JPEG quality slider, shown only while **JPEG** is chosen. Leave the format at **PNG**.
- [ ] **5.9** **Idle download timeout**: a field under advanced settings on desktop, labeled **Idle download timeout (in milliseconds)**. Confirm the plugin has the setting in the **Anki Export** tab's **Audio** card (R7), with the same label and with desktop's description, "The maximum time before an idle download will be cancelled; 0 = no limit."
- [ ] **5.10** **Suspend new cards**: desktop has it under advanced settings. Confirm the plugin has no equivalent.
- [ ] **5.11** **Note viewer window** (Browse or Edit): a choice on desktop. Confirm the plugin has no equivalent.
- [ ] **5.12** **Show card tags and flags**: a setting on desktop. Confirm the plugin has no equivalent (see also 3.9).
- [ ] **5.13** **Force Anki sync on adding card**: desktop has it under advanced settings. Confirm the plugin has no equivalent.
- [ ] **5.14** **Configure Anki flashcards…**: on desktop, a dialog that lists the five formats in order and has no **Save** or **Cancel** button; on the plugin, the always-visible tab-strip editor. Confirm the plugin lists the same five formats in the same order (`AuditVocab`, `AuditKanjiFmt`, `AuditSub`, `AuditQuoteKey`, `AuditBadMarker`), and that it has no **Save** or **Cancel** button: every edit is kept as it is made. (The check script confirms that the plugin kept every value, C1.)
- [ ] **5.15** **Configure Anki card templates…**: desktop has it under advanced settings. Confirm the plugin has no equivalent.
- [ ] **5.16** Turn **Check for card duplicates** off. Confirm the scope and behavior controls disappear, as they do on desktop. Turn it back on. Confirm they reappear.
- [ ] **5.17** Plugin: turn **Check for duplicates across all models** on. Put the plugin in the background, reopen it, and confirm it is still on. Turn it back off.
- [ ] **5.18** Set "When a duplicate is detected" to **Allow overwriting**. Each field of each card format now has an overwrite-mode menu of its own, labeled **Overwrite**, below the field's value in the format's tab (scroll past the deck and note type to the field rows). Open `AuditVocab`'s `Expression` field's menu. Confirm it offers exactly, in this order, what desktop's offers: **Skip**, **Prepend**, **Append**, **Fill if empty**, **Overwrite if available**, **Overwrite**.
- [ ] **5.19** Plugin, still in **Allow overwriting**: in `AuditVocab`, set the `Glossary` field's **Overwrite** menu to **Append**, and confirm every other field's reads **Fill if empty**.
- [ ] **5.20** Set "When a duplicate is detected" back to **Allow adding**. Confirm the plugin now reads, as desktop does after the same change:
  - **Check for card duplicates**: on
  - **Check for duplicates across all models**: off
  - **Duplicate card scope**: **Collection**
  - **When a duplicate is detected**: **Allow adding**
- [ ] **5.21** Try to add a sixth card format. Confirm the plugin refuses with a message, as desktop does, rather than doing nothing or crashing, and that no sixth format is left behind.
- [ ] **5.22** **Audio sources**: desktop's dialog starts with a toggle labeled **Enable Default Audio Sources**, then explains the source order with "When searching for audio, the sources are checked in order until the first valid source is found. This allows for selecting a fallback source if the first choice is not available.", and then lists the sources. Confirm the plugin's **Audio** card shows, in this order, its default sources switch labeled **Enable Default Audio Sources**, the same explanation, worded the same, and then its sources.
- [ ] **5.23** **Add**: desktop's add button is labeled **Add**, and clicking it three times adds **JapanesePod101**, **Jisho.org**, and **LanguagePod101**, in that order. Confirm the plugin's add button, below the sources, is labeled **Add source**, which is an expected difference (R7). Tap it three times. Confirm the card now lists three sources, whose menus **Source 1**, **Source 2**, and **Source 3** read **JapanesePod101**, **Jisho.org**, and **LanguagePod101**, in that order, as on desktop.
- [ ] **5.24** **Source types**: desktop's type menu offers, in order, **JapanesePod101**, **LanguagePod101**, **Jisho.org**, **(Commons) Lingua Libre**, **(Commons) Wiktionary**, two text-to-speech types (**Text-to-speech** and **Text-to-speech (Kana reading)**, each marked as not supported in Anki), **Custom URL**, and **Custom URL (JSON)**. Open the **Source 1** menu. Confirm the menu offers exactly **JapanesePod101**, **LanguagePod101**, **Jisho.org**, **(Commons) Lingua Libre**, **(Commons) Wiktionary**, **Custom URL**, and **Custom URL (JSON)**, in this order, which is desktop's list without the two text-to-speech types (R7). Choose **Custom URL**. Confirm a field labeled **URL** appears below the menu, as a URL field appears on desktop. Choose **JapanesePod101** again. Confirm the **URL** field disappears.
- [ ] **5.25** Remove the three sources with each source's remove button (a trash can), which takes the place of desktop's per-source menu (R7). Confirm the card lists no sources and shows no text in their place, not even **No audio sources enabled**. Desktop's dialog has an element with that text for an empty list, but desktop's stylesheet always hides it.

## Phase 6: Add the notes (Android)

The notes the check script compares with desktop's, in the same order desktop's were added,
scanning the test page in Chrome.

Until 6.26, the audio settings are the defaults of 3.8. Steps 6.26 to 6.37 change them in the
**Anki Export** tab's **Audio** card, as the reference capture changed desktop's for the same
notes. Each of 6.26 to 6.36 sets the card, then scans and adds its note, and 6.37 restores the
defaults, so the notes added after Phase 6 are unaffected. To set the sources in a step, hide
Dokuen's overlay, remove any sources left over, and set **Enable Default Audio Sources** as the
step says. Then, for each source the step names, in order, tap **Add source** and choose the source's type
in its menu (**Source 1**, **Source 2**, and so on). For a custom URL, choose **Custom URL** (or
**Custom URL (JSON)**) as the type and type the URL into the source's **URL** field.

- [ ] **6.1** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **6.2** Scan `動き出す`. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **6.3** Scan `お見舞い`. Add お見舞い【おみまい】 via `AuditVocab`.
- [ ] **6.4** Scan `食べさせられた`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **6.5** Scan `牛`. Add 牛【ぎゅう】 (not 牛【うし】) via `AuditVocab`.
- [ ] **6.6** Scan `京都`. Confirm the 京都【きょうと】 entry shows two definitions, Jitendex's first and JMnedict's second. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **6.7** Scan `学習辞典`. Add 学習辞典【がくしゅうじてん】 via `AuditVocab`.
- [ ] **6.8** Scan `時雨`. Add 時雨【しぐれ】 (not 時雨【じう】) via `AuditVocab`.
- [ ] **6.9** Scan `鳥居`. Add 鳥居【とりい】 via `AuditVocab`.
- [ ] **6.10** Scan `千鳥`. Confirm the scan read `鳥`, not `島`, and that the entry's reading is ちどり. If not, correct the misread character with the dictionary window's **Repair** button, as in 6.13. Add 千鳥【ちどり】 via `AuditVocab`.
- [ ] **6.11** Scan `順路`. Add 順路【じゅんろ】 via `AuditVocab`.
- [ ] **6.12** Scan `記号`. Add 記号【きごう】 via `AuditVocab`.
- [ ] **6.13** Scan `學校`. Dokuen's OCR often reads `學` as `学`, which makes the result one for `学校`. If it does, correct it with the dictionary window's **Repair** button: tap the misread character's box, replace the character with `學` (choose it from the suggestions if they offer it, and otherwise type or paste it), and tap **Confirm**, which looks `學校` up. Confirm the result shows exactly the entries below, in this order, as desktop's does (any difference is a finding), then add the entry whose headword is `學校` via `AuditVocab`.
  - 学校【がっこう】 (Jitendex.org [2026-05-05])
  - 學校, with no separate reading (Jitendex.org [2026-05-05])
  - 学【がく】 (Jitendex.org [2026-05-05])
  - 學, with no separate reading (Jitendex.org [2026-05-05], then JMnedict [2026-09-25])
  - 学, with no separate reading (JMnedict [2026-09-25])
- [ ] **6.14** Scan `食べる`. Tap `食` in the 食べる【たべる】 entry's headword. Add the 食 kanji entry via `AuditKanjiFmt`.
- [ ] **6.15** In the test page's paragraph, use Dokuen to select exactly `動き出す` (the four characters, nothing before or after) in the middle sentence. Add 動き出す【うごきだす】 via `AuditVocab`.
- [ ] **6.16** In the same result, tap `動` in the 動き出す【うごきだす】 entry's headword. Add the 動 kanji entry via `AuditKanjiFmt`.
- [ ] **6.17** Hide Dokuen's overlay (see "Changing a plugin setting" at the top of Part 1; the same applies to every later step that changes a plugin setting). In the plugin's **Anki Export** tab: clear **Card tags**, then type exactly `jlpt-n3, vocab;core2k listening`.
- [ ] **6.18** Still in **Card tags**, type one more `, ` (comma and space) at the end. Confirm the text stays as you typed it while you type: it is neither wiped nor rewritten.
- [ ] **6.19** Press the keyboard's **Done** key. Confirm the field now reads `jlpt-n3 vocab core2k listening`, as desktop rewrites them.
- [ ] **6.20** Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`.
- [ ] **6.21** Hide the overlay. In the **Anki Export** tab, clear **Card tags** so it is empty, and press **Done**.
- [ ] **6.22** Scan `食べる`. Add 食べる【たべる】 via `AuditBadMarker`. Confirm the note is added: the link reads **Added: AuditBadMarker**, and the host also shows the message `Note added to Anki, but card format "AuditBadMarker" uses unknown marker(s): {expresion}` in its own message area. Desktop shows `An error occurred:`, then on a line of its own `Template render error for {expresion}`, for the same add. Confirm the general shape matches, not the wording or where it is shown: on both, the note is added and the message names `{expresion}`.
- [ ] **6.23** Hide the overlay. In the plugin's **Dictionaries** tab, use the arrows to move `JMnedict [2026-09-25]` above `Jitendex.org [2026-05-05]`. Then stop Dokuen's session and start a new one (**Stop**, then **Start**, on Dokuen's main page), because the plugin reads the dictionary order when a session starts.
- [ ] **6.24** Scan `京都`. Confirm the 京都【きょうと】 entry now shows JMnedict's definition first. Add 京都【きょうと】 via `AuditVocab`.
- [ ] **6.25** Hide the overlay. In **Dictionaries**, move `Jitendex.org [2026-05-05]` back above `JMnedict [2026-09-25]`. Confirm the order is Jitendex, JMnedict, KANJIDIC, JPDBv2㋕, BCCWJ. Stop Dokuen's session and start a new one, as in 6.23.
- [ ] **6.26** Hide the overlay. Set the audio sources to **LanguagePod101** alone, with **Enable Default Audio Sources** off. Scan `記号`. Add 記号【きごう】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.27** Hide the overlay. Set the audio sources to **Jisho.org** alone, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.28** Hide the overlay. Set the audio sources to **(Commons) Lingua Libre** alone, with **Enable Default Audio Sources** off. Scan `牛`. Add 牛【ぎゅう】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.29** Hide the overlay. Set the audio sources to **(Commons) Wiktionary** alone, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Desktop adds the note with its `audio` field empty and shows no error, because it finds no Wiktionary file for any Japanese word. Confirm the note is added with no message about `{audio}`, as on desktop: the link reads **Added: AuditVocab**.
- [ ] **6.30** Hide the overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/custom/{language}/{term}/{reading}` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.31** Hide the overlay. Set the audio sources to a **Custom URL (JSON)** of `http://localhost:8766/audio/list/{term}` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.32** Hide the overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/missing`, then **Jisho.org**, with **Enable Default Audio Sources** off. Scan `京都`. Add 京都【きょうと】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.33** Hide the overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/missing` alone, with **Enable Default Audio Sources** on. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.34** Hide the overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/file/unknown` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**.
- [ ] **6.35** Hide the overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/stall` alone, with **Enable Default Audio Sources** off, and set **Idle download timeout (in milliseconds)** to `2000`. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**, and the host also shows the message `Note added to Anki, but {audio} is empty because its download timed out.` in its own message area. Desktop adds the note and shows nothing. The plugin's message is a deliberate difference (see "`{audio}` reports timed-out downloads and unreachable sources" in "Deliberate differences" in `architecture.md`, and R7).
- [ ] **6.36** Hide the overlay. Set **Idle download timeout (in milliseconds)** back to `0`. Set the audio sources to a **Custom URL** of `http://localhost:8767/audio/closed` alone, with **Enable Default Audio Sources** off. Scan `食べる`. Add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**, and the host also shows the message `Note added to Anki, but {audio} is empty because no audio source could be reached.` in its own message area. Desktop adds the note and shows nothing. The plugin's message is a deliberate difference (see "`{audio}` reports timed-out downloads and unreachable sources" in "Deliberate differences" in `architecture.md`).
- [ ] **6.37** Hide the overlay. Remove every audio source, and turn **Enable Default Audio Sources** on. Confirm the card lists no sources and shows no text in their place, as in 5.25, and that **Idle download timeout (in milliseconds)** reads `0`. Nothing is scanned or added. (The check script confirms these defaults, C1.7.)

## Phase 7: Duplicate detection (Android)

All on Android: settings in the plugin's **Anki Export** tab, notes in AnkiDroid (with **All decks**
chosen in its card browser). Use `AuditVocab` unless a step names another format. Every step that
sets something in the plugin hides Dokuen's overlay first (see "Changing a plugin setting" at the
top of Part 1), so each tap after a setting change is on a new scan, whose links read **Add**.

- [ ] **7.1** AnkiDroid: in the card browser, search `"Expression:椅子" or "Expression:鉛筆"`. Confirm it finds no notes.
- [ ] **7.2** Plugin: set scope **Collection**, across all models off, "When a duplicate is detected" = **Allow adding**. Scan `椅子` and tap **Add: AuditVocab** on 椅子【いす】. Confirm it succeeds: the link now reads **Added: AuditVocab**.
- [ ] **7.3** Set **Prevent adding**. Scan `椅子` again and tap **Add: AuditVocab**. Confirm "This note already exists in Anki." In AnkiDroid, confirm exactly 1 `椅子` note exists.
- [ ] **7.4** Set **Allow overwriting**. Scan `椅子` again and tap **Add: AuditVocab**. Confirm it succeeds.
- [ ] **7.5** Set **Allow adding**. Scan `椅子` again and tap **Add: AuditVocab**. In AnkiDroid, confirm 2 `椅子` notes now exist. Leave both.
- [ ] **7.6** AnkiDroid: open **Add**. Type `AuditTerm`, deck `AuditTest`, Expression `鉛筆`, Reading `えんぴつ`, Glossary `seed-deck`, every other field empty, tag `audit-fixture-dup-scope`. Save. (This is "Seed-Deck".)
- [ ] **7.7** Same again, but deck `AuditTest::Sub` and Glossary `seed-sub` ("Seed-Sub"). In the card browser, confirm exactly 2 `鉛筆` notes exist, one per deck.
- [ ] **7.8** Plugin: set **Prevent adding**, scope **Collection**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists".
- [ ] **7.9** Set scope **Deck**. Scan `鉛筆` again and tap **Add: AuditVocab**. Confirm "already exists" (Seed-Deck is in `AuditTest` itself).
- [ ] **7.10** AnkiDroid: search `"Expression:鉛筆" deck:AuditTest -deck:AuditTest::*` and delete that note (Seed-Deck). Search `"Expression:鉛筆"` and confirm exactly 1 note remains (Seed-Sub).
- [ ] **7.11** Plugin, scope still **Deck**: scan `鉛筆` and tap **Add: AuditVocab**. Confirm it is **not** a duplicate and a note is added (Seed-Sub is in a sub-deck, which **Deck** excludes).
- [ ] **7.12** AnkiDroid: delete the note just added, leaving only Seed-Sub.
- [ ] **7.13** Plugin: set scope **Deck root**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists" (**Deck root** includes `AuditTest::Sub`).
- [ ] **7.14** AnkiDroid: delete Seed-Sub, then create Seed-Deck again as in 7.6.
- [ ] **7.15** Plugin, scope **Deck root**: scan `鉛筆` and tap **Add: AuditSub** (whose deck is `AuditTest::Sub`). Confirm "already exists" (the search covers the root deck `AuditTest`, which holds Seed-Deck).
- [ ] **7.16** Set scope **Deck**. Scan `鉛筆` again and tap **Add: AuditSub**. Confirm it is **not** a duplicate and a note is added to `AuditTest::Sub` (Seed-Deck is outside `AuditTest::Sub`).
- [ ] **7.17** AnkiDroid: delete every `鉛筆` note. Open **Add**: type `AuditKanji` (a different note type), deck `AuditTest`, Character `鉛筆`, Kunyomi, Onyomi, and Glossary `n/a`, every other field empty. Save.
- [ ] **7.18** Plugin: set scope **Collection**, across all models off. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm it is **not** a duplicate (a different note type, not checked) and a note is added.
- [ ] **7.19** AnkiDroid: delete the note just added. Plugin: turn across all models **on**. Scan `鉛筆` and tap **Add: AuditVocab**. Confirm "already exists": across all models compares each note's first field, whatever its name, so the `AuditKanji` note, whose first field `Character` is `鉛筆`, is a duplicate.
- [ ] **7.20** Plugin: turn across all models off and set **Allow adding**. Scan `手紙` and tap **Add: AuditQuoteKey** on 手紙【てがみ】 (its Expression becomes `手紙"`). Confirm it succeeds.
- [ ] **7.21** Set **Prevent adding**. Scan `手紙` again and tap **Add: AuditQuoteKey**. Confirm "already exists". In AnkiDroid, search `手紙` and confirm exactly 1 note exists (the duplicate check found the note despite the quote in its first field).
- [ ] **7.22** Plugin: set **Allow adding**, scope **Collection**, across all models off.

## Phase 8: Check script (desktop terminal)

- [ ] **8.1** Open a second terminal (setup is still serving the test page in the first) and run `python scripts/anki_test_plan.py check` in the repository checkout. It compares the plugin's notes with desktop's, from the reference capture, and prints the host's SDK level and which of Phases 11 to 13 comes next.
- [ ] **8.2** For every result that is FAIL, REVIEW, or MISSING, look at its values in the report (`anki-test-plan-reports/<date-time>-check/report.md`) and the check it names in R8. Record: check ID, note, field, desktop value, plugin value, and whether it is a known exception from R9 (Y: which one / N: new). RECORD results need no decision; the report keeps them. This table and the report folder are the parity evidence to keep.
- [ ] **8.3** Android: stop Dokuen's session (tap **Stop** on Dokuen's main page, or drag the floating button onto the X) and start a new one. Hiding the overlay is not enough here. The check script read the plugin's notes and settings by running its test helper inside the plugin's process, which restarts the plugin without the host noticing (R6); a new session reconnects the host to it.

## Phase 9: Icons and link states (Android)

The colors of R7: green (can add), orange (a duplicate exists and would be overwritten), purple (a
duplicate exists and another note would be added), gray (a duplicate exists and adding is
prevented).

- [ ] **9.1** With Dokuen's overlay hidden, set "When a duplicate is detected" to **Allow overwriting**.
- [ ] **9.2** Plugin: scan `猫`, and then `犬` and tap `犬` in the 犬【いぬ】 entry's headword. Confirm **AuditVocab** is prefixed ●, **AuditSub** and **AuditBadMarker** ■, **AuditQuoteKey** ◉, and **AuditKanjiFmt** ◆, all in the same green, as desktop's buttons for these formats are (told apart only by shape).
- [ ] **9.3** Plugin: scan `食べる`. Confirm every link on 食べる【たべる】 is green and reads **Add**: the plugin checks for duplicates only once a link of the entry is tapped (R7). Tap **Add: AuditQuoteKey**. Confirm it now reads **Added: AuditQuoteKey**, and that it and the **AuditVocab**, **AuditSub**, and **AuditBadMarker** links are orange, as desktop's buttons end: desktop shows the overwrite (orange) variant of **AuditVocab**, **AuditSub**, and **AuditBadMarker** as soon as its popup opens, and turns **AuditQuoteKey** orange when it is clicked.
- [ ] **9.4** Tap **Added: AuditQuoteKey**. Confirm it now reads **Overwritten: AuditQuoteKey** and stays orange: like desktop's overwrite button, the second tap overwrote the note the first one added.
- [ ] **9.5** With Dokuen's overlay hidden, set "When a duplicate is detected" to **Allow adding**. Scan `食べる` and tap **Add: AuditVocab**. Confirm it reads **Added: AuditVocab**, and that it and the other three links are purple, like desktop's buttons, which show their add-duplicate (purple) variant here.
- [ ] **9.6** Plugin: confirm there is no link to view a note in AnkiDroid ("View in Anki", "Edit", or similar) anywhere (R7).

## Phase 10: Errors and edge cases (Android)

- [ ] **10.1** Plugin: scan `食べる`. Tap `食` in the 食べる【たべる】 entry's headword, then use the dictionary window's back button to return to 食べる. Tap **Add: AuditVocab**. Confirm it succeeds (a result you go back to keeps working links).
- [ ] **10.2** Scan `かける`. Confirm the result has more than 10 entries. Tap **Add: AuditVocab** on the **first** entry, then on the **last**. Confirm both succeed, rather than reporting an expired link (every entry of a large result keeps working links).
- [ ] **10.3** Scan `食べる`. Then do this 10 times: tap `食` in the 食べる【たべる】 entry's headword, which opens the 食 kanji entry, and tap the dictionary window's back button, which returns to 食べる's result. After the tenth time, with 食べる's result showing, tap **Add: AuditVocab**. Confirm the message "This Anki link has expired. Please look up the word again.", not a crash and no note added: the plugin keeps the links of its 10 most recent lookups only, and each kanji tap was a lookup.
- [ ] **10.4** AnkiDroid: delete the `AuditTest::Sub` deck. Plugin: scan `手紙` and tap **Add: AuditSub**. Confirm a message that `AuditSub`'s deck `AuditTest::Sub` no longer exists. In AnkiDroid, search `手紙` and confirm it still finds exactly 1 note (the one from 7.20), so none was added.
- [ ] **10.5** AnkiDroid: create the deck `AuditTest::Sub` again. Plugin: hide Dokuen's overlay, and choose the deck again as `AuditSub`'s deck.
- [ ] **10.6** Offline: in Android's quick settings, turn Wi-Fi and mobile data off. The audio settings are the defaults (6.37). Scan `食べる` and add 食べる【たべる】 via `AuditVocab`. Confirm the note is added: the link reads **Added: AuditVocab**, and the host also shows the message `Note added to Anki, but {audio} is empty because there is no network connection.` in its own message area. Turn Wi-Fi and mobile data back on.
- [ ] **10.7** Lookup timeout: hide Dokuen's overlay. Set the audio sources, as in Phase 6, to a **Custom URL (JSON)** of `http://localhost:8766/audio/list-stall/{term}` alone, with **Enable Default Audio Sources** off. Scan `食べる` and add 食べる【たべる】 via `AuditVocab`. The server never answers that lookup, and the plugin cancels a lookup after 30 seconds without progress (see "`{audio}`'s lookups time out" in "Deliberate differences" in `architecture.md`). Confirm that after about 30 seconds the link reads **Added: AuditVocab**, and the host shows the message `Note added to Anki, but {audio} is empty because no audio source could be reached.` in its own message area. Hide the overlay, remove the source, and turn **Enable Default Audio Sources** back on.
- [ ] **10.8** Only fields the write keeps: hide Dokuen's overlay. Set the audio sources to a **Custom URL** of `http://localhost:8766/audio/custom/{language}/{term}/{reading}` alone, with **Enable Default Audio Sources** off. Set "When a duplicate is detected" to **Allow overwriting**, and in `AuditVocab`, set the `audio` field's overwrite mode to **Skip**. Scan `食べる` and add 食べる【たべる】 via `AuditVocab`. The add overwrites a `食べる` note. Confirm the link reads **Overwritten: AuditVocab**, and that the test page's server logged no request for a path under `/audio/custom/` during this add (R5): **Skip** leaves the note's `audio` field as it is, so the plugin does not download audio for it (see "Audio is downloaded only for fields the write keeps" in "Deliberate differences" in `architecture.md`). Hide the overlay, and set the `audio` field's overwrite mode to **Overwrite**. Scan `食べる` and add 食べる【たべる】 via `AuditVocab` again. Confirm the link reads **Overwritten: AuditVocab**, and that the server logged exactly one request for a path under `/audio/custom/` during this add. Hide the overlay, set the `audio` field's overwrite mode back to **Fill if empty** and "When a duplicate is detected" back to **Allow adding**, remove the audio source, and turn **Enable Default Audio Sources** back on.

## Phase 11: Images on a host older than SDK 0.7.0 (Android)

For a host older than SDK 0.7.0, which provides no images and no clipboard text, with or without
Dokuen Pro (R10). Skip this phase on any other host. Every add here is 猫【ねこ】 via `AuditVocab`,
whose `screenshot`, `clipboard-image`, and `clipboard-text` fields hold those markers; with
**Allow adding** (7.22, 9.5) each add makes a new note. To look at the newest note's fields,
search `猫` in AnkiDroid's card browser and open the last note in its note editor. The image
settings are in the plugin's **Anki Export** tab, **Images** card; hide Dokuen's overlay to change
them, as for every plugin setting.

- [ ] **11.1** Plugin: set **Allow adding** and "{screenshot} behavior" **Full screen**. Copy some text. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm no Dokuen UI appears, the link reads **Added: AuditVocab**, and no message is shown. In AnkiDroid, confirm the new note's `screenshot`, `clipboard-image`, and `clipboard-text` fields are empty.
- [ ] **11.2** Plugin: set "{screenshot} behavior" to **Show crop UI**. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm no crop UI appears and the link reads **Added: AuditVocab**. Set it back to **Full screen**.

## Phase 12: Images and clipboard text without Dokuen Pro (Android)

For a host at SDK 0.7.0 or later, without Dokuen Pro (R10). Skip this phase in any other
configuration. Every add here is 猫【ねこ】 via `AuditVocab`, whose `screenshot`, `clipboard-image`, and `clipboard-text` fields hold those markers; with **Allow adding** (7.22, 9.5) each add makes a
new note. To look at the newest note's fields, search `猫` in AnkiDroid's card browser and open
the last note in its note editor. The image settings are in the plugin's **Anki Export** tab,
**Images** card; hide Dokuen's overlay to change them, as for every plugin setting.

- [ ] **12.1** Plugin: set **Allow adding** and "{screenshot} behavior" **Full screen**. Copy some text. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm no Dokuen UI appears, the link reads **Added: AuditVocab**, and the message reads `Note added successfully, but with empty {screenshot} and {clipboard-image} fields (images require Dokuen Pro).` In AnkiDroid, confirm the new note's `screenshot` and `clipboard-image` fields are empty and its `clipboard-text` field holds the copied text.
- [ ] **12.2** In a text editor app (for example Google Keep), type the two lines `<b>bold</b>` and `second line`, select both, and copy them. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In AnkiDroid, confirm the new note's `clipboard-text` field shows both lines, one below the other, with `<b>bold</b>` shown as typed rather than in bold, and that its `clipboard-image` field is empty.

## Phase 13: Images and clipboard text with Dokuen Pro (Android)

For a host at SDK 0.7.0 or later, with Dokuen Pro (R10). Skip this phase in any other
configuration. Every add here is 猫【ねこ】 via `AuditVocab`, whose `screenshot`, `clipboard-image`, and `clipboard-text` fields hold those markers; with **Allow adding** (7.22, 9.5) each add makes a
new note. To look at the newest note's fields, search `猫` in AnkiDroid's card browser and open
the last note in its note editor. The image settings are in the plugin's **Anki Export** tab,
**Images** card; hide Dokuen's overlay to change them, as for every plugin setting.

- [ ] **13.1** In AnkiDroid, delete every `猫` note (in the card browser, with **All decks** chosen, search `猫`), so that the steps below find only the notes they add.
- [ ] **13.2** In a text editor app (for example Google Keep), type the two lines `<b>bold</b>` and `second line`, select both, and copy them. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In AnkiDroid, confirm the new note's `clipboard-text` field shows both lines, one below the other, with `<b>bold</b>` shown as typed rather than in bold, and that its `clipboard-image` field is empty.
- [ ] **13.3** Plugin: confirm the **Images** card shows "{screenshot} behavior" **Full screen** and image format **PNG**, with no JPEG quality slider. In any app, copy some text (not an image). Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm no Dokuen UI appears and the link reads **Added: AuditVocab**. In AnkiDroid, confirm the new note's `screenshot` field shows the whole screen Dokuen scanned and its `clipboard-image` field is empty.
- [ ] **13.4** Plugin: set "{screenshot} behavior" to **Show crop UI**. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm Dokuen's crop UI opens, titled with the plugin's name. Crop to a part of the screen and confirm. In AnkiDroid, confirm the new note's `screenshot` field shows just that part.
- [ ] **13.5** Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In the crop UI, choose an image file instead. In AnkiDroid, confirm the new note's `screenshot` field shows that file's image.
- [ ] **13.6** Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In the crop UI, continue without an image. Confirm the link reads **Added: AuditVocab**, and in AnkiDroid that the new note's `screenshot` field is empty.
- [ ] **13.7** In AnkiDroid, count the `猫` notes. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In the crop UI, cancel. Confirm, in AnkiDroid, that the number of `猫` notes is unchanged.
- [ ] **13.8** Plugin: set "{screenshot} behavior" to **Full screen**, image format **JPEG**, and JPEG quality **50**. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In AnkiDroid, confirm the new note's `screenshot` field shows a `.jpg` file (the note editor's HTML view shows the file name; AnkiDroid stores JPEG as `.jpg`) that looks visibly compressed. Set the image format back to **PNG**.
- [ ] **13.9** Copy an image (for example, in Chrome, long-press an image and choose **Copy image**). Scan `猫` and add 猫【ねこ】 via `AuditVocab`. In AnkiDroid, confirm the new note's `clipboard-image` field shows that image, as a `.png` file, and that its `clipboard-text` field is empty. Plugin: set the image format to **JPEG**, scan `猫` and add 猫【ねこ】 via `AuditVocab` again, and confirm the new `clipboard-image` file is still `.png`. Set the image format back to **PNG**.
- [ ] **13.10** Plugin: set "{screenshot} behavior" to **Show crop UI** and "When a duplicate is detected" to **Prevent adding**. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm "This note already exists in Anki." and that no crop UI appeared.
- [ ] **13.11** Plugin: set **Allow overwriting**, and in `AuditVocab`, set the `screenshot` field's overwrite mode to **Skip**. Scan `猫` and add 猫【ねこ】 via `AuditVocab`. Confirm no crop UI appears and the link reads **Overwritten: AuditVocab**. In AnkiDroid, confirm the first `猫` note (the one from 13.2) still shows its screenshot.

## Phase 14: Finish (Android)

- [ ] **14.1** In the setup script's window, press **Enter** to stop serving the test page.
- [ ] **14.2** Plugin: delete card formats until exactly one is left. Confirm the delete control is disabled for the last one.

---

# Part 2: Reference

## R1. How the plan works

The plan compares the plugin with desktop Yomitan, the reference, by exporting the same lookups and
comparing the notes. Desktop's side was done once, by the [reference capture](reference-capture.md):
its notes are committed as `scripts/anki_test_plan_reference.json`, and what its screens show is
written into the steps that compare with it. The plan itself runs on Android only, in three parts:
the setup script (Phase 2), the steps on the device (Phases 3 to 7), and the check script
(Phase 8), followed by checks of what only the screen shows (Phases 9 to 13).

The note types give every marker the plan checks a field of its own, named after the marker, so a
single add records every marker at once and no step ever edits a card format's fields once they
are built. The check script reads the plugin's notes and compares them with desktop's, and makes
every check of their fields and of the plugin's stored settings (R8); the tester adds notes and
checks only what the screen shows. Only the plugin's configuration is built by hand (Phase 4),
which is what tests its format editor.

Every lookup is a scan of the same test page (R5), as desktop's were: Dokuen has no dictionary
search, and scanning the same text gives both the same query and sentence, so the sentence and
query markers can be compared too.

The steps that change state are ordered so that nothing later undoes what the check script needs:
the AnkiDroid and permission flows (Phase 3) come before any note exists, every note the check
script reads is added in Phases 6 and 7, and the steps that add further notes or delete decks
(Phases 9 to 13) come after the check.

Some of what the plugin can do depends on the host's SDK version and on Dokuen Pro, so a release
is covered by one full run per configuration (R10).

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
is typed by hand (see "Settings UI" in `architecture.md`). The note type is chosen last because
changing a format's type does not refill its fields (see "Quirks kept for parity" in
`architecture.md`).

Each format's role: `AuditVocab` and `AuditKanjiFmt` add the notes the check script compares;
`AuditSub` targets the sub-deck, for the deck-root scope test (7.15, 7.16) and the missing-deck
test (10.4); `AuditQuoteKey` puts a double quote into the first field, which the duplicate check
compares (7.20, 7.21); `AuditBadMarker` holds an unknown marker (6.22). Together they use all four icon shapes
(9.2).

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
all of this on the plugin; the reference capture set it up the same way on desktop.

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
words are from Jitendex unless noted. The audio notes, AU1 to AU11, each cover an audio setting
rather than a word, and only their `audio` field is compared (C11).

| Note | Capture / plugin step | Word (entry) | Why |
|---|---|---|---|
| T1 | D4.1 / 6.1 | 食べる【たべる】 | The main marker word (rule `v1`, tag `★`); furigana with one kanji run and a kana tail; nested lists, ruby, and external links (C7). |
| T2 | D4.2 / 6.2 | 動き出す【うごきだす】 | Furigana with two kanji runs separated by kana (rule `v5`, tags `★` and `priority form`). |
| T3 | D4.3 / 6.3 | お見舞い【おみまい】 | Furigana with a kana prefix (no tags, no rule); internal `?query=` cross-reference links (C7). |
| T4 | D4.4 / 6.4 | 食べる【たべる】, scanned as 食べさせられた | `conjugation`: the deconjugation chain. A separate note from T1, not an overwrite of it. |
| T5 | D4.5 / 6.5 | 牛【ぎゅう】 | Two Jitendex entries share this expression and reading, so the popup entry groups two definitions: the `-first` glossary variants. Scanning 牛 also shows a separate 牛【うし】, which is not used. |
| T6 | D4.6 / 6.6 | 京都【きょうと】 | In both Jitendex (tag `★`) and JMnedict (tag `place`, glossary `Kyōto (city, prefecture)` and `Kyoto`), so the popup entry groups definitions from two dictionaries (C5). |
| T7 | D4.7 / 6.7 | 学習辞典【がくしゅうじてん】 | No value in either frequency dictionary: the frequency markers' empty case. |
| 時雨 | D4.8 / 6.8 | 時雨【しぐれ】 | The forms table, with a header cell spanning two columns (C7). A separate 時雨【じう】 entry is not used. |
| 鳥居 | D4.9 / 6.9 | 鳥居【とりい】 | A graphic image (C7). |
| 千鳥 | D4.10 / 6.10 | 千鳥【ちどり】 | An inline glyph image (C7). |
| 順路 | D4.11 / 6.11 | 順路【じゅんろ】 | A literal `->` in an example sentence's translation (C7). |
| 記号 | D4.12 / 6.12 | 記号【きごう】 | A literal `&` in an example sentence's translation (C7). |
| 學校 | D4.13 / 6.13 | 學校 (old kanji form of 学校, no separate reading) | A redirect entry, whose second glossary item points at `学校` (C7). Dokuen's OCR often reads its old form, 學, as 学, which 6.13 corrects with **Repair**. |
| K1 | D4.14 / 6.14 | 食 (KANJIDIC), reached from 食べる【たべる】's headword | Kanji markers, and the sentence and query a kanji carries over from the term it was reached from. (A scan of `食` alone shows the term 食【しょく】, not the kanji, on the plugin.) |
| T8 | D4.15 / 6.15 | 動き出す【うごきだす】, scanned in the test page's paragraph (R5) | `sentence`, the cloze markers, and `search-query` (C4). |
| K2 | D4.16 / 6.16 | 動 (KANJIDIC), reached from T8's headword | A kanji that carries the term's sentence (C6). |
| TG | D4.18 / 6.20 | 食べる【たべる】, with card tags set | Card tag parsing (C9). |
| TB | D4.20 / 6.22 | 食べる【たべる】, via `AuditBadMarker` | An unknown marker (C10). |
| T6b | D4.22 / 6.24 | 京都【きょうと】, with JMnedict above Jitendex | Definitions follow the dictionary order (C5). |
| AU1 | D4.24 / 6.26 | 記号【きごう】 | LanguagePod101 as the only source, which takes the audio link from its search page. LanguagePod101 has audio for 記号. |
| AU2 | D4.25 / 6.27 | 京都【きょうと】 | Jisho.org as the only source, which takes the audio link from its search page. |
| AU3 | D4.26 / 6.28 | 牛【ぎゅう】 | (Commons) Lingua Libre as the only source, which searches Wikimedia Commons and finds one Japanese recording of 牛. (T5, the same word, has no audio from the default sources.) |
| AU4 | D4.27 / 6.29 | 京都【きょうと】 | (Commons) Wiktionary as the only source. Wikimedia Commons names nearly all of its Japanese Wiktionary files in romaji, which desktop's search for a Japanese term never matches, so `audio` is expected to be empty on both, with no message. 京都, which other sources have audio for, shows that no other source was tried. |
| AU5 | D4.28 / 6.30 | 食べる【たべる】 | A Custom URL with `{language}`, `{term}`, and `{reading}`, which shows how each platform fills in and percent-encodes them (the server answers only the exact path, R5). Its file is served as `audio/mpeg`. |
| AU6 | D4.29 / 6.31 | 食べる【たべる】 | A Custom URL (JSON), whose list's first item fails and is skipped. The second item is a file served as `audio/ogg`, which AnkiDroid stores as `.oga` (C11.1). |
| AU7 | D4.30 / 6.32 | 京都【きょうと】 | A Custom URL that fails, then Jisho.org, which is tried next (the fallback in source order). |
| AU8 | D4.31 / 6.33 | 食べる【たべる】 | A Custom URL that fails, with the default sources on. The default sources are tried after the listed one. |
| AU9 | D4.32 / 6.34 | 食べる【たべる】 | A Custom URL whose file is served with a Content-Type that neither platform knows, so both name the file `.mp3`. |
| AU10 | D4.33 / 6.35 | 食べる【たべる】 | A Custom URL whose download stalls, with an idle download timeout of 2000 ms. The download is cancelled, the note is added with `audio` empty, the plugin says that the download timed out, and desktop shows nothing. |
| AU11 | D4.34 / 6.36 | 食べる【たべる】 | A Custom URL on a port nothing listens on. The note is added with `audio` empty, the plugin says that no audio source could be reached, and desktop shows nothing. |
| 椅子 | 7.2 and 7.5 (plugin only) | 椅子【いす】 | The overwrite test (C8). |

Other words used only on the plugin, in Phases 7 and 10: `鉛筆`【えんぴつ】 (duplicate scopes;
its notes are deleted along the way), `手紙`【てがみ】 (the quote in the first field, and the
missing deck), and `かける` (a kana word whose scan shows more than 10 entries). `猫` and `犬` are
scanned in 9.2 to show the icons of formats that have no duplicate, and Phases 11 to 13 add `猫`
notes, which the check script does not read.

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

1. The words of Phase 6 (and of the reference capture's Phase D4), each on a line of its own followed by `。`: `食べる。`, `動き出す。`,
   `お見舞い。`, `食べさせられた。`, `牛。`, `京都。`, `学習辞典。`, `時雨。`, `鳥居。`, `千鳥。`,
   `順路。`, `記号。`, `學校。`.
2. The paragraph T8 and K2 are scanned from: three sentences, with no spaces and no line breaks.

   ```
   雨が降っていた。電車が動き出す前に座った。窓の外は暗かった。
   ```

3. The words only Phases 7, 9, 10, 11, 12, and 13 scan, in the same way: `椅子。`, `鉛筆。`, `手紙。`, `猫。`,
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
word's characters (an OCR misreading), zoom the page in and select again, or correct the
characters with the dictionary window's **Repair** button, as 6.10 and 6.13 do.

The same server also serves the local audio sources of 6.30 to 6.35, 10.7, and 10.8, under
`http://localhost:8766/audio/` (on the device through the same `adb reverse`). The source of 6.36
points at port 8767, where nothing listens. Each file is a short WAV tone that the script generates,
with the same bytes on every run. Neither platform looks inside the file, so the Content-Type it is
served with decides its extension.

| Source | URL | What the server answers |
|---|---|---|
| `custom` (6.30, 10.8) | `http://localhost:8766/audio/custom/{language}/{term}/{reading}` | A file served as `audio/mpeg`, but only for `/audio/custom/ja/食べる/たべる` with the Japanese percent-encoded as a browser encodes it. Any other path under `/audio/custom/` gets a 404, so a platform that fills in or encodes the placeholders differently gets no audio. |
| `json` (6.31) | `http://localhost:8766/audio/list/{term}` | A list in the original's custom audio list format (`{"type": "audioSourceList", "audioSources": [...]}`). Its first item is the `missing` URL below, which gets a 404, and its second is a file served as `audio/ogg`. |
| `missing` (6.32, 6.33) | `http://localhost:8766/audio/missing` | Always a 404. |
| `unknown_type` (6.34) | `http://localhost:8766/audio/file/unknown` | A file served as `application/octet-stream`, which is not an audio type. |
| `stall` (6.35) | `http://localhost:8766/audio/stall` | The headers of a file and its first 16 bytes, then nothing for 20 seconds, which is longer than 6.35's idle download timeout of 2000 ms. |
| `json_stall` (10.7) | `http://localhost:8766/audio/list-stall/{term}` | Nothing for 60 seconds, which is longer than the plugin's 30-second lookup timeout. |
| `closed` (6.36) | `http://localhost:8767/audio/closed` | Nothing listens on port 8767, so the connection is refused. The server does not start if something is listening there. |

Any other path under `/audio/` gets a 404. The server also logs the path and time of every request
under `/audio/`. Step 10.8 reads that log.

## R6. The scripts

Both are commands of `scripts/anki_test_plan.py`, run from the repository checkout. Each run writes
a folder under `anki-test-plan-reports/`. (Its `reference-setup` and `reference-save` commands
belong to the [reference capture](reference-capture.md).)

On Android the script runs the test helper (`AnkiTestPlanHelper`), instrumented tests that run
inside the plugin's process, which holds the AnkiDroid permission and can read the plugin's
settings; the adb shell can do neither. Every run of the helper restarts the plugin's process,
which the host does not notice on its own, so the reader session is restarted after the check
(8.3).

**Setup** (Phase 2), in order:

1. Checks that `adb` reaches the device.
2. Builds the plugin and its test helper and installs both (`--no-build` installs what is already built). `-r` keeps the plugin's dictionaries. The two must come from the same build: the test helper only runs against a plugin signed with the same key.
3. Stops if `AuditTest` holds notes, unless `--clean` was given, in which case it deletes them.
4. Creates the decks and note types of R2 in AnkiDroid, through the test helper. A note type that already exists is kept, but its fields must be exactly the listed ones.
5. Resets the plugin's Anki settings to the defaults, so that Phase 3 starts from them and Phase 4 from the three default formats.
6. Checks the plugin's dictionaries: the five of R3, at exactly those revisions, enabled in exactly that order, nothing else enabled, and no sort dictionary.
7. Serves the test page and the local audio sources (R5), and opens the page in Chrome on the device (and in the desktop browser), until Enter is pressed (14.1).

Every run of this plan needs internet access on the device (1.3): the local audio sources work
without it, but the notes added with the default audio settings, and AU1 to AU4, AU7, and AU8
(R4), get their audio from JapanesePod101, LanguagePod101, Jisho.org, and Wikimedia Commons.

**Check** (Phase 8) reads the plugin's stored Anki settings and every note in `AuditTest` (and its
sub-deck), compares the notes with desktop's from `scripts/anki_test_plan_reference.json`, copies
each audio file that a plugin note names from AnkiDroid's media folder (`collection.media`), and
makes every check of R8, reporting each under its ID with one of these statuses:

- **PASS**: the check holds.
- **FAIL**: the check does not hold. A finding, unless R9 lists it as a known exception.
- **RECORD**: a value this plan says to record rather than compare.
- **REVIEW**: the script can't decide; look at the values it shows (for example, CSS whose rules differ in more than value spelling).
- **MISSING**: a note a check needs was not found on one or both platforms.

Which checks apply depends on the SDK version of the host that last started the plugin (R10),
which the plugin records; the check refuses to run until a Dokuen session has started the plugin
at least once. The report's header names the reference (desktop Yomitan's version and the capture
date), the host's level, and which checks that level selects.

It finds each note of R4 by its first field and the order it was added: T1 is the first `AuditTerm`
note whose `Expression` is `食べる`, T4 the second, TG the third, and TB the fourth; T8 is the second
`動き出す` note; T6b the second `京都` note; AU1 the second `記号` note; and so on. That is why a note added
by mistake must be deleted. It also checks each term note's `Reading`, which catches a note added
from the wrong entry.

It prints the results, and its folder holds `report.md` (every result, the values behind each one
that isn't a PASS, and every field it compared with both platforms' values and the rule applied,
where each newline in a value is shown as `\n`), `report.json` (the full values), and the notes and
settings it compared (`desktop-notes.json`, from the golden file, and `android-notes.json`), and
the audio files it copied, in `media/`. HTML in
the report's tables is escaped, so that a Markdown renderer shows it rather than parsing it.

**Reading a note's fields by hand**, to look into something the check reports: the check compares
stored field values byte for byte, never rendered cards (a card shows only its first field and
`Glossary`, and renders HTML such as `<ruby>` instead of showing it). Compare values in the report,
not on screen: its "Values" section shows both platforms' stored values, and `desktop-notes.json`
and `android-notes.json` hold every field in full. Neither app's note editor shows a stored value
exactly (desktop's matters only during the reference capture):

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
| Screenshot format / quality | advanced | Anki Export tab, **Images** card, with "{screenshot} behavior" | As in the original, they do not apply to `{clipboard-image}`, which is PNG. "{screenshot} behavior" chooses between the whole screen Dokuen scanned and Dokuen's crop UI; desktop has no such choice and never prompts. |
| Audio settings | the source list and "Enable Default Audio Sources" in the **Audio** section's **Configure audio playback sources…** dialog, and "Idle download timeout (in milliseconds)" in the **Anki** section, under advanced settings | all three in the Anki Export tab's **Audio** card | The plugin has no advanced mode, and its card keeps the settings that `{audio}` uses together. The labels and the timeout's description are the same (5.9, 5.22). The card lists the default sources switch first, as desktop's dialog does, and the timeout last, where desktop has it in its Anki section. |
| Label of the audio add button | "Add", in a dialog titled "Audio Sources" | "Add source" (5.23) | Desktop's "Add" sits in a dialog titled "Audio Sources", so it implies a source. The plugin's button sits on a card titled "Audio", where "Add" alone would lose that meaning. |
| Removing an audio source | each source's menu (Help, Move to…, Remove) | a remove button on each source, after its move up and move down buttons | The menu's other entries have no counterpart: the plugin has no help pages, and its move buttons change the order. |
| Text-to-speech audio sources | two types in the source menu, each marked as not supported in Anki | absent | The original never exports text-to-speech audio to Anki (see "There are no text-to-speech audio sources and no preferred audio choice" in "Deliberate differences" in `architecture.md`). |
| A custom source's URL field | "URL:" before the field | a field labeled **URL**, with a hint below it that names the `{term}`, `{reading}`, and `{language}` placeholders | The plugin's text fields carry their label in the field, as Android's Material text fields do, and the hint lists what the URL's placeholders are replaced with. |
| An audio download that hits the idle timeout | the note is added with `audio` empty, and nothing is shown | the note is added with `audio` empty, and the message `Note added to Anki, but {audio} is empty because its download timed out.` is shown (6.35) | The original means to report the timeout but never does, and an empty field would otherwise go unexplained (see "`{audio}` reports timed-out downloads and unreachable sources" in "Deliberate differences" in `architecture.md`). |
| Every audio request fails without a response | the note is added with `audio` empty, and nothing is shown | the note is added with `audio` empty, and the message says that there is no network connection (10.6) or that no audio source could be reached (6.36, 10.7) | An empty field would otherwise go unexplained (see "`{audio}` reports timed-out downloads and unreachable sources" in "Deliberate differences" in `architecture.md`). |
| An audio lookup that never answers | no timeout | cancelled after 30 seconds without progress (10.7) | A lookup that never answered would otherwise hold the Anki write indefinitely (see "`{audio}`'s lookups time out" in "Deliberate differences" in `architecture.md`). |
| Suspend new cards | advanced toggle | absent | AnkiDroid's API has no equivalent. |
| Note viewer window (Browse/Edit) | select, and a "View added note" button after an add | absent: no link to a note | AnkiDroid has no API for opening its note editor, and on Android 14 and later a plugin cannot open AnkiDroid's card browser either (see `architecture.md`). |
| Show card tags and flags | never/always/non-standard/custom + target tags | absent | It would need an AnkiDroid round trip per card format on every lookup (documented in `AnkiSettings.kt`). |
| Force Anki sync on adding card | advanced toggle | absent | AnkiDroid's API has no equivalent. |
| Configure Anki flashcards… | dialog | tab-strip, always-visible editor | Same content in a layout suited to the device; both save as you edit. |
| Configure Anki card templates… (raw Handlebars) | advanced dialog | absent | A documented non-goal. |
| Card format count | at most 5; the last cannot be deleted | same | |
| Defaults | tags `yomitan`; Allow adding; formats Expression, Reading, Kanji | tags `dokuen-yomitan`; otherwise the same | The tag differs on purpose, so notes from the two can be told apart (see `architecture.md`). |
| After an add | the button changes to its duplicate variant (disabled under Prevent adding) | the link reads **Added: F** (**Overwritten: F** after an overwrite) in the color of its duplicate variant, and stays tappable; under Prevent adding a second tap reports that the note already exists | A link cannot be disabled; the label reports what the tap did (see "After a tap" in `architecture.md`). |
| An add with an unknown marker | the note is added and its button changes; the error is shown | the note is added, and a message naming the marker is shown instead of the refreshed result | The host shows either a refreshed result or a message after a tap, not both (see "Marker/template engine" in `architecture.md`). |
| Anki not reachable | a scan while Anki is closed shows no card format buttons; a button shown before Anki closed shows `An error occurred:`, then on a line of its own `Anki connection failure` | each error after a tap is a message in the host's message area (10.3, 10.4) | The general shape is the same: the tap is refused with a message naming the problem. |
| Changing settings | at any time; an open popup keeps working | only while Dokuen's overlay (and so the dictionary window) is hidden | Dokuen does not let a plugin's settings change while its overlay is shown; a result from before the change is gone once the overlay is hidden. |
| Duplicate state before a tap | each button shows its duplicate variant as soon as the popup opens | every link is green and reads **Add** until a link of that entry is tapped; the tap then colors that entry's links | Checking on every lookup would cost an AnkiDroid query per format per lookup (see "Link colors" and "Known limitations" in `architecture.md`). |
| Icons | shape identifies the format; color shows the duplicate state: green, purple (add duplicate), orange (overwrite), gray (disabled) | a glyph (● ◉ ■ ◆) identifies the format; the same colors, without the disabled button's transparency | See "Link colors" in `architecture.md`. |

## R8. What the check script checks

Every field of each pair of notes must be identical on both platforms (desktop's from the golden
file, R6), except where a check below says otherwise, or R10 makes it depend on the host; the checks also give the exact value wherever it is known in advance.

### C1. The plugin's stored settings

- **C1.1** The plugin has exactly the 5 card formats of R2, in that order.
- **C1.2** Each format's type, deck, note type, and icon are as in R2.
- **C1.3** Each format's field values are as in R2.
- **C1.4** Each format's overwrite modes are as in R2.
- **C1.5** Export is on, and the duplicate settings are: checking on, scope Collection, across all models off, Allow adding (7.22).
- **C1.6** Card tags are empty (6.21).
- **C1.7** The audio settings are the defaults again (6.37): no audio sources, the default sources on, and an idle download timeout of `0`. A failure means that a step that changed them did not restore them.

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
| C2.9 | `conjugation` | T4 | The chain of conjugations undone from 食べさせられた to 食べる. On the plugin, exactly `-せる・させる « -られる « -た`, the compact Japanese names its popup shows. Desktop's value is recorded; it is expected to be `causative « potential or passive « -た`, the same chain under the original's English names (a deliberate difference, see "Deliberate differences" in `architecture.md`). |
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
| C2.20 | `sentence-furigana`, `sentence-furigana-plain` | all | On a host at SDK 0.8.0 or later (C4.5 otherwise): on both, each is identical apart from the `<span class="term">` boundaries in `sentence-furigana`, which follow each platform's own word segmentation (see "Deliberate differences" in `architecture.md`). A reading that differs is a finding to check against that section: Dokuen's furigana can legitimately differ from desktop's dictionaries. |
| C2.21 | `popup-selection-text`, `url`, `document-title`, `phonetic-transcriptions`, `pitch-accents`, `pitch-accent-graphs`, `pitch-accent-graphs-jj`, `pitch-accent-positions`, `pitch-accent-categories` | all | Empty on the plugin: the markers have no backing subsystem yet, a documented scope boundary. Desktop may fill some of them, depending on its own state, so desktop's values are recorded, not compared. (`audio` is C11.) |
| C2.22 | every other field | all | Identical on both. |
| C2.23 | `screenshot`, `clipboard-image`, `clipboard-text` | all | Recorded on both, not compared: each platform fills them from its own screen and clipboard when the note is added. Phases 11 to 13 check them by hand. |

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
- **C4.3** On a host at SDK 0.8.0 or later: on the plugin, `sentence-furigana` has `<ruby>` and `sentence-furigana-plain` has `[...]` readings, and both match desktop as C2.20 describes.
- **C4.4** T1 to T7 and T6b, each scanned from a line of its own: on both, `sentence` is the line (the scanned text and `。`), `cloze-prefix` is empty, `cloze-body` and `search-query` are the scanned text, `cloze-suffix` is `。`, and `cloze-body-kana` is the scanned text's kana: T1 `たべる`, T2 `うごきだす`, T3 `おみまい`, T4 `たべさせられた` (scanned as 食べさせられた), T5 `ぎゅう`, T6 and T6b `きょうと`, T7 `がくしゅうじてん`.
- **C4.5** On a host older than SDK 0.8.0, which gives no readings: on the plugin, `sentence-furigana` and `sentence-furigana-plain` each equal the note's `sentence`, for every term note (in place of C2.20 and C4.3).

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
| C6.7 | `search-query` | K1, K2 | `食べる` / `動き出す` on both: a kanji reached from a term's headword keeps the term lookup's query, which is longer than the kanji (see `{search-query}` under "Marker/template engine" in `architecture.md`). |
| C6.8 | `sentence`, `cloze-prefix`, `cloze-body`, `cloze-suffix` | K2 | Exactly `電車が動き出す前に座った。`, `電車が`, `動`, `き出す前に座った。` on both: the kanji carries the term's sentence, and the cloze is split at the term's position (see "Quirks kept for parity" in `architecture.md`). |
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
"The CSS in glossary `<style>` elements is not re-serialized by a browser" in `architecture.md`).

- **C7.1** The wrapper (`<div>`, `<ol>`, `<li data-dictionary=...>`, `<i>(...)</i>` prefix) matches desktop for every note in the table.
- **C7.2** T1: the whole definition matches desktop, including every `data-sc-*`, `style`, `lang`, and `title` attribute, the ruby, and the external links' `href`s.
- **C7.3** T3: every internal link's `href` is `#` on both, and the rest of the definition, including the ruby inside the link text, matches.
- **C7.4** 時雨: `colspan="2"` on the header cell on both, and the rest of the forms table matches.
- **C7.5** 鳥居: the plugin's image is a bare `<img>` with `alt` (the image's path in the dictionary), `width`, and `height` attributes but no `src`. Desktop stores the image in Anki's media folder and writes a link to that file around a stack of spans holding an `<img>` with a `src`. That is the known gap of R9, not a new finding: the rest of the `Glossary` is compared with each image blanked out on both, and both platforms' image markup is recorded.
- **C7.6** 千鳥: the same known gap for the inline glyph image.
- **C7.7** 順路: the translation's `->` appears as `-&gt;` on both, and the rest of the sentence, including its `"` characters, matches.
- **C7.8** 記号: the translation's `&` appears as `&amp;` on both.
- **C7.9** 學校: the `Glossary` field matches desktop: the redirect's structured content only, without the `["学校", [...]]` item. (Its popup entries are compared by hand in 6.13.)
- **C7.10** The plugin's `<style>` (T1): one follows each `<li>`; every top-level selector, including each part of a comma-separated list such as `li[data-sc-content="sense-group"], li[data-sc-content="forms"]`, is prefixed with `.yomitan-glossary [data-dictionary="Jitendex.org [2026-05-05]"]`; nested rules such as `& ul[data-sc-content="glossary"]` are still inside their parent rule; neither comment appears.
- **C7.11** Both platforms' `<style>` (T1) hold the same rules with the same selectors and property names. Differences in value spelling and whitespace are the known exception of R9; a missing rule, a different selector, or a nested rule moved out of its parent is a finding.
- **C7.12** T1's `glossary-first`: its `<style>` is prefixed with `.yomitan-glossary` only, not with the `[data-dictionary=...]` part.

### C8. Overwriting (椅子, plugin only)

- **C8.1** The first `椅子` note's `Glossary` is its original render followed by a second copy (7.4, Append mode). The other fields render the same on every add, so whether they were kept (Fill if empty) cannot be seen in the note.
- **C8.2** Exactly 2 `椅子` notes exist, and the second (7.5) is a new note with a single render.

### C9. Card tags (TG)

- **C9.1** On each platform exactly one note has the tag `jlpt-n3`, and its tags are exactly `jlpt-n3`, `vocab`, `core2k`, and `listening`.

### C10. Unknown marker (TB)

- **C10.1** TB is added on both (a template error does not stop the export), with its `Reading` field exactly `{expresion-render-error}` (see "Marker/template engine" in `architecture.md`). The message is compared by hand (6.22).

### C11. Audio (every term note)

The check compares the `audio` field of every term note that both platforms have: T1 to T8, T6b,
時雨, 鳥居, 千鳥, 順路, 記号, 學校, TG, TB, and AU1 to AU11. The notes before AU1 were added with the
default audio settings, which try JapanesePod101, LanguagePod101, and Jisho.org, in this order, on
both platforms.

Desktop stores `[sound:yomitan_audio_<SHA-1 of the file>.<extension>]`, with the extension that the
response's Content-Type gives (`.mp3` when it is not a known audio type). The plugin asks AnkiDroid
for the same name, but AnkiDroid inserts `_<digits>` before the extension, and gives Ogg audio
`.oga` where desktop writes `.ogg` (see "Media file names carry AnkiDroid's suffix" in "Deliberate
differences" in `architecture.md`).

- **C11.1** Each note's `audio` is empty on both, or the plugin's is `[sound:NAME]` and equals desktop's byte for byte once AnkiDroid's `_<digits>` before the extension is removed from NAME and an `.oga` extension is changed to `.ogg`. For a note whose audio comes from a remote source (every note but AU5, AU6, AU9, AU10, and AU11), the report's message for a mismatch names the capture date, because a source that has changed its file since the capture is the likely cause.
- **C11.2** For each plugin `audio` that is `[sound:NAME]`, the file NAME, copied from AnkiDroid's media folder, has the SHA-1 that its name gives. Desktop's name holds the SHA-1 of the file desktop stored, so together with C11.1 this shows that the plugin stored the same bytes. A file that AnkiDroid does not have is MISSING.

## R9. Known exceptions, and what the plan does not cover

Every field written by this feature should match desktop exactly, with these known, accepted
exceptions: the markers that still resolve to empty (pitch accents, phonetic transcriptions, page
context), the audio file name's AnkiDroid suffix and its `.oga` extension for Ogg (C11.1), the
screenshot and clipboard fields (C2.23), the term boundaries of
`sentence-furigana` (C2.20) and its plain sentence on a host older than SDK 0.8.0 (C4.5),
`part-of-speech`, the conjugation names in `conjugation` (C2.9), glossary images (the plugin stores
no dictionary media, C7.5), and the value spelling
and whitespace of the CSS in the glossary's `<style>` elements (C7). Any other difference is a new finding.

The pinned dictionaries don't exercise these converter cases, so this plan does not check them:

- Dropping `@media`, `@font-face`, and other at-rules from dictionary CSS: Jitendex's `styles.css` has none.
- `<details>`/`<summary>` elements: Jitendex does not use them.
- Turning export off while a result is open, and then tapping one of its links: the plugin's settings cannot be reached while the dictionary window is open. `AnkiExecutionTest` covers it (`turningExportOffAfterRenderingRefusesTheTap`).
- Wildcard searches: Dokuen has no dictionary search. A large result's links are tested with a kana scan instead (10.2).
- The intentional `{glossary-plain}` deviation (a `>` inside an attribute value, which desktop leaves behind as the literal typo `&rt;` and the plugin as `&gt;`; see "Deliberate differences" in `architecture.md`): no Jitendex entry has a `>` in an attribute value.

## R10. Configurations

Some of what the plugin exports comes from the host (Dokuen), through requests that its SDK version
decides, and Dokuen gives images only with Dokuen Pro:

| Behavior | Host older than SDK 0.7.0 | Host at SDK 0.7.x | Host at SDK 0.8.0 or later |
|---|---|---|---|
| `screenshot`, `clipboard-image` | empty, with no message | with Dokuen Pro: images; without it: empty, with a message that images require Dokuen Pro | as 0.7.x |
| `clipboard-text` | empty | the clipboard's text | the clipboard's text |
| `sentence-furigana`, `sentence-furigana-plain` | the plain sentence (C4.5) | the plain sentence (C4.5) | readings (C2.20, C4.3) |

Everything else is the same in every configuration. Configurations with the same expected results
are covered once: on a host older than SDK 0.7.0, Dokuen Pro changes nothing; and a host at SDK
0.7.x has nothing of its own to test (its images and clipboard text are as SDK 0.8.0's, and its
sentence furigana as an older host's). So a release is covered by three full runs, each done from
start to finish in one configuration:

| Run | Host | Dokuen Pro | Phases for it |
|---|---|---|---|
| 1 | older than SDK 0.7.0 | either | 11 (not 12 or 13) |
| 2 | SDK 0.8.0 or later | without | 12 (not 11 or 13) |
| 3 | SDK 0.8.0 or later | with | 13 (not 11 or 12) |

The plugin records the SDK version of the host that last started it; the check script (8.1) prints
it and selects C2.20 and C4.3, or C4.5, by it.
