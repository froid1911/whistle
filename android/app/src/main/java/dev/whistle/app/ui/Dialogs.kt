package dev.whistle.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.whistle.app.protocol.BridgeError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Runs a herdr command off the UI and reports failures in [snackbar]. */
fun interface ActionRunner {
    fun run(what: String, block: suspend () -> Unit)
}

@Composable
fun rememberActionRunner(snackbar: SnackbarHostState): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember(snackbar, scope) {
        ActionRunner { what, block ->
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BridgeError) {
                    snackbar.showSnackbar("$what failed: ${describe(e)}")
                } catch (e: Exception) {
                    snackbar.showSnackbar("$what failed: ${e.message ?: e}")
                }
            }
        }
    }
}

private fun describe(e: BridgeError): String = when (e.code) {
    BridgeError.OFFLINE, BridgeError.DISCONNECTED -> "not connected"
    BridgeError.HERDR_UNAVAILABLE -> "herdr is offline"
    BridgeError.TIMEOUT -> "no answer from the bridge"
    else -> e.message?.takeIf { it.isNotBlank() } ?: e.code
}

/** Confirmation for destructive actions (close workspace/tab/pane). */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One text field per entry of [fields] (label → initial value); [onDone] gets the values in order. */
@Composable
fun TextFieldsDialog(
    title: String,
    fields: List<Pair<String, String>>,
    confirm: String,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    requireFirst: Boolean = false,
) {
    var values by rememberSaveable { mutableStateOf(fields.map { it.second }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                fields.forEachIndexed { i, (label, _) ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { v -> values = values.toMutableList().also { it[i] = v } },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !requireFirst || values.first().isNotBlank(),
                onClick = { onDismiss(); onDone(values) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
