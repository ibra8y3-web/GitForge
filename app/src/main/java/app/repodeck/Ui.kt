package app.repodeck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.serialization.json.JsonObject

/** The app logo drawn in code, identical to the launcher icon. */
@Composable
fun BrandMark(side: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(side)) {
        val s = this.size.minDimension / 108f
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF7C3AED)), Offset.Zero, Offset(this.size.width, this.size.height)),
            cornerRadius = CornerRadius(26f * s)
        )
        val w = 6f * s
        drawLine(Color.White, Offset(40f * s, 32f * s), Offset(40f * s, 76f * s), w, StrokeCap.Round)
        val p = Path().apply {
            moveTo(40f * s, 62f * s)
            cubicTo(40f * s, 50f * s, 68f * s, 58f * s, 68f * s, 44f * s)
        }
        drawPath(p, Color.White, style = Stroke(w, cap = StrokeCap.Round))
        listOf(Triple(40f, 30f, Color(0xFF2DD4BF)), Triple(40f, 78f, Color(0xFFA5B4FC)), Triple(68f, 38f, Color(0xFFFBBF24))).forEach { (x, y, c) ->
            drawCircle(c, 9f * s, Offset(x * s, y * s))
            drawCircle(Color.White, 9f * s, Offset(x * s, y * s), style = Stroke(3f * s))
        }
    }
}

@Composable
fun AppBottomBar(nav: NavController, current: String) {
    val items = listOf(
        Triple("repos", Icons.Default.Folder, R.string.repositories),
        Triple("upload", Icons.Default.CloudUpload, R.string.upload),
        Triple("downloads", Icons.Default.Download, R.string.downloads),
        Triple("settings", Icons.Default.Settings, R.string.settings)
    )
    NavigationBar {
        items.forEach { (route, icon, label) ->
            NavigationBarItem(
                selected = current == route,
                onClick = {
                    if (current != route) {
                        if (route == "repos") nav.popBackStack("repos", false)
                        else nav.navigate(route) { launchSingleTop = true; popUpTo("repos") }
                    }
                },
                icon = { Icon(icon, null) },
                label = { Text(stringResource(label)) }
            )
        }
    }
}

@Composable
fun Pill(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun langColor(l: String): Color = when (l) {
    "Kotlin" -> Color(0xFF7F52FF); "Java" -> Color(0xFFB07219); "JavaScript" -> Color(0xFFF1E05A)
    "TypeScript" -> Color(0xFF3178C6); "Python" -> Color(0xFF3572A5); "Go" -> Color(0xFF00ADD8)
    "Rust" -> Color(0xFFDEA584); "HTML" -> Color(0xFFE34C26); "CSS" -> Color(0xFF563D7C)
    "C++" -> Color(0xFFF34B7D); "C" -> Color(0xFF555555); "Swift" -> Color(0xFFF05138)
    "Dart" -> Color(0xFF00B4AB); "Shell" -> Color(0xFF89E051); "PHP" -> Color(0xFF4F5D95)
    else -> Color(0xFF9CA3AF)
}

@Composable
fun HeroCard(me: JsonObject, all: List<JsonObject>) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(me.s("avatar_url"), null, Modifier.size(52.dp).clip(CircleShape))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    stringResource(R.string.welcome, me.s("name").ifEmpty { me.s("login") }),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.repo_stats, all.size, all.count { it.b("private") }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
