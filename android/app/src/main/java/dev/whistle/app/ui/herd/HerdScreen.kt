package dev.whistle.app.ui.herd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.Machine
import dev.whistle.app.ui.ConfirmDialog
import dev.whistle.app.ui.AuthBanner
import dev.whistle.app.ui.ConnBanner
import dev.whistle.app.ui.EmptyHint
import dev.whistle.app.ui.HerdrActions
import dev.whistle.app.ui.TextFieldsDialog
import dev.whistle.app.ui.broadcast.BroadcastSheet
import dev.whistle.app.ui.broadcast.Broadcaster
import dev.whistle.app.ui.broadcast.broadcastTargets
import dev.whistle.app.ui.chat.APPROVE_KEYS
import dev.whistle.app.ui.chat.ApprovalController
import dev.whistle.app.ui.chat.ApprovalUi
import dev.whistle.app.ui.chat.CANCEL_KEYS
import dev.whistle.app.ui.chat.approvalPrompt
import dev.whistle.app.ui.chat.ApprovalAnswers
import dev.whistle.app.ui.chat.ApprovalPromptInset
import dev.whistle.app.ui.chat.herdAnswers
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.components.LampCard
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.components.SectionLabel
import dev.whistle.app.ui.components.ShellMark
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.rememberActionRunner
import dev.whistle.app.ui.snippets.SnippetRepository
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.ui.title

const val HERD_LIST_TAG = "herdList"

private sealed interface WsDialog {
    data object Create : WsDialog
    data class Rename(val ws: WorkspaceUi) : WsDialog
    data class Close(val ws: WorkspaceUi) : WsDialog
}

/** What the herd screen can do; one object so the stateless [HerdContent] stays readable. */
class HerdCallbacks(
    val onOpenPane: (PaneUi) -> Unit = {},
    val onOpenWorkspace: (workspaceId: String) -> Unit = {},
    val onOpenChanges: (workspaceId: String, cwd: String?) -> Unit = { _, _ -> },
    val onRenameWorkspace: (WorkspaceUi) -> Unit = {},
    val onCloseWorkspace: (WorkspaceUi) -> Unit = {},
    val onNewWorkspace: () -> Unit = {},
    val onBroadcast: (() -> Unit)? = null,
    val onOpenSnippets: () -> Unit = {},
    val onOpenMachines: () -> Unit = {},
    val onOpenSecurity: () -> Unit = {},
    val onKeys: (paneId: String, keys: List<String>) -> Unit = { _, _ -> },
    val onRefresh: (paneId: String) -> Unit = {},
)

/**
 * The start screen of a machine: who needs you (pinned, answerable inline), then the agents by
 * workspace, then each workspace's shells. Cached state first; the sync stream keeps it live.
 */
