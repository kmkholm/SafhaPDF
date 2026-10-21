// صفحة — ماسح المستندات (مثل CamScanner): كاميرا بكشف حي للورقة والتقاط تلقائي، قصّ بالزوايا مع مكبّر،
// فلاتر (سحري/رمادي/أبيض وأسود)، ترتيب الصفحات، وحفظ PDF. برمجة: د. محمد توفيق
package app.safha

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Stage { CAMERA, CROP, EDIT }

/** يرتّب أربع نقاط: أعلى يسار، أعلى يمين، أسفل يمين، أسفل يسار. */
fun orderQuad(q: FloatArray): FloatArray {
    val pts = (0 until 4).map { q[2 * it] to q[2 * it + 1] }
    val tl = pts.minBy { it.first + it.second }; val br = pts.maxBy { it.first + it.second }
    val tr = pts.maxBy { it.first - it.second }; val bl = pts.minBy { it.first - it.second }
    return floatArrayOf(tl.first, tl.second, tr.first, tr.second, br.first, br.second, bl.first, bl.second)
}

private fun filterName(f: String) = when (f) {
    "original" -> trS("الأصلي", "Original")
    "magic" -> trS("سحري", "Magic")
    "gray" -> trS("رمادي", "Gray")
    "bw" -> trS("أبيض وأسود", "B & W")
    else -> trS("فاتح", "Lighten")
}

@Composable
fun ScannerScreen(onBack: () -> Unit, onOpen: (Uri) -> Unit, onOcr: (List<File>) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember { mutableStateListOf<ScanPage>() }
    var stage by remember { mutableStateOf(Stage.CAMERA) }
    var cropIndex by remember { mutableIntStateOf(0) }
    var editIndex by remember { mutableIntStateOf(0) }
    var batch by remember { mutableStateOf(false) }
    val mutex = remember { Mutex() }
    var busy by remember { mutableStateOf<String?>(null) }

    fun reprocess(id: Long) {
        scope.launch {
            val p = pages.firstOrNull { it.id == id } ?: return@launch
            val done = mutex.withLock { withContext(Dispatchers.Default) { Scan.process(ctx, p) } }
            val i = pages.indexOfFirst { it.id == id }
            // لا نكتب فوق تعديل أحدث وصل أثناء المعالجة
            if (i >= 0 && pages[i].quad.contentEquals(p.quad) && pages[i].rotation == p.rotation && pages[i].filter == p.filter) pages[i] = done
        }
    }

    fun update(i: Int, f: (ScanPage) -> ScanPage) {
        if (i !in pages.indices) return
        pages[i] = f(pages[i]); reprocess(pages[i].id)
    }

    fun addPage(file: File, quad: FloatArray?, filter: String = "magic"): Int {
        val p = ScanPage(System.nanoTime(), file, quad ?: Scan.inset(), filter = filter)
        pages += p; reprocess(p.id)
        return pages.size - 1
    }

    // صور من المعرض
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { list ->
        if (list.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = trS("جارٍ الاستيراد…", "Importing…")
            var last = -1
            for (u in list) {
                val f = withContext(Dispatchers.IO) { Scan.importImage(ctx, u) } ?: continue
                val q = withContext(Dispatchers.Default) { Scan.load(f, 900)?.let { b -> Scan.detect(b).also { b.recycle() } } }
                last = addPage(f, q)
            }
            busy = null
            if (last >= 0) {
                if (list.size == 1 && !batch) { cropIndex = last; stage = Stage.CROP } else { editIndex = last; stage = Stage.EDIT }
            }
        }
    }

    // ماسح Google الذكي (إن توفرت خدمات Google)
    val gms = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val res = GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = trS("جارٍ الاستيراد…", "Importing…")
            var last = -1
            res.pages?.forEach { pg ->
                val f = withContext(Dispatchers.IO) { Scan.importImage(ctx, pg.imageUri) } ?: return@forEach
                last = addPage(f, Scan.FULL, filter = "original")
            }
            busy = null
            if (last >= 0) { editIndex = last; stage = Stage.EDIT }
        }
    }
    fun launchGoogle() {
        val act = ctx as? Activity ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true).setPageLimit(60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { gms.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { ctx.toast(trS("ماسح Google غير متاح على هذا الجهاز؛ استخدم الماسح المدمج.", "Google scanner is not available on this device; use the built-in scanner.")) }
    }

    BackHandler {
        when (stage) {
            Stage.CROP -> stage = if (pages.size > 1 || batch) Stage.EDIT else { pages.removeAt(cropIndex); Stage.CAMERA }
            Stage.EDIT -> stage = Stage.CAMERA
            Stage.CAMERA -> if (pages.isNotEmpty()) stage = Stage.EDIT else onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (stage) {
            Stage.CAMERA -> CameraStage(
                pages = pages, batch = batch, onBatch = { batch = it },
                onCaptured = { file, quad ->
                    val i = addPage(file, quad)
                    if (!batch) { cropIndex = i; stage = Stage.CROP }
                },
                onGallery = { gallery.launch("image/*") },
                onGoogle = ::launchGoogle,
                onDone = { if (pages.isNotEmpty()) { editIndex = pages.size - 1; stage = Stage.EDIT } },
                onBack = { if (pages.isNotEmpty()) stage = Stage.EDIT else onBack() },
            )
            Stage.CROP -> pages.getOrNull(cropIndex)?.let { p ->
                CropStage(p, onApply = { q, rot ->
                    update(cropIndex) { it.copy(quad = q, rotation = rot) }
                    editIndex = cropIndex; stage = Stage.EDIT
                }, onCancel = { stage = Stage.EDIT })
            }
            Stage.EDIT -> EditStage(
                pages, editIndex, onIndex = { editIndex = it },
                onFilter = { i, f -> update(i) { it.copy(filter = f) } },
                onFilterAll = { f -> pages.indices.forEach { i -> if (pages[i].filter != f) update(i) { it.copy(filter = f) } } },
                onRotate = { i -> update(i) { it.copy(rotation = (it.rotation + 90) % 360) } },
                onCrop = { i -> cropIndex = i; stage = Stage.CROP },
                onDelete = { i ->
                    pages.removeAt(i); if (pages.isEmpty()) stage = Stage.CAMERA else editIndex = editIndex.coerceAtMost(pages.size - 1)
                },
                onMove = { from, to -> if (to in pages.indices) { val x = pages.removeAt(from); pages.add(to, x) } },
                onAdd = { stage = Stage.CAMERA },
                onBack = onBack, onOpen = onOpen,
                onOcr = { onOcr(pages.mapNotNull { it.processed }) },
            )
        }
        busy?.let { m ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(m) }
                }
            }
        }
    }
}

