// صفحة — شاشات الأدوات: دمج، تقسيم، استخراج، ترتيب الصفحات، صور←PDF، حماية، فك الحماية، ضغط، ترقيم، علامة مائية، PDF←صور/نص
// برمجة: د. محمد توفيق
package app.safha

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class ToolKind(val icon: ImageVector, val tint: Long, val ar: String, val en: String, val dar: String, val den: String) {
    MERGE(Icons.Default.CallMerge, 0xFFE8562A, "دمج ملفات", "Merge PDFs", "اجمع عدة ملفات في ملف واحد", "Combine several PDFs into one"),
    SPLIT(Icons.Default.CallSplit, 0xFF0F766E, "تقسيم", "Split PDF", "كل صفحة أو كل مجموعة في ملف", "Every page or group into its own file"),
    EXTRACT(Icons.Default.ContentCut, 0xFF7C3AED, "استخراج صفحات", "Extract pages", "مثل ⁦1-3، 7، 10-12⁩", "e.g. 1-3, 7, 10-12"),
    ORGANIZE(Icons.Default.ViewModule, 0xFF2563EB, "ترتيب الصفحات", "Organize pages", "تدوير، حذف، إعادة ترتيب", "Rotate, delete, reorder"),
    IMAGES(Icons.Default.Collections, 0xFFDB2777, "صور ← PDF", "Images → PDF", "حوّل صور الكاميرا أو المعرض", "Turn photos into a PDF"),
    COMPRESS(Icons.Default.Compress, 0xFF16A34A, "ضغط الحجم", "Compress", "صغّر حجم الملف للإرسال", "Shrink the file for sharing"),
    PROTECT(Icons.Default.Lock, 0xFFB45309, "حماية بكلمة مرور", "Add password", "تشفير AES-256", "AES-256 encryption"),
    UNLOCK(Icons.Default.LockOpen, 0xFF0891B2, "إزالة كلمة المرور", "Remove password", "احفظ نسخة بلا حماية", "Save an unlocked copy"),
    NUMBERS(Icons.Default.FormatListNumbered, 0xFF4F46E5, "ترقيم الصفحات", "Page numbers", "أضف أرقاماً لكل صفحة", "Stamp numbers on every page"),
    WATERMARK(Icons.Default.BrandingWatermark, 0xFF9333EA, "علامة مائية", "Watermark", "نص بأي لغة على الصفحات", "Text in any language on pages"),
    TO_IMAGES(Icons.Default.Image, 0xFFEA580C, "PDF ← صور", "PDF → Images", "كل صفحة كصورة PNG", "Every page as a PNG image"),
    TO_TEXT(Icons.Default.TextSnippet, 0xFF475569, "PDF ← نص", "PDF → Text", "استخرج النص إلى ملف TXT", "Extract all text to TXT"),
}

