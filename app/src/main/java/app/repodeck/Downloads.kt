package app.repodeck

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads live in filesDir (not cache) so they survive restarts and "clear cache". */
object Downloads {
    fun dir(ctx: Context): File = File(ctx.filesDir, "downloads").also { it.mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun mime(f: File): String = when (f.extension.lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Copies the file into the phone's public Download/RepoDeck folder. */
    fun exportToPublic(ctx: Context, f: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime(f))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RepoDeck")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RepoDeck")
            d.mkdirs()
            f.copyTo(File(d, f.name), true)
            true
        }
    }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val act = rememberAction()
    var files by remember { mutableStateOf(Downloads.list(ctx)) }
    var del by remember { mutableStateOf<File?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.downloads)) }, actions = {
                IconButton(onClick = { files = Downloads.list(ctx) }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
            })
        },
        bottomBar = { AppBottomBar(nav, "downloads") }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (files.isEmpty()) EmptyState(stringResource(R.string.no_downloads))
            else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { f ->
                    val isApk = f.extension.equals("apk", true)
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isApk) Icons.Default.Android else Icons.Default.FolderZip, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${Fmt.size(f.length())} · ${fmt.format(Date(f.lastModified()))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isApk) FilledTonalButton(onClick = { Installer.install(ctx, f) }) { Text(stringResource(R.string.install)) }
                                IconButton(onClick = {
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                    val i = Intent(Intent.ACTION_SEND).setType(Downloads.mime(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(i, f.name))
                                }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                                IconButton(onClick = {
                                    act.run("save ${f.name}") {
                                        val ok = withContext(Dispatchers.IO) { Downloads.exportToPublic(ctx, f) }
                                        act.toast(ctx.getString(if (ok) R.string.saved_public else R.string.saved_app_only))
                                    }
                                }) { Icon(Icons.Default.SaveAlt, stringResource(R.string.save_to_phone)) }
                                IconButton(onClick = { del = f }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    del?.let { f -> ConfirmDialog(stringResource(R.string.confirm_delete, f.name), { del = null }) { f.delete(); files = Downloads.list(ctx) } }
}