// ======================= الكاميرا =======================
@Composable
private fun CameraStage(
    pages: List<ScanPage>, batch: Boolean, onBatch: (Boolean) -> Unit,
    onCaptured: (File, FloatArray?) -> Unit, onGallery: () -> Unit, onGoogle: () -> Unit, onDone: () -> Unit, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(hasCamera(ctx)) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }

    var quad by remember { mutableStateOf<FloatArray?>(null) }
    var flash by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var stableSince by remember { mutableLongStateOf(0L) }
    var shutterFlash by remember { mutableStateOf(false) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val exec = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }

    fun shoot() {
        if (capturing) return
        capturing = true; shutterFlash = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val f = File(Scan.dir(ctx), "cap_${System.nanoTime()}.jpg")
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), exec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                val file = Scan.normalizeCapture(f)
                val q = Scan.load(file, 900)?.let { b -> Scan.detect(b).also { b.recycle() } }
                scope.launch { capturing = false; stableSince = 0; onCaptured(file, q ?: quad) }
            }
            override fun onError(e: ImageCaptureException) { scope.launch { capturing = false; ctx.toast(e.message ?: "Camera error") } }
        })
    }
    LaunchedEffect(shutterFlash) { if (shutterFlash) { kotlinx.coroutines.delay(120); shutterFlash = false } }
    LaunchedEffect(flash, camera) { camera?.cameraControl?.enableTorch(flash); capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // الشريط العلوي
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(tr("ماسح المستندات", "Document scanner"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { flash = !flash }) { Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, tr("الفلاش", "Flash"), tint = Color.White) }
            IconButton(onClick = onGoogle) { Icon(Icons.Default.AutoAwesome, tr("ماسح Google الذكي", "Google smart scanner"), tint = Color(0xFFFFD54F)) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!granted) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
                Icon(Icons.Default.NoPhotography, null, tint = Color.White, modifier = Modifier.size(54.dp))
                Spacer(Modifier.height(10.dp))
                Text(tr("يحتاج الماسح إذن الكاميرا", "The scanner needs camera permission"), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { req.launch(Manifest.permission.CAMERA) }) { Text(tr("السماح بالكاميرا", "Allow camera")) }
                TextButton(onClick = onGallery) { Text(tr("أو استورد صوراً من المعرض", "Or import photos from the gallery"), color = Color.White) }
            } else {
                var box by remember { mutableStateOf(IntSize.Zero) }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AndroidView(
                        factory = { c ->
                            val pv = PreviewView(c).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }
                            val future = ProcessCameraProvider.getInstance(c)
                            future.addListener({
                                val provider = future.get()
                                val sel = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                                val preview = Preview.Builder().setResolutionSelector(sel).build().also { it.surfaceProvider = pv.surfaceProvider }
                                val analysis = ImageAnalysis.Builder()
                                    .setResolutionSelector(
                                        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                                    )
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                var smooth: FloatArray? = null
                                var misses = 0
                                analysis.setAnalyzer(exec) { img ->
                                    try {
                                        val plane = img.planes[0]
                                        val buf = plane.buffer
                                        val rs = plane.rowStride; val ps = plane.pixelStride
                                        val step = max(1, img.width / 200)
                                        val w = img.width / step; val h = img.height / step
                                        val g = IntArray(w * h)
                                        for (y in 0 until h) for (x in 0 until w) g[y * w + x] = buf.get(y * step * rs + x * step * ps).toInt() and 255
                                        val raw = Scan.detectGray(g, w, h)
                                        if (raw != null) {
                                            val r = img.imageInfo.rotationDegrees
                                            // من اتجاه الحسّاس إلى اتجاه العرض
                                            val q = orderQuad(FloatArray(8) { i ->
                                                val x = raw[i - i % 2]; val y = raw[i - i % 2 + 1]
                                                val (nx, ny) = when (r) { 90 -> (1 - y) to x; 180 -> (1 - x) to (1 - y); 270 -> y to (1 - x); else -> x to y }
                                                if (i % 2 == 0) nx else ny
                                            })
                                            val prev = smooth
                                            val next = if (prev == null) q else FloatArray(8) { prev[it] * 0.55f + q[it] * 0.45f }
                                            smooth = next
                                            misses = 0
                                            // الحركة تُقاس على الإطار المنعَّم حتى لا يمنع الارتجاف البسيط الالتقاط التلقائي
                                            val moved = if (prev == null) 1f else (0 until 8).maxOf { abs(prev[it] - next[it]) }
                                            val now = System.currentTimeMillis()
                                            scope.launch {
                                                quad = smooth
                                                if (moved > 0.012f || stableSince == 0L) stableSince = now
                                                if (auto && !capturing && now - stableSince > 1400) shoot()
                                            }
                                        } else if (++misses > 8) { smooth = null; scope.launch { quad = null; stableSince = 0 } }
                                    } catch (_: Throwable) {
                                    } finally { img.close() }
                                }
                                runCatching {
                                    provider.unbindAll()
                                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                                }.onFailure { ctx.toast(it.message ?: "Camera error") }
                            }, ContextCompat.getMainExecutor(c))
                            pv
                        },
                        modifier = Modifier.fillMaxSize().onSizeChanged { box = it },
                    )
                    // الإطار الحي حول الورقة
                    val q = quad
                    Canvas(Modifier.fillMaxSize()) {
                        val a = 3f / 4f
                        val (iw, ih) = if (size.width / size.height > a) size.height * a to size.height else size.width to size.width / a
                        val ox = (size.width - iw) / 2; val oy = (size.height - ih) / 2
                        if (q != null) {
                            val p = Path().apply {
                                moveTo(ox + q[0] * iw, oy + q[1] * ih); lineTo(ox + q[2] * iw, oy + q[3] * ih)
                                lineTo(ox + q[4] * iw, oy + q[5] * ih); lineTo(ox + q[6] * iw, oy + q[7] * ih); close()
                            }
                            drawPath(p, Color(0xFF22C55E).copy(alpha = 0.22f))
                            drawPath(p, Color(0xFF22C55E), style = Stroke(3.dp.toPx()))
                            for (k in 0 until 4) drawCircle(Color.White, 6.dp.toPx(), Offset(ox + q[2 * k] * iw, oy + q[2 * k + 1] * ih))
                        }
                        if (shutterFlash) drawRect(Color.White.copy(alpha = 0.7f))
                    }
                }
                val hint = when {
                    quad == null -> tr("ضع الورقة داخل الإطار على خلفية داكنة", "Place the page on a darker background")
                    auto -> tr("ثبّت الهاتف… سيُلتقط تلقائياً", "Hold steady… auto capture")
                    else -> tr("تم كشف الورقة", "Page detected")
                }
                Text(hint, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp)
            }
        }

        // الأوضاع
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            ModeChip(tr("صفحة واحدة", "Single"), !batch) { onBatch(false) }
            Spacer(Modifier.width(8.dp))
            ModeChip(tr("عدة صفحات", "Batch"), batch) { onBatch(true) }
            Spacer(Modifier.width(8.dp))
            ModeChip(if (auto) tr("التقاط تلقائي ✓", "Auto ✓") else tr("التقاط يدوي", "Manual"), auto) { auto = !auto }
        }
        // الأزرار السفلية
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onGallery) { Icon(Icons.Default.PhotoLibrary, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text(tr("المعرض", "Gallery"), color = Color.White, fontSize = 11.sp)
            }
            val scale by animateFloatAsState(if (capturing) 0.85f else 1f, label = "shutter")
            Box(
                Modifier.size((76 * scale).dp).clip(CircleShape).background(Color.White).border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable(enabled = granted && !capturing) { shoot() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(60.dp).clip(CircleShape).background(if (quad != null) Color(0xFF22C55E) else Brand)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val last = pages.lastOrNull()
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray).clickable(enabled = pages.isNotEmpty(), onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) {
                    if (last != null) ScanThumb(last, Modifier.fillMaxSize())
                    if (pages.isNotEmpty()) Text("${pages.size}", Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Brand).padding(horizontal = 6.dp), color = Color.White, fontSize = 11.sp)
                }
                Text(tr("تم", "Done"), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ModeChip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(
        t, Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.15f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
    )
}

/** صورة مصغّرة للصفحة (المعالجة إن وُجدت). */
@Composable
private fun ScanThumb(p: ScanPage, modifier: Modifier, maxSide: Int = 300) {
    var img by remember(p.id, p.version) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(p.id, p.version) {
        img = withContext(Dispatchers.IO) { Scan.load(p.processed ?: p.original, maxSide)?.asImageBitmap() }
    }
    Box(modifier.background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

// ======================= القصّ =======================
@Composable
private fun CropStage(p: ScanPage, onApply: (FloatArray, Int) -> Unit, onCancel: () -> Unit) {
    val dens = LocalDensity.current
    var bmp by remember(p.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(p.id) { bmp = withContext(Dispatchers.IO) { Scan.load(p.original, 1600) } }
    val q = remember(p.id) { mutableStateListOf(*p.quad.toTypedArray()) }
    var rot by remember(p.id) { mutableIntStateOf(p.rotation) }
    var drag by remember { mutableIntStateOf(-1) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(tr("اضبط حواف الورقة", "Adjust the page corners"), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(18.dp).onSizeChanged { box = it }) {
            val b = bmp
            if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val ib = remember(b) { b.asImageBitmap() }
                // مستطيل الصورة داخل المساحة
                val k = if (box.width == 0) 1f else min(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
                val iw = b.width * k; val ih = b.height * k
                val ox = (box.width - iw) / 2; val oy = (box.height - ih) / 2
                fun pt(i: Int) = Offset(ox + q[2 * i] * iw, oy + q[2 * i + 1] * ih)
                val touch = with(dens) { 36.dp.toPx() }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(b, box) {
                        detectDragGestures(
                            onDragStart = { s ->
                                // الزوايا 0..3 ثم منتصفات الأضلاع 4..7
                                val cands = (0 until 4).map { it to pt(it) } + (0 until 4).map { e -> (4 + e) to (pt(e) + pt((e + 1) % 4)) / 2f }
                                val best = cands.minBy { (it.second - s).getDistance() }
                                drag = if ((best.second - s).getDistance() < touch * 1.4f) best.first else -1
                            },
                            onDragEnd = { drag = -1 }, onDragCancel = { drag = -1 },
                        ) { ch, d ->
                            val dx = d.x / iw; val dy = d.y / ih
                            fun mv(i: Int) { q[2 * i] = (q[2 * i] + dx).coerceIn(0f, 1f); q[2 * i + 1] = (q[2 * i + 1] + dy).coerceIn(0f, 1f) }
                            when (drag) { in 0..3 -> mv(drag); in 4..7 -> { mv(drag - 4); mv((drag - 3) % 4) } }
                            ch.consume()
                        }
                    }
                ) {
                    drawImage(ib, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(ox.roundToInt(), oy.roundToInt()), IntSize(iw.roundToInt(), ih.roundToInt()))
                    val poly = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1..3) lineTo(pt(i).x, pt(i).y); close() }
                    // تعتيم خارج الورقة
                    val outside = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(ox, oy, ox + iw, oy + ih)); addPath(poly) }
                    drawPath(outside, Color.Black.copy(alpha = 0.55f))
                    val ok = Scan.convex(q.toFloatArray())
                    val col = if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)
                    drawPath(poly, col, style = Stroke(2.5.dp.toPx()))
                    for (i in 0 until 4) {
                        drawCircle(Color.White, 13.dp.toPx(), pt(i)); drawCircle(col, 13.dp.toPx(), pt(i), style = Stroke(3.dp.toPx()))
                        val m = (pt(i) + pt((i + 1) % 4)) / 2f
                        drawCircle(Color.White, 8.dp.toPx(), m); drawCircle(col, 8.dp.toPx(), m, style = Stroke(2.dp.toPx()))
                    }
                    // المكبّر أثناء سحب زاوية
                    if (drag in 0..3) {
                        val c = pt(drag)
                        val r = 62.dp.toPx(); val zoom = 2.6f
                        val center = if (c.x < size.width / 2) Offset(size.width - r - 8, r + 8) else Offset(r + 8, r + 8)
                        val circle = Path().apply { addOval(Rect(center, r)) }
                        clipPath(circle) {
                            drawRect(Color.Black, center - Offset(r, r), GSize(2 * r, 2 * r))
                            val srcX = ((c.x - ox) / k); val srcY = ((c.y - oy) / k)
                            val half = r / (k * zoom)
                            drawImage(
                                ib, IntOffset((srcX - half).roundToInt(), (srcY - half).roundToInt()), IntSize((2 * half).roundToInt(), (2 * half).roundToInt()),
                                IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()), IntSize((2 * r).roundToInt(), (2 * r).roundToInt()),
                            )
                            drawLine(col, center - Offset(14f, 0f), center + Offset(14f, 0f), 2f); drawLine(col, center - Offset(0f, 14f), center + Offset(0f, 14f), 2f)
                        }
                        drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1C1C1C)).navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkButton(Icons.Default.CropFree, tr("كشف تلقائي", "Auto detect")) {
                bmp?.let { b -> scope.launch { val d = withContext(Dispatchers.Default) { Scan.detect(b) }; (d ?: Scan.inset()).forEachIndexed { i, v -> q[i] = v } } }
            }
            DarkButton(Icons.Default.Fullscreen, tr("الصورة كاملة", "Full image")) { Scan.FULL.forEachIndexed { i, v -> q[i] = v } }
            DarkButton(Icons.AutoMirrored.Filled.RotateRight, tr("تدوير", "Rotate")) { rot = (rot + 90) % 360 }
            Button(
                onClick = { val a = q.toFloatArray(); if (Scan.convex(a)) onApply(a, rot) },
                shape = RoundedCornerShape(14.dp),
            ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(tr("تم", "Done")) }
        }
    }
}

