// صفحة — شاشة القراءة: الأشرطة، البحث، الفهرس، المصغّرات، العلامات، التعليقات، القراءة الصوتية، الطباعة
// برمجة: د. محمد توفيق
package app.safha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(uri: Uri, onBack: () -> Unit, onOcr: (List<java.io.File>) -> Unit = {}) {
    val ctx = LocalContext.current
    var password by remember(uri) { mutableStateOf<String?>(null) }
    var askPw by remember(uri) { mutableStateOf(false) }
    var wrongPw by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var doc by remember(uri) { mutableStateOf<PdfDoc?>(null) }
    var reload by remember(uri) { mutableIntStateOf(0) }
    var errorEx by remember(uri) { mutableStateOf<Throwable?>(null) }
    var picked by remember(uri) { mutableStateOf<Uri?>(null) }
    picked?.let { p ->
        // اختير الملف من جديد: نحذف الرابط المنتهي من «الأخيرة» ونفتح الجديد
        LaunchedEffect(p) { Store.removeRecent(uri.toString()) }
        ReaderScreen(p, onBack, onOcr); return
    }

    LaunchedEffect(uri, password, reload) {
        error = null
        try {
            val d = Pdf.open(ctx, uri, password)
            doc = d
            Store.touchRecent(uri, ctx.displayName(uri), ctx.fileSize(uri), d.count, Store.lastPage(uri))
        } catch (e: PasswordNeeded) {
            askPw = true; wrongPw = e.wrong
        } catch (e: Throwable) {
            errorEx = e
            error = e.message ?: e.javaClass.simpleName
        }
    }
    DisposableEffect(doc) { val d = doc; onDispose { d?.close() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val d = doc
        when {
            d != null -> key(d) {
                ReaderContent(d, uri, password, onBack, reopen = { doc = null; reload++ }, onOcr = onOcr)
            }
            error != null -> AccessError(errorEx, onBack, onPicked = if (Docs.isAccessError(errorEx)) { p -> picked = p } else null)
            !askPw -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (askPw) PasswordDialog(wrongPw, onCancel = onBack) { pw -> askPw = false; password = pw }
    }
}

/** ينسخ الملف الوارد من تطبيق آخر إلى التطبيق قبل فتحه (حتى يبقى قابلاً للفتح لاحقاً). */
@Composable
fun IncomingScreen(uri: Uri, flags: Int, onReady: (Uri) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var err by remember(uri) { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { Docs.localize(ctx, uri, flags) } }
            .onSuccess(onReady).onFailure { err = it }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        if (err == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(tr("جارٍ تجهيز الملف…", "Preparing the file…"))
        } else AccessError(err, onBack)
    }
}

/** رسالة واضحة عند فقدان صلاحية الوصول، مع زر لاختيار الملف من جديد. */
@Composable
fun AccessError(e: Throwable?, onBack: () -> Unit, onPicked: ((Uri) -> Unit)? = null) {
    val ctx = LocalContext.current
    val access = Docs.isAccessError(e)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPicked?.invoke(u)
        }
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (access) Icons.Default.FolderOff else Icons.Default.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(tr("تعذّر فتح الملف", "Could not open the file"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (access) tr("انتهت صلاحية الوصول لهذا الملف من التطبيق الذي أرسله، أو نُقل الملف أو حُذف. اختره مرة أخرى وسيُحفظ وصول دائم له.",
                "Access to this file expired (it came from another app), or the file was moved or deleted. Pick it again and Safha will keep permanent access.")
            else (e?.message ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        if (onPicked != null) Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(tr("اختيار الملف", "Pick the file"))
        }
        TextButton(onClick = onBack) { Text(tr("رجوع", "Back")) }
    }
}

@Composable
fun PasswordDialog(wrong: Boolean, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(tr("الملف محمي بكلمة مرور", "Password protected")) },
        text = {
            Column {
                if (wrong) Text(tr("كلمة المرور غير صحيحة", "Wrong password"), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text(tr("كلمة المرور", "Password")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onOk(pw) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(pw) }) { Text(tr("فتح", "Open")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("إلغاء", "Cancel")) } },
    )
}

