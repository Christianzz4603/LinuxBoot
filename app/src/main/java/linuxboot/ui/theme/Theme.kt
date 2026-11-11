package linuxboot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Status colours Material 3 does not define. */
@Immutable
class ExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val DarkExtra = ExtraColors(
    success = Color(0xFF6FD9A0), successContainer = Color(0xFF143D2B), onSuccessContainer = Color(0xFFBDF2D6),
    warning = Color(0xFFFFC27A), warningContainer = Color(0xFF4A3414), onWarningContainer = Color(0xFFFFE2BC),
)
private val LightExtra = ExtraColors(
    success = Color(0xFF1B7F4B), successContainer = Color(0xFFD4F3E2), onSuccessContainer = Color(0xFF0B3D24),
    warning = Color(0xFF9A5B00), warningContainer = Color(0xFFFFE7C2), onWarningContainer = Color(0xFF3F2500),
)

val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF0A2257),
    primaryContainer = Color(0xFF1C3A7A), onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF6FD9BE), onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF223250), onSecondaryContainer = Color(0xFFD3E0FF),
    tertiary = Color(0xFFFFC27A), onTertiary = Color(0xFF442B00),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF161B22), onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF212833), onSurfaceVariant = Color(0xFFA9B3C1),
    outline = Color(0xFF475264), outlineVariant = Color(0xFF2A3341),
    error = Color(0xFFFF8A8A), onError = Color(0xFF4A0A0A),
    errorContainer = Color(0xFF5A1D1D), onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF0B1F5C),
    secondary = Color(0xFF0E7A64), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE5FF), onSecondaryContainer = Color(0xFF0B1F5C),
    tertiary = Color(0xFF9A5B00), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF11161D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF11161D),
    surfaceVariant = Color(0xFFE9EEF5), onSurfaceVariant = Color(0xFF546071),
    outline = Color(0xFF8C97A6), outlineVariant = Color(0xFFDCE2EA),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LinuxBootTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
