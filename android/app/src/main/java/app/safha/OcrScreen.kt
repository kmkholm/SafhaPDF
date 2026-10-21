// صفحة — تحويل PDF المصوّر إلى نص (OCR) بالعربية والإنجليزية دون إنترنت (Tesseract، نماذج خفيفة 1.4 + 4.1 ميغا)
// برمجة: د. محمد توفيق
package app.safha

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object Ocr {
    /** ينسخ نماذج اللغات من داخل التطبيق إلى مجلد tessdata (مرة واحدة). */
    private fun dataDir(ctx: Context): File {
        val root = ctx.filesDir
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (lang in listOf("ara", "eng")) {
            val f = File(dir, "$lang.traineddata")
            val size = runCatching { ctx.assets.openFd("tessdata/$lang.traineddata").use { it.length } }.getOrDefault(-1L)
            if (!f.exists() || (size > 0 && f.length() != size)) {
                ctx.assets.open("tessdata/$lang.traineddata").use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
        return root
    }

    /** تجهيز الصورة: تصحيح الإضاءة (تبييض الورق) وحجم مناسب للتعرّف. */
    private fun prepare(src: Bitmap): Bitmap {
        val long = max(src.width, src.height)
        val k = when { long > 3200 -> 3200f / long; long < 1200 -> 2f; else -> 1f }
        val scaled = if (k != 1f) Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true) else src
        val out = Scan.filter(scaled, "gray")
        if (scaled != src) scaled.recycle()
        return out
    }

    /** langs: "ara+eng" أو "ara" أو "eng" */
    fun recognize(ctx: Context, src: Bitmap, langs: String): String {
        // صفحة مصوّرة بالهاتف (ورقة على طاولة)؟ نقصّ الورقة ونصحّح ميلانها أولاً
        val q = Scan.detect(src)
        val bmp = if (q != null && Scan.polyArea(q) < 0.9f) Scan.warp(src, q) else src
        try { return recognizeFlat(ctx, bmp, langs) } finally { if (bmp !== src) bmp.recycle() }
    }

    private fun recognizeFlat(ctx: Context, bmp: Bitmap, langs: String): String {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir(ctx).path, langs, TessBaseAPI.OEM_LSTM_ONLY)) error("OCR init failed")
            api.setVariable("preserve_interword_spaces", "1")
            val img = prepare(bmp)
            // نسختان إضافيتان من الصورة: الأصلية بالحجم نفسه، وأبيض/أسود. يرفض Tesseract أحياناً سطراً في نسخة
            // ويقرؤه في أخرى، فنأخذ لكل سطر القراءة الأكمل.
            val raw = Bitmap.createScaledBitmap(bmp, img.width, img.height, true).let { if (it === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else it }
            val bw = Scan.filter(raw, "bw")
            val variants = listOf(img, raw, bw)
            // التقسيم التلقائي في Tesseract يُسقط أحياناً أسطراً كاملة؛ لذلك نقسّم الصفحة إلى أسطر بأنفسنا
            // ونتعرّف على كل سطر وحده، ثم نقارن بنتيجة الصفحة كاملة ونأخذ الأكمل.
            val lines = segmentLines(img)
            val sb = StringBuilder()
            var lastBottom = -1
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            for ((top, bottom) in lines) {
                val pad = max(6, (bottom - top) / 3)
                val y0 = max(0, top - pad); val y1 = minOf(img.height, bottom + pad)
                var t = ""
                // نكبّر/نصغّر السطر لارتفاع ~70 بكسل، وهو الأنسب لنموذج التعرّف
                val k0 = (70f / (y1 - y0)).coerceIn(0.5f, 3f)
                // النسخة الأخيرة: الأصلية بتكبير أكبر (تنجح مع أسطر مثل «بسم الله الرحمن الرحيم»)
                for ((vi, v) in (variants + raw).withIndex()) {
                    val ks = if (vi == 3) k0 * 1.5f else k0
                    // createBitmap قد يعيد الصورة نفسها إن غطّى السطر الصفحة كلها؛ ننسخها حتى لا نُتلف الأصل
                    val crop0 = Bitmap.createBitmap(v, 0, y0, v.width, y1 - y0).let { if (it === v) v.copy(Bitmap.Config.ARGB_8888, false) else it }
                    val crop = if (abs(ks - 1f) > 0.1f) Bitmap.createScaledBitmap(crop0, (crop0.width * ks).roundToInt(), (crop0.height * ks).roundToInt(), true).also { crop0.recycle() } else crop0
                    val fr = framed(crop, 24); crop.recycle()
                    api.setImage(fr)
                    val r = (api.getUTF8Text() ?: "").trim()
                    fr.recycle()
                    if (r.count { !it.isWhitespace() } > t.count { !it.isWhitespace() }) t = r
                }
                if (t.isNotEmpty()) {
                    if (lastBottom >= 0 && top - lastBottom > (bottom - top) * 1.6f) sb.append('\n')   // فقرة جديدة
                    sb.append(t).append('\n'); lastBottom = bottom
                }
            }
            val byLine = clean(sb.toString())
            val f = framed(img, 40)
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(f)
            val full = clean(api.getUTF8Text() ?: "")
            f.recycle(); img.recycle(); raw.recycle(); bw.recycle()
            fun n(t: String) = t.count { !it.isWhitespace() }
            return if (n(byLine) >= n(full) * 0.9f) byLine else full
        } finally { api.recycle() }
    }

    private fun framed(src: Bitmap, pad: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out); cv.drawColor(android.graphics.Color.WHITE); cv.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** يقسّم الصفحة إلى أسطر بإسقاط الحبر أفقياً (مع ضمّ نقاط الحروف العربية لسطرها). */
    private fun segmentLines(img: Bitmap): List<Pair<Int, Int>> {
        val w = img.width; val h = img.height
        val px = IntArray(w * h); img.getPixels(px, 0, w, 0, 0, w, h)
        val ink = IntArray(h)
        for (y in 0 until h) {
            var n = 0; val o = y * w
            for (x in 0 until w) if ((px[o + x] and 0xFF) < 140) n++
            ink[y] = n
        }
        val thr = max(2, w / 400)
        val bands = ArrayList<IntArray>()
        var y = 0
        while (y < h) {
            if (ink[y] > thr) { val s0 = y; while (y < h && ink[y] > thr) y++; bands += intArrayOf(s0, y) } else y++
        }
        if (bands.isEmpty()) return emptyList()
        val med = bands.map { it[1] - it[0] }.sorted()[bands.size / 2].coerceAtLeast(4)
        // ضمّ الشرائط الرفيعة (نقاط، تشكيل) والقريبة جداً لأقرب سطر
        val merged = ArrayList<IntArray>()
        for (b in bands) {
            val last = merged.lastOrNull()
            val thin = b[1] - b[0] < med * 0.45f
            if (last != null && (b[0] - last[1] < med * 0.25f || (thin && b[0] - last[1] < med * 0.6f))) last[1] = b[1]
            else merged += b.copyOf()
        }
        val out = ArrayList<IntArray>()
        var k = 0
        while (k < merged.size) {
            val b = merged[k]
            // شريط رفيع فوق سطر (نقاط) يُضمّ للسطر التالي
            if (b[1] - b[0] < med * 0.45f && k + 1 < merged.size && merged[k + 1][0] - b[1] < med * 0.6f) { merged[k + 1][0] = b[0]; k++; continue }
            if (b[1] - b[0] >= 3) out += b
            k++
        }
        // شريط أطول من سطر بكثير = سطران متلاصقان (حروف نازلة/صاعدة)؛ نقسمه عند أقل كثافة حبر
        val med2 = out.map { it[1] - it[0] }.sorted().let { if (it.isEmpty()) med else it[it.size / 2] }.coerceAtLeast(4)
        val split = ArrayList<Pair<Int, Int>>()
        fun cut(a: Int, b: Int) {
            if (b - a <= med2 * 1.6f) { split += a to b; return }
            val from = a + (med2 * 0.6f).toInt(); val to = b - (med2 * 0.6f).toInt()
            if (to <= from) { split += a to b; return }
            var m = from
            for (yy in from..to) if (ink[yy] < ink[m]) m = yy
            cut(a, m); cut(m, b)
        }
        out.forEach { cut(it[0], it[1]) }
        return split
    }

    private val junkEnd = Regex("\\s+[\\p{P}\\p{S}|_~]{1,3}$")
    private val bidiMarks = Regex("[‎‏‪-‮]")

    /** ينظّف النص: علامات الاتجاه الخفية، والرموز الشاردة في نهايات الأسطر، والأسطر الفارغة الزائدة. */
    private fun clean(t: String) = t.replace(bidiMarks, "").lines().joinToString("\n") { it.trimEnd().replace(junkEnd, "") }
        .replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * شاشة «PDF مصوّر ← نص»: تستخرج النص من ملفات PDF الممسوحة/المصوّرة (صفحاتها صور) بالعربية والإنجليزية.
 * `initial` = صور صفحات جاهزة (من «استخراج النص» داخل القارئ).
 */