private enum class Sheet { NONE, TOC, THUMBS, BOOKMARKS, VIEW, INFO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(doc: PdfDoc, uri: Uri, password: String?, onBack: () -> Unit, reopen: () -> Unit, onOcr: (List<java.io.File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val c = MaterialTheme.colorScheme
    val u = uri.toString()
    val name = remember(uri) { ctx.displayName(uri) }

    val vs = remember(doc) { ViewerState(doc, scope, density) }
    var ui by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var searching by remember { mutableStateOf(false) }
    var annotating by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Sel?>(null) }
    var menu by remember { mutableStateOf(false) }
    var goToDlg by remember { mutableStateOf(false) }
    var noteEdit by remember { mutableStateOf<Annot?>(null) }
    var notePlace by remember { mutableStateOf<Pair<Int, NPoint>?>(null) }
    var sigPad by remember { mutableStateOf(false) }
    var saveDlg by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var linkAsk by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember { mutableStateOf(Store.bookmarks(u)) }
    var locked by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    // ----- تهيئة -----
    LaunchedEffect(vs) {
        vs.setAnnots(Store.annots(u))
        vs.onAnnotsChanged = { Store.saveAnnots(u, it) }
        vs.goTo(Store.lastPage(uri), animate = false)
        snapshotFlow { vs.currentPage }.collect { Store.updatePage(uri, it) }
    }
    SideEffect {
        vs.onTap = { if (searching) ui = true else ui = !ui; menu = false }
        vs.onLink = { l -> if (l.page != null) vs.goTo(l.page) else if (l.uri != null) linkAsk = l.uri }
        vs.onSelection = { sel = it }
        vs.onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        vs.onNote = { noteEdit = it }
        vs.onPlace = { tool, page, p ->
            if (tool == Tool.NOTE) notePlace = page to p
            else if (tool == Tool.SIGN) placeSignature(vs, page, p)
        }
    }
    LaunchedEffect(Store.layout, Store.rtlPages, Store.pageGap) { vs.setMode(Store.layout == "horizontal", Store.rtlPages, Store.pageGap) }

    // إبقاء الشاشة مضاءة + ملء الشاشة
    DisposableEffect(Store.keepScreenOn) { view.keepScreenOn = Store.keepScreenOn; onDispose { view.keepScreenOn = false } }
    val activity = ctx as? Activity
    LaunchedEffect(ui, Store.fullscreenRead) {
        activity?.window?.let { w ->
            val ctl = WindowCompat.getInsetsController(w, view)
            if (!ui && Store.fullscreenRead) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ----- مسارات الحفظ -----
    var pendingSave by remember { mutableStateOf<(suspend (Uri) -> Unit)?>(null) }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val job = pendingSave; pendingSave = null
        if (out != null && job != null) scope.launch { job(out) }
    }
    val createTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { out ->
        val job = pendingSave; pendingSave = null
        if (out != null && job != null) scope.launch { job(out) }
    }
    val createPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { out ->
        val job = pendingSave; pendingSave = null
        if (out != null && job != null) scope.launch { job(out) }
    }
    val base = name.removeSuffix(".pdf").removeSuffix(".PDF")

    suspend fun saveAnnotsTo(out: Uri, overwrite: Boolean) {
        busy = tr0("جارٍ الحفظ…", "Saving…")
        val r = runCatching { PdfOps.saveAnnotations(ctx, uri, password, vs.annots.toList(), doc, out) }
        busy = null
        r.onSuccess {
            ctx.toast(trS("حُفظت التعليقات داخل الملف", "Annotations saved into the PDF"))
            if (overwrite) { Store.saveAnnots(u, emptyList()); reopen() }
        }.onFailure { ctx.toast(trS("فشل الحفظ: ", "Save failed: ") + it.message) }
    }

    // زر الرجوع يُغلق الوضع الحالي أولاً (بحث، تعليق، تحديد) قبل مغادرة الملف
    BackHandler(enabled = searching || annotating || sel != null) {
        when {
            sel != null -> vs.clearSel()
            annotating -> { annotating = false; vs.tool = Tool.NONE }
            else -> { searching = false; vs.clearHits() }
        }
    }

    // ----- البحث -----
    var query by remember { mutableStateOf("") }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchProgress by remember { mutableFloatStateOf(1f) }
    fun runSearch() {
        searchJob?.cancel(); vs.clearHits()
        val q = query.trim(); if (q.isEmpty()) return
        searchJob = scope.launch {
            searchProgress = 0f
            val start = vs.currentPage
            val all = ArrayList<Hit>()
            var focused = false
            for (k in 0 until doc.count) {
                val p = (start + k) % doc.count
                val h = doc.search(q, p)
                if (h.isNotEmpty()) {
                    all += h
                    vs.hits = all.sortedWith(compareBy({ it.page }, { it.start }))
                    if (!focused) { focused = true; vs.focusHit(vs.hits.indexOf(h.first())) }
                    else vs.hitIndex = vs.hits.indexOf(vs.hits.getOrNull(vs.hitIndex) ?: h.first()).coerceAtLeast(0)
                }
                if (k % 8 == 0) searchProgress = (k + 1f) / doc.count
            }
            searchProgress = 1f
            if (all.isEmpty()) ctx.toast(trS("لا توجد نتائج", "No results"))
        }
    }

    val dark = isDark()
    val readerBg = if (Store.readMode == "night") Color(0xFF050505) else if (dark) Color(0xFF242120) else Color(0xFFE9E4DE)

    Box(Modifier.fillMaxSize().background(readerBg)) {
        // مساحة الشريطين حتى تظهر أول صفحة كاملة تحت الشريط العلوي
        val dens = LocalDensity.current
        val topInset = WindowInsets.statusBars.getTop(dens) + with(dens) { 60.dp.toPx() }
        val bottomInset = WindowInsets.navigationBars.getBottom(dens) + with(dens) { 70.dp.toPx() }
        LaunchedEffect(topInset, bottomInset) { vs.setInsets(topInset, bottomInset) }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            PdfView(vs, readerBg, c.primary)
        }

        // ----- الشريط العلوي -----
        AnimatedVisibility(ui || searching, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = c.surface.copy(alpha = 0.97f), shadowElevation = 4.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (!searching) Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("رجوع", "Back")) }
                        Column(Modifier.weight(1f).clickable { goToDlg = true }) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${tr("صفحة", "Page")} ${ltr("${vs.currentPage + 1} / ${doc.count}")}", fontSize = 12.sp, color = c.onSurfaceVariant)
                        }
                        IconButton(onClick = { searching = true; ui = true }) { Icon(Icons.Default.Search, tr("بحث", "Search")) }
                        val marked = bookmarks.any { it.page == vs.currentPage }
                        IconButton(onClick = {
                            bookmarks = if (marked) bookmarks.filter { it.page != vs.currentPage }
                            else (bookmarks + Bookmark(vs.currentPage, "", System.currentTimeMillis())).sortedBy { it.page }
                            Store.saveBookmarks(u, bookmarks)
                            ctx.toast(if (marked) trS("أُزيلت العلامة", "Bookmark removed") else trS("أُضيفت علامة على الصفحة", "Page bookmarked"))
                        }) { Icon(if (marked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, tr("علامة", "Bookmark"), tint = if (marked) c.primary else c.onSurface) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("المزيد", "More")) }
                            DropdownMenu(menu, { menu = false }) {
                                fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, t: String, f: () -> Unit) =
                                    @Composable { DropdownMenuItem(text = { Text(t) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; f() }) }
                                item(Icons.Default.Pages, tr("الانتقال إلى صفحة", "Go to page")) { goToDlg = true }()
                                item(Icons.Default.Share, tr("مشاركة الملف", "Share file")) { ctx.sharePdf(uri, name) }()
                                item(Icons.Default.Print, tr("طباعة", "Print")) { printPdf(ctx, uri, name, doc.count) }()
                                item(Icons.Default.Save, tr("حفظ التعليقات في PDF", "Save annotations to PDF")) {
                                    if (vs.annots.isEmpty()) ctx.toast(trS("لا توجد تعليقات بعد", "No annotations yet")) else saveDlg = true
                                }()
                                item(Icons.Default.Image, tr("حفظ الصفحة كصورة", "Save page as image")) {
                                    val p = vs.currentPage
                                    pendingSave = { out -> busy = trS("جارٍ التصدير…", "Exporting…"); exportPage(ctx, doc, p, out); busy = null }
                                    createPng.launch("${base}_${p + 1}.png")
                                }()
                                item(Icons.Default.TextSnippet, tr("استخراج النص (TXT)", "Extract text (TXT)")) {
                                    pendingSave = { out -> busy = trS("جارٍ الاستخراج…", "Extracting…"); exportText(ctx, doc, out); busy = null }
                                    createTxt.launch("$base.txt")
                                }()
                                item(Icons.Default.TextFields, tr("استخراج النص من صورة الصفحة (OCR)", "Text from page image (OCR)")) {
                                    val p = vs.currentPage
                                    scope.launch {
                                        val s = doc.sizes[p]; val k = 2600f / maxOf(s.width, s.height)
                                        val b = doc.render(p, (s.width * k).roundToInt(), (s.height * k).roundToInt())
                                        if (b != null) {
                                            val f = java.io.File(Scan.dir(ctx), "ocrpage_${System.nanoTime()}.jpg")
                                            withContext(Dispatchers.IO) { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                                            onOcr(listOf(f))
                                        }
                                    }
                                }()
                                item(Icons.Default.ContentCopy, tr("نسخ نص الصفحة", "Copy page text")) { scope.launch { ctx.copyText(doc.text(vs.currentPage).trim()) } }()
                                item(Icons.Default.SelectAll, tr("تحديد نص الصفحة", "Select page text")) { vs.selectAllOnPage() }()
                                val fav = u in Store.favorites
                                item(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, if (fav) tr("إزالة من المفضلة", "Remove from favorites") else tr("إضافة إلى المفضلة", "Add to favorites")) { Store.toggleFavorite(u) }()
                                item(if (locked) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation, if (locked) tr("إلغاء قفل التدوير", "Unlock rotation") else tr("قفل تدوير الشاشة", "Lock rotation")) {
                                    locked = !locked
                                    activity?.requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }()
                                item(Icons.AutoMirrored.Filled.OpenInNew, tr("فتح بتطبيق آخر", "Open with…")) { ctx.openWith(uri, name) }()
                                item(Icons.Default.Info, tr("معلومات الملف", "Document info")) { sheet = Sheet.INFO }()
                            }
                        }
                    } else SearchBar(
                        query, { query = it }, onSearch = { runSearch() },
                        count = vs.hits.size, index = vs.hitIndex, progress = searchProgress,
                        onPrev = { vs.focusHit(vs.hitIndex - 1) }, onNext = { vs.focusHit(vs.hitIndex + 1) },
                        onClose = { searching = false; searchJob?.cancel(); vs.clearHits(); query = "" },
                    )
                }
            }
        }

        // ----- شريط التمرير السريع -----
        if (!vs.horizontal && doc.count > 2) FastScroller(vs, visible = ui, modifier = Modifier.align(Alignment.CenterEnd))

        // ----- رقم الصفحة الطافي -----
        AnimatedVisibility(!ui && !annotating, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)) {
            Text(
                ltr("${vs.currentPage + 1} / ${doc.count}"),
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 5.dp),
                color = Color.White, fontSize = 13.sp,
            )
        }

        // ----- الشريط السفلي -----
        AnimatedVisibility(ui && !annotating && !searching, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = c.surface.copy(alpha = 0.97f), shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (vs.horizontal && doc.count > 1) {
                        var sv by remember { mutableFloatStateOf(vs.currentPage.toFloat()) }
                        LaunchedEffect(vs.currentPage) { sv = vs.currentPage.toFloat() }
                        Slider(
                            sv, { sv = it }, valueRange = 0f..(doc.count - 1).toFloat(),
                            onValueChangeFinished = { vs.goTo(sv.roundToInt(), animate = false) },
                            modifier = Modifier.padding(horizontal = 16.dp).height(30.dp),
                        )
                    }
                    if (speaker.speaking) TtsBar(speaker, onStop = { speaker.stop() })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        val wm = Modifier.weight(1f)
                        BarButton(Icons.AutoMirrored.Filled.FormatListBulleted, tr("الفهرس", "Contents"), modifier = wm) { sheet = Sheet.TOC }
                        BarButton(Icons.Default.GridView, tr("الصفحات", "Pages"), modifier = wm) { sheet = Sheet.THUMBS }
                        BarButton(Icons.Default.Bookmarks, tr("العلامات", "Marks"), modifier = wm) { sheet = Sheet.BOOKMARKS }
                        BarButton(Icons.Default.Draw, tr("تعليق", "Annotate"), modifier = wm) { annotating = true; vs.clearSel() }
                        BarButton(Icons.AutoMirrored.Filled.VolumeUp, tr("استماع", "Listen"), selected = speaker.speaking, modifier = wm) {
                            if (speaker.speaking) speaker.stop() else speakFrom(speaker, vs, doc, scope, vs.currentPage)
                        }
                        BarButton(
                            if (Store.readMode == "night") Icons.Default.LightMode else Icons.Default.DarkMode,
                            if (Store.readMode == "night") tr("نهاري", "Day") else tr("ليلي", "Night"), modifier = wm,
                        ) { Store.readMode = if (Store.readMode == "night") "normal" else "night"; Store.save() }
                        BarButton(Icons.Default.Tune, tr("العرض", "View"), modifier = wm) { sheet = Sheet.VIEW }
                    }
                }
            }
        }

        // ----- أدوات التعليق -----
        AnimatedVisibility(annotating, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            AnnotBar(vs, onDone = { annotating = false; vs.tool = Tool.NONE }, onSign = {
                if (Store.signature.isEmpty()) sigPad = true else vs.tool = Tool.SIGN
            }, onNewSignature = { sigPad = true }, onSave = { if (vs.annots.isEmpty()) ctx.toast(trS("لا توجد تعليقات بعد", "No annotations yet")) else saveDlg = true })
        }

        // ----- شريط التحديد -----
        val s = sel
        if (s != null && !vs.selecting) {
            SelectionBar(
                vs, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (ui || searching) 64.dp else 12.dp),
                onCopy = { scope.launch { ctx.copyText(vs.selectedText()); vs.clearSel() } },
                onShare = { scope.launch { ctx.shareText(vs.selectedText()); vs.clearSel() } },
                onMark = { type, col -> vs.markSelection(type, col) },
                onSpeak = { scope.launch { speaker.speakText(vs.selectedText()); vs.clearSel() } },
                onSearch = { scope.launch { query = vs.selectedText().take(80); vs.clearSel(); searching = true; runSearch() } },
                onWeb = { scope.launch { val t = vs.selectedText(); vs.clearSel(); ctx.safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", t)) } },
                onTranslate = { scope.launch { translate(ctx, vs.selectedText()); vs.clearSel() } },
                onNote = {
                    val r = vs.selRects.firstOrNull()
                    if (r != null) { notePlace = s.page to NPoint(r.l, r.t); vs.clearSel() }
                },
            )
        }

        busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(26.dp)); Spacer(Modifier.width(14.dp)); Text(msg)
                    }
                }
            }
        }
    }

    // ----- اللوحات السفلية -----
    if (sheet != Sheet.NONE) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == Sheet.THUMBS)) {
            when (sheet) {
                Sheet.TOC -> TocSheet(doc, vs.currentPage) { vs.goTo(it, animate = false); sheet = Sheet.NONE }
                Sheet.THUMBS -> ThumbSheet(doc, vs.currentPage, bookmarks.map { it.page }.toSet()) { vs.goTo(it, animate = false); sheet = Sheet.NONE }
                Sheet.BOOKMARKS -> BookmarkSheet(bookmarks, vs.currentPage, onGo = { vs.goTo(it, animate = false); sheet = Sheet.NONE }) { bookmarks = it; Store.saveBookmarks(u, it) }
                Sheet.VIEW -> ViewSheet(vs)
                Sheet.INFO -> InfoSheet(doc, uri, name, vs.annots.size, bookmarks.size)
                Sheet.NONE -> {}
            }
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }

    if (goToDlg) GoToDialog(doc.count, vs.currentPage, { goToDlg = false }) { vs.goTo(it, animate = false); goToDlg = false }

    linkAsk?.let { url ->
        AlertDialog(
            onDismissRequest = { linkAsk = null },
            icon = { Icon(Icons.Default.Link, null) },
            title = { Text(tr("فتح الرابط؟", "Open link?")) },
            text = { Text(url, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { linkAsk = null; ctx.openUrl(url) }) { Text(tr("فتح", "Open")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { linkAsk = null; ctx.copyText(url) }) { Text(tr("نسخ", "Copy")) }
                    TextButton(onClick = { linkAsk = null }) { Text(tr("إلغاء", "Cancel")) }
                }
            },
        )
    }

    // ملاحظة جديدة أو تعديل
    notePlace?.let { (page, p) ->
        NoteDialog("", onDismiss = { notePlace = null }, onDelete = null) { t ->
            if (t.isNotBlank()) vs.addAnnot(Annot(System.currentTimeMillis(), page, AnnotType.NOTE, 0xFFFFD54F.toInt(), rects = listOf(NRect(p.x, p.y, p.x, p.y)), text = t))
            notePlace = null; if (vs.tool == Tool.NOTE) vs.tool = Tool.NONE
        }
    }
    noteEdit?.let { a ->
        NoteDialog(a.text, onDismiss = { noteEdit = null }, onDelete = { vs.removeAnnot(a.id); noteEdit = null }) { t ->
            vs.replaceAnnot(a.copy(text = t)); noteEdit = null
        }
    }
    if (sigPad) SignaturePad(onDismiss = { sigPad = false }) { strokes ->
        Store.signature = strokes; Store.save(); sigPad = false; vs.tool = Tool.SIGN
        ctx.toast(trS("المس مكان التوقيع على الصفحة", "Tap where the signature should go"))
    }
    if (saveDlg) AlertDialog(
        onDismissRequest = { saveDlg = false },
        icon = { Icon(Icons.Default.Save, null) },
        title = { Text(tr("حفظ التعليقات داخل PDF", "Save annotations into the PDF")) },
        text = { Text(tr("تُكتب التعليقات (التظليل، الرسم، الملاحظات، التوقيع) كتعليقات حقيقية تظهر في أي قارئ PDF.", "Highlights, drawings, notes and signatures are written as real PDF annotations visible in any reader.")) },
        confirmButton = {
            TextButton(onClick = {
                saveDlg = false
                pendingSave = { out -> saveAnnotsTo(out, false) }
                createPdf.launch("${base}_annotated.pdf")
            }) { Text(tr("حفظ نسخة", "Save a copy")) }
        },
        dismissButton = {
            TextButton(onClick = {
                saveDlg = false
                if (uri.scheme == "content" || uri.scheme == "file") scope.launch {
                    val ok = runCatching { ctx.contentResolver.openFileDescriptor(uri, "rw")?.close(); true }.getOrDefault(false)
                    if (ok) saveAnnotsTo(uri, true) else ctx.toast(trS("لا يمكن الكتابة فوق هذا الملف؛ احفظ نسخة", "This file is read-only; save a copy"))
                }
            }) { Text(tr("الكتابة فوق الأصل", "Overwrite original")) }
        },
    )
}

