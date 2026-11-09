package dev.whistle.app.protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnState { Offline, Connecting, Syncing, Synced, HerdrOffline }

/** A `res` error from the bridge/herdr ([code] as on the wire), or a client-side failure ([OFFLINE] etc.). */
class BridgeError(val code: String, message: String) : Exception(message) {
    override fun toString() = "BridgeError($code: $message)"

    companion object {
        const val OFFLINE = "offline"
        const val DISCONNECTED = "disconnected"
        const val TIMEOUT = "client_timeout"
        const val HERDR_UNAVAILABLE = "herdr_unavailable"
    }
}

/**
 * The resume cursor: (server_id, last seq durably APPLIED by the app) per machine.
 * Only the code that applies [BridgeClient.syncEvents] may call [set]; the client only reads it.
 */
interface CursorStore {
    suspend fun get(machineId: String): Pair<String, Long>?
    suspend fun set(machineId: String, serverId: String, seq: Long)
}

class InMemoryCursorStore : CursorStore {
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    override suspend fun get(machineId: String) = map[machineId]
    override suspend fun set(machineId: String, serverId: String, seq: Long) {
        map[machineId] = serverId to seq
    }
}

/**
 * One WebSocket session to a bridge, reconnecting forever between [start] and [stop].
 *
 * - On every (re)connect the hello carries `CursorStore.get(machine.id)` read at that moment, so
 *   the client never resumes past what the app has applied. Replayed duplicates are harmless
 *   (upserts/messages are idempotent by id); skipping ahead would lose data.
 * - [syncEvents] carries the durable sync stream (welcome, state, synced, change) in wire order. It is
 *   backed by an unbounded queue, so frames received while nobody collects are kept, never dropped:
 *   collect it from exactly ONE applier for the client's lifetime (each frame goes to one collector).
 *   A collector that is cancelled and restarted resumes with the next undelivered frame; a frame
 *   handed to an applier that then crashes before applying it is lost from the queue, so such an
 *   applier must call [reconnect] (the new hello resumes from the cursor it last stored).
 * - [events] carries ephemeral frames (herdr, live, live.clear, res, term.*). It is a SharedFlow without
 *   replay: frames with no subscriber are dropped (they are never replayed by the bridge either).
 *   Ordering between the two streams is not guaranteed (live.clear may be seen before the change
 *   that preceded it on the wire is applied).
 * - A known `t` that fails to decode is a protocol error: the client drops the connection without
 *   delivering any later frame of it and reconnects, so the cursor can never skip that frame.
 *   Unknown `t` values are ignored.
 * - Close 1013 ("resync") is handled like any other drop; the next hello gets `resync=true`.
 * - Liveness: the bridge pings every 15 s, but OkHttp answers pings internally and never surfaces
 *   ping/pong frames, so received-frame silence is not observable. Instead the client pings too
 *   (OkHttp `pingInterval` = 15 s) and OkHttp fails the socket when a pong is missing at the next
 *   ping, i.e. a dead link is detected within ≤ 30 s (< the 35 s budget). An app-level timer on
 *   received messages would wrongly kill healthy idle connections (an idle bridge sends only pings),
 *   so the app-level [Config.handshakeTimeoutMs] (35 s) only covers connect → `synced`.
 */
