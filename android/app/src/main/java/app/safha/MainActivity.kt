// صفحة — النشاط الرئيسي: يستقبل ملفات PDF من أي تطبيق (فتح باستخدام / مشاركة) ويتنقّل بين الشاشات
// برمجة: د. محمد توفيق · kmkhol01@gmail.com
package app.safha

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat

sealed interface Screen {
    data object Home : Screen
    data class Reader(val uri: Uri) : Screen
    data class Incoming(val uri: Uri, val flags: Int) : Screen
    data class ToolS(val kind: ToolKind) : Screen
    data object Scanner : Screen
    data class Ocr(val files: List<String>) : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private val back = ArrayDeque<Screen>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val dark = isDark()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
                )
            }
            androidx.activity.compose.BackHandler(enabled = screen != Screen.Home) { goBack() }
            SafhaTheme {
                AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(onOpen = { go(Screen.Reader(it)) }, onTool = { go(Screen.ToolS(it)) }, onScan = { go(Screen.Scanner) }, onOcr = { go(Screen.Ocr(emptyList())) })
                        Screen.Scanner -> ScannerScreen(onBack = ::goBack, onOpen = { screen = Screen.Reader(it) }, onOcr = { go(Screen.Ocr(it.map { f -> f.path })) })
                        is Screen.Ocr -> OcrScreen(s.files.map { java.io.File(it) }, onBack = ::goBack)
                        is Screen.Reader -> ReaderScreen(s.uri, onBack = ::goBack, onOcr = { go(Screen.Ocr(it.map { f -> f.path })) })
                        is Screen.Incoming -> IncomingScreen(s.uri, s.flags, onReady = { screen = Screen.Reader(it) }, onBack = ::goBack)
                        is Screen.ToolS -> ToolScreen(s.kind, onBack = ::goBack, onOpen = { go(Screen.Reader(it)) })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            back.clear(); back.addLast(Screen.Home)
            screen = Screen.Incoming(uri, i.flags)
        }
    }

    private fun go(s: Screen) { back.addLast(screen); screen = s }

    private fun goBack() {
        val prev = back.removeLastOrNull()
        if (prev == null) { if (screen is Screen.Home) finish() else screen = Screen.Home } else screen = prev
    }

}
