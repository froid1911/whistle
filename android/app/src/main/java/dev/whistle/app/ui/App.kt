package dev.whistle.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import dev.whistle.app.AppContainer
import dev.whistle.app.ui.pair.PairScreen
import dev.whistle.app.ui.pair.cameraGranted
import dev.whistle.app.terminal.TerminalRouteScreen
import dev.whistle.app.ui.changes.ChangesScreen
import dev.whistle.app.ui.chat.chatPaneContent
import dev.whistle.app.ui.herd.HerdScreen
import dev.whistle.app.ui.snippets.SnippetsScreen
import kotlinx.serialization.Serializable

// Type-safe routes: machines → herd (a machine's agents, by workspace) → workspace (tabs + swipeable panes).
@Serializable data object MachinesRoute

/** The herd screen of a machine (its start screen). */
@Serializable data class WorkspacesRoute(val machineId: String)

/** [tabId]/[paneId]: bring that pane's page on screen at once (share / deep links). */
@Serializable data class WorkspaceRoute(
    val machineId: String,
    val workspaceId: String,
    val tabId: String? = null,
    val paneId: String? = null,
)

/** A pane's chat to open from outside (e.g. after sharing into it). */
data class PaneTarget(val machineId: String, val paneId: String)

/** git changes of a workspace; [cwd] = the pane the user came from (default path). */
@Serializable data class ChangesRoute(val machineId: String, val workspaceId: String, val cwd: String? = null)

@Serializable data object SnippetsRoute

/**
 * Pair this phone with a machine (scan the bridge's QR code). [link]: a `whistle://pair?…` link
 * from outside (system camera, a tapped link): untrusted, parsed and validated by the screen, which
 * still waits for "Pair this phone".
 */
@Serializable data class PairRoute(val link: String? = null)

/** App lock settings. */
@Serializable data object SecurityRoute

/** Raw terminal of one pane (the chat's "Terminal" toggle). */
@Serializable data class TerminalRoute(val machineId: String, val paneId: String)

/**
 * The app's navigation. Opens straight on the first machine's herd so cached state is on
 * screen at once. [paneContent] is the per-kind pane page; null = the app's (chat for agent panes).
 */