private fun tr0(ar: String, en: String) = trS(ar, en)

private fun placeSignature(vs: ViewerState, page: Int, p: NPoint) {
    val sig = Store.signature
    if (sig.isEmpty()) return
    val sz = vs.doc.sizes[page]
    val aspect = sz.width.toFloat() / sz.height        // تحويل وحدات العرض إلى وحدات الارتفاع
    val pts = sig.flatten()
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val w = (maxX - minX).coerceAtLeast(0.01f)
    val box = 0.30f
    val k = box / w
    val strokes = sig.map { st ->
        st.map { q -> NPoint((p.x + (q.x - (minX + maxX) / 2) * k).coerceIn(0f, 1f), (p.y + (q.y - (minY + maxY) / 2) * k * aspect).coerceIn(0f, 1f)) }
    }
    vs.addAnnot(Annot(System.currentTimeMillis(), page, AnnotType.INK, 0xFF0B2A6F.toInt(), width = 0.0035f, strokes = strokes))
    vs.tool = Tool.NONE
}

// ---------------- البحث ----------------
@Composable
private fun SearchBar(
    q: String, onQ: (String) -> Unit, onSearch: () -> Unit, count: Int, index: Int, progress: Float,
    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val fr = remember { FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { fr.requestFocus() }
    Column {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, tr("إغلاق", "Close")) }
            TextField(
                q, onQ, Modifier.weight(1f).focusRequester(fr), singleLine = true,
                placeholder = { Text(tr("ابحث في الملف…", "Search in document…")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide(); onSearch() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )
            if (count > 0) Text(ltr("${index + 1}/$count"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, tr("السابق", "Previous")) }
            IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, tr("التالي", "Next")) }
        }
        if (progress < 1f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(2.dp))
    }
}

