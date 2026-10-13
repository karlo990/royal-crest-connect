package zw.royalcrest.connect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import zw.royalcrest.connect.R

// The school's colours, as on the website and the posters.
val Navy = Color(0xFF0A1F5C)
val NavySoft = Color(0xFF1C3277)
val Gold = Color(0xFFF2B705)
val Panel = Color(0xFFEEF2FA)
val Ink = Color(0xFF16204A)
val Muted = Color(0xFF5B6485)
val Line = Color(0xFFD5DBEC)
val Good = Color(0xFF1F6F43)
val Bad = Color(0xFFA3271C)

val Newsreader = FontFamily(Font(R.font.newsreader_semibold, FontWeight.SemiBold))
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
)

private val colors = lightColorScheme(
    primary = Navy, onPrimary = Color.White,
    primaryContainer = Panel, onPrimaryContainer = Navy,
    secondary = Gold, onSecondary = Navy,
    secondaryContainer = Color(0xFFFFF1C2), onSecondaryContainer = Navy,
    background = Color(0xFFF6F7FB), onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Panel, onSurfaceVariant = Muted,
    outline = Color(0xFFC5CCE0), outlineVariant = Line,
    error = Bad,
)

private fun body(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = size + 6) =
    TextStyle(fontFamily = Jakarta, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val type = Typography(
    headlineMedium = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = body(16, FontWeight.Bold),
    titleSmall = body(14, FontWeight.Bold),
    bodyLarge = body(16),
    bodyMedium = body(14),
    bodySmall = body(12),
    labelLarge = body(14, FontWeight.Bold),
    labelMedium = body(12, FontWeight.SemiBold),
    labelSmall = body(11, FontWeight.SemiBold),
)

@Composable
fun RoyalCrestTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = type, content = content)
