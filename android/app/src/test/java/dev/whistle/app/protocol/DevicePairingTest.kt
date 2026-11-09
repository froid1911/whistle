package dev.whistle.app.protocol

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Pairing invites, the pairing request, and how the client treats signed connections and 401s. */
class DevicePairingTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val okHttp = OkHttpClient()
    private val keys = SoftwareDeviceKeys()
    private var client: BridgeClient? = null
    private val sockets = mutableListOf<WebSocket>()

    // The bridge identity: a P-256 key the test signs welcomes with.
    private val bridge = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val bridgeKeyB64 = Base64.getEncoder().encodeToString(bridge.public.encoded)
    private val fp = WhistleAuth.fingerprint(bridge.public.encoded)

    /** Answers `GET /v1/challenge` itself (fresh challenge, or [challengeReply]); everything else from the queue. */
    private inner class ChallengeDispatcher : QueueDispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.url.encodedPath == "/v1/challenge") {
                challengeRequests += request
                challengeReply?.let { return it }
                val ch = WhistleAuth.b64url(ByteArray(16).also(java.security.SecureRandom()::nextBytes))
                issued += request.url.queryParameter("device") to ch
                return MockResponse.Builder().body("""{"challenge":"$ch","expires_in":30}""").build()
            }
            other.incrementAndGet()
            return super.dispatch(request)
        }
    }

    private val challengeRequests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val issued = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String>>()
    @Volatile private var challengeReply: MockResponse? = null

    /** Requests other than `/v1/challenge`. */
    private val other = java.util.concurrent.atomic.AtomicInteger()

    /** The next request that is not a challenge fetch. */
    private fun takeRequest(): RecordedRequest {
        while (true) {
            val r = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")
            if (r.url.encodedPath != "/v1/challenge") return r
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = ChallengeDispatcher()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        client?.stop()
        scope.cancel()
        sockets.forEach { runCatching { it.close(1001, null) } }
        server.close()
    }

    // ---- invites ---------------------------------------------------------

    private val token = "YYHQ7CZP3HHWAWWQO72E5EIULY"
    private fun link(v: String = "1", u: String? = "100.64.0.10%3A8787", t: String? = token, f: String? = fp, n: String? = "myserver") =
        "whistle://pair?" + listOfNotNull("v=$v", u?.let { "u=$it" }, t?.let { "t=$it" }, f?.let { "f=$it" }, n?.let { "n=$it" }).joinToString("&")

    @Test
    fun parsesInvite() {
        val i = PairInvites.parse(link())
        assertEquals(PairInvite("100.64.0.10:8787", token, fp, "myserver"), i)
        assertEquals("http://100.64.0.10:8787", i.baseUrl)
        assertEquals("myserver box", PairInvites.parse(link(n = "myserver+box")).name)
        assertEquals("100.64.0.10", PairInvites.parse(link(n = null)).displayName)
    }

    @Test
    fun rejectsBadInvites() {
        val bad = mapOf(
            link(v = "2") to "newer Whistle",
            link(u = null) to "no address",
            link(u = "myserver.example%3A8787") to "IP address",
            link(u = "100.64.0.10") to "port",
            link(t = null) to "no valid code",
            link(t = "ABC") to "no valid code",
            link(f = null) to "fingerprint",
            link(f = "short") to "fingerprint",
            "https://example.com/pair?v=1" to "Not a Whistle",
            "whistle://pair" to "no version",
        )
        for ((text, why) in bad) {
            try {
                PairInvites.parse(text)
                fail("accepted $text")
            } catch (e: PairInputException) {
                assertTrue("$text: ${e.message}", e.message!!.contains(why))
            }
        }
    }

    @Test
    fun manualEntry() {
        val i = PairInvites.fromInput(" http://100.64.0.10:8787/ ", "yyhq-7czp-3hhw-awwq-o72e-5eiu-ly")
        assertEquals(PairInvite("100.64.0.10:8787", token, null, null), i)
        assertEquals(PairInvites.parse(link()), PairInvites.fromInput(link(), ""))
        try {
            PairInvites.manual("100.64.0.10:8787", "ABCD")
            fail()
        } catch (_: PairInputException) {
        }
    }

    // ---- pairing request ---------------------------------------------------

    private val addr get() = "127.0.0.1:${server.port}"

    private fun pairReply(key: String = bridgeKeyB64) = MockResponse.Builder()
        .body("""{"device_id":"d42","bridge_public_key":"$key","server_id":"srv"}""").build()

    @Test
    fun pairsAndPinsBridgeKey() = runBlocking<Unit> {
        server.enqueue(pairReply())
        val r = Pairer(okHttp, keys).pair(PairInvite(addr, token, fp, "myserver"), "m1", "Pixel 8a")
        assertEquals("d42", r.deviceId)
        assertEquals(bridgeKeyB64, r.bridgeKey)
        assertEquals("Pixel 8a", r.deviceName)
        assertTrue(keys.has(r.keyAlias))
        val req = takeRequest()
        assertEquals("/v1/pair", req.url.encodedPath)
        assertNull(req.headers["Origin"])
        val body = WireJson.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals(token, body["token"]!!.jsonPrimitive.content)
        assertEquals("Pixel 8a", body["device_name"]!!.jsonPrimitive.content)
        // The registered public key is the one the alias signs with.
        val spki = Base64.getDecoder().decode(body["public_key"]!!.jsonPrimitive.content)
        assertTrue(WhistleAuth.verify(spki, "x", WhistleAuth.b64url(keys.sign(r.keyAlias, "x".toByteArray()))))
    }

    @Test
    fun refusesBridgeKeyThatDoesNotMatchQr() = runBlocking<Unit> {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        server.enqueue(pairReply(Base64.getEncoder().encodeToString(other.public.encoded)))
        try {
            Pairer(okHttp, keys).pair(PairInvite(addr, token, fp, "myserver"), "m1", "Pixel")
            fail("must refuse")
        } catch (e: PairException) {
            assertTrue(e.message!!, e.message!!.contains("doesn't look like your myserver bridge"))
        }
        assertTrue("the new key is deleted again", keys.pairs.isEmpty())
    }

    @Test
    fun mapsPairingErrors() = runBlocking<Unit> {
        for ((code, want) in listOf(403 to "expired or already used", 429 to "Wait a minute")) {
            server.enqueue(MockResponse.Builder().code(code).body("""{"error":{"code":"x","message":"y"}}""").build())
            try {
                Pairer(okHttp, keys).pair(PairInvite(addr, token, fp, "myserver"), "m1", "Pixel")
                fail()
            } catch (e: PairException) {
                assertTrue(e.message!!, e.message!!.contains(want))
            }
        }
        assertTrue(keys.pairs.isEmpty())
    }

    // ---- signed connections --------------------------------------------------

    private fun pairedMachine(): Machine {
        keys.generate("k")
        return Machine("m1", "myserver", "http://$addr", deviceId = "d42", keyAlias = "k", bridgeKey = bridgeKeyB64, deviceName = "Pixel")
    }

    private fun newClient(m: Machine) = BridgeClient(
        m, okHttp, InMemoryCursorStore(), scope, BridgeClient.Config(minBackoffMs = 50, maxBackoffMs = 100), RequestSigner(keys),
    ).also { client = it }

    /**
     * Accepts one WS; answers hello with [before] frames, then (unless [sign] is null and
     * [welcome] false) a welcome whose sig [sign] computes from the request's nonce, then [after].
     */
    private fun acceptSigned(
        before: List<String> = emptyList(),
        after: List<String> = listOf("""{"t":"synced","seq":0}"""),
        sign: (nonce: String) -> String?,
    ): Channel<String> {
        val received = Channel<String>(Channel.UNLIMITED)
        val nonce = java.util.concurrent.atomic.AtomicReference("")
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets += webSocket
                // The upgrade request as the server received it.
                nonce.set((response.request.header("Authorization") ?: "").substringAfter("nonce=").substringBefore(","))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                received.trySend(text)
                if (!text.contains("\"hello\"")) return
                before.forEach { webSocket.send(it) }
                val sig = sign(nonce.get())?.let { ""","sig":"$it"""" } ?: ""
                webSocket.send("""{"t":"welcome","protocol":1,"server_id":"srv","head_seq":0,"resync":false,"herdr_version":"0.9.5"$sig}""")
                after.forEach { webSocket.send(it) }
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        return received
    }

    private fun bridgeSign(msg: String): String = WhistleAuth.b64url(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(bridge.private)
            update(msg.toByteArray())
            sign()
        },
    )

    private fun parts(auth: String) =
        auth.removePrefix("Whistle v2 ").split(", ").associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun signedConnectionVerifiesWelcome() = runBlocking<Unit> {
        val m = pairedMachine()
        val hellos = acceptSigned { n -> bridgeSign(WhistleAuth.welcomeMessage("srv", n)) }
        val c = newClient(m)
        c.start()
        withTimeout(5_000) { hellos.receive() }
        withTimeout(5_000) { c.connection.first { it == ConnState.Synced } }
        assertNull(c.authProblem.value)
        assertTrue(c.sessionReady.value)
        // The upgrade was signed (v2) with the challenge the bridge issued for this device.
        val ch = challengeRequests.single()
        assertEquals("d42", ch.url.queryParameter("device"))
        assertNull(ch.headers["Authorization"])
        assertNull(ch.headers["Origin"])
        val up = takeRequest()
        val p = parts(up.headers["Authorization"]!!)
        assertEquals(issued.single().second, p["challenge"])
        assertEquals(WhistleAuth.EMPTY_BODY_HASH, p["body"])
        val msg = WhistleAuth.canonical("GET", "/v1/ws", up.headers["Host"]!!, p["ts"]!!.toLong(), p["nonce"]!!, p["body"]!!, p["challenge"]!!, "d42")
        assertTrue(WhistleAuth.verify(keys.pairs["k"]!!.public.encoded, msg, p["sig"]!!))
    }

    @Test
    fun framesBeforeWelcomeAreRefusedAndNothingIsSent() = runBlocking<Unit> {
        val m = pairedMachine()
        val received = acceptSigned(
            before = listOf(
                """{"t":"state","seq":3,"state":{"workspaces":[],"tabs":[],"panes":[],"layouts":[]}}""",
                """{"t":"change","seq":1,"kind":"remove","entity":"pane","id":"x"}""",
                """{"t":"res","id":"c1","result":{}}""",
                """{"t":"live","pane_id":"w1:p1","text":"x"}""",
            ),
        ) { n -> bridgeSign(WhistleAuth.welcomeMessage("srv", n)) } // a valid welcome, but too late
        val c = newClient(m)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerMsg>()
        val eph = java.util.concurrent.CopyOnWriteArrayList<ServerMsg>()
        val collectors = listOf(
            scope.launch { c.syncEvents.collect { frames += it } },
            scope.launch { c.events.collect { eph += it } },
        )
        withTimeout(5_000) { c.eventSubscribers.first { it > 0 } }
        c.watch("w1:p1")
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.BridgeMismatch, c.authProblem.value)
        assertEquals("This doesn't look like your myserver bridge. Whistle refused to connect.", c.authProblem.value!!.message("myserver"))
        delay(300)
        assertEquals("nothing applied", listOf<ServerMsg>(ConnectionStarted), frames.toList())
        assertTrue("no ephemeral frame", eph.isEmpty())
        assertTrue(withTimeout(5_000) { received.receive() }.contains("\"hello\""))
        assertTrue("nothing but the hello was sent", received.tryReceive().isFailure)
        assertFalse(c.sessionReady.value)
        assertFalse(c.send(TermInput("s1", text = "rm -rf /")))
        try {
            c.call("pane.send_text", kotlinx.serialization.json.JsonObject(emptyMap()))
            fail()
        } catch (e: BridgeError) {
            assertEquals(BridgeError.OFFLINE, e.code)
        }
        assertEquals("no reconnect", 1, other.get())
        collectors.forEach { it.cancel() }
    }

    @Test
    fun validWelcomeThenFramesAreDelivered() = runBlocking<Unit> {
        val m = pairedMachine()
        val received = acceptSigned(
            after = listOf(
                """{"t":"state","seq":3,"state":{"workspaces":[],"tabs":[],"panes":[],"layouts":[]}}""",
                """{"t":"synced","seq":3}""",
                """{"t":"change","seq":4,"kind":"remove","entity":"pane","id":"x"}""",
                """{"t":"live","pane_id":"w1:p1","text":"x"}""",
            ),
        ) { n -> bridgeSign(WhistleAuth.welcomeMessage("srv", n)) }
        val c = newClient(m)
        val frames = Channel<ServerMsg>(Channel.UNLIMITED)
        val eph = Channel<ServerMsg>(Channel.UNLIMITED)
        val collectors = listOf(
            scope.launch { c.syncEvents.collect { frames.send(it) } },
            scope.launch { c.events.collect { eph.send(it) } },
        )
        withTimeout(5_000) { c.eventSubscribers.first { it > 0 } }
        c.watch("w1:p1")
        c.start()
        val got = withTimeout(5_000) { List(5) { frames.receive() } }
        assertEquals(ConnectionStarted, got[0])
        assertTrue(got[1] is Welcome)
        assertEquals(3L, (got[2] as StateMsg).seq)
        assertEquals(Synced(3), got[3])
        assertEquals(4L, (got[4] as Change).seq)
        assertEquals("w1:p1", (withTimeout(5_000) { eph.receive() } as Live).paneId)
        assertTrue(withTimeout(5_000) { received.receive() }.contains("\"hello\""))
        assertTrue("the watch follows the verified welcome", withTimeout(5_000) { received.receive() }.contains("\"watch\""))
        assertTrue(c.sessionReady.value)
        assertNull(c.authProblem.value)
        collectors.forEach { it.cancel() }
    }

    @Test
    fun wrongBridgeSignatureRefusesConnection() = runBlocking<Unit> {
        val m = pairedMachine()
        val impostor = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        acceptSigned { n ->
            WhistleAuth.b64url(Signature.getInstance("SHA256withECDSA").run {
                initSign(impostor.private)
                update(WhistleAuth.welcomeMessage("srv", n).toByteArray())
                sign()
            })
        }
        val c = newClient(m)
        val frames = java.util.concurrent.CopyOnWriteArrayList<ServerMsg>()
        val collector = scope.launch { c.syncEvents.collect { frames += it } }
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.BridgeMismatch, c.authProblem.value)
        delay(300) // no reconnect, nothing delivered to the cache
        assertEquals(1, other.get())
        assertEquals(listOf<ServerMsg>(ConnectionStarted), frames.toList())
        assertEquals(ConnState.Offline, c.connection.value)
        collector.cancel()
    }

    @Test
    fun missingWelcomeSignatureRefusesConnection() = runBlocking<Unit> {
        acceptSigned { null }
        val c = newClient(pairedMachine())
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.BridgeMismatch, c.authProblem.value)
    }

    @Test
    fun bridgeWithoutChallengeIsTooOld() = runBlocking<Unit> {
        challengeReply = MockResponse.Builder().code(404).body("404 page not found").build()
        val c = newClient(pairedMachine())
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.BridgeTooOld, c.authProblem.value)
        delay(300)
        assertEquals("no unsigned or v1 fallback", 0, other.get())
        assertEquals(1, challengeRequests.size)
    }

    @Test
    fun revokedDeviceStopsReconnecting() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"device_revoked","message":"x"}}""").build())
        val c = newClient(pairedMachine())
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.Revoked, c.authProblem.value)
        assertEquals("This phone was removed from myserver. Pair again.", c.authProblem.value!!.message("myserver"))
        delay(400)
        assertEquals("no reconnect after revocation", 1, other.get())
        assertTrue(takeRequest().headers["Authorization"]!!.startsWith("Whistle v2 device=d42, ts="))
    }

    @Test
    fun staleSignatureRetriesAtOnceThenBacksOff() = runBlocking<Unit> {
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"auth_stale","message":"x"}}""").build())
        }
        val c = BridgeClient(
            pairedMachine(), okHttp, InMemoryCursorStore(), scope, BridgeClient.Config(minBackoffMs = 1_000, maxBackoffMs = 1_000), RequestSigner(keys),
        ).also { client = it }
        c.start()
        withTimeout(900) { while (other.get() < 2) delay(10) } // the one immediate retry
        delay(300)
        assertEquals("then the normal backoff", 2, other.get())
        withTimeout(5_000) { while (other.get() < 3) delay(20) }
        assertNull(c.authProblem.value)
        // Every attempt used its own challenge.
        assertEquals(issued.size, issued.map { it.second }.toSet().size)
    }

    @Test
    fun unpairedMachineAgainstDevicesOnlyBridge() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"auth_required","message":"x"}}""").build())
        val c = newClient(Machine("m1", "myserver", "http://$addr"))
        c.start()
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.NotPaired, c.authProblem.value)
        assertNull("unpaired: unsigned", takeRequest().headers["Authorization"])
        assertTrue("unpaired: no challenge", challengeRequests.isEmpty())
    }

    @Test
    fun closeRevokedMidSession() = runBlocking<Unit> {
        val m = pairedMachine()
        val hellos = acceptSigned { n -> bridgeSign(WhistleAuth.welcomeMessage("srv", n)) }
        val c = newClient(m)
        c.start()
        withTimeout(5_000) { hellos.receive() }
        withTimeout(5_000) { c.connection.first { it == ConnState.Synced } }
        sockets.single().close(CLOSE_REVOKED, "device_revoked")
        withTimeout(5_000) { c.authProblem.first { it != null } }
        assertEquals(AuthProblem.Revoked, c.authProblem.value)
        assertFalse(c.sessionReady.value)
    }

    private fun verifyUploadSignature(req: RecordedRequest) {
        val p = parts(req.headers["Authorization"]!!)
        val target = req.url.encodedPath + "?" + req.url.encodedQuery
        val hash = WhistleAuth.sha256Hex(req.body!!.toByteArray())
        assertEquals("body= is the hash of the bytes actually sent", hash, p["body"])
        val msg = WhistleAuth.canonical("POST", target, req.headers["Host"]!!, p["ts"]!!.toLong(), p["nonce"]!!, hash, p["challenge"]!!, "d42")
        assertTrue("signature covers the bytes actually sent", WhistleAuth.verify(keys.pairs["k"]!!.public.encoded, msg, p["sig"]!!))
        assertTrue(issued.any { it.second == p["challenge"] })
    }

    @Test
    fun uploadIsSignedOverItsBody() = runBlocking<Unit> {
        val m = pairedMachine()
        server.enqueue(MockResponse.Builder().body("""{"path":"/tmp/x"}""").build())
        val data = "hello whistle".toByteArray()
        val path = Uploader(okHttp, RequestSigner(keys)) { true }.upload(m, "w1:p1", "a.txt", "text/plain", data.size.toLong()) { data.inputStream() }
        assertEquals("/tmp/x", path)
        verifyUploadSignature(takeRequest())
    }

    @Test
    fun uploadRetriesOnceOnStaleChallenge() = runBlocking<Unit> {
        val m = pairedMachine()
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"auth_stale","message":"x"}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"path":"/tmp/y"}""").build())
        val data = "hello".toByteArray()
        assertEquals("/tmp/y", Uploader(okHttp, RequestSigner(keys)).upload(m, "w1:p1", "a", null, data.size.toLong()) { data.inputStream() })
        val first = takeRequest()
        val second = takeRequest()
        verifyUploadSignature(second)
        assertTrue(parts(first.headers["Authorization"]!!)["challenge"] != parts(second.headers["Authorization"]!!)["challenge"])
        // Only once: a second auth_stale is an error.
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"auth_stale","message":"x"}}""").build())
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"auth_stale","message":"x"}}""").build())
        try {
            Uploader(okHttp, RequestSigner(keys)).upload(m, "w1:p1", "a", null, data.size.toLong()) { data.inputStream() }
            fail()
        } catch (e: UploadException) {
            assertTrue(e.message!!, e.message!!.contains("HTTP 401"))
        }
    }

    @Test
    fun uploadNeedsAWelcomedConnection() = runBlocking<Unit> {
        try {
            Uploader(okHttp, RequestSigner(keys)) { false }.upload(pairedMachine(), "w1:p1", "a", null, 1) { "x".byteInputStream() }
            fail()
        } catch (e: UploadException) {
            assertEquals("Not connected to myserver", e.message)
        }
        assertEquals("nothing sent", 0, other.get())
        assertTrue(challengeRequests.isEmpty())
    }

    @Test
    fun uploadToTooOldBridgeFails() = runBlocking<Unit> {
        challengeReply = MockResponse.Builder().code(404).build()
        try {
            Uploader(okHttp, RequestSigner(keys)).upload(pairedMachine(), "w1:p1", "a", null, 1) { "x".byteInputStream() }
            fail()
        } catch (e: UploadException) {
            assertTrue(e.message!!, e.message!!.contains("too old"))
        }
        assertEquals(0, other.get())
    }

    @Test
    fun uploadRevokedMessage() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"device_revoked","message":"x"}}""").build())
        try {
            Uploader(okHttp, RequestSigner(keys)).upload(pairedMachine(), "w1:p1", "a", null, 1) { "x".byteInputStream() }
            fail()
        } catch (e: UploadException) {
            assertEquals("This phone was removed from myserver. Pair again.", e.message)
        }
    }

    @Test
    fun unpairedUploadIsUnsigned() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"path":"/tmp/x"}""").build())
        Uploader(okHttp, RequestSigner(keys)).upload(Machine("m1", "myserver", "http://$addr"), "w1:p1", "a", null, 1) { "x".byteInputStream() }
        assertNull(takeRequest().headers["Authorization"])
        assertTrue(challengeRequests.isEmpty())
        assertFalse(keys.pairs.isNotEmpty())
    }

    @Test
    fun strongBoxFallbackCoversProviderAndKeystoreErrors() {
        val f = dev.whistle.app.security.KeystoreDeviceKeys.strongBoxFailure(java.lang.IllegalStateException())
        assertFalse(f)
        assertTrue(dev.whistle.app.security.KeystoreDeviceKeys.strongBoxFailure(java.security.ProviderException("Failed to generate key pair")))
        assertTrue(dev.whistle.app.security.KeystoreDeviceKeys.strongBoxFailure(RuntimeException(java.security.KeyStoreException("-68"))))
    }

    @Test
    fun authStatusLabels() {
        assertEquals("Paired as Pixel 8a · secure login", dev.whistle.app.ui.authStatusLabel(pairedMachine().copy(deviceName = "Pixel 8a")))
        assertEquals("Legacy login — pair this machine", dev.whistle.app.ui.authStatusLabel(Machine("m1", "myserver", "http://$addr")))
    }
}
