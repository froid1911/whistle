package dev.whistle.app.share

import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.MAX_UPLOAD_BYTES
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.Uploader
import dev.whistle.app.ui.chat.DraftStore
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An agent pane of some machine, as offered by the picker. */
data class SharePane(val machineId: String, val machineName: String, val pane: PaneUi)

/** A shared image: [open] yields a fresh stream each call; [size] is -1 when unknown. */
class SharedImage(val name: String, val mime: String?, val size: Long, val open: () -> InputStream)

/** [problems]: shared items refused before reading (shown to the user). */
data class ShareInput(val text: String?, val images: List<SharedImage>, val problems: List<String> = emptyList())

/**
 * Only content:// URIs of other apps may be read: file:// and our own provider authorities would
 * let a malicious sender make herdr upload its private files. null = fine, else the reason.
 */
fun shareUriProblem(scheme: String?, authority: String?, ownPackage: String): String? {
    if (!"content".equals(scheme, ignoreCase = true)) return "Refused a shared item that is not a content:// URI"
    val a = authority?.substringAfterLast('@')?.lowercase().orEmpty()
    if (a.isEmpty()) return "Refused a shared item without a source"
    val own = ownPackage.lowercase()
    if (a == own || a.startsWith("$own.")) return "Refused a shared item from herdr itself"
    return null
}

interface ShareBackend {
    /** Agent panes across all machines (non-agent panes excluded). */
    fun agentPanes(): Flow<List<SharePane>>
    fun machine(id: String): Machine?

    /** False on first run (nothing paired): the share sheet says so instead of "no agent panes". */
    fun hasMachines(): Boolean = true
}

data class ImageFailure(val image: SharedImage, val message: String)

data class ShareState(
    val busy: Boolean = false,
    val chosen: SharePane? = null,
    val failed: List<ImageFailure> = emptyList(),
    val error: String? = null,
    /** Set when everything is in the draft: open this pane's chat. */
    val opened: SharePane? = null,
)

/**
 * Share-to-agent: after the pane is chosen the text goes into that pane's draft and images are
 * uploaded one by one (each success adds an `Image: <path>` line). Failures keep the draft and
 * can be retried. Never sends anything.
 */
class ShareViewModel(
    val input: ShareInput,
    private val backend: ShareBackend,
    private val uploader: Uploader,
    private val drafts: DraftStore,
    private val scope: CoroutineScope,
) {
    val hasMachines: Boolean get() = backend.hasMachines()

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()
    val panes: Flow<List<SharePane>> get() = backend.agentPanes()
    private var textApplied = false
    private var job: Job? = null

    fun choose(target: SharePane) {
        if (_state.value.busy || _state.value.chosen != null) return
        _state.value = ShareState(busy = true, chosen = target)
        run(target, input.images)
    }

    /** Uploads again what failed. */
    fun retry() {
        val s = _state.value
        val target = s.chosen ?: return
        if (s.busy || s.failed.isEmpty()) return
        _state.value = s.copy(busy = true, error = null)
        run(target, s.failed.map { it.image })
    }

    /** Back to the picker (e.g. to pick another pane after a failure). */
    fun reset() {
        if (_state.value.busy) return
        textApplied = false
        _state.value = ShareState()
    }

    /** Leave with what succeeded so far. */
    fun openAnyway() {
        val s = _state.value
        if (!s.busy && s.chosen != null) _state.value = s.copy(opened = s.chosen)
    }

    private fun run(target: SharePane, images: List<SharedImage>) {
        job = scope.launch {
            if (!textApplied) {
                textApplied = true
                input.text?.takeIf { it.isNotBlank() }?.let { drafts.append(target.machineId, target.pane.id, it) }
            }
            val failed = ArrayList<ImageFailure>()
            val machine = backend.machine(target.machineId)
            for (img in images) {
                try {
                    if (machine == null) throw IllegalStateException("This machine was removed")
                    if (img.size > MAX_UPLOAD_BYTES) throw IllegalStateException("${img.name} is larger than 25 MB")
                    val path = uploader.upload(machine, target.pane.id, img.name, img.mime, img.size, img.open)
                    drafts.append(target.machineId, target.pane.id, "Image: $path")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += ImageFailure(img, "${img.name}: ${e.message ?: e}")
                }
            }
            _state.value = if (failed.isEmpty()) {
                ShareState(chosen = target, opened = target)
            } else {
                ShareState(chosen = target, failed = failed, error = failed.joinToString("\n") { it.message })
            }
        }
    }
}
