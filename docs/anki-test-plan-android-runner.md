# Anki Export Test Plan: Android Runner

`scripts/anki_test_plan_android.py` is an optional assistant for the
[manual test plan](anki-export-manual-test-plan.md). The plan, with its checklist page
(`anki-test-plan-checklist.html`), is the required procedure, and it is complete without the
runner: every step can be done by hand. The runner only takes over some of that work. It does the
plan's Android steps on the connected device, checks what each step asks to confirm, and, for the
steps it cannot do, shows the step and asks the tester. A tester may use it for the whole plan, for
some phases or steps, or not at all.

The runner follows the plan, not the other way round. It reads each step's text from Part 1 of the
plan, by step ID, and its checks are the plan's confirmations. Where it does a step differently
from how the plan describes it, the result on the device is the same; the differences are listed
in "Where it differs from the plan".

## Requirements

Everything the plan's Phase 1 lists, and also:

- The Python package `uiautomator2` (`pip install uiautomator2`). It installs its agent on the
  device the first time it connects.
- The device's language set to English, since the runner finds controls by their labels.
- The plugin and its test helper installed by the plan's setup (Phase 2), which builds them as debug
  builds. The runner reads the plugin's log, which only a debug build writes, to see what each scan
  looked up.

The runner uses the test page that setup or `python scripts/anki_test_plan.py serve` serves, or
serves it itself if nothing does. It keeps the device's screen on while it runs and restores the
setting at the end.

## Running it

| Command | Does |
|---|---|
| `python scripts/anki_test_plan_android.py run` | Every step of the plan, from 1.1, in order. |
| `... run --from 8.1` | From step 8.1 on (`--to 9.5` also sets a last step). |
| `... run --steps 9.3,9.4` | Only these steps. |
| `... run --android-only` | Leaves out the steps only the tester can do. |
| `... run --unattended` | Never waits for the tester; its questions are left unanswered. |
| `... run --pro yes` or `--pro no` | Says whether Dokuen Pro is active (see "Dokuen Pro"). |
| `... run --pause-for-pro` | Pauses at 13.3 for the tester to turn Dokuen Pro on (see "Dokuen Pro"). |
| `python scripts/anki_test_plan_android.py list` | Every step, how the runner handles it, and any step of the plan it does not know. |

A tester can move between the runner and doing steps by hand at any step: each step leaves the
device as the plan describes, so `--from` resumes after steps done by hand, and steps done with the
runner can be followed by hand.

## How a run goes

Each step is one of these kinds:

- **auto**: done and checked on the device.
- **partial**: done on the device, with a question for the tester about what only a person can
  judge: desktop's side of a comparison, an image in AnkiDroid, or a message compared with
  desktop's.
- **manual**: a desktop step, or one the runner cannot do. It shows the step and asks whether it
  was done and every confirmation held.

Step 10.1 runs the check script itself when desktop Anki answers through AnkiConnect, and asks the
tester to start Anki when it does not.

Each run writes `report.md`, `report.json`, and screenshots to
`anki-test-plan-reports/<date-time>-android/`, and gives each step one of these statuses:

| Status | Meaning |
|---|---|
| PASS | Every check held. |
| FAIL | A check did not hold. A finding, unless the plan lists it as a known exception. |
| ERROR | The runner could not finish the step, for example because a screen looked unexpected. Do the step by hand. |
| REVIEW | A person has to decide. |
| PARTIAL | The step was checked, but a question for the tester went unanswered. |
| MANUAL | A step for the tester, not done. |
| SKIPPED | A step for the other kind of device, with or without Dokuen Pro. |

A FAIL or an ERROR does not stop the run: the runner records it, with a screenshot, and goes on to
the next step. A step that failed may leave the device in a state the plan's next steps do not
expect, so read the report from its first failure.

### Dokuen Pro

Phase 13 has steps only for a device with Dokuen Pro, and one only for a device without it. The
runner runs those for the device at hand and records the others as SKIPPED. It learns which kind
of device it is from the first add (without Dokuen Pro, an add shows a message about the image
fields), or from `--pro`. Until it knows, it asks the tester, or, with `--unattended`, skips those
steps.

The rest of the plan does not depend on Dokuen Pro, and the plan puts the steps that need it last,
after 13.3, where the tester can turn it on. Before a run, the tester chooses between two ways
through 13.3:

- **One pass (the default).** The run does not stop at 13.3. It does the steps for the device as it
  is, skips those for the other kind of device, and goes on to the plan's last steps. With
  `--unattended`, the whole run needs no one at the device.
