// صفحة — عارض الصفحات: تمرير، تكبير، تحديد نص، رسم، بحث، روابط. برمجة: د. محمد توفيق
package app.safha

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { NONE, PEN, MARKER, ERASER, NOTE, SIGN }

data class Sel(val page: Int, val start: Int, val end: Int)

private class Tile(val page: Int, val doc: RectF, val bmp: Bitmap, val scale: Float)

class ViewerState(val doc: PdfDoc, val scope: CoroutineScope, private val density: Float) {
    var vw by mutableIntStateOf(0); private set
    var vh by mutableIntStateOf(0); private set
    var scale by mutableFloatStateOf(1f); private set
    var ox by mutableFloatStateOf(0f); private set
    var oy by mutableFloatStateOf(0f); private set
    var horizontal = Store.layout == "horizontal"; private set
    var rtl = Store.rtlPages; private set
    var gapOn = Store.pageGap; private set
    var currentPage by mutableIntStateOf(0); private set
    var tick by mutableIntStateOf(0); private set

    private var rects: Array<RectF> = emptyArray()
    private var contentW = 1f
    private var contentH = 1f
    private var pendingPage = -1

    // ذاكرة الصور
    private val base = LinkedHashMap<Int, Bitmap>(32, 0.75f, true)
    private var baseBytes = 0L
    private val budget = Runtime.getRuntime().maxMemory() / 4
    private val pending = HashSet<Int>()
    @Volatile private var wantFrom = 0
    @Volatile private var wantTo = 0
    private var tiles by mutableStateOf<List<Tile>>(emptyList())

    // التحديد
    var sel by mutableStateOf<Sel?>(null); private set
    var selRects by mutableStateOf<List<NRect>>(emptyList()); private set
    private var startBox: NRect? = null
    private var endBox: NRect? = null
    var selecting by mutableStateOf(false); private set
    private val selChan = Channel<Pair<Int, Offset>>(Channel.CONFLATED)
    private var anchor = -1
    private var anchorEnd = -1

    // البحث
    var hits by mutableStateOf<List<Hit>>(emptyList())
    var hitIndex by mutableIntStateOf(-1)
    private val hitRects = mutableStateMapOf<Hit, List<NRect>>()

    // التعليقات والرسم
    val annots = mutableStateListOf<Annot>()
    var tool by mutableStateOf(Tool.NONE)
    var penColor by mutableIntStateOf(0xFFE11D48.toInt())
    var markerColor by mutableIntStateOf(0xFFFACC15.toInt())
    var penWidth by mutableFloatStateOf(0.004f)
    private val stroke = mutableStateListOf<NPoint>()
    private var strokePage = -1
    private val undo = ArrayDeque<List<Annot>>()
    private val redo = ArrayDeque<List<Annot>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    // نداءات للواجهة
    var onTap: () -> Unit = {}
    var onLink: (PLink) -> Unit = {}
    var onSelection: (Sel?) -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onNote: (Annot) -> Unit = {}
    var onPlace: (Tool, Int, NPoint) -> Unit = { _, _, _ -> }
    var onAnnotsChanged: (List<Annot>) -> Unit = {}

    private var motion: Job? = null
    var autoScroll by mutableFloatStateOf(0f)   // بكسل/ثانية؛ 0 = متوقف

    init {
        scope.launch {
            selChan.receiveAsFlow().collect { (kind, p) -> handleSel(kind, p) }
        }
    }

