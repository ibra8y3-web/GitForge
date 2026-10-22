package app.repodeck

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import kotlinx.serialization.json.*
import java.io.File

// ---------------------------------------------------------------- Upload a project from phone storage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(nav: NavController) {
    val ctx = LocalContext.current
    val act = rememberAction()
    val login = Graph.store.activeLogin.orEmpty()
    val repos = rememberLoader(login) { Graph.api.pages("/user/repos?sort=updated&affiliation=owner,collaborator,organization_member") }
    var tree by remember { mutableStateOf<Uri?>(null) }
    var report by remember { mutableStateOf<UploadEngine.Report?>(null) }
    var newRepo by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf("") }
    var repoName by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    var branch by remember { mutableStateOf("main") }
    var message by remember { mutableStateOf("Initial upload from RepoDeck") }
    var force by remember { mutableStateOf(false) }
    var addIgnore by remember { mutableStateOf(true) }
    var showFlagged by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    val logLine: (String) -> Unit = { s -> lines.add(s); if (lines.size > 200) lines.removeAt(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            tree = uri; report = null; lines.clear()
            if (repoName.isEmpty()) repoName = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name.orEmpty().replace(' ', '-')
            act.run("scan folder") { report = UploadEngine.scan(ctx, uri) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.upload_project)) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }, bottomBar = { AppBottomBar(nav, "upload") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { picker.launch(null) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.choose_folder)) }
            if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            report?.let { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.scan_result, r.files.size, Fmt.size(r.total), r.type), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.skipped_dirs, r.skipped.size), style = MaterialTheme.typography.bodySmall)
                        if (r.flagged.isNotEmpty()) {
                            Text(stringResource(R.string.flagged_files, r.flagged.size), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { showFlagged = !showFlagged }) { Text(stringResource(R.string.show_details)) }
                            if (showFlagged) r.flagged.take(100).forEach { (f, why) -> Text("• $f — $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        } else Text(stringResource(R.string.no_secrets), color = Color(0xFF2EA043))
                    }
                }
                Row { FilterChip(newRepo, { newRepo = true }, label = { Text(stringResource(R.string.new_repo)) }); Spacer(Modifier.width(8.dp)); FilterChip(!newRepo, { newRepo = false }, label = { Text(stringResource(R.string.existing_repo)) }) }
                if (newRepo) {
                    OutlinedTextField(repoName, { repoName = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(priv, { priv = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.private_repo)) }
                } else {
                    val opts = repos.valueOrNull().orEmpty().filter { it.obj("permissions").b("push") }.map { it.s("full_name") }
                    PickerRow(stringResource(R.string.repository), target, opts) { target = it }
                }
                OutlinedTextField(branch, { branch = it }, label = { Text(stringResource(R.string.branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.commit_message)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(addIgnore, { addIgnore = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_gitignore)) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(force, { force = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.force_push)) }
                Button(enabled = !act.busy && r.files.isNotEmpty() && (if (newRepo) repoName.isNotBlank() else target.isNotBlank()), onClick = {
                    lines.clear()
                    act.run("upload folder", onOk = { act.toast(ctx.getString(R.string.upload_done)) }) {
                        val acc = Graph.store.active()!!
                        val token = acc.token
                        val full: String
                        if (newRepo) {
                            logLine("Creating repository…")
                            val o = Graph.api.send("POST", "/user/repos", obj("name" to repoName.trim(), "private" to priv, "auto_init" to false)).js().o()
                            full = o.s("full_name")
                        } else full = target
                        val (owner, name) = full.split("/")
                        val res = UploadEngine.upload(ctx, r, owner, name, branch.trim(), message, force, addIgnore, token, acc.login to "${acc.login}@users.noreply.github.com", logLine)
                        logLine(if (res == "PUSHED") "✔ https://github.com/$full" else "Nothing to commit")
                    }
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upload)) }
            }
            if (lines.isNotEmpty()) Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val act = rememberAction()
    val rate = rememberLoader(Graph.store.activeLogin) { Graph.api.get("/rate_limit").o().obj("resources").obj("core") }
    var lock by remember { mutableStateOf(Graph.store.lock) }
    var cid by remember { mutableStateOf(Graph.store.clientId) }
    var storage by remember { mutableStateOf("") }
    fun calc() {
        val a = File(ctx.filesDir, "repos").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val b = ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        storage = ctx.getString(R.string.storage_fmt, Fmt.size(a), Fmt.size(b))
    }
    LaunchedEffect(Unit) { calc() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) act.run("backup export") {
            // Secrets are never written: no tokens, only logins and preferences.
            val o = buildJsonObject {
                put("version", 1)
                put("client_id", Graph.store.clientId)
                put("lock", Graph.store.lock)
                put("language", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                put("accounts", JsonArray(Graph.store.accounts.map { JsonPrimitive(it.login) }))
            }
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(o.toString().toByteArray()) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act.run("backup import") {
            val o = json.parseToJsonElement(ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }).o()
            Graph.store.clientId = o.s("client_id"); cid = o.s("client_id")
            Graph.store.lock = o.b("lock"); lock = o.b("lock")
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(o.s("language")))
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }, bottomBar = { AppBottomBar(nav, "settings") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.language))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ar" to R.string.lang_ar).forEach { (tag, label) ->
                    FilterChip(AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith(tag) && (tag.isNotEmpty() || AppCompatDelegate.getApplicationLocales().isEmpty()),
                        { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }, label = { Text(stringResource(label)) })
                }
            }
            SectionTitle(stringResource(R.string.security))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(lock, { lock = it; Graph.store.lock = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.app_lock))
            }
            SectionTitle(stringResource(R.string.account_section))
            Graph.store.accounts.forEach { a ->
                ListItem(headlineContent = { Text(a.login + if (a.login == Graph.store.activeLogin) "  ✓" else "") },
                    trailingContent = { IconButton(onClick = { Graph.store.remove(a.login) }) { Icon(Icons.Default.Delete, stringResource(R.string.sign_out)) } })
            }
            OutlinedTextField(cid, { cid = it; Graph.store.clientId = it.trim() }, label = { Text(stringResource(R.string.client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("${stringResource(R.string.token_scopes)}: ${Graph.api.scopes.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
            LoaderView(rate, Modifier.height(48.dp)) { c -> Text(stringResource(R.string.api_usage, c.n("remaining"), c.n("limit")), style = MaterialTheme.typography.bodyMedium) }
            SectionTitle(stringResource(R.string.storage))
            Text(storage, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; calc() }) { Text(stringResource(R.string.clear_cache)) }
                OutlinedButton(onClick = { File(ctx.filesDir, "repos").deleteRecursively(); calc() }) { Text(stringResource(R.string.delete_clones)) }
            }
            SectionTitle(stringResource(R.string.backup))
            Text(stringResource(R.string.backup_note), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export.launch("repodeck-settings.json") }) { Text(stringResource(R.string.export)) }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json")) }) { Text(stringResource(R.string.import_)) }
            }
            TextButton(onClick = { nav.navigate("logs") }) { Text(stringResource(R.string.op_log)) }
            Text("RepoDeck 1.0.0", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(nav: NavController) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.op_log)) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { TextButton(onClick = { OpLog.clear() }) { Text(stringResource(R.string.clear)) } })
    }) { pad ->
        Box(Modifier.padding(pad)) {
            if (OpLog.entries.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
            else LazyColumn { items(OpLog.entries) { e ->
                ListItem(
                    leadingContent = { Icon(if (e.ok) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (e.ok) Color(0xFF2EA043) else MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(e.op) },
                    supportingContent = { Text(e.time + if (e.detail.isNotEmpty()) "\n${e.detail}" else "") }
                )
                HorizontalDivider()
            } }
        }
    }
}
