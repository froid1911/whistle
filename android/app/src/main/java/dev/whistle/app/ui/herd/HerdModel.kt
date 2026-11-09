package dev.whistle.app.ui.herd

import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.ui.isAgent
import dev.whistle.app.ui.theme.statusPhrase
import dev.whistle.app.ui.title
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One agent pane on the herd screen, with its workspace's title for the subline. */
data class HerdAgent(val pane: PaneUi, val workspace: WorkspaceUi) {
    val subline: String get() = "${workspace.title()} · ${statusPhrase(pane.agentStatus)}"
}

/** A workspace's agents (most urgent first) and its shell panes. Needs-you agents are pinned instead. */
data class HerdGroup(val workspace: WorkspaceUi, val agents: List<HerdAgent>, val shells: List<PaneUi>)

data class Herd(val needsYou: List<HerdAgent>, val groups: List<HerdGroup>, val subline: String) {
    val isEmpty: Boolean get() = groups.isEmpty() && needsYou.isEmpty()
}

/** Herd order: needs you, then working, done, idle, unknown. */
fun statusRank(s: AgentStatus): Int = when (s) {
    AgentStatus.BLOCKED -> 0
    AgentStatus.WORKING -> 1
    AgentStatus.DONE -> 2
    AgentStatus.IDLE -> 3
    AgentStatus.UNKNOWN -> 4
}

/** "3 agents on myserver · 1 needs you" / "· 2 working" / "· all quiet"; "no agents on myserver". */
fun herdSubline(machineName: String, statuses: List<AgentStatus>): String {
    val n = statuses.size
    if (n == 0) return "No agents on $machineName"
    val head = "$n agent${if (n == 1) "" else "s"} on $machineName"
    val blocked = statuses.count { it == AgentStatus.BLOCKED }
    val working = statuses.count { it == AgentStatus.WORKING }
    val tail = when {
        blocked > 0 -> "$blocked need${if (blocked == 1) "s" else ""} you"
        working > 0 -> "$working working"
        else -> "all quiet"
    }
    return "$head · $tail"
}

/**
 * Groups [panes] (layout order) by [workspaces] (their order). Blocked agents go to
 * [Herd.needsYou]; the rest are sorted by [statusRank] within their workspace (stable, so
 * layout order breaks ties). Shell panes stay in layout order.
 */
fun buildHerd(machineName: String, workspaces: List<WorkspaceUi>, panes: List<PaneUi>): Herd {
    val byWs = panes.groupBy { it.workspaceId }
    val needs = ArrayList<HerdAgent>()
    val groups = workspaces.map { ws ->
        val ps = byWs[ws.id].orEmpty()
        val agents = ps.filter { it.isAgent }.map { HerdAgent(it, ws) }
        needs += agents.filter { it.pane.agentStatus == AgentStatus.BLOCKED }
        HerdGroup(
            ws,
            agents.filter { it.pane.agentStatus != AgentStatus.BLOCKED }.sortedBy { statusRank(it.pane.agentStatus) },
            ps.filter { !it.isAgent },
        )
    }
    val statuses = panes.filter { it.isAgent && it.workspaceId in workspaces.map { w -> w.id } }.map { it.agentStatus }
    return Herd(needs, groups, herdSubline(machineName, statuses))
}

/** Display name of an agent pane (label, else agent kind). */
val HerdAgent.name: String get() = pane.title()

/**
 * Every workspace of a machine and every pane of its tabs, from the Room cache (kept live by the
 * sync stream). Built from the repository's existing per-tab reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun HerdrRepository.herdSource(machineId: String): Flow<Pair<List<WorkspaceUi>, List<PaneUi>>> =
    workspaces(machineId).flatMapLatest { wss ->
        if (wss.isEmpty()) return@flatMapLatest flowOf(wss to emptyList())
        combine(wss.map { tabs(machineId, it.id) }) { arr -> arr.flatMap { it } }
            .distinctUntilChanged()
            .flatMapLatest { tabs ->
                if (tabs.isEmpty()) flowOf(emptyList())
                else combine(tabs.map { panes(machineId, it.id) }) { arr -> arr.flatMap { it } }
            }
            .map { wss to it }
    }