    // ---------------- التخطيط ----------------
    fun setViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == vw && h == vh)) return
        val keep = if (rects.isEmpty()) pendingPage.coerceAtLeast(0) else currentPage
        vw = w; vh = h
        relayout()
        goTo(keep, animate = false)
    }

    /** مساحة الأشرطة العلوية والسفلية حتى لا تغطي أول صفحة أو آخرها. */
    private var insetTop = 0f
    private var insetBottom = 0f
    fun setInsets(top: Float, bottom: Float) {
        if (top == insetTop && bottom == insetBottom) return
        val keep = currentPage
        insetTop = top; insetBottom = bottom
        if (vw > 0) { relayout(); goTo(keep, animate = false) }
    }

    fun setMode(horizontal: Boolean, rtl: Boolean, gap: Boolean) {
        if (horizontal == this.horizontal && rtl == this.rtl && gap == this.gapOn) return
        val keep = currentPage
        this.horizontal = horizontal; this.rtl = rtl; this.gapOn = gap
        relayout(); goTo(keep, animate = false)
    }

    private fun relayout() {
        if (vw == 0) return
        val n = doc.count
        val gap = if (gapOn) 8f * density else 0f
        base.clear(); baseBytes = 0; tiles = emptyList(); pending.clear()
        if (!horizontal) {
            val maxW = doc.sizes.maxOf { it.width }.toFloat()
            var y = insetTop + gap
            rects = Array(n) { i ->
                val s = doc.sizes[i]
                val w = vw * (s.width / maxW)
                val h = w * s.height / s.width
                val x = (vw - w) / 2f
                RectF(x, y, x + w, y + h).also { y += h + gap }
            }
            contentW = vw.toFloat(); contentH = y + insetBottom
        } else {
            rects = Array(n) { i ->
                val s = doc.sizes[i]
                val avail = vh - insetTop - insetBottom
                val f = min((vw - gap * 2) / s.width, (avail - gap * 2) / s.height)
                val w = s.width * f; val h = s.height * f
                val slot = if (rtl) n - 1 - i else i
                val x = slot * vw + (vw - w) / 2f
                val top = insetTop + (avail - h) / 2f
                RectF(x, top, x + w, top + h)
            }
            contentW = n * vw.toFloat(); contentH = vh.toFloat()
        }
        scale = 1f
        clamp(); update()
    }

    fun pageRect(i: Int): RectF? = rects.getOrNull(i)

    private fun clamp() {
        val cw = contentW * scale; val ch = contentH * scale
        ox = if (cw <= vw) (vw - cw) / 2f else ox.coerceIn(vw - cw, 0f)
        oy = if (ch <= vh) (vh - ch) / 2f else oy.coerceIn(vh - ch, 0f)
    }

    private fun minScale() = if (horizontal) 1f else 0.5f

    fun screenRect(i: Int): Rect {
        val r = rects[i]
        return Rect(r.left * scale + ox, r.top * scale + oy, r.right * scale + ox, r.bottom * scale + oy)
    }

    private fun visibleRange(): IntRange {
        if (rects.isEmpty()) return IntRange.EMPTY
        var a = -1; var b = -1
        for (i in rects.indices) {
            val r = screenRect(i)
            if (r.right >= 0 && r.left <= vw && r.bottom >= 0 && r.top <= vh) { if (a < 0) a = i; b = i } else if (a >= 0 && !horizontal) break
        }
        return if (a < 0) IntRange.EMPTY else a..b
    }

    /** يُستدعى بعد كل تغيير في الموضع أو التكبير. */
    private fun update() {
        if (rects.isEmpty()) return
        // الصفحة الحالية = التي في أعلى المساحة الظاهرة (عند ربعها الأول)، لا التي في المنتصف؛
        // وإلا قفز العارض إلى الصفحة 3 أو 4 في الملفات ذات الصفحات العرضية القصيرة
        val cx = (vw / 2f - ox) / scale
        val atEnd = !horizontal && oy <= vh - contentH * scale + 1f && contentH * scale > vh
        val refY = if (atEnd) vh - insetBottom - 1f else insetTop + (vh - insetTop - insetBottom) * 0.25f
        val cy = (refY - oy) / scale
        var best = currentPage.coerceIn(0, rects.size - 1); var bd = Float.MAX_VALUE
        for (i in rects.indices) {
            val r = rects[i]
            val d = if (horizontal) (if (cx < r.left) r.left - cx else if (cx > r.right) cx - r.right else 0f)
            else (if (cy < r.top) r.top - cy else if (cy > r.bottom) cy - r.bottom else 0f)
            if (d < bd) { bd = d; best = i }
            if (d == 0f) break
        }
        if (best != currentPage) currentPage = best
        val vis = visibleRange()
        if (!vis.isEmpty()) { wantFrom = max(0, vis.first - 2); wantTo = min(rects.size - 1, vis.last + 2) }
        requestBase(vis)
    }

    // ---------------- الرسم في الخلفية ----------------
    private fun baseSize(i: Int): IntSize {
        val r = rects[i]
        var w = r.width(); var h = r.height()
        val cap = 2200f / max(w, h)
        if (cap < 1f) { w *= cap; h *= cap }
        return IntSize(max(1, w.roundToInt()), max(1, h.roundToInt()))
    }

    private fun requestBase(vis: IntRange) {
        if (vis.isEmpty()) return
        val order = ArrayList<Int>()
        for (i in vis) order += i
        for (d in 1..2) { if (vis.last + d < rects.size) order += vis.last + d; if (vis.first - d >= 0) order += vis.first - d }
        for (i in order) {
            if (base.containsKey(i) || i in pending) continue
            pending += i
            val sz = baseSize(i)
            scope.launch {
                val bmp = doc.render(i, sz.width, sz.height) { i in wantFrom..wantTo }
                pending -= i
                if (bmp != null) {
                    base[i] = bmp; baseBytes += bmp.byteCount
                    trim(); tick++
                }
            }
        }
    }

    private fun trim() {
        val it = base.entries.iterator()
        while (baseBytes > budget && it.hasNext()) {
            val e = it.next()
            if (e.key in wantFrom..wantTo) continue
            baseBytes -= e.value.byteCount; it.remove()
        }
    }

    /** صور أوضح للجزء الظاهر عند التكبير. */
    suspend fun sharpLoop() {
        snapshotFlow { Triple(scale, ox, oy) to (tick to vw) }.collectLatest {
            delay(140)
            if (scale <= 1.05f || rects.isEmpty()) { if (tiles.isNotEmpty()) tiles = emptyList(); return@collectLatest }
            val s = scale
            val out = ArrayList<Tile>()
            for (i in visibleRange()) {
                val sr = screenRect(i)
                val l = max(0f, sr.left); val t = max(0f, sr.top); val r = min(vw.toFloat(), sr.right); val b = min(vh.toFloat(), sr.bottom)
                if (r - l < 2 || b - t < 2) continue
                val bmp = doc.renderRegion(
                    i, (r - l).roundToInt(), (b - t).roundToInt(),
                    -(l - sr.left).roundToInt(), -(t - sr.top).roundToInt(), sr.width.roundToInt(), sr.height.roundToInt()
                ) ?: continue
                out += Tile(i, RectF((l - ox) / s, (t - oy) / s, (r - ox) / s, (b - oy) / s), bmp, s)
            }
            if (s == scale) tiles = out
        }
    }

    // ---------------- الحركة ----------------
    fun stopMotion() { motion?.cancel(); motion = null }

    fun transform(focal: Offset, pan: Offset, zoom: Float) {
        val ns = (scale * zoom).coerceIn(minScale(), 8f)
        val k = ns / scale
        ox = focal.x - (focal.x - ox) * k + pan.x
        oy = focal.y - (focal.y - oy) * k + pan.y
        scale = ns
        clamp(); update()
    }

    fun endDrag(vx: Float, vy: Float, multi: Boolean) {
        if (horizontal && scale <= 1.01f) { snapPage(vx); return }
        if (multi) return
        fling(vx, vy)
    }

    private fun fling(vx0: Float, vy0: Float) {
        stopMotion()
        if (abs(vx0) < 50 && abs(vy0) < 50) return
        motion = scope.launch {
            var vx = vx0; var vy = vy0
            var last = 0L
            while (isActive && (abs(vx) > 20 || abs(vy) > 20)) {
                withFrameNanos { t ->
                    if (last != 0L) {
                        val dt = (t - last) / 1e9f
                        val px = ox; val py = oy
                        ox += vx * dt; oy += vy * dt
                        clamp(); update()
                        if (ox == px) vx = 0f
                        if (oy == py) vy = 0f
                        val f = exp(-3.2f * dt)
                        vx *= f; vy *= f
                    }
                    last = t
                }
            }
        }
    }

    private fun snapPage(vx: Float) {
        val cur = (-ox / vw).let { if (vx < -400) kotlin.math.ceil(it) else if (vx > 400) kotlin.math.floor(it) else it.roundToInt().toFloat() }
        val slot = cur.toInt().coerceIn(0, rects.size - 1)
        animateTo(1f, -slot * vw.toFloat(), oy)
    }

    private fun animateTo(ts: Float, tx: Float, ty: Float, ms: Int = 260) {
        stopMotion()
        val s0 = scale; val x0 = ox; val y0 = oy
        motion = scope.launch {
            var start = 0L
            while (isActive) {
                val done = withFrameNanos { t ->
                    if (start == 0L) start = t
                    val p = ((t - start) / 1e6f / ms).coerceIn(0f, 1f)
                    val e = 1 - (1 - p) * (1 - p) * (1 - p)
                    scale = s0 + (ts - s0) * e; ox = x0 + (tx - x0) * e; oy = y0 + (ty - y0) * e
                    clamp(); update()
                    p >= 1f
                }
                if (done) break
            }
        }
    }

    fun doubleTap(p: Offset) {
        val ts = if (scale < 1.6f) 2.5f else 1f
        val k = ts / scale
        animateTo(ts, p.x - (p.x - ox) * k, p.y - (p.y - oy) * k)
    }

    fun goTo(i: Int, animate: Boolean = true) {
        if (rects.isEmpty()) { pendingPage = i; return }
        val p = i.coerceIn(0, rects.size - 1)
        val r = rects[p]
        val tx = if (horizontal) -(r.left - (vw - r.width()) / 2f) * scale else ox
        val ty = if (horizontal) oy else insetTop + (if (gapOn) 8f * density else 0f) - r.top * scale
        if (animate && abs(p - currentPage) <= 3) animateTo(scale, tx, ty)
        else { stopMotion(); ox = tx; oy = ty; clamp(); update() }
    }

    /** يمرّر بحيث يظهر مستطيل مطبَّع من صفحة (لنتائج البحث). */
    fun reveal(page: Int, nr: NRect) {
        val r = rects.getOrNull(page) ?: return
        val x = (r.left + nr.l * r.width()) * scale + ox
        val y = (r.top + nr.t * r.height()) * scale + oy
        if (x in 0f..vw.toFloat() && y in (vh * 0.12f)..(vh * 0.8f)) return
        val tx: Float; val ty: Float
        if (horizontal) {
            tx = if (scale <= 1.01f) -(r.left - (vw - r.width()) / 2f) * scale else ox - (x - vw / 2f)
            ty = if (scale <= 1.01f) oy else oy - (y - vh * 0.4f)
        } else {
            tx = if (x in 0f..vw.toFloat()) ox else ox - (x - vw / 2f)
            ty = oy - (y - vh * 0.35f)
        }
        animateTo(scale, tx, ty)
    }

    var fraction: Float
        get() {
            return if (horizontal) { val m = contentW * scale - vw; if (m <= 0) 0f else (-ox / m).coerceIn(0f, 1f) }
            else { val m = contentH * scale - vh; if (m <= 0) 0f else (-oy / m).coerceIn(0f, 1f) }
        }
        set(f) {
            stopMotion()
            if (horizontal) ox = -f * (contentW * scale - vw) else oy = -f * (contentH * scale - vh)
            clamp(); update()
        }

    suspend fun autoScrollLoop() {
        snapshotFlow { autoScroll }.collectLatest { speed ->
            if (speed <= 0f) return@collectLatest
            var last = 0L
            while (true) {
                withFrameNanos { t ->
                    if (last != 0L && motion == null) {
                        val dt = (t - last) / 1e9f
                        if (horizontal) ox -= speed * density * dt else oy -= speed * density * dt
                        clamp(); update()
                    }
                    last = t
                }
            }
        }
    }

    // ---------------- اللمس ----------------
    /** أي صفحة تحت النقطة، والنقطة مطبَّعة عليها. */
    fun hit(p: Offset, onlyPage: Int = -1): Pair<Int, NPoint>? {
        if (onlyPage >= 0 && onlyPage < rects.size) {
            val r = screenRect(onlyPage)
            return onlyPage to NPoint(((p.x - r.left) / r.width).coerceIn(0f, 1f), ((p.y - r.top) / r.height).coerceIn(0f, 1f))
        }
        for (i in visibleRange()) {
            val r = screenRect(i)
            if (r.contains(p)) return i to NPoint(((p.x - r.left) / r.width).coerceIn(0f, 1f), ((p.y - r.top) / r.height).coerceIn(0f, 1f))
        }
        return null
    }

    fun tap(p: Offset) {
        if (sel != null) { clearSel(); return }
        val h = hit(p)
        if (h != null && (tool == Tool.NOTE || tool == Tool.SIGN)) { onPlace(tool, h.first, h.second); return }
        if (h != null) {
            // ملاحظة؟
            annots.lastOrNull { a -> a.page == h.first && a.type == AnnotType.NOTE && a.rects.firstOrNull()?.let { inside(it, h.second, 0.03f) } == true }
                ?.let { onNote(it); return }
            scope.launch {
                val link = doc.links(h.first).lastOrNull { inside(it.rect, h.second, 0.004f) }
                if (link != null) onLink(link) else onTap()
            }
            return
        }
        onTap()
    }

    private fun inside(r: NRect, p: NPoint, pad: Float) = p.x >= r.l - pad && p.x <= r.r + pad && p.y >= r.t - pad && p.y <= r.b + pad

    // ----- تحديد النص -----
    fun longPress(p: Offset) { selChan.trySend(0 to p) }
    fun extendSel(p: Offset) { selChan.trySend(1 to p) }
    fun dragHandle(which: Int, p: Offset) { selChan.trySend((if (which == 1) 2 else 3) to p) }

    private suspend fun handleSel(kind: Int, p: Offset) {
        val cur = sel
        if (kind != 0 && cur == null) return
        val h = (if (kind == 0) hit(p) else hit(p, onlyPage = cur!!.page)) ?: return
        val (page, np) = h
        val idx = doc.charAt(page, np.x, np.y, if (kind == 0) 8.0 else 30.0)
        if (idx < 0) return
        val text = doc.text(page)
        when (kind) {
            0 -> {
                if (idx >= text.length) return
                fun word(c: Char) = c.isLetterOrDigit() || c == 'ـ' || c in "ًٌٍَُِّْ'’-_"
                // إن وقعت اللمسة على فراغ نأخذ أقرب كلمة
                val at = when {
                    word(text[idx]) -> idx
                    idx + 1 < text.length && word(text[idx + 1]) -> idx + 1
                    idx > 0 && word(text[idx - 1]) -> idx - 1
                    else -> idx
                }
                var s = at; var e = at
                if (word(text[at])) {
                    while (s > 0 && word(text[s - 1])) s--
                    while (e < text.length - 1 && word(text[e + 1])) e++
                }
                anchor = s; anchorEnd = e
                setSel(Sel(page, s, e)); selecting = true
                onLongPress()
            }
            1 -> setSel(if (idx >= anchor) Sel(page, anchor, max(idx, anchorEnd)) else Sel(page, idx, anchorEnd))
            2 -> setSel(if (idx <= cur!!.end) Sel(page, idx, cur.end) else Sel(page, cur.end, idx))
            3 -> setSel(if (idx >= cur!!.start) Sel(page, cur.start, idx) else Sel(page, idx, cur.start))
        }
    }

    private suspend fun setSel(s: Sel) {
        if (s == sel) return
        sel = s
        selRects = doc.rects(s.page, s.start, s.end - s.start + 1)
        startBox = doc.rects(s.page, s.start, 1).firstOrNull() ?: selRects.firstOrNull()
        endBox = doc.rects(s.page, s.end, 1).lastOrNull() ?: selRects.lastOrNull()
        onSelection(s)
    }

    fun selectionDone() { selecting = false; onSelection(sel) }

    fun clearSel() {
        sel = null; selRects = emptyList(); startBox = null; endBox = null; selecting = false
        onSelection(null)
    }

    suspend fun selectedText(): String {
        val s = sel ?: return ""
        val t = doc.text(s.page)
        return t.substring(s.start.coerceIn(0, t.length), (s.end + 1).coerceIn(0, t.length)).replace("\r\n", "\n").trim()
    }

    fun selectAllOnPage() {
        scope.launch {
            val p = currentPage
            val n = doc.charCount(p)
            if (n > 0) { anchor = 0; anchorEnd = 0; setSel(Sel(p, 0, n - 1)); onSelection(sel) }
        }
    }

    private fun handlePoints(): Pair<Offset, Offset>? {
        val s = sel ?: return null
        val a = startBox ?: return null; val b = endBox ?: return null
        val r = screenRect(s.page)
        return Offset(r.left + a.l * r.width, r.top + a.b * r.height) to Offset(r.left + b.r * r.width, r.top + b.b * r.height)
    }

    /** 1 = مقبض البداية، 2 = مقبض النهاية، 0 = لا شيء */
    fun handleAt(p: Offset, radius: Float): Int {
        val (a, b) = handlePoints() ?: return 0
        val da = (p - (a + Offset(0f, radius * 0.6f))).getDistance()
        val db = (p - (b + Offset(0f, radius * 0.6f))).getDistance()
        return when { da < radius && da <= db -> 1; db < radius -> 2; else -> 0 }
    }

    // ----- البحث -----
    fun hitRectsFor(h: Hit) {
        if (hitRects.containsKey(h)) return
        scope.launch { hitRects[h] = doc.rects(h.page, h.start, h.count) }
    }

    fun clearHits() { hits = emptyList(); hitIndex = -1; hitRects.clear() }

    fun focusHit(i: Int) {
        if (hits.isEmpty()) return
        hitIndex = (i + hits.size) % hits.size
        val h = hits[hitIndex]
        scope.launch {
            val rs = hitRects[h] ?: doc.rects(h.page, h.start, h.count).also { hitRects[h] = it }
            if (h.page != currentPage) goTo(h.page, animate = false)
            rs.firstOrNull()?.let { reveal(h.page, it) }
        }
    }

    // ----- التعليقات -----
    fun setAnnots(list: List<Annot>) { annots.clear(); annots.addAll(list) }

    private fun snapshotUndo() {
        undo.addLast(annots.toList()); if (undo.size > 60) undo.removeFirst()
        redo.clear(); canUndo = true; canRedo = false
    }

    fun addAnnot(a: Annot) { snapshotUndo(); annots += a; onAnnotsChanged(annots.toList()) }
    fun replaceAnnot(a: Annot) { snapshotUndo(); val i = annots.indexOfFirst { it.id == a.id }; if (i >= 0) annots[i] = a; onAnnotsChanged(annots.toList()) }
    fun removeAnnot(id: Long) { snapshotUndo(); annots.removeAll { it.id == id }; onAnnotsChanged(annots.toList()) }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(annots.toList()); annots.clear(); annots.addAll(prev)
        canUndo = undo.isNotEmpty(); canRedo = true; onAnnotsChanged(annots.toList())
    }

    fun redo() {
        val nxt = redo.removeLastOrNull() ?: return
        undo.addLast(annots.toList()); annots.clear(); annots.addAll(nxt)
        canUndo = true; canRedo = redo.isNotEmpty(); onAnnotsChanged(annots.toList())
    }

    fun markSelection(type: Int, color: Int) {
        val s = sel ?: return
        if (selRects.isEmpty()) return
        addAnnot(Annot(System.currentTimeMillis(), s.page, type, color, rects = mergeLines(selRects)))
        clearSel()
    }

    private fun mergeLines(rs: List<NRect>): List<NRect> {
        val out = ArrayList<NRect>()
        for (r in rs.sortedWith(compareBy({ ((it.t + it.b) / 2 * 400).roundToInt() }, { it.l }))) {
            val last = out.lastOrNull()
            if (last != null && abs((last.t + last.b) / 2 - (r.t + r.b) / 2) < (last.b - last.t) * 0.5f && r.l - last.r < 0.02f)
                out[out.size - 1] = NRect(min(last.l, r.l), min(last.t, r.t), max(last.r, r.r), max(last.b, r.b))
            else out += r
        }
        return out
    }

    fun beginStroke(p: Offset): Boolean {
        val h = hit(p) ?: return false
        strokePage = h.first; stroke.clear(); stroke += h.second
        return true
    }

    fun addStrokePoint(p: Offset) {
        if (strokePage < 0) return
        val r = screenRect(strokePage)
        stroke += NPoint(((p.x - r.left) / r.width).coerceIn(0f, 1f), ((p.y - r.top) / r.height).coerceIn(0f, 1f))
    }

    fun cancelStroke() { stroke.clear(); strokePage = -1 }

    fun endStroke() {
        if (strokePage >= 0 && stroke.size >= 2) {
            val marker = tool == Tool.MARKER
            addAnnot(
                Annot(
                    System.currentTimeMillis(), strokePage, AnnotType.INK,
                    if (marker) (markerColor and 0x00FFFFFF) or (0x66 shl 24) else penColor,
                    width = if (marker) penWidth * 4f else penWidth, strokes = listOf(stroke.toList()),
                )
            )
        }
        cancelStroke()
    }

    fun eraseAt(p: Offset) {
        val (page, np) = hit(p) ?: return
        val victim = annots.lastOrNull { a ->
            a.page == page && (a.rects.any { inside(it, np, 0.008f) } || a.strokes.any { st -> st.any { abs(it.x - np.x) < 0.02f && abs(it.y - np.y) < 0.02f } })
        } ?: return
        removeAnnot(victim.id)
    }

    // ---------------- الرسم على الشاشة ----------------
    fun draw(ds: DrawScope, filter: ColorFilter?, pageColor: Color, bg: Color, selColor: Color) = with(ds) {
        if (tick < 0) return@with
        drawRect(bg)
        if (rects.isEmpty()) return@with
        val tl = tiles
        for (i in visibleRange()) {
            val r = screenRect(i)
            if (gapOn) drawRect(Color.Black.copy(alpha = 0.10f), Offset(r.left + 2, r.top + 3), r.size)
            drawRect(pageColor, r.topLeft, r.size)
            base[i]?.let { b ->
                drawImage(
                    b.asImageBitmap(), IntOffset.Zero, IntSize(b.width, b.height),
                    IntOffset(r.left.roundToInt(), r.top.roundToInt()), IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    colorFilter = filter, filterQuality = FilterQuality.Medium,
                )
            }
            clipRect(r.left, r.top, r.right, r.bottom) {
                for (t in tl) if (t.page == i) {
                    val d = t.doc
                    drawImage(
                        t.bmp.asImageBitmap(), IntOffset.Zero, IntSize(t.bmp.width, t.bmp.height),
                        IntOffset((d.left * scale + ox).roundToInt(), (d.top * scale + oy).roundToInt()),
                        IntSize(((d.right - d.left) * scale).roundToInt(), ((d.bottom - d.top) * scale).roundToInt()),
                        colorFilter = filter, filterQuality = FilterQuality.Low,
                    )
                }
                drawAnnots(i, r)
                // نتائج البحث
                val cur = hits.getOrNull(hitIndex)
                for (h in hits) if (h.page == i) {
                    val rs = hitRects[h]
                    if (rs == null) { hitRectsFor(h); continue }
                    val c = if (h == cur) Color(0xFFFF7A00).copy(alpha = 0.55f) else Color(0xFFFFE600).copy(alpha = 0.45f)
                    rs.forEach { nr -> drawRect(c, Offset(r.left + nr.l * r.width, r.top + nr.t * r.height), Size((nr.r - nr.l) * r.width, (nr.b - nr.t) * r.height)) }
                }
                // التحديد
                if (sel?.page == i) selRects.forEach { nr ->
                    drawRect(selColor.copy(alpha = 0.32f), Offset(r.left + nr.l * r.width, r.top + nr.t * r.height), Size((nr.r - nr.l) * r.width, (nr.b - nr.t) * r.height))
                }
            }
        }
        // المقابض
        handlePoints()?.let { (a, b) ->
            val rad = 9f * density
            listOf(a, b).forEach { p ->
                drawLine(selColor, p, p + Offset(0f, rad * 0.4f), strokeWidth = 2f * density)
                drawCircle(selColor, rad, p + Offset(0f, rad * 1.3f))
            }
        }
    }

    private fun DrawScope.drawAnnots(i: Int, r: Rect) {
        fun pt(p: NPoint) = Offset(r.left + p.x * r.width, r.top + p.y * r.height)
        for (a in annots) if (a.page == i) {
            val c = Color(a.color)
            when (a.type) {
                AnnotType.HIGHLIGHT -> a.rects.forEach { nr ->
                    drawRect(c.copy(alpha = 0.38f), Offset(r.left + nr.l * r.width, r.top + nr.t * r.height), Size((nr.r - nr.l) * r.width, (nr.b - nr.t) * r.height))
                }
                AnnotType.UNDERLINE, AnnotType.STRIKE -> a.rects.forEach { nr ->
                    val y = r.top + (if (a.type == AnnotType.UNDERLINE) nr.b - (nr.b - nr.t) * 0.04f else (nr.t + nr.b) / 2f) * r.height
                    drawLine(c, Offset(r.left + nr.l * r.width, y), Offset(r.left + nr.r * r.width, y), strokeWidth = max(1.5f, (nr.b - nr.t) * r.height * 0.09f))
                }
                AnnotType.INK -> a.strokes.forEach { st -> drawPath(smooth(st.map(::pt)), c, style = Stroke(a.width * r.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                AnnotType.NOTE -> a.rects.firstOrNull()?.let { nr ->
                    val s = 22f * density
                    val o = Offset(r.left + nr.l * r.width - s / 2, r.top + nr.t * r.height - s / 2)
                    drawRoundRect(c, o, Size(s, s), CornerRadius(5f * density))
                    for (k in 0..2) drawLine(Color(0xAA3B2A00), o + Offset(s * 0.22f, s * (0.3f + k * 0.2f)), o + Offset(s * 0.78f, s * (0.3f + k * 0.2f)), strokeWidth = 1.6f * density)
                }
            }
        }
        if (strokePage == i && stroke.size > 1) {
            val marker = tool == Tool.MARKER
            val c = if (marker) Color(markerColor).copy(alpha = 0.4f) else Color(penColor)
            drawPath(smooth(stroke.map(::pt)), c, style = Stroke((if (marker) penWidth * 4f else penWidth) * r.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    private fun smooth(p: List<Offset>): Path {
        val path = Path()
        if (p.isEmpty()) return path
        path.moveTo(p[0].x, p[0].y)
        if (p.size == 1) { path.lineTo(p[0].x + 0.1f, p[0].y); return path }
        for (k in 1 until p.size) {
            val a = p[k - 1]; val b = p[k]
            path.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
        path.lineTo(p.last().x, p.last().y)
        return path
    }
}

/** مرشّحات القراءة: ليلي يحافظ على الألوان، سيبيا، رمادي. */
fun readFilter(mode: String): ColorFilter? = when (mode) {
    "night" -> {
        val h = floatArrayOf(-0.574f, 1.43f, 0.144f, 0.426f, 0.43f, 0.144f, 0.426f, 1.43f, -0.856f)
        val k = 0.88f; val off = 0.88f * 255f + 10f
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -k * h[0], -k * h[1], -k * h[2], 0f, off,
                    -k * h[3], -k * h[4], -k * h[5], 0f, off,
                    -k * h[6], -k * h[7], -k * h[8], 0f, off,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    "sepia" -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.393f * 0.45f + 0.55f * 0.985f, 0.769f * 0.45f, 0.189f * 0.45f, 0f, 0f,
                0.349f * 0.45f, 0.686f * 0.45f + 0.55f * 0.93f, 0.168f * 0.45f, 0f, 0f,
                0.272f * 0.45f, 0.534f * 0.45f, 0.131f * 0.45f + 0.55f * 0.80f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    "gray" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    else -> null
}

fun readPageColor(mode: String): Color = when (mode) {
    "night" -> Color(0xFF0A0A0A)
    "sepia" -> Color(0xFFF6ECD8)
    else -> Color.White
}

@Composable
fun PdfView(st: ViewerState, bg: Color, selColor: Color, modifier: Modifier = Modifier) {
    val filter = androidx.compose.runtime.remember(Store.readMode) { readFilter(Store.readMode) }
    val pageColor = readPageColor(Store.readMode)
    LaunchedEffect(st) { st.sharpLoop() }
    LaunchedEffect(st) { st.autoScrollLoop() }
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { st.setViewport(it.width, it.height) }
            .pointerInput(st) { gestures(st) }
    ) {
        st.draw(this, filter, pageColor, bg, selColor)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures(st: ViewerState) {
    val slop = viewConfiguration.touchSlop
    val lpt = viewConfiguration.longPressTimeoutMillis
    val dtt = viewConfiguration.doubleTapTimeoutMillis
    val handleR = 30f * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        st.stopMotion()
        val p0 = down.position

        // سحب مقبض التحديد
        val hnd = st.handleAt(p0, handleR)
        if (hnd != 0) {
            down.consume()
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                st.dragHandle(hnd, c.position - Offset(0f, 22f * density)); c.consume()
            }
            st.selectionDone(); return@awaitEachGesture
        }

        // القلم والقلم العريض
        if (st.tool == Tool.PEN || st.tool == Tool.MARKER) {
            if (!st.beginStroke(p0)) { transformLoop(st, false); return@awaitEachGesture }
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) { st.cancelStroke(); transformLoop(st, true); return@awaitEachGesture }
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                st.addStrokePoint(c.position); c.consume()
            }
            st.endStroke(); return@awaitEachGesture
        }
        if (st.tool == Tool.ERASER) {
            st.eraseAt(p0)
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) { transformLoop(st, true); return@awaitEachGesture }
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                st.eraseAt(c.position); c.consume()
            }
            return@awaitEachGesture
        }

        val kind = withTimeoutOrNull(lpt) {
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.count { it.pressed } > 1) return@withTimeoutOrNull 3
                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 1
                if (!c.pressed) return@withTimeoutOrNull 1
                if ((c.position - p0).getDistance() > slop) return@withTimeoutOrNull 2
            }
            @Suppress("UNREACHABLE_CODE") 0
        }
        when (kind) {
            null -> { // ضغطة مطوّلة: تحديد كلمة ثم السحب لتوسيعه
                if (st.tool != Tool.NONE) { drain(); return@awaitEachGesture }
                st.longPress(p0)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    if (c.positionChanged()) st.extendSel(c.position)
                    c.consume()
                }
                st.selectionDone()
            }
            1 -> {
                if (st.tool == Tool.NOTE || st.tool == Tool.SIGN) { st.tap(p0); return@awaitEachGesture }
                val second = withTimeoutOrNull(dtt) { awaitFirstDown(requireUnconsumed = false) }
                if (second != null) {
                    st.doubleTap(second.position)
                    drain()
                } else st.tap(p0)
            }
            else -> transformLoop(st, kind == 3)
        }
    }
}

private suspend fun AwaitPointerEventScope.drain() {
    while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break }
}

private suspend fun AwaitPointerEventScope.transformLoop(st: ViewerState, multi0: Boolean) {
    var multi = multi0
    val tracker = VelocityTracker()
    var acc = Offset.Zero
    var lastCount = -1
    while (true) {
        val ev = awaitPointerEvent()
        val count = ev.changes.count { it.pressed }
        if (count == 0) break
        if (count != lastCount) { tracker.resetTracking(); lastCount = count }
        if (count > 1) multi = true
        val zoom = ev.calculateZoom()
        val pan = ev.calculatePan()
        val c = ev.calculateCentroid(useCurrent = false)
        if (c != Offset.Unspecified) st.transform(c, pan, zoom)
        acc += pan
        ev.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, acc) }
        ev.changes.forEach { if (it.positionChanged()) it.consume() }
    }
    val v = runCatching { tracker.calculateVelocity() }.getOrNull()
    st.endDrag(v?.x ?: 0f, v?.y ?: 0f, multi && lastCount > 1)
}