@Composable
private fun DarkButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

// ======================= التحرير والتصدير =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStage(
    pages: List<ScanPage>, index: Int, onIndex: (Int) -> Unit,
    onFilter: (Int, String) -> Unit, onFilterAll: (String) -> Unit, onRotate: (Int) -> Unit, onCrop: (Int) -> Unit,
    onDelete: (Int) -> Unit, onMove: (Int, Int) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, onOpen: (Uri) -> Unit,
    onOcr: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val pager = rememberPagerState(initialPage = index.coerceIn(0, max(0, pages.size - 1))) { pages.size }
    LaunchedEffect(pager.currentPage) { onIndex(pager.currentPage) }
    LaunchedEffect(index, pages.size) { if (index in pages.indices && index != pager.currentPage) pager.scrollToPage(index) }
    var name by remember { mutableStateOf("Scan " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())) }
    var size by remember { mutableStateOf("a4") }
    var reorder by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    val cur = pages.getOrNull(pager.currentPage)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        if (out == null) return@rememberLauncherForActivityResult
        scope.launch {
            saving = true
            // ننتظر انتهاء معالجة كل الصفحات
            while (pages.any { it.processed == null }) kotlinx.coroutines.delay(150)
            val r = runCatching { withContext(Dispatchers.IO) { Scan.exportPdf(ctx, pages.toList(), size, out) } }
            saving = false
            r.onSuccess { saved = out; runCatching { ctx.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                .onFailure { ctx.toast(trS("فشل الحفظ: ", "Save failed: ") + it.message) }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, null) }
            Column(Modifier.weight(1f)) {
                Text(tr("مراجعة الصفحات", "Review pages"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${tr("صفحة", "Page")} ${ltr("${pager.currentPage + 1} / ${pages.size}")}", fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            IconButton(onClick = { reorder = true }) { Icon(Icons.Default.GridView, tr("ترتيب", "Reorder")) }
            IconButton(onClick = onAdd) { Icon(Icons.Default.AddAPhoto, tr("إضافة صفحة", "Add page")) }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp), pageSpacing = 12.dp) { i ->
            val p = pages[i]
            Box(Modifier.fillMaxSize().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                ScanThumb(p, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), maxSide = 1400)
                if (p.processed == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Text(tr("جارٍ التحسين…", "Enhancing…"), color = Color.White)
                }
            }
        }

        Surface(color = c.surface, shadowElevation = 8.dp) {
            Column(Modifier.navigationBarsPadding()) {
                // الفلاتر
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanFilters.all.forEach { f ->
                        FilterChip(selected = cur?.filter == f, onClick = { onFilter(pager.currentPage, f) }, label = { Text(filterName(f)) },
                            leadingIcon = { Icon(when (f) { "original" -> Icons.Default.Image; "magic" -> Icons.Default.AutoFixHigh; "gray" -> Icons.Default.Gradient; "bw" -> Icons.Default.Contrast; else -> Icons.Default.WbSunny }, null, Modifier.size(18.dp)) })
                    }
                    AssistChip(onClick = { cur?.let { onFilterAll(it.filter) } }, label = { Text(tr("تطبيق على الكل", "Apply to all")) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton(Icons.Default.Crop, tr("قص", "Crop"), modifier = Modifier.weight(1f)) { onCrop(pager.currentPage) }
                    BarButton(Icons.AutoMirrored.Filled.RotateRight, tr("تدوير", "Rotate"), modifier = Modifier.weight(1f)) { onRotate(pager.currentPage) }
                    BarButton(Icons.Default.AddAPhoto, tr("صفحة أخرى", "Add page"), modifier = Modifier.weight(1f), onClick = onAdd)
                    BarButton(Icons.Default.DeleteOutline, tr("حذف", "Delete"), modifier = Modifier.weight(1f)) { onDelete(pager.currentPage) }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), singleLine = true,
                    label = { Text(tr("اسم الملف", "File name")) }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                ChoiceChips(listOf("a4" to "A4", "letter" to "Letter", "fit" to tr("بحجم الصورة", "Fit image")), size) { size = it }
                Button(
                    onClick = { create.launch(name.trim().ifBlank { "Scan" }.removeSuffix(".pdf") + ".pdf") }, enabled = !saving && pages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = c.onPrimary, strokeWidth = 2.dp)
                    else { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("${tr("حفظ PDF", "Save PDF")} (${pages.size})", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    if (reorder) ModalBottomSheet(onDismissRequest = { reorder = false }) {
        SheetTitle(tr("ترتيب الصفحات", "Reorder pages"), tr("حرّك الصفحات أو احذفها", "Move or delete pages"))
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(10.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScanThumb(p, Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).clickable { onIndex(i); reorder = false })
                    Text("${i + 1}", fontSize = 12.sp)
                    Row {
                        IconButton(onClick = { onMove(i, i - 1) }, enabled = i > 0, modifier = Modifier.size(30.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)) }
                        IconButton(onClick = { onDelete(i) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)) }
                        IconButton(onClick = { onMove(i, i + 1) }, enabled = i < pages.size - 1, modifier = Modifier.size(30.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(10.dp))
    }

    saved?.let { out ->
        AlertDialog(
            onDismissRequest = { saved = null },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = c.secondary) },
            title = { Text(tr("حُفظ ملف PDF", "PDF saved")) },
            text = { Text("${ctx.displayName(out)} · ${pages.size} ${tr("صفحة", "pages")} · ${humanSize(ctx.fileSize(out))}") },
            confirmButton = { Button(onClick = { saved = null; onOpen(out) }) { Text(tr("فتح", "Open")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { ctx.sharePdf(out, ctx.displayName(out)) }) { Text(tr("مشاركة", "Share")) }
                    TextButton(onClick = { saved = null }) { Text(tr("إغلاق", "Close")) }
                }
            },
        )
    }
}