// ---------------- التحديد ----------------
@Composable
private fun SelectionBar(
    vs: ViewerState, modifier: Modifier, onCopy: () -> Unit, onShare: () -> Unit, onMark: (Int, Int) -> Unit,
    onSpeak: () -> Unit, onSearch: () -> Unit, onWeb: () -> Unit, onTranslate: () -> Unit, onNote: () -> Unit,
) {
    var colors by remember { mutableStateOf(false) }
    Surface(modifier.padding(horizontal = 10.dp), shape = RoundedCornerShape(18.dp), shadowElevation = 10.dp, tonalElevation = 3.dp) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
                BarButton(Icons.Default.ContentCopy, tr("نسخ", "Copy"), onClick = onCopy)
                BarButton(Icons.Default.BorderColor, tr("تظليل", "Highlight")) { onMark(AnnotType.HIGHLIGHT, vs.markerColor) }
                BarButton(Icons.Default.Palette, tr("لون", "Color"), selected = colors) { colors = !colors }
                BarButton(Icons.Default.FormatUnderlined, tr("تسطير", "Underline")) { onMark(AnnotType.UNDERLINE, vs.penColor) }
                BarButton(Icons.Default.FormatStrikethrough, tr("شطب", "Strike")) { onMark(AnnotType.STRIKE, vs.penColor) }
                BarButton(Icons.Default.StickyNote2, tr("ملاحظة", "Note"), onClick = onNote)
                BarButton(Icons.Default.Share, tr("مشاركة", "Share"), onClick = onShare)
                BarButton(Icons.Default.Translate, tr("ترجمة", "Translate"), onClick = onTranslate)
                BarButton(Icons.AutoMirrored.Filled.VolumeUp, tr("استماع", "Speak"), onClick = onSpeak)
                BarButton(Icons.Default.Search, tr("ابحث", "Find"), onClick = onSearch)
                BarButton(Icons.Default.TravelExplore, tr("الويب", "Web"), onClick = onWeb)
                BarButton(Icons.Default.Close, tr("إلغاء", "Cancel")) { vs.clearSel() }
            }
            if (colors) Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                ColorDots(MarkColors, vs.markerColor) { vs.markerColor = it; colors = false; onMark(AnnotType.HIGHLIGHT, it) }
            }
        }
    }
}

