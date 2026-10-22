package app.repodeck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_SCOPES = "repo workflow delete_repo read:org admin:repo_hook notifications"

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val act = rememberAction()
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val builtIn = BuildConfig.OAUTH_CLIENT_ID
    var advanced by remember { mutableStateOf(builtIn.isBlank()) }
    var tab by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(Graph.store.clientId) }
    var secret by remember { mutableStateOf(Graph.store.clientSecret) }
    var scopes by remember { mutableStateOf(DEFAULT_SCOPES) }
    var device by remember { mutableStateOf<GitHubApi.Device?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    suspend fun finish(t: String) {
        val u = Graph.api.send("GET", "/user", token = t).js().o()
        Graph.store.addAccount(Account(u.s("login"), t, u.s("avatar_url")))
    }

    fun copyCode(code: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", code))
    }

    fun startDevice(id: String) {
        Graph.store.clientId = id
        job = act.run("login/device", onOk = onDone) {
            val dc = Graph.api.deviceStart(id, scopes.trim())
            device = dc
            copyCode(dc.userCode)
            act.toast(ctx.getString(R.string.code_copied))
            uri.openUri(dc.uri)
            try { finish(Graph.api.devicePoll(id, dc)) } finally { device = null }
        }
    }

    val cb = Graph.oauthCallback
    LaunchedEffect(cb) {
        if (cb != null) {
            Graph.oauthCallback = null
            val code = cb.getQueryParameter("code")
            val okState = cb.getQueryParameter("state") == Graph.oauthState
            if (code != null && okState) {
                act.run("login/browser", onOk = onDone) {
                    val t = Graph.api.exchangeCode(Graph.store.clientId, Graph.store.clientSecret, code, "repodeck://oauth")
                    finish(t)
                }
            } else act.toast(cb.getQueryParameter("error_description") ?: "OAuth error")
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF5B21B6))))) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            BrandMark(104.dp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(stringResource(R.string.tagline), color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val d = device
                    if (d != null) {
                        Text(stringResource(R.string.enter_code), style = MaterialTheme.typography.bodyMedium)
                        Text(d.userCode, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copyCode(d.userCode); uri.openUri(d.uri) }) { Text(stringResource(R.string.copy_and_open)) }
                            OutlinedButton(onClick = { job?.cancel(); device = null }) { Text(stringResource(R.string.cancel)) }
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.waiting_authorization), style = MaterialTheme.typography.bodySmall)
                    } else if (!advanced) {
                        Button(
                            onClick = { startDevice(builtIn) }, enabled = !act.busy,
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Icon(Icons.Default.Public, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.continue_github), style = MaterialTheme.typography.titleMedium) }
                        Text(stringResource(R.string.redirect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        TextButton(onClick = { advanced = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.other_options)) }
                    } else {
                        if (builtIn.isBlank()) Text(stringResource(R.string.onetap_setup), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.login_device)) })
                            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.login_token)) })
                            Tab(tab == 2, { tab = 2 }, text = { Text(stringResource(R.string.login_browser)) })
                        }
                        if (tab == 0) {
                            OutlinedTextField(clientId, { clientId = it }, label = { Text(stringResource(R.string.client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(scopes, { scopes = it }, label = { Text(stringResource(R.string.scopes)) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.device_help), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { uri.openUri("https://github.com/settings/developers") }) { Text(stringResource(R.string.open_oauth_settings)) }
                            Button(enabled = clientId.isNotBlank() && !act.busy, onClick = { startDevice(clientId.trim()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_device)) }
                        } else if (tab == 2) {
                            OutlinedTextField(clientId, { clientId = it }, label = { Text(stringResource(R.string.client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.client_secret)) }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(scopes, { scopes = it }, label = { Text(stringResource(R.string.scopes)) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.browser_help), style = MaterialTheme.typography.bodySmall)
                            Button(enabled = clientId.isNotBlank() && secret.isNotBlank(), onClick = {
                                Graph.store.clientId = clientId.trim(); Graph.store.clientSecret = secret.trim()
                                val st = java.util.UUID.randomUUID().toString()
                                Graph.oauthState = st
                                uri.openUri("https://github.com/login/oauth/authorize?client_id=${Uri.encode(clientId.trim())}&redirect_uri=${Uri.encode("repodeck://oauth")}&scope=${Uri.encode(scopes.trim())}&state=$st")
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_in_browser)) }
                        } else {
                            OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.pat)) },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.pat_help), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { uri.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow,delete_repo,read:org,admin:repo_hook,notifications&description=RepoDeck") }) {
                                Text(stringResource(R.string.create_token))
                            }
                            Button(enabled = token.isNotBlank() && !act.busy, onClick = {
                                act.run("login/token", onOk = onDone) { finish(token.trim()) }
                            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_in)) }
                        }
                        if (builtIn.isNotBlank()) TextButton(onClick = { advanced = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
                    }
                    if (act.busy && device == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReposScreen(nav: NavController) {
    val login = Graph.store.activeLogin
    LaunchedEffect(login) { if (login == null) nav.navigate("login") { popUpTo(0) } }
    if (login == null) return

    val me = rememberLoader(login) { Graph.api.get("/user").o() }
    val repos = rememberLoader(login) { Graph.api.pages("/user/repos?sort=updated&affiliation=owner,collaborator,organization_member") }
    val orgs = rememberLoader(login) { Graph.api.get("/user/orgs").items() }
    val act = rememberAction()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var notifs by remember { mutableStateOf(false) }
    var bulkDelete by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val filters = listOf(R.string.f_all, R.string.f_private, R.string.f_public, R.string.f_forks, R.string.f_archived, R.string.f_owned)

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size}") },
                    navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, null) } },
                    actions = {
                        IconButton(onClick = {
                            val names = selected.toList(); selected.clear()
                            act.run("archive×${names.size}", onOk = { repos.reload() }) {
                                names.forEach { Graph.api.send("PATCH", "/repos/$it", obj("archived" to true)) }
                            }
                        }) { Icon(Icons.Default.Archive, stringResource(R.string.archive)) }
                        IconButton(onClick = { bulkDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.repositories)) },
                    actions = {
                        IconButton(onClick = { notifs = true }) { Icon(Icons.Default.Notifications, stringResource(R.string.notifications)) }
                        IconButton(onClick = { nav.navigate("upload") }) { Icon(Icons.Default.Upload, stringResource(R.string.upload)) }
                        IconButton(onClick = { repos.reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                val av = Graph.store.active()?.avatar.orEmpty()
                                if (av.isNotEmpty()) AsyncImage(av, null, Modifier.size(28.dp)) else Icon(Icons.Default.AccountCircle, null)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                Graph.store.accounts.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.login + if (a.login == login) "  ✓" else "") },
                                        onClick = { menu = false; if (a.login != login) Graph.store.switchTo(a.login) }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.add_account)) }, onClick = { menu = false; nav.navigate("login") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.downloads)) }, onClick = { menu = false; nav.navigate("downloads") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; nav.navigate("settings") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.op_log)) }, onClick = { menu = false; nav.navigate("logs") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.sign_out)) }, onClick = { menu = false; Graph.store.remove(login) })
                            }
                        }
                    }
                )
            }
        },
        bottomBar = { AppBottomBar(nav, "repos") },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_repo)) })
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(stringResource(R.string.search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEachIndexed { i, f -> FilterChip(filter == i, { filter = i }, label = { Text(stringResource(f)) }) }
            }
            LoaderView(repos) { all ->
                val myLogin = me.valueOrNull()?.s("login").orEmpty()
                val shown = all.filter { r ->
                    (query.isBlank() || r.s("full_name").contains(query, true) || r.s("description").contains(query, true)) &&
                        when (filter) {
                            1 -> r.b("private"); 2 -> !r.b("private"); 3 -> r.b("fork"); 4 -> r.b("archived")
                            5 -> r.obj("owner").s("login") == myLogin
                            else -> true
                        }
                }
                if (shown.isEmpty()) EmptyState(stringResource(R.string.no_repos))
                else LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    me.valueOrNull()?.let { m -> item { HeroCard(m, all) } }
                    item { Text(stringResource(R.string.repo_count, shown.size, all.size), style = MaterialTheme.typography.labelMedium) }
                    items(shown, key = { it.n("id") }) { r -> RepoCard(r, selected, nav) }
                }
            }
        }
    }

    if (create) CreateRepoDialog(me.valueOrNull(), orgs.valueOrNull().orEmpty(), { create = false }) { repos.reload() }
    if (notifs) NotificationsDialog { notifs = false }
    if (bulkDelete) TypeConfirmDialog(stringResource(R.string.delete_n_repos, selected.size), "DELETE", { bulkDelete = false }) {
        val names = selected.toList(); selected.clear()
        act.run("delete×${names.size}", onOk = { repos.reload() }) { names.forEach { Graph.api.send("DELETE", "/repos/$it") } }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(r: JsonObject, selected: MutableList<String>, nav: NavController) {
    val full = r.s("full_name")
    val isSel = full in selected
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selected.isNotEmpty()) { if (isSel) selected.remove(full) else selected.add(full) } else nav.navigate("repo/${r.obj("owner").s("login")}/${r.s("name")}") },
            onLongClick = { if (!isSel) selected.add(full) }
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(langColor(r.s("language"))))
                Spacer(Modifier.width(8.dp))
                Text(r.s("name"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Pill(if (r.b("private")) Icons.Default.Lock else Icons.Default.Public, stringResource(if (r.b("private")) R.string.private_repo else R.string.f_public))
            }
            Text(r.obj("owner").s("login"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.s("description").isNotBlank()) Text(r.s("description"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.s("language").isNotBlank()) Text(r.s("language"), style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Star, null, Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("${r.n("stargazers_count")}", style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountTree, null, Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("${r.n("forks_count")}", style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.weight(1f))
                Text(Fmt.time(r.s("pushed_at")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.b("archived")) Pill(Icons.Default.Archive, stringResource(R.string.f_archived))
        }
    }
}

@Composable
private fun CreateRepoDialog(me: JsonObject?, orgs: List<JsonObject>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val act = rememberAction()
    var owner by remember { mutableStateOf("") }
    var ownerMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }
    var readme by remember { mutableStateOf(true) }
    var template by remember { mutableStateOf(false) }
    var ignore by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    val ignores = listOf("", "Android", "Node", "Python", "Java", "Kotlin", "Swift", "Go", "Rust", "Dart", "C++", "Unity")
    val licenses = listOf("", "mit", "apache-2.0", "gpl-3.0", "bsd-3-clause", "unlicense")
    val myLogin = me?.s("login").orEmpty()
    val ownerEff = owner.ifEmpty { myLogin }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_repo)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { ownerMenu = true }, Modifier.fillMaxWidth()) { Text("${stringResource(R.string.owner)}: $ownerEff") }
                    DropdownMenu(ownerMenu, { ownerMenu = false }) {
                        (listOf(myLogin) + orgs.map { it.s("login") }).forEach { o ->
                            DropdownMenuItem(text = { Text(o) }, onClick = { owner = o; ownerMenu = false })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.description)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(priv, { priv = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.private_repo)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(readme, { readme = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.init_readme)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(template, { template = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.is_template)) }
                PickerRow(stringResource(R.string.gitignore_tpl), ignore, ignores) { ignore = it }
                PickerRow(stringResource(R.string.license), license, licenses) { license = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !act.busy, onClick = {
                act.run("create repo $ownerEff/$name", onOk = { onDone(); onDismiss() }) {
                    val body = obj(
                        "name" to name.trim(), "description" to desc, "private" to priv, "auto_init" to readme,
                        "is_template" to template,
                        "gitignore_template" to ignore.ifEmpty { null }, "license_template" to license.ifEmpty { null }
                    )
                    val path = if (ownerEff == myLogin) "/user/repos" else "/orgs/$ownerEff/repos"
                    Graph.api.send("POST", path, body)
                }
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun PickerRow(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text("$label: ${value.ifEmpty { "—" }}") }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o.ifEmpty { "—" }) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsDialog(onClose: () -> Unit) {
    val l = rememberLoader { Graph.api.get("/notifications?all=false").items() }
    val act = rememberAction()
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.notifications)) }, actions = {
                    TextButton(onClick = { act.run("notifications read", onOk = { l.reload() }) { Graph.api.send("PUT", "/notifications", obj("read" to true)) } }) { Text(stringResource(R.string.mark_read)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                })
            }) { pad ->
                Box(Modifier.padding(pad)) {
                    LoaderView(l) { list ->
                        if (list.isEmpty()) EmptyState(stringResource(R.string.nothing_here))
                        else LazyColumn { items(list, key = { it.s("id") }) { n ->
                            ListItem(
                                headlineContent = { Text(n.obj("subject").s("title")) },
                                supportingContent = { Text("${n.obj("repository").s("full_name")} · ${n.obj("subject").s("type")} · ${n.s("reason")}") }
                            )
                            HorizontalDivider()
                        } }
                    }
                }
            }
        }
    }
}
