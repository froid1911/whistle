package dev.whistle.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import dev.whistle.app.ui.components.RoundButton
import dev.whistle.app.security.LocalAppLock
import dev.whistle.app.security.LocalStepUp
import dev.whistle.app.security.StepUpOnce
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.voice.MicIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import dev.whistle.app.voice.AndroidSpeechEngine
import dev.whistle.app.voice.SpeechEngine
import dev.whistle.app.voice.VoiceController
import dev.whistle.app.voice.VoiceState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.whistle.app.ui.imeVisible
import dev.whistle.app.ui.theme.rememberReducedMotion
import dev.whistle.app.voice.MicEffect
import dev.whistle.app.voice.MicGesture
import dev.whistle.app.voice.MicPhase
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.data.ChatState
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.terminal.TerminalScreen
import dev.whistle.app.ui.PaneContent
import dev.whistle.app.ui.isAgent
import dev.whistle.app.ui.snippets.SnippetPickerDialog
import dev.whistle.app.ui.snippets.SnippetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

const val CHAT_LIST_TAG = "chatList"

/** Pane pages of the app: chat for agent panes, the terminal for shell panes. */
fun chatPaneContent(
    repo: HerdrRepository,
    outbox: PromptOutbox,
    drafts: DraftStore = DraftStore(),
    snippets: SnippetRepository? = null,
    onOpenSnippets: () -> Unit = {},
): PaneContent = { machineId, pane ->
    if (pane.isAgent) ChatScreen(repo, outbox, machineId, pane, drafts = drafts, snippets = snippets, onOpenSnippets = onOpenSnippets) else TerminalScreen(repo, machineId, pane.id)
}

/** The session key of an agent pane's chat; agents without a session get a screen-only chat. */
val PaneUi.chatKey: String get() = sessionKey ?: "pane:$id"

@Composable
fun ChatScreen(
    repo: HerdrRepository,
    outbox: PromptOutbox,
    machineId: String,
    pane: PaneUi,
    modifier: Modifier = Modifier,
    drafts: DraftStore = remember { DraftStore() },
    snippets: SnippetRepository? = null,
    onOpenSnippets: () -> Unit = {},
) {
    val source = remember(repo, machineId) { RepoChatSource(repo, machineId) }
    val paneOutbox = remember(outbox, machineId, pane.id) { outbox.forPane(machineId, pane.id) }
    val draft = remember(drafts, machineId, pane.id) { PaneDraft(drafts, machineId, pane.id) }
    ChatScreen(source, paneOutbox, pane, modifier, draft, snippets = snippets, onOpenSnippets = onOpenSnippets)
}