@Composable
fun ToolScreen(kind: ToolKind, onBack: () -> Unit, onOpen: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme

    val multi = kind == ToolKind.MERGE || kind == ToolKind.IMAGES
    val inputs = remember { mutableStateListOf<Uri>() }
    var password by remember { mutableStateOf<String?>(null) }
    var askPw by remember { mutableStateOf(false) }
    var wrongPw by remember { mutableStateOf(false) }
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    DisposableEffect(doc) { val d = doc; onDispose { d?.close() } }

    // خيارات
    var range by remember { mutableStateOf("") }
    var every by remember { mutableStateOf("1") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("medium") }
    var pos by remember { mutableIntStateOf(0) }
    var withTotal by remember { mutableStateOf(true) }
    var wmText by remember { mutableStateOf("") }
    var wmColor by remember { mutableIntStateOf(0xFFE11D48.toInt()) }
    var a4 by remember { mutableStateOf(true) }
    val order = remember { mutableStateListOf<Pair<Int, Int>>() }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<Pair<Uri?, String>?>(null) }

    val firstName = inputs.firstOrNull()?.let { ctx.displayName(it).removeSuffix(".pdf").removeSuffix(".PDF") } ?: "document"

    suspend fun openSingle(u: Uri, pw: String?) {
        doc?.close(); doc = null
        try {
            val d = Pdf.open(ctx, u, pw)
            doc = d; password = pw
            order.clear(); order.addAll((0 until d.count).map { it to 0 })
        } catch (e: PasswordNeeded) {
            askPw = true; wrongPw = e.wrong
        } catch (e: Throwable) { ctx.toast(e.message ?: "Error") }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { inputs.clear(); inputs += u; result = null; scope.launch { openSingle(u, null) } }
    }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l -> inputs.addAll(l.filter { it !in inputs }); result = null }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { l -> inputs.addAll(l); result = null }

    var pendingOut by remember { mutableStateOf<(suspend (Uri) -> Unit)?>(null) }
    fun runWith(out: Uri, job: suspend (Uri) -> Unit) {
        scope.launch {
            running = true; progress = 0f
            val r = runCatching { job(out) }
            running = false
            r.onFailure { e ->
                ctx.toast(if (e is NeedPassword) trS("الملف محمي بكلمة مرور", "The file is password protected") else (trS("فشلت العملية: ", "Failed: ") + (e.message ?: e.javaClass.simpleName)))
            }
        }
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out -> val j = pendingOut; pendingOut = null; if (out != null && j != null) runWith(out, j) }
    val createTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { out -> val j = pendingOut; pendingOut = null; if (out != null && j != null) runWith(out, j) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { out -> val j = pendingOut; pendingOut = null; if (out != null && j != null) runWith(out, j) }

    fun done(u: Uri?, msg: String) { result = u to msg }

    fun start() {
        val d = doc
        when (kind) {
            ToolKind.MERGE -> {
                if (inputs.size < 2) { ctx.toast(trS("اختر ملفين على الأقل", "Pick at least two files")); return }
                pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.merge(ctx, inputs.toList(), out) { progress = it } }; done(out, trS("تم دمج ${inputs.size} ملفات", "Merged ${inputs.size} files")) }
                createPdf.launch("${firstName}_merged.pdf")
            }
            ToolKind.IMAGES -> {
                if (inputs.isEmpty()) { ctx.toast(trS("اختر صوراً أولاً", "Pick images first")); return }
                pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.imagesToPdf(ctx, inputs.toList(), a4, out) { progress = it } }; done(out, trS("أُنشئ PDF من ${inputs.size} صورة", "PDF created from ${inputs.size} images")) }
                createPdf.launch("images.pdf")
            }
            else -> {
                val src = inputs.firstOrNull()
                if (src == null || d == null) { ctx.toast(trS("اختر ملف PDF أولاً", "Pick a PDF first")); return }
                when (kind) {
                    ToolKind.EXTRACT -> {
                        val pages = PdfOps.parseRange(range, d.count)
                        if (pages.isEmpty()) { ctx.toast(trS("اكتب أرقام الصفحات، مثل 1-3، 5", "Enter pages, e.g. 1-3, 5")); return }
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.extract(ctx, src, password, pages.map { it to 0 }, out) }; done(out, trS("استُخرجت ${pages.size} صفحة", "Extracted ${pages.size} pages")) }
                        createPdf.launch("${firstName}_pages.pdf")
                    }
                    ToolKind.ORGANIZE -> {
                        if (order.isEmpty()) { ctx.toast(trS("لا توجد صفحات", "No pages left")); return }
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.extract(ctx, src, password, order.toList(), out) }; done(out, trS("حُفظ الملف بعد الترتيب (${order.size} صفحة)", "Saved (${order.size} pages)")) }
                        createPdf.launch("${firstName}_organized.pdf")
                    }
                    ToolKind.SPLIT -> {
                        val n = every.trim().map { ch -> if (ch in '٠'..'٩') '0' + (ch - '٠') else ch }.joinToString("").toIntOrNull()?.coerceAtLeast(1) ?: 1
                        pendingOut = { tree -> val k = withContext(Dispatchers.IO) { PdfOps.splitEvery(ctx, src, password, n, tree, firstName) { progress = it } }; done(null, trS("أُنشئ $k ملف في المجلد المختار", "Created $k files in the chosen folder")) }
                        ctx.toast(trS("اختر المجلد الذي ستُحفظ فيه الملفات", "Choose the folder for the files"))
                        pickTree.launch(null)
                    }
                    ToolKind.COMPRESS -> {
                        val (q, m) = when (quality) { "low" -> 0.45f to 1100; "high" -> 0.8f to 2200; else -> 0.62f to 1600 }
                        val before = ctx.fileSize(src)
                        pendingOut = { out ->
                            withContext(Dispatchers.IO) { PdfOps.compress(ctx, src, password, q, m, out) { progress = it } }
                            val after = ctx.fileSize(out)
                            val pct = if (before > 0) ((before - after) * 100 / before) else 0
                            done(out, if (after in 1 until before) trS("الحجم: ${ltr("${humanSize(before)} → ${humanSize(after)}")} (−$pct%)", "Size: ${humanSize(before)} → ${humanSize(after)} (−$pct%)")
                                else trS("الملف مضغوط أصلاً؛ لم يتغيّر حجمه كثيراً", "The file was already optimized; size barely changed"))
                        }
                        createPdf.launch("${firstName}_compressed.pdf")
                    }
                    ToolKind.PROTECT -> {
                        if (newPw.length < 4) { ctx.toast(trS("كلمة المرور قصيرة (4 أحرف على الأقل)", "Password too short (min 4)")); return }
                        if (newPw != newPw2) { ctx.toast(trS("كلمتا المرور غير متطابقتين", "Passwords do not match")); return }
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.protect(ctx, src, password, newPw, out) }; done(out, trS("حُمي الملف بكلمة المرور", "File protected with a password")) }
                        createPdf.launch("${firstName}_protected.pdf")
                    }
                    ToolKind.UNLOCK -> {
                        if (password == null) { ctx.toast(trS("هذا الملف غير محمي أصلاً", "This file is not protected")); return }
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.unlock(ctx, src, password!!, out) }; done(out, trS("حُفظت نسخة بلا كلمة مرور", "Unlocked copy saved")) }
                        createPdf.launch("${firstName}_unlocked.pdf")
                    }
                    ToolKind.NUMBERS -> {
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.pageNumbers(ctx, src, password, pos, withTotal, out) }; done(out, trS("رُقّمت الصفحات", "Pages numbered")) }
                        createPdf.launch("${firstName}_numbered.pdf")
                    }
                    ToolKind.WATERMARK -> {
                        if (wmText.isBlank()) { ctx.toast(trS("اكتب نص العلامة المائية", "Enter the watermark text")); return }
                        pendingOut = { out -> withContext(Dispatchers.IO) { PdfOps.watermark(ctx, src, password, wmText.trim(), wmColor, 0.22f, out) }; done(out, trS("أُضيفت العلامة المائية", "Watermark added")) }
                        createPdf.launch("${firstName}_watermark.pdf")
                    }
                    ToolKind.TO_IMAGES -> {
                        pendingOut = { tree ->
                            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                            for (i in 0 until d.count) {
                                val s = d.sizes[i]
                                val k = minOf(2400f / maxOf(s.width, s.height), 200f / 72f)
                                val bmp = d.render(i, (s.width * k).roundToInt(), (s.height * k).roundToInt()) ?: continue
                                withContext(Dispatchers.IO) {
                                    val u = DocumentsContract.createDocument(ctx.contentResolver, dir, "image/png", "${firstName}_${i + 1}.png")!!
                                    ctx.contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                }
                                progress = (i + 1f) / d.count
                            }
                            done(null, trS("حُفظت ${d.count} صورة", "Saved ${d.count} images"))
                        }
                        ctx.toast(trS("اختر المجلد الذي ستُحفظ فيه الصور", "Choose the folder for the images"))
                        pickTree.launch(null)
                    }
                    ToolKind.TO_TEXT -> {
                        pendingOut = { out -> exportText(ctx, d, out); done(out, trS("استُخرج النص", "Text extracted")) }
                        createTxt.launch("$firstName.txt")
                    }
                    else -> {}
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Icon(kind.icon, null, tint = Color(kind.tint))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(tr(kind.ar, kind.en), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(tr(kind.dar, kind.den), fontSize = 12.sp, color = c.onSurfaceVariant)
            }
        }

        Column(Modifier.weight(1f).then(if (kind == ToolKind.ORGANIZE && doc != null) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = 16.dp)) {
            // الملفات المختارة
            if (multi) {
                inputs.forEachIndexed { i, u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (kind == ToolKind.MERGE) PdfThumb(u.toString(), Modifier.size(36.dp, 48.dp))
                        else ImageThumb(u)
                        Spacer(Modifier.width(10.dp))
                        Text("${i + 1}. ${ctx.displayName(u)}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        IconButton(onClick = { if (i > 0) { val x = inputs.removeAt(i); inputs.add(i - 1, x) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, null) }
                        IconButton(onClick = { if (i < inputs.size - 1) { val x = inputs.removeAt(i); inputs.add(i + 1, x) } }, enabled = i < inputs.size - 1) { Icon(Icons.Default.KeyboardArrowDown, null) }
                        IconButton(onClick = { inputs.removeAt(i) }) { Icon(Icons.Default.Close, null) }
                    }
                }
                OutlinedButton(onClick = { if (kind == ToolKind.MERGE) pickPdfs.launch(arrayOf("application/pdf")) else pickImages.launch("image/*") }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp))
                    Text(if (kind == ToolKind.MERGE) tr("إضافة ملفات PDF", "Add PDF files") else tr("إضافة صور", "Add images"))
                }
                if (kind == ToolKind.IMAGES) {
                    Text(tr("حجم الصفحة", "Page size"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    ChoiceChips(listOf("a4" to "A4", "fit" to tr("بحجم الصورة", "Fit image")), if (a4) "a4" else "fit") { a4 = it == "a4" }
                }
            } else {
                val u = inputs.firstOrNull()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
                        .clickable { pickPdf.launch(arrayOf("application/pdf")) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (u != null) PdfThumb(u.toString(), Modifier.size(40.dp, 54.dp)) else Icon(Icons.Default.FileOpen, null, Modifier.size(40.dp), tint = c.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (u != null) ctx.displayName(u) else tr("اختر ملف PDF", "Choose a PDF"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        doc?.let { Text(joinIso(listOf("${it.count} ${tr("صفحة", "pages")}", humanSize(ctx.fileSize(u!!)), if (password != null) "🔒" else "")), fontSize = 12.sp, color = c.onSurfaceVariant) }
                    }
                    Text(tr("تغيير", "Change"), color = c.primary, fontSize = 13.sp)
                }
                val d = doc
                if (d != null) when (kind) {
                    ToolKind.EXTRACT -> OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(tr("الصفحات (من 1 إلى ${d.count})", "Pages (1 to ${d.count})")) }, placeholder = { Text("1-3, 5, 8-10") })
                    ToolKind.SPLIT -> OutlinedTextField(every, { every = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(tr("عدد الصفحات في كل ملف", "Pages per file")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    ToolKind.COMPRESS -> {
                        Text(tr("مستوى الضغط", "Compression level"), fontWeight = FontWeight.SemiBold)
                        ChoiceChips(listOf("low" to tr("أقصى ضغط", "Smallest"), "medium" to tr("متوازن", "Balanced"), "high" to tr("جودة عالية", "High quality")), quality) { quality = it }
                        Text(tr("يعيد ضغط الصور داخل الملف؛ النصوص تبقى كما هي.", "Re-compresses images inside the file; text stays unchanged."), fontSize = 12.sp, color = c.onSurfaceVariant)
                    }
                    ToolKind.PROTECT -> {
                        OutlinedTextField(newPw, { newPw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("كلمة المرور الجديدة", "New password")) }, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(newPw2, { newPw2 = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("تأكيد كلمة المرور", "Confirm password")) }, visualTransformation = PasswordVisualTransformation())
                    }
                    ToolKind.UNLOCK -> Text(
                        if (password != null) tr("فُتح الملف بكلمة المرور. اضغط «تنفيذ» لحفظ نسخة بلا حماية.", "Opened with the password. Tap Run to save an unlocked copy.")
                        else tr("هذا الملف غير محمي بكلمة مرور.", "This file is not password protected."), Modifier.padding(vertical = 8.dp),
                    )
                    ToolKind.NUMBERS -> {
                        Text(tr("مكان الرقم", "Position"), fontWeight = FontWeight.SemiBold)
                        ChoiceChips(listOf("0" to tr("أسفل الوسط", "Bottom center"), "1" to tr("أسفل اليمين", "Bottom right"), "2" to tr("أسفل اليسار", "Bottom left"), "3" to tr("أعلى", "Top")), "$pos") { pos = it.toInt() }
                        SwitchRow(tr("إظهار العدد الكلي (3 / 20)", "Show total (3 / 20)"), null, withTotal) { withTotal = it }
                    }
                    ToolKind.WATERMARK -> {
                        OutlinedTextField(wmText, { wmText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("نص العلامة المائية", "Watermark text")) }, placeholder = { Text(tr("سرّي", "CONFIDENTIAL")) })
                        Spacer(Modifier.height(10.dp))
                        ColorDots(PenColors, wmColor) { wmColor = it }
                    }
                    ToolKind.ORGANIZE -> Organizer(d, order)
                    else -> {}
                }
            }

            if (running) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { progress.coerceIn(0.02f, 1f) }, Modifier.fillMaxWidth())
                Text(tr("جارٍ التنفيذ…", "Working…"), Modifier.padding(top = 6.dp), fontSize = 13.sp)
            }
            result?.let { (u, msg) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 14.dp), colors = CardDefaults.cardColors(containerColor = c.secondaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = c.secondary); Spacer(Modifier.width(8.dp))
                            Text(msg, fontWeight = FontWeight.SemiBold)
                        }
                        if (u != null) Row(Modifier.padding(top = 10.dp)) {
                            if (kind != ToolKind.TO_TEXT) Button(onClick = { onOpen(u) }) { Text(tr("فتح الملف", "Open")) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                if (kind == ToolKind.TO_TEXT) ctx.safeStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                                else ctx.sharePdf(u, ctx.displayName(u))
                            }) { Text(tr("مشاركة", "Share")) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = { start() }, enabled = !running, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp), shape = RoundedCornerShape(16.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null); Spacer(Modifier.width(8.dp)); Text(tr("تنفيذ", "Run"), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }

    if (askPw) PasswordDialog(wrongPw, onCancel = { askPw = false; inputs.clear() }) { pw ->
        askPw = false; inputs.firstOrNull()?.let { u -> scope.launch { openSingle(u, pw) } }
    }
}