@Composable
fun HerdScreen(
    repo: HerdrRepository,
    machine: Machine,
    onOpenPane: (PaneUi) -> Unit,
    onOpenWorkspace: (workspaceId: String) -> Unit,
    onOpenMachines: () -> Unit,
    onOpenChanges: (workspaceId: String, cwd: String?) -> Unit = { _, _ -> },
    broadcaster: Broadcaster? = null,
    snippets: SnippetRepository? = null,
    onOpenSnippets: () -> Unit = {},
    onOpenSecurity: () -> Unit = {},
    notice: @Composable () -> Unit = {},
) {
    val source by remember(machine.id) { repo.herdSource(machine.id) }.collectAsStateWithLifecycle(null)
    val herd = remember(source, machine.name) { source?.let { (wss, panes) -> buildHerd(machine.name, wss, panes) } }
    val conn by remember(machine.id) { repo.connState(machine.id) }.collectAsStateWithLifecycle()
    val authProblem by remember(machine.id) { repo.authProblem(machine.id) }.collectAsStateWithLifecycle()
    val actions = remember(machine.id) { HerdrActions(repo, machine.id) }
    val snackbar = remember { SnackbarHostState() }
    val runner = rememberActionRunner(snackbar)
    var dialog by remember { mutableStateOf<WsDialog?>(null) }
    var broadcast by remember { mutableStateOf(false) }
    val agentPanes by remember(machine.id) { repo.agentPanes(machine.id) }.collectAsStateWithLifecycle(emptyList())

    // One approval controller per needs-you agent; dropped when it stops needing you.
    val scope = rememberCoroutineScope()
    val latestPanes by rememberUpdatedState(source?.second.orEmpty())
    val controllers = remember(machine.id) { mutableMapOf<String, ApprovalController>() }
    val blockedIds = herd?.needsYou?.map { it.pane.id }.orEmpty()
    val live = blockedIds.associateWith { id ->
        controllers.getOrPut(id) {
            ApprovalController(scope, id, { m, p -> repo.cmd(machine.id, m, p) }) {
                latestPanes.firstOrNull { it.id == id }?.agentStatus == AgentStatus.BLOCKED
            }
        }
    }
    SideEffect { (controllers.keys - blockedIds.toSet()).forEach { controllers.remove(it)?.dismiss() } }
    val approvals = HashMap<String, ApprovalUi?>()
    for ((id, c) in live) key(id) {
        LaunchedEffect(c) { c.fetch() }
        approvals[id] = c.state.collectAsStateWithLifecycle().value
    }

    HerdContent(
        machineName = machine.name,
        conn = conn,
        authProblem = authProblem?.message(machine.name),
        herd = herd,
        approvals = approvals,
        snackbar = snackbar,
        notice = notice,
        callbacks = HerdCallbacks(
            onOpenPane = onOpenPane,
            onOpenWorkspace = onOpenWorkspace,
            onOpenChanges = onOpenChanges,
            onRenameWorkspace = { dialog = WsDialog.Rename(it) },
            onCloseWorkspace = { dialog = WsDialog.Close(it) },
            onNewWorkspace = { dialog = WsDialog.Create },
            onBroadcast = if (broadcaster != null) ({ broadcast = true }) else null,
            onOpenSnippets = onOpenSnippets,
            onOpenMachines = onOpenMachines,
            onOpenSecurity = onOpenSecurity,
            onKeys = { id, keys -> controllers[id]?.sendKeys(keys) },
            onRefresh = { id -> controllers[id]?.fetch() },
        ),
    )

    if (broadcast && broadcaster != null) {
        BroadcastSheet(
            machineId = machine.id,
            targets = broadcastTargets(source?.first.orEmpty(), agentPanes),
            broadcaster = broadcaster,
            snippets = snippets,
            onOpenSnippets = { broadcast = false; onOpenSnippets() },
            onDismiss = { broadcast = false },
        )
    }

    when (val d = dialog) {
        null -> Unit
        WsDialog.Create -> TextFieldsDialog(
            title = "New workspace",
            fields = listOf("Working directory (empty: herdr default)" to "", "Label (optional)" to ""),
            confirm = "Create",
            onDone = { (cwd, label) -> runner.run("Create workspace") { actions.createWorkspace(cwd, label) } },
            onDismiss = { dialog = null },
        )
        is WsDialog.Rename -> TextFieldsDialog(
            title = "Rename workspace",
            fields = listOf("Label" to d.ws.label),
            confirm = "Rename",
            requireFirst = true,
            onDone = { (label) -> runner.run("Rename") { actions.renameWorkspace(d.ws.id, label) } },
            onDismiss = { dialog = null },
        )
        is WsDialog.Close -> ConfirmDialog(
            title = "Close workspace?",
            text = "“${d.ws.title()}” and all its tabs and panes will be closed on ${machine.name}. Running agents are stopped.",
            confirm = "Close",
            onConfirm = { runner.run("Close workspace") { actions.closeWorkspace(d.ws.id) } },
            onDismiss = { dialog = null },
        )
    }
}

/** Stateless herd screen (screenshots and tests render it directly). */
@Composable
fun HerdContent(
    machineName: String,
    conn: ConnState,
    herd: Herd?,
    approvals: Map<String, ApprovalUi?>,
    callbacks: HerdCallbacks,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    authProblem: String? = null,
    notice: @Composable () -> Unit = {},
) {
    val c = Whistle.colors
    Scaffold(
        containerColor = c.night,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            HerdHeader(herd?.subline ?: machineName, callbacks)
            ConnBanner(conn)
            if (authProblem != null) AuthBanner(authProblem, onClick = callbacks.onOpenMachines)
            notice()
            when {
                herd == null -> Unit // first DB read in flight (a few ms)
                herd.isEmpty -> EmptyHint(
                    if (conn == ConnState.Synced) "No workspaces yet. Start one from the menu above." else "Nothing cached yet.",
                )
                else -> HerdList(herd, approvals, callbacks)
            }
        }
    }
}

@Composable
private fun HerdHeader(subline: String, cb: HerdCallbacks) {
    val c = Whistle.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 6.dp)) {
            Text("Your herd", style = WhistleType.title, color = c.wool)
            Spacer(Modifier.height(4.dp))
            Text(subline, style = WhistleType.secondary, color = c.sage, modifier = Modifier.testTag("herdSubline"))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("herdOverflow")) {
                Icon(Icons.Default.MoreVert, "More", tint = c.sage)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New workspace…") }, onClick = { menu = false; cb.onNewWorkspace() })
                cb.onBroadcast?.let { b ->
                    DropdownMenuItem(text = { Text("Broadcast prompt…") }, onClick = { menu = false; b() })
                }
                DropdownMenuItem(text = { Text("Snippets") }, onClick = { menu = false; cb.onOpenSnippets() })
                DropdownMenuItem(text = { Text("Machines") }, onClick = { menu = false; cb.onOpenMachines() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; cb.onOpenSecurity() })
            }
        }
    }
}

