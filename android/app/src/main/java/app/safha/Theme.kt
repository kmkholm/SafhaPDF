// صفحة — الألوان والخطوط. برمجة: د. محمد توفيق
package app.safha

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

val Brand = Color(0xFFC2410C)
val BrandDeep = Color(0xFF8C230B)
val BrandSoft = Color(0xFFFFE4D6)
val Ink = Color(0xFF221A15)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = Color(0xFF3B1204),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF1EC),
    onSecondaryContainer = Color(0xFF032E2A),
    tertiary = Color(0xFF6D28D9),
    background = Color(0xFFF7F4EF),
    onBackground = Ink,
    surface = Color(0xFFFFFCF8),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E8E0),
    onSurfaceVariant = Color(0xFF5E534B),
    surfaceContainer = Color(0xFFF5EEE7),
    surfaceContainerHigh = Color(0xFFEFE6DD),
    surfaceContainerLow = Color(0xFFFAF5F0),
    outline = Color(0xFF9C8F86),
    outlineVariant = Color(0xFFE2D8CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A5B),
    onPrimary = Color(0xFF4A1304),
    primaryContainer = Color(0xFF6E2410),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF10433D),
    onSecondaryContainer = Color(0xFFB8F5EA),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF121110),
    onBackground = Color(0xFFEDE6E0),
    surface = Color(0xFF1A1816),
    onSurface = Color(0xFFEDE6E0),
    surfaceVariant = Color(0xFF2C2724),
    onSurfaceVariant = Color(0xFFCFC4BB),
    surfaceContainer = Color(0xFF211E1B),
    surfaceContainerHigh = Color(0xFF2A2623),
    surfaceContainerLow = Color(0xFF1C1A18),
    outline = Color(0xFF8E827A),
    outlineVariant = Color(0xFF3D3632),
)

@Composable
fun isDark(): Boolean = when (Store.theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun SafhaTheme(content: @Composable () -> Unit) {
    val arabic = Store.isArabic()
    MaterialTheme(
        colorScheme = if (isDark()) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalArabic provides arabic,
            LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) { content() }
    }
}
