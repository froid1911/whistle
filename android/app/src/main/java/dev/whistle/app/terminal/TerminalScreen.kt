package dev.whistle.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.whistle.app.ui.components.AgentOrb
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.components.ShellMark
import dev.whistle.app.ui.components.StatusLine
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.isAgent
import dev.whistle.app.ui.theme.DarkWhistle
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.terminal.TerminalSession.Phase
import dev.whistle.app.ui.title
import dev.whistle.app.security.InputGate
import dev.whistle.app.security.LocalAppLock
import dev.whistle.app.security.LocalStepUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

const val TERMINAL_TAG = "terminal"

/** [TerminalSession.fail] reason when the WebView renderer crashed or was killed. */
const val RENDERER_GONE = "renderer_gone"

/**
 * A pane's raw terminal: xterm.js mirror of the pane + soft-key row. Opens its own session on the
 * machine's connection and closes it when it leaves composition (pager pages are disposed on swipe;
 * the bridge allows only 2 terminal sessions per connection).
 */
@Composable
fun TerminalScreen(repo: HerdrRepository, machineId: String, paneId: String, modifier: Modifier = Modifier) {
    val client = remember(repo, machineId) { repo.connect(machineId) }
    val context = LocalContext.current
    // A new bridge (WebView) per generation: bumped by Reopen after the renderer was gone.
    var generation by remember { mutableIntStateOf(0) }
    var bridgeGone by remember { mutableStateOf(false) }
    val bridge = remember(generation) { TerminalBridge(context) }
    val currentBridge = remember { arrayOfNulls<TerminalBridge>(1) }
    val ctrl = remember { mutableStateOf(false) }
    val alt = remember { mutableStateOf(false) }
    var dropped by remember { mutableIntStateOf(0) }
    val (session, scope) = remember(client, paneId) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val s = TerminalSession(client.asTermLink(), paneId, scope) { f, fresh -> currentBridge[0]?.frame(f, fresh) }
        s to scope
    }

    // Typing runs commands on the server: the first keystroke of a session (per lock epoch) asks
    // for step-up; while locked every key is dropped (hardware keyboard, late IME commit, xterm).
    val stepUp = LocalStepUp.current
    val lockStatus = LocalAppLock.current
    val gate = remember(session, lockStatus) { InputGate(lockStatus) }

    fun deliver(data: String) {
        if (session.input(data)) {
            ctrl.value = false
            alt.value = false
        } else {
            dropped++ // keep the modifiers; tell the user the key went nowhere
        }
    }

    fun send(data: String) {
        when (gate.offer(data)) {
            InputGate.Decision.Pass -> deliver(data)
            InputGate.Decision.Wait, InputGate.Decision.Drop -> Unit
            InputGate.Decision.Ask -> stepUp.require(
                "Type in the terminal",
                onDenied = gate::deny,
                action = { gate.grant().takeIf { it.isNotEmpty() }?.let(::deliver) },
            )
        }
    }

    DisposableEffect(session) {
        session.start()
        onDispose {
            session.stop()
            scope.cancel()
        }
    }
    DisposableEffect(bridge, session) {
        currentBridge[0] = bridge
        bridge.onData = { s -> send(SoftKeys.applyModifiers(s, ctrl.value, alt.value)) }
        bridge.onFit = session::fitTo
        bridge.onResync = session::resync
        // Pinch zoom is remembered per pane (also across a renderer-gone reopen).
        bridge.userFontSize = PaneFontSizes.get(machineId, paneId)
        bridge.onFontSize = { px -> PaneFontSizes.put(machineId, paneId, px) }
        bridge.onGone = {
            currentBridge[0] = null
            bridgeGone = true
            session.fail(RENDERER_GONE)
        }
        onDispose {
            if (currentBridge[0] === bridge) currentBridge[0] = null
            bridge.destroy()
        }
    }

    // Locked: the WebView gives up focus and can't take it back until unlocked.
    val locked by lockStatus.locked.collectAsState()
    LaunchedEffect(locked, bridge) {
        bridge.webView.apply {
            if (locked) clearFocus()
            isFocusable = !locked
            isFocusableInTouchMode = !locked
        }
    }

    val state by session.state.collectAsStateWithLifecycle()
    val fitOn = state.fit != null
    // Typing drops the fit (the bridge upgrades to control at the desktop size): follow in layout.
    LaunchedEffect(fitOn, bridge) { if (!fitOn) bridge.setFitLayout(false) }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(dropped) {
        if (dropped == 0) return@LaunchedEffect
        hint = true
        delay(1_500)
        hint = false
    }

    Column(modifier.fillMaxSize().imePadding().testTag(TERMINAL_TAG)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(DarkWhistle.inset)) {
            key(bridge) { AndroidView(factory = { bridge.webView }, modifier = Modifier.fillMaxSize()) }
            TerminalOverlay(state, onReopen = {
                if (bridgeGone) {
                    bridgeGone = false
                    generation++
                }
                session.reopen()
            })
            if (hint) {
                Text(
                    "Not connected: key not sent",
                    style = WhistleType.secondary,
                    color = Whistle.colors.wool,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .clip(WhistleShapes.Pill).background(Whistle.colors.cardHi).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        SoftKeyRow(
            ctrl = ctrl.value,
            alt = alt.value,
            fit = fitOn,
            onKey = { send(SoftKeys.encode(it, ctrl.value, alt.value, bridge.appCursor)) },
            onCtrl = { ctrl.value = !ctrl.value },
            onAlt = { alt.value = !alt.value },
            onFit = { if (fitOn) session.unfit() else bridge.proposeFit() },
            onKeyboard = bridge::focus,
        )
    }
}

@Composable
private fun TerminalOverlay(state: TerminalSession.TermState, onReopen: () -> Unit) {
    val c = DarkWhistle // the terminal is always night, also in the light theme
    when (state.phase) {
        Phase.Live -> Unit
        Phase.Waiting, Phase.Opening -> if (state.size == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = c.sage, strokeWidth = 2.dp)
            }
        }
        Phase.Reconnecting -> Box(
            Modifier.fillMaxSize().background(c.inset.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) { StatusLine("Reconnecting…", c.sky) }
        Phase.Closed -> Box(
            Modifier.fillMaxSize().background(c.inset.copy(alpha = 0.85f)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(closedMessage(state.reason), color = c.wool, style = WhistleType.body)
                PillButton("Reopen", onClick = onReopen, style = PillStyle.Wool)
            }
        }
    }
}

