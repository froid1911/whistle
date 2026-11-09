package dev.whistle.app.ui.chat

import dev.whistle.app.data.ChatState
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.Role
import dev.whistle.app.protocol.WMessage
import dev.whistle.app.protocol.WireJson
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the chat needs from one machine; [RepoChatSource] in the app, a fake in tests. */
interface ChatSource {
    fun messages(sessionKey: String): Flow<List<WMessage>>
    fun chatState(sessionKey: String): Flow<ChatState>
    fun live(paneId: String): StateFlow<String?>
    fun status(paneId: String): Flow<AgentStatus>
    suspend fun cmd(method: String, params: JsonObject): JsonElement
    suspend fun ensureHistory(sessionKey: String)
    suspend fun loadOlder(sessionKey: String)
    fun watch(paneId: String)
    fun unwatch(paneId: String)
}

class RepoChatSource(private val repo: HerdrRepository, private val machineId: String) : ChatSource {
    override fun messages(sessionKey: String) = repo.messages(machineId, sessionKey)
    override fun chatState(sessionKey: String) = repo.chatState(machineId, sessionKey)
    override fun live(paneId: String) = repo.live(machineId, paneId)
    override fun status(paneId: String): Flow<AgentStatus> =
        repo.pane(machineId, paneId).map { it?.agentStatus ?: AgentStatus.UNKNOWN }
    override suspend fun cmd(method: String, params: JsonObject) = repo.cmd(machineId, method, params)
    override suspend fun ensureHistory(sessionKey: String) = repo.ensureHistory(machineId, sessionKey)
    override suspend fun loadOlder(sessionKey: String) = repo.loadOlder(machineId, sessionKey)
    override fun watch(paneId: String) = repo.watch(machineId, paneId)
    override fun unwatch(paneId: String) = repo.unwatch(machineId, paneId)
}

// ---- rows ----------------------------------------------------------------------

/**
 * One list row. Consecutive assistant/thinking/tool rows form a turn: they share [turnKey] (the
 * turn's first message id) and only the first has [firstInTurn]. A tool_result is folded into
 * its tool_call's row; an unpaired result gets a row of its own.
 */
sealed interface ChatRow {
    val key: String
    val turnKey: String?
    val firstInTurn: Boolean

    data class User(val msg: WMessage) : ChatRow {
        override val key get() = msg.id
        override val turnKey: String? get() = null
        override val firstInTurn get() = true
    }

    data class Screen(val msg: WMessage) : ChatRow {
        override val key get() = msg.id
        override val turnKey: String? get() = null
        override val firstInTurn get() = true
    }

    data class Text(val msg: WMessage, override val turnKey: String, override val firstInTurn: Boolean) : ChatRow {
        override val key get() = msg.id
    }

    data class Thinking(val msg: WMessage, override val turnKey: String, override val firstInTurn: Boolean) : ChatRow {
        override val key get() = msg.id
    }

    /** [call] null = a result whose call is not loaded (or never existed). */
    data class Tool(
        val call: WMessage?,
        val result: WMessage?,
        override val turnKey: String,
        override val firstInTurn: Boolean,
    ) : ChatRow {
        override val key get() = (call ?: result)!!.id
    }
}

/**
 * Groups [messages] (ordered by seq) into rows. Rows equal to one in [previous] are reused as the
 * same instance, so Compose skips them when a new emission only appends or updates a few messages.
 */
fun groupMessages(messages: List<WMessage>, previous: List<ChatRow> = emptyList()): List<ChatRow> {
    val results = HashMap<String, WMessage>()
    val callIds = HashSet<String>()
    for (m in messages) {
        val id = m.toolCallId ?: continue
        if (m.role == Role.TOOL_RESULT) results[id] = m else if (m.role == Role.TOOL_CALL) callIds += id
    }
    val prev = previous.associateBy { it.key }
    val out = ArrayList<ChatRow>(messages.size)
    var turn: String? = null
    for (m in messages) {
        val first = turn == null
        val row: ChatRow = when (m.role) {
            Role.USER -> { turn = null; ChatRow.User(m) }
            Role.SCREEN -> { turn = null; ChatRow.Screen(m) }
            Role.THINKING -> ChatRow.Thinking(m, turn ?: m.id, first)
            Role.TOOL_CALL -> ChatRow.Tool(m, m.toolCallId?.let { results[it] }, turn ?: m.id, first)
            Role.TOOL_RESULT -> {
                if (m.toolCallId != null && m.toolCallId in callIds) continue // shown under its call
                ChatRow.Tool(null, m, turn ?: m.id, first)
            }
            else -> ChatRow.Text(m, turn ?: m.id, first) // assistant (and roles of a newer bridge)
        }
        if (row !is ChatRow.User && row !is ChatRow.Screen && turn == null) turn = m.id
        val old = prev[row.key]
        out += if (old == row) old else row
    }
    return out
}

