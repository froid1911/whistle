package dev.whistle.app.ui.snippets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Alignment
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.SnippetEntity
import dev.whistle.app.ui.EmptyHint
import dev.whistle.app.ui.ConfirmDialog
import kotlinx.coroutines.launch

/** CRUD list of snippets: tap to edit, trash to delete, + to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(repo: SnippetRepository, onBack: () -> Unit) {
    val list by remember(repo) { repo.all }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    // Saved across rotation/process death: -1 = closed, 0 = new, else the snippet id.
    var editingId by rememberSaveable { mutableStateOf(-1L) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SnippetEntity?>(null) }
    Scaffold(
        containerColor = Whistle.colors.night,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            WhistleTopBar(
                title = "Snippets",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { editingId = 0L }) { Icon(Icons.Default.Add, "New snippet", tint = Whistle.colors.wool) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            val l = list
            when {
                l == null -> Unit
                l.isEmpty() -> EmptyHint("No snippets. Tap + to save a prompt you reuse.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(l, key = { it.id }) { s ->
                        WhistleCard(onClick = { editingId = s.id }, contentPadding = 0.dp) {
                            Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = WhistleType.name, color = Whistle.colors.wool, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.text, style = WhistleType.secondary, color = Whistle.colors.sage, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { deleting = s }) { Icon(Icons.Default.Delete, "Delete", tint = Whistle.colors.sage) }
                            }
                        }
                    }
                }
            }
        }
    }
    val editedOrNull = when {
        editingId < 0 -> null
        editingId == 0L -> SnippetEntity(title = "", text = "")
        else -> list?.firstOrNull { it.id == editingId }
    }
    if (editingId > 0 && list != null && editedOrNull == null) editingId = -1L // deleted elsewhere
    editedOrNull?.let { s ->
        var title by rememberSaveable(s.id) { mutableStateOf(s.title) }
        var text by rememberSaveable(s.id) { mutableStateOf(s.text) }
        // Outside tap / back never silently drops typed text.
        val dismiss = { if (snippetDirty(s, title, text)) confirmDiscard = true else editingId = -1L }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(if (s.id == 0L) "New snippet" else "Edit snippet") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    editingId = -1L
                    scope.launch { repo.save(s.id, title, text) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        if (confirmDiscard) {
            ConfirmDialog(
                title = "Discard changes?",
                text = "Your edits to this snippet will be lost.",
                confirm = "Discard",
                onConfirm = { editingId = -1L },
                onDismiss = { confirmDiscard = false },
            )
        }
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete snippet?",
            text = "“${s.title}” will be deleted.",
            confirm = "Delete",
            onConfirm = { scope.launch { repo.delete(s.id) } },
            onDismiss = { deleting = null },
        )
    }
}