@Composable
fun HerdrNavHost(
    container: AppContainer,
    nav: NavHostController = rememberNavController(),
    paneContent: PaneContent? = null,
    openRequest: PaneTarget? = null,
    onOpenHandled: () -> Unit = {},
    pairRequest: String? = null,
    onPairHandled: () -> Unit = {},
) {
    val content = paneContent ?: remember(container) {
        chatPaneContent(container.repo, container.outbox, container.drafts, container.snippets, onOpenSnippets = { nav.navigate(SnippetsRoute) { launchSingleTop = true } })
    }
    // Fixed once: a changed start (or a builder capturing changing state) would rebuild the graph
    // and reset the back stack, so destinations read the machine list themselves.
    val start: Any = remember { container.machines.machines.value.firstOrNull()?.let { WorkspacesRoute(it.id) } ?: MachinesRoute }
    LaunchedEffect(openRequest) {
        val t = openRequest ?: return@LaunchedEffect
        // The pane is in the Room cache (it was just picked from it); give up quietly if it is gone.
        val pane = withTimeoutOrNull(3_000) { container.repo.pane(t.machineId, t.paneId).filterNotNull().first() }
        if (pane != null) nav.navigate(WorkspaceRoute(t.machineId, pane.workspaceId, pane.tabId, pane.id))
        onOpenHandled()
    }
    LaunchedEffect(pairRequest) {
        val link = pairRequest ?: return@LaunchedEffect
        nav.navigate(PairRoute(link))
        onPairHandled()
    }
    NavHost(nav, startDestination = start) {
        composable<MachinesRoute> {
            val machines by container.machines.machines.collectAsStateWithLifecycle()
            MachinesScreen(
                machines = machines,
                repo = container.repo,
                onOpen = { m ->
                    nav.navigate(WorkspacesRoute(m.id)) { popUpTo(nav.graph.id) { inclusive = true } }
                },
                onSave = container::saveMachine,
                onOpenSecurity = { nav.navigate(SecurityRoute) { launchSingleTop = true } },
                onPair = { nav.navigate(PairRoute()) { launchSingleTop = true } },
                onRemove = { container.removeMachine(it.id) },
                newId = container.machines::newId,
            )
        }
        composable<WorkspacesRoute> { entry ->
            val r = entry.toRoute<WorkspacesRoute>()
            val machines by container.machines.machines.collectAsStateWithLifecycle()
            val machine = machines.firstOrNull { it.id == r.machineId }
            if (machine == null) {
                EmptyHint("This machine was removed.")
            } else {
                HerdScreen(
                    container.repo, machine,
                    onOpenPane = { p -> nav.navigate(WorkspaceRoute(machine.id, p.workspaceId, p.tabId, p.id)) { launchSingleTop = true } },
                    onOpenWorkspace = { nav.navigate(WorkspaceRoute(machine.id, it)) },
                    onOpenMachines = { nav.navigate(MachinesRoute) { launchSingleTop = true } },
                    onOpenChanges = { ws, cwd -> nav.navigate(ChangesRoute(machine.id, ws, cwd)) { launchSingleTop = true } },
                    broadcaster = container.broadcaster,
                    snippets = container.snippets,
                    onOpenSnippets = { nav.navigate(SnippetsRoute) { launchSingleTop = true } },
                    onOpenSecurity = { nav.navigate(SecurityRoute) { launchSingleTop = true } },
                )
            }
        }
        composable<WorkspaceRoute> { entry ->
            val r = entry.toRoute<WorkspaceRoute>()
            WorkspaceScreen(
                container.repo, r.machineId, r.workspaceId,
                initialTabId = r.tabId,
                initialPaneId = r.paneId,
                onBack = { nav.popBackStack() },
                paneContent = content,
                onOpenTerminal = { paneId -> nav.navigate(TerminalRoute(r.machineId, paneId)) { launchSingleTop = true } },
                onOpenChanges = { cwd -> nav.navigate(ChangesRoute(r.machineId, r.workspaceId, cwd)) { launchSingleTop = true } },
            )
        }
        composable<ChangesRoute> { entry ->
            val r = entry.toRoute<ChangesRoute>()
            ChangesScreen(
                container.repo, container.drafts, r.machineId, r.workspaceId, r.cwd,
                onBack = { nav.popBackStack() },
                onOpenPane = { pane ->
                    nav.navigate(WorkspaceRoute(r.machineId, pane.workspaceId, pane.tabId, pane.id)) { launchSingleTop = true }
                },
            )
        }
        composable<PairRoute> { entry ->
            val ctx = LocalContext.current
            PairScreen(
                initialLink = entry.toRoute<PairRoute>().link,
                onBack = { nav.popBackStack() },
                pair = { invite, name ->
                    val id = container.pairingTargetId(invite)
                    container.completePairing(id, invite, container.pairer.pair(invite, id, name))
                },
                onOpenMachine = { m -> nav.navigate(WorkspacesRoute(m.id)) { popUpTo(nav.graph.id) { inclusive = true } } },
                defaultDeviceName = Build.MODEL ?: "phone",
                cameraPermitted = { cameraGranted(ctx) },
            )
        }
        composable<SecurityRoute> { SecurityScreen(container.appLock, onBack = { nav.popBackStack() }) }
        composable<SnippetsRoute> { SnippetsScreen(container.snippets, onBack = { nav.popBackStack() }) }
        composable<TerminalRoute> { entry ->
            val r = entry.toRoute<TerminalRoute>()
            TerminalRouteScreen(container.repo, r.machineId, r.paneId, onBack = { nav.popBackStack() })
        }
    }
}
