package dev.whistle.app.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The surface of [BridgeClient] the data layer depends on (lets tests drive the repository with a fake). */
interface SyncClient {
    /** Durable sync frames; exactly one collector for the client's lifetime. */
    val syncEvents: Flow<ServerMsg>

    /** Ephemeral frames (herdr, live, live.clear, res, term.*). */
    val events: SharedFlow<ServerMsg>
    val connection: StateFlow<ConnState>

    /**
     * Number of `synced` frames queued on [syncEvents] so far. Incremented before [connection]
     * reports Synced, so "connection says Synced but the applier has processed fewer synced
     * frames" means the backlog is still being applied.
     */
    val syncedFrames: StateFlow<Long>

    /** Why the machine refuses this phone; non-null = stopped for good (pair again). */
    val authProblem: StateFlow<AuthProblem?> get() = NO_AUTH_PROBLEM

    /**
     * Whether the current connection's welcome was accepted (verified against the pinned bridge key
     * for a paired machine). Uploads wait for it.
     */
    val sessionReady: StateFlow<Boolean> get() = NOT_READY

    fun start()
    fun stop()
    suspend fun call(method: String, params: JsonObject): JsonElement
    fun watch(paneId: String)
    fun unwatch(paneId: String)

    /** Drops the connection; the next hello resumes from the stored cursor. */
    fun reconnect()

    /** Sends a raw client message (term.*); false if not connected. Not replayed on reconnect. */
    fun send(raw: ClientMsg): Boolean
}

private val NO_AUTH_PROBLEM: StateFlow<AuthProblem?> = MutableStateFlow(null)
private val NOT_READY: StateFlow<Boolean> = MutableStateFlow(false)
