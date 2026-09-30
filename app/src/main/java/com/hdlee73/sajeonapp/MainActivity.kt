package com.hdlee73.sajeonapp

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
private data class LocalMeaning(val korean: String, val english: String, val ipa: String)

private class LocalGlossary(private val activity: Activity) {
    private var database: SQLiteDatabase? = null
    @Synchronized private fun open(): SQLiteDatabase? {
        database?.let { return it }
        return try {
            val file = activity.getDatabasePath("meaning_dictionary_nikl_v1.sqlite")
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
            db.rawQuery("SELECT meaning_ko, meaning_en, ipa FROM words WHERE word = ? COLLATE NOCASE LIMIT 1", arrayOf(word.trim())).use { c ->
                if (c.moveToFirst()) LocalMeaning(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty()) else null
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
    fun save(e: WordEntry): Boolean {
        val v = ContentValues().apply { put("word", e.word); put("ipa", ""); put("korean", e.korean); put("english", e.english); put("examples", e.examples); put("source", e.source) }
        return writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }
    fun delete(id: Long) { writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) }
}

class MainActivity : Activity() {
    private val translator by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN).build())
    }
    private var translationModelReady = false
    private val io = Executors.newSingleThreadExecutor()
    private val lookupSequence = AtomicInteger(0)
    private val resultCache = mutableMapOf<String, WordEntry>()
    private lateinit var exampleCorpus: BilingualExamples
    private lateinit var glossary: LocalGlossary
    private lateinit var db: EntryDb
    private lateinit var resultBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchInput: EditText
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var current: WordEntry? = null
    private var showSaved = false
    private var exportFormat = 1
    private var returnToPdf = false
    private lateinit var returnButton: Button
    private val blue = 0xff245bd6.toInt()
    private val dark = 0xff182230.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = EntryDb(this)
        glossary = LocalGlossary(this)
        exampleCorpus = BilingualExamples(this)
        returnToPdf = intent?.getBooleanExtra("return_to_pdf", false) == true
        buildUi()
        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
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
            searchInput.setText(query)
            searchInput.setSelection(query.length)
            lookup(query)
        } else {
            renderSaved()
        }
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
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 18.dp(), 20.dp(), 18.dp()); background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xff142949.toInt(), 0xff294c79.toInt())).apply { cornerRadius = 22.dp().toFloat() } }
        hero.addView(label("영어단어장", 25, true, 0xffffffff.toInt()))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14.dp() })
        val searchCard = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp()); background = rounded(0xffffffff.toInt(), 17) }
        searchInput = EditText(this).apply { hint = "단어, 숙어 또는 구동사"; setSingleLine(true); textSize = 16f; setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp()); background = android.graphics.drawable.ColorDrawable(0x00000000); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        searchInput.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchNow(); true } else false }
        searchCard.addView(searchInput, LinearLayout.LayoutParams(0, 50.dp(), 1f))
        searchCard.addView(button("검색").apply { setOnClickListener { searchNow() } }, LinearLayout.LayoutParams(84.dp(), 50.dp()))
        root.addView(searchCard)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.dp(), 0, 12.dp()) }
        val searchTab = button("검색 결과").apply { setOnClickListener { showSaved = false; showPage() } }
        val savedTab = button("저장 단어").apply { setOnClickListener { showSaved = true; renderSaved(); showPage() } }
        tabs.addView(searchTab, LinearLayout.LayoutParams(0, 46.dp(), 1f)); tabs.addView(savedTab, LinearLayout.LayoutParams(0, 46.dp(), 1f).apply { leftMargin = 8.dp() })
        root.addView(tabs)
        status = label("검색어를 입력하면 뜻과 예문을 찾아드립니다.", 14, false, 0xff5d6877.toInt()).apply { setPadding(2.dp(), 0, 2.dp(), 4.dp()) }
        root.addView(status)
        val scroll = ScrollView(this).apply { setFillViewport(true) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox = content
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun searchNow() {
        val q = searchInput.text.toString().trim()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        if (q.isNotEmpty()) lookup(q) else toast("검색어를 입력해 주세요")
    }

    private fun showPage() { if (showSaved) renderSaved() else { resultBox.removeAllViews(); current?.let { showEntry(it, true) } } }

    private fun lookup(q: String) {
        val requestId = lookupSequence.incrementAndGet()
        status.text = "‘$q’ 검색 중…"
        resultBox.removeAllViews()
        showSaved = false
        current = null
        val cached = synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] }
        if (cached != null) {
            current = cached
            status.text = "검색 결과 · 저장된 검색"
            showEntry(cached, true)
            return
        }
        io.execute {
            ReviewedEntries.lookup(q)?.let { reviewed ->
                synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = reviewed }
                runOnUiThread {
                    if (requestId != lookupSequence.get()) return@runOnUiThread
                    current = reviewed
                    status.text = "검색 결과 · 의미별 풀이"
                    resultBox.removeAllViews()
                    showEntry(reviewed, true)
                }
                return@execute
            }
            val local = glossary.lookup(q)
            val humanExamples = exampleCorpus.lookup(q)
            if (requestId != lookupSequence.get()) return@execute
            if (local != null && local.english.isNotBlank() && humanExamples.isNotEmpty()) {
                val complete = WordEntry(word = q, ipa = "", korean = local.korean,
                    english = local.english, examples = humanExamples.joinToString("\n") { "${it.english}\t${it.korean}" },
                    source = "한국어 풀이: 국립국어원 한국어기초사전 영어 대역의 역색인 (CC BY-SA 2.0 KR).\n" +
                        humanExamples.joinToString("\n") { it.credit } + "\n문장 모음: ManyThings / Tatoeba")
                synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = complete }
                runOnUiThread {
                    if (requestId != lookupSequence.get()) return@runOnUiThread
                    current = complete
                    status.text = "검색 결과 · 사전 뜻풀이 / 한영 예문"
                    resultBox.removeAllViews()
                    showEntry(complete, true)
                }
                return@execute
            }
            if (local != null) {
                val initial = WordEntry(
                    word = q,
                    ipa = "",
                    korean = naturalizeKorean(q, local.korean),
                    english = local.english.ifBlank { "영어 풀이를 불러오는 중…" },
                    examples = bilingualFallbackExample(q),
                    source = "국립국어원 한국어기초사전 영어 대역의 역색인 (CC BY-SA 2.0 KR)"
                )
                runOnUiThread {
                    if (requestId != lookupSequence.get()) return@runOnUiThread
                    current = initial
                    status.text = "한글 뜻 표시됨 · 예문을 불러오는 중…"
                    resultBox.removeAllViews()
                    showEntry(initial, false)
                }
            }
            try {
                val online = fetchDictionary(q)
                // Korean meanings must be dictionary records, never machine-translated definitions.
                val korean = local?.korean?.takeIf { it.isNotBlank() }
                    ?: "등록된 영한 뜻풀이가 없습니다. 아래 네이버 사전에서 확인해 주세요."
                val sentences = online.third.split("\n").map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("이 단어의 예문은 사전에서 제공하지 않습니다") }
                    .distinct().ifEmpty { listOf(fallbackExample(q)) }.take(2)
                val bilingual = sentences.map { sentence ->
                    val ko = if (sentence == fallbackExample(q)) "오늘 대화에서 “$q”라는 표현을 들었습니다." else try { translate(sentence) } catch (_: Exception) { "" }
                    if (ko.isBlank()) "$sentence\t(해석을 불러오지 못했습니다)" else "$sentence\t$ko"
                }.joinToString("\n")
                val entry = WordEntry(word = q, ipa = "", korean = korean,
                    english = local?.english?.takeIf { it.isNotBlank() } ?: online.first, examples = bilingual,
                    source = (if (local != null) "국립국어원 한국어기초사전 영어 대역의 역색인 (CC BY-SA 2.0 KR)" else "FreeDictionaryAPI / Wiktionary (CC BY-SA 4.0)") +
                        "\n일반 예문 해석: Google ML Kit 자동 번역")
                if (!korean.contains("불러오지 못") && !bilingual.contains("불러오지 못")) {
                    synchronized(resultCache) { resultCache[q.lowercase(Locale.ROOT)] = entry }
                }
                runOnUiThread {
                    if (requestId != lookupSequence.get()) return@runOnUiThread
                    current = entry
                    status.text = "검색 결과"
                    resultBox.removeAllViews()
                    showEntry(entry, true)
                }
            } catch (e: Exception) {
                if (local != null) {
                    runOnUiThread {
                        if (requestId != lookupSequence.get()) return@runOnUiThread
                        status.text = "저장된 사전 뜻을 표시했습니다. 영문 풀이와 예문은 연결 후 다시 검색해 주세요."
                    }
                } else {
                    val suggestions = try { fetchSuggestions(q) } catch (_: Exception) { emptyList() }
                    runOnUiThread {
                        if (requestId != lookupSequence.get()) return@runOnUiThread
                        status.text = if (suggestions.isNotEmpty()) "‘$q’ 검색 결과가 없습니다. 철자를 확인하거나 아래 단어를 선택해 보세요."
                            else lookupErrorMessage(e)
                        resultBox.removeAllViews()
                        if (suggestions.isNotEmpty()) showSuggestions(suggestions)
                    }
                }
            }
        }
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
            for (j in 0 until senses.length()) senses.optJSONObject(j)?.let { allSenses += it }
        }
        val useful = allSenses.filterNot { sense ->
            val tags = sense.optJSONArray("tags") ?: JSONArray()
            (0 until tags.length()).any { tags.optString(it).equals("form of", true) }
        }.ifEmpty { allSenses }
        val definitions = useful.mapNotNull { it.optString("definition").trim().takeIf(String::isNotBlank) }.distinct().take(4)
        if (definitions.isEmpty()) throw IllegalStateException("사전 결과가 없습니다")
        val samples = useful.flatMap { sense ->
            val examples = sense.optJSONArray("examples") ?: JSONArray()
            (0 until examples.length()).mapNotNull { examples.optString(it).trim().takeIf(String::isNotBlank) }
        }.distinct().take(3)
        val english = definitions.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")
        val examples = if (samples.isEmpty()) fallbackExample(q) else samples.joinToString("\n")
        return Triple(english, ipa, examples)
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
                item.optString("example").takeIf(String::isNotBlank)?.let(samples::add)
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
        return Triple(english, ipa, examples)
    }

    private fun fallbackExample(word: String): String = "I heard “$word” in a conversation today."
    private fun bilingualFallbackExample(word: String): String =
        "${fallbackExample(word)}\t오늘 대화에서 “$word”라는 표현을 들었습니다."

    private fun examplePairs(stored: String): List<Pair<String, String>> = stored.lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        val english = parts.firstOrNull()?.trim().orEmpty()
        if (english.isBlank()) null else english to parts.getOrElse(1) { "" }.trim()
    }

    private fun displayExamples(stored: String): String = examplePairs(stored)
        .joinToString("\n") { (en, ko) -> "$en\n$ko" }

    private fun naturalizeKorean(word: String, dictionaryGloss: String): String {
        // Add natural, sense-aware Korean glosses for common inflected forms whose
        // single-word database gloss would otherwise hide the contextual meaning.
        return when (word.trim().lowercase(Locale.ROOT)) {
            "stuck" -> "끼어 움직이지 않는; (일이나 문제 해결이) 막힌, 진전이 없는"
            else -> dictionaryGloss.trim().ifBlank { "한글 뜻을 찾지 못했습니다." }
        }
    }

    private fun translate(text: String): String {
        if (!translationModelReady) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) status.text = "한국어 번역 준비 중… 최초 사용 시 번역 모델을 내려받습니다."
            }
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 90, TimeUnit.SECONDS)
            translationModelReady = true
        }
        val translated = Tasks.await(translator.translate(text), 20, TimeUnit.SECONDS).trim()
        if (translated.isBlank() || !translated.any { it in '가'..'힣' }) {
            throw IllegalStateException("한국어 번역 결과가 없습니다")
        }
        return translated
    }

    private fun http(address: String, connectTimeout: Int = 6000, readTimeout: Int = 8000): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = connectTimeout; c.readTimeout = readTimeout
        c.setRequestProperty("User-Agent", "SajeonApp/1.0 (Android)")
        return try { val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream; val body = stream.bufferedReader().use { it.readText() }; if (code !in 200..299) throw IllegalStateException("HTTP $code"); body } finally { c.disconnect() }
    }

    private fun showEntry(e: WordEntry, canSave: Boolean) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp()); background = rounded(0xffffffff.toInt(), 18) }
        val wordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wordRow.addView(label(e.word, 25, true, dark), LinearLayout.LayoutParams(0, -2, 1f))
        wordRow.addView(button("🔊 듣기").apply { setOnClickListener { speak(e.word) } })
        card.addView(wordRow)
        section(card, "한글 의미", e.korean)
        card.addView(button("네이버 영한사전").apply { setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://en.dict.naver.com/#/search?query=" + Uri.encode(e.word))))
        } }, LinearLayout.LayoutParams(-1, 42.dp()).apply { topMargin = 8.dp() })
        section(card, "English definition", e.english)
        section(card, if (e.examples == bilingualFallbackExample(e.word)) "표현을 언급하는 예문" else "예문 · 한국어 해석", displayExamples(e.examples))
        val credit = label(e.source.ifBlank { "의미별 자체 정리 · 직접 작성한 한영 예문" }, 10, false, 0xff64748b.toInt()).apply {
            setPadding(0, 12.dp(), 0, 0)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://krdict.korean.go.kr/eng/mainAction"))) }
        }
        card.addView(credit)
        if (canSave) {
            val saveButton = button("이 단어 저장").apply { setOnClickListener { db.save(e); toast("저장했습니다"); renderSaved(); isEnabled = false; text = "저장됨" } }
            card.addView(saveButton, LinearLayout.LayoutParams(-1, 48.dp()).apply { topMargin = 14.dp() })
        }
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp(); bottomMargin = 12.dp() })
    }

    private fun speak(text: String) {
        if (!ttsReady) { toast("영어 음성 엔진을 준비하고 있습니다"); return }
        tts?.setLanguage(Locale.US)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word-pronunciation")
    }

    private fun renderSaved() {
        if (!showSaved) return
        resultBox.removeAllViews()
        val entries = db.all()
        if (entries.isEmpty()) { resultBox.addView(label("아직 저장한 단어가 없습니다. 검색 결과에서 원하는 단어만 저장할 수 있어요.", 15, false, 0xff5d6877.toInt()).apply { setPadding(4.dp(), 14.dp(), 4.dp(), 14.dp()) }); return }
        resultBox.addView(button("엑셀(.xlsx)로 내보내기").apply { setOnClickListener { createXlsx() } }, LinearLayout.LayoutParams(-1, 48.dp()).apply { bottomMargin = 12.dp() })
        entries.forEach { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14.dp(), 12.dp(), 8.dp(), 12.dp()); background = rounded(0xffffffff.toInt(), 14) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(label(e.word, 18, true, dark))
            info.addView(label(e.korean, 14, false, 0xff526174.toInt()).apply { maxLines = 2 })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("보기").apply { setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(e.word).setMessage("한글 의미\n${e.korean}\n\nEnglish definition\n${e.english}\n\n예문 · 한국어 해석\n${displayExamples(e.examples)}").setPositiveButton("닫기", null).show() } })
            row.addView(button("다시 검색").apply { setOnClickListener { searchInput.setText(e.word); lookup(e.word) } })
            row.addView(button("삭제").apply { setOnClickListener { db.delete(e.id); renderSaved() } })
            resultBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() })
        }
    }

    private fun createXlsx() {
        val entries = db.all()
        if (entries.isEmpty()) { toast("내보낼 저장 단어가 없습니다"); return }
        val choices = arrayOf("단어·뜻·영어 예문(한글 해석 병기)", "한글 예문 해석 + 영어 예문", "영어 예문만")
        AlertDialog.Builder(this).setTitle("엑셀 저장 형식").setItems(choices) { _, which ->
            exportFormat = which + 1
            val name = when (which) { 1 -> "영어예문_한영.xlsx"; 2 -> "영어예문.xlsx"; else -> "영어단어장.xlsx" }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; putExtra(Intent.EXTRA_TITLE, name) }
            startActivityForResult(intent, 42)
        }.setNegativeButton("취소", null).show()
    }

    @Deprecated("Activity result API kept compatible with platform-only project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            io.execute { try { contentResolver.openOutputStream(uri)?.use { it.write(makeWorkbook(db.all())) }; runOnUiThread { toast("엑셀 파일을 저장했습니다") } } catch (e: Exception) { runOnUiThread { toast("파일 저장 실패: ${e.message}") } } }
        }
    }

    private fun makeWorkbook(entries: List<WordEntry>): ByteArray {
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
    private fun button(text: String) = Button(this).apply { this.text = text; textSize = 14f; isAllCaps = false; setTextColor(0xffffffff.toInt()); background = rounded(blue, 12); minHeight = 0; minimumHeight = 0; stateListAnimator = null }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.dp().toFloat() }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        lookupSequence.incrementAndGet()
        tts?.stop()
        tts?.shutdown()
        io.execute { translator.close() }
        io.shutdown()
        super.onDestroy()
    }
}
