# 영어단어장

Android vocabulary lookup and a personal wordbook for words, idioms and phrasal verbs.

## Current data and lookup

Korean meanings use reviewed entries, actual Korean lexical translations returned by FreeDictionaryAPI when available, direct English headwords from Korean Wiktionary, and a supplementary reverse index of the National Institute of Korean Language Korean Basic Dictionary. English definitions are never machine-translated into Korean dictionary meanings.

v1.5.6 adds 15,167 direct Korean-Wiktionary English headwords (6,954 new entries, 55,442 combined headwords in the tested snapshot). Direct senses have their original English part of speech when the source provides it. The previous reverse-only index missed words such as preliminary because the English equivalent was a phrase such as "being preliminary". The new direct index does not invent headwords by splitting definition text.

The build validates preliminary and 25 common words, and tests filtering, exact headwords, part-of-speech handling and Korean lexical translations. Coverage is not exhaustive and neither source guarantees every sense of every word. The app marks remaining missing entries instead of fabricating a Korean meaning. Preliminary also has separately reviewed meanings and three original bilingual usage sentences.

Up to four meanings are shown and exported. Inflected forms with explicit dictionary base information get Korean grammatical descriptions. IPA is hidden and omitted from saved entries and exports. Naver English-Korean and English-English dictionaries are optional compact external links; their contents are not collected.

Examples prefer reviewed bilingual sentences and human pairs from ManyThings/Tatoeba. Other dictionary examples use attributed Google ML Kit translations. The translation model needs a first network download; failures are shown explicitly. If no usage example is available, a sentence mentioning the expression is labelled accordingly.

## Wordbook and Excel

Saved words are retained during updates. Existing old meanings are refreshed by searching and saving the word again. Excel supports:

1. Word, Korean/English meanings, English examples with Korean translations.
2. Korean example translation in column A and English example in column B.
3. English examples only.

Every layout includes a Sources sheet with retained source credits. Excel export is a readable backup; there is no automatic Excel re-import.

## Data attribution and license

- Korean Wiktionary contributors / Kaikki.org / Wiktextract: direct English-to-Korean senses, CC BY-SA 4.0. https://kaikki.org/kowiktionary/%EC%98%81%EC%96%B4/index.html
- National Institute of Korean Language Korean Basic Dictionary: supplemental reverse index, CC BY-SA 2.0 KR. January 2024 source export is pinned to binjang/NIKL-dictionary-parser commit 52ffffdc0a6fd8d00d7dce45cffcc0930ebbdaea.
- FreeDictionaryAPI.com / English Wiktionary: English definitions, dictionary examples and available Korean lexical translations, CC BY-SA 4.0. https://freedictionaryapi.com/
- Tatoeba contributors via ManyThings.org: bilingual sentence pairs, CC BY 2.0 France. Contributor names and sentence IDs are preserved.
- Reviewed entries and bilingual sentences are separately authored for this app.

Licenses remain attached to their respective source records; combining records does not relicense the NIKL data. See app/src/main/assets/DATA_SOURCES.txt and the complete adaptation scripts in tools/. The earlier Open English-Korean Dictionary asset is no longer used.

## Build and release

Run Python 3 data generation before Gradle:

```sh
python -m unittest discover -s tools -p 'test_*.py'
python tools/build_learning_data.py
gradle :app:testDebugUnitTest :app:assembleDebug
```

The build downloads dictionary source data and human example data; generated databases are not committed. Korean-Wiktionary and Tatoeba sources are updated upstream, so counts may change in future builds. A separate installed database filename replaces the previous copied dictionary during an app update.

Ordinary GitHub pushes validate data and run tests. Commits containing [release], version tags or an explicit release dispatch build and publish an APK.

## Signing and device audio

Versions through v1.4.0 had ephemeral build-runner signing keys. v1.5.x uses the fixed private key in Actions secret ANDROID_DEBUG_KEYSTORE_BASE64 (alias androiddebugkey, store/key password android). Never commit the private key. Updates with the same key retain the wordbook.

TTS declares Android engine-discovery queries, validates English language support, prefers installed English voices, queues early taps and uses media-volume playback. Missing data, playback failures and zero media volume have user-visible guidance. Actual device sound depends on the installed engine and audio routing.
