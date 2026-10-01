
## v1.5.11: reviewed phrases and Anki workflow

High-frequency phrasal verbs and idioms such as **keep away**, **keep up**, **come across**, **get through**, **make up**, and **put up with** are now resolved as reviewed learning entries before an online lookup. Each supplies a Korean part of speech, one to four dictionary-style senses, and natural bilingual examples.

The saved-word export menu now includes an Anki CSV with the word on the front and Korean meaning, English definition, and examples on the back. A compact Anki button on each result sends that single card to AnkiDroid through its supported text-sharing flow. Clipboard detection searches a copied English word or short phrase when the app is opened. Search input is debounced for 300 ms and the search bar expands subtly on focus. Material dynamic colors are applied on supported Android versions.

## v1.5.12: durable background export

Exports now run as a WorkManager job after Android has granted the chosen document URI. The worker reads saved entries independently, writes XLSX or Anki CSV off the UI thread, and shows a completion notification when Android permits notifications. Export data is no longer tied to the visible activity.
