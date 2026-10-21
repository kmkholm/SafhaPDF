// صفحة — عمليات التحرير (PDFBox): دمج، تقسيم، ترتيب، صور، حماية، ضغط، ترقيم، علامة مائية، حفظ التعليقات
// برمجة: د. محمد توفيق
package app.safha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.io.File
import kotlin.math.max
import kotlin.math.min

class NeedPassword : Exception()

object PdfOps {
    private fun mem(ctx: Context) = MemoryUsageSetting.setupMixed(64L * 1024 * 1024).setTempDir(ctx.cacheDir)

    fun load(ctx: Context, uri: Uri, password: String? = null): PDDocument {
        try {
            return ctx.contentResolver.openInputStream(uri)!!.use { PDDocument.load(it, password ?: "", mem(ctx)) }
        } catch (e: InvalidPasswordException) {
            throw NeedPassword()
        }
    }

    fun save(ctx: Context, doc: PDDocument, out: Uri) {
        // نكتب لملف مؤقت أولاً حتى لا نُتلف الوجهة إن فشل الحفظ
        val tmp = File.createTempFile("out", ".pdf", ctx.cacheDir)
        try {
            tmp.outputStream().buffered().use { doc.save(it) }
            ctx.contentResolver.openOutputStream(out, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        } finally { tmp.delete() }
    }

    fun merge(ctx: Context, inputs: List<Uri>, out: Uri, progress: (Float) -> Unit) {
        val m = PDFMergerUtility()
        val streams = inputs.map { ctx.contentResolver.openInputStream(it)!! }
        try {
            streams.forEach { m.addSource(it) }
            val tmp = File.createTempFile("merge", ".pdf", ctx.cacheDir)
            try {
                tmp.outputStream().buffered().use { o ->
                    m.destinationStream = o
                    progress(0.3f)
                    m.mergeDocuments(mem(ctx))
                }
                ctx.contentResolver.openOutputStream(out, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            } finally { tmp.delete() }
        } finally { streams.forEach { runCatching { it.close() } } }
        progress(1f)
    }

    /** صفحات مختارة إلى ملف جديد (pages بترقيم يبدأ من 0)، مع تدوير إضافي اختياري لكل صفحة. */
    fun extract(ctx: Context, src: Uri, password: String?, pages: List<Pair<Int, Int>>, out: Uri) {
        load(ctx, src, password).use { d ->
            PDDocument().use { o ->
                for ((i, rot) in pages) {
                    val p = o.importPage(d.getPage(i))
                    if (rot != 0) p.rotation = ((p.rotation + rot) % 360 + 360) % 360
                }
                save(ctx, o, out)
            }
        }
    }

    /** كل صفحة (أو كل مجموعة) في ملف مستقل داخل مجلد. */
    fun splitEvery(ctx: Context, src: Uri, password: String?, every: Int, tree: Uri, baseName: String, progress: (Float) -> Unit): Int {
        val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var files = 0
        load(ctx, src, password).use { d ->
            val n = d.numberOfPages
            var i = 0
            while (i < n) {
                PDDocument().use { o ->
                    for (k in i until min(n, i + every)) o.importPage(d.getPage(k))
                    val name = if (every == 1) "${baseName}_${i + 1}.pdf" else "${baseName}_${i + 1}-${min(n, i + every)}.pdf"
                    val u = DocumentsContract.createDocument(ctx.contentResolver, dirDoc, "application/pdf", name)!!
                    save(ctx, o, u)
                    files++
                }
                i += every
                progress(i.toFloat() / n)
            }
        }
        return files
    }

    fun imagesToPdf(ctx: Context, images: List<Uri>, a4: Boolean, out: Uri, progress: (Float) -> Unit) {
        PDDocument().use { d ->
            images.forEachIndexed { idx, u ->
                val bmp = decodeImage(ctx, u, 2400) ?: return@forEachIndexed
                val img = JPEGFactory.createFromImage(d, bmp, 0.88f)
                val page = if (a4) {
                    val land = bmp.width > bmp.height
                    PDPage(if (land) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4)
                } else PDPage(PDRectangle(bmp.width * 0.75f, bmp.height * 0.75f))
                d.addPage(page)
                val box = page.mediaBox
                val margin = if (a4) 18f else 0f
                val f = min((box.width - 2 * margin) / bmp.width, (box.height - 2 * margin) / bmp.height)
                val w = bmp.width * f; val h = bmp.height * f
                PDPageContentStream(d, page).use { cs -> cs.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
                bmp.recycle()
                progress((idx + 1f) / images.size)
            }
            save(ctx, d, out)
        }
    }

    fun decodeImage(ctx: Context, u: Uri, maxDim: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, u)) { dec, info, _ ->
                val s = info.size
                val k = max(s.width, s.height).toFloat() / maxDim
                if (k > 1f) dec.setTargetSize((s.width / k).toInt(), (s.height / k).toInt())
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > maxDim * 2) sample *= 2
            ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()

    fun protect(ctx: Context, src: Uri, oldPw: String?, newPw: String, out: Uri) {
        load(ctx, src, oldPw).use { d ->
            d.isAllSecurityToBeRemoved = true
            val p = StandardProtectionPolicy(newPw, newPw, AccessPermission())
            p.encryptionKeyLength = 256
            d.protect(p)
            save(ctx, d, out)
        }
    }

    fun unlock(ctx: Context, src: Uri, pw: String, out: Uri) {
        load(ctx, src, pw).use { d ->
            d.isAllSecurityToBeRemoved = true
            save(ctx, d, out)
        }
    }

    /** يعيد ترميز الصور الكبيرة بجودة أقل. */
    fun compress(ctx: Context, src: Uri, password: String?, quality: Float, maxDim: Int, out: Uri, progress: (Float) -> Unit) {
        load(ctx, src, password).use { d ->
            d.isAllSecurityToBeRemoved = password != null
            val done = HashMap<Any, PDImageXObject>()
            val n = d.numberOfPages
            d.pages.forEachIndexed { pi, page ->
                val res = page.resources ?: return@forEachIndexed
                for (name in res.xObjectNames.toList()) {
                    runCatching {
                        val x = res.getXObject(name)
                        if (x is PDImageXObject) {
                            val key = x.cosObject
                            val repl = done[key] ?: run {
                                if (x.cosObject.containsKey(COSName.SMASK) || x.isStencil || x.cosObject.containsKey(COSName.MASK)) return@runCatching
                                val bmp0 = x.image ?: return@runCatching
                                val k = max(bmp0.width, bmp0.height).toFloat() / maxDim
                                val bmp = if (k > 1f) Bitmap.createScaledBitmap(bmp0, (bmp0.width / k).toInt(), (bmp0.height / k).toInt(), true) else bmp0
                                JPEGFactory.createFromImage(d, bmp, quality).also { done[key] = it }
                            }
                            res.put(name, repl)
                        }
                    }
                }
                progress((pi + 1f) / n)
            }
            save(ctx, d, out)
        }
    }

    /** ترقيم الصفحات: pos = 0 أسفل الوسط، 1 أسفل اليمين، 2 أسفل اليسار، 3 أعلى الوسط */
    fun pageNumbers(ctx: Context, src: Uri, password: String?, pos: Int, withTotal: Boolean, out: Uri) {
        load(ctx, src, password).use { d ->
            d.isAllSecurityToBeRemoved = password != null
            val n = d.numberOfPages
            val font = PDType1Font.HELVETICA
            d.pages.forEachIndexed { i, page ->
                val box = page.cropBox
                val label = if (withTotal) "${i + 1} / $n" else "${i + 1}"
                val size = (min(box.width, box.height) / 55f).coerceIn(8f, 14f)
                val tw = font.getStringWidth(label) / 1000f * size
                val x = when (pos) { 1 -> box.upperRightX - 36 - tw; 2 -> box.lowerLeftX + 36; else -> box.lowerLeftX + (box.width - tw) / 2 }
                val y = if (pos == 3) box.upperRightY - 24 else box.lowerLeftY + 14
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText(); cs.setFont(font, size)
                    cs.setNonStrokingColor(60, 60, 60)
                    cs.newLineAtOffset(x, y); cs.showText(label); cs.endText()
                }
            }
            save(ctx, d, out)
        }
    }

    /** علامة مائية نصية (أي لغة، تُرسم كصورة شفافة حتى تتشكّل العربية صحيحاً). */
    fun watermark(ctx: Context, src: Uri, password: String?, text: String, color: Int, opacity: Float, out: Uri) {
        val bmp = textBitmap(text, color)
        load(ctx, src, password).use { d ->
            d.isAllSecurityToBeRemoved = password != null
            val img = LosslessFactory.createFromImage(d, bmp)
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity; strokingAlphaConstant = opacity }
            d.pages.forEach { page ->
                val box = page.cropBox
                val w = box.width * 0.8f
                val h = w * bmp.height / bmp.width
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(gs)
                    cs.saveGraphicsState()
                    val cx = box.lowerLeftX + box.width / 2; val cy = box.lowerLeftY + box.height / 2
                    cs.transform(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(35.0), cx, cy))
                    cs.drawImage(img, -w / 2, -h / 2, w, h)
                    cs.restoreGraphicsState()
                }
            }
            save(ctx, d, out)
        }
    }

    private fun textBitmap(text: String, color: Int): Bitmap {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 140f; typeface = Typeface.DEFAULT_BOLD }
        val w = (p.measureText(text) + 40).toInt().coerceIn(200, 4000)
        val lay = StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val bmp = Bitmap.createBitmap(w, lay.height + 20, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.translate(0f, 10f); lay.draw(c)
        return bmp
    }

    /** يكتب التعليقات داخل نسخة PDF كتعليقات حقيقية قابلة للتحرير في أي قارئ. */
    suspend fun saveAnnotations(
        ctx: Context, src: Uri, password: String?, annots: List<Annot>, view: PdfDoc, out: Uri,
    ) {
        // نحسب الإحداثيات بمحرّك العرض نفسه (يراعي التدوير وحدود الصفحة)
        data class Conv(val a: Annot, val rects: List<FloatArray>, val strokes: List<FloatArray>, val pageW: Float)
        val conv = annots.map { a ->
            val rs = a.rects.map { r ->
                val (x1, y1) = view.toPdfPoint(a.page, r.l, r.t); val (x2, y2) = view.toPdfPoint(a.page, r.r, r.b)
                floatArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
            }
            val ss = a.strokes.map { st ->
                val arr = FloatArray(st.size * 2)
                st.forEachIndexed { k, p -> val (x, y) = view.toPdfPoint(a.page, p.x, p.y); arr[2 * k] = x; arr[2 * k + 1] = y }
                arr
            }
            Conv(a, rs, ss, view.sizes[a.page].width.toFloat())
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            load(ctx, src, password).use { d ->
                if (password != null) d.isAllSecurityToBeRemoved = true
                for (c in conv) runCatching {
                    val a = c.a
                    val page = d.getPage(a.page)
                    val col = PDColor(floatArrayOf(((a.color shr 16) and 255) / 255f, ((a.color shr 8) and 255) / 255f, (a.color and 255) / 255f), PDDeviceRGB.INSTANCE)
                    val alpha = ((a.color ushr 24) and 255) / 255f
                    when (a.type) {
                        AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKE -> {
                            val sub = when (a.type) {
                                AnnotType.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                                AnnotType.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                                else -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                            }
                            val m = PDAnnotationTextMarkup(sub)
                            val q = FloatArray(c.rects.size * 8)
                            c.rects.forEachIndexed { k, r ->
                                // أعلى يسار، أعلى يمين، أسفل يسار، أسفل يمين
                                floatArrayOf(r[0], r[3], r[2], r[3], r[0], r[1], r[2], r[1]).copyInto(q, k * 8)
                            }
                            m.quadPoints = q
                            m.rectangle = bounds(c.rects.flatMap { listOf(it[0], it[1], it[2], it[3]) }.toFloatArray(), 1f)
                            m.color = col
                            m.constantOpacity = 1f
                            m.creationDate = java.util.Calendar.getInstance()
                            m.titlePopup = "Safha PDF"
                            m.constructAppearances(d)
                            page.annotations.add(m)
                        }
                        AnnotType.INK -> {
                            val ink = PDAnnotationMarkup()
                            ink.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)
                            ink.inkList = c.strokes.toTypedArray()
                            val bw = a.width * c.pageW
                            ink.borderStyle = PDBorderStyleDictionary().apply { width = bw }
                            ink.color = col
                            ink.constantOpacity = if (alpha < 0.99f) alpha else 1f
                            ink.rectangle = bounds(c.strokes.fold(FloatArray(0)) { acc, f -> acc + f }.let { pts ->
                                val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }; val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
                                floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                            }, bw + 2f)
                            ink.titlePopup = "Safha PDF"
                            ink.constructAppearances(d)
                            page.annotations.add(ink)
                        }
                        AnnotType.NOTE -> {
                            val r = c.rects.first()
                            val t = PDAnnotationText()
                            t.contents = a.text
                            t.rectangle = PDRectangle(r[0] - 10, r[3] - 10, 20f, 20f)
                            t.color = col
                            t.titlePopup = "Safha PDF"
                            t.name = PDAnnotationText.NAME_COMMENT
                            t.constructAppearances(d)
                            page.annotations.add(t)
                        }
                    }
                }
                save(ctx, d, out)
            }
        }
    }

    private fun bounds(v: FloatArray, pad: Float): PDRectangle {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var i = 0
        while (i + 3 < v.size) { x0 = min(x0, v[i]); y0 = min(y0, v[i + 1]); x1 = max(x1, v[i + 2]); y1 = max(y1, v[i + 3]); i += 4 }
        return PDRectangle(x0 - pad, y0 - pad, (x1 - x0) + 2 * pad, (y1 - y0) + 2 * pad)
    }

    /** يحلّل نطاقاً مثل "1-3, 5, 8-10" إلى أرقام صفحات تبدأ من 0. */
    fun parseRange(s: String, n: Int): List<Int> {
        val out = ArrayList<Int>()
        val clean = s.replace('،', ',').replace('–', '-').replace('—', '-')
            .map { c -> if (c in '٠'..'٩') ('0' + (c - '٠')) else c }.joinToString("")
        for (part in clean.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val ab = part.split('-').map { it.trim() }
            val a = ab[0].toIntOrNull() ?: continue
            val b = if (ab.size > 1) (ab[1].toIntOrNull() ?: n) else a
            val (lo, hi) = if (a <= b) a to b else b to a
            for (p in lo..hi) if (p in 1..n) out += p - 1
        }
        return out
    }
}
