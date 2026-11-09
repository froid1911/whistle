package dev.whistle.app.terminal

import dev.whistle.app.protocol.ClientMsg
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.ServerMsg
import dev.whistle.app.protocol.SyncClient
import dev.whistle.app.protocol.TermClose
import dev.whistle.app.protocol.TermClosed
import dev.whistle.app.protocol.TermFrame
import dev.whistle.app.protocol.TermInput
import dev.whistle.app.protocol.TermOpen
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a terminal session needs from a bridge connection. */
interface TermLink {
    val events: SharedFlow<ServerMsg>
    val connection: StateFlow<ConnState>
    fun send(msg: ClientMsg): Boolean
}

fun SyncClient.asTermLink(): TermLink = object : TermLink {
    override val events get() = this@asTermLink.events
    override val connection get() = this@asTermLink.connection
    override fun send(msg: ClientMsg) = this@asTermLink.send(msg)
}

/**
 * One pane's terminal stream over a bridge connection (one `sid` at a time).
 *
 * - Opens `observe` at the pane's real size once the connection is Synced. [fitTo] reopens it in
 *   observe mode with a phone-sized cols/rows (does not resize the real pane); [unfit] restores.
 *   Never sends `control` with a size or `term.resize`: either would resize the desktop pane.
 * - Input is sent on the current session; the bridge upgrades it to control at the pane's own
 *   size, so a fit view becomes a desktop-size view ([TermState.fit] is cleared).
 * - The bridge does not restore sessions after a WS reconnect (and sends no term.closed for them):
 *   leaving Synced drops the session (term.close best effort), returning reopens with a new sid.
 * - term.closed: `too_many_sessions` is retried a few times (a neighbouring page may still hold
 *   a slot while it is being disposed); any other reason stops at [Phase.Closed] until [reopen].
 * - [stop] closes the session; call it when the view goes away (max 2 sessions per connection).
 *
 * Not thread-safe: use from one thread (the main thread) via a [scope] confined to it.
 */
class TerminalSession(
    private val link: TermLink,
    private val paneId: String,
    private val scope: CoroutineScope,
    private val newSid: () -> String = { "t-" + UUID.randomUUID().toString().take(8) },
    private val sink: (frame: TermFrame, fresh: Boolean) -> Unit,
) {
    enum class Phase { Waiting, Opening, Live, Reconnecting, Closed }

    data class TermState(
        val phase: Phase = Phase.Waiting,
        /** term.closed reason while [Phase.Closed]. */
        val reason: String? = null,
        /** Requested fit-to-phone size, or null for the pane's real size. */
        val fit: Pair<Int, Int>? = null,
        /** Size of the latest frame. */
        val size: Pair<Int, Int>? = null,
    )

    private val _state = MutableStateFlow(TermState())
    val state: StateFlow<TermState> = _state.asStateFlow()

    private var sid: String? = null
    private var gotFrame = false
    private var busyRetries = 0
    private var stopped = false
    private var wasLive = false
    private val jobs = mutableListOf<Job>()
    private var retryJob: Job? = null

    private val synced get() = link.connection.value == ConnState.Synced

    fun start() {
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect { onEvent(it) } }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { link.connection.collect { onConn(it) } }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        closeCurrent()
        retryJob?.cancel()
        jobs.forEach { it.cancel() }
    }

    /** Sends typed or soft-key text; false if there is no open session. */
    fun input(text: String): Boolean {
        val s = sid ?: return false
        if (stopped || !link.send(TermInput(s, text = text))) return false
        // The bridge restarts the session in control mode without our size.
        if (_state.value.fit != null) _state.update { it.copy(fit = null) }
        return true
    }

    /** User asked to reopen after a term.closed. */
    fun reopen() {
        busyRetries = 0
        restart()
    }

    /** The view lost frames (overflow, detached): reopen with a new sid for a fresh full frame. */
    fun resync() {
        if (_state.value.phase != Phase.Closed) restart()
    }

    /** The view failed (e.g. WebView renderer gone): close and show [reason] until [reopen]. */
    fun fail(reason: String) {
        if (stopped) return
        retryJob?.cancel()
        closeCurrent()
        _state.update { it.copy(phase = Phase.Closed, reason = reason) }
    }

    fun fitTo(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        _state.update { it.copy(fit = cols to rows) }
        restart()
    }

    fun unfit() {
        _state.update { it.copy(fit = null) }
        restart()
    }

    private fun restart() {
        if (stopped) return
        retryJob?.cancel()
        closeCurrent()
        _state.update { it.copy(reason = null) }
        if (synced) open() else _state.update { it.copy(phase = if (wasLive) Phase.Reconnecting else Phase.Waiting) }
    }

    private fun onConn(st: ConnState) {
        if (stopped) return
        if (st == ConnState.Synced) {
            if (sid == null && _state.value.phase != Phase.Closed && retryJob?.isActive != true) open()
            return
        }
        if (_state.value.phase == Phase.Closed) return
        retryJob?.cancel()
        // The session is gone (WS drop) or about to end (herdr offline); free the slot either way.
        closeCurrent()
        _state.update { it.copy(phase = if (wasLive) Phase.Reconnecting else Phase.Waiting) }
    }

    private fun open() {
        val s = newSid()
        val fit = _state.value.fit
        sid = s
        gotFrame = false
        if (!link.send(TermOpen(s, paneId, "observe", fit?.first, fit?.second))) {
            // Not connected after all: the next Synced reopens.
            sid = null
            _state.update { it.copy(phase = if (wasLive) Phase.Reconnecting else Phase.Waiting) }
            return
        }
        _state.update { it.copy(phase = Phase.Opening) }
    }

    private fun closeCurrent() {
        val s = sid ?: return
        sid = null
        link.send(TermClose(s))
    }

    private fun onEvent(msg: ServerMsg) {
        if (stopped) return
        when (msg) {
            is TermFrame -> if (msg.sid == sid) {
                val fresh = !gotFrame
                gotFrame = true
                wasLive = true
                busyRetries = 0
                val size = msg.width to msg.height
                if (_state.value.phase != Phase.Live || _state.value.size != size) {
                    _state.update { it.copy(phase = Phase.Live, size = size) }
                }
                sink(msg, fresh)
            }
            is TermClosed -> if (msg.sid == sid) {
                sid = null
                if (msg.reason == BUSY && busyRetries < BUSY_RETRIES) {
                    busyRetries++
                    _state.update { it.copy(phase = Phase.Opening) }
                    retryJob = scope.launch {
                        delay(BUSY_RETRY_MS)
                        if (!stopped && sid == null && synced) open()
                    }
                } else {
                    _state.update { it.copy(phase = Phase.Closed, reason = msg.reason.ifBlank { "closed" }) }
                }
            }
            else -> Unit
        }
    }

    companion object {
        const val BUSY = "too_many_sessions"
        const val BUSY_RETRIES = 3
        const val BUSY_RETRY_MS = 1_000L
    }
}
