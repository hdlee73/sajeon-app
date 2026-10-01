package com.hdlee73.sajeonapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class WordEntry(val id: Long = 0, val word: String, val ipa: String, val korean: String, val english: String, val examples: String, val source: String = "")
private data class LocalMeaning(val korean: String, val english: String, val ipa: String, val source: String)

private class LocalGlossary(private val context: Context) {
    private var database: SQLiteDatabase? = null
    // The saved vocabulary database (sajeon.db) is separate and never touched here.
    @Synchronized private fun open(): SQLiteDatabase? =
        database ?: AssetDatabase.open(context, "word_dictionary.sqlite", "meaning_dictionary", 5)?.also { database = it }
    fun prewarm() { open() }
    fun contains(word: String): Boolean = lookup(word) != null
    /** Dictionary headwords one edit away from a misspelled query, common entries first. */
    fun spellingCandidates(word: String): List<String> {
        val edits = Spelling.edits1(word).toList()
        if (edits.isEmpty()) return emptyList()
        val db = open() ?: return emptyList()
        val found = mutableListOf<Pair<String, String>>()
        return try {
            edits.chunked(400).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                db.rawQuery("SELECT word, source FROM words WHERE word IN ($marks)", chunk.toTypedArray()).use { c ->
                    while (c.moveToNext()) found += c.getString(0) to c.getString(1).orEmpty()
                }
            }
            Spelling.rank(word, found).take(5)
        } catch (_: Exception) { emptyList() }
    }
    fun lookup(word: String): LocalMeaning? {
        val db = open() ?: return null
        return try {
            db.rawQuery("SELECT meaning_ko, meaning_en, ipa, source FROM words WHERE word = ? COLLATE NOCASE LIMIT 1", arrayOf(word.trim())).use { c ->
                if (c.moveToFirst()) LocalMeaning(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty()) else null
            }
        } catch (_: Exception) { null }
    }
    fun suggest(query: String): List<String> {
        val terms = Regex("[a-z]+").findAll(query.lowercase(Locale.ROOT)).map { it.value + "*" }.toList()
        if (terms.isEmpty()) return emptyList()
        val db = open() ?: return emptyList()
        return try {
            db.rawQuery(
                "SELECT w.word FROM words_fts f JOIN words w ON w.rowid=f.docid WHERE words_fts MATCH ? ORDER BY length(w.word), w.word LIMIT 6",
                arrayOf(terms.joinToString(" "))
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        } catch (_: Exception) { emptyList() }
    }
}

class EntryDb(context: Context) : SQLiteOpenHelper(context, "sajeon.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL UNIQUE COLLATE NOCASE, ipa TEXT, korean TEXT, english TEXT, examples TEXT, source TEXT DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("UPDATE entries SET ipa=''")
        if (oldVersion < 3) db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT DEFAULT ''")
    }
    fun all(): List<WordEntry> {
        val out = mutableListOf<WordEntry>()
        readableDatabase.rawQuery("SELECT id,word,ipa,korean,english,examples,source FROM entries ORDER BY word COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out += WordEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6).orEmpty())
        }
        return out
    }
    fun save(original: WordEntry): Boolean {
        val e = original.studyVersion()
        val v = ContentValues().apply { put("word", e.word); put("ipa", ""); put("korean", e.korean); put("english", e.english); put("examples", e.examples); put("source", e.source) }
        return writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }
    fun updateExamples(before: WordEntry, after: WordEntry) {
        val old = before.studyVersion()
        val updated = after.studyVersion()
        val values = ContentValues().apply { put("examples", updated.examples) }
        writableDatabase.update("entries", values,
            "word=? COLLATE NOCASE AND examples=? AND korean=? AND english=?",
            arrayOf(old.word, old.examples, old.korean, old.english))
    }
    fun delete(id: Long) { writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) }
}