// ---------------- أدوات التعليق ----------------
@Composable
private fun AnnotBar(vs: ViewerState, onDone: () -> Unit, onSign: () -> Unit, onNewSignature: () -> Unit, onSave: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surface, shadowElevation = 10.dp, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.navigationBarsPadding().padding(top = 6.dp)) {
            if (vs.tool == Tool.PEN || vs.tool == Tool.MARKER) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (vs.tool == Tool.PEN) ColorDots(PenColors, vs.penColor) { vs.penColor = it }
                else ColorDots(MarkColors, vs.markerColor) { vs.markerColor = it }
                Spacer(Modifier.width(12.dp))
                Slider(vs.penWidth, { vs.penWidth = it }, valueRange = 0.0015f..0.012f, modifier = Modifier.weight(1f))
            }
            if (vs.tool == Tool.SIGN) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("المس الصفحة لوضع توقيعك", "Tap the page to place your signature"), Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = onNewSignature) { Text(tr("توقيع جديد", "New signature")) }
            }
            if (vs.tool == Tool.NOTE) Text(tr("المس مكان الملاحظة على الصفحة", "Tap the page to add a note"), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                BarButton(Icons.Default.Create, tr("قلم", "Pen"), selected = vs.tool == Tool.PEN) { vs.tool = if (vs.tool == Tool.PEN) Tool.NONE else Tool.PEN }
                BarButton(Icons.Default.Brush, tr("قلم عريض", "Marker"), selected = vs.tool == Tool.MARKER) { vs.tool = if (vs.tool == Tool.MARKER) Tool.NONE else Tool.MARKER }
                BarButton(Icons.Default.AutoFixNormal, tr("ممحاة", "Eraser"), selected = vs.tool == Tool.ERASER) { vs.tool = if (vs.tool == Tool.ERASER) Tool.NONE else Tool.ERASER }
                BarButton(Icons.Default.StickyNote2, tr("ملاحظة", "Note"), selected = vs.tool == Tool.NOTE) { vs.tool = if (vs.tool == Tool.NOTE) Tool.NONE else Tool.NOTE }
                BarButton(Icons.Default.Gesture, tr("توقيع", "Sign"), selected = vs.tool == Tool.SIGN) { if (vs.tool == Tool.SIGN) vs.tool = Tool.NONE else onSign() }
                BarButton(Icons.AutoMirrored.Filled.Undo, tr("تراجع", "Undo"), enabled = vs.canUndo) { vs.undo() }
                BarButton(Icons.AutoMirrored.Filled.Redo, tr("إعادة", "Redo"), enabled = vs.canRedo) { vs.redo() }
                BarButton(Icons.Default.Save, tr("حفظ PDF", "Save PDF"), onClick = onSave)
                BarButton(Icons.Default.Check, tr("تم", "Done"), onClick = onDone)
            }
            Text(
                tr("التظليل: اضغط مطوّلاً على النص. التكبير والتمرير بإصبعين أثناء الرسم.", "To highlight: long-press text. Use two fingers to zoom/scroll while drawing."),
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp).padding(bottom = 4.dp), fontSize = 11.sp, color = c.onSurfaceVariant,
            )
        }
    }
}

