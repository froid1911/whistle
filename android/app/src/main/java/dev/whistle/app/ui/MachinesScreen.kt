package dev.whistle.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import dev.whistle.app.ui.components.Dot
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.Machine
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import dev.whistle.app.R

private val connLabel = mapOf(
    ConnState.Offline to "offline",
    ConnState.Connecting to "connecting…",
    ConnState.Syncing to "syncing…",
    ConnState.Synced to "connected",
    ConnState.HerdrOffline to "herdr offline",
)

/** Machine switcher + add/edit. With no machines yet it is the first-run screen ([FirstRun]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(
    machines: List<Machine>,
    repo: HerdrRepository,
    onOpen: (Machine) -> Unit,
    onSave: (Machine) -> Unit,
    onRemove: (Machine) -> Unit,
    newId: () -> String,
    onOpenSecurity: () -> Unit = {},
    onPair: () -> Unit = {},
) {
    var editing by remember { mutableStateOf<Machine?>(null) }
    Scaffold(
        containerColor = Whistle.colors.night,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            WhistleTopBar(
                title = "Machines",
                actions = {
                    IconButton(onClick = onOpenSecurity) {
                        Icon(Icons.Default.Lock, "Security", tint = Whistle.colors.sage)
                    }
                    IconButton(onClick = { editing = Machine(newId(), "", "http://") }) {
                        Icon(Icons.Default.Add, "Add machine", tint = Whistle.colors.wool)
                    }
                },
            )
        },
    ) { pad ->
        if (machines.isEmpty()) {
            FirstRun(onPair, Modifier.padding(pad).consumeWindowInsets(pad))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(machines, key = { it.id }) { m ->
                val conn by remember(m.id) { repo.connState(m.id) }.collectAsStateWithLifecycle()
                val auth by remember(m.id) { repo.authProblem(m.id) }.collectAsStateWithLifecycle()
                val c = Whistle.colors
                WhistleCard(onClick = { onOpen(m) }, contentPadding = 0.dp) {
                    Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(
                            when (conn) {
                                ConnState.Synced -> c.diffAddFg
                                ConnState.HerdrOffline -> c.ember
                                ConnState.Offline -> c.moss
                                else -> c.sky
                            },
                            size = 10.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = WhistleType.name, color = c.wool)
                            Text("${m.baseUrl} · ${connLabel[conn]}", style = WhistleType.secondary, color = c.sage)
                            Text(
                                authStatusLabel(m),
                                style = WhistleType.secondary,
                                color = if (m.paired) c.sage else c.ember,
                                modifier = Modifier.testTag("authStatus:${m.id}"),
                            )
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, "Edit ${m.name}", tint = c.sage) }
                    }
                    val problem = auth
                    if (problem != null || !m.paired) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).testTag("pairNudge:${m.id}")) {
                            Text(
                                problem?.message(m.name)
                                    ?: "Any device of your Tailscale account can act as this phone. Pair it so only this phone can.",
                                style = WhistleType.secondary,
                                color = if (problem != null) c.ember else c.sage,
                            )
                            Spacer(Modifier.height(8.dp))
                            PillButton(
                                if (m.paired || problem != null) "Pair again" else "Pair this machine",
                                onClick = onPair,
                                style = PillStyle.Quiet,
                            )
                        }
                    }
                }
            }
            item(key = "pair") {
                PillButton(
                    "Pair a machine",
                    onClick = onPair,
                    style = PillStyle.Wool,
                    modifier = Modifier.padding(top = 8.dp).testTag("pairMachine"),
                )
            }
        }
    }
    editing?.let { m ->
        MachineDialog(
            initial = m,
            isNew = machines.none { it.id == m.id },
            onSave = { onSave(it); editing = null },
            onRemove = { onRemove(m); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** First run: nothing paired yet. One way forward: pair (scan the QR, enter the code or paste a whistle:// link). */
@Composable
private fun FirstRun(onPair: () -> Unit, modifier: Modifier = Modifier) {
    val c = Whistle.colors
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("firstRun"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_whistle_tile), contentDescription = null, tint = c.lamp, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("No machines yet", style = WhistleType.title, color = c.wool, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Run the Whistle bridge on your server and press Ctrl+Alt+W in herdr.",
            style = WhistleType.body,
            color = c.sage,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PillButton("Pair a machine", onClick = onPair, style = PillStyle.Lamp, modifier = Modifier.testTag("pairMachine"))
        Spacer(Modifier.height(10.dp))
        Text(
            "Scan the QR code, enter the code, or paste a whistle:// link.",
            style = WhistleType.secondary,
            color = c.sage,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MachineDialog(initial: Machine, isNew: Boolean, onSave: (Machine) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var url by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var confirmRemove by remember { mutableStateOf(false) }
    val problem = Machine.baseUrlProblem(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add machine" else "Edit machine") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Bridge address") },
                    placeholder = { Text("http://100.x.y.z:8787") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { Text(problem ?: "Tailscale IP and port of the machine's whistle-bridge.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Use the machine's Tailscale IP, not a hostname: the bridge only answers requests " +
                        "addressed to its exact ip:port (protection against DNS rebinding).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!isNew) {
                    TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Remove machine", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && name.isNotBlank(),
                onClick = { onSave(initial.copy(name = name.trim(), baseUrl = url.trim().trimEnd('/'))) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove machine?",
            text = "“${initial.name}” is removed from this phone. Nothing changes on the machine.",
            confirm = "Remove",
            onConfirm = onRemove,
            onDismiss = { confirmRemove = false },
        )
    }
}

/** Per-machine auth status on the Machines screen. */
fun authStatusLabel(m: Machine): String =
    if (m.paired) "Paired as ${m.deviceName ?: "this phone"} · secure login" else "Legacy login — pair this machine"
