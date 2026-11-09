package dev.whistle.app.ui.changes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.CommitMono
import dev.whistle.app.security.LocalStepUp
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.PaneUi
import dev.whistle.app.ui.ConnBanner
import dev.whistle.app.ui.EmptyHint
import dev.whistle.app.ui.chat.DiffLine
import dev.whistle.app.ui.chat.DiffListView
import dev.whistle.app.ui.chat.DraftStore
import dev.whistle.app.ui.isAgent
import dev.whistle.app.ui.title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** All panes of a workspace (across its tabs). */
private fun HerdrRepository.workspacePanes(machineId: String, wsId: String): Flow<List<PaneUi>> =
    tabs(machineId, wsId).flatMapLatest { tabs ->
        if (tabs.isEmpty()) flowOf(emptyList()) else combine(tabs.map { panes(machineId, it.id) }) { it.toList().flatten() }
    }

/**
 * git status / diff / commit of a workspace. [initialCwd] (the pane the user came from) is the
 * default path. "Ask agent" on a long-pressed diff line appends to that agent's draft and
 * calls [onOpenPane] to show its chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesScreen(
    repo: HerdrRepository,
    drafts: DraftStore,
    machineId: String,
    workspaceId: String,
    initialCwd: String?,
    onBack: () -> Unit,
    onOpenPane: (PaneUi) -> Unit,
) {
    val vm = remember(repo, drafts, machineId) { ChangesViewModel(machineId, repo.changesBackend(machineId), drafts) }
    val s by vm.state.collectAsStateWithLifecycle()
    val conn by remember(machineId) { repo.connState(machineId) }.collectAsStateWithLifecycle()
    val wsPanes by remember(machineId, workspaceId) { repo.workspacePanes(machineId, workspaceId) }.collectAsStateWithLifecycle(emptyList())
    val agentPanes by remember(machineId) { repo.agentPanes(machineId) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var picker by remember { mutableStateOf(false) }
    var committing by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<NumberedLine?>(null) }

    LaunchedEffect(vm) { vm.loadWorktrees() }
    LaunchedEffect(vm, wsPanes.map { it.cwd }) {
        if (wsPanes.isNotEmpty() || s.path == null) vm.setPanePaths(wsPanes.map { it.cwd }, initialCwd)
    }

    Scaffold(
        containerColor = Whistle.colors.night,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            WhistleTopBar(
                title = if (s.file != null) s.file!!.substringAfterLast('/') else "Changes",
                subtitle = if (s.file != null) s.file else s.branch?.let { "on $it" },
                onBack = { if (s.file != null) vm.closeFile() else onBack() },
                actions = {
                    IconButton(onClick = { scope.launch { if (s.file != null) vm.openFile(s.file!!) else vm.refresh() } }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Whistle.colors.sage)
                    }
                    TextButton(
                        onClick = { vm.dismissCommit(); committing = true },
                        enabled = s.path != null,
                        modifier = Modifier.testTag("commitButton"),
                    ) { Text("Commit", color = if (s.path != null) Whistle.colors.wool else Whistle.colors.sage) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).consumeWindowInsets(pad).windowInsetsPadding(WindowInsets.navigationBars).fillMaxSize()) {
            ConnBanner(conn)
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Whistle.colors.sky, trackColor = Whistle.colors.card)
            if (s.file == null) {
                Box(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { picker = true }, enabled = s.paths.isNotEmpty(), modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            (s.path ?: "No path") + if (s.paths.size > 1) "  ▾" else "",
                            style = WhistleType.mono.copy(fontSize = 13.sp),
                            color = Whistle.colors.wool,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        s.paths.forEach { p ->
                            DropdownMenuItem(text = { Text(p, fontFamily = CommitMono) }, onClick = {
                                picker = false
                                scope.launch { vm.selectPath(p) }
                            })
                        }
                    }
                }

            }
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("changesError"))
            }
            val done = s.commit as? CommitState.Done
            if (done != null && !committing) {
                Text("Committed ${done.sha.take(10)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
            }
            if (s.file != null) {
                Column(Modifier.fillMaxSize()) {
                    if (s.diff.isEmpty() && !s.loading && s.error == null) Text("No textual diff.", Modifier.padding(16.dp))
                    DiffListView(
                        s.diffLines,
                        modifier = Modifier.weight(1f),
                        onLongClickLine = { i -> s.diff.getOrNull(i)?.takeIf { it.refNo != null }?.let { asking = it } },
                    )
                    if (s.truncated) Text("Diff truncated.", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    if (s.diff.isNotEmpty()) {
                        Text("Long-press a line to ask an agent about it.", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (s.files.isEmpty() && !s.loading && s.error == null && s.path != null) {
                EmptyHint("Working tree clean.")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(s.files, key = { it.path + it.staged }) { f -> FileCard(f) { scope.launch { vm.openFile(f.path) } } }
                }
            }
        }
    }

    val stepUp = LocalStepUp.current
    if (committing) CommitDialog(s.commit, s.files, onCommit = { m -> stepUp.require("Commit changes") { scope.launch { vm.commit(m) } } }, onClose = { committing = false; vm.dismissCommit() })

    asking?.let { line ->
        AskAgentDialog(
            agents = agentPanes,
            sameWorkspace = workspaceId,
            onPick = { pane ->
                asking = null
                if (vm.askAgent(s.file.orEmpty(), line, pane.id)) onOpenPane(pane)
            },
            onDismiss = { asking = null },
        )
    }
}

/** One changed file: status letter in its color, file name, directory and "staged" in sage. */
@Composable
private fun FileCard(f: GitFile, onClick: () -> Unit) {
    val c = Whistle.colors
    WhistleCard(onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.status,
                style = WhistleType.mono.copy(fontSize = 13.sp),
                color = gitStatusColor(f.status),
                modifier = Modifier.widthIn(min = 28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(f.path.substringAfterLast('/'), style = WhistleType.bodyStrong, color = c.wool, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dir = f.path.substringBeforeLast('/', "")
                val sub = listOfNotNull(dir.ifEmpty { null }, if (f.staged) "staged" else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = WhistleType.secondary, color = c.sage, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun gitStatusColor(status: String) = Whistle.colors.let { c ->
    when (status.trim().firstOrNull()) {
        'A', '?' -> c.diffAddFg
        'D' -> c.ember
        else -> c.sky
    }
}

@Composable
private fun CommitDialog(state: CommitState, files: List<GitFile>, onCommit: (String) -> Unit, onClose: () -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    val done = state as? CommitState.Done
    AlertDialog(
        onDismissRequest = { if (state != CommitState.Busy) onClose() },
        title = { Text(if (done != null) "Committed" else commitHeading(files)) },
        text = {
            if (done != null) {
                Text(done.sha, fontFamily = CommitMono, modifier = Modifier.testTag("commitSha"))
            } else {
                Column {
                    LazyColumn(Modifier.heightIn(max = 160.dp).testTag("commitFiles")) {
                        items(files, key = { it.path + it.staged }) { f ->
                            Row {
                                Text(f.status, style = WhistleType.mono, color = gitStatusColor(f.status))
                                Text(f.path, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        message, { message = it },
                        label = { Text("Message") },
                        minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("commitMessage"),
                    )
                    (state as? CommitState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("commitError"))
                    }
                }
            }
        },
        confirmButton = {
            if (done != null) TextButton(onClick = onClose) { Text("Close") }
            else TextButton(enabled = message.isNotBlank() && files.isNotEmpty() && state != CommitState.Busy, onClick = { onCommit(message) }) { Text("Commit") }
        },
        dismissButton = if (done == null) ({ TextButton(onClick = onClose, enabled = state != CommitState.Busy) { Text("Cancel") } }) else null,
    )
}

@Composable
private fun AskAgentDialog(agents: List<PaneUi>, sameWorkspace: String, onPick: (PaneUi) -> Unit, onDismiss: () -> Unit) {
    val choices = remember(agents) { agents.filter { it.isAgent }.sortedBy { it.workspaceId != sameWorkspace } }
    var chosen by remember(choices) { mutableStateOf(choices.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask which agent?") },
        text = {
            if (choices.isEmpty()) {
                Text("No agent panes on this machine.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    choices.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = p.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == p.id, onClick = { chosen = p.id })
                            Text(
                                "${p.label.ifBlank { p.agent ?: p.id }} · ${p.agent ?: "agent"}" +
                                    if (p.workspaceId == sameWorkspace) " · this workspace" else "",
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen != null, onClick = { choices.firstOrNull { it.id == chosen }?.let(onPick) }) { Text("Ask") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
