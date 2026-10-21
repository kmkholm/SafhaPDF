// صفحة — الشاشة الرئيسية: الأخيرة، ملفات الجهاز، المفضلة، الأدوات، الإعدادات وعن المطوّر
// برمجة: د. محمد توفيق
package app.safha

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

data class DeviceFile(val uri: Uri, val name: String, val size: Long, val modified: Long, val folder: String)

fun hasAllFiles(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** يفتح صفحة «الوصول إلى كل الملفات» لهذا التطبيق (مع بدائل لواجهات الشركات المختلفة). */
fun requestAllFiles(ctx: Context, legacy: (() -> Unit)? = null) {
    if (Build.VERSION.SDK_INT >= 30) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e2: Exception) { ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
        }
    } else legacy?.invoke() ?: ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

fun hasCamera(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** بطاقة الصلاحيات: الحالة الحالية وزر منحها. */
@Composable
fun PermissionsCard() {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(hasAllFiles(ctx)) }
    var cam by remember { mutableStateOf(hasCamera(ctx)) }
    LifecycleResumeEffect(Unit) { files = hasAllFiles(ctx); cam = hasCamera(ctx); onPauseOrDispose { } }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { files = it }
    val camReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cam = granted
        if (!granted) ctx.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
    val c = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("الصلاحيات", "Permissions"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(tr("لا يطلب التطبيق إذن الإنترنت أبداً. هذه هي الصلاحيات التي يحتاجها فقط:", "Safha never asks for internet access. These are the only permissions it uses:"),
                fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            PermRow(Icons.Outlined.FolderOpen, tr("الوصول إلى كل الملفات", "All files access"),
                tr("لعرض كل ملفات PDF في جهازك وحفظ التعديلات", "To list every PDF on the phone and save edits"), files) {
                requestAllFiles(ctx) { legacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
            }
            PermRow(Icons.Outlined.PhotoCamera, tr("الكاميرا", "Camera"), tr("لماسح المستندات فقط", "Only for the document scanner"), cam) {
                camReq.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

@Composable
private fun PermRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, ok: Boolean, onGrant: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(sub, fontSize = 12.sp, color = c.onSurfaceVariant)
        }
        if (ok) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = c.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp)); Text(tr("مفعّل", "Allowed"), color = c.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else Button(onClick = onGrant, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text(tr("سماح", "Allow")) }
    }
}

fun queryPdfs(ctx: Context): List<DeviceFile> {
    val col = MediaStore.Files.getContentUri("external")
    @Suppress("DEPRECATION") val dataCol = MediaStore.Files.FileColumns.DATA
    val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED, dataCol)
    val sel = "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
    val out = ArrayList<DeviceFile>()
    runCatching {
        ctx.contentResolver.query(col, proj, sel, arrayOf("application/pdf", "%.pdf"), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val path = c.getString(4) ?: ""
                val file = File(path)
                if (path.isNotEmpty() && !file.exists()) continue
                // نفتح بالمسار مباشرة (إذن «كل الملفات») فلا نعتمد على صلاحيات مزوّد المحتوى
                val u = if (path.isNotEmpty() && file.canRead()) Uri.fromFile(file) else ContentUris.withAppendedId(col, id)
                out += DeviceFile(u, name, c.getLong(2), c.getLong(3) * 1000, file.parentFile?.name ?: "")
            }
        }
    }
    return out.distinctBy { it.uri }
}

object Thumbs {
    private val mem = LruCache<String, Bitmap>(80)
    suspend fun get(ctx: Context, uri: String): Bitmap? {
        mem.get(uri)?.let { return it }
        val f = File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, Store.key(uri) + ".jpg")
        val bmp = withContext(Dispatchers.IO) { if (f.exists()) BitmapFactory.decodeFile(f.path) else null } ?: runCatching {
            val d = Pdf.open(ctx, Uri.parse(uri), null)
            try {
                val s = d.sizes[0]
                val w = 200; val h = (w * s.height.toFloat() / s.width).toInt().coerceIn(60, 400)
                d.render(0, w, h)
            } finally { d.close() }
        }.getOrNull()?.also { b -> withContext(Dispatchers.IO) { runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } } } }
        if (bmp != null) mem.put(uri, bmp)
        return bmp
    }
}

