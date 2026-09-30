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

## v1.5.0 dictionary data correction

The earlier merged dictionary is no longer bundled. The build script constructs an exact English-equivalent reverse index from the National Institute of Korean Language Korean Basic Dictionary's January 2024 export. Korean headwords and native Korean definitions are preserved; this is a reverse index of a Korean-English dictionary, not Naver data or an exhaustive English-Korean dictionary. No machine-translated English definition is displayed as a Korean dictionary meaning. Missing Korean entries are explicitly marked and a Naver English-Korean search link is provided.

The generated index contains 48,488 English headwords/expressions. 6,392 human bilingual sentence pairs from the ManyThings/Tatoeba corpus are indexed by complete word or phrase. Contributor names and sentence IDs are retained in the app, saved entries, and an additional Sources worksheet in all three Excel export formats. Corpus coverage and sense alignment are not universal. Where a human pair is unavailable, external dictionary examples use explicitly attributed automatic translations. A generic quotation example, if necessary, is labelled as a sentence mentioning the expression.

Spontaneous is separately reviewed with distinct senses and three original usage examples with natural Korean translations. The new dictionary has a separate installed filename so older copied database contents cannot survive an app update. The user's saved word database is migrated without deleting words.

Data source and licenses: app/src/main/assets/DATA_SOURCES.txt. NIKL-derived data: CC BY-SA 2.0 KR. Tatoeba sentence pairs: CC BY 2.0 France. Build script and all generated adaptations are available in this repository.

Ordinary pushes run data validation and unit tests without requiring a signing key or publishing an ephemeral-key APK. APK publication still requires the fixed signing key configured after the update-signature issue.
