package dev.whistle.app.ui.changes

import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.ui.chat.DiffKind
import dev.whistle.app.ui.chat.DiffLine
import dev.whistle.app.ui.chat.DraftStore
import dev.whistle.app.ui.chat.parseUnifiedDiff
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A diff line with its line numbers in the old and new file (null where the side has none). */
data class NumberedLine(val kind: DiffKind, val text: String, val oldNo: Int?, val newNo: Int?) {
    /** The line to quote: old-file line for removed lines, new-file line for added/context ones. */
    val refNo: Int? get() = if (kind == DiffKind.Removed) oldNo else newNo
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** Numbers [parseUnifiedDiff] output by following each hunk header's start lines. */
fun numberDiff(lines: List<DiffLine>): List<NumberedLine> {
    var o = 0
    var n = 0
    return lines.map { l ->
        when (l.kind) {
            DiffKind.Hunk -> {
                HUNK.find(l.text)?.let { o = it.groupValues[1].toInt(); n = it.groupValues[2].toInt() }
                NumberedLine(l.kind, l.text, null, null)
            }
            DiffKind.Header -> NumberedLine(l.kind, l.text, null, null)
            DiffKind.Added -> NumberedLine(l.kind, l.text, null, n++)
            DiffKind.Removed -> NumberedLine(l.kind, l.text, o++, null)
            // "\ No newline at end of file" is a marker, not a line.
            DiffKind.Context ->
                if (l.text.startsWith("\\ ")) NumberedLine(l.kind, l.text, null, null)
                else NumberedLine(l.kind, l.text, o++, n++)
        }
    }
}

data class GitFile(val path: String, val status: String, val staged: Boolean)

/** Heading of the commit dialog: what `all:true` is about to commit. */
fun commitHeading(files: List<GitFile>) = if (files.size == 1) "Commit 1 file" else "Commit ${files.size} files"

sealed interface CommitState {
    data object Idle : CommitState
    data object Busy : CommitState
    data class Done(val sha: String) : CommitState
    data class Error(val message: String) : CommitState
}

data class ChangesState(
    /** Picker entries: pane cwds of the workspace first, then worktree paths. */
    val paths: List<String> = emptyList(),
    val path: String? = null,
    val branch: String? = null,
    val files: List<GitFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val file: String? = null,
    val diff: List<NumberedLine> = emptyList(),
    /** [diff] as plain lines for rendering, derived once when the diff loads. */
    val diffLines: List<DiffLine> = emptyList(),
    val truncated: Boolean = false,
    val commit: CommitState = CommitState.Idle,
)

/** The bridge calls Changes makes (git.*, worktree.list) for one machine. */
fun interface ChangesBackend {
    suspend fun cmd(method: String, params: JsonObject): JsonElement
}

fun HerdrRepository.changesBackend(machineId: String) = ChangesBackend { m, p -> cmd(machineId, m, p) }

/** git status / diff / commit of one path of a machine, plus "Ask agent" on a diff line. */
class ChangesViewModel(
    private val machineId: String,
    private val backend: ChangesBackend,
    private val drafts: DraftStore,
) {
    private val _state = MutableStateFlow(ChangesState())
    val state: StateFlow<ChangesState> = _state.asStateFlow()

    private suspend fun <T> guarded(onError: (String) -> Unit, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BridgeError) {
        onError(e.message?.takeIf { it.isNotBlank() } ?: e.code); null
    } catch (e: Exception) {
        onError(e.message ?: e.toString()); null
    }

    /** Distinct pane cwds (picker entries); selects [active] (else the first) unless a path is already chosen. */
    suspend fun setPanePaths(cwds: List<String>, active: String?) {
        val paths = cwds.filter { it.isNotBlank() }.distinct()
        _state.update { it.copy(paths = (paths + it.paths).distinct()) }
        if (_state.value.path == null) (active?.takeIf { it.isNotBlank() } ?: paths.firstOrNull())?.let { selectPath(it) }
    }

    /** Adds the paths `worktree.list` reports to the picker; failure just leaves them out. */
    suspend fun loadWorktrees() {
        val res = guarded({}) { backend.cmd("worktree.list", JsonObject(emptyMap())) } ?: return
        val found = worktreePaths(res)
        _state.update { it.copy(paths = (it.paths + found).distinct()) }
    }

    suspend fun selectPath(path: String) {
        _state.update { it.copy(path = path, paths = (it.paths + path).distinct(), file = null, diff = emptyList(), diffLines = emptyList(), commit = CommitState.Idle) }
        refresh()
    }

    suspend fun refresh() {
        val path = _state.value.path ?: return
        _state.update { it.copy(loading = true, error = null) }
        val res = guarded({ m -> _state.update { it.copy(loading = false, error = m, files = emptyList(), branch = null) } }) {
            backend.cmd("git.status", buildJsonObject { put("path", path) })
        } ?: return
        val obj = res as? JsonObject
        val files = (obj?.get("files") as? JsonArray).orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val p = (o["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            GitFile(p, (o["status"] as? JsonPrimitive)?.content ?: "?", (o["staged"] as? JsonPrimitive)?.boolean ?: false)
        }
        _state.update { it.copy(loading = false, branch = (obj?.get("branch") as? JsonPrimitive)?.content, files = files) }
    }

    suspend fun openFile(file: String) {
        val path = _state.value.path ?: return
        _state.update { it.copy(file = file, diff = emptyList(), diffLines = emptyList(), truncated = false, loading = true, error = null) }
        val res = guarded({ m -> _state.update { it.copy(loading = false, error = m) } }) {
            backend.cmd("git.diff", buildJsonObject { put("path", path); put("file", file) })
        } ?: return
        val obj = res as? JsonObject
        val text = (obj?.get("diff") as? JsonPrimitive)?.content.orEmpty()
        val truncated = (obj?.get("truncated") as? JsonPrimitive)?.boolean ?: false
        // Up to 512 KiB of diff: parse off the main thread, once.
        val numbered = withContext(Dispatchers.Default) { numberDiff(parseUnifiedDiff(text)) }
        val plain = numbered.map { DiffLine(it.kind, it.text) }
        _state.update { it.copy(loading = false, diff = numbered, diffLines = plain, truncated = truncated) }
    }

    fun closeFile() = _state.update { it.copy(file = null, diff = emptyList(), diffLines = emptyList(), truncated = false) }

    /** `git.commit {path, message, all:true}`; the message must be non-blank. Refreshes the status after. */
    suspend fun commit(message: String) {
        val path = _state.value.path ?: return
        val msg = message.trim()
        if (msg.isEmpty()) {
            _state.update { it.copy(commit = CommitState.Error("Enter a commit message")) }
            return
        }
        _state.update { it.copy(commit = CommitState.Busy) }
        val res = guarded({ m -> _state.update { it.copy(commit = CommitState.Error(m)) } }) {
            backend.cmd("git.commit", buildJsonObject { put("path", path); put("message", msg); put("all", true) })
        } ?: return
        val sha = ((res as? JsonObject)?.get("commit") as? JsonPrimitive)?.content.orEmpty()
        _state.update { it.copy(commit = CommitState.Done(sha), file = null, diff = emptyList(), diffLines = emptyList()) }
        refresh()
    }

    fun dismissCommit() = _state.update { it.copy(commit = CommitState.Idle) }

    /**
     * Appends `In <file>:<line>:\n> <text>\n` to [paneId]'s draft (never sent). False for lines
     * with no number (hunk / file headers).
     */
    fun askAgent(file: String, line: NumberedLine, paneId: String): Boolean {
        val no = line.refNo ?: return false
        drafts.append(machineId, paneId, "In $file:$no:\n> ${line.text}\n")
        return true
    }

    private companion object {
        /** `worktree.list` is a herdr passthrough: take every `path` string wherever the list sits. */
        fun worktreePaths(e: JsonElement): List<String> = buildList {
            fun walk(x: JsonElement) {
                when (x) {
                    is JsonObject -> x.forEach { (k, v) ->
                        if ((k == "path" || k == "worktree_path") && v is JsonPrimitive && v.isString) add(v.content) else walk(v)
                    }
                    is JsonArray -> x.forEach(::walk)
                    else -> Unit
                }
            }
            walk(e)
        }
    }
}
