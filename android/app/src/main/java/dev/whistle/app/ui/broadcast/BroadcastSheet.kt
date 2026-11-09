package dev.whistle.app.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.ui.theme.statusPhrase
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.ui.chat.Pending
import dev.whistle.app.ui.chat.PendingState
import dev.whistle.app.ui.chat.PromptOutbox
import dev.whistle.app.ui.snippets.SnippetPickerButton
import dev.whistle.app.ui.snippets.SnippetRepository
import dev.whistle.app.ui.title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dev.whistle.app.security.LocalStepUp

/** Outcome of one broadcast target, read from the [PromptOutbox] (same state as a normal send). */
sealed interface TargetResult {
    data object Sending : TargetResult

    /** Delivered (accepted by the agent, or already in its transcript). */
    data object Ok : TargetResult

    /** Timed out: may or may not have arrived. Never retried automatically. */
    data object Unconfirmed : TargetResult

    data class Failed(val code: String, val message: String) : TargetResult
}

/**
 * Sends one prompt to many panes of a machine through the shared [PromptOutbox], so every
 * target has the same pending/outcome state as a prompt typed in its chat (and it survives
 * navigation). Nothing is retried here.
 */
class Broadcaster(private val outbox: PromptOutbox) {
    /** pane id -> the outbox id of the last broadcast prompt to it, per machine. */
    private val sent = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()

    /** One outbox send per target (each runs concurrently in the outbox's scope). */
    fun send(machineId: String, paneIds: Collection<String>, text: String) {
        val ids = LinkedHashMap<String, Long>()
        for (p in paneIds.distinct()) outbox.send(machineId, p, text)?.let { ids[p] = it }
        sent[machineId] = ids
    }

    /** Live per-target results of the last [send] on [machineId]; empty before any send. */
    fun results(machineId: String): Flow<Map<String, TargetResult>> {
        val ids = sent[machineId].orEmpty()
        if (ids.isEmpty()) return flowOf(emptyMap())
        val flows = ids.map { (pane, id) -> outbox.pending(machineId, pane).map { pane to resultOf(it, id) } }
        return combine(flows) { it.toMap() }
    }

    private fun resultOf(list: List<Pending>, id: Long): TargetResult =
        when (val st = list.firstOrNull { it.id == id }?.state) {
            null -> TargetResult.Ok // matched in the transcript (or dismissed): it was delivered
            PendingState.Sending -> TargetResult.Sending
            PendingState.Sent, PendingState.NotSeen -> TargetResult.Ok
            PendingState.Unconfirmed -> TargetResult.Unconfirmed
            is PendingState.Failed -> TargetResult.Failed(st.code, st.message)
        }
}

/** Agent panes with the workspace title to group them by. */
data class BroadcastTarget(val pane: PaneUi, val workspace: String)

fun broadcastTargets(workspaces: List<WorkspaceUi>, agentPanes: List<PaneUi>): List<BroadcastTarget> {
    val names = workspaces.associate { it.id to it.title() }
    return agentPanes.filter { it.kind == PaneKind.AGENT }.map { BroadcastTarget(it, names[it.workspaceId] ?: it.workspaceId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastSheet(
    machineId: String,
    targets: List<BroadcastTarget>,
    broadcaster: Broadcaster,
    snippets: SnippetRepository?,
    onOpenSnippets: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var text by rememberSaveable { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val stepUp = LocalStepUp.current
    val results by remember(started) { if (started) broadcaster.results(machineId) else flowOf(emptyMap()) }
        .collectAsStateWithLifecycle(emptyMap())

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Whistle.colors.card, shape = WhistleShapes.Sheet) {
        Column(Modifier.padding(horizontal = 16.dp).testTag("broadcastSheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Whistle to the herd", style = WhistleType.heading, color = Whistle.colors.wool)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Prompt") },
                enabled = !started,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("broadcastText"),
            )
            if (snippets != null && !started) {
                SnippetPickerButton(snippets, onPick = { text = if (text.isBlank()) it else text.trimEnd() + "\n" + it }, onManage = onOpenSnippets)
            }
            if (targets.isEmpty()) Text("No agent panes on this machine.", color = Whistle.colors.sage)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(targets, key = { it.pane.id }) { t ->
                    val id = t.pane.id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id in selected,
                            enabled = !started,
                            onCheckedChange = { c -> selected = if (c) selected + id else selected - id },
                        )
                        AgentOrb(t.pane.agent, t.pane.agentStatus, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${t.workspace} · ${t.pane.label.ifBlank { t.pane.agent ?: id }}", style = WhistleType.bodyStrong, maxLines = 1)
                            Text(
                                "${t.pane.agent ?: "agent"} · ${statusPhrase(t.pane.agentStatus)}",
                                style = WhistleType.secondary,
                                color = Whistle.colors.sage,
                            )
                        }
                        results[id]?.let { ResultLabel(id, it) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(if (started) "Close" else "Cancel") }
                if (!started) {
                    TextButton(
                        enabled = text.isNotBlank() && selected.isNotEmpty(),
                        onClick = { stepUp.require("Broadcast to ${selected.size} agents") { broadcaster.send(machineId, selected, text); started = true } },
                        modifier = Modifier.testTag("broadcastSend"),
                    ) { Text("Send to ${selected.size}") }
                }
            }
            Text("", Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ResultLabel(paneId: String, r: TargetResult) {
    val (label, color) = when (r) {
        TargetResult.Sending -> "sending…" to Whistle.colors.sage
        TargetResult.Ok -> "ok" to Whistle.colors.diffAddFg
        TargetResult.Unconfirmed -> "unconfirmed" to Whistle.colors.sky
        is TargetResult.Failed -> r.code to Whistle.colors.ember
    }
    Text(label, color = color, style = WhistleType.secondaryStrong, modifier = Modifier.testTag("result:$paneId"))
}
