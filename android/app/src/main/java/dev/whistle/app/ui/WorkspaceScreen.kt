package dev.whistle.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.components.ShellMark
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.TabUi
import dev.whistle.app.protocol.AgentStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.whistle.app.security.LocalStepUp

const val PANE_PAGER_TAG = "panePager"
const val TAB_STRIP_TAG = "tabStrip"
const val COMPACT_BAR_TAG = "compactBar"

fun TabUi.title(): String = label.ifBlank { "Tab $number" }

/**
 * A tab pill's text: the number plus a short label. A leading "N ·" in the label is not repeated,
 * and a label the pane header already shows ([paneTitle], selected tab only) is left out.
 */
fun tabPillLabel(tab: TabUi, paneTitle: String?): String {
    val short = tab.label.trim().replaceFirst(Regex("^${tab.number}\\s*[·:.\\-]\\s*"), "").trim()
    val p = paneTitle?.trim().orEmpty()
    val dup = p.isNotEmpty() && (short.equals(p, ignoreCase = true) || short.startsWith(p, ignoreCase = true) || p.startsWith(short, ignoreCase = true))
    return if (short.isEmpty() || dup) "${tab.number}" else "${tab.number} · $short"
}

/** The keyboard-open chrome: back, orb, agent name, status dot, overflow, in 48 dp. */
@Composable
private fun CompactPaneBar(pane: PaneUi?, fallbackTitle: String, onBack: () -> Unit, menu: @Composable (dismiss: () -> Unit) -> Unit) {
    val c = Whistle.colors
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(c.night).statusBarsPadding().height(48.dp).padding(end = 4.dp).testTag(COMPACT_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.wool) }
        if (pane != null) {
            if (pane.isAgent) AgentOrb(pane.agent, pane.agentStatus, size = 24.dp) else ShellMark(size = 24.dp)
            Spacer(Modifier.width(10.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pane?.title() ?: fallbackTitle,
                style = WhistleType.name,
                color = c.wool,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pane != null && pane.isAgent) {
                Spacer(Modifier.width(8.dp))
                StatusDot(pane.agentStatus, size = 8.dp)
            }
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More", tint = c.sage) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

private sealed interface Dlg {
    data class NewTab(val cwd: String) : Dlg
    data class RenameTab(val tab: TabUi) : Dlg
    data class CloseTab(val tab: TabUi) : Dlg
    data class RenamePane(val pane: PaneUi) : Dlg
    data class StartAgent(val pane: PaneUi) : Dlg
    data class ClosePane(val pane: PaneUi) : Dlg
    data class RenameWorkspace(val ws: WorkspaceUi) : Dlg
    data class CloseWorkspace(val ws: WorkspaceUi) : Dlg
}

/** One workspace: scrollable tab strip, and the selected tab's panes as swipeable full-screen pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    repo: HerdrRepository,
    machineId: String,
    workspaceId: String,
    onBack: () -> Unit,
    paneContent: PaneContent = DefaultPaneContent,
    onOpenTerminal: ((paneId: String) -> Unit)? = null,
    /** Opens git Changes of this workspace; gets the current pane's cwd (the default path). */
    onOpenChanges: ((cwd: String?) -> Unit)? = null,
    initialTabId: String? = null,
    initialPaneId: String? = null,
) {
    // Outer null = first DB read pending; inner null = the workspace is gone.
    val found by remember(machineId, workspaceId) {
        repo.workspaces(machineId).map { l -> Found(l.firstOrNull { it.id == workspaceId }) }
    }.collectAsStateWithLifecycle(null)
    val tabs by remember(machineId, workspaceId) { repo.tabs(machineId, workspaceId) }.collectAsStateWithLifecycle(emptyList())
    val conn by remember(machineId) { repo.connState(machineId) }.collectAsStateWithLifecycle()
    val actions = remember(machineId) { HerdrActions(repo, machineId) }
    val snackbar = remember { SnackbarHostState() }
    val runner = rememberActionRunner(snackbar)
    val stepUp = LocalStepUp.current
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var selectedTabId by rememberSaveable(workspaceId) { mutableStateOf<String?>(initialTabId) }
    // A pane to bring on screen once it exists (e.g. the result of a split).
    var revealPaneId by remember { mutableStateOf<String?>(initialPaneId) }
    var currentPane by remember { mutableStateOf<PaneUi?>(null) }

    val ws = found?.value
    if (found != null && ws == null) LaunchedEffect(Unit) { onBack() } // closed (here or on the desktop)

    val tab = tabs.firstOrNull { it.id == selectedTabId }
        ?: tabs.firstOrNull { it.id == ws?.activeTabId }
        ?: tabs.firstOrNull()

    val compact = imeVisible()
    val onPaneAction: (PaneUi, PaneAction) -> Unit = { pane, action ->
        when (action) {
            PaneAction.SplitRight, PaneAction.SplitDown -> runner.run("Split") {
                val dir = if (action == PaneAction.SplitRight) "right" else "down"
                revealPaneId = actions.splitPane(pane.id, dir)
            }
            PaneAction.Rename -> dialog = Dlg.RenamePane(pane)
            PaneAction.StartAgent -> dialog = Dlg.StartAgent(pane)
            PaneAction.Close -> dialog = Dlg.ClosePane(pane)
            PaneAction.OpenTerminal -> onOpenTerminal?.invoke(pane.id)
        }
    }
    @Composable
    fun WorkspaceMenuEntries(dismiss: () -> Unit) {
        if (onOpenChanges != null) {
            DropdownMenuItem(text = { Text("Changes") }, onClick = { dismiss(); onOpenChanges(currentPane?.cwd) })
        }
        DropdownMenuItem(text = { Text("New tab…") }, onClick = { dismiss(); dialog = Dlg.NewTab(currentPane?.cwd.orEmpty()) })
        if (ws != null) {
            DropdownMenuItem(text = { Text("Rename workspace…") }, onClick = { dismiss(); dialog = Dlg.RenameWorkspace(ws) })
            DropdownMenuItem(
                text = { Text("Close workspace", color = Whistle.colors.ember) },
                onClick = { dismiss(); dialog = Dlg.CloseWorkspace(ws) },
            )
        }
    }

    Scaffold(
        containerColor = Whistle.colors.night,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // Keyboard up: title, tab pills and pane header give way to one 48 dp bar.
            AnimatedContent(
                targetState = compact,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) using SizeTransform(clip = true) { _, _ -> tween(180) } },
                label = "workspaceChrome",
            ) { isCompact ->
                if (isCompact) {
                    CompactPaneBar(currentPane, ws?.title() ?: "", onBack) { dismiss ->
                        currentPane?.let { p -> PaneMenuEntries(p, canOpenTerminal = onOpenTerminal != null && p.isAgent, dismiss) { onPaneAction(p, it) } }
                        WorkspaceMenuEntries(dismiss)
                    }
                } else {
                    WhistleTopBar(
                        title = ws?.title() ?: "",
                        onBack = onBack,
                        actions = {
                            var overflow by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { overflow = true }, modifier = Modifier.testTag("workspaceOverflow")) {
                                    Icon(Icons.Default.MoreVert, "More", tint = Whistle.colors.sage)
                                }
                                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    WorkspaceMenuEntries { overflow = false }
                                }
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        // Nav bar insets are consumed here, so a page's imePadding() adds only what the keyboard covers beyond them.
        Column(Modifier.padding(pad).consumeWindowInsets(pad).windowInsetsPadding(WindowInsets.navigationBars).fillMaxSize()) {
            ConnBanner(conn)
            // One tab: the pills only repeat the pane header ("New tab…" stays in the overflow).
            AnimatedVisibility(
                visible = !compact && tabs.size >= 2,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
            ) {
                TabStrip(
                    tabs = tabs,
                    selected = tab,
                    selectedPaneTitle = currentPane?.title(),
                    onSelect = { selectedTabId = it.id },
                    onRename = { dialog = Dlg.RenameTab(it) },
                    onClose = { dialog = Dlg.CloseTab(it) },
                    onAdd = { dialog = Dlg.NewTab(currentPane?.cwd.orEmpty()) },
                )
            }
            if (tab == null) {
                if (found != null) EmptyHint("No tabs. Tap + to open one.")
            } else {
                key(tab.id) {
                    TabPanes(
                        repo, machineId, tab.id, revealPaneId,
                        onRevealed = { revealPaneId = null },
                        onCurrent = { currentPane = it },
                        onAction = onPaneAction,
                        compact = compact,
                        paneContent = paneContent,
                        canOpenTerminal = onOpenTerminal != null,
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is Dlg.NewTab -> TextFieldsDialog(
            title = "New tab",
            fields = listOf("Label (optional)" to "", "Working directory (optional)" to d.cwd),
            confirm = "Create",
            onDone = { (label, cwd) ->
                runner.run("New tab") { actions.createTab(workspaceId, cwd, label)?.let { selectedTabId = it } }
            },
            onDismiss = { dialog = null },
        )
        is Dlg.RenameTab -> TextFieldsDialog(
            title = "Rename tab",
            fields = listOf("Label" to d.tab.label),
            confirm = "Rename",
            requireFirst = true,
            onDone = { (label) -> runner.run("Rename") { actions.renameTab(d.tab.id, label) } },
            onDismiss = { dialog = null },
        )
        is Dlg.CloseTab -> ConfirmDialog(
            title = "Close tab?",
            text = "“${d.tab.title()}” and all its panes will be closed. Running agents are stopped.",
            confirm = "Close",
            onConfirm = { runner.run("Close tab") { actions.closeTab(d.tab.id) } },
            onDismiss = { dialog = null },
        )
        is Dlg.RenamePane -> TextFieldsDialog(
            title = "Rename pane",
            fields = listOf("Label" to d.pane.label),
            confirm = "Rename",
            onDone = { (label) -> runner.run("Rename") { actions.renamePane(d.pane.id, label) } },
            onDismiss = { dialog = null },
        )
        is Dlg.StartAgent -> StartAgentDialog(
            onStart = { kind, name -> stepUp.require("Start $kind") { runner.run("Start $kind") { actions.startAgent(d.pane.id, kind, name) } } },
            onDismiss = { dialog = null },
        )
        is Dlg.RenameWorkspace -> TextFieldsDialog(
            title = "Rename workspace",
            fields = listOf("Label" to d.ws.label),
            confirm = "Rename",
            requireFirst = true,
            onDone = { (label) -> runner.run("Rename") { actions.renameWorkspace(d.ws.id, label) } },
            onDismiss = { dialog = null },
        )
        is Dlg.CloseWorkspace -> ConfirmDialog(
            title = "Close workspace?",
            text = "“${d.ws.title()}” and all its tabs and panes will be closed. Running agents are stopped.",
            confirm = "Close",
            onConfirm = { runner.run("Close workspace") { actions.closeWorkspace(d.ws.id) } },
            onDismiss = { dialog = null },
        )
        is Dlg.ClosePane -> ConfirmDialog(
            title = "Close pane?",
            text = "“${d.pane.title()}” will be closed" + if (d.pane.isAgent) " and its agent stopped." else ".",
            confirm = "Close",
            onConfirm = { runner.run("Close pane") { actions.closePane(d.pane.id) } },
            onDismiss = { dialog = null },
        )
    }
}

private data class Found<T>(val value: T?)

/** Tabs as pills (scrollable), with a "+" pill for a new tab. */
@Composable
private fun TabStrip(
    tabs: List<TabUi>,
    selected: TabUi?,
    selectedPaneTitle: String?,
    onSelect: (TabUi) -> Unit,
    onRename: (TabUi) -> Unit,
    onClose: (TabUi) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(TAB_STRIP_TAG).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { t ->
            val label = tabPillLabel(t, if (t == selected) selectedPaneTitle else null)
            TabItem(t, label, t == selected, { onSelect(t) }, { onRename(t) }, { onClose(t) })
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, "New tab", tint = Whistle.colors.sage, modifier = Modifier.size(20.dp))
        }
    }
}

/** A tab pill: tap selects, long-press offers rename / close. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(tab: TabUi, label: String, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val c = Whistle.colors
    Box {
        Row(
            Modifier
                .semantics { this.selected = selected; role = Role.Tab }
                .clip(WhistleShapes.Pill)
                .background(if (selected) c.cardHi else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .defaultMinSize(minHeight = 36.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tab.agentStatus != AgentStatus.UNKNOWN) {
                StatusDot(tab.agentStatus, size = 7.dp)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                label,
                color = if (selected) c.wool else c.sage,
                style = WhistleType.secondaryStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename tab") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(
                text = { Text("Close tab", color = c.ember) },
                onClick = { menu = false; onClose() },
            )
        }
    }
}

/** The panes of one tab, in layout order, one full-screen page each. */
@Composable
private fun TabPanes(
    repo: HerdrRepository,
    machineId: String,
    tabId: String,
    revealPaneId: String?,
    onRevealed: () -> Unit,
    onCurrent: (PaneUi?) -> Unit,
    onAction: (PaneUi, PaneAction) -> Unit,
    compact: Boolean,
    paneContent: PaneContent,
    canOpenTerminal: Boolean,
) {
    val panes by remember(machineId, tabId) { repo.panes(machineId, tabId) }.collectAsStateWithLifecycle(null)
    val list = panes ?: return
    if (list.isEmpty()) {
        EmptyHint("No panes in this tab.")
        LaunchedEffect(Unit) { onCurrent(null) }
        return
    }
    val latest = rememberUpdatedState(list)
    val pager = rememberPagerState { latest.value.size }
    // The pane on screen, by id: when panes before it come or go, the pager follows it.
    var shownId by rememberSaveable(tabId) { mutableStateOf<String?>(null) }

    // Keyed on the ordered ids only: pane data changes (frequent status flips) must not touch paging.
    val ids = remember(list) { list.map { it.id } }

    // Tracking runs in its own effects so an interrupted follow (below) can never stop it.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { shownId = latest.value.getOrNull(it)?.id }
    }
    LaunchedEffect(pager) {
        snapshotFlow { latest.value.getOrNull(pager.settledPage) }.collect { onCurrent(it) }
    }
    LaunchedEffect(pager, ids, revealPaneId) {
        val reveal = revealPaneId?.takeIf { it in ids }
        if (reveal != null) {
            snapshotFlow { pager.isScrollInProgress }.first { !it } // let a swipe finish first
            pager.animateScrollToPage(ids.indexOf(reveal))
            onRevealed()
            return@LaunchedEffect
        }
        // Never fight the user's swipe or a running page animation.
        if (pager.isScrollInProgress) return@LaunchedEffect
        val idx = ids.indexOf(shownId)
        when {
            idx < 0 -> shownId = ids.getOrNull(pager.currentPage.coerceAtMost(ids.lastIndex)) // removed: neighbour
            idx != pager.currentPage -> pager.scrollToPage(idx) // panes before it came or went
        }
    }

    HorizontalPager(pager, Modifier.fillMaxSize().testTag(PANE_PAGER_TAG), key = { latest.value.getOrNull(it)?.id ?: it }) { page ->
        val pane = list.getOrNull(page) ?: return@HorizontalPager
        PaneHost(
            machineId, pane, position = "${page + 1}/${list.size}",
            onAction = { onAction(pane, it) },
            content = paneContent,
            compact = compact,
            canOpenTerminal = canOpenTerminal && pane.isAgent,
        )
    }
}

@Composable
private fun StartAgentDialog(onStart: (kind: String, name: String) -> Unit, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(AGENT_KINDS.first()) }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start agent") },
        text = {
            Column {
                AGENT_KINDS.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = kind == k, onClick = { kind = k }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == k, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(k)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text(kind) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onStart(kind, name) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
