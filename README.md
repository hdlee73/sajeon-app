# 사전앱 (Sajeon App)

Android English vocabulary lookup and personal wordbook for English words, idioms, and phrasal verbs.

## Features

- Look up English definitions and available IPA pronunciations from the Free Dictionary API.
- Play words and phrases with the phone's English text-to-speech voice.
- If a lookup fails, choose from spelling suggestions powered by Datamuse.
- Translate definitions into Korean using MyMemory. Example sentences come from dictionary entries; a simple sample sentence is shown when none is supplied.
- Save only entries you choose. Saved words remain on the device in SQLite and can be viewed or deleted.
- Export saved entries as a real `.xlsx` workbook through Android's document picker. Choose a folder and filename there. Google Drive appears as a destination when its Android document provider is installed and signed in.
- Workbook columns: English word and IPA; Korean meaning followed by English definitions on a new line; English examples.

## Build

Open this project in Android Studio or run:

```sh
gradle :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the debug APK on pushes to `main`; pushing a tag such as `v1.0.0` also creates a GitHub Release with the APK attached. A main-branch commit whose message includes `[release]` publishes the version in `app/build.gradle.kts`.

## Notes

Network access is needed for lookups, suggestions, and translation. Saved entries and exports are local unless you choose a cloud document destination. Dictionary text and translations are provided by third-party services and may need review. Datamuse's documentation says API keys will be required from January 1, 2027.
