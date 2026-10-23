package app.repodeck

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0D9488), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF), onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFFD97706), tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF0F8), onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFF9CA3AF), outlineVariant = Color(0xFFE5E7EB),
    surfaceTint = Color(0xFF4F46E5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F7FB), surfaceContainer = Color(0xFFF0F2FA),
    surfaceContainerHigh = Color(0xFFEAEDF8), surfaceContainerHighest = Color(0xFFE3E7F4),
    error = Color(0xFFDC2626)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF312E81), onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFFFBBF24), tertiaryContainer = Color(0xFF78350F), onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0B0E14), onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF12161F), onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1C2230), onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF4B5563), outlineVariant = Color(0xFF262C3A),
    surfaceTint = Color(0xFFA5B4FC),
    surfaceContainerLowest = Color(0xFF0B0E14), surfaceContainerLow = Color(0xFF0F131B), surfaceContainer = Color(0xFF151A24),
    surfaceContainerHigh = Color(0xFF1B2130), surfaceContainerHighest = Color(0xFF232A3A),
    error = Color(0xFFF87171)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun RepoDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography, shapes = AppShapes, content = content
    )
}