// ---------------- شريط التمرير السريع ----------------
@Composable
private fun FastScroller(vs: ViewerState, visible: Boolean, modifier: Modifier) {
    var h by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val thumbH = with(LocalDensity.current) { 48.dp.toPx() }
    val show = visible || dragging
    Box(
        modifier.fillMaxHeight().width(36.dp).padding(vertical = 120.dp).onSizeChanged { h = it.height }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; vs.fraction = ((it.y - thumbH / 2) / (h - thumbH)).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                ) { ch, _ -> vs.fraction = ((ch.position.y - thumbH / 2) / (h - thumbH)).coerceIn(0f, 1f) }
            },
    ) {
        if (show && h > 0) {
            val y = (vs.fraction * (h - thumbH)).roundToInt()
            Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 4.dp).size(8.dp, 48.dp)
                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.75f))
            )
            if (dragging) Text(
                "${vs.currentPage + 1}",
                Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(this) { 26.dp.roundToPx() }, y + 8) }
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------- اللوحات ----------------
@Composable
private fun TocSheet(doc: PdfDoc, current: Int, onGo: (Int) -> Unit) {
    var toc by remember { mutableStateOf<List<TocItem>?>(null) }
    LaunchedEffect(doc) { toc = doc.toc() }
    SheetTitle(tr("الفهرس", "Contents"))
    val t = toc
    when {
        t == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        t.isEmpty() -> Text(tr("لا يحتوي هذا الملف على فهرس. استخدم «الصفحات» أو «الانتقال إلى صفحة».", "This document has no outline. Use Pages or Go to page."),
            Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val curIdx = t.indexOfLast { it.page <= current }.coerceAtLeast(0)
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (curIdx - 3).coerceAtLeast(0))
            LazyColumn(state = ls, modifier = Modifier.heightIn(max = 560.dp)) {
                itemsIndexed(t) { i, item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onGo(item.page) }
                            .background(if (i == curIdx) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
                            .padding(start = (20 + item.depth * 18).dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.title, Modifier.weight(1f), fontWeight = if (item.depth == 0) FontWeight.SemiBold else FontWeight.Normal, fontSize = if (item.depth == 0) 15.sp else 14.sp)
                        Text("${item.page + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbSheet(doc: PdfDoc, current: Int, marked: Set<Int>, onGo: (Int) -> Unit) {
    SheetTitle(tr("الصفحات", "Pages"), "${doc.count} ${tr("صفحة", "pages")}")
    val gs = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    val cache = remember { HashMap<Int, Bitmap>() }
    LazyVerticalGrid(GridCells.Adaptive(104.dp), state = gs, modifier = Modifier.heightIn(max = 620.dp), contentPadding = PaddingValues(12.dp)) {
        items(doc.count) { i ->
            val sz = doc.sizes[i]
            var bmp by remember(i) { mutableStateOf(cache[i]) }
            LaunchedEffect(i) {
                if (bmp == null) {
                    val w = 220; val h = (w * sz.height.toFloat() / sz.width).roundToInt().coerceIn(40, 600)
                    doc.render(i, w, h)?.let { cache[i] = it; bmp = it }
                }
            }
            Column(Modifier.padding(6.dp).clickable { onGo(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(sz.width.toFloat() / sz.height)
                        .border(if (i == current) 3.dp else 1.dp, if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp)).background(Color.White),
                ) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = readFilter(Store.readMode)) }
                    if (i in marked) Icon(Icons.Default.Bookmark, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("${i + 1}", fontSize = 12.sp, fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun BookmarkSheet(list: List<Bookmark>, current: Int, onGo: (Int) -> Unit, onChange: (List<Bookmark>) -> Unit) {
    var label by remember { mutableStateOf("") }
    SheetTitle(tr("العلامات المرجعية", "Bookmarks"))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(label, { label = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("اسم العلامة (اختياري)", "Label (optional)")) })
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            onChange((list.filter { it.page != current } + Bookmark(current, label.trim(), System.currentTimeMillis())).sortedBy { it.page }); label = ""
        }) { Text("${tr("أضف ص", "Add p.")}${current + 1}") }
    }
    if (list.isEmpty()) Text(tr("لا توجد علامات بعد. اضغط رمز العلامة في الأعلى لتعليم الصفحة الحالية.", "No bookmarks yet. Tap the bookmark icon at the top to mark the current page."),
        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(list, key = { it.page }) { b ->
            Row(Modifier.fillMaxWidth().clickable { onGo(b.page) }.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.label.ifBlank { "${tr("صفحة", "Page")} ${b.page + 1}" }, fontWeight = FontWeight.SemiBold)
                    if (b.label.isNotBlank()) Text("${tr("صفحة", "Page")} ${b.page + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onChange(list - b) }) { Icon(Icons.Default.DeleteOutline, tr("حذف", "Delete")) }
            }
        }
    }
}

@Composable
private fun ViewSheet(vs: ViewerState) {
    SheetTitle(tr("إعدادات العرض", "View settings"))
    Text(tr("طريقة التصفّح", "Page flow"), Modifier.padding(start = 20.dp, top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    ChoiceChips(listOf("vertical" to tr("تمرير عمودي", "Vertical scroll"), "horizontal" to tr("صفحة صفحة", "Page by page")), Store.layout) { Store.layout = it; Store.save() }
    Text(tr("وضع القراءة", "Reading mode"), Modifier.padding(start = 20.dp, top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    ChoiceChips(listOf("normal" to tr("عادي", "Normal"), "night" to tr("ليلي", "Night"), "sepia" to tr("ورقي", "Sepia"), "gray" to tr("رمادي", "Gray")), Store.readMode) { Store.readMode = it; Store.save() }
    if (Store.layout == "horizontal") SwitchRow(tr("تقليب من اليمين لليسار", "Right-to-left paging"), tr("مناسب للكتب العربية", "For Arabic books"), Store.rtlPages) { Store.rtlPages = it; Store.save() }
    SwitchRow(tr("فواصل بين الصفحات", "Gaps between pages"), null, Store.pageGap) { Store.pageGap = it; Store.save() }
    SwitchRow(tr("إبقاء الشاشة مضاءة", "Keep screen on"), null, Store.keepScreenOn) { Store.keepScreenOn = it; Store.save() }
    SwitchRow(tr("ملء الشاشة عند إخفاء الأشرطة", "Full screen when bars are hidden"), null, Store.fullscreenRead) { Store.fullscreenRead = it; Store.save() }
    Text(tr("تمرير تلقائي", "Auto-scroll"), Modifier.padding(start = 20.dp, top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Speed, null)
        Slider(vs.autoScroll, { vs.autoScroll = if (it < 4f) 0f else it }, valueRange = 0f..120f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(if (vs.autoScroll <= 0f) tr("متوقف", "Off") else "${vs.autoScroll.roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(48.dp))
    }
    Text(tr("سرعة القراءة الصوتية", "Read-aloud speed"), Modifier.padding(start = 20.dp, top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
        Slider(Store.ttsRate, { Store.ttsRate = it }, valueRange = 0.5f..2f, onValueChangeFinished = { Store.save() }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("×%.1f".format(Locale.US, Store.ttsRate), fontSize = 12.sp, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun InfoSheet(doc: PdfDoc, uri: Uri, name: String, annots: Int, marks: Int) {
    val ctx = LocalContext.current
    var meta by remember { mutableStateOf<io.legere.pdfiumandroid.api.Meta?>(null) }
    LaunchedEffect(doc) { meta = doc.meta() }
    SheetTitle(tr("معلومات الملف", "Document info"))
    val m = meta
    val rows = buildList {
        add(tr("الاسم", "Name") to name)
        add(tr("الحجم", "Size") to humanSize(ctx.fileSize(uri)))
        add(tr("الصفحات", "Pages") to "${doc.count}")
        val s = doc.sizes.firstOrNull()
        if (s != null) add(tr("مقاس الصفحة", "Page size") to "%.0f × %.0f mm".format(Locale.US, s.width * 25.4 / 72, s.height * 25.4 / 72))
        m?.title?.takeIf { it.isNotBlank() }?.let { add(tr("العنوان", "Title") to it) }
        m?.author?.takeIf { it.isNotBlank() }?.let { add(tr("المؤلف", "Author") to it) }
        m?.subject?.takeIf { it.isNotBlank() }?.let { add(tr("الموضوع", "Subject") to it) }
        m?.keywords?.takeIf { it.isNotBlank() }?.let { add(tr("كلمات مفتاحية", "Keywords") to it) }
        m?.creator?.takeIf { it.isNotBlank() }?.let { add(tr("أُنشئ بـ", "Creator") to it) }
        m?.producer?.takeIf { it.isNotBlank() }?.let { add(tr("المنتِج", "Producer") to it) }
        m?.creationDate?.takeIf { it.isNotBlank() }?.let { add(tr("تاريخ الإنشاء", "Created") to pdfDate(it)) }
        m?.modDate?.takeIf { it.isNotBlank() }?.let { add(tr("آخر تعديل", "Modified") to pdfDate(it)) }
        add(tr("تعليقاتك", "Your annotations") to "$annots")
        add(tr("علاماتك", "Your bookmarks") to "$marks")
        add(tr("المسار", "Location") to Uri.decode(uri.toString()))
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(k, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(v, Modifier.weight(1f), fontSize = 14.sp)
            }
        }
    }
}

private fun pdfDate(s: String): String {
    val d = s.removePrefix("D:")
    return if (d.length >= 8) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" + (if (d.length >= 12) " ${d.substring(8, 10)}:${d.substring(10, 12)}" else "") else s
}

@Composable
private fun GoToDialog(count: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var t by remember { mutableStateOf("") }
    fun go() { t.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("").trim().toIntOrNull()?.let { onGo((it - 1).coerceIn(0, count - 1)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("الانتقال إلى صفحة", "Go to page")) },
        text = {
            OutlinedTextField(
                t, { t = it }, singleLine = true, label = { Text("1 – $count") }, placeholder = { Text("${current + 1}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
        },
        confirmButton = { TextButton(onClick = { go() }) { Text(tr("انتقال", "Go")) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onGo(0) }) { Text(tr("الأولى", "First")) }
                TextButton(onClick = { onGo(count - 1) }) { Text(tr("الأخيرة", "Last")) }
            }
        },
    )
}

@Composable
private fun NoteDialog(initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.StickyNote2, null) },
        title = { Text(tr("ملاحظة", "Note")) },
        text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text(tr("اكتب ملاحظتك…", "Write your note…")) }) },
        confirmButton = { TextButton(onClick = { onSave(t) }) { Text(tr("حفظ", "Save")) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("حذف", "Delete"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(tr("إلغاء", "Cancel")) }
            }
        },
    )
}

@Composable
private fun SignaturePad(onDismiss: () -> Unit, onSave: (List<List<NPoint>>) -> Unit) {
    val strokes = remember { mutableStateListOf<List<NPoint>>() }
    val cur = remember { mutableStateListOf<NPoint>() }
    var w by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("ارسم توقيعك", "Draw your signature")) },
        text = {
            Box(
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .onSizeChanged { w = it.width }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { cur.clear(); cur += NPoint(it.x / w, it.y / w) },
                            onDragEnd = { if (cur.size > 1) strokes += cur.toList(); cur.clear() },
                        ) { ch, _ -> cur += NPoint(ch.position.x / w, ch.position.y / w) }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val all = strokes + listOf(cur.toList())
                    all.forEach { st ->
                        if (st.size > 1) {
                            val p = Path(); p.moveTo(st[0].x * w, st[0].y * w); st.drop(1).forEach { p.lineTo(it.x * w, it.y * w) }
                            drawPath(p, Color(0xFF0B2A6F), style = Stroke(3.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    drawLine(Color(0x33000000), Offset(16f, size.height * 0.78f), Offset(size.width - 16f, size.height * 0.78f), 1.5f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (strokes.isNotEmpty()) onSave(strokes.toList()) }, enabled = strokes.isNotEmpty()) { Text(tr("استخدام", "Use")) } },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text(tr("مسح", "Clear")) }
                TextButton(onClick = onDismiss) { Text(tr("إلغاء", "Cancel")) }
            }
        },
    )
}

// ---------------- القراءة الصوتية ----------------
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val app = ctx.applicationContext
    private val tts = TextToSpeech(app, this)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    var speaking by mutableStateOf(false); private set
    var page by mutableIntStateOf(-1); private set
    var onPageDone: (Int) -> Unit = {}
    private var pendingText: Pair<Int, String>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id?.endsWith("_last") == true) main.post { val p = page; if (p >= 0 && speaking) onPageDone(p) else speaking = false } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { speaking = false } }
        })
        pendingText?.let { (p, t) -> pendingText = null; speak(p, t) }
        if (!ready) main.post { app.toast(trS("محرّك النطق غير متاح على الجهاز", "Text-to-speech is not available")) }
    }

    fun speak(p: Int, text: String) {
        if (!ready) { pendingText = p to text; speaking = true; page = p; return }
        tts.stop()
        page = p; speaking = true
        val arabic = text.count { it in '؀'..'ۿ' } > text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val r = tts.setLanguage(if (arabic) Locale("ar") else Locale.US)
        if (arabic && (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED))
            app.toast(trS("ثبّت الصوت العربي من إعدادات النطق في الجهاز", "Install an Arabic voice in the device TTS settings"))
        tts.setSpeechRate(Store.ttsRate)
        val clean = text.replace(Regex("-\\s*\\r?\\n"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) { main.postDelayed({ if (speaking) onPageDone(p) }, 300); return }
        val max = minOf(3500, TextToSpeech.getMaxSpeechInputLength() - 10)
        val parts = ArrayList<String>()
        var rest = clean
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '؟', '،', ',', ';', '\n'), max)
            if (cut < max / 3) cut = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            parts += rest.substring(0, cut + 1); rest = rest.substring(cut + 1)
        }
        parts += rest
        parts.forEachIndexed { k, s -> tts.speak(s, TextToSpeech.QUEUE_ADD, Bundle(), if (k == parts.size - 1) "p${p}_last" else "p${p}_$k") }
    }

    fun speakText(t: String) { speak(-1, t) }
    fun stop() { speaking = false; page = -1; runCatching { tts.stop() } }
    fun shutdown() { stop(); runCatching { tts.shutdown() } }
}

