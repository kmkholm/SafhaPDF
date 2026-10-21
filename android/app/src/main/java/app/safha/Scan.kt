// صفحة — محرّك الماسح الضوئي: كشف حواف الورقة، تصحيح المنظور، فلاتر التحسين، تصدير PDF
// برمجة: د. محمد توفيق
package app.safha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** صفحة ممسوحة: الصورة الأصلية + زوايا الورقة (مطبَّعة) + التدوير + الفلتر. */
data class ScanPage(
    val id: Long,
    val original: File,
    val quad: FloatArray,          // TLx,TLy, TRx,TRy, BRx,BRy, BLx,BLy  (0..1)
    val rotation: Int = 0,
    val filter: String = "magic",
    val processed: File? = null,
    val version: Int = 0,
)

object ScanFilters {
    val all = listOf("original", "magic", "gray", "bw", "light")
}

object Scan {
    const val MAX_SIDE = 3300

    val FULL = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    fun inset(k: Float = 0.06f) = floatArrayOf(k, k, 1 - k, k, 1 - k, 1 - k, k, 1 - k)

    fun dir(ctx: Context): File = File(ctx.cacheDir, "scan").apply { mkdirs() }

    // ---------------- تحميل الصور ----------------
    /** يقرأ الصورة مصغّرة بأمان ويطبّق اتجاه EXIF. */
    fun load(file: File, maxSide: Int = MAX_SIDE): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        var out = if (rot != 0) Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it != b) b.recycle() } else b
        val k = max(out.width, out.height).toFloat() / maxSide
        if (k > 1f) out = Bitmap.createScaledBitmap(out, (out.width / k).roundToInt(), (out.height / k).roundToInt(), true)
        out
    }.getOrNull()

    /** ينسخ صورة من المعرض إلى ملف عمل مع تثبيت الاتجاه. */
    fun importImage(ctx: Context, uri: Uri): File? = runCatching {
        val raw = File(dir(ctx), "raw_${System.nanoTime()}.jpg")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> raw.outputStream().use { i.copyTo(it) } }
        val bmp = load(raw) ?: return null
        val f = File(dir(ctx), "img_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        raw.delete(); bmp.recycle()
        f
    }.getOrNull()

    /** يحفظ الصورة الملتقطة بعد تطبيق اتجاه EXIF. */
    fun normalizeCapture(file: File): File {
        val bmp = load(file) ?: return file
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return file
    }

    // ---------------- كشف الورقة ----------------
    fun detect(bmp: Bitmap): FloatArray? {
        val k = 256f / max(bmp.width, bmp.height)
        val w = max(8, (bmp.width * k).roundToInt()); val h = max(8, (bmp.height * k).roundToInt())
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        if (s != bmp) s.recycle()
        val g = IntArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        return detectGray(g, w, h)
    }

    /** يكشف أكبر شكل رباعي فاتح (الورقة) في صورة رمادية صغيرة. يعيد الزوايا مطبَّعة أو null. */
    fun detectGray(g0: IntArray, w: Int, h: Int): FloatArray? {
        val g = boxBlur(boxBlur(g0, w, h, 2), w, h, 2)
        val t = otsu(g)
        var best: FloatArray? = null
        var bestScore = 0f
        for (bright in booleanArrayOf(true)) {   // الورقة أفتح من الخلفية؛ الكشف المعكوس كان يلتقط كتل النص خطأً
            var bin = BooleanArray(w * h) { if (bright) g[it] > t else g[it] < t }
            bin = erode(dilate(bin, w, h), w, h)
            val (comp, area) = largest(bin, w, h) ?: continue
            val frac = area.toFloat() / (w * h)
            if (frac < 0.12f || frac > 0.985f) continue
            var tl = 0; var tr = 0; var br = 0; var bl = 0
            var vTl = Int.MAX_VALUE; var vBr = Int.MIN_VALUE; var vTr = Int.MIN_VALUE; var vBl = Int.MAX_VALUE
            for (i in comp.indices) if (comp[i]) {
                val x = i % w; val y = i / w
                if (x + y < vTl) { vTl = x + y; tl = i }
                if (x + y > vBr) { vBr = x + y; br = i }
                if (x - y > vTr) { vTr = x - y; tr = i }
                if (x - y < vBl) { vBl = x - y; bl = i }
            }
            val q = floatArrayOf(
                (tl % w).toFloat(), (tl / w).toFloat(), (tr % w).toFloat(), (tr / w).toFloat(),
                (br % w).toFloat(), (br / w).toFloat(), (bl % w).toFloat(), (bl / w).toFloat(),
            )
            if (!convex(q)) continue
            val qa = polyArea(q)
            val fill = area / max(1f, qa)                 // قرب الشكل من رباعي حقيقي
            if (fill < 0.82f || qa < 0.12f * w * h) continue
            // الورقة التي تملأ الصورة كلها لا تُعتبر كشفاً
            val touches = listOf(q[0] < 2 && q[1] < 2, q[2] > w - 3 && q[3] < 2, q[4] > w - 3 && q[5] > h - 3, q[6] < 2 && q[7] > h - 3).count { it }
            if (touches >= 3) continue
            val score = fill * (qa / (w * h)) * (if (bright) 1.15f else 1f)
            if (score > bestScore) {
                bestScore = score
                best = FloatArray(8) { if (it % 2 == 0) (q[it] + 0.5f) / w else (q[it] + 0.5f) / h }
            }
        }
        return best?.let { expand(it, -0.004f) }
    }

    private fun expand(q: FloatArray, k: Float): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        return FloatArray(8) { i -> val c = if (i % 2 == 0) cx else cy; (q[i] + (q[i] - c) * k * 4).coerceIn(0f, 1f) }
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size); val out = IntArray(a.size)
        for (y in 0 until h) {
            var s = 0; var n = 0
            for (x in -r..r) if (x in 0 until w) { s += a[y * w + x]; n++ }
            for (x in 0 until w) {
                tmp[y * w + x] = s / n
                val o = x - r; val i = x + r + 1
                if (o >= 0) { s -= a[y * w + o]; n-- }
                if (i < w) { s += a[y * w + i]; n++ }
            }
        }
        for (x in 0 until w) {
            var s = 0; var n = 0
            for (y in -r..r) if (y in 0 until h) { s += tmp[y * w + x]; n++ }
            for (y in 0 until h) {
                out[y * w + x] = s / n
                val o = y - r; val i = y + r + 1
                if (o >= 0) { s -= tmp[o * w + x]; n-- }
                if (i < h) { s += tmp[i * w + x]; n++ }
            }
        }
        return out
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256); g.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = g.size; var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = 127
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; t = i }
        }
        return t
    }

    private fun dilate(b: BooleanArray, w: Int, h: Int): BooleanArray = BooleanArray(b.size) { i ->
        val x = i % w; val y = i / w
        b[i] || (x > 0 && b[i - 1]) || (x < w - 1 && b[i + 1]) || (y > 0 && b[i - w]) || (y < h - 1 && b[i + w])
    }

    private fun erode(b: BooleanArray, w: Int, h: Int): BooleanArray = BooleanArray(b.size) { i ->
        val x = i % w; val y = i / w
        b[i] && (x == 0 || b[i - 1]) && (x == w - 1 || b[i + 1]) && (y == 0 || b[i - w]) && (y == h - 1 || b[i + w])
    }

    private fun largest(b: BooleanArray, w: Int, h: Int): Pair<BooleanArray, Int>? {
        val label = IntArray(b.size)
        var bestL = 0; var bestN = 0; var cur = 0
        val stack = IntArray(b.size)
        for (s in b.indices) {
            if (!b[s] || label[s] != 0) continue
            cur++; var sp = 0; stack[sp++] = s; label[s] = cur; var n = 0
            while (sp > 0) {
                val i = stack[--sp]; n++
                val x = i % w; val y = i / w
                if (x > 0 && b[i - 1] && label[i - 1] == 0) { label[i - 1] = cur; stack[sp++] = i - 1 }
                if (x < w - 1 && b[i + 1] && label[i + 1] == 0) { label[i + 1] = cur; stack[sp++] = i + 1 }
                if (y > 0 && b[i - w] && label[i - w] == 0) { label[i - w] = cur; stack[sp++] = i - w }
                if (y < h - 1 && b[i + w] && label[i + w] == 0) { label[i + w] = cur; stack[sp++] = i + w }
            }
            if (n > bestN) { bestN = n; bestL = cur }
        }
        if (bestL == 0) return null
        return BooleanArray(b.size) { label[it] == bestL } to bestN
    }

    fun polyArea(q: FloatArray): Float {
        var a = 0f
        for (k in 0 until 4) { val j = (k + 1) % 4; a += q[2 * k] * q[2 * j + 1] - q[2 * j] * q[2 * k + 1] }
        return abs(a) / 2
    }

    fun convex(q: FloatArray): Boolean {
        var sign = 0
        for (k in 0 until 4) {
            val ax = q[2 * ((k + 1) % 4)] - q[2 * k]; val ay = q[2 * ((k + 1) % 4) + 1] - q[2 * k + 1]
            val bx = q[2 * ((k + 2) % 4)] - q[2 * ((k + 1) % 4)]; val by = q[2 * ((k + 2) % 4) + 1] - q[2 * ((k + 1) % 4) + 1]
            val cr = ax * by - ay * bx
            val s = if (cr > 0) 1 else if (cr < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return sign != 0
    }

    // ---------------- تصحيح المنظور ----------------
    fun warp(src: Bitmap, q: FloatArray): Bitmap {
        val p = FloatArray(8) { if (it % 2 == 0) q[it] * src.width else q[it] * src.height }
        val top = hypot(p[2] - p[0], p[3] - p[1]); val bottom = hypot(p[4] - p[6], p[5] - p[7])
        val left = hypot(p[6] - p[0], p[7] - p[1]); val right = hypot(p[4] - p[2], p[5] - p[3])
        var ow = max(top, bottom); var oh = max(left, right)
        val k = MAX_SIDE / max(ow, oh); if (k < 1f) { ow *= k; oh *= k }
        val w = max(16, ow.roundToInt()); val h = max(16, oh.roundToInt())
        val m = Matrix()
        m.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        return out
    }

    fun rotate(b: Bitmap, deg: Int): Bitmap =
        if (deg % 360 == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    // ---------------- الفلاتر ----------------
    /** لون الورق المحلي (تُزال الكتابة بتوسيع القيم الفاتحة ثم تنعيم) — أساس «التحسين السحري». */
    private fun paperColor(src: Bitmap): IntArray {
        val w = src.width; val h = src.height
        val sw = max(24, w / 12); val sh = max(24, h / 12)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        var px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh); small.recycle()
        repeat(3) { px = maxFilter(px, sw, sh) }
        val rr = boxBlur(IntArray(px.size) { px[it] shr 16 and 255 }, sw, sh, 3)
        val gg = boxBlur(IntArray(px.size) { px[it] shr 8 and 255 }, sw, sh, 3)
        val bb = boxBlur(IntArray(px.size) { px[it] and 255 }, sw, sh, 3)
        val sm = Bitmap.createBitmap(IntArray(px.size) { (0xFF shl 24) or (rr[it] shl 16) or (gg[it] shl 8) or bb[it] }, sw, sh, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(sm, w, h, true); sm.recycle()
        val out = IntArray(w * h); big.getPixels(out, 0, w, 0, 0, w, h); big.recycle()
        return out
    }

    private fun maxFilter(a: IntArray, w: Int, h: Int): IntArray = IntArray(a.size) { i ->
        val x = i % w; val y = i / w
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
            val c = a[yy * w + xx]
            val lum = (c shr 16 and 255) + (c shr 8 and 255) + (c and 255)
            if (lum > r + g + b) { r = c shr 16 and 255; g = c shr 8 and 255; b = c and 255 }
        }
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun filter(src: Bitmap, f: String): Bitmap {
        if (f == "original") return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        if (f == "light") {
            for (i in px.indices) {
                val c = px[i]
                fun ch(v: Int) = ((v - 128) * 1.12f + 128 + 28).roundToInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
            }
        } else {
            val bg = paperColor(src)
            for (i in px.indices) {
                val c = px[i]; val b = bg[i]
                // قسمة على لون الورق ⇒ خلفية بيضاء نظيفة وإزالة الظلال
                var r = (c shr 16 and 255) * 255f / max(40, b shr 16 and 255)
                var g = (c shr 8 and 255) * 255f / max(40, b shr 8 and 255)
                var bl = (c and 255) * 255f / max(40, b and 255)
                when (f) {
                    "magic" -> {
                        val l = (r + g + bl) / 3
                        r = l + (r - l) * 1.35f; g = l + (g - l) * 1.35f; bl = l + (bl - l) * 1.35f   // ألوان أنصع
                        r = curve(r); g = curve(g); bl = curve(bl)
                    }
                    "gray" -> { val l = curve(0.299f * r + 0.587f * g + 0.114f * bl); r = l; g = l; bl = l }
                    "bw" -> {
                        val l = 0.299f * r + 0.587f * g + 0.114f * bl
                        val v = ((l - 150f) / 55f).coerceIn(0f, 1f)          // عتبة ناعمة: حروف حادة بلا تشويش
                        val o = 255f * v * v * (3 - 2 * v)
                        r = o; g = o; bl = o
                    }
                }
                px[i] = (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or bl.roundToInt().coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** منحنى تباين: يبيّض ما فوق 225 ويقوّي الحبر. */
    private fun curve(v: Float): Float {
        val x = (v / 255f).coerceIn(0f, 1f)
        val y = if (x > 0.88f) 1f else ((x / 0.88f).let { it * it * (3 - 2 * it) } * 0.94f + (x / 0.88f) * 0.06f)
        return y * 255f
    }

    /** يعالج صفحة كاملة ويحفظها JPEG. */
    fun process(ctx: Context, p: ScanPage): ScanPage {
        val src = load(p.original) ?: return p
        val warped = warp(src, p.quad); src.recycle()
        val rot = rotate(warped, p.rotation); if (rot != warped) warped.recycle()
        val out = filter(rot, p.filter); rot.recycle()
        val f = File(dir(ctx), "page_${p.id}_${System.nanoTime()}.jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (p.filter == "bw") 80 else 88, it) }
        out.recycle()
        p.processed?.delete()
        return p.copy(processed = f, version = p.version + 1)
    }

    /** ينشئ PDF من صفحات معالجة (الصور JPEG تُضمَّن كما هي فيبقى الحجم صغيراً). */
    fun exportPdf(ctx: Context, pages: List<ScanPage>, size: String, out: Uri) {
        PDDocument().use { d ->
            for (p in pages) {
                val f = p.processed ?: continue
                val img = f.inputStream().use { JPEGFactory.createFromStream(d, it) }
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val box = when (size) {
                    "a4" -> if (iw > ih) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    "letter" -> if (iw > ih) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
                    else -> { val k = 595f / min(iw, ih); PDRectangle(iw * k, ih * k) }
                }
                val page = PDPage(box); d.addPage(page)
                val k = min(box.width / iw, box.height / ih)
                val w = iw * k; val h = ih * k
                PDPageContentStream(d, page).use { it.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
            }
            PdfOps.save(ctx, d, out)
        }
    }
}
