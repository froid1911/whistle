package dev.whistle.app.ui.chat

import dev.whistle.app.protocol.BridgeError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Answering one blocked agent pane: fetch its screen (`agent.screen`), parse the numbered
 * choices ([parseApprovalOptions]), send the chosen keys (`agent.send_keys`). Shared by the chat's
 * approval card and the herd screen's needs-you cards.
 *
 * [stillBlocked] is asked [refetchAfterKeysMs] after keys were sent: multi-step dialogs show
 * their next screen.
 */
class ApprovalController(
    private val scope: CoroutineScope,
    val paneId: String,
    private val cmd: suspend (method: String, params: JsonObject) -> JsonElement,
    private val refetchAfterKeysMs: Long = REFETCH_AFTER_KEYS_MS,
    private val stillBlocked: () -> Boolean,
) {
    private val _state = MutableStateFlow<ApprovalUi?>(null)

    /** null = no card. */
    val state: StateFlow<ApprovalUi?> = _state.asStateFlow()

    private var job: Job? = null

    /** Shows the card with the pane's current screen. */
    fun fetch() {
        job?.cancel()
        _state.value = ApprovalUi(loading = true)
        job = scope.launch {
            _state.value = try {
                val res = cmd("agent.screen", buildJsonObject { put("pane_id", paneId); put("lines", SCREEN_LINES) })
                val text = ((res as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                ApprovalUi(text, parseApprovalOptions(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApprovalUi(error = e.describe())
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = null
    }

    fun sendKeys(keys: List<String>) {
        job?.cancel()
        _state.update { (it ?: ApprovalUi()).copy(sending = true, error = null) }
        job = scope.launch {
            try {
                cmd("agent.send_keys", buildJsonObject {
                    put("target", paneId)
                    putJsonArray("keys") { keys.forEach { add(JsonPrimitive(it)) } }
                })
                _state.value = null
                // Multi-step dialogs: still blocked after a moment → show the next screen.
                delay(refetchAfterKeysMs)
                if (stillBlocked()) fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { (it ?: ApprovalUi()).copy(sending = false, error = e.describe()) }
            }
        }
    }

    companion object {
        const val REFETCH_AFTER_KEYS_MS = 1_500L
        const val SCREEN_LINES = 60

        fun Exception.describe(): String =
            if (this is BridgeError) "$code: ${message ?: ""}".trimEnd(' ', ':') else message ?: toString()
    }
}

private val QUESTION_BOX = Regex("[│┃╭╮╰╯─━┌┐└┘╌┄]")
private val OPTION_LINE = Regex("""^(?:[❯›>▶➤]\s*)?\d{1,2}[.)]\s+.+$""")
private val HINT_LINE = Regex("""(?i)^(esc|enter|ctrl|tab|↑|↓|⏎).*(to|cancel|select|confirm|navigate).*$""")

/**
 * The question an approval dialog asks, for the needs-you card: the last non-empty lines (at most
 * [maxLines]) above the numbered options, box borders stripped. Without options: the last lines of
 * the screen that are not key hints.
 */
fun approvalQuestion(screen: String, maxLines: Int = 3): String {
    val lines = screen.lines().map { it.replace(QUESTION_BOX, " ").trim() }
    val firstOption = run {
        val opts = parseApprovalOptions(screen)
        if (opts.isEmpty()) -1
        else {
            // The run that parseApprovalOptions picked starts at the last line numbered 1.
            lines.indexOfLast { OPTION_LINE.matches(it) && it.trimStart('❯', '›', '>', '▶', '➤', ' ').startsWith("1") }
        }
    }
    val above = if (firstOption >= 0) lines.subList(0, firstOption) else lines
    return above.filter { it.isNotBlank() && !HINT_LINE.matches(it) }.takeLast(maxLines).joinToString("\n")
}

/** A rule or the top of a box: where a dialog starts (Claude Code draws `────` above the prompt). */
private val DIALOG_TOP = Regex("""^\s*[╭┌]?[─━╌┄]{8,}.*$""")

/**
 * The FULL prompt of an approval dialog (the command, the question), for the card's inset: the
 * lines between the nearest rule/box top above the numbered options and the options, box borders
 * stripped, common indentation removed, hint lines dropped, at most [maxLines] (the last ones).
 * Without options: the bottom of the screen. Only ever built from `agent.screen`.
 */
fun approvalPrompt(screen: String, maxLines: Int = 40): String {
    val raw = screen.lines()
    val cleaned = raw.map { it.replace(QUESTION_BOX, " ").trim() }
    val end = if (parseApprovalOptions(screen).isEmpty()) raw.size
    else cleaned.indexOfLast { OPTION_LINE.matches(it) && it.trimStart('❯', '›', '>', '▶', '➤', ' ').startsWith("1") }
    var start = 0
    for (i in end - 1 downTo 0) {
        if (DIALOG_TOP.matches(raw[i])) { start = i + 1; break }
    }
    val body = raw.subList(start, end)
        .map { it.replace(QUESTION_BOX, " ").trimEnd() }
        .filterNot { HINT_LINE.matches(it.trim()) }
    val indent = body.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } } ?: 0
    val lines = body.map { if (it.isBlank()) "" else it.drop(indent) }
        .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        // collapse runs of blank lines
        .fold(mutableListOf<String>()) { acc, l -> if (!(l.isEmpty() && acc.lastOrNull()?.isEmpty() == true)) acc += l; acc }
    return lines.takeLast(maxLines).joinToString("\n")
}