/** User-facing text for a term.closed reason. */
fun closedMessage(reason: String?): String = when (reason) {
    "ended" -> "The terminal session ended."
    "not_available" -> "Terminal streaming is not available on this bridge."
    "too_many_sessions" -> "Too many terminals are open on this connection."
    "duplicate_sid" -> "The terminal session clashed with another one."
    RENDERER_GONE -> "The terminal view crashed."
    null, "" -> "The terminal closed."
    else -> "The terminal closed: $reason"
}

@Composable
private fun SoftKeyRow(
    ctrl: Boolean,
    alt: Boolean,
    fit: Boolean,
    onKey: (SoftKeys.Key) -> Unit,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onFit: () -> Unit,
    onKeyboard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Whistle.colors.night).horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyPill("⌨", onClick = onKeyboard)
        KeyPill("Fit", selected = fit, onClick = onFit)
        KeyPill(SoftKeys.Key.Esc.label) { onKey(SoftKeys.Key.Esc) }
        KeyPill(SoftKeys.Key.Tab.label) { onKey(SoftKeys.Key.Tab) }
        KeyPill("Ctrl", selected = ctrl, onClick = onCtrl)
        KeyPill("Alt", selected = alt, onClick = onAlt)
        for (k in listOf(
            SoftKeys.Key.Up, SoftKeys.Key.Down, SoftKeys.Key.Left, SoftKeys.Key.Right,
            SoftKeys.Key.PgUp, SoftKeys.Key.PgDn, SoftKeys.Key.Pipe, SoftKeys.Key.Tilde, SoftKeys.Key.Slash,
        )) {
            KeyPill(k.label) { onKey(k) }
        }
    }
}

/** A soft key as a pill chip; toggles (Fit, Ctrl, Alt) fill with wool while on. */
@Composable
private fun KeyPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = Whistle.colors
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(WhistleShapes.Pill)
            .background(if (selected) c.wool else c.card)
            .semantics { this.selected = selected; role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ⌨ is not in Commit Mono: the system font draws it, a bit larger.
        val style = if (label == "⌨") WhistleType.body.copy(fontSize = 18.sp) else WhistleType.mono.copy(fontSize = 13.sp)
        Text(label, style = style, color = if (selected) c.night else c.wool)
    }
}

/** The terminal as its own destination (agent panes' chat toggle). */
@Composable
fun TerminalRouteScreen(repo: HerdrRepository, machineId: String, paneId: String, onBack: () -> Unit) {
    val pane by remember(machineId, paneId) { repo.pane(machineId, paneId) }.collectAsStateWithLifecycle(null)
    Scaffold(
        containerColor = Whistle.colors.night,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            WhistleTopBar(
                title = pane?.title() ?: paneId,
                subtitle = "terminal" + (pane?.cwd?.let { " · $it" } ?: ""),
                onBack = onBack,
                leading = {
                    val p = pane
                    if (p != null && p.isAgent) AgentOrb(p.agent, p.agentStatus, size = 32.dp) else ShellMark(size = 32.dp)
                },
            )
        },
    ) { inner ->
        TerminalScreen(
            repo, machineId, paneId,
            Modifier.padding(inner).consumeWindowInsets(inner).windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}
