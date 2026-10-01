package com.hdlee73.sajeonapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
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
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WordEntry(val id: Long = 0, val word: String, val ipa: String, val korean: String, val english: String, val examples: String, val source: String = "")
private data class LocalMeaning(val korean: String, val english: String, val ipa: String, val source: String)

private class LocalGlossary(private val activity: Activity) {
    private var database: SQLiteDatabase? = null
    @Synchronized private fun open(): SQLiteDatabase? {
        database?.let { return it }
        return try {
            val file = activity.getDatabasePath("meaning_dictionary_combined_v3.sqlite")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                activity.assets.open("word_dictionary.sqlite").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
        } catch (_: Exception) { null }
    }
    fun lookup(word: String): LocalMeaning? {
        val db = open() ?: return null
        return try {
            db.rawQuery("SELECT meaning_ko, meaning_en, ipa, source FROM words WHERE word = ? COLLATE NOCASE LIMIT 1", arrayOf(word.trim())).use { c ->
                if (c.moveToFirst()) LocalMeaning(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty()) else null
            }
        } catch (_: Exception) { null }
    }
}

class EntryDb(context: Activity) : SQLiteOpenHelper(context, "sajeon.db", null, 3) {
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
    private val lookupSequence = AtomicInteger(0)
    private val resultCache = mutableMapOf<String, WordEntry>()
    private lateinit var exampleCorpus: BilingualExamples
    private lateinit var glossary: LocalGlossary
    private lateinit var db: EntryDb
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 16.dp(), 20.dp(), 12.dp()); setBackgroundColor(0xfff3f6fb.toInt()) }
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
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        if (q.isNotEmpty()) lookup(q) else toast("검색어를 입력해 주세요")
    }

    private fun showPage() { updateTabs(); if (showSaved) renderSaved() else { resultBox.removeAllViews(); current?.let { showEntry(it, true) } } }

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
            status.text = "검색 결과 · 저장된 검색"
            showEntry(cached, true)
            return
        }
        searchTask = searchIo.submit {
            requestContext.set(requestId)
            val local = glossary.lookup(q)
            val humanExamples = exampleCorpus.lookup(q)
            if (requestId != lookupSequence.get()) return@submit
            if (local != null && local.english.isNotBlank() && humanExamples.isNotEmpty()) {
                val complete = WordEntry(word = q, ipa = "", korean = local.korean,
                    english = local.english, examples = humanExamples.joinToString("\n") { "${it.english}\t${it.korean}" },
                    source = localMeaningCredit(local, q) + "\n" +
                        humanExamples.joinToString("\n") { it.credit } + "\n문장 모음: ManyThings / Tatoeba")
                synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = complete }
                runOnUiThread {
                    if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                    current = complete
                    if (showSaved) return@runOnUiThread
                    status.text = "검색 결과 · 사전 뜻풀이 / 한영 예문"
                    resultBox.removeAllViews()
                    showEntry(complete, true)
                }
                return@submit
            }
            if (local != null) {
                val initial = WordEntry(
                    word = q,
                    ipa = "",
                    korean = naturalizeKorean(q, local.korean),
                    english = local.english.ifBlank { "영어 풀이를 불러오는 중…" },
                    examples = humanExamples.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.english}\t${it.korean}" } ?: bilingualFallbackExample(q),
                    source = localMeaningCredit(local, q) + (if (humanExamples.isNotEmpty()) "\n" + humanExamples.joinToString("\n") { it.credit } else "")
                )
                runOnUiThread {
                    if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                    current = initial
                    if (showSaved) return@runOnUiThread
                    status.text = "한글 뜻 표시됨 · 예문을 불러오는 중…"
                    resultBox.removeAllViews()
                    showEntry(initial, false)
                }
            }
            try {
                val online = fetchDictionary(q)
                if (requestId != lookupSequence.get()) return@submit
                // Korean meanings must be dictionary records, never machine-translated definitions.
                val inflection = InflectedForms.find(online.first)
                val baseLocal = inflection?.let { glossary.lookup(it.base) }
                val baseMeaning = inflection?.let { form ->
                    ReviewedEntries.lookup(form.base)?.korean ?: baseLocal?.korean
                }
                val korean = inflection?.korean(baseMeaning ?: online.second.takeIf { it.isNotBlank() } ?: local?.korean)
                    ?: online.second.takeIf { it.isNotBlank() }
                    ?: local?.korean?.takeIf { it.isNotBlank() }
                    ?: "등록된 영한 뜻풀이가 없습니다. 아래 네이버 사전에서 확인해 주세요."
                val sentences = online.third.split("\n").map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("이 단어의 예문은 사전에서 제공하지 않습니다") }
                    .distinct().ifEmpty { listOf(fallbackExample(q)) }.take(2)
                // Automatic machine translation caused a long first-search delay. Show the
                // source English sentence immediately; reviewed corpus pairs remain bilingual.
                val bilingual = if (humanExamples.isNotEmpty()) humanExamples.joinToString("\n") { "${it.english}\t${it.korean}" }
                    else sentences.joinToString("\n") { sentence ->
                        if (sentence == fallbackExample(q)) "$sentence\t오늘 대화에서 “$q”라는 표현을 들었습니다." else "$sentence\t"
                    }
                val entry = WordEntry(word = q, ipa = "", korean = korean,
                    english = if (inflection != null) online.first else local?.english?.takeIf { it.isNotBlank() } ?: online.first, examples = bilingual,
                    source = "영영 풀이: FreeDictionaryAPI / Wiktionary (CC BY-SA 4.0)\n" +
                        (if (inflection != null) "변형 안내: 영영 사전의 원형 정보를 한국어로 표시\n" else "") +
                        (if (online.second.isNotBlank() && inflection == null) "한글 의미: Wiktionary 한국어 어휘 번역 (CC BY-SA 4.0)" else if (baseLocal != null && inflection != null) localMeaningCredit(baseLocal, inflection.base) else localMeaningCredit(local, q)) +
                        (if (humanExamples.isNotEmpty()) "\n" + humanExamples.joinToString("\n") { it.credit } + "\n문장 모음: ManyThings / Tatoeba" else "\n일반 예문: 영어 사전 예문"))
                if (!korean.contains("등록된 영한 뜻풀이가 없습니다")) synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = entry }
                runOnUiThread {
                    if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                    current = entry
                    if (!showSaved) {
                        status.text = "검색 결과"
                        resultBox.removeAllViews()
                        showEntry(entry, true)
                    }
                }
            } catch (e: Exception) {
                if (requestId != lookupSequence.get() || Thread.currentThread().isInterrupted) return@submit
                if (local != null) {
                    runOnUiThread {
                        if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                        current = current?.copy(english = local.english.ifBlank { "영어 풀이를 불러오지 못했습니다." })
                        if (showSaved) return@runOnUiThread
                        status.text = "저장된 사전 뜻을 표시했습니다. 영문 풀이는 연결 후 다시 검색해 주세요."
                        resultBox.removeAllViews()
                        current?.let { showEntry(it, true) }
                    }
                } else {
                    val suggestions = try { fetchSuggestions(q) } catch (_: Exception) { emptyList() }
                    runOnUiThread {
                        if (requestId != lookupSequence.get() || isDestroyed) return@runOnUiThread
                        if (showSaved) return@runOnUiThread
                        status.text = if (suggestions.isNotEmpty()) "‘$q’ 검색 결과가 없습니다. 철자를 확인하거나 아래 단어를 선택해 보세요."
                            else lookupErrorMessage(e)
                        resultBox.removeAllViews()
                        if (suggestions.isNotEmpty()) showSuggestions(suggestions)
                    }
                }
            }
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

    private fun showSuggestions(words: List<String>) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 16.dp(), 18.dp(), 16.dp()); background = rounded(0xffffffff.toInt(), 18) }
        card.addView(label("혹시 이 단어인가요?", 18, true, dark))
        card.addView(label("가장 가까운 철자부터 보여드려요.", 13, false, 0xff64748b.toInt()).apply { setPadding(0, 4.dp(), 0, 10.dp()) })
        words.forEach { candidate ->
            val pick = button("$candidate   ›").apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { searchInput.setText(candidate); lookup(candidate) }
            }
            card.addView(pick, LinearLayout.LayoutParams(-1, 44.dp()).apply { topMargin = 5.dp() })
        }
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp() })
    }

    private fun fetchDictionary(q: String): Triple<String, String, String> {
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

    private fun fetchOpenDictionary(q: String): Triple<String, String, String> {
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val root = JSONObject(http("https://freedictionaryapi.com/api/v1/entries/en/$encoded?translations=true", 3500, 5000))
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val allSenses = mutableListOf<JSONObject>()
        var ipa = ""
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (ipa.isBlank()) {
                val pronunciations = entry.optJSONArray("pronunciations") ?: JSONArray()
                for (j in 0 until pronunciations.length()) {
                    val pronunciation = pronunciations.optJSONObject(j) ?: continue
                    if (pronunciation.optString("type").equals("ipa", true)) {
                        val candidate = pronunciation.optString("text").trim()
                        if (candidate.isNotBlank()) { ipa = candidate; break }
                    }
                }
            }
            val senses = entry.optJSONArray("senses") ?: JSONArray()
            for (j in 0 until senses.length()) senses.optJSONObject(j)?.let { sense ->
                sense.put("_partOfSpeech", entry.optString("partOfSpeech"))
                allSenses += sense
            }
        }
        val useful = allSenses
        val definitions = useful.mapNotNull { it.optString("definition").trim().takeIf(String::isNotBlank) }.distinct().take(4)
        if (definitions.isEmpty()) throw IllegalStateException("사전 결과가 없습니다")
        val samples = useful.flatMap { sense ->
            val examples = sense.optJSONArray("examples") ?: JSONArray()
            (0 until examples.length()).mapNotNull { examples.optString(it).trim().takeIf(String::isNotBlank) }
        }.filter { SentenceExamples.isSentence(it) }.distinct().take(3)
        val english = definitions.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")
        val examples = if (samples.isEmpty()) fallbackExample(q) else samples.joinToString("\n")
        val korean = KoreanLexicalMeanings.format(useful.map { sense ->
            val translations = sense.optJSONArray("translations") ?: JSONArray()
            val words = (0 until translations.length()).mapNotNull { index ->
                val translation = translations.optJSONObject(index) ?: return@mapNotNull null
                val code = translation.optJSONObject("language")?.optString("code").orEmpty()
                if (code == "ko" || code == "kor") translation.optString("word").takeIf { it.isNotBlank() } else null
            }
            KoreanDictionarySense(sense.optString("_partOfSpeech"), words)
        })
        return Triple(english, korean, examples)
    }

    private fun fetchLegacyDictionary(q: String): Triple<String, String, String> {
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
        val phonetics = json.optJSONArray("phonetics") ?: JSONArray()
        var ipa = ""
        for (i in 0 until phonetics.length()) {
            val p = phonetics.optJSONObject(i) ?: continue
            val candidate = p.optString("text").trim()
            if (candidate.startsWith("/") || candidate.startsWith("[")) { ipa = candidate; break }
        }
        val english = definitions.take(4).mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")
        val examples = if (samples.isEmpty()) fallbackExample(q) else samples.take(3).joinToString("\n")
        return Triple(english, "", examples)
    }

    private fun fallbackExample(word: String): String = "I heard “$word” in a conversation today."
    private fun bilingualFallbackExample(word: String): String =
        "${fallbackExample(word)}\t오늘 대화에서 “$word”라는 표현을 들었습니다."

    private fun examplePairs(stored: String): List<Pair<String, String>> = stored.lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        val english = parts.firstOrNull()?.trim().orEmpty()
        if (!SentenceExamples.isSentence(english)) null else english to parts.getOrElse(1) { "" }.trim()
    }

    private fun displayExamples(stored: String): String = examplePairs(stored)
        .joinToString("\n") { (en, ko) -> if (ko.isBlank()) en else "$en\n$ko" }

    private fun naturalizeKorean(word: String, dictionaryGloss: String): String {
        // Add natural, sense-aware Korean glosses for common inflected forms whose
        // single-word database gloss would otherwise hide the contextual meaning.
        return when (word.trim().lowercase(Locale.ROOT)) {
            "stuck" -> "끼어 움직이지 않는; (일이나 문제 해결이) 막힌, 진전이 없는"
            else -> dictionaryGloss.trim().ifBlank { "한글 뜻을 찾지 못했습니다." }
        }
    }

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
        wordRow.addView(button("Anki", 0xfffff2d8.toInt(), 0xff805c20.toInt()).apply {
            textSize = 12f
            contentDescription = "AnkiDroid 카드 보내기"
            setOnClickListener { sendToAnki(e) }
        }, LinearLayout.LayoutParams(60.dp(), 40.dp()).apply { leftMargin = 6.dp() })
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
        section(card, "English definition", e.english)
        val pairs = examplePairs(e.examples)
        val exampleTitle = when {
            e.examples == bilingualFallbackExample(e.word) -> "표현을 언급하는 예문"
            pairs.any { it.second.isNotBlank() } -> "예문 · 한국어 해석"
            else -> "영어 예문"
        }
        section(card, exampleTitle, displayExamples(e.examples))
        val credit = label(e.source.ifBlank { "의미별 자체 정리 · 직접 작성한 한영 예문" }, 10, false, 0xff64748b.toInt()).apply {
            setPadding(0, 12.dp(), 0, 0)
        }
        card.addView(credit)
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp(); bottomMargin = 12.dp() })
    }

    private fun speak(text: String) { wordSpeaker.speak(text) }

    private fun sendToAnki(entry: WordEntry) {
        val e = entry.studyVersion()
        val back = buildString {
            append(e.korean)
            if (e.english.isNotBlank()) append("\n\n").append(e.english)
            if (e.examples.isNotBlank()) append("\n\n").append(displayExamples(e.examples))
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.ichi2.anki")
            putExtra(Intent.EXTRA_SUBJECT, e.word)
            putExtra(Intent.EXTRA_TEXT, back)
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast("AnkiDroid를 설치한 뒤 다시 시도해 주세요.")
        } else {
            startActivity(intent)
        }
    }

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
                    .setMessage("한글 의미\n" + e.korean + "\n\nEnglish definition\n" + e.english + "\n\n예문 · 한국어 해석\n" + displayExamples(e.examples))
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
            resultBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() })
        }
    }

    private fun createXlsx() {
        val entries = db.all()
        if (entries.isEmpty()) { toast("내보낼 저장 단어가 없습니다"); return }
        val choices = arrayOf("단어·뜻·영어 예문(한글 해석 병기)", "한글 예문 해석 + 영어 예문", "영어 예문만", "Anki용 CSV (앞면: 단어 / 뒷면: 뜻·예문)")
        AlertDialog.Builder(this).setTitle("내보내기 형식").setItems(choices) { _, which ->
            exportFormat = which + 1
            val name = when (which) {
                1 -> "영어예문_한영.xlsx"
                2 -> "영어예문.xlsx"
                3 -> "Anki_영어단어장.csv"
                else -> "영어단어장.xlsx"
            }
            val mime = if (exportFormat == 4) "text/csv" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
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
            io.execute {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        val content = if (exportFormat == 4) makeAnkiCsv(db.all()).toByteArray(Charsets.UTF_8) else makeWorkbook(db.all())
                        output.write(content)
                    }
                    runOnUiThread { toast(if (exportFormat == 4) "Anki용 CSV 파일을 저장했습니다" else "엑셀 파일을 저장했습니다") }
                } catch (e: Exception) {
                    runOnUiThread { toast("파일 저장 실패: ${e.message}") }
                }
            }
        }
    }

    private fun makeAnkiCsv(originalEntries: List<WordEntry>): String {
        fun field(value: String) = "\"" + value.replace("\"", "\"\"").replace("\n", "<br>") + "\""
        return buildString {
            append("\uFEFF앞면,뒷면\n")
            originalEntries.map { it.studyVersion() }.forEach { e ->
                val back = buildString {
                    append(e.korean)
                    if (e.english.isNotBlank()) append("\n\n").append(e.english)
                    if (e.examples.isNotBlank()) append("\n\n").append(displayExamples(e.examples))
                }
                append(field(e.word)).append(',').append(field(back)).append('\n')
            }
        }
    }

    private fun makeWorkbook(originalEntries: List<WordEntry>): ByteArray {
        val entries = originalEntries.map { it.studyVersion() }
        val rows = when (exportFormat) {
            2 -> mutableListOf(listOf("예문 한글 해석", "영어 예문")).apply {
                entries.forEach { e -> examplePairs(e.examples).forEach { add(listOf(it.second, it.first)) } }
            }
            3 -> mutableListOf(listOf("영어 예문")).apply {
                entries.forEach { e -> examplePairs(e.examples).forEach { add(listOf(it.first)) } }
            }
            else -> mutableListOf(listOf("영단어", "한글 의미\nEnglish definition", "영어 예문 (한글 해석 병기)")).apply {
                entries.forEach { e -> add(listOf(e.word, "${e.korean}\n${e.english}", displayExamples(e.examples))) }
            }
        }
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"52\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"60\" customWidth=\"1\"/></cols><sheetData>")
            rows.forEachIndexed { ri, row -> append("<row r=\"${ri + 1}\" ht=\"42\" customHeight=\"1\">"); row.forEachIndexed { ci, value -> val ref = "${'A' + ci}${ri + 1}"; append("<c r=\"$ref\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>") }; append("</row>") }
            append("</sheetData></worksheet>")
        }
        val attribution = entries.map { it.source.ifBlank { "의미별 자체 정리 · 직접 작성한 한영 예문" } }.distinct()
        val sourceSheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" + attribution.mapIndexed { i, credit -> """<row r="${i + 1}"><c r="A${i + 1}" t="inlineStr"><is><t>${xml(credit)}</t></is></c></row>""" }.joinToString("") + "</sheetData></worksheet>"
        val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z ->
            fun put(path: String, value: String) { z.putNextEntry(ZipEntry(path)); z.write(value.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"단어장\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"출처\" sheetId=\"2\" r:id=\"rId3\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>")
            put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf xfId=\"0\"/><xf xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs></styleSheet>")
            put("xl/worksheets/sheet1.xml", sheet)
            put("xl/worksheets/sheet2.xml", sourceSheet)
        }; return out.toByteArray()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;").replace("\n", "&#10;")
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
        connections.values.forEach { connection -> io.execute { connection.disconnect() } }
        io.shutdown()
        super.onDestroy()
    }
}

