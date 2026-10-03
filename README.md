
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

## v1.6.2: fuller Korean meanings, dictionary refreshes on update

**Why meanings were incomplete (e.g. *palm* showed only “[동사] 손 안에 감추다”).** Korean Wiktionary only supplies senses that have a Korean gloss, so a whole part of speech could be missing (*palm, date, match* lost their noun senses), and the build then overwrote the National Institute of Korean Language (NIKL) entry for the same word instead of combining them. NIKL senses were also cut after the first six in Korean alphabetical order, which dropped common words (달리다 for *run*).

- The build now **merges** both dictionaries: Wiktionary senses first, then NIKL senses only for parts of speech Wiktionary lacks. NIKL senses are ranked by the dictionary's learner level (초급 → 중급 → 고급) with one sense per Korean headword.
- English explanations that Wiktionary glosses carry in parentheses (“(To move from one place to another.)”) are removed.
- In the app, Korean words from the online Wiktionary translations that the list does not have yet are added, and a part of speech of the English entry that is still missing is filled from the automatic dictionary (labeled 자동 번역). Senses follow the English entry's order (e.g. noun before verb), and up to six are shown instead of four.
- The bundled dictionary cache is now named after the installed app version, so **installing an update replaces the dictionary automatically**; old cached copies are deleted.

The app still cannot tell which sense an example sentence uses; it lists the senses and leaves that choice to the reader.


## v1.6.3: fixed update signing, meanings and examples that match
- **Update install fix.** Earlier releases were each signed with a freshly generated key, so Android refused to install one over another ("App not installed"). The build now passes the permanent keystore to Gradle explicitly and CI fails the release if the APK's signing certificate differs from that keystore. From this version on, updates install over the previous version. The one-time exception: moving from a pre-1.6.3 install to 1.6.3 still needs the old app removed first (export saved words to Excel beforehand).
- **Main sense in the meaning list.** For headwords the automatic dictionary is asked in parallel and its top words for the entry's main part of speech are added when missing (palm → 야자나무).
- **Examples fit the meanings.** Up to 30 corpus sentences are considered; the ones whose Korean translation uses a word of the first-listed sense come first, then another listed sense.

## v1.6.4: meanings follow the example's sense
- When a shown example uses a compound the dictionary knows (Tom is planting a palm **tree** → `palm tree` = 야자나무) and the Korean word appears in the example's translation, that sense is added to the meaning list, so the list and the example always agree.

## v1.6.5: simpler examples, pronunciation, sorting saved words
- **Simple examples.** Online example sentences must now be a single plain line (3–16 words, ≤90 characters) without bracketed quotation fragments, verse slashes, old letters or archaic words, so old-book quotations no longer show up. Corpus sentences use the same rule.
- **Pronunciation.** The IPA transcription from the online dictionary is shown under the word, saved with the word and shown in the saved list.
- **Sorting saved words.** A sort button in the saved tab offers A→Z, Z→A, newest and oldest first. The choice is remembered and also used for the Excel export.

## v1.6.6: Korean explanation for every English definition
- Naver's dictionary cannot be used inside the app: it has no public dictionary API, its content is licensed to Naver, and scraping it would violate its terms. The Naver 영한/영영 links on every result stay for checking.
- Every English definition (up to four) is now followed by its Korean translation (automatic, labeled), so senses missing from the word lists (rip off = cheat, steal) are still explained in Korean.
- The Wiktionary / automatic-dictionary supplement now also applies to redirected spellings and variants (rip off → rip-off).
