// صفحة — محرّك PDF (PDFium): فتح، رسم، نصوص، بحث، روابط، فهرس. برمجة: د. محمد توفيق
package app.safha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.AlreadyClosedBehavior
import io.legere.pdfiumandroid.api.Config
import io.legere.pdfiumandroid.api.Meta
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class PasswordNeeded(val wrong: Boolean) : Exception()

data class PLink(val rect: NRect, val page: Int?, val uri: String?)
data class TocItem(val title: String, val page: Int, val depth: Int)
data class Hit(val page: Int, val start: Int, val count: Int)

object Pdf {
    /** كل نداءات PDFium تمرّ عبر خيط واحد. */
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pdfium").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    val core: PdfiumCore by lazy {
        PdfiumCore(Store.app, Config(alreadyClosedBehavior = AlreadyClosedBehavior.IGNORE))
    }

    /** يفتح الملف؛ إن لم يكن قابلاً للقراءة العشوائية (مرفقات بعض التطبيقات) يُنسخ للذاكرة المؤقتة. */
    suspend fun open(ctx: Context, uri: Uri, password: String?): PdfDoc = withContext(Pdf.dispatcher) {
        val pfd = openPfd(ctx, uri)
        try {
            PdfDoc(core.newDocument(pfd, password), pfd)
        } catch (e: Exception) {
            runCatching { pfd.close() }
            if (e.javaClass.simpleName.contains("Password") || e.message?.contains("assword") == true)
                throw PasswordNeeded(password != null)
            throw e
        }
    }

    fun openPfd(ctx: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        val direct = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (direct != null && direct.statSize > 0) return direct
        runCatching { direct?.close() }
        val dir = File(ctx.cacheDir, "open").apply { mkdirs() }
        val f = File(dir, Store.key(uri.toString()) + ".pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

class PdfDoc(private val doc: PdfDocument, private val pfd: ParcelFileDescriptor) {
    val count: Int = doc.getPageCount()
    /** أبعاد الصفحات بالنقاط (72 dpi) بعد مراعاة التدوير. */
    val sizes: List<android.util.Size> = (0 until count).map { i -> runCatching { doc.openPage(i)!!.use { p -> p.getPageSize(72).let { android.util.Size(maxOf(1, it.width), maxOf(1, it.height)) } } }.getOrDefault(android.util.Size(612, 792)) }
    @Volatile var closed = false; private set

    private class Open(val page: PdfPage, var text: PdfTextPage? = null)

    private val open = object : LinkedHashMap<Int, Open>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Open>): Boolean {
            if (size > 6) {
                runCatching { eldest.value.text?.close(); eldest.value.page.close() }
                return true
            }
            return false
        }
    }
    private val textCache = HashMap<Int, String>()
    private val linkCache = HashMap<Int, List<PLink>>()

    private fun get(i: Int): Open = open.getOrPut(i) { Open(doc.openPage(i)!!) }
    private fun textPage(i: Int): PdfTextPage { val o = get(i); return o.text ?: o.page.openTextPage().also { o.text = it } }

    // مقياس كبير لتحويل الإحداثيات بدقة عبر دوال PDFium
    private fun bigW(i: Int) = sizes[i].width * 16
    private fun bigH(i: Int) = sizes[i].height * 16

    /** يرسم الصفحة كاملة بالحجم المطلوب. */
    suspend fun render(i: Int, w: Int, h: Int, need: () -> Boolean = { true }): Bitmap? = withContext(Pdf.dispatcher) {
        if (closed || w <= 0 || h <= 0 || !need()) return@withContext null
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            get(i).page.renderPageBitmap(bmp, 0, 0, w, h, renderAnnot = true, canvasColor = 0xFFFFFFFF.toInt())
            bmp
        }.getOrNull()
    }

    /** يرسم جزءاً من الصفحة مكبَّرة (للوضوح عند التكبير). */
    suspend fun renderRegion(i: Int, bw: Int, bh: Int, startX: Int, startY: Int, fullW: Int, fullH: Int): Bitmap? =
        withContext(Pdf.dispatcher) {
            if (closed || bw <= 0 || bh <= 0) return@withContext null
            runCatching {
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                get(i).page.renderPageBitmap(bmp, startX, startY, fullW, fullH, renderAnnot = true, canvasColor = 0xFFFFFFFF.toInt())
                bmp
            }.getOrNull()
        }

    suspend fun text(i: Int): String = withContext(Pdf.dispatcher) { textNow(i) }

    private fun textNow(i: Int): String {
        if (closed) return ""
        return textCache.getOrPut(i) {
            runCatching {
                val tp = textPage(i)
                val n = tp.textPageCountChars()
                if (n <= 0) "" else (tp.textPageGetText(0, n) ?: "")
            }.getOrDefault("")
        }
    }

    suspend fun charCount(i: Int): Int = withContext(Pdf.dispatcher) {
        if (closed) 0 else runCatching { textPage(i).textPageCountChars() }.getOrDefault(0)
    }

    /** رقم الحرف تحت النقطة (مطبَّعة)، أو -1. */
    suspend fun charAt(i: Int, nx: Float, ny: Float, tol: Double = 10.0): Int = withContext(Pdf.dispatcher) {
        if (closed) return@withContext -1
        runCatching {
            val o = get(i)
            val pt = o.page.mapDeviceCoordsToPage(0, 0, bigW(i), bigH(i), 0, (nx * bigW(i)).toInt(), (ny * bigH(i)).toInt())
            textPage(i).textPageGetCharIndexAtPos(pt.x.toDouble(), pt.y.toDouble(), tol, tol)
        }.getOrDefault(-1)
    }

    /** مستطيلات نطاق من الحروف (مطبَّعة). */
    suspend fun rects(i: Int, start: Int, count: Int): List<NRect> = withContext(Pdf.dispatcher) { rectsNow(i, start, count) }

    private fun rectsNow(i: Int, start: Int, count: Int): List<NRect> {
        if (closed || count <= 0) return emptyList()
        return runCatching {
            val tp = textPage(i)
            val n = tp.textPageCountRects(start, count)
            (0 until n).mapNotNull { k -> tp.textPageGetRect(k)?.let { toN(i, it) } }
        }.getOrDefault(emptyList())
    }

    private fun toN(i: Int, r: RectF): NRect {
        val d: Rect = get(i).page.mapRectToDevice(0, 0, bigW(i), bigH(i), 0, r)
        val w = bigW(i).toFloat(); val h = bigH(i).toFloat()
        return NRect(minOf(d.left, d.right) / w, minOf(d.top, d.bottom) / h, maxOf(d.left, d.right) / w, maxOf(d.top, d.bottom) / h)
    }

    /** نقطة مطبَّعة ← إحداثيات PDF (للحفظ داخل الملف). */
    suspend fun toPdfPoint(i: Int, nx: Float, ny: Float): Pair<Float, Float> = withContext(Pdf.dispatcher) {
        val p = get(i).page.mapDeviceCoordsToPage(0, 0, bigW(i), bigH(i), 0, (nx * bigW(i)).toInt(), (ny * bigH(i)).toInt())
        p.x to p.y
    }

    suspend fun links(i: Int): List<PLink> = withContext(Pdf.dispatcher) {
        if (closed) return@withContext emptyList()
        linkCache.getOrPut(i) {
            val out = ArrayList<PLink>()
            runCatching {
                get(i).page.getPageLinks().forEach { l ->
                    out += PLink(toN(i, RectF(l.bounds)), l.destPageIdx?.takeIf { it >= 0 }, l.uri?.takeIf { it.isNotBlank() })
                }
            }
            // روابط مكتوبة كنص (www… / https…)
            runCatching {
                textPage(i).loadWebLink()?.use { wl ->
                    for (k in 0 until wl.countWebLinks()) {
                        val url = wl.getURL(k, 2048) ?: continue
                        for (r in 0 until wl.countRects(k)) out += PLink(toN(i, wl.getRect(k, r)), null, url.trim().trimEnd('\u0000'))
                    }
                }
            }
            out
        }
    }

    suspend fun toc(): List<TocItem> = withContext(Pdf.dispatcher) {
        val out = ArrayList<TocItem>()
        fun walk(list: List<io.legere.pdfiumandroid.api.Bookmark>, d: Int) {
            list.forEach { b ->
                out += TocItem(b.title?.trim().orEmpty().ifEmpty { "—" }, b.pageIdx.toInt().coerceIn(0, count - 1), d)
                walk(b.children, d + 1)
            }
        }
        runCatching { walk(doc.getTableOfContents(), 0) }
        out
    }

    suspend fun meta(): Meta = withContext(Pdf.dispatcher) { runCatching { doc.getDocumentMeta() }.getOrDefault(Meta()) }

    /** بحث يتجاهل حالة الأحرف والتشكيل وأشكال الهمزة. */
    suspend fun search(query: String, page: Int): List<Hit> = withContext(Pdf.dispatcher) {
        val (nq, _) = normalize(query.trim())
        if (nq.isEmpty() || closed) return@withContext emptyList()
        val text = textNow(page)
        val (nt, map) = normalize(text)
        val out = ArrayList<Hit>()
        var from = 0
        while (true) {
            val k = nt.indexOf(nq, from)
            if (k < 0) break
            val s = map[k]
            val e = map[k + nq.length - 1] + 1
            out += Hit(page, s, e - s)
            from = k + maxOf(1, nq.length)
        }
        out
    }

    fun close() {
        if (closed) return
        closed = true
        kotlinx.coroutines.runBlocking(Pdf.dispatcher) {
            open.values.forEach { runCatching { it.text?.close(); it.page.close() } }
            open.clear()
            runCatching { doc.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        private val tashkeel = Regex("[ً-ٰٟـ]")

        /** يعيد النص المطبَّع وخريطة من موضع المطبَّع إلى موضع الأصل. */
        fun normalize(s: String): Pair<String, IntArray> {
            val sb = StringBuilder(s.length)
            val map = IntArray(s.length + 1)
            var n = 0
            for ((i, ch0) in s.withIndex()) {
                if (tashkeel.matches(ch0.toString())) continue
                val ch = when (ch0) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    '\r', '\n', '\t', ' ' -> ' '
                    else -> ch0.lowercaseChar()
                }
                if (ch == ' ' && n > 0 && sb[n - 1] == ' ') continue
                sb.append(ch); map[n] = i; n++
            }
            return sb.toString() to map
        }
    }
}