@Composable
private fun ImageThumb(u: Uri) {
    val ctx = LocalContext.current
    var bmp by remember(u) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(u) { bmp = withContext(Dispatchers.IO) { PdfOps.decodeImage(ctx, u, 160) } }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun Organizer(doc: PdfDoc, order: MutableList<Pair<Int, Int>>) {
    val cache = remember { HashMap<Int, Bitmap>() }
    Text(tr("دوّر أو احذف أو حرّك الصفحات ثم اضغط «تنفيذ».", "Rotate, delete or move pages, then tap Run."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxWidth().heightIn(max = 2000.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(order, key = { _, it -> it.first }) { i, (p, rot) ->
            val sz = doc.sizes[p]
            var bmp by remember(p) { mutableStateOf(cache[p]) }
            LaunchedEffect(p) { if (bmp == null) doc.render(p, 200, (200f * sz.height / sz.width).roundToInt().coerceIn(40, 500))?.let { cache[p] = it; bmp = it } }
            Column(Modifier.padding(5.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                    bmp?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(0.92f).rotate(rot.toFloat()).border(1.dp, MaterialTheme.colorScheme.outlineVariant), contentScale = ContentScale.Fit)
                    }
                }
                Text("${i + 1}  (${tr("أصل", "orig.")} ${p + 1})", fontSize = 11.sp)
                Row {
                    SmallIcon(Icons.AutoMirrored.Filled.ArrowBack, i > 0) { val x = order.removeAt(i); order.add(i - 1, x) }
                    SmallIcon(Icons.AutoMirrored.Filled.RotateRight, true) { order[i] = p to (rot + 90) % 360 }
                    SmallIcon(Icons.Default.DeleteOutline, true) { order.removeAt(i) }
                    SmallIcon(Icons.AutoMirrored.Filled.ArrowForward, i < order.size - 1) { val x = order.removeAt(i); order.add(i + 1, x) }
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
    }
}
