# Anki Export Test Plan: Runner

`scripts/anki_test_plan_android.py` does the [test plan](test-plan.md) on a connected device. The
plan, with its checklist page (`test-plan-checklist.html`), is the required procedure, and it is
complete without the runner: every step can be done by hand. The runner does every step itself,
from setup (2.1) on, checks what each step asks to confirm, and saves what only a person can judge
for one review at the end of the run, so that a run needs no one at the device until it is done.

The runner follows the plan, not the other way round. It reads each step's text from Part 1 of the
plan, by step ID, and its checks are the plan's confirmations. Where it does a step differently
from how the plan describes it, the result on the device is the same; the differences are listed
in "Where it differs from the plan".

## Requirements

Everything the plan's Phase 1 lists, and also:

- The Python package `uiautomator2` (`pip install uiautomator2`). It installs its agent on the
  device the first time it connects.
- The device's language set to English, since the runner finds controls by their labels.
- adb connected to the device over USB, not over the network (`adb connect` or wireless
  debugging), since 10.6 turns the device's Wi-Fi and mobile data off. The runner refuses to do
  that step when the device's serial (`--serial`, or else the one `adb get-serialno` reports) is a
  `host:port` or wireless debugging's `._adb-tls-connect._tcp` name, since turning the network off
  would cut that connection.

Like the plan, the runner needs the device online, since `{audio}` downloads from remote sources
in Phase 6: 1.3 fails unless Android has validated one of the device's connections as reaching the
internet.

The runner runs the plan's setup (2.1) itself, which builds the plugin and its test helper as debug
builds and installs them. The runner reads the plugin's log, which only a debug build writes, to
see what each scan looked up. It serves the test page itself, and keeps the device's screen on
while it runs, restoring the setting at the end. Desktop Anki and desktop Yomitan are not needed:
the plan compares with the committed [reference capture](reference-capture.md).

## Running it

| Command | Does |
|---|---|
| `python scripts/anki_test_plan_android.py run` | Every step of the plan, from 1.1, in order, then the review. |
| `... run --from 6.1` | From step 6.1 on (`--to 7.5` also sets a last step). |
| `... run --steps 7.3,7.4` | Only these steps. |
| `... run --no-build` | Setup (2.1) installs the APKs already built instead of building them. |
| `... run --pro yes` or `--pro no` | Says whether Dokuen Pro is active (see "Configurations"). |
| `... run --unattended` | Never waits for the tester; the review's questions are left for the `review` command. |
| `python scripts/anki_test_plan_android.py review <folder>` | Asks the questions a run left PENDING, for the run whose report is in `<folder>`. |
| `python scripts/anki_test_plan_android.py list` | Every step, how the runner handles it, its configuration, and any step of the plan it does not know. |

A tester can move between the runner and doing steps by hand at any step: each step leaves the
device as the plan describes, so `--from` resumes after steps done by hand, and steps done with the
runner can be followed by hand.

## How a run goes

A run is one configuration of the plan's R10: the host's SDK level and, on a host at SDK 0.7.0 or
later, whether Dokuen Pro is active. Once setup has run (2.3), the runner scans a word, which makes
the plugin record the host's SDK version, reads it, and prints the configuration. Phases 11, 12, and
13 are each for one configuration; the runner does the one for the device at hand and records the
others as SKIPPED, with the reason. The runner reads the configuration at most once, so when that
fails, the steps of those phases fail with the error instead of reading it again. A release needs
one run per configuration (R10).

Each run writes `report.md`, `report.json`, and screenshots to
`anki-test-plan-reports/<date-time>-android/`, and gives each step one of these statuses:

| Status | Meaning |
|---|---|
| PASS | Every check held, and every question about it was answered yes. |
| FAIL | A check did not hold, or a question was answered no. A finding, unless the plan lists it as a known exception. |
| ERROR | The runner could not finish the step, for example because a screen looked unexpected. Do the step by hand. |
| PENDING | A question about the step waits for the review. |
| SKIPPED | A step for another configuration. |
| MANUAL | A step for the tester, not done (only a plan step the runner does not know, see "Maintaining it"). |

A FAIL or an ERROR does not stop the run: the runner records it, with a screenshot, and goes on to
the next step. A step that failed may leave the device in a state the plan's next steps do not
expect, so read the report from its first failure.

### The review

What only a person can judge is not asked during the run. The step records a question, with the
images it is about, and is PENDING until it is answered:

- the image steps of Phase 13: whether the note's screenshot or clipboard image, copied from
  AnkiDroid's media folder into the report folder, shows what the step expects, next to the image
  it is compared with (the device's screen at the scan, the crop UI, the chosen file, 13.3's PNG,
  or the copied image);
- 8.2: for every FAIL, REVIEW, or MISSING result of the check script, whether the plan's R9 lists
  it as a known exception.

At the end of the run, the runner writes `review.html` in the report folder (each question with its
images side by side and what is expected), opens it, and asks the questions in the terminal one
after another: **y**, **n** (then what you saw), or **s** to stop and answer the rest later. The
answers are written into the report. With `--unattended`, or after **s**, the questions stay
PENDING, and `review <folder>` asks them later.

### Configurations

The host's SDK level comes from the plugin, which records the SDK version of the host that last
started it. Whether Dokuen Pro is active comes from `--pro`, or else from the first add through a
format with image fields on a host at SDK 0.7.0 or later: without Dokuen Pro, the add shows a
message about the image fields. A write shows at most one message, and the plan's other messages
(about an unknown marker, rejected media, or an empty `{audio}`) take the place of that one, so the
runner learns nothing about Dokuen Pro from an add that shows another message, such as 6.22's. A
host older than SDK 0.7.0 shows no such message either way, so the runner never concludes anything
about Dokuen Pro there, where it changes nothing (R10). If a run reaches Phase 12 or 13 still not
knowing, it asks, or with `--unattended` skips both.

## How it does what the tester does

- **Scanning.** Dokuen's overlay draws the recognized text without accessibility nodes, so the
  runner asks the test page where the word is. The page in Chrome on Android polls the server for
  a line to show, scrolls it to the middle of the screen, and reports where each character of the
  word is (see `PAGE_SCRIPT` in `anki_test_plan.py`, which changes nothing a scan sees). The runner
  then shows the overlay and drags across exactly those characters. It reads what the plugin looked
  up from the plugin's log, and when the OCR misread the word, it does what the plan tells the
  tester to do. In 6.10 and 6.13, it corrects each misread character with **Repair**, pasting the
  right one into its box, since the box takes Japanese input. Elsewhere, the page enlarges the
  word's line, as a tester zooms in, and the runner scans it again. A word the OCR still misreads
  fails its step, for the tester to do by hand.
- **The dictionary window.** Entries are found by their headword row and the readings above it,
  links by their text (`● Add: AuditVocab`), messages in the bar at the window's bottom, and link
  colors by sampling a screenshot against the colors of the plan's R7. The number of entries in
  10.2 comes from the plugin's log, since counting them on screen would mean scrolling through
  dozens of screens.
- **The dashboard.** Controls are found by their labels. The card format tabs are scrolled by
  tapping the tab at the strip's edge, because a swipe there would also turn the dashboard's page.
- **The Audio card.** Each step that sets the audio sources (6.26 to 6.36, 10.7, and 10.8) puts
  the card's defaults back afterwards (no sources, **Enable Default Audio Sources** on, and an idle
  download timeout of `0`), also when the step fails, so that a failure does not affect the steps
  that follow. A failure to put them back fails the step. 5.23 and 5.24 leave sources on the card
  for 5.25 to remove, so a failure in 5.25 can leave sources there until 6.37 restores the defaults.
  10.8 also puts back the `audio` field's overwrite mode and **Allow adding**. After each add with
  the audio sources set, the report notes every request to the local audio sources (R5) that the
  test page's server had no audio for, such as a Custom URL that the plugin filled in differently
  from desktop. 10.8 counts the requests in the server's log, so the runner must serve the test page
  itself for that step, rather than setup or `serve` in another terminal (which print each request
  instead).
- **The network (1.3 and 10.6).** 1.3 reads `dumpsys connectivity` for a network whose
  capabilities include `VALIDATED`, which is what Android's Settings show as a connection without a
  "No internet" mark. 10.6 turns Wi-Fi and mobile data off with `svc wifi disable` and
  `svc data disable`, and afterwards, also when the step fails, turns both on again and waits up to
  30 seconds for a validated network.
- **Dokuen's plugin list (1.2).** Dokuen's main page, **Settings**, **Plugin Manager**, and
  **Dictionary Plugins**, where the plugin is listed by its manifest's `plugin_name` (`Yomitan`)
  and description, and its card's toggle reads **Selected**.