private val SUMMARY_KEYS = listOf(
    "command", "file_path", "path", "filePath", "pattern", "url", "query", "description", "prompt",
)

/**
 * One line describing a tool call: its command / file path / first string argument. Cheap on the
 * cached raw input ([WMessage.inputJson]): a key scan, no full decode (that happens on expand).
 * Tolerates any input shape: object, `{"_truncated": "<prefix>"}`, string or other primitives.
 */
fun toolSummary(call: WMessage): String {
    val v = when (val input = call.input) {
        null -> call.inputJson?.let { summaryFromRaw(it) } ?: return firstLine(call.text)
        is JsonObject -> truncatedPrefix(input)?.let { summaryFromRaw(it) ?: it } ?: summaryFromObject(input)
        is JsonPrimitive -> if (input.isString) summaryFromRaw(input.content) ?: input.content else input.content
        else -> null
    }
    return firstLine(v.orEmpty())
}

private fun firstLine(s: String) = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** The serialized-input prefix of a bridge-truncated input `{"_truncated": "…"}`, else null. */
fun truncatedPrefix(input: JsonObject): String? =
    (input[TRUNCATED_KEY] as? JsonPrimitive)?.takeIf { it.isString && input.size == 1 }?.content

const val TRUNCATED_KEY = "_truncated"

