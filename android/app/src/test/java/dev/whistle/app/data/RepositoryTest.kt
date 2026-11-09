package dev.whistle.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.protocol.Change
import dev.whistle.app.protocol.ClientMsg
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.ConnectionStarted
import dev.whistle.app.protocol.CursorStore
import dev.whistle.app.protocol.Live
import dev.whistle.app.protocol.LiveClear
import dev.whistle.app.protocol.ServerMsg
import dev.whistle.app.protocol.StateMsg
import dev.whistle.app.protocol.SyncClient
import dev.whistle.app.protocol.Synced
import dev.whistle.app.protocol.WLayout
import dev.whistle.app.protocol.WLayoutPane
import dev.whistle.app.protocol.WMessage
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WState
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import dev.whistle.app.protocol.Welcome
import dev.whistle.app.protocol.WireJson
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val M = "m1"

class FakeClient : SyncClient {
    val sync = Channel<ServerMsg>(Channel.UNLIMITED)
    val syncCollectors = AtomicInteger()
    var collectorsAtStart = -1
    val reconnects = AtomicInteger()
    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    @Volatile var onCall: (String, JsonObject) -> JsonElement = { m, _ -> throw BridgeError("not_available", m) }

    override val syncEvents: Flow<ServerMsg> = flow {
        syncCollectors.incrementAndGet()
        try {
            emitAll(sync)
        } finally {
            syncCollectors.decrementAndGet()
        }
    }
    val ev = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val events = ev
    override val connection = MutableStateFlow(ConnState.Offline)
    override val syncedFrames = MutableStateFlow(0L)

    override fun start() {
        collectorsAtStart = syncCollectors.get()
    }