class MainActivity : Activity() {
    private val searchIo = Executors.newFixedThreadPool(2)
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var suppressDebouncedSearch = false
    private var lastClipboardText = ""
    private val debouncedSearch = Runnable {
        if (!suppressDebouncedSearch) {
            val query = searchInput.text.toString().trim()
            if (query.length >= 2) lookup(query)
        }
    }
    private var searchTask: java.util.concurrent.Future<*>? = null
    private val requestContext = ThreadLocal<Int>()
    private val connections = java.util.concurrent.ConcurrentHashMap<Int, HttpURLConnection>()
    private val io = Executors.newSingleThreadExecutor()
    private val translateIo = Executors.newFixedThreadPool(3)
    private val translationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lookupSequence = AtomicInteger(0)
    private val resultCache = mutableMapOf<String, WordEntry>()
    private lateinit var exampleCorpus: BilingualExamples
    private lateinit var glossary: LocalGlossary
    private lateinit var db: EntryDb
    private lateinit var screenRoot: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchInput: EditText
    private lateinit var wordSpeaker: WordSpeaker
    private lateinit var searchTab: Button
    private lateinit var savedTab: Button
    private var current: WordEntry? = null
    private var showSaved = false
    private var exportFormat = 1
    private var returnToPdf = false
    private lateinit var returnButton: Button
    private var blue = 0xff486a90.toInt()
    private var dark = 0xff182230.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        blue = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, blue)
        dark = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, dark)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        db = EntryDb(this)
        glossary = LocalGlossary(this)
        exampleCorpus = BilingualExamples(this)
        searchIo.execute { glossary.prewarm() }
        returnToPdf = intent?.getBooleanExtra("return_to_pdf", false) == true
        buildUi()
        wordSpeaker = WordSpeaker(this) { toast(it) }
        handleIncomingSearch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingSearch(intent)
    }

    private fun handleIncomingSearch(intent: Intent?) {
        returnToPdf = intent?.getBooleanExtra("return_to_pdf", false) == true
        if (::returnButton.isInitialized) returnButton.visibility = if (returnToPdf) View.VISIBLE else View.GONE
        val query = intent?.getStringExtra("query")?.trim().orEmpty()
        if (query.isNotEmpty()) {
            showSaved = false
            setSearchTextWithoutDebounce(query)
            lookup(query)
        } else {
            renderSaved()
        }
    }

    override fun onResume() {
        super.onResume()
        detectClipboardSearch()
    }

    private fun setSearchTextWithoutDebounce(query: String) {
        suppressDebouncedSearch = true
        searchInput.setText(query)
        searchInput.setSelection(query.length)
        suppressDebouncedSearch = false
    }

    private fun detectClipboardSearch() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        // Only a copied word, phrasal verb, or short idiom is used; sentences are ignored.
        if (text == lastClipboardText || !text.matches(Regex("[A-Za-z][A-Za-z '\\-]{0,79}"))) return
        if (text.split(Regex("\\s+")).size > 6) return
        lastClipboardText = text
        setSearchTextWithoutDebounce(text)
        lookup(text)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 12.dp()); setBackgroundColor(0xfff3f6fb.toInt()); isFocusable = true; isFocusableInTouchMode = true }
        screenRoot = root
        setContentView(root)
        // Android 15+ draws edge-to-edge for apps targeting API 35. Keep the app
        // content below the status bar and above the navigation/gesture area.
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom
            view.setPadding(20.dp(), 16.dp() + topInset, 20.dp(), 12.dp() + bottomInset)
            insets
        }
        returnButton = button("← PDF로 돌아가기").apply { visibility = if (returnToPdf) View.VISIBLE else View.GONE; setOnClickListener { finish() } }
        root.addView(returnButton, LinearLayout.LayoutParams(-1, 46.dp()).apply { bottomMargin = 10.dp() })
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 12.dp(), 18.dp(), 12.dp()); background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xffe3edf5.toInt(), 0xffeeeaf5.toInt())).apply { cornerRadius = 22.dp().toFloat() } }
        hero.addView(label("영어단어장", 23, true, dark))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp() })
        val searchCard = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp()); background = rounded(0xffffffff.toInt(), 17) }
        searchInput = EditText(this).apply { hint = "단어, 숙어 또는 구동사"; setSingleLine(true); textSize = 16f; setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp()); background = android.graphics.drawable.ColorDrawable(0x00000000); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        searchInput.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchNow(); true } else false }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debounceHandler.removeCallbacks(debouncedSearch)
                if (!suppressDebouncedSearch) debounceHandler.postDelayed(debouncedSearch, 300)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        searchInput.setOnFocusChangeListener { _, focused ->
            searchCard.animate().scaleX(if (focused) 1.015f else 1f).scaleY(if (focused) 1.015f else 1f).setDuration(160).start()
        }
        searchCard.addView(searchInput, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        searchCard.addView(button("검색", 0xff567590.toInt(), 0xffffffff.toInt()).apply { setOnClickListener { searchNow() } }, LinearLayout.LayoutParams(84.dp(), 50.dp()))
        root.addView(searchCard)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.dp(), 0, 12.dp()) }
        searchTab = button("검색 결과").apply { setOnClickListener { showSaved = false; showPage() } }
        savedTab = button("저장 단어").apply { setOnClickListener { showSaved = true; renderSaved(); showPage() } }
        tabs.addView(searchTab, LinearLayout.LayoutParams(0, 40.dp(), 1f)); tabs.addView(savedTab, LinearLayout.LayoutParams(0, 40.dp(), 1f).apply { leftMargin = 8.dp() })
        root.addView(tabs)
        updateTabs()
        status = label("검색어를 입력하면 뜻과 예문을 찾아드립니다.", 14, false, 0xff5d6877.toInt()).apply { setPadding(2.dp(), 0, 2.dp(), 4.dp()) }
        root.addView(status)
        val scroll = ScrollView(this).apply { setFillViewport(true) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox = content
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun searchNow() {
        debounceHandler.removeCallbacks(debouncedSearch)
        val q = searchInput.text.toString().trim()
        hideKeyboard()
        if (q.isNotEmpty()) lookup(q) else toast("검색어를 입력해 주세요")
    }

    /**
     * Hides the keyboard and moves focus off the search field. Clearing focus alone lets Android
     * hand focus straight back to the only focusable view (the field), which brought the keyboard
     * back when the results were drawn, so focus is parked on the screen root instead.
     */
    private fun hideKeyboard() {
        val token = (currentFocus ?: window.decorView).windowToken
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(token, 0)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        searchInput.clearFocus()
        if (::screenRoot.isInitialized) screenRoot.requestFocus()
    }

    private fun showPage() { updateTabs(); if (showSaved) renderSaved() else { resultBox.removeAllViews(); current?.let { showEntry(it, true) } } }

    private data class OnlineResult(val english: String, val korean: String, val examples: List<String>, val allDefinitions: String)

    private fun lookup(q: String) {
        debounceHandler.removeCallbacks(debouncedSearch)
        val requestId = lookupSequence.incrementAndGet()
        searchTask?.cancel(true)
        // Disconnect old requests away from the UI thread, including blocked reads.
        connections.entries.filter { it.key != requestId }.forEach { (id, connection) ->
            io.execute { connection.disconnect(); connections.remove(id, connection) }
        }
        status.text = "‘$q’ 검색 중…"
        resultBox.removeAllViews()
        showSaved = false
        updateTabs()
        current = null
        val cached = ReviewedEntries.lookup(q) ?: synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] }
        if (cached != null) {
            current = cached
            status.text = "검색 결과"
            showEntry(cached, true)
            return
        }
        searchTask = searchIo.submit {
            requestContext.set(requestId)
            fun stale() = requestId != lookupSequence.get() || Thread.currentThread().isInterrupted
            fun present(entry: WordEntry?, text: String, canSave: Boolean = true,
                        suggestions: List<String> = emptyList(), suggestionTitle: String = "혹시 이 단어인가요?") = runOnUiThread {
                if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                if (entry != null) current = entry
                if (showSaved) return@runOnUiThread
                status.text = text
                resultBox.removeAllViews()
                entry?.let { showEntry(it, canSave) }
                if (suggestions.isNotEmpty()) showSuggestions(suggestions, suggestionTitle)
                if (!searchInput.hasFocus()) hideKeyboard()
            }

            // 1) Offline: exact headword, or the base word of an inflected form (went → go).
            val local = glossary.lookup(q)
            val baseForm = if (local == null) WordForms.find(q) { glossary.contains(it) } else null
            val baseLocal = baseForm?.let { glossary.lookup(it.base) }
            val alsoForm = if (local != null) WordForms.irregular(q)?.let { form -> glossary.lookup(form.base)?.let { form to it } } else null
            // Rows such as "asked: ask의 과거형" get the base word's real senses underneath.
            val pointer = local?.let { WordForms.pointerBase(it.korean) }?.let { base -> glossary.lookup(base) }
            val offlineKorean = when {
                local != null && pointer != null -> local.korean.lines().filter { it.isNotBlank() }
                    .joinToString("\n") { "[변화형] " + it.replace(Regex("^\\s*\\d+[.)]\\s*"), "") } + "\n" + StudyMeanings.limit(pointer.korean)
                local != null -> (alsoForm?.let { (form, base) -> form.note(base.korean) + "\n" } ?: "") + local.korean
                baseForm != null && baseLocal != null -> baseForm.korean(baseLocal.korean)
                else -> null
            }
            val offlineCredit = when {
                local != null -> localMeaningCredit(local, q)
                baseLocal != null -> localMeaningCredit(baseLocal, baseForm!!.base)
                else -> ""
            }
            val humanExamples = exampleCorpus.lookup(q)
            val humanExampleText = humanExamples.joinToString("\n") { "${it.english}\t${it.korean}" }
            val humanCredit = if (humanExamples.isEmpty()) "" else humanExamples.joinToString("\n") { it.credit } + "\n문장 모음: ManyThings / Tatoeba"
            val phraseCandidates = if (offlineKorean == null) glossary.suggest(q).filter { !it.equals(q, true) } else emptyList()
            if (stale()) return@submit

            if (offlineKorean != null) {
                val englishLocal = local?.english.orEmpty()
                val initial = WordEntry(word = q, ipa = "", korean = offlineKorean,
                    english = englishLocal.ifBlank { "영어 풀이를 불러오는 중…" }, examples = humanExampleText,
                    source = listOf(offlineCredit, humanCredit).filter { it.isNotBlank() }.joinToString("\n"))
                if (englishLocal.isNotBlank() && humanExamples.isNotEmpty()) {
                    // Complete offline entry: no network needed.
                    synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = initial }
                    present(initial, "검색 결과 · 기기 내 사전")
                    return@submit
                }
                present(initial, "한글 뜻 표시됨 · 영어 풀이와 예문을 불러오는 중…", canSave = false)
            } else if (phraseCandidates.isNotEmpty()) {
                present(null, "기기 내 사전 후보를 표시했습니다 · 온라인 사전 확인 중…",
                    suggestions = phraseCandidates, suggestionTitle = "이 표현을 찾으세요?")
            }

            // 2) Online: English definitions, examples and (if still needed) a Korean meaning.
            val online = try { fetchDictionary(q) } catch (e: Exception) {
                if (stale()) return@submit
                if (offlineKorean != null) {
                    val englishLocal = local?.english.orEmpty()
                    present(WordEntry(word = q, ipa = "", korean = offlineKorean,
                        english = englishLocal.ifBlank { "영어 풀이를 불러오지 못했습니다." }, examples = humanExampleText,
                        source = listOf(offlineCredit, humanCredit).filter { it.isNotBlank() }.joinToString("\n")),
                        "기기 내 사전 뜻을 표시했습니다. 영어 풀이는 인터넷 연결 후 다시 검색해 주세요.")
                } else {
                    // Not in the English dictionary API either (phrases, names, rare words): try the
                    // automatic dictionary before giving up.
                    val auto = autoMeaning(q, requestId)
                    if (stale()) return@submit
                    if (auto != null) {
                        val entry = WordEntry(word = q, ipa = "", korean = auto.text, english = "", examples = humanExampleText,
                            source = listOf(MachineTranslation.CREDIT_MEANING, humanCredit).filter { it.isNotBlank() }.joinToString("\n"))
                        synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = entry }
                        present(entry, "검색 결과 · 한글 뜻은 자동 번역입니다")
                        return@submit
                    }
                    val suggestions = (phraseCandidates + glossary.spellingCandidates(q) +
                        (try { fetchSuggestions(q) } catch (_: Exception) { emptyList() })).distinct().take(6)
                    present(null, if (suggestions.isNotEmpty()) "‘$q’에 대한 결과가 없습니다. 철자를 확인하거나 아래 단어를 선택해 보세요."
                        else lookupErrorMessage(e), suggestions = suggestions)
                }
                return@submit
            }
            if (stale()) return@submit

            // Korean meanings come from dictionary records first. Only when no curated dictionary
            // has an answer is a clearly labeled automatic translation used.
            val formOf = InflectedForms.find(online.allDefinitions)?.takeIf { !it.base.equals(q, true) }
            val formBaseKorean = formOf?.let { ReviewedEntries.lookup(it.base)?.korean ?: glossary.lookup(it.base)?.korean }
            val formBaseLocal = formOf?.let { glossary.lookup(it.base) }
            var usedAutoMeaning = false
            val korean: String? = when {
                offlineKorean != null -> {
                    // "wanted" is a headword (adjective) and also the past tense of "want".
                    val note = if (local != null && pointer == null && alsoForm == null && formOf != null && formBaseKorean != null)
                        BaseForm(formOf.base, formOf.form).note(formBaseKorean) + "\n" else ""
                    note + offlineKorean
                }
                formOf != null -> {
                    val baseGloss = formBaseKorean ?: online.korean.ifBlank { null }
                        ?: autoMeaning(formOf.base, requestId)?.also { usedAutoMeaning = true }?.text
                    formOf.korean(baseGloss)
                }
                online.korean.isNotBlank() -> online.korean
                else -> autoMeaning(q, requestId)?.also { usedAutoMeaning = true }?.text
            }
            if (stale()) return@submit
            val spelling = if (korean == null) (glossary.spellingCandidates(q) + phraseCandidates).distinct().take(5) else emptyList()
            val english = local?.english?.ifBlank { null } ?: online.english

            // Every example gets a Korean line: corpus pairs are human translations, online English
            // sentences are translated automatically.
            var machineExamples = false
            var missingTranslations = 0
            val examples = if (humanExamples.isNotEmpty()) humanExampleText else {
                val translated = translateSentences(online.examples, requestId)
                online.examples.zip(translated).joinToString("\n") { (sentence, ko) ->
                    if (ko == null) missingTranslations++ else machineExamples = true
                    "$sentence\t${ko.orEmpty()}"
                }
            }
            if (stale()) return@submit
            val koreanCredit = when {
                offlineKorean != null -> offlineCredit
                formOf != null && formBaseLocal != null -> localMeaningCredit(formBaseLocal, formOf.base)
                usedAutoMeaning -> MachineTranslation.CREDIT_MEANING
                online.korean.isNotBlank() -> "한글 의미: Wiktionary 한국어 어휘 번역 (CC BY-SA 4.0)"
                else -> ""
            }
            val entry = WordEntry(word = q, ipa = "", examples = examples,
                korean = korean ?: "기기 내 사전과 온라인 사전에 한글 뜻풀이가 없습니다." +
                    (if (spelling.isNotEmpty()) " 철자를 확인하거나 아래 추천 단어를 눌러 보세요." else " 아래 네이버 사전에서 확인해 주세요."),
                english = english,
                source = listOf(koreanCredit,
                    if (local?.english.isNullOrBlank()) "영영 풀이: FreeDictionaryAPI / Wiktionary (CC BY-SA 4.0)" else "",
                    humanCredit.ifBlank { if (online.examples.isNotEmpty()) "영어 예문: FreeDictionaryAPI / Wiktionary" else "" },
                    if (machineExamples) MachineTranslation.CREDIT_EXAMPLES else "")
                    .filter { it.isNotBlank() }.joinToString("\n"))
            // An entry with a missing example translation is not cached, so searching again retries.
            if (korean != null && missingTranslations == 0) synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = entry }
            present(entry, when {
                korean == null -> "한글 뜻풀이를 찾지 못했습니다"
                missingTranslations > 0 -> "검색 결과 · 예문 해석을 불러오지 못했습니다. 다시 검색하면 해석이 추가됩니다."
                usedAutoMeaning || machineExamples -> "검색 결과 · 일부 자동 번역 포함"
                else -> "검색 결과"
            }, canSave = korean != null, suggestions = spelling)
        }
    }

    /** Automatic dictionary/translation lookup; null when offline, rate limited or not Korean. */
    private fun autoMeaning(word: String, requestId: Int): MachineMeaning? = try {
        requestContext.set(requestId)
        MachineTranslation.meaning(http(MachineTranslation.url(word, true), 2500, 3500), word)
    } catch (_: Exception) { null }

    /** Translates sentences in parallel; a failed sentence is null. Results are cached per session. */
    private fun translateSentences(sentences: List<String>, requestId: Int): List<String?> {
        val futures = sentences.map { sentence ->
            if (translationCache.containsKey(sentence)) null
            else translateIo.submit<String?> {
                try {
                    requestContext.set(requestId)
                    MachineTranslation.sentence(http(MachineTranslation.url(sentence, false), 2500, 3500), sentence)
                        ?.also { translationCache[sentence] = it }
                } catch (_: Exception) { null }
            }
        }
        return sentences.mapIndexed { i, sentence ->
            translationCache[sentence] ?: try { futures[i]?.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: Exception) { futures[i]?.cancel(true); null }
        }
    }

    private fun localMeaningCredit(local: LocalMeaning?, word: String): String = when (local?.source) {
        "KOWIKTIONARY" -> "한국어 위키낱말사전 / Kaikki.org 영한 표제어 (CC BY-SA 4.0)\nhttps://ko.wiktionary.org/wiki/" + Uri.encode(word)
        "NIKL" -> "국립국어원 한국어기초사전 영어 대역의 역색인 (CC BY-SA 2.0 KR)"
        else -> "Wiktionary 사전 원형·뜻풀이 / 자체 검토 자료"
    }

    private fun lookupErrorMessage(error: Exception): String {
        val details = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return when {
            "unknownhost" in details || "unable to resolve host" in details -> "인터넷 주소에 연결하지 못했습니다. 모바일 데이터나 Wi‑Fi 연결을 확인해 주세요."
            "timeout" in details || "timed out" in details -> "사전 서버 응답이 늦습니다. 잠시 후 다시 검색해 주세요."
            "http 404" in details || "not found" in details -> "단어를 찾지 못했습니다. 철자를 확인하거나 다른 표현으로 검색해 주세요."
            "ssl" in details || "certificate" in details -> "보안 연결에 실패했습니다. 기기의 날짜·시간과 네트워크 설정을 확인해 주세요."
            else -> "검색에 실패했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요. (${error.message?.take(100) ?: "연결 오류"})"
        }
    }

    private fun fetchSuggestions(q: String): List<String> {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val response = JSONArray(http("https://api.datamuse.com/sug?s=$encoded&max=6", 2000, 2500))
        val out = linkedSetOf<String>()
        for (i in 0 until response.length()) {
            val word = response.optJSONObject(i)?.optString("word", "")?.trim().orEmpty()
            if (word.isNotBlank() && !word.equals(q, ignoreCase = true)) out += word
        }
        return out.take(5)
    }

    private fun showSuggestions(words: List<String>, title: String = "혹시 이 단어인가요?") {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 16.dp(), 18.dp(), 16.dp()); background = rounded(0xffffffff.toInt(), 18) }
        card.addView(label(title, 18, true, dark).apply { setPadding(0, 0, 0, 8.dp()) })
        words.forEach { candidate ->
            val pick = button("$candidate   ›").apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { setSearchTextWithoutDebounce(candidate); hideKeyboard(); lookup(candidate) }
            }
            card.addView(pick, LinearLayout.LayoutParams(-1, 44.dp()).apply { topMargin = 5.dp() })
        }
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp() })
    }

    private fun fetchDictionary(q: String): OnlineResult {
        return try {
            fetchOpenDictionary(q)
        } catch (primaryError: Exception) {
            try { fetchLegacyDictionary(q) } catch (fallbackError: Exception) {
                throw IllegalStateException(
                    "FreeDictionaryAPI: ${primaryError.message ?: primaryError.javaClass.simpleName}; " +
                        "Dictionary API: ${fallbackError.message ?: fallbackError.javaClass.simpleName}", primaryError
                )
            }
        }
    }

    private fun fetchOpenDictionary(q: String): OnlineResult {
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val root = JSONObject(http("https://freedictionaryapi.com/api/v1/entries/en/$encoded?translations=true", 3500, 5000))
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val allSenses = mutableListOf<JSONObject>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val senses = entry.optJSONArray("senses") ?: JSONArray()
            for (j in 0 until senses.length()) senses.optJSONObject(j)?.let { sense ->
                sense.put("_partOfSpeech", entry.optString("partOfSpeech"))
                allSenses += sense
            }
        }
        val allDefinitions = allSenses.mapNotNull { it.optString("definition").trim().takeIf(String::isNotBlank) }.distinct()
        if (allDefinitions.isEmpty()) throw IllegalStateException("사전 결과가 없습니다")
        val samples = allSenses.flatMap { sense ->
            val examples = sense.optJSONArray("examples") ?: JSONArray()
            (0 until examples.length()).mapNotNull { examples.optString(it).trim().takeIf(String::isNotBlank) }
        }.filter { SentenceExamples.isSentence(it) }.distinct().take(3)
        val korean = KoreanLexicalMeanings.format(allSenses.map { sense ->
            val translations = sense.optJSONArray("translations") ?: JSONArray()
            val words = (0 until translations.length()).mapNotNull { index ->
                val translation = translations.optJSONObject(index) ?: return@mapNotNull null
                val code = translation.optJSONObject("language")?.optString("code").orEmpty()
                if (code == "ko" || code == "kor") translation.optString("word").takeIf { it.isNotBlank() } else null
            }
            KoreanDictionarySense(sense.optString("_partOfSpeech"), words)
        })
        return OnlineResult(numbered(allDefinitions.take(4)), korean, samples, allDefinitions.joinToString("\n"))
    }

    private fun fetchLegacyDictionary(q: String): OnlineResult {
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val root = JSONArray(http("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded", 2500, 4000))
        val json = root.optJSONObject(0) ?: throw IllegalStateException("사전 결과가 없습니다")
        val meanings = json.optJSONArray("meanings") ?: JSONArray()
        val definitions = linkedSetOf<String>(); val samples = linkedSetOf<String>()
        for (i in 0 until meanings.length()) {
            val defs = meanings.optJSONObject(i)?.optJSONArray("definitions") ?: continue
            for (j in 0 until defs.length()) {
                val item = defs.optJSONObject(j) ?: continue
                item.optString("definition").takeIf(String::isNotBlank)?.let(definitions::add)
                item.optString("example").takeIf { SentenceExamples.isSentence(it) }?.let(samples::add)
            }
        }
        if (definitions.isEmpty()) throw IllegalStateException("정의를 찾지 못했습니다")
        return OnlineResult(numbered(definitions.take(4)), "", samples.take(3), definitions.joinToString("\n"))
    }

    private fun numbered(lines: List<String>) = lines.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")

    private fun http(address: String, connectTimeout: Int = 3500, readTimeout: Int = 5000): String {
        val requestId = requestContext.get()
        if (Thread.currentThread().isInterrupted || (requestId != null && requestId != lookupSequence.get())) throw java.io.InterruptedIOException("Search cancelled")
        val c = URL(address).openConnection() as HttpURLConnection
        if (requestId != null) connections[requestId] = c
        c.requestMethod = "GET"; c.connectTimeout = connectTimeout; c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", "SajeonApp/1.0 (Android)")
        return try { val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream; val body = stream.bufferedReader().use { it.readText() }; if (code !in 200..299) throw IllegalStateException("HTTP $code"); body } finally { if (requestId != null) connections.remove(requestId, c); c.disconnect() }
    }

    private fun showEntry(original: WordEntry, canSave: Boolean) {
        val e = original.studyVersion()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp()); background = rounded(0xffffffff.toInt(), 18) }
        val wordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wordRow.addView(label(e.word, 25, true, dark), LinearLayout.LayoutParams(0, -2, 1f))
        wordRow.addView(button("🔊 듣기", 0xffeee9f7.toInt(), 0xff655880.toInt()).apply {
            textSize = 12f
            setOnClickListener { speak(e.word) }
        }, LinearLayout.LayoutParams(78.dp(), 40.dp()))
        if (canSave) {
            val saveButton = button("🔖 저장", 0xffdff0e7.toInt(), 0xff386752.toInt()).apply {
                textSize = 12f
                setOnClickListener {
                    if (db.save(e)) { toast("저장했습니다"); isEnabled = false; text = "✓ 저장" }
                    else toast("저장하지 못했습니다. 다시 시도해 주세요.")
                }
            }
            wordRow.addView(saveButton, LinearLayout.LayoutParams(72.dp(), 40.dp()).apply { leftMargin = 6.dp() })
        }
        card.addView(wordRow)
        section(card, "한글 의미", e.korean)
        val dictionaryLinks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dictionaryLinks.addView(label("네이버 ", 12, false, 0xff64748b.toInt()))
        fun addDictionaryLink(title: String, address: String) {
            dictionaryLinks.addView(label(title, 12, false, blue).apply {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(4.dp(), 8.dp(), 4.dp(), 8.dp())
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address + Uri.encode(e.word)))) }
            })
        }
        addDictionaryLink("영한", "https://en.dict.naver.com/#/search?query=")
        dictionaryLinks.addView(label(" · ", 12, false, 0xff64748b.toInt()))
        addDictionaryLink("영영", "https://dict.naver.com/enendict/#/search?query=")
        card.addView(dictionaryLinks)
        if (e.english.isNotBlank()) section(card, "English definition", e.english)
        val pairs = examplePairs(e.examples)
        if (pairs.isNotEmpty()) {
            section(card, if (pairs.any { it.second.isNotBlank() }) "예문 · 한국어 해석" else "영어 예문", displayExamples(e.examples))
        }
        val credit = label(e.source.ifBlank { "의미별 자체 정리 · 직접 작성한 한영 예문" }, 10, false, 0xff64748b.toInt()).apply {
            setPadding(0, 12.dp(), 0, 0)
        }
        card.addView(credit)
        if (canSave) {
            installHorizontalSwipe(card) {
                if (db.save(e)) toast("단어장에 저장했습니다")
                else toast("저장하지 못했습니다. 다시 시도해 주세요.")
            }
        }
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp(); bottomMargin = 12.dp() })
    }

    private fun speak(text: String) { wordSpeaker.speak(text) }

    private fun updateTabs() {
        if (!::searchTab.isInitialized || !::savedTab.isInitialized) return
        fun style(tab: Button, selected: Boolean) {
            tab.background = rounded(if (selected) 0xffdce8f2.toInt() else 0xffedf0f4.toInt(), 12)
            tab.setTextColor(if (selected) blue else 0xff667384.toInt())
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.isSelected = selected
        }
        style(searchTab, !showSaved)
        style(savedTab, showSaved)
    }

    private fun renderSaved() {
        if (!showSaved) return
        updateTabs()
        resultBox.removeAllViews()
        val entries = db.all().map { it.studyVersion() }
        if (entries.isEmpty()) { resultBox.addView(label("아직 저장한 단어가 없습니다. 검색 결과에서 원하는 단어만 저장할 수 있어요.", 15, false, 0xff5d6877.toInt()).apply { setPadding(4.dp(), 14.dp(), 4.dp(), 14.dp()) }); return }
        resultBox.addView(button("↗ 엑셀 내보내기", 0xffdff0e7.toInt(), 0xff386752.toInt()).apply { setOnClickListener { createXlsx() } }, LinearLayout.LayoutParams(-1, 48.dp()).apply { bottomMargin = 12.dp() })
        entries.forEach { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14.dp(), 12.dp(), 8.dp(), 12.dp()); background = rounded(0xffffffff.toInt(), 14) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(e.word, 18, true, dark))
            info.addView(label(e.korean, 14, false, 0xff526174.toInt()).apply { maxLines = 2 })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val openEntry = {
                AlertDialog.Builder(this@MainActivity).setTitle(e.word)
                    .setMessage("한글 의미\n" + e.korean + (if (e.english.isBlank()) "" else "\n\nEnglish definition\n" + e.english) +
                        displayExamples(e.examples).let { if (it.isBlank()) "" else "\n\n예문\n$it" })
                    .setNeutralButton("🔊 듣기") { _, _ -> speak(e.word) }
                    .setPositiveButton("닫기", null).show()
            }
            info.setOnClickListener { openEntry() }
            row.addView(button("보기").apply { textSize = 12f; setOnClickListener { openEntry() } },
                LinearLayout.LayoutParams(56.dp(), 40.dp()))
            row.addView(button("⋯").apply {
                textSize = 22f
                contentDescription = "단어 메뉴"
                setOnClickListener { anchor ->
                    PopupMenu(this@MainActivity, anchor).apply {
                        menu.add("다시 검색").setOnMenuItemClickListener { searchInput.setText(e.word); lookup(e.word); true }
                        menu.add("삭제").setOnMenuItemClickListener { db.delete(e.id); renderSaved(); true }
                        show()
                    }
                }
            }, LinearLayout.LayoutParams(40.dp(), 40.dp()).apply { leftMargin = 4.dp() })
            installHorizontalSwipe(row) {
                db.delete(e.id)
                toast("삭제했습니다")
                renderSaved()
            }
            resultBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() })
        }
    }

    private fun createXlsx() {
        val entries = db.all()
        if (entries.isEmpty()) { toast("내보낼 저장 단어가 없습니다"); return }
        val choices = arrayOf("단어·뜻·영어 예문(한글 해석 병기)", "한글 예문 해석 + 영어 예문", "영어 예문만")
        AlertDialog.Builder(this).setTitle("내보내기 형식").setItems(choices) { _, which ->
            exportFormat = which + 1
            val name = when (which) {
                1 -> "영어예문_한영.xlsx"
                2 -> "영어예문.xlsx"
                else -> "영어단어장.xlsx"
            }
            val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
            }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, name)
            }
            startActivityForResult(intent, 42)
        }.setNegativeButton("취소", null).show()
    }

    @Deprecated("Activity result API kept compatible with platform-only project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) { }
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(ExportWorker.KEY_URI to uri.toString(), ExportWorker.KEY_FORMAT to exportFormat))
                .build()
            WorkManager.getInstance(this).enqueue(request)
            toast("파일 저장을 백그라운드에서 시작했습니다.")
        }
    }

    private fun installHorizontalSwipe(view: View, action: () -> Unit) {
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 96.dp() && abs(dx) > abs(dy) * 1.5f) {
                        action()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun section(parent: LinearLayout, title: String, value: String) { parent.addView(label(title, 14, true, blue).apply { setPadding(0, 10.dp(), 0, 3.dp()) }); parent.addView(label(value, 16, false, dark)) }
    private fun label(text: String, size: Int, bold: Boolean, color: Int) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setLineSpacing(2.dp().toFloat(), 1f) }
    private fun button(text: String, fill: Int = 0xffedf1f6.toInt(), ink: Int = blue) = Button(this).apply { this.text = text; textSize = 14f; isAllCaps = false; setTextColor(ink); background = rounded(fill, 12); setPadding(10.dp(), 0, 10.dp(), 0); minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; stateListAnimator = null }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.dp().toFloat() }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        debounceHandler.removeCallbacksAndMessages(null)
        lookupSequence.incrementAndGet()
        if (::wordSpeaker.isInitialized) wordSpeaker.close()
        searchTask?.cancel(true)
        searchIo.shutdownNow()
        translateIo.shutdownNow()
        connections.values.forEach { connection -> io.execute { connection.disconnect() } }
        io.shutdown()
        super.onDestroy()
    }
}