- **With a pause (`--pause-for-pro`).** On a device without Dokuen Pro, where it can be turned on,
  the run pauses at 13.3, even with `--unattended`, until the tester turns Dokuen Pro on and
  presses Enter. It then does 13.4 (a new Dokuen session, and the `猫` notes cleared), the steps that
  need Dokuen Pro, and the plan's last steps. So one run covers the device both without and with
  Dokuen Pro, and the steps after the pause take minutes, where doing the whole plan again would
  take hours. Typing `skip` instead goes on without Dokuen Pro, as one pass does.

A run that stopped before 13.3 can be resumed with `--from`. For the steps that need Dokuen Pro on
their own, after turning it on, use `run --from 13.4 --pro yes`.

## How it does what the tester does

- **Scanning.** Dokuen's overlay draws the recognized text without accessibility nodes, so the
  runner asks the test page where the word is. The page in Chrome on Android polls the server for
  a line to show, scrolls it to the middle of the screen, and reports where each character of the
  word is (see `PAGE_SCRIPT` in `anki_test_plan.py`, which changes nothing a scan sees). The runner
  then shows the overlay and drags across exactly those characters. It reads what the plugin looked
  up from the plugin's log, and when the OCR misread the word, it does what the plan tells the
  tester to do. In 8.10 and 8.13, it corrects each misread character with **Repair**, pasting the
  right one into its box, since the box takes Japanese input. Elsewhere, the page enlarges the
  word's line, as a tester zooms in, and the runner scans it again. A word the OCR still misreads
  fails its step, for the tester to do by hand.
- **The dictionary window.** Entries are found by their headword row and the readings above it,
  links by their text (`● Add: AuditVocab`), messages in the bar at the window's bottom, and link
  colors by sampling a screenshot against the colors of the plan's R7. The number of entries in
  12.2 comes from the plugin's log, since counting them on screen would mean scrolling through
  dozens of screens.
- **The dashboard.** Controls are found by their labels. The card format tabs are scrolled by
  tapping the tab at the strip's edge, because a swipe there would also turn the dashboard's page.
- **AnkiDroid.** The deck in 12.4 is deleted in AnkiDroid's deck list, since AnkiDroid's API
  cannot delete decks. Notes are handled through the test helper (see "Where it differs from the
  plan"). Android lets one client at a time drive the UI, and the test helper takes it to grant
  itself the AnkiDroid permission, so the runner stops uiautomator2's agent before each run of the
  helper.
- **Dokuen's session.** Dokuen connects to the plugin only when its session starts, and every run
  of the test helper restarts the plugin's process. So the runner starts a new session (Dokuen's
  main page, **Stop**, **Start**, sharing Chrome) before the first scan of a run and before the
  first scan after each run of the helper, as the plan's 10.3 does after the check. It also starts
  one when 8.23 and 8.25 ask for one, and when the overlay does not appear, as happens when the
  screen sharing has ended on its own.
- **Interruptions.** A prompt no step expects, such as Google Play asking for a review of Dokuen,
  is dismissed.

## Where it differs from the plan

These steps reach the state the plan describes by another route:

- **3.1** also clears Android's record that the permission was denied before
  (`pm clear-permission-flags`). After two denials, in earlier runs, Android stops asking, and the
  plugin offers **Open settings** where 3.4 expects **Grant permission**.
- **3.6** revokes the permission with `pm revoke`, which is what Android's Settings does.
- **Phase 9 on**: notes are counted, added, and deleted through the test helper (its `dumpNotes`,
  `addNote`, and `deleteNotes` actions) rather than AnkiDroid's screens. The notes are what those
  steps set up and check, and the plugin's duplicate check is what they test.
- **13.2** puts the two lines on the clipboard directly rather than copying them from a text
  editor.
- **13.7** chooses, in Android's photo picker, the test page's image, which the runner puts in the
  device's Pictures folder as its newest photo for the length of the step.
- **13.11** copies, in Chrome, an image that the test page's server serves.

## Maintaining it

Each step's automation is a function in `scripts/anki_test_plan_android.py`, registered with
`@step('<step ID>', <kind>, needs=...)`; steps the tester does are registered with `manual(...)`.
`list` names any step of the plan that the runner does not know. When a step of the plan changes,
change its function to match, so that the runner keeps doing what the plan says.

The runner depends on:

- the plan's Part 1, for the step IDs and texts it shows;
- the test page and its `PAGE_SCRIPT`, and the server's `/control` endpoints, in
  `scripts/anki_test_plan.py`;
- the test helper's actions (`AnkiTestPlanHelper`), as `anki_test_plan.py` does;
- the English labels of the plugin's dashboard, and Dokuen's screens: its main page, floating
  button, overlay, dictionary window, Repair, and crop UI;
- the plugin's debug log lines `onLookup: contextText=...` and
  `onLookup completed. Found N entry/entries.`
