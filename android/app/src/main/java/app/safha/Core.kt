// صفحة (Safha PDF) — الأساسيات: التطبيق، الترجمة، الإعدادات، السجلّ، العلامات، التعليقات
// برمجة: د. محمد توفيق · kmkhol01@gmail.com
package app.safha

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

const val DEVELOPER_AR = "د. محمد توفيق"
const val DEVELOPER_EN = "Dr. Mohammed Tawfik"
const val DEVELOPER_EMAIL = "kmkhol01@gmail.com"
const val DEVELOPER_ORCID = "0009-0004-7622-5991"

class SafhaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Store.init(this)
        Thread { Docs.cleanInbox(this) }.start()
    }
}

// ---------- الترجمة: عربي / إنجليزي ----------
val LocalArabic = staticCompositionLocalOf { true }

@Composable
fun tr(ar: String, en: String): String = if (LocalArabic.current) ar else en

fun trS(ar: String, en: String): String = if (Store.isArabic()) ar else en

// ---------- نماذج البيانات ----------
data class RecentFile(
    val uri: String,
    val name: String,
    val size: Long,
    val opened: Long,
    val page: Int,
    val pages: Int,
)

data class Bookmark(val page: Int, val label: String, val time: Long)

/** إحداثيات مطبَّعة 0..1 على الصفحة كما تُعرض (الأصل أعلى اليسار). */
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float)
data class NPoint(val x: Float, val y: Float)

object AnnotType {
    const val HIGHLIGHT = 0
    const val UNDERLINE = 1
    const val STRIKE = 2
    const val INK = 3
    const val NOTE = 4
}

data class Annot(
    val id: Long,
    val page: Int,
    val type: Int,
    val color: Int,
    val width: Float = 0.004f,          // سماكة القلم نسبةً لعرض الصفحة
    val rects: List<NRect> = emptyList(),
    val strokes: List<List<NPoint>> = emptyList(),
    val text: String = "",
)

// ---------- التخزين ----------
object Store {
    private lateinit var sp: SharedPreferences
    lateinit var app: Context; private set

    var lang by mutableStateOf("system")        // system | ar | en
    var theme by mutableStateOf("system")       // system | light | dark
    var readMode by mutableStateOf("normal")    // normal | night | sepia | gray
    var layout by mutableStateOf("vertical")    // vertical | horizontal
    var rtlPages by mutableStateOf(false)
    var keepScreenOn by mutableStateOf(true)
    var fullscreenRead by mutableStateOf(false)
    var pageGap by mutableStateOf(true)
    var ttsRate by mutableStateOf(1.0f)
    var signature by mutableStateOf<List<List<NPoint>>>(emptyList())
    var homeTab by mutableStateOf(0)            // التبويب الحالي في الرئيسية (لا يُحفظ)
    val recents = mutableStateListOf<RecentFile>()
    val favorites = mutableStateListOf<String>()

