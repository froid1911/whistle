package dev.whistle.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.fragment.app.FragmentActivity
import dev.whistle.app.security.ActivityLock
import dev.whistle.app.security.LockGate
import dev.whistle.app.ui.links.SafeLinks
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.ui.theme.statusPhrase
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.AppContainer
import dev.whistle.app.MainActivity
import dev.whistle.app.container
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.Machine
import dev.whistle.app.ui.HerdrTheme
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Receives text / images shared from other apps and drops them into an agent pane's draft. */
class ShareActivity : FragmentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        c.enterForeground() // the worker must not close the clients a share is using
        val vm = ShareViewModel(readInput(intent), AppShareBackend(c), c.uploader, c.drafts, scope)
        enableEdgeToEdge()
        lockGate = ActivityLock(this, c.appLock)
        // Sharing opens chats and writes drafts: the same lock as the app.
        setContent { HerdrTheme { LockGate(lockGate) { SafeLinks { ShareScreen(vm, onCancel = ::finish, onOpen = ::openChat) } } } }
    }

    private lateinit var lockGate: ActivityLock

    override fun onStart() {
        super.onStart()
        lockGate.onStart()
    }

    override fun onStop() {
        super.onStop()
        lockGate.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        lockGate.onDestroy()
        container.exitForeground()
        scope.cancel()
    }

    private fun openChat(t: SharePane) {
        startActivity(MainActivity.openPane(this, t.machineId, t.pane.id))
        finish()
    }

    private fun readInput(i: Intent): ShareInput {
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        val uris = LinkedHashSet<Uri>()
        if (i.type?.startsWith("image/") == true || i.action == Intent.ACTION_SEND_MULTIPLE) {
            if (i.action == Intent.ACTION_SEND_MULTIPLE) uris += streams(i) else streams(i).firstOrNull()?.let(uris::add)
        }
        val problems = ArrayList<String>()
        val ok = uris.filter { u ->
            val p = shareUriProblem(u.scheme, u.authority, packageName)
            if (p != null) problems += p
            p == null
        }
        return ShareInput(text, ok.map { image(this, it) }, problems)
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        } else {
            listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }

    private companion object {
        /** Name and size via the provider; streams are opened lazily, one at a time (never loaded whole). */
        fun image(ctx: Context, uri: Uri): SharedImage {
            var name: String? = null
            var size = -1L
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val mime = ctx.contentResolver.getType(uri)
            val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
            return SharedImage(fileName, mime, size) {
                ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't read $fileName")
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppShareBackend(private val c: AppContainer) : ShareBackend {
    override fun agentPanes(): Flow<List<SharePane>> =
        c.machines.machines.flatMapLatest { ms ->
            if (ms.isEmpty()) flowOf(emptyList())
            else combine(ms.map { m -> c.repo.agentPanes(m.id).map { l -> l.map { SharePane(m.id, m.name, it) } } }) { it.toList().flatten() }
        }

    override fun machine(id: String): Machine? = c.machines.get(id)

    override fun hasMachines(): Boolean = c.machines.machines.value.isNotEmpty()
}

@Composable
private fun ShareScreen(vm: ShareViewModel, onCancel: () -> Unit, onOpen: (SharePane) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val panes by remember(vm) { vm.panes }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(state.opened) { state.opened?.let(onOpen) }
    Surface(Modifier.fillMaxSize(), color = Whistle.colors.night) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Text("Whistle to an agent", style = WhistleType.title, color = Whistle.colors.wool)
            Spacer(Modifier.padding(4.dp))
            vm.input.problems.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            val n = vm.input.images.size
            val what = listOfNotNull(
                vm.input.text?.takeIf { it.isNotBlank() }?.let { "text" },
                n.takeIf { it > 0 }?.let { "$it image${if (it > 1) "s" else ""}" },
            ).joinToString(" and ")
            Text(
                if (what.isEmpty()) "Nothing to share." else "Pick the agent that gets the $what. It goes into the message box, not sent.",
                style = WhistleType.secondary,
                color = Whistle.colors.sage,
            )
            Spacer(Modifier.padding(8.dp))
            when {
                state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Uploading to ${state.chosen?.pane?.title()}…")
                }
                state.error != null -> Column {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::retry) { Text("Retry") }
                        TextButton(onClick = vm::openAnyway) { Text("Open chat anyway") }
                        TextButton(onClick = vm::reset) { Text("Other agent") }
                    }
                }
                what.isEmpty() -> TextButton(onClick = onCancel) { Text("Close") }
                panes.isEmpty() && !vm.hasMachines -> Column {
                    Text("No machines paired yet. Open Whistle and pair a machine first.", color = Whistle.colors.sage)
                    TextButton(onClick = onCancel) { Text("Close") }
                }
                panes.isEmpty() -> Text("No agent panes yet. Open herdr and start an agent first.")
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(panes, key = { it.machineId + "/" + it.pane.id }) { p ->
                        WhistleCard(onClick = { vm.choose(p) }, lamp = p.pane.agentStatus == AgentStatus.BLOCKED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AgentOrb(p.pane.agent, p.pane.agentStatus)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(p.pane.title(), style = WhistleType.name, color = Whistle.colors.wool)
                                    Text(
                                        "${p.machineName} · ${p.pane.agent ?: "agent"} · ${statusPhrase(p.pane.agentStatus)}",
                                        style = WhistleType.secondary,
                                        color = Whistle.colors.sage,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (!state.busy && state.error == null && what.isNotEmpty()) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun PaneUi.title() = label.ifBlank { agent ?: id }