    override fun stop() {}
    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls.add(method to params)
        return onCall(method, params)
    }

    override fun watch(paneId: String) {}
    override fun unwatch(paneId: String) {}
    override fun reconnect() {
        reconnects.incrementAndGet()
    }

    override fun send(raw: ClientMsg): Boolean = false

    // Like BridgeClient: a synced frame is counted before it is queued (and before connection says Synced).
    suspend fun send(vararg msgs: ServerMsg) = msgs.forEach {
        if (it is Synced) syncedFrames.value++
        sync.send(it)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    private lateinit var db: HerdrDb
    private lateinit var scope: CoroutineScope
    private lateinit var repo: HerdrRepository
    private val fakes = mutableMapOf<String, FakeClient>()
    private var hook: (ServerMsg) -> Unit = {}
    private val fake get() = fakes.getValue(M)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HerdrDb::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = HerdrRepository(db, { id, _: CursorStore -> FakeClient().also { fakes[id] = it } }, scope) { hook(it) }
        repo.connState(M) // creates + attaches the client
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ---- builders ----
    private fun ws(id: String, n: Int, status: String? = null) = WWorkspace(id, n, "ws$n", status, null)
    private fun tab(id: String, ws: String, n: Int = 1) = WTab(id, ws, n, "tab$n")
    private fun pane(id: String, tab: String, ws: String = "w1", status: String? = null, sk: String? = null) =
        WPane(id, tab, ws, "lbl-$id", "/tmp", "agent", "claude", status, sk, sk != null)
    private fun msg(id: String, seq: Long, text: String, sk: String = "claude:s1") =
        WMessage(id, seq, sk, "2026-11-08T00:00:00Z", "assistant", text)

    private inline fun <reified T> upsert(seq: Long, entity: String, data: T) =
        Change(seq, "upsert", entity = entity, data = WireJson.encodeToJsonElement(data).jsonObject)
    private fun remove(seq: Long, entity: String, id: String) = Change(seq, "remove", entity = entity, id = id)
    private fun msgChange(seq: Long, m: WMessage) = Change(seq, "message", sessionKey = m.sessionKey, message = m)
    private fun status(seq: Long, pane: String, to: String) =
        Change(seq, "status", paneId = pane, from = "working", to = to, at = "2026-11-08T00:00:00Z")

    private val baseState = WState(
        workspaces = listOf(ws("w1", 1, "working")),
        tabs = listOf(tab("t1", "w1")),
        panes = listOf(pane("p1", "t1", status = "working", sk = "claude:s1"), pane("p2", "t1")),
        layouts = listOf(WLayout("t1", listOf(WLayoutPane("p1", 0, 10, 80, 10), WLayoutPane("p2", 0, 0, 80, 10)))),
    )

    private suspend fun baseline(serverId: String = "srv", seq: Long = 5, state: WState = baseState) {
        fake.send(Welcome(1, serverId, seq, resync = true, herdrVersion = "0.9.5"), StateMsg(seq, state), Synced(seq))
        eventually { cursor() == (serverId to seq) }
    }

    private suspend fun cursor() = repo.cursorStore.get(M)

    private suspend fun eventually(what: String = "condition", cond: suspend () -> Boolean) {
        withTimeout(5_000) { while (!cond()) delay(10) }
    }

    private fun page(more: Boolean, vararg ms: WMessage): JsonElement = buildJsonObject {
        put("messages", WireJson.encodeToJsonElement(ms.toList()))
        put("more", more)
    }

    // ---- tests ----

    @Test
    fun applierAttachedBeforeStartAndSingle() = runBlocking<Unit> {
        assertEquals(1, fake.collectorsAtStart)
        repo.connState(M); repo.workspaces(M).first()
        assertEquals(1, fakes.size)
        assertEquals(1, fake.syncCollectors.get())
    }

    @Test
    fun appliesStateThenChanges() = runBlocking<Unit> {
        baseline()
        val wss = repo.workspaces(M).first()
        assertEquals(listOf("w1"), wss.map { it.id })
        assertEquals(AgentStatus.WORKING, wss[0].agentStatus)
        assertEquals(listOf("t1"), repo.tabs(M, "w1").first().map { it.id })
        // ordered by layout y then x: p2 (y=0) before p1 (y=10)
        assertEquals(listOf("p2", "p1"), repo.panes(M, "t1").first().map { it.id })

        fake.send(
            upsert(6, "pane", pane("p3", "t1")),
            upsert(7, "layout", WLayout("t1", listOf(WLayoutPane("p3", 0, 0, 40, 10), WLayoutPane("p2", 40, 0, 40, 10), WLayoutPane("p1", 0, 10, 80, 10)))),
            remove(8, "workspace", "w9"), // unknown id: harmless
        )
        eventually { cursor()?.second == 8L }
        assertEquals(listOf("p3", "p2", "p1"), repo.panes(M, "t1").first().map { it.id })
        assertEquals("claude:s1", repo.pane(M, "p1").first()?.sessionKey)
    }

    @Test
    fun newConnectionDropsTheBaselineBeforeItsWelcome() = runBlocking<Unit> {
        baseline()
        fake.send(upsert(6, "pane", pane("p3", "t1")))
        eventually { cursor()?.second == 6L }
        // A new connection whose frames would fit seamlessly on the old baseline: nothing of it
        // may be applied before its own welcome.
        fake.send(ConnectionStarted, upsert(7, "pane", pane("p4", "t1")), Synced(7))
        delay(200)
        assertEquals(6L, cursor()?.second)
        assertNull(repo.pane(M, "p4").first())
        // With its welcome (resume from the stored cursor) the connection applies again.
        fake.send(ConnectionStarted, Welcome(1, "srv", 7, resync = false, herdrVersion = "0.9.5"), upsert(7, "pane", pane("p4", "t1")), Synced(7))
        eventually { cursor()?.second == 7L }
        assertEquals("p4", repo.pane(M, "p4").first()?.id)
    }

    @Test
    fun workspaceSummaryRollsUpPaneStatuses() = runBlocking<Unit> {
        baseline(
            state = baseState.copy(
                workspaces = listOf(ws("w1", 1, "idle"), ws("w2", 2, "idle")),
                tabs = listOf(tab("t1", "w1"), tab("t2", "w2")),
                panes = listOf(pane("p1", "t1", status = "done"), pane("p2", "t1", status = "blocked"), pane("p3", "t2", ws = "w2")),
            ),
        )
        val rows = repo.workspaceSummaries(M).first()
        assertEquals(listOf(2 to AgentStatus.BLOCKED, 1 to AgentStatus.IDLE), rows.map { it.paneCount to it.status })
    }

    @Test
    fun disconnectDetachesAndNextCallCreatesFreshClient() = runBlocking<Unit> {
        val old = fake
        repo.disconnect(M)
        eventually { old.syncCollectors.get() == 0 }
        repo.connState(M)
        assertTrue(fake !== old)
        assertEquals(1, fake.collectorsAtStart)
    }

    @Test
    fun cursorAdvancesWithChangeAtomically() = runBlocking<Unit> {
        baseline()
        hook = { if (it is Change && it.seq == 6L) throw IllegalStateException("boom") }
        fake.send(upsert(6, "workspace", ws("w2", 2)))
        eventually { fake.reconnects.get() == 1 }
        assertEquals("srv" to 5L, cursor())
        assertEquals(listOf("w1"), repo.workspaces(M).first().map { it.id }) // rolled back with the cursor

        // a malformed change (upsert without data) fails the same way
        hook = {}
        fake.send(Welcome(1, "srv", 6, resync = false, herdrVersion = "x"), Change(6, "upsert", entity = "tab"))
        eventually { fake.reconnects.get() == 2 }
        assertEquals("srv" to 5L, cursor())

        fake.send(Welcome(1, "srv", 6, resync = false, herdrVersion = "x"), upsert(6, "workspace", ws("w2", 2)))
        eventually { cursor()?.second == 6L }
        assertEquals(listOf("w1", "w2"), repo.workspaces(M).first().map { it.id })
    }

    @Test
    fun gapIsNotAppliedAndReconnects() = runBlocking<Unit> {
        baseline()
        fake.send(
            upsert(5, "workspace", ws("dup5", 5)), // duplicate of the baseline seq: ignored
            upsert(6, "workspace", ws("w6", 6)),
            upsert(6, "workspace", ws("dup6", 6)), // replayed duplicate: ignored
            upsert(8, "workspace", ws("w8", 8)), // gap (7 missing)
            upsert(9, "workspace", ws("w9", 9)), // after a gap nothing applies until the next welcome
        )
        eventually { fake.reconnects.get() == 1 }
        delay(100)
        assertEquals("srv" to 6L, cursor())
        assertEquals(listOf("w1", "w6"), repo.workspaces(M).first().map { it.id })
        assertEquals(1, fake.reconnects.get())

        // resumed from the stored cursor
        fake.send(
            Welcome(1, "srv", 8, resync = false, herdrVersion = "x"),
            upsert(7, "workspace", ws("w7", 7)),
            upsert(8, "workspace", ws("w8", 8)),
            Synced(8),
        )
        eventually { cursor()?.second == 8L }
        assertEquals(listOf("w1", "w6", "w7", "w8"), repo.workspaces(M).first().map { it.id })
    }

    @Test
    fun resyncReplacesStaleEntities() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old")) }
        repo.ensureHistory(M, "claude:s1")
        assertEquals(1, fake.calls.size)
        repo.ensureHistory(M, "claude:s1") // cached and fresh: no refetch
        assertEquals(1, fake.calls.size)

        // same server, log pruned: state replaces machine rows, messages kept, history refetched on demand
        val s2 = WState(workspaces = listOf(ws("w2", 2)), tabs = listOf(tab("t2", "w2")), panes = listOf(pane("p9", "t2", "w2")))
        fake.send(Welcome(1, "srv", 50, resync = true, herdrVersion = "x"), StateMsg(50, s2), Synced(50))
        eventually { cursor()?.second == 50L }
        assertEquals(listOf("w2"), repo.workspaces(M).first().map { it.id })
        assertTrue(repo.tabs(M, "w1").first().isEmpty())
        assertTrue(repo.panes(M, "t1").first().isEmpty())
        assertNull(repo.pane(M, "p1").first())
        assertEquals(listOf("a"), repo.messages(M, "claude:s1").first().map { it.id })
        fake.onCall = { _, _ -> page(true, msg("b", 40, "new")) }
        repo.ensureHistory(M, "claude:s1")
        assertEquals(2, fake.calls.size)
        // latest page replaces older cached messages (no gap between them and the page)
        assertEquals(listOf("b"), repo.messages(M, "claude:s1").first().map { it.id })

        // different server_id: cache dropped
        fake.send(Welcome(1, "srv2", 3, resync = true, herdrVersion = "x"), StateMsg(3, baseState), Synced(3))
        eventually { cursor() == ("srv2" to 3L) }
        assertEquals(listOf("w1"), repo.workspaces(M).first().map { it.id })
        assertTrue(repo.messages(M, "claude:s1").first().isEmpty())
    }

    @Test
    fun shownChatIsRefetchedAfterResync() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old")) }
        val shown = scope.launchCollect(repo.messages(M, "claude:s1"))
        repo.ensureHistory(M, "claude:s1")
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old"), msg("b", 60, "new")) }
        fake.send(Welcome(1, "srv", 60, resync = true, herdrVersion = "x"), StateMsg(60, baseState), Synced(60))
        eventually { fake.calls.size == 2 }
        eventually { repo.messages(M, "claude:s1").first().map { it.id } == listOf("a", "b") }
        shown.cancel()
    }

    @Test
    fun resyncDuringHistoryFetchKeepsChatStale() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, _ ->
            // a resync lands while this (now outdated) page is in flight
            runBlocking {
                fake.send(Welcome(1, "srv", 70, resync = true, herdrVersion = "x"), StateMsg(70, baseState), Synced(70))
                eventually { cursor()?.second == 70L }
            }
            page(false, msg("a", 1, "old"))
        }
        repo.ensureHistory(M, "claude:s1")
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old"), msg("b", 65, "new")) }
        repo.ensureHistory(M, "claude:s1") // must not trust the pre-resync page
        assertEquals(2, fake.calls.size)
        assertEquals(listOf("a", "b"), repo.messages(M, "claude:s1").first().map { it.id })
    }

    @Test
    fun historyPageNeverOverwritesNewerChange() = runBlocking<Unit> {
        baseline()
        fake.send(msgChange(6, msg("x", 6, "v1 trunc")))
        eventually { cursor()?.second == 6L }
        fake.onCall = { _, _ ->
            // snapshot taken at cursor 6; an update of x is applied before the page is stored
            runBlocking {
                fake.send(msgChange(7, msg("x", 6, "v2 final")))
                eventually { cursor()?.second == 7L }
            }
            page(false, msg("x", 6, "v1 trunc"))
        }
        repo.ensureHistory(M, "claude:s1")
        assertEquals(listOf("v2 final"), repo.messages(M, "claude:s1").first().map { it.text })
    }

    @Test
    fun resyncMarksShownChatStaleUntilRefetched() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old")) }
        assertEquals(ChatState.Stale, repo.chatState(M, "claude:s1").first())
        val shown = launchCollect(repo.messages(M, "claude:s1"))
        repo.ensureHistory(M, "claude:s1")
        assertEquals(ChatState.Fresh, repo.chatState(M, "claude:s1").first())

        val release = java.util.concurrent.CountDownLatch(1)
        fake.onCall = { _, _ -> release.await(); page(false, msg("a", 1, "old"), msg("b", 60, "new")) }
        fake.send(Welcome(1, "srv", 60, resync = true, herdrVersion = "x"), StateMsg(60, baseState), Synced(60))
        eventually { repo.chatState(M, "claude:s1").first() == ChatState.Loading }
        assertEquals(listOf("a"), repo.messages(M, "claude:s1").first().map { it.id })
        release.countDown()
        eventually { repo.chatState(M, "claude:s1").first() == ChatState.Fresh }
        assertEquals(listOf("a", "b"), repo.messages(M, "claude:s1").first().map { it.id })
        shown.cancel()
    }

    @Test
    fun failedRefetchIsErrorAndRetriedOnSynced() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, _ -> page(false, msg("a", 1, "old")) }
        val shown = launchCollect(repo.messages(M, "claude:s1"))
        repo.ensureHistory(M, "claude:s1")

        fake.onCall = { _, _ -> throw BridgeError("timeout", "slow") }
        fake.send(Welcome(1, "srv", 60, resync = true, herdrVersion = "x"), StateMsg(60, baseState), Synced(60))
        eventually { repo.chatState(M, "claude:s1").first() == ChatState.Error("timeout") }

        fake.onCall = { _, _ -> page(false, msg("a", 1, "old"), msg("b", 60, "new")) }
        fake.connection.value = ConnState.Synced
        eventually { repo.chatState(M, "claude:s1").first() == ChatState.Fresh }
        assertEquals(listOf("a", "b"), repo.messages(M, "claude:s1").first().map { it.id })
        shown.cancel()
    }

    @Test
    fun syncedSeqMismatchReconnects() = runBlocking<Unit> {
        baseline()
        fake.send(upsert(6, "workspace", ws("w6", 6)))
        eventually { cursor()?.second == 6L }
        // resumed at 6 but the bridge claims head 8 without sending 7, 8
        fake.send(Welcome(1, "srv", 8, resync = false, herdrVersion = "x"), Synced(8))
        eventually { fake.reconnects.get() == 1 }
        assertEquals("srv" to 6L, cursor())
    }

    private fun <T> CoroutineScope.launchCollect(f: Flow<T>) = launch { f.collect {} }

    @Test
    fun messageUpdateReplacesById() = runBlocking<Unit> {
        baseline()
        fake.send(
            msgChange(6, msg("m1", 6, "hel")),
            msgChange(7, msg("m2", 7, "second")),
            msgChange(8, msg("m1", 6, "hello")),
            msgChange(9, msg("x", 9, "other chat", sk = "claude:s2")),
        )
        eventually { cursor()?.second == 9L }
        val ms = repo.messages(M, "claude:s1").first()
        assertEquals(listOf("m1" to "hello", "m2" to "second"), ms.map { it.id to it.text })
        assertEquals(listOf(6L, 7L), ms.map { it.seq })
    }

    @Test
    fun historyPaginates() = runBlocking<Unit> {
        baseline()
        fake.onCall = { _, p ->
            if (p["before_seq"] == null) page(true, msg("m3", 3, "c"), msg("m4", 4, "d"))
            else page(false, msg("m1", 1, "a"), msg("m2", 2, "b"))
        }
        repo.ensureHistory(M, "claude:s1")
        assertEquals(listOf("m3", "m4"), repo.messages(M, "claude:s1").first().map { it.id })
        repo.loadOlder(M, "claude:s1")
        val (method, params) = fake.calls[1]
        assertEquals("chat.history", method)
        assertEquals("claude:s1", params["session_key"]!!.jsonPrimitive.content)
        assertEquals(3L, params["before_seq"]!!.jsonPrimitive.long)
        assertEquals(listOf("m1", "m2", "m3", "m4"), repo.messages(M, "claude:s1").first().map { it.id })
        repo.loadOlder(M, "claude:s1") // more=false: nothing to fetch
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun orphansAreStoredButHidden() = runBlocking<Unit> {
        baseline()
        fake.send(
            remove(6, "tab", "t1"), // tab removed before its panes
            upsert(7, "pane", pane("p5", "t5")), // pane of a tab not known yet
        )
        eventually { cursor()?.second == 7L }
        assertTrue(repo.panes(M, "t1").first().isEmpty())
        assertNull(repo.pane(M, "p1").first())
        assertTrue(repo.panes(M, "t5").first().isEmpty())

        fake.send(remove(8, "pane", "p1"), remove(9, "pane", "p2"), upsert(10, "tab", tab("t5", "w1", 2)))
        eventually { cursor()?.second == 10L }
        assertEquals(listOf("p5"), repo.panes(M, "t5").first().map { it.id })
        assertEquals(listOf("t5"), repo.tabs(M, "w1").first().map { it.id })
    }

    @Test
    fun statusChangeUpdatesPaneInEitherOrder() = runBlocking<Unit> {
        baseline()
        fake.send(status(6, "p1", "done"))
        eventually { cursor()?.second == 6L }
        val p = repo.pane(M, "p1").first()!!
        assertEquals(AgentStatus.DONE, p.agentStatus)
        assertTrue(p.agentStatus.settled)
        assertTrue(AgentStatus.IDLE.settled)
        assertFalse(AgentStatus.WORKING.settled)

        // status for a pane not known yet, then its upsert
        fake.send(status(7, "p7", "working"), upsert(8, "pane", pane("p7", "t1", status = "working")), status(9, "p7", "idle"))
        eventually { cursor()?.second == 9L }
        assertEquals(AgentStatus.IDLE, repo.pane(M, "p7").first()!!.agentStatus)
    }

    @Test
    fun liveAndConnState() = runBlocking<Unit> {
        val live = repo.live(M, "p1")
        assertNull(live.value)
        fake.ev.emit(Live("p1", "thinking…", "working"))
        eventually { live.value == "thinking…" }
        fake.ev.emit(LiveClear("p1"))
        eventually { live.value == null }

        assertEquals(ConnState.Offline, repo.connState(M).value)
        fake.connection.value = ConnState.Synced
        eventually { repo.connState(M).value == ConnState.Synced }
    }

    @Test
    fun cmdGoesThroughClient() = runBlocking<Unit> {
        fake.onCall = { _, _ -> buildJsonObject { put("ok", true) } }
        val r = repo.cmd(M, "tab.create", buildJsonObject { put("workspace_id", "w1") })
        assertEquals("true", r.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("tab.create", fake.calls.single().first)
    }

    // ---- I2: applied-state banner, batching, lazy input ----

    private fun wsChanges(seqs: LongRange) = seqs.map { upsert(it, "workspace", ws("w$it", it.toInt())) }.toTypedArray()

    @Test
    fun connStateStaysSyncingUntilBacklogApplied() = runBlocking<Unit> {
        baseline()
        val gate = CountDownLatch(1)
        hook = { if (it is Change && it.seq == 6L) gate.await(10, TimeUnit.SECONDS) }
        fake.send(Welcome(1, "srv", 505, resync = false, herdrVersion = "x"))
        fake.send(*wsChanges(6L..505L))
        fake.send(Synced(505))
        fake.connection.value = ConnState.Synced // the client got `synced`; the app has not applied the backlog
        delay(300)
        assertEquals(ConnState.Syncing, repo.connState(M).value)
        gate.countDown()
        eventually { repo.connState(M).value == ConnState.Synced }
        assertEquals("srv" to 505L, cursor())
        assertEquals(501, repo.workspaces(M).first().size)
    }

    @Test
    fun backlogAppliesInFewTransactions() = runBlocking<Unit> {
        baseline()
        val gate = CountDownLatch(1)
        hook = { if (it is Change && it.seq == 6L) gate.await(10, TimeUnit.SECONDS) }
        fake.send(Welcome(1, "srv", 505, resync = false, herdrVersion = "x"))
        val before = repo.applyTransactions.get()
        fake.send(*wsChanges(6L..505L))
        delay(100)
        gate.countDown()
        eventually { cursor()?.second == 505L }
        val txns = repo.applyTransactions.get() - before
        assertTrue("500 changes took $txns transactions", txns <= 5)
        assertEquals(501, repo.workspaces(M).first().size)
    }

    @Test
    fun gapMidBatchAppliesPrefixThenReconnects() = runBlocking<Unit> {
        baseline()
        val gate = CountDownLatch(1)
        hook = { if (it is Change && it.seq == 6L) gate.await(10, TimeUnit.SECONDS) }
        fake.send(Welcome(1, "srv", 150, resync = false, herdrVersion = "x"))
        fake.send(*wsChanges(6L..100L), *wsChanges(102L..150L)) // 101 missing
        delay(100)
        gate.countDown()
        eventually { fake.reconnects.get() == 1 }
        delay(100)
        assertEquals("srv" to 100L, cursor())
        assertEquals(96, repo.workspaces(M).first().size)
        assertEquals(1, fake.reconnects.get())
    }

    @Test
    fun messagesKeepRawInputUntilAsked() = runBlocking<Unit> {
        baseline()
        val call = WMessage("c", 6, "claude:s1", "2026-11-08T00:00:00Z", "tool_call", "", "Bash", "t1",
            buildJsonObject { put("command", "ls") })
        fake.send(msgChange(6, call))
        eventually { cursor()?.second == 6L }
        val m = repo.messages(M, "claude:s1").first().single()
        assertNull("decoded eagerly", m.input)
        assertEquals("""{"command":"ls"}""", m.inputJson)
        assertEquals("ls", m.toolInput!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    /** C1: the real bridge history page with a truncated input is accepted (it used to fail as "internal"). */
    @Test
    fun historyWithTruncatedInputFixtureLoads() = runBlocking<Unit> {
        baseline()
        val fx = WireJson.parseToJsonElement(javaClass.getResource("/truncated_tool_call.json")!!.readText()).jsonObject
        val result = fx["history"]!!.jsonObject["result"]!!
        fake.onCall = { _, _ -> result }
        repo.ensureHistory(M, "claude:s1")
        val m = repo.messages(M, "claude:s1").first().single()
        assertEquals("toolu_big", m.toolCallId)
        assertTrue(m.toolInput!!.jsonObject.containsKey("_truncated"))
    }
}
