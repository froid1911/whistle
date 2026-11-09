package dev.whistle.app.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.SnippetDao
import dev.whistle.app.data.SnippetEntity
import kotlinx.coroutines.flow.Flow

/** The user's saved prompts (Room-backed, never part of the resettable cache). */
class SnippetRepository(private val dao: SnippetDao) {
    val all: Flow<List<SnippetEntity>> get() = dao.all()

    /** Creates (id 0) or updates; blank text is rejected, a blank title falls back to the text's first line. */
    suspend fun save(id: Long, title: String, text: String): Boolean {
        if (text.isBlank()) return false
        val t = title.trim().ifEmpty { text.trim().lineSequence().first().take(40) }
        dao.upsert(SnippetEntity(id, t, text))
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)
}

/** Whether the edit dialog holds text that differs from the stored snippet (dismissing would lose it). */
fun snippetDirty(original: SnippetEntity, title: String, text: String) = title != original.title || text != original.text

/** "Snippets" text button: opens a list; picking one calls [onPick] with its text. */
@Composable
fun SnippetPickerButton(repo: SnippetRepository, onPick: (String) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("snippetsButton")) { Text("Snippets") }
    if (open) SnippetPickerDialog(repo, onPick = { open = false; onPick(it) }, onManage = { open = false; onManage() }, onDismiss = { open = false })
}

@Composable
fun SnippetPickerDialog(repo: SnippetRepository, onPick: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val list by remember(repo) { repo.all }.collectAsStateWithLifecycle(emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert snippet") },
        text = {
            if (list.isEmpty()) {
                Text("No snippets yet.")
            } else {
                LazyColumn {
                    items(list, key = { it.id }) { s ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(s.text) },
                            headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(s.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManage) { Text("Manage…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
