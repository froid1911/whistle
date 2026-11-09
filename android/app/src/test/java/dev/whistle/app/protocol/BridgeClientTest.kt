package dev.whistle.app.protocol

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BridgeClientTest {

    /** Server side of one accepted WebSocket. */
    class ServerConn {
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val opened = kotlinx.coroutines.CompletableDeferred<WebSocket>()
        @Volatile var socket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                opened.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.trySend(WireJson.parseToJsonElement(text).jsonObject)
            }
        }

        suspend fun next(): JsonObject = withTimeout(5_000) { received.receive() }
        suspend fun ws(): WebSocket = withTimeout(5_000) { opened.await() }
        suspend fun send(json: String) {
            check(ws().send(json))
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val cursor = InMemoryCursorStore()
    private val okHttp = OkHttpClient()
    private var client: BridgeClient? = null
    private val conns = mutableListOf<ServerConn>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        client?.stop()
        scope.cancel()
        // Server-side sockets must be closed or MockWebServer.close() waits for them.
        for (c in conns) runCatching { c.socket?.close(1001, null) }
        server.close()
    }

    private fun accept(): ServerConn {
        val c = ServerConn().also { conns += it }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(c.listener).build())
        return c
    }

    private val machine get() = Machine("m1", "test", "http://127.0.0.1:${server.port}")

    private fun newClient(config: BridgeClient.Config = BridgeClient.Config(minBackoffMs = 50, maxBackoffMs = 200)) =
        BridgeClient(machine, okHttp, cursor, scope, config).also { client = it }

    private suspend fun ServerConn.handshake(serverId: String = "srv", head: Long = 0, herdrVersion: String = "0.9.5") {
        assertEquals("hello", next()["t"]!!.jsonPrimitive.content)
        send("""{"t":"welcome","protocol":1,"server_id":"$serverId","head_seq":$head,"resync":false,"herdr_version":"$herdrVersion"}""")
        send("""{"t":"synced","seq":$head}""")
    }

    private suspend fun awaitConn(c: BridgeClient, state: ConnState) =
        withTimeout(5_000) { c.connection.first { it == state } }

    @Test
    fun sendsHelloWithStoredCursor() = runBlocking<Unit> {
        cursor.set("m1", "srv-7", 42)
        val conn = accept()
        newClient().start()

        val hello = conn.next()
        assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
        assertEquals("srv-7", hello["server_id"]!!.jsonPrimitive.content)
        assertEquals(42L, hello["since"]!!.jsonPrimitive.long)
        assertEquals("android/1", hello["client"]!!.jsonPrimitive.content)
    }

    @Test
    fun helloWithEmptyCursorAndNoOriginHeader() = runBlocking<Unit> {
        val conn = accept()
        newClient().start()
        val hello = conn.next()
        assertEquals("", hello["server_id"]!!.jsonPrimitive.content)
        assertEquals(0L, hello["since"]!!.jsonPrimitive.long)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull("bridge refuses requests carrying Origin", req.headers["Origin"])
        assertEquals("127.0.0.1:${server.port}", req.headers["Host"])
        assertEquals("/v1/ws", req.url.encodedPath)
    }

    @Test
    fun resumeTest() = runBlocking<Unit> {
        val c = newClient()
        // The "repository": applies changes and only then advances the cursor.
        scope.launch {
            c.syncEvents.collect { m -> if (m is Change) cursor.set("m1", "srv", m.seq) }
        }
        val first = accept()
        c.start()
        assertEquals(0L, first.next()["since"]!!.jsonPrimitive.long)
        first.send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":6,"resync":true,"herdr_version":"0.9.5"}""")
        first.send("""{"t":"state","seq":4,"state":{"workspaces":[],"tabs":[],"panes":[],"layouts":[]}}""")
        first.send("""{"t":"synced","seq":4}""")
        awaitConn(c, ConnState.Synced)
        assertEquals("synced counted before Synced is reported", 1L, c.syncedFrames.value)
        first.send("""{"t":"change","seq":5,"kind":"status","pane_id":"w1:p1","from":"idle","to":"working","at":"2026-11-08T10:00:00Z"}""")
        first.send("""{"t":"change","seq":6,"kind":"status","pane_id":"w1:p1","from":"working","to":"done","at":"2026-11-08T10:00:05Z"}""")
        withTimeout(5_000) { while (cursor.get("m1")?.second != 6L) delay(10) }

        val second = accept()
        first.ws().close(1001, "going away") // drop the connection
        val hello = second.next()
        assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
        assertEquals("srv", hello["server_id"]!!.jsonPrimitive.content)
        assertEquals(6L, hello["since"]!!.jsonPrimitive.long)
    }

    @Test
    fun clientNeverAdvancesCursorItself() = runBlocking<Unit> {
        val c = newClient()
        val first = accept()
        c.start()
        first.handshake(head = 0)
        first.send("""{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""")
        awaitConn(c, ConnState.Synced)
        val second = accept()
        first.ws().close(1013, "resync")
        val hello = second.next()
        assertEquals("nothing applied, nothing advanced", 0L, hello["since"]!!.jsonPrimitive.long)
        assertNull(cursor.get("m1"))
    }

    @Test
    fun reconnectsAfterResyncClose() = runBlocking<Unit> {
        cursor.set("m1", "srv", 3)
        val c = newClient()
        val first = accept()
        c.start()
        first.handshake(head = 3)
        awaitConn(c, ConnState.Synced)
        val second = accept()
        first.ws().close(1013, "resync")
        assertEquals(3L, second.next()["since"]!!.jsonPrimitive.long)
    }

    @Test
    fun callMapsErrorCode() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        conn.handshake()
        awaitConn(c, ConnState.Synced)

        val result = async {
            runCatching { c.call("agent.prompt", buildJsonObject { put("pane_id", "w1:p1"); put("text", "hi") }) }
        }
        val cmd = conn.next()
        assertEquals("cmd", cmd["t"]!!.jsonPrimitive.content)
        assertEquals("agent.prompt", cmd["method"]!!.jsonPrimitive.content)
        assertEquals("hi", cmd["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        val id = cmd["id"]!!.jsonPrimitive.content
        conn.send("""{"t":"res","id":"$id","error":{"code":"agent_blocked","message":"agent is waiting for approval"}}""")

        val err = result.await().exceptionOrNull()
        if (err !is BridgeError) fail("expected BridgeError, got $err")
        err as BridgeError
        assertEquals("agent_blocked", err.code)
        assertEquals("agent is waiting for approval", err.message)
    }

    @Test
    fun callReturnsResult() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        conn.handshake()
        awaitConn(c, ConnState.Synced)
        val result = async { c.call("agent.screen", buildJsonObject { put("pane_id", "w1:p1") }) }
        val id = conn.next()["id"]!!.jsonPrimitive.content
        conn.send("""{"t":"res","id":"$id","result":{"text":"screen"}}""")
        assertEquals("screen", result.await().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun callWhileOfflineFails() = runBlocking<Unit> {
        val c = newClient()
        val err = runCatching { c.call("agent.screen", JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue("got $err", err is BridgeError && err.code == BridgeError.OFFLINE)
    }

    @Test
    fun pendingCallFailsOnDisconnect() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        conn.handshake()
        awaitConn(c, ConnState.Synced)
        val result = async { runCatching { c.call("agent.screen", JsonObject(emptyMap())) } }
        conn.next()
        accept()
        conn.ws().close(1001, "going away")
        val err = withTimeout(5_000) { result.await() }.exceptionOrNull()
        assertTrue("got $err", err is BridgeError && err.code == BridgeError.DISCONNECTED)
    }

    @Test
    fun handshakeSilenceTriggersReconnect() = runBlocking<Unit> {
        val silent = accept()
        val second = accept()
        newClient(BridgeClient.Config(minBackoffMs = 50, handshakeTimeoutMs = 300)).start()
        assertEquals("hello", silent.next()["t"]!!.jsonPrimitive.content)
        // never answer: the client must give up and reconnect
        assertEquals("hello", second.next()["t"]!!.jsonPrimitive.content)
    }

    @Test
    fun watchesAreSentAndRestoredOnReconnect() = runBlocking<Unit> {
        val c = newClient()
        c.watch("w1:p1")
        val first = accept()
        c.start()
        assertEquals("hello", first.next()["t"]!!.jsonPrimitive.content)
        // Watches are sent only once the connection's welcome was accepted.
        first.handshake2()
        val w = first.next()
        assertEquals("watch", w["t"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", w["pane_id"]!!.jsonPrimitive.content)
        awaitConn(c, ConnState.Synced)
        c.unwatch("w1:p1")
        assertEquals("unwatch", first.next()["t"]!!.jsonPrimitive.content)
        c.watch("w1:p2")
        assertEquals("watch", first.next()["t"]!!.jsonPrimitive.content)

        val second = accept()
        first.ws().close(1001, "going away")
        assertEquals("hello", second.next()["t"]!!.jsonPrimitive.content)
        second.handshake2()
        val w2 = second.next()
        assertEquals("watch", w2["t"]!!.jsonPrimitive.content)
        assertEquals("w1:p2", w2["pane_id"]!!.jsonPrimitive.content)
    }

    private suspend fun ServerConn.handshake2() {
        send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":0,"resync":false,"herdr_version":"0.9.5"}""")
        send("""{"t":"synced","seq":0}""")
    }

    @Test
    fun herdrOfflineWhenBridgeHasNoHerdr() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        conn.handshake(herdrVersion = "")
        awaitConn(c, ConnState.HerdrOffline)
        conn.send("""{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""")
        awaitConn(c, ConnState.Synced)
        val result = async { runCatching { c.call("agent.screen", JsonObject(emptyMap())) } }
        val id = conn.next()["id"]!!.jsonPrimitive.content
        conn.send("""{"t":"res","id":"$id","error":{"code":"herdr_unavailable","message":"dial: no socket"}}""")
        assertEquals("herdr_unavailable", (result.await().exceptionOrNull() as BridgeError).code)
        awaitConn(c, ConnState.HerdrOffline)
    }

    @Test
    fun herdrMessageOverridesHeuristic() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        assertEquals("hello", conn.next()["t"]!!.jsonPrimitive.content)
        // herdr_version is set (heuristic: online), but the explicit frame says offline.
        conn.send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":0,"resync":false,"herdr_version":"0.9.5"}""")
        conn.send("""{"t":"herdr","online":false}""")
        conn.send("""{"t":"synced","seq":0}""")
        awaitConn(c, ConnState.HerdrOffline)
        // A live change no longer flips it back: only a herdr frame does.
        conn.send("""{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""")
        conn.send("""{"t":"herdr","online":true}""")
        awaitConn(c, ConnState.Synced)
        conn.send("""{"t":"herdr","online":false}""")
        awaitConn(c, ConnState.HerdrOffline)
    }

    @Test
    fun herdrOnlineFrameIgnoresStaleErrorHeuristic() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        assertEquals("hello", conn.next()["t"]!!.jsonPrimitive.content)
        conn.send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":0,"resync":false,"herdr_version":""}""")
        conn.send("""{"t":"herdr","online":true}""")
        conn.send("""{"t":"synced","seq":0}""")
        awaitConn(c, ConnState.Synced)
        val result = async { runCatching { c.call("agent.screen", JsonObject(emptyMap())) } }
        val id = conn.next()["id"]!!.jsonPrimitive.content
        conn.send("""{"t":"res","id":"$id","error":{"code":"herdr_unavailable","message":"x"}}""")
        assertEquals("herdr_unavailable", (result.await().exceptionOrNull() as BridgeError).code)
        delay(100)
        assertEquals(ConnState.Synced, c.connection.value)
    }

    @Test
    fun eventsAreEmittedInOrderAndUnknownTypesSkipped() = runBlocking<Unit> {
        val c = newClient()
        val sync = Channel<ServerMsg>(Channel.UNLIMITED)
        val eph = Channel<ServerMsg>(Channel.UNLIMITED)
        scope.launch { c.syncEvents.collect { if (it !is ConnectionStarted) sync.send(it) } }
        scope.launch { c.events.collect { eph.send(it) } }
        withTimeout(5_000) { c.eventSubscribers.first { it > 0 } }
        val conn = accept()
        c.start()
        conn.handshake(head = 0)
        conn.send("""{"t":"brand.new","x":1}""")
        conn.send("""{"t":"live","pane_id":"p","text":"a","status":"working"}""")
        conn.send("""{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""")
        conn.send("""{"t":"live.clear","pane_id":"p"}""")
        val got = withTimeout(5_000) { List(3) { sync.receive() } }
        assertTrue(got[0] is Welcome)
        assertTrue(got[1] is Synced)
        assertEquals(1L, (got[2] as Change).seq)
        assertEquals(Live("p", "a", "working"), withTimeout(5_000) { eph.receive() })
        assertEquals(LiveClear("p"), withTimeout(5_000) { eph.receive() })
        assertTrue("connection survives an unknown t", c.connection.value == ConnState.Synced)
    }

    @Test
    fun syncFramesBeforeAnyCollectorAreNotLost() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start() // nobody collects yet
        assertEquals("hello", conn.next()["t"]!!.jsonPrimitive.content)
        conn.send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":6,"resync":true,"herdr_version":"0.9.5"}""")
        conn.send("""{"t":"state","seq":4,"state":{"workspaces":[],"tabs":[],"panes":[],"layouts":[]}}""")
        conn.send("""{"t":"synced","seq":4}""")
        conn.send("""{"t":"change","seq":5,"kind":"remove","entity":"pane","id":"a"}""")
        conn.send("""{"t":"change","seq":6,"kind":"remove","entity":"pane","id":"b"}""")
        awaitConn(c, ConnState.Synced)
        delay(300) // let the changes arrive with no collector

        val got = withTimeout(5_000) { c.syncEvents.filter { it !is ConnectionStarted }.take(5).toList() }
        assertTrue(got[0] is Welcome)
        assertEquals(4L, (got[1] as StateMsg).seq)
        assertEquals(Synced(4), got[2])
        assertEquals(listOf(5L, 6L), got.drop(3).map { (it as Change).seq })
    }

    @Test
    fun malformedKnownFrameDropsConnectionWithoutDeliveringLaterFrames() = runBlocking<Unit> {
        val c = newClient()
        val applied = java.util.Collections.synchronizedList(mutableListOf<Long>())
        scope.launch {
            c.syncEvents.collect { m ->
                if (m is Change) {
                    applied += m.seq
                    cursor.set("m1", "srv", m.seq)
                }
            }
        }
        val first = accept()
        val second = accept()
        c.start()
        first.handshake(head = 4)
        first.send("""{"t":"change","seq":5,"kind":"remove","entity":"pane","id":"a"}""")
        first.send("""{"t":"change","kind":"status","pane_id":"w1:p1"}""") // known t, no seq
        first.send("""{"t":"change","seq":7,"kind":"remove","entity":"pane","id":"c"}""")

        val hello = second.next()
        assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
        assertEquals(5L, hello["since"]!!.jsonPrimitive.long)
        delay(200)
        assertEquals(listOf(5L), applied.toList())
    }

    @Test
    fun connectionStartsWithMarkerAndRequiresWelcomeFirst() = runBlocking<Unit> {
        val c = newClient()
        val sync = Channel<ServerMsg>(Channel.UNLIMITED)
        scope.launch { c.syncEvents.collect { sync.send(it) } }
        val first = accept()
        val second = accept()
        c.start()
        assertEquals(ConnectionStarted, withTimeout(5_000) { sync.receive() })
        assertEquals("hello", first.next()["t"]!!.jsonPrimitive.content)
        // Legacy (unpaired) machine: a non-welcome first frame is a protocol error -> reconnect, nothing delivered.
        first.send("""{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""")
        assertEquals("hello", second.next()["t"]!!.jsonPrimitive.content)
        assertEquals(ConnectionStarted, withTimeout(5_000) { sync.receive() })
        assertNull(c.authProblem.value)
        second.handshake2()
        assertTrue(withTimeout(5_000) { sync.receive() } is Welcome)
        withTimeout(5_000) { c.sessionReady.first { it } }
    }

    @Test
    fun stopGoesOffline() = runBlocking<Unit> {
        val c = newClient()
        val conn = accept()
        c.start()
        conn.handshake()
        awaitConn(c, ConnState.Synced)
        c.stop()
        assertEquals(ConnState.Offline, c.connection.value)
    }
}