@Composable
private fun HerdList(herd: Herd, approvals: Map<String, ApprovalUi?>, cb: HerdCallbacks) {
    val nav = WindowInsets.navigationBars.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize().testTag(HERD_LIST_TAG),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = nav.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (herd.needsYou.isNotEmpty()) {
            item(key = "needs") { SectionLabel("Needs you", Modifier.padding(start = 6.dp, top = 4.dp)) }
            items(herd.needsYou, key = { "n:" + it.pane.id }) { a ->
                NeedsYouCard(a, approvals[a.pane.id], cb)
            }
        }
        for (g in herd.groups) {
            item(key = "ws:" + g.workspace.id) { WorkspaceHeading(g, cb) }
            items(g.agents, key = { "a:" + it.pane.id }) { a -> AgentCard(a) { cb.onOpenPane(a.pane) } }
            items(g.shells, key = { "s:" + it.id }) { p -> ShellRow(p) { cb.onOpenPane(p) } }
            if (g.agents.isEmpty() && g.shells.isEmpty()) {
                item(key = "empty:" + g.workspace.id) {
                    Text(
                        "No panes",
                        style = WhistleType.secondary,
                        color = Whistle.colors.sage,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeading(g: HerdGroup, cb: HerdCallbacks) {
    var menu by remember { mutableStateOf(false) }
    val ws = g.workspace
    SectionLabel(
        ws.title(),
        Modifier
            .padding(top = 10.dp)
            .clickable { cb.onOpenWorkspace(ws.id) }
            .padding(start = 6.dp)
            .testTag("wsHeading:${ws.id}"),
    ) {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, "Workspace actions", tint = Whistle.colors.sage, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open workspace") }, onClick = { menu = false; cb.onOpenWorkspace(ws.id) })
            DropdownMenuItem(text = { Text("Changes") }, onClick = {
                menu = false
                cb.onOpenChanges(ws.id, (g.agents.map { it.pane } + g.shells).firstOrNull()?.cwd)
            })
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; cb.onRenameWorkspace(ws) })
            DropdownMenuItem(
                text = { Text("Close workspace", color = Whistle.colors.ember) },
                onClick = { menu = false; cb.onCloseWorkspace(ws) },
            )
        }
    }
}

@Composable
private fun AgentCard(a: HerdAgent, onClick: () -> Unit) {
    val c = Whistle.colors
    WhistleCard(Modifier.testTag("agent:${a.pane.id}"), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentOrb(a.pane.agent, a.pane.agentStatus, Modifier.testTag("orb:${a.pane.id}"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = WhistleType.name, color = c.wool, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(a.subline, style = WhistleType.secondary, color = c.sage, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Pinned card of an agent that is waiting for you: its question and the answers, right here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedsYouCard(a: HerdAgent, approval: ApprovalUi?, cb: HerdCallbacks) {
    val c = Whistle.colors
    val id = a.pane.id
    LampCard(Modifier.testTag("needsYou:$id"), onClick = { cb.onOpenPane(a.pane) }) {
        Row(verticalAlignment = Alignment.Top) {
            AgentOrb(a.pane.agent, a.pane.agentStatus, Modifier.testTag("orb:$id"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = WhistleType.name, color = c.wool, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(a.subline, style = WhistleType.secondary, color = c.sage, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val prompt = remember(approval?.text) { approval?.text?.let { approvalPrompt(it) }.orEmpty() }
                Spacer(Modifier.height(8.dp))
                when {
                    approval == null || approval.loading ->
                        Text("Reading the screen…", style = WhistleType.secondary, color = c.sage)
                    prompt.isNotBlank() -> ApprovalPromptInset(prompt)
                    else -> Text("Waiting for your answer.", style = WhistleType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.wool)
                }
                approval?.error?.let {
                    Text(it, style = WhistleType.secondary, color = c.ember, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                val enabled = approval != null && !approval.loading && !approval.sending
                val opts = approval?.options.orEmpty()
                if (opts.isEmpty() && approval?.error != null) {
                    PillButton("Retry", onClick = { cb.onRefresh(id) }, style = PillStyle.Outline)
                } else {
                    ApprovalAnswers(
                        herdAnswers(opts),
                        prompt = prompt,
                        enabled = enabled,
                        onKeys = { keys -> cb.onKeys(id, keys) },
                        labelMaxWidth = 220.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellRow(p: PaneUi, onClick: () -> Unit) {
    val c = Whistle.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("shell:${p.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellMark()
        Spacer(Modifier.width(12.dp))
        Text(p.title(), style = WhistleType.secondaryStrong, color = c.wool, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(
            p.cwd,
            style = WhistleType.secondary,
            color = c.sage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