private fun summaryFromObject(input: JsonObject): String? =
    SUMMARY_KEYS.firstNotNullOfOrNull { k -> (input[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        ?: input.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }


/** Summary from serialized (possibly cut-off) JSON text without decoding it. */
private fun summaryFromRaw(raw: String): String? {
    val fields = stringFields(raw)
    fields.firstOrNull { it.first == TRUNCATED_KEY }?.let { (_, v) -> unescape(v)?.let { return summaryFromRaw(it) ?: it } }
    val v = SUMMARY_KEYS.firstNotNullOfOrNull { k -> fields.firstOrNull { it.first == k }?.second } ?: fields.firstOrNull()?.second
    return v?.let(::unescape)
}

/** (key, escaped value) of every `"key": "string"` pair in serialized (possibly cut-off) JSON. */
private fun stringFields(raw: String): List<Pair<String, String>> {
    val n = raw.length
    // Index after the closing quote of the string opening at [start], or -1 if cut off.
    fun stringEnd(start: Int): Int {
        var j = start + 1
        while (j < n) {
            when (raw[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return -1
    }
    val out = ArrayList<Pair<String, String>>()
    var i = 0
    while (i < n) {
        if (raw[i] != '"') {
            i++
            continue
        }
        val kEnd = stringEnd(i)
        if (kEnd < 0) break
        var j = kEnd
        while (j < n && raw[j].isWhitespace()) j++
        if (j < n && raw[j] == ':') {
            j++
            while (j < n && raw[j].isWhitespace()) j++
            if (j < n && raw[j] == '"') {
                val vEnd = stringEnd(j)
                if (vEnd < 0) break
                out += raw.substring(i + 1, kEnd - 1) to raw.substring(j + 1, vEnd - 1)
                i = vEnd
                continue
            }
        }
        i = kEnd
    }
    return out
}

private fun unescape(jsonStringBody: String): String? =
    runCatching { (WireJson.parseToJsonElement("\"$jsonStringBody\"") as JsonPrimitive).content }.getOrNull()

// ---- approval --------------------------------------------------------------------

data class ApprovalUi(
    val text: String = "",
    val options: List<ApprovalOption> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

// ---- view model ------------------------------------------------------------------

/**
 * State and actions of one agent pane's chat. Plain class living in the composition's scope
 * ([scope]); [start] subscribes and watches the pane, [stop] unwatches.
 */
class ChatViewModel(
    private val src: ChatSource,
    val paneId: String,
    val sessionKey: String,
    private val scope: CoroutineScope,
    private val outbox: PaneOutbox,
    private val compute: CoroutineContext = Dispatchers.Default,
) {
    private val raw = MutableStateFlow<List<WMessage>>(emptyList())
    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    private val _chatState = MutableStateFlow<ChatState>(ChatState.Loading)
    private val _status = MutableStateFlow(AgentStatus.UNKNOWN)
    private val _live = MutableStateFlow<String?>(null)
    private val _loadingOlder = MutableStateFlow(false)
    private val _notice = MutableStateFlow<String?>(null)

    /** Message rows, oldest first. */
    val rows: StateFlow<List<ChatRow>> = _rows.asStateFlow()
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    /** The live bubble's text: the in-progress screen, held briefly after live.clear (see [onLive]). */
    val live: StateFlow<String?> = _live.asStateFlow()

    /** This pane's prompts not yet in the transcript; held by the app-wide [PromptOutbox]. */
    val pending: StateFlow<List<Pending>> get() = outbox.pending
    val approval: StateFlow<ApprovalUi?> get() = approvals.state
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /** A transient error to show once (snackbar); cleared by [consumeNotice]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var started = false
    private var loaded = false

    fun start() {
        if (started) return
        started = true
        src.watch(paneId)
        scope.launch {
            var prev = emptyList<ChatRow>()
            src.messages(sessionKey)
                .map { list -> list to groupMessages(list, prev).also { prev = it } }
                .flowOn(compute)
                .collect { (list, grouped) ->
                    raw.value = list
                    if (!loaded) {
                        loaded = true
                        // A turn already running when the chat opened: its start is the cache's head.
                        if (turnStartSeq != null) turnStartSeq = maxSeq()
                    }
                    _rows.value = grouped
                    outbox.match(list)
                    checkLiveHold()
                }
        }
        scope.launch { src.chatState(sessionKey).collect { _chatState.value = it } }
        // A prompt refused because the agent is blocked (possibly while this page was away): show the card once.
        scope.launch {
            outbox.pending.collect { list ->
                list.firstOrNull { (it.state as? PendingState.Failed)?.code == "agent_blocked" && !it.blockedAcked }?.let {
                    outbox.ackBlocked(it.id)
                    fetchApproval()
                }
            }
        }
        scope.launch { src.live(paneId).collect { onLive(it) } }
        scope.launch { src.status(paneId).distinctUntilChanged().collect { onStatus(it) } }
        retryHistory()
    }

    fun stop() {
        if (!started) return
        started = false
        src.unwatch(paneId)
    }

    // ---- history

    fun retryHistory() {
        scope.launch {
            try {
                src.ensureHistory(sessionKey)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // surfaced through chatState (Error) → inline Retry
            }
        }
    }

    /** The list reached its oldest row: load the page before it (one load at a time). */
    fun onReachedTop() {
        // Nothing cached yet: the first page comes from ensureHistory, not from here.
        if (_loadingOlder.value || raw.value.isEmpty()) return
        _loadingOlder.value = true
        scope.launch {
            try {
                src.loadOlder(sessionKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "Couldn't load older messages: ${e.message ?: e}"
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    // ---- live bubble

    private var turnStartSeq: Long? = null
    private var holdJob: Job? = null

    /**
     * live and the transcript arrive on different flows. On live.clear the last text stays until
     * the turn's final reply (an assistant or screen message newer than the turn start) is in the
     * cache, or [LIVE_HOLD_MS] pass, so the bubble never vanishes before the reply appears.
     */
    private fun onLive(text: String?) {
        if (text != null) {
            holdJob?.cancel()
            holdJob = null
            if (turnStartSeq == null) turnStartSeq = maxSeq()
            _live.value = text
            return
        }
        if (_live.value == null) {
            turnStartSeq = null
            return
        }
        if (replyArrived()) {
            clearLive()
        } else if (holdJob == null) {
            holdJob = scope.launch {
                delay(LIVE_HOLD_MS)
                holdJob = null
                clearLive()
            }
        }
    }

    private fun checkLiveHold() {
        if (holdJob != null && replyArrived()) clearLive()
    }

    private fun replyArrived(): Boolean {
        val start = turnStartSeq ?: return true
        val last = raw.value.lastOrNull() ?: return false
        return last.seq > start && (last.role == Role.ASSISTANT || last.role == Role.SCREEN)
    }

    private fun clearLive() {
        holdJob?.cancel()
        holdJob = null
        turnStartSeq = null
        _live.value = null
    }

    private fun maxSeq(): Long = raw.value.maxOfOrNull { it.seq } ?: 0L

    // ---- status / approval (shared with the herd screen's needs-you cards)

    private val approvals = ApprovalController(scope, paneId, src::cmd, REFETCH_AFTER_KEYS_MS) {
        _status.value == AgentStatus.BLOCKED
    }

    private fun onStatus(s: AgentStatus) {
        val was = _status.value
        _status.value = s
        when {
            s == AgentStatus.BLOCKED -> fetchApproval()
            was == AgentStatus.BLOCKED -> approvals.dismiss()
        }
    }

    /** Shows the approval card with the pane's current screen. */
    fun fetchApproval() = approvals.fetch()

    fun dismissApproval() = approvals.dismiss()

    fun sendKeys(keys: List<String>) = approvals.sendKeys(keys)

    // ---- composer (delivery and matching live in the outbox, which outlives this page)

    fun send(text: String) = outbox.send(text)

    /** Re-sends a failed / unconfirmed / unseen prompt (only ever on the user's request). */
    fun retry(id: Long) = outbox.retry(id)

    fun dismiss(id: Long) = outbox.dismiss(id)

    companion object {
        const val LIVE_HOLD_MS = 3_000L
        const val REFETCH_AFTER_KEYS_MS = ApprovalController.REFETCH_AFTER_KEYS_MS
        const val SCREEN_LINES = ApprovalController.SCREEN_LINES
    }
}
