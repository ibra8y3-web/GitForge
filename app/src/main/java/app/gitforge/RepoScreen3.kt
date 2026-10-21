package app.gitforge

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// ---------------------------------------------------------------- Local git (JGit)

@Composable
fun LocalTab(owner: String, name: String) {
    val ctx = LocalContext.current
    val act = rememberAction()
    val dir = remember { GitOps.dir(ctx, owner, name) }
    var cloned by remember { mutableStateOf(GitOps.exists(dir)) }
    var out by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf(false) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var delLocal by remember { mutableStateOf(false) }
    fun log(s: String) { out = (out + "\n" + s).takeLast(4000) }
    val token = Graph.store.activeToken().orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall)
        if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!cloned) {
            Button(onClick = { act.run("clone $owner/$name", onOk = { cloned = true }) { GitOps.clone(ctx, owner, name, token) { log(it) } } }) { Text(stringResource(R.string.clone)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act.run("pull $owner/$name") { log(GitOps.pull(dir, token) { log(it) }) } }) { Text(stringResource(R.string.pull)) }
                OutlinedButton(onClick = { act.run("status") { out = GitOps.status(dir) } }) { Text(stringResource(R.string.status)) }
                OutlinedButton(onClick = { commit = true }) { Text(stringResource(R.string.commit_push)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act.run("branches") { branches = GitOps.branches(dir) } }) { Text(stringResource(R.string.local_branches)) }
                OutlinedButton(onClick = { delLocal = true }) { Text(stringResource(R.string.delete_local)) }
            }
            branches.forEach { b ->
                TextButton(onClick = { act.run("checkout $b") { GitOps.checkout(dir, b); log("checkout $b") } }) { Text(b) }
            }
        }
        Text(out, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
    if (commit) FormDialog(stringResource(R.string.commit_push), listOf(Field(stringResource(R.string.commit_message), "Update")), onDismiss = { commit = false }) { v, _ ->
        act.run("commit+push $owner/$name") {
            val acc = Graph.store.active()
            log(GitOps.commitPush(dir, token, v[0], acc?.login ?: "user", "${acc?.login ?: "user"}@users.noreply.github.com") { log(it) })
        }
    }
    if (delLocal) ConfirmDialog(stringResource(R.string.confirm_delete, dir.name), { delLocal = false }) {
        act.run("delete local clone") { dir.deleteRecursively(); cloned = false; out = "" }
    }
}

// ---------------------------------------------------------------- Repo settings & admin

@Composable
fun RepoSettingsTab(nav: NavController, owner: String, name: String, r: kotlinx.serialization.json.JsonObject, onChanged: () -> Unit) {
    val base = "/repos/$owner/$name"
    val act = rememberAction()
    var edit by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var fork by remember { mutableStateOf(false) }
    var generate by remember { mutableStateOf(false) }
    var section by remember { mutableIntStateOf(0) }
    val sections = listOf(R.string.labels, R.string.milestones, R.string.collaborators, R.string.webhooks, R.string.deploy_keys)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(r.s("description").ifEmpty { "—" })
                Text("${if (r.b("private")) stringResource(R.string.private_repo) else stringResource(R.string.f_public)} · ${r.s("default_branch")} · ${Fmt.size(r.n("size") * 1024)}", style = MaterialTheme.typography.labelMedium)
                Button(onClick = { edit = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_settings)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        act.run(if (r.b("archived")) "unarchive" else "archive", onOk = onChanged) { Graph.api.send("PATCH", base, obj("archived" to !r.b("archived"))) }
                    }) { Text(stringResource(if (r.b("archived")) R.string.unarchive else R.string.archive)) }
                    OutlinedButton(onClick = { fork = true }) { Text(stringResource(R.string.fork)) }
                    if (r.b("is_template")) OutlinedButton(onClick = { generate = true }) { Text(stringResource(R.string.use_template)) }
                }
                OutlinedButton(onClick = { transfer = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.transfer)) }
                Button(onClick = { delete = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_repo)) }
            }
            ScrollableTabRow(selectedTabIndex = section, edgePadding = 8.dp) {
                sections.forEachIndexed { i, s -> Tab(section == i, { section = i }, text = { Text(stringResource(s)) }) }
            }
        }
        item { AdminSection(section, base, act) }
    }

    if (edit) FormDialog(stringResource(R.string.edit_settings),
        listOf(Field(stringResource(R.string.name), name), Field(stringResource(R.string.description), r.s("description")), Field("Homepage", r.s("homepage")), Field(stringResource(R.string.default_branch), r.s("default_branch"))),
        listOf(stringResource(R.string.private_repo), stringResource(R.string.is_template)), onDismiss = { edit = false }) { v, t ->
        act.run("update settings", onOk = {
            if (v[0].trim() != name) nav.popBackStack() else onChanged()
        }) {
            Graph.api.send("PATCH", base, obj("name" to v[0].trim(), "description" to v[1], "homepage" to v[2], "default_branch" to v[3].trim(), "private" to t[0], "is_template" to t[1]))
        }
    }
    if (fork) FormDialog(stringResource(R.string.fork), listOf(Field(stringResource(R.string.org_optional)), Field(stringResource(R.string.name))), onDismiss = { fork = false }) { v, _ ->
        act.run("fork $owner/$name") {
            Graph.api.send("POST", "$base/forks", obj("organization" to v[0].trim().ifEmpty { null }, "name" to v[1].trim().ifEmpty { null }))
        }
    }
    if (generate) FormDialog(stringResource(R.string.use_template), listOf(Field(stringResource(R.string.owner)), Field(stringResource(R.string.name)), Field(stringResource(R.string.description))), listOf(stringResource(R.string.private_repo)), onDismiss = { generate = false }) { v, t ->
        act.run("generate from template") { Graph.api.send("POST", "$base/generate", obj("owner" to v[0].trim().ifEmpty { Graph.store.activeLogin }, "name" to v[1].trim(), "description" to v[2], "private" to t[0])) }
    }
    if (transfer) FormDialog(stringResource(R.string.transfer), listOf(Field(stringResource(R.string.new_owner))), onDismiss = { transfer = false }) { v, _ ->
        act.run("transfer $owner/$name → ${v[0]}", onOk = { nav.popBackStack() }) { Graph.api.send("POST", "$base/transfer", obj("new_owner" to v[0].trim())) }
    }
    if (delete) TypeConfirmDialog(stringResource(R.string.delete_repo), "$owner/$name", { delete = false }) {
        act.run("delete $owner/$name", onOk = { nav.popBackStack() }) { Graph.api.send("DELETE", base) }
    }
}

