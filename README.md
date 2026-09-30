# LEXI · 단어장 (Sajeon App)

Android English vocabulary lookup and personal wordbook for English words, idioms, and phrasal verbs.

## What changed in 1.3.0

- Korean meanings use the bundled English-Korean dictionary first, then Korean Wiktionary lexical translations; machine translation of the English definition is only a last fallback.
- Every successful word or phrase lookup includes at least one English example. If neither dictionary provides an example, a complete example sentence is supplied. English examples are shown and exported with Korean translations.
- Excel export offers three layouts: word + meanings + bilingual examples; Korean example translation + English example in two columns; or English examples only.
- IPA symbols are hidden from the search and saved-word screens, never written to new wordbook records, omitted from all exports, and removed from existing saved records during upgrade.

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

## APK update signing

Versions through v1.4.0 were built with ephemeral GitHub runner debug keys. Their signing certificates differ, so an APK from one build cannot update an installed APK from another build. An old APK's public certificate cannot recover its private signing key.

The workflow now requires the repository Actions secret `ANDROID_DEBUG_KEYSTORE_BASE64`. Supply the Base64 encoding of a fixed Java keystore with alias `androiddebugkey`, store password `android`, and key password `android`. Keep the original keystore in a durable private backup. Never commit a private signing key to this public repository. This configuration retains the current debug build convention; production distribution should use a dedicated release signing configuration.

The workflow fails before building/publishing if the secret is absent instead of silently generating an incompatible signing key. A newly created key will not fix compatibility with already installed versions signed with lost keys.

Do not uninstall an existing installation before exporting saved vocabulary. The current Excel export is a backup for viewing the words; it does not automatically restore them into the app.
