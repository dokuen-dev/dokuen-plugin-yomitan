# Yomitan plugin for Dokuen Japanese Reader

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-Compile_SDK_37-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Compose](https://img.shields.io/badge/Compose-Jetpack-purple.svg?style=flat&logo=jetpackcompose)](https://developer.android.com)
[![SQLite Room](https://img.shields.io/badge/SQLite-Room_Database-blue?style=flat&logo=sqlite)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/License-GPL_v3-red.svg?style=flat)](LICENSE)

A dictionary plugin for **Dokuen Japanese Reader** that adds offline **Yomitan** dictionary search
capabilities on Android.

[**Download on Google Play**](https://play.google.com/store/apps/details?id=io.github.dokuendev.dokuen.plugins.dictionary.yomitan)

<p><kbd><img src="https://github.com/user-attachments/assets/a9625dce-d5c6-4e83-b056-d511e51f9984" width="250"></kbd>&nbsp;&nbsp;&nbsp;&nbsp;<kbd><img src="https://github.com/user-attachments/assets/1aab81ee-7333-45ca-94d0-69c3d9c4b927" width="250"></kbd>&nbsp;&nbsp;&nbsp;&nbsp;<kbd><img src="https://github.com/user-attachments/assets/7185e653-4a01-4e05-a373-6c9162e97bfa" width="250"></kbd></p>

---

## Overview

This project is an Android port of the popular [Yomitan](https://github.com/yomidevs/yomitan)
browser extension for Japanese dictionary search, deinflection, and rendering. It is packaged as a
plugin app conforming to
the [Dokuen Dictionary Plugin SDK](https://github.com/dokuen-dev/dokuen-reader/tree/main/sdk/dictionary)
spec. Using Dokuen's OCR infrastructure, it provides overlay definitions on top of any app, not just
your browser.

When installed
alongside [Dokuen Japanese Reader](https://play.google.com/store/apps/details?id=io.github.dokuendev.dokuenreader),
it allows you to look up words and kanji offline. Dokuen handles the OCR and the system-wide
overlay, while this plugin supplies the dictionary lookups, deinflection, and definition rendering.

---

## Features

* **Yomitan Archive Importer:** Import any official or community Yomitan dictionary `.zip` package.
  Works with:
    * **V1 formats** (older formats).
    * **V3 formats** (modern formats including structured content tags).
* **High-Performance Search:** Fast word lookups powered by SQLite with Room database indexing,
  keeping memory footprint low.
* **Intelligent Deinflection:** Integrates a Kotlin port of the Yomitan deinflector to recognize
  conjugations (verbs, adjectives, etc.) and match them back to their dictionary forms.
* **Structured Content Renderer:** Accurately renders Yomitan structured content elements,
  definitions, lists, bullet points, tags, and CSS styles in a clean mobile interface.
* **Interactive Kanji Links:** Tapping a character in a headword opens the kanji entry with stroke
  order diagram and kanji stats.
* **Modern Compose UI:** A clean dark-mode theme management screen that allows users to:
    * Install high-quality presets (Jitendex, JMnedict, KANJIDIC).
    * Import local ZIPs from device storage.
    * Download dictionaries directly via custom URLs.
    * Reorder search priority of active dictionaries, or disable/delete them.

---

## Architecture & Internals

The plugin is structured as an Android application with two main components:

1. **The Plugin Service:** A background service subclassing the Dokuen SDK's
   `DictionaryPluginService` that handles IPC queries from Dokuen.
2. **The Dashboard Activity:** A Jetpack Compose activity where users manage their dictionary
   databases. This activity also serves as the plugin's configuration UI, launched by Dokuen when
   the user taps **Configure** in the plugin manager.

### Dokuen Integration

The plugin is discovered and managed by Dokuen Japanese Reader as
a [Dictionary plugin](https://github.com/dokuen-dev/dokuen-reader/blob/main/sdk/dictionary/README.md)
via Android IPC (AIDL). It publishes its capabilities by implementing
`YomitanDictionaryPluginService`:

```kotlin
override val capabilities = Bundle().apply {
    putBoolean(PluginCapabilityKeys.HANDLES_SEGMENTATION, true)
    putBoolean(PluginCapabilityKeys.REQUIRES_DICTIONARY_FORM, false)
    putStringArray(PluginCapabilityKeys.SUPPORTED_SOURCE_LANGUAGES, arrayOf("ja"))
    putStringArray(
        PluginCapabilityKeys.SUPPORTED_TARGET_LANGUAGES,
        arrayOf("en", "es", "fr", "de", "it", "zh-CN", "ko")
    )
}
```

Setting `HANDLES_SEGMENTATION = true` means Dokuen passes the full OCR block text to `onLookup`
along with cursor indices, rather than just the selected word. This gives the plugin surrounding
sentence context for more accurate lookups.

The plugin hosts its own configuration UI via `configActivityName = ".MainActivity"`. Dokuen
launches `MainActivity` when the user taps **Configure** in the plugin manager. The plugin checks
`isConfigured()`, which verifies that at least one dictionary is active in SharedPreferences, to
determine whether the plugin is ready to use.

When Dokuen queries a word, the service receives
`onLookup(contextText, cursorStartIndex, cursorEndIndex)` and handles:

- Substring slicing (up to 12 characters from the cursor position).
- Running the `JapaneseDeinflector` to generate base form candidates.
- Database query for matched terms or kanji characters.
- Formatting the output as a `DictionaryResult` composed of `DictionaryEntry` objects.

### Database Schema

Data is stored using Android Jetpack Room with the following table entities:

* **`DictionaryEntity`:** Stores metadata about imported dictionaries (version, author, description,
  CSS styles, active status).
* **`TermEntity`:** Stores dictionary entries: expressions, readings, definition tags, search score,
  sequence, and raw glossary JSON.
* **`KanjiEntity`:** Stores kanji characters, onyomi, kunyomi, meanings, and statistics.
* **`MediaEntity`:** Caches images embedded inside the dictionaries as byte arrays.
* **`TagMetaEntity`:** Stores tag category details (e.g. grammar, dialect) and styling hints.

---

## Getting Started

> [!IMPORTANT]
> This application acts as a plugin. You must have **Dokuen Japanese Reader** installed to perform
> lookups.
> You can download Dokuen from the
> [Google Play Store](https://play.google.com/store/apps/details?id=io.github.dokuendev.dokuenreader).

### Configuration Steps

1. Download, install, and open this Yomitan Plugin application on your Android device.
2. Import your dictionaries:
    * **Presets:** Tap to download preconfigured dictionaries like **Jitendex**, **JMnedict**, or
      **KANJIDIC**.
    * **Local ZIP:** Select a Yomitan `.zip` file from your device downloads.
    * **Custom URL:** Provide a direct URL to a Yomitan dictionary `.zip` to download and import it.
3. Toggle the switches to activate the dictionaries you want to search.
4. Use the Up/Down arrows to change the lookup order.
5. Open **Dokuen Japanese Reader**, go to **Settings → Dictionary**, and select the Yomitan plugin.

---

## Development & Build

### Prerequisites

* **Android Studio** (Koala or newer recommended)
* **Android SDK** (Compile SDK 37, Target SDK 36, Min SDK 29 / Android 10)
* **JDK 17**

### Gradle Commands

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew test
```

Generate debug render output from test fixture files:

```bash
./gradlew generateDebugEntries
```

This reads raw Yomitan entry JSON from `app/src/test/resources/testcases/` (using a mocked database
rather than a real one), runs them through the renderer, and writes the results to
`debug_entry.json` and `debug_kanji_entry.json` at the project root. This is useful for iterating on
the renderer without needing a full dictionary import.

---

## Contributing

Contributions and pull requests are welcome. If you plan to work on something substantial, opening
an issue first to discuss the approach is appreciated. See the [Roadmap](#roadmap) for known gaps,
or browse the issue tracker for smaller tasks.

---

## Roadmap

Features present in the original Yomitan browser extension that are not yet implemented in this
port:

* **Custom Anki templates:** Yomitan supports configurable Handlebars templates mapping markers (
  `{expression}`, `{reading}`, `{sentence}`, `{audio}`, `{pitch-accents}`, `{cloze-*}`, frequency
  data, etc.) to note fields. Currently only a fixed card format is supported.
* **Pronunciation audio:** Playback of word audio from dedicated sources such as JapanesePod101 or
  Forvo. Currently only Android's built-in TTS is used.
* **Pitch accent display:** Rendering of pitch accent graphs and downstep notation from dictionaries
  that include pitch data (e.g. [Kanjium](https://github.com/mifunetoshiro/kanjium)). The data is
  stored in the glossary JSON but not yet surfaced in the UI.
* **Word frequency dictionaries:** Yomitan supports frequency dictionaries (e.g. JPDB, Innocent
  Corpus) both for displaying corpus frequency inline with definitions and for influencing search
  result sort order. Neither is currently implemented.

---

## License

This project is licensed under the GPL-3.0 License. See the [LICENSE](LICENSE) file for details.
