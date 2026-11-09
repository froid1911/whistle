package dev.whistle.app.widget

import android.content.Context
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.MachineStore
import dev.whistle.app.protocol.ConnState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Computes the [Snapshot] from the cache + connection states, stores it, refreshes widget and tile. */
class WidgetUpdater(
    private val ctx: Context,
    private val repo: HerdrRepository,
    private val machines: MachineStore,
    private val store: SnapshotStore,
    private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    private data class Input(val panes: List<Pane>, val allSynced: Boolean)

    private fun input(): Flow<Input> = machines.machines.flatMapLatest { ms ->
        if (ms.isEmpty()) flowOf(Input(emptyList(), false))
        else combine(
            ms.map { m ->
                combine(repo.agentPanes(m.id), repo.connState(m.id)) { ps, st ->
                    ps.map { Pane(m.id, it.id, it.agentStatus) } to (st == ConnState.Synced)
                }
            },
        ) { parts -> Input(parts.flatMap { it.first }, parts.all { it.second }) }
    }

    /** Idempotent; follows the repository while the process lives (debounced ~1 s). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { input().debounce(1_000).collect { publish(it.panes, it.allSynced) } }
    }

    /** One-shot from the cache (worker). */
    suspend fun publishFromCache(allSynced: Boolean) {
        val panes = machines.machines.value.flatMap { m ->
            repo.agentPanes(m.id).first().map { Pane(m.id, it.id, it.agentStatus) }
        }
        publish(panes, allSynced)
    }

    private suspend fun publish(panes: List<Pane>, allSynced: Boolean) {
        val next = reduceSnapshot(store.read(), panes, allSynced, System.currentTimeMillis())
        store.write(next)
        refreshSurfaces(ctx)
    }

    /** Waits until every machine is Synced; false on timeout. */
    suspend fun awaitSynced(timeoutMs: Long): Boolean {
        val ms = machines.machines.value
        if (ms.isEmpty()) return false
        val all = combine(ms.map { repo.connState(it.id) }) { st -> st.all { it == ConnState.Synced } }
        return withTimeoutOrNull(timeoutMs) { all.first { it } } != null
    }
}
