
## v1.5.11: reviewed phrases and Anki workflow

High-frequency phrasal verbs and idioms such as **keep away**, **keep up**, **come across**, **get through**, **make up**, and **put up with** are now resolved as reviewed learning entries before an online lookup. Each supplies a Korean part of speech, one to four dictionary-style senses, and natural bilingual examples.

The saved-word export menu now includes an Anki CSV with the word on the front and Korean meaning, English definition, and examples on the back. A compact Anki button on each result sends that single card to AnkiDroid through its supported text-sharing flow. Clipboard detection searches a copied English word or short phrase when the app is opened. Search input is debounced for 300 ms and the search bar expands subtly on focus. Material dynamic colors are applied on supported Android versions.

## v1.5.12: durable background export

Exports now run as a WorkManager job after Android has granted the chosen document URI. The worker reads saved entries independently, writes XLSX or Anki CSV off the UI thread, and shows a completion notification when Android permits notifications. Export data is no longer tied to the visible activity.

## v1.5.13: swipe actions

Swipe a search-result card horizontally to save it. Swipe a saved-word row horizontally to delete it; a confirmation toast is shown and the list refreshes.

## v1.5.14: offline phrase suggestions

The bundled SQLite dictionary now creates an FTS4 index over every headword, including multi-word expressions. The dictionary cache is pre-opened after launch. When a typed prefix or partial phrase has local matches, the app shows those candidates immediately and does not wait for an online lookup. The disposable dictionary cache moved to v4; saved vocabulary is unaffected.

## v1.6.0: Korean meanings for everyday words, Anki removed

**Korean meanings for inflected and misspelled words.** Basic forms such as *went, ate, studied, cities, stopped, taught, bigger, children* used to show “등록된 영한 뜻풀이가 없습니다” because only the base word is a dictionary headword. The app now resolves irregular verbs, irregular plurals and comparatives from a built-in table, and regular -s/-es/-ies, -ed/-ied, -ing and -er/-est forms with spelling rules. A base word is used only when it is a real headword in the bundled dictionary, so the result is shown instantly and offline as “[변화형] go의 과거형” followed by the base word's senses. Rows that only said “ask의 과거형” now include *ask*'s senses as well, and headwords that are also irregular forms (*saw, felt, thought*) carry a one-line note.

**Misspellings.** Online “Misspelling of …”, “Alternative spelling of …” and similar definitions now link to the correct headword's Korean meaning (e.g. *curiousity* → curiosity). When nothing is found, the app offers headwords one edit away from the query (offline) together with online suggestions, instead of only an empty message.

**Search flow.** A local meaning is shown before the network lookup and is no longer replaced by sparser online translations. Phrase candidates no longer block the online lookup. Words without any Korean meaning can't be saved to the vocabulary list.

**Reliability.** Bundled databases are copied to a temporary file and renamed only when complete, so an interrupted first launch can't leave a broken dictionary that makes every local lookup fail. Old cache copies (about 20 MB each) are deleted.

**Removed.** The Anki button, AnkiDroid sharing and the Anki CSV export; the placeholder example sentence (“I heard … in a conversation today.”), which is also stripped from previously saved words; and unused machine-translation queue code.

## v1.6.1: automatic fallback dictionary, translated examples, keyboard

**Korean meanings.** When neither the bundled dictionary nor the Wiktionary translations have a Korean meaning (rare words, phrases, names), the app now asks Google Translate's bilingual dictionary and shows numbered senses with parts of speech, e.g. `1. [명사] 호기심, 궁금증`. Phrases without a dictionary entry get a plain translation. Anything that came from this fallback is labeled **자동 번역** in the result credit and the status line, and curated dictionary records are always preferred. Headwords that are also inflected forms (*wanted, tried, freed*) now carry a note such as “want의 과거형·과거분사이기도 합니다”.

**Examples always have a Korean line.** Corpus and reviewed examples already had human translations; English sentences from the online dictionary used to be shown alone. They are now translated automatically (marked “예문 해석: 구글 번역 (자동 번역)”). If a translation can't be fetched, the entry is not cached, the status line says so, and searching again retries.

**Keyboard.** The search button and the keyboard's search key now dismiss the keyboard and move focus off the text field, so it no longer reappears when the results are drawn.

Note: the fallback and example translations send the searched word and example sentences to `translate.googleapis.com` (the unofficial `gtx` client, no key). Words found in the bundled dictionary with a complete offline entry never leave the device.

