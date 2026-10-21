package app.gitforge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF3B5BDB), onPrimary = Color.White,
    secondary = Color(0xFF0CA678), tertiary = Color(0xFFF08C00),
    background = Color(0xFFF6F7FB), surface = Color.White
)
private val Dark = darkColorScheme(
    primary = Color(0xFF91A7FF), onPrimary = Color(0xFF0B1240),
    secondary = Color(0xFF63E6BE), tertiary = Color(0xFFFFC078),
    background = Color(0xFF0E1117), surface = Color(0xFF161B22)
)

@Composable
fun GitForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
