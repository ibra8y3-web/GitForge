package app.gitforge

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface Res<out T> {
    data object Loading : Res<Nothing>
    data class Ok<T>(val v: T) : Res<T>
    data class Err(val msg: String) : Res<Nothing>
}

class Loader<T>(private val scope: CoroutineScope, private val block: suspend () -> T) {
    var state by mutableStateOf<Res<T>>(Res.Loading)
        private set
    private var job: Job? = null

    init { fire() }

    private fun fire() {
        job?.cancel()
        job = scope.launch {
            state = try { Res.Ok(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Res.Err(Fmt.err(e)) }
        }
    }

    fun reload() { state = Res.Loading; fire() }
    fun refreshQuiet() = fire()
    fun valueOrNull(): T? = (state as? Res.Ok<T>)?.v
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, block: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    return remember(*keys) { Loader(scope, block) }
}

/** Runs a real operation, records it in the operations log, shows the real error on failure. */
class Action(private val scope: CoroutineScope, private val ctx: Context) {
    var busy by mutableStateOf(false)
        private set

    fun run(op: String, onOk: () -> Unit = {}, block: suspend () -> Unit): Job = scope.launch {
        busy = true
        try {
            block()
            OpLog.add(true, op)
            onOk()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OpLog.add(false, op, Fmt.err(e))
            Toast.makeText(ctx, Fmt.err(e), Toast.LENGTH_LONG).show()
        } finally {
            busy = false
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

@Composable
fun rememberAction(): Action {
    val s = rememberCoroutineScope()
    val c = LocalContext.current
    return remember { Action(s, c) }
}

@Composable
fun <T> LoaderView(l: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = l.state) {
        is Res.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is Res.Err -> Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(s.msg, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { l.reload() }) { Text(stringResource(R.string.retry)) }
        }
        is Res.Ok -> content(s.v)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

data class Field(val label: String, val init: String = "", val multi: Boolean = false, val secret: Boolean = false)

@Composable
fun FormDialog(
    title: String,
    fields: List<Field>,
    toggles: List<String> = emptyList(),
    confirm: String = stringResource(R.string.ok),
    onDismiss: () -> Unit,
    onSubmit: (List<String>, List<Boolean>) -> Unit
) {
    val vals = remember { mutableStateListOf(*fields.map { it.init }.toTypedArray()) }
    val tg = remember { mutableStateListOf(*Array(toggles.size) { false }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, f ->
                    OutlinedTextField(
                        value = vals[i], onValueChange = { vals[i] = it }, label = { Text(f.label) },
                        minLines = if (f.multi) 4 else 1, singleLine = !f.multi,
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                toggles.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = tg[i], onCheckedChange = { tg[i] = it })
                        Spacer(Modifier.width(8.dp)); Text(t)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(vals.toList(), tg.toList()); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** The destructive action only unlocks after the exact word/name is typed. */
@Composable
fun TypeConfirmDialog(title: String, word: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.type_to_confirm, word))
                OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = typed == word, onClick = { onConfirm(); onDismiss() }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerDialog(title: String, text: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(title, maxLines = 1) }, actions = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                })
            }) { pad ->
                SelectionContainer {
                    Box(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
