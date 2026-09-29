package com.hdlee73.sajeonapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WordEntry(val id: Long = 0, val word: String, val ipa: String, val korean: String, val english: String, val examples: String)

class EntryDb(context: Activity) : SQLiteOpenHelper(context, "sajeon.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL UNIQUE COLLATE NOCASE, ipa TEXT, korean TEXT, english TEXT, examples TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun all(): List<WordEntry> {
        val out = mutableListOf<WordEntry>()
        readableDatabase.rawQuery("SELECT id,word,ipa,korean,english,examples FROM entries ORDER BY word COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out += WordEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
        }
        return out
    }
    fun save(e: WordEntry): Boolean {
        val v = ContentValues().apply { put("word", e.word); put("ipa", e.ipa); put("korean", e.korean); put("english", e.english); put("examples", e.examples) }
        return writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE) >= 0
    }
    fun delete(id: Long) { writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) }
}

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var db: EntryDb
    private lateinit var resultBox: LinearLayout
    private lateinit var savedBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private var current: WordEntry? = null
    private var showSaved = false
    private val blue = 0xff245bd6.toInt()
    private val dark = 0xff182230.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = EntryDb(this)
        buildUi()
        renderSaved()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22.dp(), 18.dp(), 22.dp(), 12.dp()); setBackgroundColor(0xfff6f8fc.toInt()) }
        setContentView(root)
        root.addView(label("단어장", 28, true, dark))
        root.addView(label("영어 단어 · 숙어 · 구동사를 찾아 저장하세요", 14, false, 0xff5d6877.toInt()).apply { setPadding(0, 0, 0, 14.dp()) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = "예: break the ice"; singleLine = true; textSize = 16f; setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp()); background = rounded(0xffffffff.toInt(), 12) }
        row.addView(input, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        val search = button("검색").apply { setOnClickListener { val q = input.text.toString().trim(); if (q.isNotEmpty()) lookup(q) else toast("검색어를 입력해 주세요") } }
        row.addView(search, LinearLayout.LayoutParams(82.dp(), 52.dp()).apply { leftMargin = 8.dp() })
        root.addView(row)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14.dp(), 0, 12.dp()) }
        val searchTab = button("검색 결과").apply { setOnClickListener { showSaved = false; showPage() } }
        val savedTab = button("저장 단어").apply { setOnClickListener { showSaved = true; renderSaved(); showPage() } }
        tabs.addView(searchTab, LinearLayout.LayoutParams(0, 44.dp(), 1f)); tabs.addView(savedTab, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { leftMargin = 8.dp() })
        root.addView(tabs)
        status = label("검색 결과가 여기에 표시됩니다.", 14, false, 0xff5d6877.toInt())
        root.addView(status)
        val scroll = ScrollView(this).apply { fillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox = content
        savedBox = content
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showPage() { if (showSaved) renderSaved() else { resultBox.removeAllViews(); current?.let { showEntry(it, false) } } }

    private fun lookup(q: String) {
        status.text = "‘$q’ 검색 중…"
        io.execute {
            try {
                val data = fetchDictionary(q)
                val translated = try { translate(data.first) } catch (_: Exception) { "번역 서비스를 사용할 수 없습니다. 영문 의미를 확인해 주세요." }
                val e = WordEntry(word = q, ipa = data.second, korean = translated, english = data.first, examples = data.third)
                runOnUiThread { current = e; status.text = "검색 결과"; showSaved = false; resultBox.removeAllViews(); showEntry(e, true) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "검색하지 못했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요. (${e.message ?: "오류"})" }
            }
        }
    }

    private fun fetchDictionary(q: String): Triple<String, String, String> {
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val root = JSONArray(http("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded"))
        val json = root.optJSONObject(0) ?: throw IllegalStateException("사전 결과가 없습니다")
        val meanings = json.optJSONArray("meanings") ?: JSONArray()
        val definitions = linkedSetOf<String>(); val samples = linkedSetOf<String>()
        for (i in 0 until meanings.length()) {
            val defs = meanings.optJSONObject(i)?.optJSONArray("definitions") ?: continue
            for (j in 0 until defs.length()) {
                val item = defs.optJSONObject(j) ?: continue
                item.optString("definition").takeIf { it.isNotBlank() }?.let { definitions.add(it) }
                item.optString("example").takeIf { it.isNotBlank() }?.let { samples.add(it) }
            }
        }
        if (definitions.isEmpty()) throw IllegalStateException("의미를 찾을 수 없습니다")
        val phonetics = json.optJSONArray("phonetics") ?: JSONArray()
        var ipa = ""
        for (i in 0 until phonetics.length()) { val p = phonetics.optJSONObject(i)?.optString("text", "") ?: ""; if (p.isNotBlank()) { ipa = p; break } }
        val cleanWord = json.optString("word", q)
        if (samples.isEmpty()) samples += "I learned how to use ‘$cleanWord’ in a sentence."
        return Triple(definitions.take(3).mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n"), ipa.ifBlank { "발음기호 정보 없음" }, samples.take(3).joinToString("\n"))
    }

    private fun translate(text: String): String {
        val q = URLEncoder.encode(text.take(900), "UTF-8")
        val response = JSONObject(http("https://api.mymemory.translated.net/get?q=$q&langpair=en%7Cko"))
        val translated = response.optJSONObject("responseData")?.optString("translatedText", "")?.trim().orEmpty()
        if (translated.isBlank()) throw IllegalStateException("번역 결과가 없습니다")
        return translated
    }

    private fun http(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 12000; c.readTimeout = 16000
        c.setRequestProperty("User-Agent", "SajeonApp/1.0 (Android)")
        return try { val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream; val body = stream.bufferedReader().use { it.readText() }; if (code !in 200..299) throw IllegalStateException("HTTP $code"); body } finally { c.disconnect() }
    }

    private fun showEntry(e: WordEntry, canSave: Boolean) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp()); background = rounded(0xffffffff.toInt(), 18) }
        card.addView(label(e.word, 25, true, dark))
        card.addView(label(e.ipa, 15, false, 0xff526174.toInt()).apply { setPadding(0, 4.dp(), 0, 16.dp()) })
        section(card, "한글 의미", e.korean)
        section(card, "English definition", e.english)
        section(card, "예문", e.examples)
        if (canSave) {
            saveButton = button("이 단어 저장").apply { setOnClickListener { db.save(e); toast("저장했습니다"); renderSaved(); isEnabled = false; text = "저장됨" } }
            card.addView(saveButton, LinearLayout.LayoutParams(-1, 48.dp()).apply { topMargin = 14.dp() })
        }
        resultBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp(); bottomMargin = 12.dp() })
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
            info.addView(label("${e.word}  ${e.ipa}", 18, true, dark))
            info.addView(label(e.korean, 14, false, 0xff526174.toInt()).apply { maxLines = 2 })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("보기").apply { setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(e.word).setMessage("${e.ipa}\n\n한글 의미\n${e.korean}\n\nEnglish definition\n${e.english}\n\n예문\n${e.examples}").setPositiveButton("닫기", null).show() } })
            row.addView(button("삭제").apply { setOnClickListener { db.delete(e.id); renderSaved() } })
            resultBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() })
        }
    }

    private fun createXlsx() {
        val entries = db.all()
        if (entries.isEmpty()) { toast("내보낼 저장 단어가 없습니다"); return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; putExtra(Intent.EXTRA_TITLE, "영어단어장.xlsx") }
        startActivityForResult(intent, 42)
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
        val rows = mutableListOf(listOf("영단어", "한글 의미\nEnglish definition", "영어 예문"))
        entries.forEach { rows += listOf("${it.word} ${it.ipa}".trim(), "${it.korean}\n${it.english}", it.examples) }
        val sheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols><col min=\"1\" max=\"1\" width=\"28\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"52\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"60\" customWidth=\"1\"/></cols><sheetData>")
            rows.forEachIndexed { ri, row -> append("<row r=\"${ri + 1}\" ht=\"42\" customHeight=\"1\">"); row.forEachIndexed { ci, value -> val ref = "${'A' + ci}${ri + 1}"; append("<c r=\"$ref\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>") }; append("</row>") }
            append("</sheetData></worksheet>")
        }
        val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z ->
            fun put(path: String, value: String) { z.putNextEntry(ZipEntry(path)); z.write(value.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"단어장\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf xfId=\"0\"/><xf xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs></styleSheet>")
            put("xl/worksheets/sheet1.xml", sheet)
        }; return out.toByteArray()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;").replace("\n", "&#10;")
    private fun section(parent: LinearLayout, title: String, value: String) { parent.addView(label(title, 14, true, blue).apply { setPadding(0, 10.dp(), 0, 3.dp()) }); parent.addView(label(value, 16, false, dark)) }
    private fun label(text: String, size: Int, bold: Boolean, color: Int) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setLineSpacing(2.dp().toFloat(), 1f) }
    private fun button(text: String) = Button(this).apply { this.text = text; textSize = 14f; isAllCaps = false; setTextColor(0xffffffff.toInt()); background = rounded(blue, 12); minHeight = 0; minimumHeight = 0; stateListAnimator = null }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.dp().toFloat() }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