private fun speakFrom(sp: Speaker, vs: ViewerState, doc: PdfDoc, scope: kotlinx.coroutines.CoroutineScope, page: Int) {
    sp.onPageDone = { p ->
        if (p + 1 < doc.count) {
            vs.goTo(p + 1)
            scope.launch { sp.speak(p + 1, doc.text(p + 1)) }
        } else sp.stop()
    }
    scope.launch { sp.speak(page, doc.text(page)) }
}

@Composable
private fun TtsBar(sp: Speaker, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(if (sp.page >= 0) "${tr("قراءة الصفحة", "Reading page")} ${sp.page + 1}" else tr("قراءة النص المحدد", "Reading selection"), Modifier.weight(1f), fontSize = 13.sp)
        TextButton(onClick = onStop) { Icon(Icons.Default.Stop, null); Text(tr("إيقاف", "Stop")) }
    }
}

// ---------------- الطباعة والتصدير ----------------
fun printPdf(ctx: Context, uri: Uri, name: String, pages: Int) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(name, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(), true)
        }

        override fun onWrite(range: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) { cb.onWriteFailed(e.message) }
        }
    }, PrintAttributes.Builder().build())
}

suspend fun exportPage(ctx: Context, doc: PdfDoc, page: Int, out: Uri) {
    val s = doc.sizes[page]
    val k = minOf(3000f / maxOf(s.width, s.height), 300f / 72f)
    val bmp = doc.render(page, (s.width * k).roundToInt(), (s.height * k).roundToInt())
    if (bmp == null) { ctx.toast("Error"); return }
    withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(out, "wt")!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    ctx.toast(trS("حُفظت الصورة", "Image saved"))
}

suspend fun exportText(ctx: Context, doc: PdfDoc, out: Uri) {
    val sb = StringBuilder()
    for (i in 0 until doc.count) {
        sb.append("——— ").append(trS("صفحة", "Page")).append(' ').append(i + 1).append(" ———\n")
        sb.append(doc.text(i).replace("\r\n", "\n").trim()).append("\n\n")
    }
    withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(out, "wt")!!.use { it.write(sb.toString().toByteArray()) } }
    ctx.toast(trS("حُفظ النص", "Text saved"))
}

fun translate(ctx: Context, text: String) {
    // نفضّل ميزة «ترجمة» في النظام (Google Translate وغيره) دون أي اتصال من تطبيقنا
    val pt = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val acts = ctx.packageManager.queryIntentActivities(pt, 0)
    val tr = acts.firstOrNull { it.activityInfo.packageName.contains("translate", true) } ?: acts.firstOrNull()
    if (tr != null) ctx.safeStart(pt.setClassName(tr.activityInfo.packageName, tr.activityInfo.name))
    else ctx.safeStart(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.google.android.apps.translate"))
}
