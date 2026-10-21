package app.gitforge

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(nav: NavController, owner: String, name: String) {
    val repo = rememberLoader(owner, name) { Graph.api.get("/repos/$owner/$name").o() }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(R.string.tab_files, R.string.tab_branches, R.string.tab_commits, R.string.tab_actions,
        R.string.tab_releases, R.string.tab_issues, R.string.tab_prs, R.string.tab_local, R.string.tab_settings)
    var ref by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("$owner/$name", maxLines = 1) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton(onClick = { repo.reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } }
        )
    }) { pad ->
        Box(Modifier.padding(pad)) {
            LoaderView(repo) { r ->
                val cur = ref.ifEmpty { r.s("default_branch") }
                Column {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(stringResource(t)) }) }
                    }
                    when (tab) {
                        0 -> FilesTab(owner, name, cur)
                        1 -> BranchesTab(owner, name, cur) { ref = it; tab = 0 }
                        2 -> CommitsTab(owner, name, cur)
                        3 -> ActionsTab(owner, name, cur)
                        4 -> ReleasesTab(owner, name, cur)
                        5 -> ThreadsTab(owner, name, false)
                        6 -> ThreadsTab(owner, name, true)
                        7 -> LocalTab(owner, name)
                        else -> RepoSettingsTab(nav, owner, name, r) { repo.reload() }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Files

suspend fun putFile(owner: String, name: String, ref: String, path: String, bytes: ByteArray, message: String) {
    val sha = runCatching { Graph.api.get("/repos/$owner/$name/contents/${encPath(path)}?ref=${Uri.encode(ref)}").o().s("sha") }.getOrNull()
    Graph.api.send("PUT", "/repos/$owner/$name/contents/${encPath(path)}", obj(
        "message" to message, "content" to Base64.encodeToString(bytes, Base64.NO_WRAP), "branch" to ref,
        "sha" to sha?.ifEmpty { null }
    ))
}

@Composable
fun FilesTab(owner: String, name: String, ref: String) {
    var path by remember { mutableStateOf("") }
    val l = rememberLoader(owner, name, ref, path) { Graph.api.get("/repos/$owner/$name/contents/${encPath(path)}?ref=${Uri.encode(ref)}") }
    val act = rememberAction()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var newFile by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<JsonObject?>(null) }
    var del by remember { mutableStateOf<JsonObject?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val fn = uri.displayName(ctx)
            act.run("upload $fn", onOk = { l.reload() }) {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                putFile(owner, name, ref, if (path.isEmpty()) fn else "$path/$fn", bytes, "Add $fn")
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$ref:/$path", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            if (path.isNotEmpty()) TextButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("..") }
            IconButton(onClick = { newFile = true }) { Icon(Icons.Default.NoteAdd, stringResource(R.string.new_file)) }
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, stringResource(R.string.upload)) }
        }
        if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LoaderView(l) { res ->
            val entries = res.items().sortedWith(compareBy({ it.s("type") != "dir" }, { it.s("name").lowercase() }))
            if (entries.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
            else LazyColumn { items(entries, key = { it.s("path") }) { f ->
                val dir = f.s("type") == "dir"
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { if (dir) path = f.s("path") else editing = f },
                    leadingContent = { Icon(if (dir) Icons.Default.Folder else Icons.Outlined.Description, null) },
                    headlineContent = { Text(f.s("name")) },
                    supportingContent = { if (!dir) Text(Fmt.size(f.n("size"))) },
                    trailingContent = {
                        if (!dir) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = f })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; del = f })
                            }
                        }
                    }
                )
            } }
        }
    }
    editing?.let { f -> FileEditor(owner, name, ref, f, { editing = null }) { l.reload() } }
    if (newFile) FormDialog(stringResource(R.string.new_file), listOf(Field(stringResource(R.string.file_path), if (path.isEmpty()) "" else "$path/"), Field(stringResource(R.string.content), multi = true)),
        onDismiss = { newFile = false }) { v, _ ->
        act.run("create file ${v[0]}", onOk = { l.reload() }) { putFile(owner, name, ref, v[0].trim(), v[1].toByteArray(), "Create ${v[0].trim()}") }
    }
    rename?.let { f -> FormDialog(stringResource(R.string.rename), listOf(Field(stringResource(R.string.file_path), f.s("path"))), onDismiss = { rename = null }) { v, _ ->
        act.run("rename ${f.s("path")}", onOk = { l.reload() }) {
            val bytes = Graph.api.rawBytes("/repos/$owner/$name/contents/${encPath(f.s("path"))}?ref=${Uri.encode(ref)}")
            putFile(owner, name, ref, v[0].trim(), bytes, "Rename ${f.s("path")} to ${v[0].trim()}")
            Graph.api.send("DELETE", "/repos/$owner/$name/contents/${encPath(f.s("path"))}", obj("message" to "Remove ${f.s("path")}", "sha" to f.s("sha"), "branch" to ref))
        }
    } }
    del?.let { f -> ConfirmDialog(stringResource(R.string.confirm_delete, f.s("path")), { del = null }) {
        act.run("delete ${f.s("path")}", onOk = { l.reload() }) {
            Graph.api.send("DELETE", "/repos/$owner/$name/contents/${encPath(f.s("path"))}", obj("message" to "Delete ${f.s("path")}", "sha" to f.s("sha"), "branch" to ref))
        }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditor(owner: String, name: String, ref: String, f: JsonObject, onClose: () -> Unit, onSaved: () -> Unit) {
    val uri = LocalUriHandler.current
    val big = f.n("size") > 1_000_000
    val l = rememberLoader(f.s("sha")) {
        if (big) null else Graph.api.rawBytes("/repos/$owner/$name/contents/${encPath(f.s("path"))}?ref=${Uri.encode(ref)}")
    }
    val act = rememberAction()
    var text by remember { mutableStateOf<String?>(null) }
    var binary by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("Update ${f.s("path")}") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(f.s("name"), maxLines = 1) }, actions = {
                    TextButton(enabled = text != null && !act.busy, onClick = {
                        act.run("edit ${f.s("path")}", onOk = { onSaved(); onClose() }) { putFile(owner, name, ref, f.s("path"), text!!.toByteArray(), msg) }
                    }) { Text(stringResource(R.string.commit)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                })
            }) { pad ->
                Column(Modifier.padding(pad).padding(8.dp)) {
                    LoaderView(l) { bytes ->
                        if (bytes == null) {
                            Column { Text(stringResource(R.string.too_big)); Button(onClick = { uri.openUri(f.s("html_url")) }) { Text(stringResource(R.string.open_browser)) } }
                        } else {
                            LaunchedEffect(bytes) {
                                if (bytes.take(8000).any { it.toInt() == 0 }) binary = true else text = String(bytes, Charsets.UTF_8)
                            }
                            if (binary) Text(stringResource(R.string.binary_file))
                            else text?.let { t ->
                                OutlinedTextField(msg, { msg = it }, label = { Text(stringResource(R.string.commit_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(t, { text = it }, modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Branches / tags / compare

@Composable
fun BranchesTab(owner: String, name: String, ref: String, onPick: (String) -> Unit) {
    val base = "/repos/$owner/$name"
    val branches = rememberLoader(owner, name) { Graph.api.pages("$base/branches") }
    val tags = rememberLoader(owner, name) { Graph.api.pages("$base/tags") }
    val act = rememberAction()
    var create by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var merge by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<String?>(null) }
    var delB by remember { mutableStateOf<String?>(null) }
    var newTag by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { create = true }) { Text(stringResource(R.string.new_branch)) }
                OutlinedButton(onClick = { compare = true }) { Text(stringResource(R.string.compare)) }
                OutlinedButton(onClick = { merge = true }) { Text(stringResource(R.string.merge)) }
            }
            if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionTitle(stringResource(R.string.tab_branches))
        }
        when (val s = branches.state) {
            is Res.Ok -> items(s.v, key = { "b" + it.s("name") }) { b ->
                ListItem(
                    modifier = Modifier.clickable { onPick(b.s("name")) },
                    leadingContent = { Icon(Icons.Default.AccountTree, null) },
                    headlineContent = { Text(b.s("name") + if (b.s("name") == ref) "  ✓" else "") },
                    supportingContent = { Text(b.obj("commit").s("sha").take(7) + if (b.b("protected")) " · 🔒" else "") },
                    trailingContent = { IconButton(onClick = { delB = b.s("name") }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }
                )
            }
            is Res.Err -> item { Text(s.msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            else -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.tags)) }
                TextButton(onClick = { newTag = true }) { Text(stringResource(R.string.new_tag)) }
            }
        }
        when (val s = tags.state) {
            is Res.Ok -> items(s.v, key = { "t" + it.s("name") }) { t ->
                ListItem(
                    headlineContent = { Text(t.s("name")) }, supportingContent = { Text(t.obj("commit").s("sha").take(7)) },
                    leadingContent = { Icon(Icons.Default.Label, null) },
                    trailingContent = { IconButton(onClick = { act.run("delete tag ${t.s("name")}", onOk = { tags.reload() }) { Graph.api.send("DELETE", "$base/git/refs/tags/${Uri.encode(t.s("name"))}") } }) { Icon(Icons.Default.Delete, null) } }
                )
            }
            else -> {}
        }
    }
    if (create) FormDialog(stringResource(R.string.new_branch), listOf(Field(stringResource(R.string.name)), Field(stringResource(R.string.from_branch), ref)), onDismiss = { create = false }) { v, _ ->
        act.run("create branch ${v[0]}", onOk = { branches.reload() }) {
            val sha = Graph.api.get("$base/git/ref/heads/${encPath(v[1].trim())}").o().obj("object").s("sha")
            Graph.api.send("POST", "$base/git/refs", obj("ref" to "refs/heads/${v[0].trim()}", "sha" to sha))
        }
    }
    if (newTag) FormDialog(stringResource(R.string.new_tag), listOf(Field(stringResource(R.string.name)), Field(stringResource(R.string.from_branch), ref), Field(stringResource(R.string.description))), onDismiss = { newTag = false }) { v, _ ->
        act.run("create tag ${v[0]}", onOk = { tags.reload() }) {
            val sha = Graph.api.get("$base/git/ref/heads/${encPath(v[1].trim())}").o().obj("object").s("sha")
            val tagObj = Graph.api.send("POST", "$base/git/tags", obj("tag" to v[0].trim(), "message" to v[2].ifBlank { v[0] }, "object" to sha, "type" to "commit")).js().o()
            Graph.api.send("POST", "$base/git/refs", obj("ref" to "refs/tags/${v[0].trim()}", "sha" to tagObj.s("sha")))
        }
    }
    delB?.let { b -> ConfirmDialog(stringResource(R.string.confirm_delete, b), { delB = null }) {
        act.run("delete branch $b", onOk = { branches.reload() }) { Graph.api.send("DELETE", "$base/git/refs/heads/${encPath(b)}") }
    } }
    if (compare) FormDialog(stringResource(R.string.compare), listOf(Field(stringResource(R.string.base_branch), ref), Field(stringResource(R.string.head_branch))), onDismiss = { compare = false }) { v, _ ->
        act.run("compare ${v[0]}...${v[1]}") {
            val c = Graph.api.get("$base/compare/${Uri.encode(v[0].trim())}...${Uri.encode(v[1].trim())}").o()
            val sb = StringBuilder("${c.s("status")}  ahead ${c.n("ahead_by")}  behind ${c.n("behind_by")}\n\n")
            c.list("files").forEach { f -> sb.append("### ${f.s("filename")} (+${f.n("additions")} -${f.n("deletions")})\n${f.s("patch")}\n\n") }
            diff = sb.toString()
        }
    }
    if (merge) FormDialog(stringResource(R.string.merge), listOf(Field(stringResource(R.string.base_branch), ref), Field(stringResource(R.string.head_branch))), onDismiss = { merge = false }) { v, _ ->
        act.run("merge ${v[1]} into ${v[0]}", onOk = { act.toast("OK") }) { Graph.api.send("POST", "$base/merges", obj("base" to v[0].trim(), "head" to v[1].trim())) }
    }
    diff?.let { TextViewerDialog(stringResource(R.string.compare), it) { diff = null } }
}

// ---------------------------------------------------------------- Commits

@Composable
fun CommitsTab(owner: String, name: String, ref: String) {
    val l = rememberLoader(owner, name, ref) { Graph.api.get("/repos/$owner/$name/commits?sha=${Uri.encode(ref)}&per_page=50").items() }
    val act = rememberAction()
    var detail by remember { mutableStateOf<String?>(null) }
    LoaderView(l) { list ->
        if (list.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
        else LazyColumn { items(list, key = { it.s("sha") }) { c ->
            val cm = c.obj("commit")
            ListItem(
                modifier = Modifier.clickable {
                    act.run("commit ${c.s("sha").take(7)}") {
                        val d = Graph.api.get("/repos/$owner/$name/commits/${c.s("sha")}").o()
                        val sb = StringBuilder(cm.s("message") + "\n\n")
                        d.list("files").forEach { f -> sb.append("### ${f.s("filename")} (+${f.n("additions")} -${f.n("deletions")})\n${f.s("patch")}\n\n") }
                        detail = sb.toString()
                    }
                },
                leadingContent = { Icon(Icons.Default.History, null) },
                headlineContent = { Text(cm.s("message").lineSequence().first(), maxLines = 2) },
                supportingContent = { Text("${c.s("sha").take(7)} · ${cm.obj("author").s("name")} · ${Fmt.time(cm.obj("author").s("date"))}") }
            )
        } }
    }
    detail?.let { TextViewerDialog(stringResource(R.string.tab_commits), it) { detail = null } }
}
