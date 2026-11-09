package dev.whistle.app.ui.chat

import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.protocol.Role
import dev.whistle.app.protocol.WMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface PendingState {
    /** agent.prompt in flight. */
    data object Sending : PendingState

    /** Accepted; waiting for the transcript's user message. */
    data object Sent : PendingState

    /** 30 s passed and the agent settled without the prompt showing up in the transcript. */
    data object NotSeen : PendingState

    /** Stalled / timed out / connection lost: it may or may not have been delivered. Never retried automatically. */
    data object Unconfirmed : PendingState

    /** Not delivered (e.g. blocked, herdr down). */
    data class Failed(val code: String, val message: String) : PendingState
}

/**
 * A prompt sent from the phone that the transcript does not show yet. [afterSeq] is the machine's
 * last applied change seq when it was sent: only a user message with a greater seq can be it.
 * [blockedAcked]: a chat view already opened the approval card for its `agent_blocked` failure.
 */
data class Pending(
    val id: Long,
    val text: String,
    val afterSeq: Long,
    val state: PendingState,
    val blockedAcked: Boolean = false,
)

/**
 * Prompts in flight / awaiting the transcript, per (machine, pane). Lives in the app scope, not
 * in a chat page: swiping away, rotation or a session switch in the pane (/clear, a session
 * appearing) never loses a pending prompt or the outcome of a send that is still in flight.
 */
class PromptOutbox(private val scope: CoroutineScope, private val backend: Backend) {
    interface Backend {
        suspend fun cmd(machineId: String, method: String, params: JsonObject): JsonElement

        /** The last applied change-log seq of the machine (message seqs share this numbering). */
        suspend fun headSeq(machineId: String): Long

        fun status(machineId: String, paneId: String): Flow<AgentStatus>
    }

    private val panes = ConcurrentHashMap<Pair<String, String>, MutableStateFlow<List<Pending>>>()
    private val ids = AtomicLong()

    private fun flow(m: String, pane: String) = panes.getOrPut(m to pane) { MutableStateFlow(emptyList()) }

    fun pending(machineId: String, paneId: String): StateFlow<List<Pending>> = flow(machineId, paneId).asStateFlow()

    /** Queues and delivers [text]; returns the [Pending.id] (null for blank text, nothing sent). */
    fun send(machineId: String, paneId: String, text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val p = Pending(ids.incrementAndGet(), t, afterSeq = Long.MAX_VALUE, state = PendingState.Sending)
        flow(machineId, paneId).update { it + p }
        deliver(machineId, paneId, p.id)
        return p.id
    }

    /** Re-sends a failed / unconfirmed / unseen prompt (only ever on the user's request). */
    fun retry(machineId: String, paneId: String, id: Long) {
        val f = flow(machineId, paneId)
        var ok = false
        f.update { list ->
            list.map {
                if (it.id == id && it.state != PendingState.Sending && it.state != PendingState.Sent) {
                    ok = true
                    it.copy(state = PendingState.Sending, blockedAcked = false)
                } else it
            }
        }
        if (ok) deliver(machineId, paneId, id)
    }

    fun dismiss(machineId: String, paneId: String, id: Long) =
        flow(machineId, paneId).update { list -> list.filterNot { it.id == id } }

    fun ackBlocked(machineId: String, paneId: String, id: Long) =
        flow(machineId, paneId).update { list -> list.map { if (it.id == id) it.copy(blockedAcked = true) else it } }

    /** Drops pending prompts whose transcript user message (same trimmed text, newer than the send) is in [messages]. */
    fun match(machineId: String, paneId: String, messages: List<WMessage>) {
        val f = panes[machineId to paneId] ?: return
        if (f.value.isEmpty()) return
        val users = messages.filter { it.role == Role.USER }
        f.update { list ->
            val used = HashSet<String>()
            list.filterNot { p ->
                if (p.state == PendingState.Sending || p.state is PendingState.Failed) return@filterNot false
                val m = users.firstOrNull { it.seq > p.afterSeq && it.id !in used && it.text.trim() == p.text }
                m != null && used.add(m.id)
            }
        }
    }

    private fun setState(f: MutableStateFlow<List<Pending>>, id: Long, state: PendingState, afterSeq: Long? = null) =
        f.update { list ->
            list.map { if (it.id == id) it.copy(state = state, afterSeq = afterSeq ?: it.afterSeq) else it }
        }

    // Fire-and-forget (no `wait`): the status / message stream shows the turn's progress.
    private fun deliver(m: String, pane: String, id: Long) {
        val f = flow(m, pane)
        scope.launch {
            val text = f.value.firstOrNull { it.id == id }?.text ?: return@launch
            val state = try {
                // Taken before the send: the transcript's copy of this prompt is always newer.
                setState(f, id, PendingState.Sending, afterSeq = backend.headSeq(m))
                backend.cmd(m, "agent.prompt", buildJsonObject { put("target", pane); put("text", text) })
                PendingState.Sent
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeError) {
                if (e.code in AMBIGUOUS) PendingState.Unconfirmed else PendingState.Failed(e.code, e.message ?: e.code)
            } catch (e: Exception) {
                PendingState.Unconfirmed // unknown failure: it may have been sent
            }
            setState(f, id, state)
            if (state == PendingState.Sent) {
                delay(PENDING_TIMEOUT_MS)
                backend.status(m, pane).first { it.settled }
                f.update { list ->
                    list.map { if (it.id == id && it.state == PendingState.Sent) it.copy(state = PendingState.NotSeen) else it }
                }
            }
        }
    }

    /** One pane's view of the outbox. */
    fun forPane(machineId: String, paneId: String) = PaneOutbox(this, machineId, paneId)

    companion object {
        const val PENDING_TIMEOUT_MS = 30_000L

        /**
         * Errors after which the prompt may have reached the agent: never offered as a plain
         * Retry (it could type the prompt twice); cleared by [match] when the transcript shows it.
         * Everything else (offline before sending, herdr_unavailable, agent_blocked → approval
         * card, not_found, method_not_allowed, bad_request, busy) means not delivered.
         */
        val AMBIGUOUS = setOf(
            BridgeError.DISCONNECTED, BridgeError.TIMEOUT, "internal", "timeout", "agent_prompt_stalled",
        )
    }
}

class PaneOutbox(private val outbox: PromptOutbox, private val machineId: String, private val paneId: String) {
    val pending: StateFlow<List<Pending>> get() = outbox.pending(machineId, paneId)
    fun send(text: String): Long? = outbox.send(machineId, paneId, text)
    fun retry(id: Long) = outbox.retry(machineId, paneId, id)
    fun dismiss(id: Long) = outbox.dismiss(machineId, paneId, id)
    fun ackBlocked(id: Long) = outbox.ackBlocked(machineId, paneId, id)
    fun match(messages: List<WMessage>) = outbox.match(machineId, paneId, messages)
}

class RepoOutboxBackend(private val repo: HerdrRepository) : PromptOutbox.Backend {
    override suspend fun cmd(machineId: String, method: String, params: JsonObject) = repo.cmd(machineId, method, params)
    override suspend fun headSeq(machineId: String): Long = repo.cursorStore.get(machineId)?.second ?: 0L
    override fun status(machineId: String, paneId: String): Flow<AgentStatus> =
        repo.pane(machineId, paneId).map { it?.agentStatus ?: AgentStatus.UNKNOWN }
}
