package dev.whistle.app.widget

/** Side effects of a background refresh, behind an interface so the sequencing is unit-testable. */
interface RefreshOps {
    /** True while the UI is started: it owns the connections. */
    fun isForeground(): Boolean
    fun openClients()
    suspend fun awaitSynced(timeoutMs: Long): Boolean
    suspend fun publish(allSynced: Boolean)
    fun closeClients()
}

object RefreshLogic {
    const val SYNC_TIMEOUT_MS = 20_000L

    suspend fun run(ops: RefreshOps) {
        // The UI's observer keeps the snapshot fresh; don't touch its sockets or overwrite its snapshot.
        if (ops.isForeground()) return
        ops.openClients()
        try {
            val synced = ops.awaitSynced(SYNC_TIMEOUT_MS)
            ops.publish(synced)
        } finally {
            if (!ops.isForeground()) ops.closeClients() // UI opened meanwhile: it now uses them
        }
    }
}

/** Connects only machines nobody else is using, and stops exactly those. */
class OwnedClients(
    private val machineIds: () -> List<String>,
    private val inUse: (String) -> Boolean,
    private val start: (String) -> Unit,
    private val stop: (String) -> Unit,
) {
    private val owned = mutableListOf<String>()

    fun open() {
        machineIds().forEach { if (!inUse(it)) { owned += it; start(it) } }
    }

    fun close() {
        owned.forEach(stop)
        owned.clear()
    }
}
