package dev.whistle.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.components.ShellMark
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.ui.theme.statusPhrase
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.PaneKind

/**
 * What a pane page shows below its header. Swapped per kind by later tasks (chat for agent panes,
 * terminal for shell panes); the app passes it down from [HerdrNavHost].
 */
typealias PaneContent = @Composable (machineId: String, pane: PaneUi) -> Unit

val DefaultPaneContent: PaneContent = { _, pane -> PanePlaceholder(pane) }

const val PANE_HEADER_TAG = "paneHeader"

enum class PaneAction { SplitRight, SplitDown, Rename, StartAgent, Close, OpenTerminal }

fun PaneUi.title(): String = label.ifBlank { agent ?: cwd.trimEnd('/').substringAfterLast('/').ifBlank { id } }

val PaneUi.isAgent: Boolean get() = kind == PaneKind.AGENT

/** One full-screen pane page: header (orb, name, status line, actions) over the kind's content. */
@Composable
fun PaneHost(
    machineId: String,
    pane: PaneUi,
    position: String,
    onAction: (PaneAction) -> Unit,
    content: PaneContent,
    modifier: Modifier = Modifier,
    canOpenTerminal: Boolean = false,
    /** Keyboard up: the header folds away (the workspace shows a compact bar instead). */
    compact: Boolean = false,
) {
    Column(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !compact,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) {
            PaneHeader(pane, position, onAction, canOpenTerminal)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content(machineId, pane) }
    }
}

@Composable
private fun PaneHeader(pane: PaneUi, position: String, onAction: (PaneAction) -> Unit, canOpenTerminal: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val c = Whistle.colors
    Row(
        Modifier.fillMaxWidth().testTag(PANE_HEADER_TAG).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pane.isAgent) AgentOrb(pane.agent, pane.agentStatus, size = 32.dp) else ShellMark(size = 32.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pane.title(), style = WhistleType.name, color = c.wool, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = pane.agent ?: "agent"
            val sub = when {
                !pane.isAgent -> "shell · ${pane.cwd}"
                pane.title() == kind -> statusPhrase(pane.agentStatus)
                else -> "$kind · ${statusPhrase(pane.agentStatus)}"
            }
            Text(
                sub,
                style = WhistleType.secondary,
                color = if (pane.isAgent && pane.agentStatus == AgentStatus.BLOCKED) c.lamp else c.sage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(position, style = WhistleType.small, color = c.sage)
        if (canOpenTerminal) {
            // Toggle to the raw terminal of this pane (chat ⇄ terminal).
            IconButton(onClick = { onAction(PaneAction.OpenTerminal) }) {
                Text(
                    ">_",
                    style = WhistleType.mono.copy(fontSize = 13.sp),
                    color = c.sage,
                    modifier = Modifier.semantics { contentDescription = "Terminal" },
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Pane actions", tint = c.sage) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                PaneMenuEntries(pane, canOpenTerminal = false, dismiss = { menu = false }, onAction = onAction)
            }
        }
    }
}

/** A pane's actions as menu items ([canOpenTerminal]: also "Terminal", for menus without the >_ button). */
@Composable
fun PaneMenuEntries(pane: PaneUi, canOpenTerminal: Boolean, dismiss: () -> Unit, onAction: (PaneAction) -> Unit) {
    if (canOpenTerminal) PaneMenuItem("Terminal") { dismiss(); onAction(PaneAction.OpenTerminal) }
    PaneMenuItem("Split right") { dismiss(); onAction(PaneAction.SplitRight) }
    PaneMenuItem("Split down") { dismiss(); onAction(PaneAction.SplitDown) }
    PaneMenuItem("Rename") { dismiss(); onAction(PaneAction.Rename) }
    if (!pane.isAgent) PaneMenuItem("Start agent…") { dismiss(); onAction(PaneAction.StartAgent) }
    PaneMenuItem("Close pane", danger = true) { dismiss(); onAction(PaneAction.Close) }
}

@Composable
private fun PaneMenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        onClick = onClick,
    )
}

/** Stand-in until the chat (agent) and terminal (shell) pages exist. */
@Composable
fun PanePlaceholder(pane: PaneUi) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            if (pane.isAgent) "Chat with ${pane.agent ?: "the agent"} comes here." else "Terminal for ${pane.cwd} comes here.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
