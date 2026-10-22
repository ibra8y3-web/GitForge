package app.repodeck

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject

/** Real GitHub code search (default branch only). */
@Composable
fun SearchTab(owner: String, name: String, defaultBranch: String) {
    var q by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<JsonObject>?>(null) }
    var total by remember { mutableLongStateOf(0L) }
    var open by remember { mutableStateOf<JsonObject?>(null) }
    val act = rememberAction()
    val focus = LocalFocusManager.current

    fun go() {
        if (q.isBlank()) return
        focus.clearFocus()
        act.run("search code") {
            val query = Uri.encode(q.trim()) + (if (byName) "+in:path" else "") + "+repo:$owner/$name"
            val r = Graph.api.send("GET", "/search/code?q=$query&per_page=50", accept = "application/vnd.github.text-match+json").js().o()
            total = r.n("total_count")
            results = r.list("items")
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.search_code)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Default.Search, stringResource(R.string.search_code)) } },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!byName, { byName = false }, label = { Text(stringResource(R.string.content)) })
            FilterChip(byName, { byName = true }, label = { Text(stringResource(R.string.in_filename)) })
        }
        Text(stringResource(R.string.search_note), Modifier.padding(12.dp, 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (act.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        results?.let { list ->
            if (list.isEmpty()) EmptyState(stringResource(R.string.no_results))
            else LazyColumn {
                item { Text(stringResource(R.string.results_count, total.toInt()), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
                items(list, key = { it.s("sha") + it.s("path") }) { r ->
                    val frag = r.list("text_matches").firstOrNull()?.s("fragment")?.trim()?.take(220).orEmpty()
                    ListItem(
                        modifier = Modifier.clickable { open = r },
                        leadingContent = { Icon(Icons.Outlined.Description, null) },
                        headlineContent = { Text(r.s("path"), maxLines = 2) },
                        supportingContent = { if (frag.isNotEmpty()) Text(frag, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    open?.let { f -> FileEditor(owner, name, defaultBranch, f, { open = null }) {} }
}