- **AnkiDroid.** The deck in 10.4 is deleted in AnkiDroid's deck list, since AnkiDroid's API
  cannot delete decks. In 3.3 the runner taps the Play Store's **Install**; if the Play Store's
  screens are not as expected, an attended run asks the tester to install AnkiDroid, and an
  unattended one fails the step. Notes are handled through the test helper (see "Where it differs
  from the plan"). Android lets one client at a time drive the UI, and the test helper takes it to
  grant itself the AnkiDroid permission, so the runner stops uiautomator2's agent before each run
  of the helper, including setup's and the check script's.
- **Dokuen's session.** Dokuen connects to the plugin only when its session starts, and every run
  of the helper restarts the plugin's process. So the runner starts a new session (Dokuen's
  main page, **Stop**, **Start**, sharing Chrome) before the first scan of a run and before the
  first scan after each run of the helper, as the plan's 8.3 does after the check. It also starts
  one when 6.23 and 6.25 ask for one, and when the overlay does not appear, as happens when the
  screen sharing has ended on its own.
- **Interruptions.** A prompt no step expects, such as Google Play asking for a review of Dokuen,
  is dismissed.

## Where it differs from the plan

These steps reach the state the plan describes by another route:

- **1.3** reads `dumpsys connectivity` rather than Android's Settings (see "The network" above).
- **2.1** always deletes notes left in `AuditTest` (setup's `--clean`), and logs how many.
- **3.1** also clears Android's record that the permission was denied before
  (`pm clear-permission-flags`). After two denials, in earlier runs, Android stops asking, and the
  plugin offers **Open settings** where 3.4 expects **Grant permission**.
- **3.6** revokes the permission with `pm revoke`, which is what Android's Settings does.
- **6.13** compares the entries' headwords and readings, in order, with the list the plan states
  (`GAKKOU_ENTRIES`), not the dictionary of each entry.
- **Phase 7 on**: notes are counted, added, and deleted through the test helper (its `dumpNotes`,
  `addNote`, and `deleteNotes` actions) rather than AnkiDroid's screens. The notes are what those
  steps set up and check, and the plugin's duplicate check is what they test.
- **8.2** asks one question per FAIL, REVIEW, or MISSING result, in the review.
- **10.6** turns Wi-Fi and mobile data off and on with `svc` rather than in Android's quick
  settings (see "The network" above), and turns both on again afterwards, even one that was off
  before the step.
- **12.2 and 13.2** put the two lines on the clipboard directly rather than copying them from a
  text editor.
- **13.3 to 13.5, 13.8, and 13.9**: the images are judged in the review, from the files copied
  out of AnkiDroid's media folder, rather than in AnkiDroid's note editor.
- **13.5** chooses, in Android's photo picker, the test page's image, which the runner puts in the
  device's Pictures folder as its newest photo for the length of the step.
- **13.9** copies, in Chrome, an image that the test page's server serves.
- **14.1** stops the runner's own server, when it served the page.

## Maintaining it

Each step's automation is a function in `scripts/anki_test_plan_android.py`, registered with
`@step('<step ID>')`; a step the tester does would be registered with `manual(...)`, which names
what the tester does or the questions to ask, each with its expected value. `list` names any step
of the plan that the runner does not know; a run shows such a step and asks one question per
"Confirm ..." sentence of its text. When a step of the plan changes, change its function to match,
so that the runner keeps doing what the plan says.

The runner depends on:

- the plan's Part 1, for the step IDs and texts it shows;
- `PHASES`, which must match the conditions the intros of the plan's Phases 11 to 13 state;
- `GAKKOU_ENTRIES`, which must match the entries the plan's 6.13 lists (the reference capture says
  when to update both);
- the test page and its `PAGE_SCRIPT`, the server's `/control` endpoints, and its local audio
  sources (`audio_urls`, `AUDIO_WORDS`, and the server's `audio_requests` log), in
  `scripts/anki_test_plan.py`;
- the test helper's actions (`AnkiTestPlanHelper`), as `anki_test_plan.py` does, including the
  `hostSdkVersion` that `dumpPluginState` reports;
- the English labels of the plugin's dashboard, and Dokuen's screens: its main page and its
  **Settings**, **Plugin Manager**, and **Dictionary Plugins** screens, floating button, overlay,
  dictionary window, Repair, and crop UI;
- the plugin's debug log lines `onLookup: contextText=...` and
  `onLookup completed. Found N entry/entries.`