@Composable
fun OcrScreen(initial: List<File>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    var langs by remember { mutableStateOf("ara+eng") }
    var pdfName by remember { mutableStateOf<String?>(null) }
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    androidx.activity.compose.BackHandler { job?.cancel(); onBack() }

    /** يتعرّف على قائمة صور صفحات ويجمع النص. */
    suspend fun ocrImages(pages: List<Pair<Int, () -> Bitmap?>>) {
        val sb = StringBuilder()
        for ((k, pair) in pages.withIndex()) {
            val (no, get) = pair
            status = "${trS("صفحة", "Page")} $no"
            progress = k.toFloat() / pages.size
            val t = withContext(Dispatchers.Default) {
                runCatching { get()?.let { b -> Ocr.recognize(ctx, b, langs).also { b.recycle() } } ?: "" }.getOrElse { "⚠ " + (it.message ?: "error") }
            }
            if (pages.size > 1) sb.append("——— ").append(trS("صفحة", "Page")).append(' ').append(no).append(" ———\n")
            sb.append(t).append("\n\n")
            text = sb.toString().trim()
        }
        progress = 1f
    }

    fun runPdf() {
        val u = pdfUri ?: return
        job?.cancel()
        job = scope.launch {
            running = true; text = ""
            runCatching {
                val d = Pdf.open(ctx, u, null)
                try {
                    val pages = PdfOps.parseRange(range, d.count).ifEmpty { (0 until d.count).toList() }
                    ocrImages(pages.map { i ->
                        (i + 1) to {
                            kotlinx.coroutines.runBlocking {
                                // صفحة بدقة ~300 نقطة/بوصة للتعرّف
                                val s = d.sizes[i]; val k = minOf(3000f / max(s.width, s.height), 300f / 72f)
                                d.render(i, (s.width * k).roundToInt(), (s.height * k).roundToInt())
                            }
                        }
                    })
                } finally { d.close() }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) ctx.toast(if (it is PasswordNeeded) trS("الملف محمي بكلمة مرور؛ أزل الحماية أولاً من الأدوات", "The file is password protected; remove the password first") else (it.message ?: "Error")) }
            running = false
            if (text.isBlank() && progress >= 1f) ctx.toast(trS("لم يُعثر على نص واضح", "No clear text found"))
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        pdfUri = u; pdfName = ctx.displayName(u); text = ""
        scope.launch { pageCount = runCatching { Pdf.open(ctx, u, null).let { d -> d.count.also { d.close() } } }.getOrDefault(0) }
    }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { out ->
        if (out != null) runCatching { ctx.contentResolver.openOutputStream(out, "wt")!!.use { it.write(text.toByteArray()) }; ctx.toast(trS("حُفظ النص", "Text saved")) }
    }

    // صور جاهزة من القارئ (صفحة واحدة)
    LaunchedEffect(Unit) {
        if (initial.isNotEmpty()) {
            running = true
            job = scope.launch { ocrImages(initial.mapIndexed { i, f -> (i + 1) to { Scan.load(f, 3300) } }); running = false }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { job?.cancel(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Icon(Icons.Default.TextFields, null, tint = c.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("PDF مصوّر ← نص", "Scanned PDF → Text"), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(tr("يستخرج النص من ملفات PDF الممسوحة · عربي وإنجليزي · دون إنترنت", "Extracts text from scanned PDFs · Arabic & English · offline"), fontSize = 12.sp, color = c.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (initial.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                        .clickable(enabled = !running) { pick.launch(arrayOf("application/pdf")) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PictureAsPdf, null, Modifier.size(36.dp), tint = c.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pdfName ?: tr("اختر ملف PDF مصوّر", "Choose a scanned PDF"), fontWeight = FontWeight.SemiBold, maxLines = 2)
                        if (pageCount > 0) Text("$pageCount ${tr("صفحة", "pages")}", fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                    Text(if (pdfUri == null) tr("اختيار", "Choose") else tr("تغيير", "Change"), color = c.primary, fontSize = 13.sp)
                }
                Text(tr("لغة النص", "Text language"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                ChoiceChips(listOf("ara+eng" to tr("عربي + English", "Arabic + English"), "ara" to "العربية", "eng" to "English"), langs) { langs = it }
                if (pageCount > 1) OutlinedTextField(
                    range, { range = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("الصفحات (اتركه فارغاً لكل الصفحات)", "Pages (empty = all)")) }, placeholder = { Text("1-3, 5") },
                )
                Button(
                    onClick = { runPdf() }, enabled = pdfUri != null && !running,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(50.dp), shape = RoundedCornerShape(14.dp),
                ) { Icon(Icons.Default.TextFields, null); Spacer(Modifier.width(8.dp)); Text(tr("استخراج النص", "Extract text"), fontWeight = FontWeight.Bold) }
            }
            if (running) Column(Modifier.padding(vertical = 10.dp)) {
                LinearProgressIndicator(progress = { progress.coerceIn(0.03f, 1f) }, Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("جارٍ استخراج النص…", "Extracting text…") + " " + status, Modifier.weight(1f))
                    TextButton(onClick = { job?.cancel(); running = false }) { Text(tr("إيقاف", "Stop")) }
                }
            }
            if (text.isNotEmpty()) {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(vertical = 8.dp),
                    label = { Text(tr("النص المستخرج (يمكن تعديله)", "Extracted text (editable)")) },
                )
                Text("${text.length} ${tr("حرف", "characters")}", fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            if (initial.isEmpty() && text.isEmpty() && !running) Text(
                tr("ملاحظة: إن كان الملف يحتوي نصاً حقيقياً (غير مصوّر) فاستخدم «استخراج النص (TXT)» من القارئ؛ فهو أسرع وأدق.",
                    "Tip: if the PDF already has real (selectable) text, use \"Extract text (TXT)\" in the reader — it is faster and exact."),
                fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (text.isNotEmpty() && !running) Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarButton(Icons.Default.ContentCopy, tr("نسخ", "Copy"), modifier = Modifier.weight(1f)) { ctx.copyText(text) }
            BarButton(Icons.Default.Share, tr("مشاركة", "Share"), modifier = Modifier.weight(1f)) { ctx.shareText(text) }
            BarButton(Icons.Default.Save, tr("حفظ TXT", "Save TXT"), modifier = Modifier.weight(1f)) { saveTxt.launch((pdfName?.removeSuffix(".pdf") ?: "text") + ".txt") }
            BarButton(Icons.Default.Translate, tr("ترجمة", "Translate"), modifier = Modifier.weight(1f)) { translate(ctx, text) }
        }
    }
}
