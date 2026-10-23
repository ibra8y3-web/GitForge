package app.repodeck

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

// ---------------------------------------------------------------- Actions (build)

private val ANDROID_WF = """name: Android Build
on:
  push:
    branches: [ main ]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - run: chmod +x ./gradlew || true
      - run: ./gradlew assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: '**/build/outputs/apk/debug/*.apk'
"""

private val RELEASE_STEP = """      - name: Publish release (auto version)
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${'$'}{{ github.run_number }}
          name: Build ${'$'}{{ github.run_number }}
          target_commitish: ${'$'}{{ github.sha }}
          files: '**/build/outputs/apk/debug/*.apk'
          generate_release_notes: true
"""

private val ANDROID_REL = ANDROID_WF.replace("jobs:\n", "permissions:\n  contents: write\njobs:\n") + RELEASE_STEP

private val NODE_WF = """name: Node CI
on:
  push:
    branches: [ main ]
  pull_request:
  workflow_dispatch:
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: npm ci || npm install
      - run: npm test --if-present
      - run: npm run build --if-present
"""

@Composable
fun ActionsTab(owner: String, name: String, ref: String) {
    val base = "/repos/$owner/$name/actions"
    val ctx = LocalContext.current
    val runs = rememberLoader(owner, name) { Graph.api.get("$base/runs?per_page=30").o().list("workflow_runs") }
    val wfs = rememberLoader(owner, name) { Graph.api.get("$base/workflows").o().list("workflows") }
    val act = rememberAction()
    var open by remember { mutableStateOf<JsonObject?>(null) }
    var addWf by remember { mutableStateOf(false) }
    val seen = remember { mutableMapOf<Long, String>() }

    LaunchedEffect(owner, name) {
        while (true) {
            delay(10_000)
            val cur = runs.valueOrNull().orEmpty()
            if (cur.any { it.s("status") != "completed" }) runs.refreshQuiet()
        }
    }
    LaunchedEffect(runs.state) {
        runs.valueOrNull()?.forEach { r ->
            val id = r.n("id")
            val prev = seen[id]
            if (prev != null && prev != "completed" && r.s("status") == "completed")
                Notifier.notify(ctx, id.toInt(), "$owner/$name", "${r.s("name")}: ${r.s("conclusion")}")
            seen[id] = r.s("status")
        }
    }

    LaunchedEffect(runs.state) {
        if (runs.state is Res.Ok && Graph.store.widgetRepo == "$owner/$name") BuildWidget.requestUpdate(ctx)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addWf = true }) { Text(stringResource(R.string.add_workflow)) }
                OutlinedButton(onClick = {
                    Graph.store.widgetRepo = "$owner/$name"
                    BuildWidget.requestUpdate(ctx)
                    act.toast(ctx.getString(R.string.widget_pinned))
                }) { Text(stringResource(R.string.pin_widget)) }
            }
            if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionTitle(stringResource(R.string.workflows))
        }
        when (val s = wfs.state) {
            is Res.Ok -> {
                if (s.v.isEmpty()) item { Text(stringResource(R.string.no_workflows), Modifier.padding(16.dp)) }
                items(s.v, key = { "w" + it.n("id") }) { w ->
                    ListItem(
                        headlineContent = { Text(w.s("name")) }, supportingContent = { Text(w.s("path") + " · " + w.s("state")) },
                        trailingContent = {
                            IconButton(onClick = {
                                act.run("dispatch ${w.s("name")}", onOk = { runs.reload() }) {
                                    Graph.api.send("POST", "$base/workflows/${w.n("id")}/dispatches", obj("ref" to ref))
                                }
                            }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.run)) }
                        }
                    )
                }
            }
            is Res.Err -> item { Text(s.msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            else -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        item { SectionTitle(stringResource(R.string.runs)) }
        when (val s = runs.state) {
            is Res.Ok -> {
                if (s.v.isEmpty()) item { Text(stringResource(R.string.nothing_here), Modifier.padding(16.dp)) }
                items(s.v, key = { "r" + it.n("id") }) { r ->
                    val st = r.s("status"); val c = r.s("conclusion")
                    ListItem(
                        modifier = Modifier.clickable { open = r },
                        leadingContent = {
                            when {
                                st != "completed" -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                c == "success" -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2EA043))
                                c == "cancelled" || c == "skipped" -> Icon(Icons.Default.Cancel, null)
                                else -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        headlineContent = { Text("#${r.n("run_number")} ${r.s("display_title").ifEmpty { r.s("name") }}", maxLines = 2) },
                        supportingContent = { Text("${r.s("name")} · ${r.s("head_branch")} · ${r.s("event")} · ${Fmt.time(r.s("created_at"))}") }
                    )
                }
            }
            else -> {}
        }
    }
    if (addWf) {
        FormDialog(stringResource(R.string.add_workflow), listOf(Field(stringResource(R.string.file_path), ".github/workflows/build.yml"), Field(stringResource(R.string.template_type), "android"), Field(stringResource(R.string.auto_release), "yes")), onDismiss = { addWf = false }) { v, _ ->
            act.run("add workflow ${v[0]}", onOk = { wfs.reload() }) {
                val root = Graph.api.get("/repos/$owner/$name/contents?ref=${Uri.encode(ref)}").items().map { it.s("name") }.toSet()
                val type = v[1].trim().ifEmpty { GitIgnore.detect(root) }
                val auto = v[2].trim().lowercase() !in listOf("no", "n", "لا")
                val tpl = if (type == "node") NODE_WF else if (auto) ANDROID_REL else ANDROID_WF
                putFile(owner, name, ref, v[0].trim(), tpl.toByteArray(), "Add CI workflow")
            }
        }
    }
    open?.let { r -> RunDialog(owner, name, r, { open = null }) { runs.reload() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDialog(owner: String, name: String, run: JsonObject, onClose: () -> Unit, onChanged: () -> Unit) {
    val id = run.n("id")
    val base = "/repos/$owner/$name/actions"
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val jobs = rememberLoader(id) { Graph.api.get("$base/runs/$id/jobs").o().list("jobs") }
    val arts = rememberLoader(id) { Graph.api.get("$base/runs/$id/artifacts").o().list("artifacts") }
    val act = rememberAction()
    var log by remember { mutableStateOf<Pair<String, String>?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    val files = remember { mutableStateMapOf<Long, File>() }
    val info = remember { mutableStateMapOf<Long, String>() }
    val apks = remember { mutableStateMapOf<Long, List<File>>() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                BrandTopBar(title = { Text("#${run.n("run_number")} ${run.s("name")}", maxLines = 1) }, actions = {
                    IconButton(onClick = { jobs.reload(); arts.reload() }) { Icon(Icons.Default.Refresh, null) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                })
            }) { pad ->
                LazyColumn(Modifier.padding(pad)) {
                    item {
                        Row(Modifier.padding(horizontal = 12.dp).horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { act.run("rerun $id", onOk = onChanged) { Graph.api.send("POST", "$base/runs/$id/rerun") } }) { Text(stringResource(R.string.rerun)) }
                            OutlinedButton(onClick = { act.run("rerun failed $id", onOk = onChanged) { Graph.api.send("POST", "$base/runs/$id/rerun-failed-jobs") } }) { Text(stringResource(R.string.rerun_failed)) }
                            OutlinedButton(onClick = { act.run("cancel $id", onOk = onChanged) { Graph.api.send("POST", "$base/runs/$id/cancel") } }) { Text(stringResource(R.string.cancel_run)) }
                            OutlinedButton(onClick = { act.run("diagnose $id") { diag = BuildDoctor.diagnose(ctx, base, jobs.valueOrNull().orEmpty()) } }) { Text(stringResource(R.string.diagnose)) }
                            OutlinedButton(onClick = { uri.openUri(run.s("html_url")) }) { Text(stringResource(R.string.open_browser)) }
                        }
                        if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        SectionTitle(stringResource(R.string.jobs))
                    }
                    when (val s = jobs.state) {
                        is Res.Ok -> items(s.v, key = { "j" + it.n("id") }) { j ->
                            Column(Modifier.clickable {
                                act.run("log ${j.s("name")}") { log = j.s("name") to BuildDoctor.clean(Graph.api.send("GET", "$base/jobs/${j.n("id")}/logs").body).takeLast(200_000) }
                            }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("${j.s("name")} — ${j.s("conclusion").ifEmpty { j.s("status") }}", style = MaterialTheme.typography.titleSmall)
                                j.list("steps").forEach { st ->
                                    Text("  ${if (st.s("conclusion") == "success") "✓" else if (st.s("conclusion") == "failure") "✗" else "•"} ${st.s("name")}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                        }
                        is Res.Err -> item { Text(s.msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                        else -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                    item { SectionTitle(stringResource(R.string.artifacts)) }
                    when (val s = arts.state) {
                        is Res.Ok -> {
                            if (s.v.isEmpty()) item { Text(stringResource(R.string.nothing_here), Modifier.padding(16.dp)) }
                            items(s.v, key = { "a" + it.n("id") }) { a ->
                                val aid = a.n("id")
                                ListItem(
                                    headlineContent = { Text(a.s("name")) },
                                    supportingContent = { Text(Fmt.size(a.n("size_in_bytes")) + (if (a.b("expired")) " · expired" else "") + (info[aid]?.let { "\n$it" } ?: "")) },
                                    trailingContent = {
                                        Row {
                                            IconButton(enabled = !a.b("expired"), onClick = {
                                                act.run("download ${a.s("name")}") {
                                                    val prefix = "$name-run${run.n("run_number")}"
                                                    val f = File(Downloads.dir(ctx), "$prefix-${a.s("name")}.zip")
                                                    Graph.api.download("https://api.github.com$base/artifacts/$aid/zip", f) { d, t -> info[aid] = "${Fmt.size(d)} / ${Fmt.size(t)}" }
                                                    files[aid] = f
                                                    val result = withContext(Dispatchers.IO) {
                                                        val extracted = ZipTools.extractApks(f, Downloads.dir(ctx), prefix)
                                                        val saved = (listOf(f) + extracted).count { Downloads.exportToPublic(ctx, it) }
                                                        Triple(extracted, saved, ZipTools.verify(f))
                                                    }
                                                    apks[aid] = result.first
                                                    info[aid] = result.third + "\n" + ctx.getString(if (result.second > 0) R.string.saved_public else R.string.saved_app_only)
                                                }
                                            }) { Icon(Icons.Default.Download, stringResource(R.string.download)) }
                                            (apks[aid]?.let { l -> l.firstOrNull { it.name.contains("release") } ?: l.firstOrNull() })?.let { apk ->
                                                IconButton(onClick = { act.run("install apk") { Installer.install(ctx, apk) } }) {
                                                    Icon(Icons.Default.InstallMobile, stringResource(R.string.install))
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        else -> {}
                    }
                    item {
                        TextButton(onClick = {
                            act.run("run logs $id") {
                                val f = File(ctx.cacheDir, "downloads/logs-$id.zip")
                                Graph.api.download("https://api.github.com$base/runs/$id/logs", f)
                                log = "logs #$id" to BuildDoctor.clean(ZipTools.readLogZip(f))
                            }
                        }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.full_logs)) }
                    }
                }
            }
        }
    }
    log?.let { (t, txt) -> TextViewerDialog(t, txt) { log = null } }
    diag?.let { TextViewerDialog(stringResource(R.string.diagnose), it) { diag = null } }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

// ---------------------------------------------------------------- Releases

@Composable
fun ReleasesTab(owner: String, name: String, ref: String) {
    val base = "/repos/$owner/$name"
    val l = rememberLoader(owner, name) { Graph.api.pages("$base/releases", 3) }
    val act = rememberAction()
    val ctx = LocalContext.current
    var create by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<JsonObject?>(null) }
    var target by remember { mutableStateOf<JsonObject?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rel = target
        if (uri != null && rel != null) {
            val fn = uri.displayName(ctx)
            val len = uri.fileSize(ctx)
            act.run("release asset $fn", onOk = { l.reload() }) {
                Graph.api.uploadAsset(rel.s("upload_url"), fn, ctx.contentResolver.getType(uri) ?: "application/octet-stream", len) { ctx.contentResolver.openInputStream(uri)!! }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp)) { Button(onClick = { create = true }) { Text(stringResource(R.string.new_release)) } }
        if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LoaderView(l) { list ->
            if (list.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
            else LazyColumn { items(list, key = { it.n("id") }) { r ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${r.s("name").ifEmpty { r.s("tag_name") }}  (${r.s("tag_name")})" + (if (r.b("draft")) " · draft" else "") + (if (r.b("prerelease")) " · pre" else ""), style = MaterialTheme.typography.titleSmall)
                    r.list("assets").forEach { a -> Text("• ${a.s("name")} — ${Fmt.size(a.n("size"))}", style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(onClick = { target = r; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.attach_file)) }
                        TextButton(onClick = { del = r }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
                HorizontalDivider()
            } }
        }
    }
    if (create) FormDialog(stringResource(R.string.new_release),
        listOf(Field("Tag"), Field(stringResource(R.string.name)), Field(stringResource(R.string.description), multi = true), Field(stringResource(R.string.from_branch), ref)),
        listOf("Pre-release", "Draft"), onDismiss = { create = false }) { v, t ->
        act.run("release ${v[0]}", onOk = { l.reload() }) {
            Graph.api.send("POST", "$base/releases", obj("tag_name" to v[0].trim(), "name" to v[1], "body" to v[2], "target_commitish" to v[3].trim(), "prerelease" to t[0], "draft" to t[1]))
        }
    }
    del?.let { r -> ConfirmDialog(stringResource(R.string.confirm_delete, r.s("tag_name")), { del = null }) {
        act.run("delete release", onOk = { l.reload() }) { Graph.api.send("DELETE", "$base/releases/${r.n("id")}") }
    } }
}

// ---------------------------------------------------------------- Issues + Pull requests

@Composable
fun ThreadsTab(owner: String, name: String, pulls: Boolean) {
    val base = "/repos/$owner/$name"
    var state by remember { mutableStateOf("open") }
    val l = rememberLoader(owner, name, state, pulls) {
        if (pulls) Graph.api.get("$base/pulls?state=$state&per_page=50").items()
        else Graph.api.get("$base/issues?state=$state&per_page=50").items().filter { !it.containsKey("pull_request") }
    }
    val act = rememberAction()
    var create by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { create = true }) { Text(stringResource(if (pulls) R.string.new_pr else R.string.new_issue)) }
            FilterChip(state == "open", { state = "open" }, label = { Text(stringResource(R.string.open_state)) })
            FilterChip(state == "closed", { state = "closed" }, label = { Text(stringResource(R.string.closed_state)) })
        }
        LoaderView(l) { list ->
            if (list.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
            else LazyColumn { items(list, key = { it.n("id") }) { t ->
                ListItem(
                    modifier = Modifier.clickable { open = t },
                    headlineContent = { Text("#${t.n("number")} ${t.s("title")}", maxLines = 2) },
                    supportingContent = { Text("${t.obj("user").s("login")} · ${Fmt.time(t.s("created_at"))}" + (if (pulls) " · ${t.obj("head").s("ref")} → ${t.obj("base").s("ref")}" else "")) }
                )
            } }
        }
    }
    if (create) {
        val fields = if (pulls) listOf(Field(stringResource(R.string.title)), Field(stringResource(R.string.head_branch)), Field(stringResource(R.string.base_branch)), Field(stringResource(R.string.description), multi = true))
        else listOf(Field(stringResource(R.string.title)), Field(stringResource(R.string.description), multi = true))
        FormDialog(stringResource(if (pulls) R.string.new_pr else R.string.new_issue), fields, onDismiss = { create = false }) { v, _ ->
            act.run(if (pulls) "create PR" else "create issue", onOk = { l.reload() }) {
                if (pulls) Graph.api.send("POST", "$base/pulls", obj("title" to v[0], "head" to v[1].trim(), "base" to v[2].trim(), "body" to v[3]))
                else Graph.api.send("POST", "$base/issues", obj("title" to v[0], "body" to v[1]))
            }
        }
    }
    open?.let { t -> ThreadDialog(owner, name, t, pulls, { open = null }) { l.reload() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDialog(owner: String, name: String, t: JsonObject, pulls: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    val base = "/repos/$owner/$name"
    val n = t.n("number")
    val comments = rememberLoader(n) { Graph.api.get("$base/issues/$n/comments?per_page=100").items() }
    val act = rememberAction()
    var text by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<String?>(null) }
    val closed = t.s("state") == "closed"
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { BrandTopBar(title = { Text("#$n ${t.s("title")}", maxLines = 1) }, actions = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } }) }) { pad ->
                Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.s("body").ifEmpty { "—" }, maxLines = 8)
                    Row(Modifier.horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { act.run("${if (closed) "reopen" else "close"} #$n", onOk = { onChanged(); onClose() }) { Graph.api.send("PATCH", "$base/issues/$n", obj("state" to if (closed) "open" else "closed")) } }) {
                            Text(stringResource(if (closed) R.string.reopen else R.string.close_item))
                        }
                        if (pulls && !closed) {
                            listOf("merge" to R.string.merge, "squash" to R.string.squash, "rebase" to R.string.rebase).forEach { (m, label) ->
                                Button(onClick = { act.run("$m PR #$n", onOk = { onChanged(); onClose() }) { Graph.api.send("PUT", "$base/pulls/$n/merge", obj("merge_method" to m)) } }) { Text(stringResource(label)) }
                            }
                            OutlinedButton(onClick = { review = "APPROVE" }) { Text(stringResource(R.string.approve)) }
                            OutlinedButton(onClick = { review = "REQUEST_CHANGES" }) { Text(stringResource(R.string.request_changes)) }
                        }
                    }
                    if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)) {
                        LoaderView(comments) { list ->
                            LazyColumn { items(list, key = { it.n("id") }) { c ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text("${c.obj("user").s("login")} · ${Fmt.time(c.s("created_at"))}", style = MaterialTheme.typography.labelSmall)
                                    Text(c.s("body"))
                                }
                                HorizontalDivider()
                            } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.comment)) })
                        IconButton(enabled = text.isNotBlank(), onClick = {
                            val b = text; text = ""
                            act.run("comment #$n", onOk = { comments.reload() }) { Graph.api.send("POST", "$base/issues/$n/comments", obj("body" to b)) }
                        }) { Icon(Icons.Default.Send, stringResource(R.string.comment)) }
                    }
                }
            }
        }
    }
    review?.let { ev -> FormDialog(stringResource(R.string.review), listOf(Field(stringResource(R.string.comment), multi = true)), onDismiss = { review = null }) { v, _ ->
        act.run("review #$n $ev", onOk = { onChanged(); onClose() }) { Graph.api.send("POST", "$base/pulls/$n/reviews", obj("event" to ev, "body" to v[0])) }
    } }
}