@Composable
fun PdfThumb(uri: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) { bmp = Thumbs.get(ctx, uri); failed = bmp == null }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Icon(if (failed) Icons.Default.Lock else Icons.Default.PictureAsPdf, null, tint = Brand.copy(alpha = 0.7f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Uri) -> Unit, onTool: (ToolKind) -> Unit, onScan: () -> Unit, onOcr: () -> Unit) {
    val ctx = LocalContext.current
    var tab by Store::homeTab
    var settings by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            onOpen(u)
        }
    }
    val c = MaterialTheme.colorScheme

    if (settings) { SettingsScreen { settings = false }; return }

    var askFiles by remember { mutableStateOf(!Store.flag("askedFiles") && !hasAllFiles(ctx)) }
    val legacyReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (askFiles) AlertDialog(
        onDismissRequest = { askFiles = false; Store.setFlag("askedFiles") },
        icon = { Icon(Icons.Outlined.FolderOpen, null) },
        title = { Text(tr("اسمح بالوصول إلى الملفات", "Allow file access")) },
        text = {
            Text(tr("حتى يعرض «صفحة» كل ملفات PDF في جهازك، ويفتح ملفات واتساب والتنزيلات دائماً، ويحفظ التعديلات عليها، فعّل «السماح بإدارة كل الملفات» في الصفحة التالية.\n\nالتطبيق لا يملك إذن الإنترنت، فلا يخرج أي ملف من جهازك.",
                "So Safha can list every PDF on your phone, always reopen WhatsApp and download files, and save your edits, turn on \"Allow access to manage all files\" on the next screen.\n\nThe app has no internet permission, so no file ever leaves your phone."))
        },
        confirmButton = { Button(onClick = { askFiles = false; Store.setFlag("askedFiles"); requestAllFiles(ctx) { legacyReq.launch(Manifest.permission.READ_EXTERNAL_STORAGE) } }) { Text(tr("سماح", "Allow")) } },
        dismissButton = { TextButton(onClick = { askFiles = false; Store.setFlag("askedFiles") }) { Text(tr("لاحقاً", "Later")) } },
    )

    Scaffold(
        topBar = {
            Column(Modifier.background(c.background).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFF0683A), BrandDeep))),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("صفحة PDF", "Safha PDF"), fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                        Text(tr("قارئ بلا إعلانات · بلا إنترنت", "No ads · No internet"), fontSize = 12.sp, color = c.secondary, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onScan) { Icon(Icons.Outlined.DocumentScanner, tr("ماسح المستندات", "Document scanner"), tint = c.primary) }
                    if (tab < 3) IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) filter = "" }) { Icon(Icons.Default.Search, tr("بحث", "Search")) }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, tr("الإعدادات", "Settings")) }
                }
                if (searchOpen && tab < 3) OutlinedTextField(
                    filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(tr("ابحث باسم الملف", "Filter by file name")) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(Icons.Outlined.History, tr("الأخيرة", "Recent"), 0),
                    Triple(Icons.Outlined.Folder, tr("الملفات", "Files"), 1),
                    Triple(Icons.Outlined.StarBorder, tr("المفضلة", "Favorites"), 2),
                    Triple(Icons.Outlined.Handyman, tr("الأدوات", "Tools"), 3),
                ).forEach { (icon, label, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (tab < 3) ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.FileOpen, null) }, text = { Text(tr("فتح ملف", "Open PDF")) },
                containerColor = c.primary, contentColor = c.onPrimary,
            )
        },
        containerColor = c.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> RecentTab(filter, onOpen) { picker.launch(arrayOf("application/pdf")) }
                1 -> FilesTab(filter, onOpen)
                2 -> FavoritesTab(filter, onOpen)
                3 -> ToolsTab(onTool, onScan, onOcr)
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
private fun FileRow(uri: String, name: String, line2: String, progress: Float?, onClick: () -> Unit, menu: @Composable (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfThumb(uri, Modifier.size(48.dp, 64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(line2, fontSize = 12.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), trackColor = c.surfaceVariant)
            }
        }
        if (menu != null) menu()
    }
}

private fun fmtDate(t: Long) = if (t <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))

@Composable
private fun RecentMenu(r: RecentFile) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, null) }
        DropdownMenu(open, { open = false }) {
            val fav = r.uri in Store.favorites
            DropdownMenuItem(text = { Text(if (fav) tr("إزالة من المفضلة", "Unfavorite") else tr("إلى المفضلة", "Favorite")) },
                leadingIcon = { Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, null) }, onClick = { open = false; Store.toggleFavorite(r.uri) })
            DropdownMenuItem(text = { Text(tr("مشاركة", "Share")) }, leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { open = false; ctx.sharePdf(Uri.parse(r.uri), r.name) })
            DropdownMenuItem(text = { Text(tr("إزالة من القائمة", "Remove from list")) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                onClick = { open = false; Store.removeRecent(r.uri) })
        }
    }
}

