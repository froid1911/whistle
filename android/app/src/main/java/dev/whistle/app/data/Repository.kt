package dev.whistle.app.data

import androidx.room.withTransaction
import dev.whistle.app.protocol.AuthProblem
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.protocol.Change
import dev.whistle.app.protocol.ChangeKind
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.CursorStore
import dev.whistle.app.protocol.Entity
import dev.whistle.app.protocol.Live
import dev.whistle.app.protocol.LiveClear
import dev.whistle.app.protocol.ServerMsg
import dev.whistle.app.protocol.StateMsg
import dev.whistle.app.protocol.SyncClient
import dev.whistle.app.protocol.ConnectionStarted
import dev.whistle.app.protocol.Synced
import dev.whistle.app.protocol.WLayout
import dev.whistle.app.protocol.WHistoryPage
import dev.whistle.app.protocol.WMessage
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import dev.whistle.app.protocol.Welcome
import dev.whistle.app.protocol.WireJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** Room-backed resume cursor. The repository writes it only inside the applying transaction. */
class RoomCursorStore(private val db: HerdrDb) : CursorStore {
    override suspend fun get(machineId: String): Pair<String, Long>? =
        db.sync().cursor(machineId)?.let { it.serverId to it.seq }

    override suspend fun set(machineId: String, serverId: String, seq: Long) =
        db.sync().setCursor(CursorEntity(machineId, serverId, seq))
}


/**
 * Offline-first cache of every machine's herdr state and chats, kept in sync by applying each
 * [SyncClient.syncEvents] frame in one Room transaction that also advances the resume cursor
 * (so the cursor is never ahead of the data).
 *
 * A machine's client is created, its applier and event collector attached, and only then started,
 * on the first per-machine call ([connect], [connState], [live], [cmd], [ensureHistory], [loadOlder]).
 * The DB flows ([workspaces] etc.) never create a client.
 *
 * [beforeCommit] runs inside each apply transaction after its writes (tests use it to inject failures).
 */
/** UI connection state: Syncing while the applier has processed fewer `synced` frames than were received. */
fun appliedConnState(st: ConnState, received: Long, applied: Long): ConnState =
    if ((st == ConnState.Synced || st == ConnState.HerdrOffline) && applied < received) ConnState.Syncing else st