@Composable
private fun AdminSection(section: Int, base: String, act: Action) {
    val path = when (section) { 0 -> "/labels"; 1 -> "/milestones?state=all"; 2 -> "/collaborators"; 3 -> "/hooks"; else -> "/keys" }
    val l = rememberLoader(base, section) { Graph.api.pages("$base$path", 5) }
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp)) { Button(onClick = { add = true }) { Text(stringResource(R.string.add)) } }
        when (val s = l.state) {
            is Res.Ok -> {
                if (s.v.isEmpty()) Text(stringResource(R.string.nothing_here), Modifier.padding(16.dp))
                s.v.forEach { it0 ->
                    val title = when (section) {
                        0 -> it0.s("name"); 1 -> it0.s("title") + " (" + it0.s("state") + ")"
                        2 -> it0.s("login") + " · " + it0.s("role_name")
                        3 -> it0.obj("config").s("url"); else -> it0.s("title")
                    }
                    val del = when (section) {
                        0 -> "$base/labels/${android.net.Uri.encode(it0.s("name"))}"
                        1 -> "$base/milestones/${it0.n("number")}"
                        2 -> "$base/collaborators/${it0.s("login")}"
                        3 -> "$base/hooks/${it0.n("id")}"
                        else -> "$base/keys/${it0.n("id")}"
                    }
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(when (section) { 3 -> ((it0["events"] as? JsonArray)?.joinToString { e -> (e as? JsonPrimitive)?.content.orEmpty() }).orEmpty(); 4 -> if (it0.b("read_only")) "read-only" else "read/write"; else -> it0.s("description") }) },
                        trailingContent = { IconButton(onClick = { act.run("delete ${title}", onOk = { l.reload() }) { Graph.api.send("DELETE", del) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } }
                    )
                }
            }
            is Res.Err -> Text(s.msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (add) {
        val (fields, toggles) = when (section) {
            0 -> listOf(Field(stringResource(R.string.name)), Field("Color (hex)", "1f6feb"), Field(stringResource(R.string.description))) to emptyList<String>()
            1 -> listOf(Field(stringResource(R.string.title)), Field(stringResource(R.string.description)), Field("Due (YYYY-MM-DDThh:mm:ssZ)")) to emptyList<String>()
            2 -> listOf(Field(stringResource(R.string.username)), Field("Permission (pull/push/admin)", "push")) to emptyList<String>()
            3 -> listOf(Field("URL"), Field("Secret", secret = true), Field("Events (comma separated)", "push")) to emptyList<String>()
            else -> listOf(Field(stringResource(R.string.title)), Field("Public key", multi = true)) to listOf("Read-only")
        }
        FormDialog(stringResource(R.string.add), fields, toggles, onDismiss = { add = false }) { v, t ->
            act.run("add ${path.substringBefore('?')}", onOk = { l.reload() }) {
                when (section) {
                    0 -> Graph.api.send("POST", "$base/labels", obj("name" to v[0].trim(), "color" to v[1].trim().removePrefix("#"), "description" to v[2]))
                    1 -> Graph.api.send("POST", "$base/milestones", obj("title" to v[0], "description" to v[1], "due_on" to v[2].trim().ifEmpty { null }))
                    2 -> Graph.api.send("PUT", "$base/collaborators/${v[0].trim()}", obj("permission" to v[1].trim()))
                    3 -> Graph.api.send("POST", "$base/hooks", buildJsonObject {
                        put("name", "web"); put("active", true)
                        put("events", JsonArray(v[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }))
                        put("config", obj("url" to v[0].trim(), "content_type" to "json", "secret" to v[1].ifEmpty { null }))
                    })
                    else -> Graph.api.send("POST", "$base/keys", obj("title" to v[0], "key" to v[1].trim(), "read_only" to t[0]))
                }
            }
        }
    }
}