@Composable
private fun RecentTab(filter: String, onOpen: (Uri) -> Unit, onPick: () -> Unit) {
    val list = Store.recents.filter { filter.isBlank() || it.name.contains(filter, true) }
    if (Store.recents.isEmpty()) {
        EmptyState(Icons.Outlined.AutoStories, tr("مرحباً بك في «صفحة»", "Welcome to Safha"),
            tr("افتح أي ملف PDF من جهازك أو من واتساب والبريد. لا إعلانات ولا تتبّع، فالتطبيق لا يملك إذن الإنترنت أصلاً.", "Open any PDF from your phone, WhatsApp or email. No ads and no tracking — the app has no internet permission at all.")) {
            Button(onClick = onPick) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(tr("فتح ملف", "Open a PDF")) }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        list.firstOrNull()?.let { r ->
            item {
                ContinueCard(r) { onOpen(Uri.parse(r.uri)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("الملفات الأخيرة", "Recent files"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { Store.clearRecents() }) { Text(tr("مسح الكل", "Clear all")) }
            }
        }
        items(list, key = { it.uri }) { r ->
            FileRow(r.uri, r.name, listOf(humanSize(r.size), "${r.pages} ${trS("صفحة", "pages")}", fmtDate(r.opened)).let(::joinIso),
                if (r.pages > 0) (r.page + 1f) / r.pages else null, { onOpen(Uri.parse(r.uri)) }) { RecentMenu(r) }
        }
    }
}

@Composable
private fun ContinueCard(r: RecentFile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE8562A), BrandDeep))).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfThumb(r.uri, Modifier.size(62.dp, 84.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("تابع القراءة", "Continue reading"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Text(r.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            if (r.pages > 0) {
                LinearProgressIndicator(progress = { (r.page + 1f) / r.pages }, Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                Spacer(Modifier.height(4.dp))
                Text("${tr("صفحة", "Page")} ${ltr("${r.page + 1} / ${r.pages}")}", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
            }
        }
        Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun FilesTab(filter: String, onOpen: (Uri) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAllFiles(ctx)) }
    var files by remember { mutableStateOf<List<DeviceFile>?>(null) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LifecycleResumeEffect(Unit) { granted = hasAllFiles(ctx); onPauseOrDispose { } }
    LaunchedEffect(granted) { if (granted) files = withContext(Dispatchers.IO) { queryPdfs(ctx) } }

    if (!granted) {
        EmptyState(Icons.Outlined.FolderOpen, tr("اعرض كل ملفات PDF في جهازك", "See every PDF on your phone"),
            tr("يحتاج التطبيق إذن الوصول إلى الملفات ليجمع لك كل ملفات PDF في مكان واحد. لا يُرسل أي شيء خارج جهازك.", "Allow file access so the app can list all your PDFs in one place. Nothing ever leaves your phone.")) {
            Button(onClick = { requestAllFiles(ctx) { req.launch(Manifest.permission.READ_EXTERNAL_STORAGE) } }) { Text(tr("السماح بالوصول", "Allow access")) }
        }
        return
    }
    val f = files
    if (f == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val list = f.filter { filter.isBlank() || it.name.contains(filter, true) || it.folder.contains(filter, true) }.let { l ->
        when (sort) { 1 -> l.sortedBy { it.name.lowercase() }; 2 -> l.sortedByDescending { it.size }; else -> l }
    }
    if (f.isEmpty()) { EmptyState(Icons.Outlined.SearchOff, tr("لا توجد ملفات PDF", "No PDF files found"), tr("لم نجد ملفات PDF في ذاكرة الجهاز.", "No PDFs were found on this device.")); return }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${list.size} ${tr("ملف", "files")}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                listOf(tr("الأحدث", "Newest"), tr("الاسم", "Name"), tr("الحجم", "Size")).forEachIndexed { i, s ->
                    FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(s, fontSize = 12.sp) }, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        items(list, key = { it.uri.toString() }) { d ->
            FileRow(d.uri.toString(), d.name, listOf(humanSize(d.size), d.folder, fmtDate(d.modified)).let(::joinIso), null, { onOpen(d.uri) }) {
                val fav = d.uri.toString() in Store.favorites
                IconButton(onClick = { Store.toggleFavorite(d.uri.toString()); if (Store.recents.none { it.uri == d.uri.toString() }) Store.touchRecent(d.uri, d.name, d.size, 0, 0) }) {
                    Icon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, null, tint = if (fav) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FavoritesTab(filter: String, onOpen: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val list = Store.favorites.map { u -> Store.recents.firstOrNull { it.uri == u } ?: RecentFile(u, ctx.displayName(Uri.parse(u)), 0, 0, 0, 0) }
        .filter { filter.isBlank() || it.name.contains(filter, true) }
    if (Store.favorites.isEmpty()) {
        EmptyState(Icons.Outlined.StarBorder, tr("لا توجد ملفات مفضلة", "No favorites yet"), tr("من قائمة أي ملف اختر «إلى المفضلة» ليظهر هنا.", "Use \"Favorite\" from any file's menu to pin it here."))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        items(list, key = { it.uri }) { r ->
            FileRow(r.uri, r.name, listOf(humanSize(r.size), if (r.pages > 0) "${r.pages} ${trS("صفحة", "pages")}" else "").let(::joinIso),
                if (r.pages > 0) (r.page + 1f) / r.pages else null, { onOpen(Uri.parse(r.uri)) }) {
                IconButton(onClick = { Store.toggleFavorite(r.uri) }) { Icon(Icons.Default.Star, null, tint = Color(0xFFF59E0B)) }
            }
        }
    }
}

@Composable
private fun ToolsTab(onTool: (ToolKind) -> Unit, onScan: () -> Unit, onOcr: () -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.padding(6.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF064E47)))).clickable(onClick = onScan).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("ماسح المستندات", "Document scanner"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(tr("صوّر الأوراق: كشف تلقائي للحواف، تصحيح الميلان، تبييض الورق، ثم PDF", "Scan paper: auto edges, perspective fix, clean white pages, then PDF"),
                        color = Color.White.copy(alpha = 0.88f), fontSize = 12.5.sp)
                }
                Icon(Icons.Default.PhotoCamera, null, tint = Color.White)
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.padding(6.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6D28D9), Color(0xFF3B1782)))).clickable(onClick = onOcr).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.TextFields, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("PDF مصوّر ← نص", "Scanned PDF → Text"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(tr("حوّل ملفات PDF الممسوحة (صور) إلى نص عربي وإنجليزي قابل للنسخ، دون إنترنت", "Turn scanned (image) PDFs into copyable Arabic & English text, offline"),
                        color = Color.White.copy(alpha = 0.88f), fontSize = 12.5.sp)
                }
                Icon(Icons.Default.Translate, null, tint = Color.White)
            }
        }
        items(ToolKind.entries.toList()) { k ->
            val c = MaterialTheme.colorScheme
            Column(
                Modifier.padding(6.dp).clip(RoundedCornerShape(20.dp)).background(c.surface).clickable { onTool(k) }.padding(16.dp).heightIn(min = 118.dp),
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(k.tint).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(k.icon, null, tint = Color(k.tint))
                }
                Spacer(Modifier.height(10.dp))
                Text(tr(k.ar, k.en), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(tr(k.dar, k.den), fontSize = 12.sp, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- الإعدادات وعن المطوّر ----------------
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(tr("الإعدادات", "Settings"), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = c.secondaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = c.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("القارئ الافتراضي لملفات PDF", "Default PDF reader"), fontWeight = FontWeight.Bold)
                }
                Text(tr("اجعل «صفحة» يفتح كل ملفات PDF تلقائياً. اضغط الزر ثم اختر «صفحة» واضغط «دائماً».",
                    "Make Safha open every PDF automatically. Tap the button, choose Safha, then tap \"Always\"."), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                Row {
                    Button(onClick = { makeDefault(ctx) }) { Text(tr("تعيين كافتراضي", "Set as default")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { ctx.safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text(tr("التطبيقات الافتراضية", "Default apps")) }
                }
                Text(tr("إن كان هناك قارئ آخر مضبوطاً كافتراضي: افتح إعداداته ← «فتح افتراضياً» ← «مسح الإعدادات الافتراضية».",
                    "If another reader is already the default: open its App info → Open by default → Clear defaults."), fontSize = 11.5.sp, color = c.onSecondaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp))
            }
        }
        PermissionsCard()
        SheetTitle(tr("اللغة", "Language"))
        ChoiceChips(listOf("system" to tr("لغة الجهاز", "System"), "ar" to "العربية", "en" to "English"), Store.lang) { Store.lang = it; Store.save() }
        SheetTitle(tr("المظهر", "Appearance"))
        ChoiceChips(listOf("system" to tr("تلقائي", "Auto"), "light" to tr("فاتح", "Light"), "dark" to tr("داكن", "Dark")), Store.theme) { Store.theme = it; Store.save() }
        SheetTitle(tr("القراءة", "Reading"))
        ChoiceChips(listOf("vertical" to tr("تمرير عمودي", "Vertical"), "horizontal" to tr("صفحة صفحة", "Paged")), Store.layout) { Store.layout = it; Store.save() }
        ChoiceChips(listOf("normal" to tr("عادي", "Normal"), "night" to tr("ليلي", "Night"), "sepia" to tr("ورقي", "Sepia"), "gray" to tr("رمادي", "Gray")), Store.readMode) { Store.readMode = it; Store.save() }
        SwitchRow(tr("تقليب من اليمين لليسار", "Right-to-left paging"), tr("في وضع صفحة صفحة", "In paged mode"), Store.rtlPages) { Store.rtlPages = it; Store.save() }
        SwitchRow(tr("إبقاء الشاشة مضاءة أثناء القراءة", "Keep screen on while reading"), null, Store.keepScreenOn) { Store.keepScreenOn = it; Store.save() }
        SwitchRow(tr("ملء الشاشة عند إخفاء الأشرطة", "Full screen when bars are hidden"), null, Store.fullscreenRead) { Store.fullscreenRead = it; Store.save() }
        SwitchRow(tr("فواصل بين الصفحات", "Gaps between pages"), null, Store.pageGap) { Store.pageGap = it; Store.save() }
        Row(Modifier.fillMaxWidth().clickable {
            File(ctx.cacheDir, "thumbs").deleteRecursively(); File(ctx.cacheDir, "open").deleteRecursively(); File(ctx.cacheDir, "share").deleteRecursively()
            ctx.toast(trS("نُظّفت الذاكرة المؤقتة", "Cache cleared"))
        }.padding(20.dp, 14.dp)) { Icon(Icons.Outlined.CleaningServices, null); Spacer(Modifier.width(12.dp)); Text(tr("تنظيف الذاكرة المؤقتة", "Clear cache")) }

        // عن المطوّر
        Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFE8562A), BrandDeep))).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(tr("صفحة PDF", "Safha PDF"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text(tr("الإصدار", "Version") + " " + appVersion(ctx), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Text(tr("تطوير وبرمجة", "Designed & developed by"), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Text(tr(DEVELOPER_AR, DEVELOPER_EN), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(DEVELOPER_EMAIL, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                    modifier = Modifier.clickable { ctx.safeStart(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "Safha PDF")) }.padding(4.dp))
                Text("ORCID $DEVELOPER_ORCID", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                Text(tr("بلا إعلانات · بلا تتبّع · لا يملك إذن الإنترنت · كل شيء يبقى في جهازك", "No ads · No tracking · No internet permission · Everything stays on your phone"),
                    color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun appVersion(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"

/** يفتح ملف PDF صغيراً عبر النظام ليظهر مربع «فتح باستخدام / دائماً». */
fun makeDefault(ctx: Context) {
    runCatching {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "Safha.pdf")
        if (!f.exists()) {
            val pd = android.graphics.pdf.PdfDocument()
            val page = pd.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val p = android.graphics.Paint().apply { textSize = 26f; isAntiAlias = true; color = 0xFFC2410C.toInt(); textAlign = android.graphics.Paint.Align.CENTER }
            page.canvas.drawText("Safha PDF", 297f, 380f, p)
            p.textSize = 16f; p.color = 0xFF333333.toInt()
            page.canvas.drawText("$DEVELOPER_EN · $DEVELOPER_EMAIL", 297f, 420f, p)
            pd.finishPage(page)
            f.outputStream().use { pd.writeTo(it) }; pd.close()
        }
        val u = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        // نافذة الاختيار الافتراضية من النظام (وليست createChooser) لتظهر «دائماً»
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val def = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        if (def?.activityInfo?.packageName == ctx.packageName) ctx.toast(trS("«صفحة» هو القارئ الافتراضي بالفعل ✓", "Safha is already the default ✓"))
        else ctx.startActivity(i)
    }.onFailure { ctx.toast(it.message ?: "") }
}