/** One agent pane's chat: history + live bubble, approval card, composer. */
@Composable
fun ChatScreen(
    source: ChatSource,
    outbox: PaneOutbox,
    pane: PaneUi,
    modifier: Modifier = Modifier,
    draft: PaneDraft = remember(pane.id) { PaneDraft(DraftStore(), "", pane.id) },
    speech: () -> SpeechEngine = rememberSpeechEngineFactory(),
    snippets: SnippetRepository? = null,
    onOpenSnippets: () -> Unit = {},
) {
    val sessionKey = pane.chatKey
    // One VM (and coroutine scope) per pane+session; cancelled when the page leaves composition.
    // Pending prompts are not lost with it: they live in the app-wide outbox.
    val (vm, scope) = remember(source, outbox, pane.id, sessionKey) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ChatViewModel(source, pane.id, sessionKey, scope, outbox) to scope
    }
    DisposableEffect(vm) {
        vm.start() // watch + subscribe
        onDispose {
            vm.stop() // unwatch
            scope.cancel()
        }
    }

    val rows by vm.rows.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val chatState by vm.chatState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val approval by vm.approval.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // A prompt runs on the server: the first send or retry of this pane per lock epoch steps up
    // (typed, dictated or share-prefilled alike). Denied: the text goes back into the draft.
    val stepUp = LocalStepUp.current
    val lockStatus = LocalAppLock.current
    val sendGate = remember(pane.id, lockStatus) { StepUpOnce(lockStatus) }
    val send: (String) -> Unit = { text ->
        sendGate.run(
            stepUp, "Send to ${pane.agent ?: "the agent"}", detail = text.take(80),
            onDenied = { draft.set(draft.text.value.let { cur -> if (cur.isEmpty()) text else DraftStore.joinLines(text, cur) }) },
        ) { vm.send(text) }
    }
    val retry: (Long) -> Unit = { id -> sendGate.run(stepUp, "Send to ${pane.agent ?: "the agent"} again") { vm.retry(id) } }
    LaunchedEffect(notice) {
        notice?.let {
            vm.consumeNotice()
            snackbar.showSnackbar(it)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            when (val s = chatState) {
                ChatState.Fresh -> Unit
                ChatState.Stale, ChatState.Loading -> UpdatingBar()
                is ChatState.Error -> ChatErrorBar(s.code, onRetry = vm::retryHistory)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessageList(vm, rows, pending, retry, agentName = pane.agent ?: "agent", hasTranscript = pane.hasTranscript, imeOpen = imeVisible())
            }
            val card = approval
            if (card != null) {
                ApprovalCard(card, onKeys = vm::sendKeys, onRefresh = vm::fetchApproval, onDismiss = vm::dismissApproval)
            } else if (status == AgentStatus.BLOCKED) {
                TextButton(onClick = vm::fetchApproval, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Agent is waiting for approval — show", color = Whistle.colors.lamp)
                }
            }
            Composer(
                draft, speech, snippets, onOpenSnippets, agentName = pane.agent ?: "agent",
                onSend = send, onNotice = { scope.launch { snackbar.showSnackbar(it) } },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

@Composable
private fun MessageList(vm: ChatViewModel, rows: List<ChatRow>, pending: List<Pending>, retry: (Long) -> Unit, agentName: String, hasTranscript: Boolean, imeOpen: Boolean) {
    val listState = rememberLazyListState()
    // Keyboard up: show the latest (index 0 is the bottom).
    LaunchedEffect(imeOpen) { if (imeOpen) listState.animateScrollToItem(0) }
    // reverseLayout: index 0 is the bottom (live bubble), so new messages keep the view at the bottom.
    val newestFirst = remember(rows) { foldTools(rows).asReversed() }
    val pendingNewestFirst = remember(pending) { pending.asReversed() }

    // Near the oldest loaded row → fetch the page before it.
    LaunchedEffect(listState, vm) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // The count is part of the key so a page that still leaves us near the top loads the next one.
            (last >= info.totalItemsCount - 3) to info.totalItemsCount
        }.distinctUntilChanged().filter { it.first }.collect { vm.onReachedTop() }
    }

    LazyColumn(Modifier.fillMaxSize().testTag(CHAT_LIST_TAG), state = listState, reverseLayout = true) {
        // Always present; reads the live state itself so its updates recompose only this item.
        item(key = "live", contentType = "live") {
            val live by vm.live.collectAsStateWithLifecycle()
            val status by vm.status.collectAsStateWithLifecycle()
            LiveBubble(live, working = status == AgentStatus.WORKING, imeOpen = imeOpen)
        }
        items(pendingNewestFirst, key = { "pending:${it.id}" }, contentType = { "pending" }) { p ->
            PendingBubble(p, onRetry = { retry(p.id) }, onDismiss = { vm.dismiss(p.id) })
        }
        items(newestFirst, key = { it.key }, contentType = { (it as? ChatItem.Row)?.row?.let { r -> r::class } ?: it::class }) { item -> ChatItemView(item) }
        if (rows.isEmpty() && pending.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (hasTranscript) "No messages yet." else "No transcript for this agent: its replies appear as screen captures.",
                    style = WhistleType.secondary,
                    color = Whistle.colors.sage,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        item(key = "older", contentType = "older") {
            val loading by vm.loadingOlder.collectAsStateWithLifecycle()
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = Whistle.colors.sky, trackColor = Whistle.colors.card)
        }
    }
}

@Composable
private fun ChatErrorBar(code: String, onRetry: () -> Unit) {
    val c = Whistle.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(WhistleShapes.Inset).background(c.card).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Couldn't update this chat ($code). It may be missing recent messages.",
            style = WhistleType.secondary,
            color = c.ember,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry", color = c.wool) }
    }
}