class BridgeClient(
    val machine: Machine,
    okHttp: OkHttpClient,
    private val cursor: CursorStore,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    /** Signs the upgrade request of a paired machine; null = never sign (tests, legacy). */
    private val signer: RequestSigner? = null,
) : SyncClient {
    data class Config(
        val minBackoffMs: Long = 500,
        val maxBackoffMs: Long = 10_000,
        val handshakeTimeoutMs: Long = 35_000,
        val pingIntervalMs: Long = 15_000,
        val callTimeoutMs: Long = 30_000,
        val log: (String) -> Unit = {},
    )

    private val http = okHttp.newBuilder().pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS).build()

    private val _connection = MutableStateFlow(ConnState.Offline)
    override val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _authProblem = MutableStateFlow<AuthProblem?>(null)

    /** Set when the machine refused this phone (or the bridge failed its identity check); the client then stops. */
    override val authProblem: StateFlow<AuthProblem?> = _authProblem.asStateFlow()

    private val _sessionReady = MutableStateFlow(false)

    /**
     * True while the current connection's `welcome` has been accepted (for a paired machine:
     * verified against the pinned bridge key). Only then is the socket exposed to [call], [send],
     * [watch]; uploads wait for it too.
     */
    override val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    private val _syncedFrames = MutableStateFlow(0L)
    override val syncedFrames: StateFlow<Long> = _syncedFrames.asStateFlow()

    private val _syncEvents = Channel<ServerMsg>(Channel.UNLIMITED)

    /** Durable sync frames; single collector only (see class doc). */
    override val syncEvents: Flow<ServerMsg> = _syncEvents.receiveAsFlow()

    private val _events = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)

    /** Ephemeral frames (live, live.clear, res, term.*); dropped while nobody subscribes. */
    override val events: SharedFlow<ServerMsg> = _events.asSharedFlow()

    /** Number of [events] collectors; lets an owner await its subscription before [start]. */
    val eventSubscribers: StateFlow<Int> get() = _events.subscriptionCount

    /**
     * The socket once the connection's welcome was accepted (verified for a paired machine); null
     * otherwise. Nothing but the hello is sent before that.
     */
    @Volatile private var socket: WebSocket? = null
    @Volatile private var herdrOnline = true

    /** Whether this connection got a `herdr` frame; until then [herdrOnline] is a heuristic. */
    @Volatile private var herdrReported = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val nextId = AtomicLong()
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
        _sessionReady.value = false
        failPending()
        _connection.value = ConnState.Offline
    }

    /** Runs a bridge/herdr command. Throws [BridgeError] with the wire error code on failure. */
    override suspend fun call(method: String, params: JsonObject): JsonElement {
        val ws = socket ?: throw BridgeError(BridgeError.OFFLINE, "not connected to ${machine.name}")
        val id = "c${nextId.incrementAndGet()}"
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            // Registered before the check: a disconnect after it is seen by failPending().
            if (socket !== ws || !ws.send(encodeClientMsg(Cmd(id, method, params)))) {
                throw BridgeError(BridgeError.DISCONNECTED, "connection lost")
            }
            return withTimeoutOrNull(config.callTimeoutMs) { result.await() }
                ?: throw BridgeError(BridgeError.TIMEOUT, "$method: no response")
        } finally {
            pending.remove(id)
        }
    }

    /** Marks a pane's chat as on screen; re-sent automatically after every reconnect. */
    override fun watch(paneId: String) {
        watched.add(paneId)
        socket?.send(encodeClientMsg(Watch(paneId)))
    }

    override fun unwatch(paneId: String) {
        watched.remove(paneId)
        socket?.send(encodeClientMsg(Unwatch(paneId)))
    }

    /** Drops the current connection (if any); the loop reconnects with the stored cursor. */
    override fun reconnect() {
        socket?.cancel()
    }

    /** Sends a raw client message (e.g. term.*); false if not connected. Not replayed on reconnect. */
    override fun send(raw: ClientMsg): Boolean = socket?.send(encodeClientMsg(raw)) ?: false

    /** Set by [connectOnce] when the attempt failed on a stale challenge (retry once right away). */
    private var retryNow = false
    private var retriedNow = false

    private suspend fun runLoop() {
        var backoff = config.minBackoffMs
        try {
            while (currentCoroutineContext().isActive) {
                retryNow = false
                val reachedSync = connectOnce()
                _connection.value = ConnState.Offline
                if (_authProblem.value != null) {
                    config.log("${machine.name}: ${_authProblem.value}, not reconnecting")
                    return
                }
                if (reachedSync) backoff = config.minBackoffMs
                if (retryNow && !retriedNow) {
                    // A stale/consumed challenge: one immediate retry with a fresh one, then back off.
                    retriedNow = true
                    continue
                }
                retriedNow = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(config.maxBackoffMs)
            }
        } finally {
            _connection.value = ConnState.Offline
        }
    }

    private sealed interface WsEvent {
        data object Open : WsEvent
        class Text(val text: String) : WsEvent
        class Closed(val code: Int, val reason: String?) : WsEvent

        /** The upgrade was answered with an HTTP error ([code] = the bridge's error code, if any). */
        class Refused(val status: Int, val code: String?) : WsEvent
    }

    private class Listener(private val inbox: SendChannel<WsEvent>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            inbox.trySend(WsEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inbox.trySend(WsEvent.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            inbox.trySend(WsEvent.Closed(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inbox.trySend(WsEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response != null && response.code in 400..499) {
                inbox.trySend(WsEvent.Refused(response.code, errorCode(response)))
            } else {
                inbox.trySend(WsEvent.Closed(-1, t.toString()))
            }
        }

        private fun errorCode(r: Response): String? = runCatching {
            val o = WireJson.parseToJsonElement(r.peekBody(4096).string()).jsonObject
            o["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** One connection attempt; returns whether it got as far as `synced`. */
    private suspend fun connectOnce(): Boolean {
        _sessionReady.value = false
        _connection.value = ConnState.Connecting
        // In-band marker: the applier drops its baseline before any frame of this connection.
        _syncEvents.send(ConnectionStarted)
        val inbox = Channel<WsEvent>(Channel.UNLIMITED)
        val req = Request.Builder().url(machine.wsUrl)
        var nonce: String? = null
        val s = signer
        val paired = machine.paired && s != null
        if (paired) {
            val httpUrl = machine.baseUrl.trimEnd('/').plus("/v1/ws").toHttpUrlOrNull()
            if (httpUrl == null) {
                config.log("${machine.name}: bad address")
                return false
            }
            val signed = try {
                s!!.signFresh(http, machine, "GET", httpUrl)
            } catch (e: ChallengeException) {
                config.log("${machine.name}: challenge failed: ${e.message}")
                when (e.code) {
                    ChallengeException.CHALLENGE_UNSUPPORTED -> {
                        config.log("WARNING ${machine.name}: bridge has no /v1/challenge; secure login needs bridge plugin >= 0.3.0")
                        _authProblem.value = AuthProblem.BridgeTooOld
                    }
                    null -> Unit
                    else -> retryNow = true
                }
                return false
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                config.log("${machine.name}: cannot sign (${e.message})")
                null
            }
            if (signed == null) {
                config.log("${machine.name}: cannot sign (device key missing?)")
                _authProblem.value = AuthProblem.Unknown
                return false
            }
            req.header("Authorization", signed.authorization)
            nonce = signed.nonce
        }
        val ws = http.newWebSocket(req.build(), Listener(inbox))
        var welcomed = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.handshakeTimeoutMs)
        var synced = false
        try {
            while (true) {
                val ev = if (synced) {
                    inbox.receive()
                } else {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    (if (left > 0) withTimeoutOrNull(left) { inbox.receive() } else null) ?: run {
                        config.log("${machine.name}: no sync within ${config.handshakeTimeoutMs} ms, reconnecting")
                        return false
                    }
                }
                when (ev) {
                    WsEvent.Open -> {
                        val c = cursor.get(machine.id)
                        if (!ws.send(encodeClientMsg(Hello(serverId = c?.first ?: "", since = c?.second ?: 0)))) {
                            return false
                        }
                        // The socket is exposed (calls, watches, term input) only after the welcome.
                        _connection.value = ConnState.Syncing
                    }
                    is WsEvent.Text -> {
                        val msg = when (val d = decodeServerFrame(ev.text)) {
                            is Decoded.Msg -> d.msg
                            is Decoded.Unknown -> {
                                config.log("${machine.name}: ignored unknown t=${d.t}")
                                continue
                            }
                            is Decoded.Malformed -> {
                                // Never skip it: later frames would move the cursor past it.
                                config.log("${machine.name}: malformed ${d.t ?: "frame"} (${d.error}), reconnecting")
                                return false
                            }
                        }
                        // Welcome first: the first frame of a connection must be its (for a paired
                        // machine: validly signed) welcome, and there is exactly one.
                        if (!welcomed) {
                            if (msg !is Welcome || (paired && !welcomeTrusted(msg, nonce))) {
                                // Before anything of this connection reaches the cache or is sent.
                                val what = if (msg is Welcome) "welcome signature does not match the pinned bridge key" else "first frame is not a welcome"
                                config.log("${machine.name}: $what")
                                if (paired) _authProblem.value = AuthProblem.BridgeMismatch
                                return false
                            }
                            welcomed = true
                        } else if (msg is Welcome) {
                            config.log("${machine.name}: second welcome on one connection")
                            if (paired) _authProblem.value = AuthProblem.BridgeMismatch
                            return false
                        }
                        if (msg is Synced) {
                            synced = true
                            _syncedFrames.value++ // before handle() reports Synced (see SyncClient.syncedFrames)
                        }
                        handle(msg, synced)
                        when (msg) {
                            is Welcome, is StateMsg, is Synced, is Change -> _syncEvents.send(msg)
                            else -> _events.emit(msg)
                        }
                        if (msg is Welcome) {
                            // Verified: now the app may talk on this socket.
                            socket = ws
                            for (p in watched) ws.send(encodeClientMsg(Watch(p)))
                            _sessionReady.value = true
                        }
                    }
                    is WsEvent.Closed -> {
                        config.log("${machine.name}: closed ${ev.code} ${ev.reason.orEmpty()}")
                        if (ev.code == CLOSE_REVOKED) _authProblem.value = AuthProblem.Revoked
                        return synced
                    }
                    is WsEvent.Refused -> {
                        config.log("${machine.name}: refused HTTP ${ev.status} ${ev.code.orEmpty()}")
                        if (ev.status == 401) {
                            _authProblem.value = AuthProblem.fromCode(ev.code)
                            if (paired && ev.code == "auth_stale") retryNow = true
                        }
                        return false
                    }
                }
            }
        } finally {
            if (socket === ws) socket = null
            _sessionReady.value = false
            ws.cancel()
            failPending()
            inbox.close()
        }
    }

    private fun welcomeTrusted(w: Welcome, nonce: String?): Boolean {
        val key = machine.bridgeKey ?: return false
        val sig = w.sig ?: return false
        val spki = runCatching { java.util.Base64.getDecoder().decode(key) }.getOrNull() ?: return false
        return nonce != null && WhistleAuth.verify(spki, WhistleAuth.welcomeMessage(w.serverId, nonce), sig)
    }

    private fun handle(msg: ServerMsg, live: Boolean) {
        when (msg) {
            is Welcome -> {
                herdrReported = false
                // Fallback for bridges without `herdr` frames: an empty herdr_version means never seen.
                herdrOnline = msg.herdrVersion.isNotEmpty()
            }
            is HerdrStatus -> {
                herdrReported = true
                herdrOnline = msg.online
                if (live) _connection.value = if (msg.online) ConnState.Synced else ConnState.HerdrOffline
            }
            is Synced -> _connection.value = if (herdrOnline) ConnState.Synced else ConnState.HerdrOffline
            // Heuristic (only without `herdr` frames): live changes come from fresh herdr snapshots.
            is Change, is Live -> if (live && !herdrReported && !herdrOnline) {
                herdrOnline = true
                _connection.compareAndSet(ConnState.HerdrOffline, ConnState.Synced)
            }
            is Res -> {
                val err = msg.error
                if (err?.code == BridgeError.HERDR_UNAVAILABLE && !herdrReported) {
                    herdrOnline = false
                    _connection.compareAndSet(ConnState.Synced, ConnState.HerdrOffline)
                }
                val d = pending.remove(msg.id) ?: return
                if (err != null) d.completeExceptionally(BridgeError(err.code, err.message))
                else d.complete(msg.result ?: JsonNull)
            }
            else -> Unit
        }
    }

    private fun failPending() {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(BridgeError(BridgeError.DISCONNECTED, "connection lost"))
        }
    }
}

/** WebSocket close code the bridge sends when this device is unpaired. */
const val CLOSE_REVOKED = 4001