    fun init(c: Context) {
        app = c.applicationContext
        sp = c.getSharedPreferences("safha", Context.MODE_PRIVATE)
        lang = sp.getString("lang", "system")!!
        theme = sp.getString("theme", "system")!!
        readMode = sp.getString("readMode", "normal")!!
        layout = sp.getString("layout", "vertical")!!
        rtlPages = sp.getBoolean("rtlPages", false)
        keepScreenOn = sp.getBoolean("keepScreenOn", true)
        fullscreenRead = sp.getBoolean("fullscreenRead", false)
        pageGap = sp.getBoolean("pageGap", true)
        ttsRate = sp.getFloat("ttsRate", 1f)
        signature = parseStrokes(sp.getString("signature", "[]")!!)
        loadRecents()
        if (!sp.getBoolean("f_pageFix101", false)) {
            // في 1.0.0 كانت «آخر صفحة» تُحفظ من منتصف الشاشة فتقفز للصفحة 3-4؛ نصفّرها مرة واحدة
            val e = sp.edit()
            sp.all.keys.filter { it.startsWith("pg_") }.forEach { e.remove(it) }
            e.putBoolean("f_pageFix101", true).apply()
            for (i in recents.indices) recents[i] = recents[i].copy(page = 0)
            saveRecents()
        }
        favorites.clear()
        favorites.addAll(JSONArray(sp.getString("favorites", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    fun isArabic(): Boolean = when (lang) {
        "ar" -> true
        "en" -> false
        else -> Locale.getDefault().language == "ar"
    }

    fun flag(name: String): Boolean = sp.getBoolean("f_$name", false)
    fun setFlag(name: String) = sp.edit().putBoolean("f_$name", true).apply()

    fun save() {
        sp.edit()
            .putString("lang", lang).putString("theme", theme).putString("readMode", readMode)
            .putString("layout", layout).putBoolean("rtlPages", rtlPages)
            .putBoolean("keepScreenOn", keepScreenOn).putBoolean("fullscreenRead", fullscreenRead)
            .putBoolean("pageGap", pageGap).putFloat("ttsRate", ttsRate)
            .putString("signature", strokesJson(signature).toString())
            .apply()
    }

    // ----- الملفات الأخيرة -----
    private fun loadRecents() {
        recents.clear()
        val a = JSONArray(sp.getString("recents", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            recents += RecentFile(o.getString("u"), o.getString("n"), o.optLong("s"), o.optLong("o"), o.optInt("p"), o.optInt("c"))
        }
    }

    private fun saveRecents() {
        val a = JSONArray()
        recents.take(80).forEach {
            a.put(JSONObject().put("u", it.uri).put("n", it.name).put("s", it.size).put("o", it.opened).put("p", it.page).put("c", it.pages))
        }
        sp.edit().putString("recents", a.toString()).apply()
    }

    fun touchRecent(uri: Uri, name: String, size: Long, pages: Int, page: Int) {
        val u = uri.toString()
        recents.removeAll { it.uri == u }
        recents.add(0, RecentFile(u, name, size, System.currentTimeMillis(), page, pages))
        saveRecents()
    }

    fun updatePage(uri: Uri, page: Int) {
        val u = uri.toString()
        val i = recents.indexOfFirst { it.uri == u }
        if (i >= 0 && recents[i].page != page) {
            recents[i] = recents[i].copy(page = page)
            saveRecents()
        }
        sp.edit().putInt("pg_" + key(u), page).apply()
    }

    fun lastPage(uri: Uri): Int = sp.getInt("pg_" + key(uri.toString()), recents.firstOrNull { it.uri == uri.toString() }?.page ?: 0)

    fun removeRecent(u: String) {
        recents.removeAll { it.uri == u }
        saveRecents()
    }

    fun clearRecents() {
        recents.clear()
        saveRecents()
    }

    fun toggleFavorite(u: String) {
        if (u in favorites) favorites.remove(u) else favorites.add(0, u)
        sp.edit().putString("favorites", JSONArray(favorites.toList()).toString()).apply()
    }

    // ----- العلامات المرجعية -----
    fun bookmarks(u: String): List<Bookmark> {
        val a = JSONArray(sp.getString("bm_" + key(u), "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Bookmark(o.getInt("p"), o.optString("l"), o.optLong("t")) }
            .sortedBy { it.page }
    }

    fun saveBookmarks(u: String, list: List<Bookmark>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("p", it.page).put("l", it.label).put("t", it.time)) }
        sp.edit().putString("bm_" + key(u), a.toString()).apply()
    }

    // ----- التعليقات (تظهر فوق الصفحة وتُحفظ داخل PDF عند الطلب) -----
    fun annots(u: String): List<Annot> {
        val a = JSONArray(sp.getString("an_" + key(u), "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val rs = o.optJSONArray("r") ?: JSONArray()
            Annot(
                id = o.getLong("id"), page = o.getInt("p"), type = o.getInt("ty"), color = o.getInt("c"),
                width = o.optDouble("w", 0.004).toFloat(),
                rects = (0 until rs.length()).map { k ->
                    val r = rs.getJSONArray(k)
                    NRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                },
                strokes = parseStrokes(o.optString("s", "[]")),
                text = o.optString("x"),
            )
        }
    }

    fun saveAnnots(u: String, list: List<Annot>) {
        val a = JSONArray()
        list.forEach { an ->
            val rs = JSONArray()
            an.rects.forEach { rs.put(JSONArray().put(an.r4(it.l)).put(an.r4(it.t)).put(an.r4(it.r)).put(an.r4(it.b))) }
            a.put(
                JSONObject().put("id", an.id).put("p", an.page).put("ty", an.type).put("c", an.color)
                    .put("w", an.width.toDouble()).put("r", rs).put("s", strokesJson(an.strokes).toString()).put("x", an.text)
            )
        }
        sp.edit().putString("an_" + key(u), a.toString()).apply()
    }

    private fun Annot.r4(f: Float) = Math.round(f * 100000.0) / 100000.0

    fun key(u: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(u.toByteArray())
        return d.take(10).joinToString("") { "%02x".format(it) }
    }

    fun strokesJson(s: List<List<NPoint>>): JSONArray {
        val a = JSONArray()
        s.forEach { st ->
            val p = JSONArray()
            st.forEach { p.put(Math.round(it.x * 100000.0) / 100000.0).put(Math.round(it.y * 100000.0) / 100000.0) }
            a.put(p)
        }
        return a
    }

    fun parseStrokes(js: String): List<List<NPoint>> {
        val a = JSONArray(js)
        return (0 until a.length()).map { i ->
            val p = a.getJSONArray(i)
            (0 until p.length() / 2).map { k -> NPoint(p.getDouble(2 * k).toFloat(), p.getDouble(2 * k + 1).toFloat()) }
        }
    }
}

// ---------- الملفات الواردة من تطبيقات أخرى ----------
object Docs {
    fun inbox(c: Context) = java.io.File(c.filesDir, "inbox").apply { mkdirs() }

    /**
     * واتساب وتيليجرام والبريد ومديرو الملفات يمنحون صلاحية مؤقتة فقط؛ بعد إغلاق التطبيق يصبح الرابط
     * غير صالح ("No content provider" / Permission denial). لذلك نحفظ نسخة داخل التطبيق فوراً ونفتحها هي،
     * إلا إن كان الرابط يقبل صلاحية دائمة (منتقي الملفات في النظام).
     */
    fun localize(c: Context, uri: Uri, flags: Int): Uri {
        if (uri.scheme == "file" || uri.scheme == null) return uri
        if ((flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
            runCatching { c.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        ) return uri
        val name = c.displayName(uri).replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().ifBlank { "document.pdf" }
            .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val size = c.fileSize(uri)
        val dir = inbox(c)
        var f = java.io.File(dir, name)
        if (f.exists() && f.length() == 0L) f.delete()
        if (f.exists() && size > 0 && f.length() == size) return Uri.fromFile(f)
        var k = 2
        while (f.exists()) { f = java.io.File(dir, name.removeSuffix(".pdf").removeSuffix(".PDF") + " ($k).pdf"); k++ }
        val tmp = java.io.File(dir, ".part_" + System.nanoTime())
        c.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (tmp.length() == 0L) { tmp.delete(); throw java.io.IOException(trS("التطبيق المرسِل أعطى ملفاً فارغاً", "The sending app provided an empty file")) }
        tmp.renameTo(f)
        return Uri.fromFile(f)
    }

    /** يحذف النسخ القديمة التي لم تعد في «الأخيرة» أو «المفضلة». */
    fun cleanInbox(c: Context) = runCatching {
        val keep = (Store.recents.map { it.uri } + Store.favorites).toSet()
        inbox(c).listFiles()?.forEach { f ->
            val old = System.currentTimeMillis() - f.lastModified() > 7L * 86_400_000
            if (f.name.startsWith(".part_") || (Uri.fromFile(f).toString() !in keep && old)) f.delete()
        }
    }

    fun isAccessError(e: Throwable?): Boolean {
        val m = (e?.message ?: "") + " " + (e?.javaClass?.simpleName ?: "")
        return e is SecurityException || m.contains("No content provider", true) || m.contains("Permission", true) ||
            m.contains("ENOENT") || m.contains("FileNotFound", true) || m.contains("EACCES")
    }
}

// ---------- أدوات الملفات ----------
fun Context.displayName(uri: Uri): String {
    if (uri.scheme == "file") return uri.lastPathSegment ?: "document.pdf"
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
}

fun Context.fileSize(uri: Uri): Long {
    if (uri.scheme == "file") return uri.path?.let { java.io.File(it).length() } ?: 0L
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L
}

/** يعزل نصاً من اليسار لليمين (أرقام مثل 3 / 20) داخل واجهة عربية. */
fun ltr(s: String) = "⁦$s⁩"

/** يجمع أجزاء مختلفة الاتجاه دون أن تختلط. */
fun joinIso(parts: List<String>) = parts.filter { it.isNotBlank() }.joinToString(" · ") { "⁨$it⁩" }

fun humanSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(Locale.US, b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, b / 1048576.0)
    else -> "%.2f GB".format(Locale.US, b / 1073741824.0)
}