@Composable
private fun rememberSpeechEngineFactory(): () -> SpeechEngine {
    val ctx = LocalContext.current
    return remember(ctx) { { AndroidSpeechEngine(ctx) } }
}

/** Slide up this far while holding the mic to lock hands-free recording. */
private val LOCK_DISTANCE = 72.dp
private const val HINT_MS = 1_600L

const val RECORDING_BAR_TAG = "recordingBar"
const val MIC_HINT_TAG = "micHint"

/** "0:03" */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun Composer(
    draft: PaneDraft,
    speech: () -> SpeechEngine,
    snippets: SnippetRepository?,
    onOpenSnippets: () -> Unit,
    agentName: String,
    onSend: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val text by draft.text.collectAsStateWithLifecycle()
    // Always the latest text, for the voice callbacks and press handler captured once.
    val current by rememberUpdatedState(text)
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val voice = remember(draft) { VoiceController(speech(), draft::set) }
    val gesture = remember(draft, density) { MicGesture(lockPx = with(density) { LOCK_DISTANCE.toPx() }) }
    DisposableEffect(voice) { onDispose { voice.cancel(); voice.destroy() } }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val phase by gesture.phase.collectAsStateWithLifecycle()
    val partial by voice.partial.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) { if (hint) { delay(HINT_MS); hint = false } }
    LaunchedEffect(voiceState) {
        when (val v = voiceState) {
            is VoiceState.Failed -> { onNotice(v.message); voice.consumeMessage() }
            is VoiceState.Unavailable -> { onNotice(v.message); voice.consumeMessage() }
            else -> Unit
        }
        // The recognizer ended on its own (error, timeout): the gesture is over too.
        if (voiceState != VoiceState.Listening && voiceState != VoiceState.Finishing) gesture.reset()
    }
    val runEffects by rememberUpdatedState<(List<MicEffect>) -> Unit> { effects ->
        effects.forEach {
            when (it) {
                MicEffect.Start -> voice.begin(current)
                MicEffect.Finish -> voice.end()
                MicEffect.Cancel -> voice.cancel()
                MicEffect.TapHint -> hint = true
                MicEffect.Haptic -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    var rationale by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The hold that asked is over by now: just tell the user to hold again.
        onNotice(if (granted) "Microphone ready: hold the mic button and speak." else "Microphone permission denied, voice input is off.")
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text("Voice input") },
            text = { Text("Hold the mic button to dictate. Whistle needs the microphone only while you hold it; the text stays editable and is never sent automatically.") },
            confirmButton = { TextButton(onClick = { rationale = false; request.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } },
        )
    }
    val recordingActive = phase == MicPhase.Recording || phase == MicPhase.Locked
    val finishing = voiceState == VoiceState.Finishing
    val recording = recordingActive || finishing
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recordingActive) {
        if (!recordingActive) return@LaunchedEffect
        val start = System.currentTimeMillis()
        elapsed = 0
        while (true) {
            delay(250)
            elapsed = System.currentTimeMillis() - start
        }
    }
    var barWidth by remember { mutableIntStateOf(0) }
    // The field's value with its selection; outside the recording branch so it survives the bar.
    // A change that did not come from typing (voice, share, snippet) puts the cursor at the end.
    var field by remember(draft) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var synced by remember(draft) { mutableStateOf(text) }
    if (text != synced) { // the store moved (not our own echo lagging behind a keystroke)
        synced = text
        if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    }
    val focus = remember { FocusRequester() }
    var focusAfterVoice by remember { mutableStateOf(false) }
    LaunchedEffect(voiceState) { if (voiceState == VoiceState.Finishing) focusAfterVoice = true }
    LaunchedEffect(focusAfterVoice, recording) {
        if (focusAfterVoice && !recording) {
            focusAfterVoice = false
            runCatching { focus.requestFocus() } // keep typing after the dictated words
        }
    }
    val c = Whistle.colors
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(hint, Modifier.align(Alignment.End), enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Text(
                "Hold to talk",
                style = WhistleType.small,
                color = c.wool,
                modifier = Modifier.padding(end = 16.dp, top = 4.dp).testTag(MIC_HINT_TAG)
                    .clip(WhistleShapes.Pill).background(c.cardHi).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // Floating card pill above the keyboard / nav bar (the page handles the insets).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                .onSizeChanged { barWidth = it.width }
                .clip(WhistleShapes.Composer)
                .background(c.card)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                RecordingBar(
                    elapsedMs = elapsed,
                    partial = partial,
                    locked = phase == MicPhase.Locked,
                    finishing = finishing,
                    onCancel = { runEffects(gesture.cancel()) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; if (it.text != text) draft.set(it.text) },
                    textStyle = WhistleType.body.copy(color = c.wool),
                    cursorBrush = SolidColor(c.wool),
                    maxLines = 6,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focus).testTag("composer"),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text("Whistle to $agentName", style = WhistleType.body, color = c.sage, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                if (snippets != null) {
                    var picking by remember { mutableStateOf(false) }
                    IconButton(onClick = { picking = true }, modifier = Modifier.size(40.dp).testTag("snippets")) {
                        Icon(Icons.AutoMirrored.Filled.List, "Snippets", tint = c.sage)
                    }
                    if (picking) {
                        SnippetPickerDialog(
                            snippets,
                            onPick = { picking = false; draft.append(it) },
                            onManage = { picking = false; onOpenSnippets() },
                            onDismiss = { picking = false },
                        )
                    }
                }
            }
            // Stays in place (and keeps its gesture) while the row switches to the recording bar.
            Box(
                Modifier.size(40.dp).testTag("mic")
                    .semantics { contentDescription = if (phase == MicPhase.Locked) "Stop" else "Hold to talk" }
                    .pointerInput(gesture) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                rationale = true
                                return@awaitEachGesture
                            }
                            down.consume()
                            runEffects(gesture.down(down.uptimeMillis))
                            if (gesture.phase.value != MicPhase.Pressed) return@awaitEachGesture // locked: that was Stop
                            // A tap ends within the threshold; a hold times out.
                            val released = withTimeoutOrNull(MicGesture.HOLD_MS) {
                                while (true) {
                                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                }
                                true
                            }
                            if (released != null) {
                                runEffects(gesture.up(down.uptimeMillis))
                                return@awaitEachGesture
                            }
                            runEffects(gesture.hold(down.uptimeMillis + MicGesture.HOLD_MS))
                            while (true) {
                                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed) {
                                    runEffects(gesture.up(ch?.uptimeMillis ?: Long.MAX_VALUE))
                                    break
                                }
                                val d = ch.position - down.position
                                runEffects(gesture.drag(d.x, d.y, barWidth.toFloat()))
                                ch.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (phase == MicPhase.Locked) {
                    Box(Modifier.size(14.dp).clip(WhistleShapes.Inset).background(c.ember))
                } else {
                    Icon(MicIcon, null, tint = if (recording) c.ember else c.sage)
                }
            }
            if (!recording) {
                Spacer(Modifier.width(4.dp))
                RoundButton(
                    onClick = {
                        // The store's value, not the composed snapshot: a share append may have landed since.
                        draft.takeAndClear().takeIf { it.isNotBlank() }?.let(onSend)
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.semantics { contentDescription = "Send" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (text.isNotBlank()) c.night else c.sage, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The composer while dictating: pulsing dot, timer, what was heard so far, cancel hint or button. */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    partial: String,
    locked: Boolean,
    finishing: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Whistle.colors
    val reduced = rememberReducedMotion()
    val alpha = if (reduced || finishing) 1f else {
        val t = rememberInfiniteTransition(label = "recDot")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recDotAlpha").value
    }
    Row(modifier.heightIn(min = 40.dp).testTag(RECORDING_BAR_TAG), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(c.ember))
        Spacer(Modifier.width(8.dp))
        Text(formatElapsed(elapsedMs), style = WhistleType.secondaryStrong, color = c.wool)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                finishing -> "Finishing…"
                partial.isBlank() -> "Listening…"
                else -> partial
            },
            style = WhistleType.secondary,
            color = if (partial.isBlank() || finishing) c.sage else c.wool,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            finishing -> Unit
            locked -> TextButton(onClick = onCancel) { Text("Cancel", color = c.ember) }
            else -> Text("‹ Slide to cancel", style = WhistleType.small, color = c.sage, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}
