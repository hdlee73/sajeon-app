# LEXI · 단어장 (Sajeon App)

Android English vocabulary lookup and personal wordbook for English words, idioms, and phrasal verbs.

## What changed in 1.2.0

- Common Korean meanings load from a local 48,037-entry English-Korean dictionary bundled into the APK at build time, so the first meaning appears without waiting for a translation server.
- A context-sensitive Korean gloss is used for “stuck”: “끼어 움직이지 않는; (일이나 문제 해결이) 막힌, 진전이 없는”.
- English definitions, IPA pronunciations, and authentic dictionary examples are fetched together from FreeDictionaryAPI.com, prioritizing IPA fields and examples from Wiktionary. A secondary dictionary API is used if that service cannot respond.
- Missing examples are shown as unavailable; the app no longer invents a sample sentence.
- Search results are cached during the app session. Saved words stay on the device and can be exported as `.xlsx`.

## Data attribution and license

The bundled Korean gloss dictionary is the [Open English-Korean Dictionary](https://github.com/jhseo1211/open-english-korean-dict), CC BY-SA 4.0. Its upstream credits list kengdic, cc-kedict, ipa-dict, CMU Pronouncing Dictionary, CEFR-J, NGSL, NAWL, and Wiktionary. Its source snapshot is pinned to commit `92cbfe63deee1ccead2c42677027d8b4a305b2c7`. See [CREDITS.md](CREDITS.md) and the upstream [license terms](https://creativecommons.org/licenses/by-sa/4.0/).

English definitions, IPA, and examples are from [FreeDictionaryAPI.com](https://freedictionaryapi.com/) using [Wiktionary](https://en.wiktionary.org/) content, licensed CC BY-SA 4.0. The app displays attribution on each entry card; the release page links back to the provider and source.

## Build

Open in Android Studio or run:

```sh
gradle :app:assembleDebug
```

The release workflow downloads the pinned SQLite dictionary to `app/src/main/assets/word_dictionary.sqlite` before packaging. For a local build, download that file into the same path first. Network access is required for IPA/examples and for words not in the local Korean dictionary. If the enrichment API is unavailable, the local Korean meaning remains visible.

GitHub Actions builds the APK on pushes to `main`; commits with `[release]` also publish a GitHub Release.