class HerdrRepository(
    private val db: HerdrDb,
    private val clientFactory: (machineId: String, cursor: CursorStore) -> SyncClient,
    private val scope: CoroutineScope,
    private val beforeCommit: (ServerMsg) -> Unit = {},
) {
    val cursorStore: CursorStore = RoomCursorStore(db)

    private val clients = ConcurrentHashMap<String, SyncClient>()
    private val lives = ConcurrentHashMap<Pair<String, String>, MutableStateFlow<String?>>()
    private val shown = HashMap<Pair<String, String>, Int>() // guarded by itself
    private val historyLocks = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private val fetchStates = ConcurrentHashMap<Pair<String, String>, MutableStateFlow<ChatState?>>()

    /**
     * Bumped inside every state transaction. A history page fetched under an older generation
     * predates a resync (or comes from a replaced bridge DB) and is discarded.
     */
    /** Apply transactions run (tests check batching). */
    internal val applyTransactions = AtomicLong()

    private val generations = ConcurrentHashMap<String, AtomicLong>()

    private fun generation(machineId: String) = generations.getOrPut(machineId) { AtomicLong() }

    // ---- clients ---------------------------------------------------------------

    fun connect(machineId: String): SyncClient =
        clients[machineId] ?: synchronized(clients) { clients.getOrPut(machineId) { attach(machineId) } }

    /** Stops all clients (the [scope] is owned by the caller). [SyncClient.start] resumes one. */
    fun close() = clients.values.forEach { it.stop() }

    /**
     * Stops and forgets a machine's client (e.g. its address changed); the next per-machine call
     * creates a fresh one. The cache is kept: a different bridge is detected by its server_id.
     */
    fun disconnect(machineId: String) {
        val client = synchronized(clients) {
            clients.remove(machineId)?.also {
                clientJobs.remove(machineId)?.cancel()
                connStates.remove(machineId)
            }
        } ?: return
        client.stop()
        lives.forEach { (k, v) -> if (k.first == machineId) v.value = null }
    }

    private val clientJobs = ConcurrentHashMap<String, Job>()
    private val connStates = ConcurrentHashMap<String, StateFlow<ConnState>>()

    private fun attach(machineId: String): SyncClient {
        val client = clientFactory(machineId, cursorStore)
        val job = SupervisorJob(scope.coroutineContext[Job]).also { clientJobs[machineId] = it }
        val scope = scope + job
        // Synced frames the applier has fully processed (vs. client.syncedFrames received).
        val appliedSynced = MutableStateFlow(0L)
        // Read all three values on every change (not combine's per-flow snapshots): the client bumps
        // syncedFrames before it reports Synced, so Synced is never seen with a stale count.
        fun effective() = appliedConnState(client.connection.value, client.syncedFrames.value, appliedSynced.value)
        val conn = merge(client.connection, client.syncedFrames, appliedSynced).map { effective() }
            .stateIn(scope, SharingStarted.Eagerly, effective())
        connStates[machineId] = conn
        // UNDISPATCHED: both collectors are subscribed before start() returns from here.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { Applier(machineId, client, appliedSynced).run() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { onEvent(machineId, it) } }
        scope.launch {
            var wasSynced = false
            conn.collect { st ->
                if (st == ConnState.Offline) lives.forEach { (k, v) -> if (k.first == machineId) v.value = null }
                val synced = st == ConnState.Synced || st == ConnState.HerdrOffline
                // Retry stale/failed shown chats on every (re)sync.
                if (synced && !wasSynced) refetchShown(machineId)
                wasSynced = synced
            }
        }
        client.start()
        return client
    }

    /**
     * The machine's connection as the UI should show it: [ConnState.Syncing] until the applier has
     * applied everything up to the bridge's `synced` (not merely received it), so "Synced" never
     * labels a cache that is still behind.
     */
    fun connState(machineId: String): StateFlow<ConnState> {
        val client = connect(machineId)
        return connStates[machineId] ?: client.connection
    }

    /** Whether the machine's current connection got its (verified, when paired) welcome. */
    fun sessionReady(machineId: String): StateFlow<Boolean> = connect(machineId).sessionReady

    /** Why the machine refuses this phone (null = fine); the client has stopped when set. */
    fun authProblem(machineId: String): StateFlow<AuthProblem?> = connect(machineId).authProblem

    suspend fun cmd(machineId: String, method: String, params: JsonObject): JsonElement =
        connect(machineId).call(method, params)

    fun watch(machineId: String, paneId: String) = connect(machineId).watch(paneId)
    fun unwatch(machineId: String, paneId: String) = connect(machineId).unwatch(paneId)

    // ---- reads -----------------------------------------------------------------

    fun workspaces(machineId: String): Flow<List<WorkspaceUi>> =
        db.machines().workspaces(machineId).map { rows -> rows.map { it.toUi() } }.distinctUntilChanged()

    /** Workspaces with pane count and status rollup ([rollupStatus]). */
    fun workspaceSummaries(machineId: String): Flow<List<WorkspaceSummaryUi>> =
        combine(workspaces(machineId), db.machines().paneStatuses(machineId)) { wss, panes ->
            val byWs = panes.groupBy({ it.workspaceId }, { AgentStatus.parse(it.agentStatus) })
            wss.map { w ->
                val ps = byWs[w.id].orEmpty()
                WorkspaceSummaryUi(w, ps.size, rollupStatus(ps, w.agentStatus))
            }
        }.distinctUntilChanged()

    fun tabs(machineId: String, wsId: String): Flow<List<TabUi>> =
        db.machines().tabs(machineId, wsId).map { rows -> rows.map { it.toUi() } }.distinctUntilChanged()

    /** Panes whose tab exists, ordered by layout y, then x (panes missing from the layout last). */
    fun panes(machineId: String, tabId: String): Flow<List<PaneUi>> =
        db.machines().panes(machineId, tabId).map { rows -> rows.map { it.toUi() } }.distinctUntilChanged()

    fun agentPanes(machineId: String): Flow<List<PaneUi>> =
        db.machines().agentPanes(machineId).map { l -> l.map { it.toUi() } }.distinctUntilChanged()

    fun pane(machineId: String, paneId: String): Flow<PaneUi?> =
        db.machines().pane(machineId, paneId).map { it?.toUi() }.distinctUntilChanged()

    /**
     * Messages ordered by their ordering seq. While collected, the chat counts as "shown": after
     * a resync its latest history page is refetched automatically.
     */
    fun messages(machineId: String, sessionKey: String): Flow<List<WMessage>> {
        val key = machineId to sessionKey
        return db.messages().messages(machineId, sessionKey)
            .map { rows -> rows.map { it.toWire() } }
            .distinctUntilChanged()
            .onStart { synchronized(shown) { shown[key] = (shown[key] ?: 0) + 1 } }
            .onCompletion {
                synchronized(shown) {
                    val n = (shown[key] ?: 1) - 1
                    if (n <= 0) shown.remove(key) else shown[key] = n
                }
            }
    }

    /**
     * Freshness of a chat's cache: [ChatState.Stale] after a resync until its latest page is
     * refetched (the cache may be missing messages), [ChatState.Error] if that refetch failed.
     */
    fun chatState(machineId: String, sessionKey: String): Flow<ChatState> =
        combine(db.messages().metaFlow(machineId, sessionKey), fetchState(machineId, sessionKey)) { meta, fetch ->
            when {
                fetch == ChatState.Loading -> ChatState.Loading
                meta != null && !meta.stale -> ChatState.Fresh
                fetch is ChatState.Error -> fetch
                else -> ChatState.Stale
            }
        }.distinctUntilChanged()

    /** In-memory state of the latest-page fetch: null (idle), Loading or Error. */
    private fun fetchState(machineId: String, sessionKey: String) =
        fetchStates.getOrPut(machineId to sessionKey) { MutableStateFlow(null) }

    /** In-progress screen text of a watched agent pane; null when no turn is in progress. */
    fun live(machineId: String, paneId: String): StateFlow<String?> {
        connect(machineId)
        return liveFlow(machineId, paneId).asStateFlow()
    }

    private fun liveFlow(machineId: String, paneId: String) =
        lives.getOrPut(machineId to paneId) { MutableStateFlow(null) }

    private fun onEvent(machineId: String, msg: ServerMsg) {
        when (msg) {
            is Live -> liveFlow(machineId, msg.paneId).value = msg.text
            is LiveClear -> liveFlow(machineId, msg.paneId).value = null
            else -> Unit
        }
    }

    // ---- chat history ----------------------------------------------------------

    /** Fetches the latest page if it was never fetched or a resync made the cache untrusted. */
    suspend fun ensureHistory(machineId: String, sessionKey: String) = withHistoryLock(machineId, sessionKey) {
        connect(machineId)
        val meta = db.messages().meta(machineId, sessionKey)
        if (meta == null || meta.stale) fetchLatest(machineId, sessionKey)
    }

    /** Fetches the page before the oldest cached message (no-op if the bridge said there is none). */
    suspend fun loadOlder(machineId: String, sessionKey: String) = withHistoryLock(machineId, sessionKey) {
        connect(machineId)
        val dao = db.messages()
        val meta = dao.meta(machineId, sessionKey)
        val oldest = dao.minSeq(machineId, sessionKey)
        if (meta == null || meta.stale || oldest == null) {
            fetchLatest(machineId, sessionKey)
            return@withHistoryLock
        }
        if (!meta.more) return@withHistoryLock
        val gen = generation(machineId).get()
        val snapshot = cursorStore.get(machineId)?.second ?: 0
        val page = history(machineId, sessionKey, oldest)
        db.withTransaction {
            if (generation(machineId).get() != gen) return@withTransaction // resynced meanwhile
            page.messages.forEach { dao.upsertFromHistory(it.toEntity(machineId, sessionKey), snapshot) }
            dao.setMeta(ChatMetaEntity(machineId, sessionKey, more = page.more, stale = false))
        }
    }

    private suspend fun fetchLatest(machineId: String, sessionKey: String) {
        val status = fetchState(machineId, sessionKey)
        status.value = ChatState.Loading
        try {
            val dao = db.messages()
            val gen = generation(machineId).get()
            // The page is a snapshot at (at least) this cursor; rows changed after it are newer than the page.
            val snapshot = cursorStore.get(machineId)?.second ?: 0
            val page = history(machineId, sessionKey, null)
            db.withTransaction {
                // Resynced meanwhile: the chat stays stale and the post-resync refetch replaces this page.
                if (generation(machineId).get() != gen) return@withTransaction
                // Older cached messages may be separated from this page by a gap: drop them, loadOlder refills.
                page.messages.minOfOrNull { it.seq }?.let { dao.deleteOlderThan(machineId, sessionKey, it) }
                page.messages.forEach { dao.upsertFromHistory(it.toEntity(machineId, sessionKey), snapshot) }
                dao.setMeta(ChatMetaEntity(machineId, sessionKey, more = page.more, stale = false))
            }
            status.value = null
        } catch (e: CancellationException) {
            status.value = null
            throw e
        } catch (e: BridgeError) {
            status.value = ChatState.Error(e.code)
            throw e
        } catch (e: Exception) {
            status.value = ChatState.Error("internal")
            throw e
        }
    }

    private suspend fun history(machineId: String, sessionKey: String, beforeSeq: Long?): WHistoryPage {
        val res = connect(machineId).call(
            "chat.history",
            buildJsonObject {
                put("session_key", sessionKey)
                if (beforeSeq != null) put("before_seq", beforeSeq)
                put("limit", HISTORY_PAGE)
            },
        )
        return WireJson.decodeFromJsonElement<WHistoryPage>(res)
    }

    private suspend fun <T> withHistoryLock(machineId: String, sessionKey: String, block: suspend () -> T): T =
        historyLocks.getOrPut(machineId to sessionKey) { Mutex() }.withLock { block() }

    private fun refetchShown(machineId: String) {
        val keys = synchronized(shown) { shown.keys.filter { it.first == machineId } }
        for ((m, sk) in keys) {
            scope.launch {
                try {
                    ensureHistory(m, sk)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // offline / bridge error: the cache stays stale, the next ensureHistory retries
                }
            }
        }
    }

    // ---- sync applier ----------------------------------------------------------

    /**
     * The single collector of one client's [SyncClient.syncEvents], for the client's whole life.
     * [lastApplied] is the seq of the baseline this connection is applying on top of; null means
     * "no baseline": changes are ignored until the next welcome (resume) or state (resync).
     */
    private inner class Applier(
        private val m: String,
        private val client: SyncClient,
        private val appliedSynced: MutableStateFlow<Long>,
    ) {
        private var welcome: Welcome? = null
        private var lastApplied: Long? = null

        /**
         * Frames are moved to a local queue so a backlog of consecutive changes can be drained
         * and applied in one transaction (one cursor write, one invalidation of the DB flows).
         * Draining never waits for more frames: a lone live change is applied immediately.
         */
        suspend fun run() = coroutineScope {
            val inbox = Channel<ServerMsg>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (true) {
                        try {
                            client.syncEvents.collect { inbox.send(it) }
                            return@launch
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            fail()
                        }
                    }
                } finally {
                    inbox.close()
                }
            }
            var held: ServerMsg? = null
            while (true) {
                val first = held ?: inbox.receiveCatching().getOrNull() ?: break
                held = null
                if (first is Change) {
                    val batch = arrayListOf(first)
                    val deadline = System.nanoTime() + BATCH_WINDOW_NS
                    while (batch.size < BATCH_MAX && System.nanoTime() < deadline) {
                        val next = inbox.tryReceive().getOrNull() ?: break
                        if (next is Change) batch += next else {
                            held = next
                            break
                        }
                    }
                    safely { onChanges(batch) }
                } else {
                    try {
                        safely { handle(first) }
                    } finally {
                        if (first is Synced) appliedSynced.value++
                    }
                }
            }
        }

        /** A failure applying a frame must not detach the applier: drop the connection instead. */
        private suspend inline fun safely(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                fail()
            }
        }

        /** Drop the connection: the next hello resumes from the cursor of the last applied frame. */
        private fun fail() {
            welcome = null
            lastApplied = null
            client.reconnect()
        }

        private suspend fun handle(msg: ServerMsg) {
            when (msg) {
                // A new connection: nothing of it may be applied on the previous connection's baseline.
                ConnectionStarted -> {
                    welcome = null
                    lastApplied = null
                }
                is Welcome -> onWelcome(msg)
                is StateMsg -> onState(msg)
                is Change -> onChanges(listOf(msg))
                is Synced -> onSynced(msg)
                else -> Unit
            }
        }

        /** After replay, everything up to synced.seq must have been applied; else a change went missing. */
        private fun onSynced(s: Synced) {
            if (welcome == null) return // stale frame of a dropped connection
            if (lastApplied != s.seq) fail()
        }

        private suspend fun onWelcome(w: Welcome) {
            welcome = w
            lastApplied = null
            if (w.resync) return // baseline comes with the following state
            val stored = db.sync().cursor(m)
            if (stored == null || stored.serverId != w.serverId) {
                fail() // a resume we did not ask for: never apply changes on top of the wrong baseline
                return
            }
            lastApplied = stored.seq
        }

        private suspend fun onState(s: StateMsg) {
            val w = welcome ?: return // stale frame of a dropped connection
            try {
                db.withTransaction {
                    // Bumped before commit, so no history transaction can see the new state with the old
                    // generation. If this transaction rolls back, the extra bump only discards one page.
                    generation(m).incrementAndGet()
                    val stored = db.sync().cursor(m)
                    replaceState(s)
                    if (stored != null && stored.serverId != w.serverId) {
                        // Different bridge DB: its message seqs are a different numbering. Drop the chats.
                        db.messages().clearMessages(m)
                        db.messages().clearMeta(m)
                    } else {
                        db.messages().markAllStale(m)
                    }
                    db.sync().setCursor(CursorEntity(m, w.serverId, s.seq))
                    beforeCommit(s)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail()
                return
            }
            lastApplied = s.seq
            refetchShown(m)
        }

        private suspend fun replaceState(s: StateMsg) {
            val dao = db.sync()
            dao.clearWorkspaces(m)
            dao.clearTabs(m)
            dao.clearPanes(m)
            dao.clearLayouts(m)
            val st = s.state
            dao.upsertWorkspaces(st.workspaces.map { it.toEntity(m) })
            dao.upsertTabs(st.tabs.map { it.toEntity(m) })
            dao.upsertPanes(st.panes.map { it.toEntity(m) })
            dao.upsertLayoutPanes(st.layouts.flatMap { l -> l.panes.map { it.toEntity(m, l.tabId) } })
        }

        /**
         * Applies the gap-free prefix of [batch] (after dropping replayed duplicates) in ONE
         * transaction that also advances the cursor to its last seq. A gap inside the batch applies
         * the prefix, then drops the connection (the resume replays from the new cursor).
         */
        private suspend fun onChanges(batch: List<Change>) {
            var last = lastApplied ?: return // no baseline on this connection (yet)
            val w = welcome ?: return
            val apply = ArrayList<Change>(batch.size)
            var gap = false
            for (c in batch) {
                if (c.seq <= last) continue // replayed duplicate
                if (c.seq != last + 1) {
                    gap = true // never apply past a missing change
                    break
                }
                apply += c
                last = c.seq
            }
            if (apply.isNotEmpty()) {
                try {
                    applyTransactions.incrementAndGet()
                    db.withTransaction {
                        for (c in apply) {
                            applyChange(c)
                            beforeCommit(c)
                        }
                        db.sync().setCursor(CursorEntity(m, w.serverId, last))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fail()
                    return
                }
                lastApplied = last
            }
            if (gap) fail()
        }

        private suspend fun applyChange(c: Change) {
            val dao = db.sync()
            when (c.kind) {
                ChangeKind.UPSERT -> {
                    requireNotNull(c.data) { "upsert ${c.seq} without data" }
                    when (c.entity) {
                        Entity.WORKSPACE -> dao.upsertWorkspaces(listOf(c.dataAs<WWorkspace>()!!.toEntity(m)))
                        Entity.TAB -> dao.upsertTabs(listOf(c.dataAs<WTab>()!!.toEntity(m)))
                        Entity.PANE -> dao.upsertPanes(listOf(c.dataAs<WPane>()!!.toEntity(m)))
                        Entity.LAYOUT -> {
                            val l = c.dataAs<WLayout>()!!
                            dao.deleteLayout(m, l.tabId)
                            dao.upsertLayoutPanes(l.panes.map { it.toEntity(m, l.tabId) })
                        }
                        else -> Unit // entity added by a newer bridge
                    }
                }
                ChangeKind.REMOVE -> {
                    val id = requireNotNull(c.id) { "remove ${c.seq} without id" }
                    // No cascades to children: their own removes follow (log is ordered per pane only).
                    when (c.entity) {
                        Entity.WORKSPACE -> dao.deleteWorkspace(m, id)
                        Entity.TAB -> {
                            dao.deleteTab(m, id)
                            dao.deleteLayout(m, id) // layouts are keyed by tab
                        }
                        Entity.PANE -> dao.deletePane(m, id)
                        Entity.LAYOUT -> dao.deleteLayout(m, id)
                        else -> Unit
                    }
                }
                ChangeKind.MESSAGE -> {
                    val msg = requireNotNull(c.message) { "message ${c.seq} without message" }
                    db.messages().upsert(msg.toEntity(m, c.sessionKey ?: msg.sessionKey, changeSeq = c.seq))
                }
                // The pane upsert carrying the same status may come before or after; both are idempotent.
                // A status for a pane not known yet updates nothing; its upsert carries the status.
                ChangeKind.STATUS -> dao.setPaneStatus(
                    m,
                    requireNotNull(c.paneId) { "status ${c.seq} without pane_id" },
                    requireNotNull(c.to) { "status ${c.seq} without to" },
                )
                else -> Unit // kind added by a newer bridge
            }
        }
    }

    companion object {
        const val HISTORY_PAGE = 100
        const val BATCH_MAX = 200
        const val BATCH_WINDOW_NS = 50_000_000L
    }
}
