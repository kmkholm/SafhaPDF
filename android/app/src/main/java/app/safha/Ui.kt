// صفحة — عناصر واجهة مشتركة وأدوات مساعدة. برمجة: د. محمد توفيق
package app.safha

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.copyText(t: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Safha", t))
    toast(trS("نُسخ النص", "Text copied"))
}

fun Context.shareText(t: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
    startActivity(Intent.createChooser(i, trS("مشاركة النص", "Share text")))
}

fun Context.safeStart(i: Intent) {
    try { startActivity(i) } catch (e: ActivityNotFoundException) { toast(trS("لا يوجد تطبيق مناسب", "No app found")) } catch (e: SecurityException) { toast(e.message ?: "") }
}

/** نسخة مؤقتة يمكن مشاركتها مع أي تطبيق. */
fun Context.shareableCopy(uri: Uri, name: String): Uri {
    val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000) it.delete() } }
    val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.pdf" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
    val f = File(dir, clean)
    // نفتح المصدر أولاً ثم نكتب لملف مؤقت، فلا يبقى ملف فارغ إن فشلت القراءة
    val tmp = File(dir, ".tmp_" + System.nanoTime())
    contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
    if (tmp.length() == 0L) { tmp.delete(); error(trS("الملف فارغ", "The file is empty")) }
    f.delete(); tmp.renameTo(f)
    return FileProvider.getUriForFile(this, "$packageName.files", f)
}

fun Context.sharePdf(uri: Uri, name: String) = runCatching {
    val u = shareableCopy(uri, name)
    val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, u)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, trS("مشاركة الملف", "Share file")))
}.onFailure { toast(it.message ?: "Error") }

fun Context.openWith(uri: Uri, name: String) = runCatching {
    val u = shareableCopy(uri, name)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, trS("فتح باستخدام", "Open with")))
}.onFailure { toast(it.message ?: "Error") }

fun Context.openUrl(url: String) {
    val u = if (url.startsWith("www.", true)) "https://$url" else url
    safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun BarButton(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        (modifier ?: Modifier.width(60.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (!enabled) c.onSurface.copy(alpha = 0.3f) else if (selected) c.onPrimaryContainer else c.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (!enabled) c.onSurface.copy(alpha = 0.3f) else c.onSurfaceVariant)
    }
}

@Composable
fun SheetTitle(t: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(t, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        if (sub != null) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.primary else c.surfaceVariant)
                    .clickable { onSelect(k) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) c.onPrimary else c.onSurfaceVariant, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
        }
    }
}

@Composable
fun ColorDots(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { col ->
            val on = (col and 0xFFFFFF) == (selected and 0xFFFFFF)
            Box(
                Modifier.size(if (on) 30.dp else 24.dp).clip(CircleShape).background(Color(col or (0xFF shl 24)))
                    .clickable { onPick(col) },
                contentAlignment = Alignment.Center,
            ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
        }
    }
}

val PenColors = listOf(0xFFE11D48.toInt(), 0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFF111827.toInt(), 0xFF9333EA.toInt(), 0xFFF97316.toInt())
val MarkColors = listOf(0xFFFACC15.toInt(), 0xFF4ADE80.toInt(), 0xFF60A5FA.toInt(), 0xFFF472B6.toInt(), 0xFFFB923C.toInt(), 0xFFA78BFA.toInt())
