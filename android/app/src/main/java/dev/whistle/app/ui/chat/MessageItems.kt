package dev.whistle.app.ui.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.whistle.app.ui.components.Chip
import dev.whistle.app.ui.components.ChipRow
import dev.whistle.app.ui.components.InsetTerminal
import dev.whistle.app.ui.components.StatusLine
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import dev.whistle.app.ui.links.MessageLinks
import dev.whistle.app.protocol.WMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PrettyJson = Json { prettyPrint = true }

/**
 * KEEP NoOp: agent output must never make the phone fetch a URL (tracking pixels, data smuggled
 * out in an image URL, a slow or huge download). `![x](https://…)` renders nothing. Do not swap
 * in a Coil/network transformer; MarkdownSafetyTest guards this.
 */
val MarkdownImages: ImageTransformer = NoOpImageTransformerImpl()
private const val MAX_INLINE_CHARS = 8_000

/** Long-press copies [text] to the clipboard. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String, onClick: () -> Unit = {}): Modifier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return combinedClickable(
        onClick = onClick,
        onLongClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) } },
        onLongClickLabel = "Copy",
    )
}

/** Dispatches one message row. Agent rows are plain prose on night (no avatar, no bubble). */
@Composable
fun ChatRowItem(row: ChatRow, @Suppress("UNUSED_PARAMETER") agentName: String = "") {
    when (row) {
        is ChatRow.User -> UserBubble(row.msg.text, footer = null)
        is ChatRow.Screen -> ScreenBlock(row.msg)
        is ChatRow.Text -> TurnRow(row) { AssistantText(row.msg) }
        is ChatRow.Thinking -> TurnRow(row) { ThinkingRow(row.msg) }
        is ChatRow.Tool -> TurnRow(row) { ToolRow(row) }
    }
}

/** One item of the chat list. */
@Composable
fun ChatItemView(item: ChatItem) {
    when (item) {
        is ChatItem.Row -> ChatRowItem(item.row)
        is ChatItem.Tools -> TurnRow(item.steps.first()) { ToolChips(item) }
    }
}

/** Rows of one agent turn share the gutter; a turn starts with a little more air. */
@Composable
private fun TurnRow(row: ChatRow, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (row.firstInTurn) 14.dp else 6.dp)) {
        content()
    }
}

@Composable
fun UserBubble(text: String, footer: (@Composable () -> Unit)?, dim: Boolean = false) {
    val c = Whistle.colors
    Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 14.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .clip(WhistleShapes.Bubble)
                .background(c.cardHi.copy(alpha = if (dim) 0.6f else 1f))
                .copyOnLongPress(text)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, style = WhistleType.body, color = c.wool.copy(alpha = if (dim) 0.8f else 1f))
            footer?.invoke()
        }
    }
}

@Composable
private fun AssistantText(msg: WMessage) {
    // Parsed synchronously and remembered per text: no loading flash while scrolling.
    val state = rememberMarkdownState(msg.text, immediate = true)
    val c = Whistle.colors
    val body = WhistleType.body.copy(color = c.wool)
    // Links in agent text are untrusted: a tap opens the link sheet (real target), never the browser.
    Box(Modifier.fillMaxWidth().copyOnLongPress(msg.text)) { MessageLinks(msg.text) {
        Markdown(
            state,
            imageTransformer = MarkdownImages,
            colors = markdownColor(
                text = c.wool,
                codeBackground = c.inset,
                inlineCodeBackground = c.card,
                dividerColor = c.line,
                tableBackground = c.card,
            ),
            typography = markdownTypography(
                h1 = WhistleType.heading,
                h2 = WhistleType.heading.copy(fontSize = 18.sp, lineHeight = 24.sp),
                h3 = WhistleType.name,
                h4 = WhistleType.name,
                h5 = WhistleType.bodyStrong,
                h6 = WhistleType.bodyStrong,
                text = body,
                code = WhistleType.mono.copy(color = c.insetText),
                inlineCode = WhistleType.mono.copy(fontSize = 13.sp),
                quote = body.copy(color = c.sage),
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    } }
    if (msg.truncated) TruncatedNote()
}

@Composable
private fun TruncatedNote() {
    Text("(truncated)", style = WhistleType.small, color = Whistle.colors.sage)
}

/** Thinking: one sage line; tap to read it all. */
@Composable
private fun ThinkingRow(msg: WMessage) {
    var open by rememberSaveable(msg.id) { mutableStateOf(false) }
    val c = Whistle.colors
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).copyOnLongPress(msg.text) { open = !open }) {
        Text(
            if (open) "Thinking" else "Thinking · " + msg.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim(),
            style = WhistleType.secondary,
            fontStyle = FontStyle.Italic,
            color = c.sage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Text(
                msg.text.take(MAX_INLINE_CHARS),
                style = WhistleType.secondary,
                fontStyle = FontStyle.Italic,
                color = c.sage,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
            )
        }
    }
}

/** A turn's tool steps folded into chips ("read skill", "grep schema", "+2"); tap lists the steps. */
@Composable
private fun ToolChips(item: ChatItem.Tools) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val c = Whistle.colors
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180))) {
        val labels = remember(item.steps) { chipLabels(item.steps) }
        val failed = item.steps.any { it.result?.isError == true }
        ChipRow(Modifier.fillMaxWidth().clip(WhistleShapes.Inset).clickable { open = !open }.testTag("toolChips")) {
            labels.forEachIndexed { i, l ->
                val err = !l.startsWith("+") && item.steps.getOrNull(i)?.result?.isError == true
                Chip(l, color = if (err) c.ember else Color.Unspecified)
            }
            if (open) Chip("hide", color = c.sage)
        }
        if (failed && !open) {
            Text("a step failed", style = WhistleType.small, color = c.ember, modifier = Modifier.padding(top = 4.dp))
        }
        if (open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.steps.forEach { ToolRow(it) }
            }
        }
    }
}

@Composable
private fun ToolRow(row: ChatRow.Tool) {
    val call = row.call
    val result = row.result
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    var resultOpen by rememberSaveable(row.key + ":r") { mutableStateOf(false) }
    val isError = result?.isError == true
    val c = Whistle.colors
    Column(
        Modifier.fillMaxWidth().clip(WhistleShapes.Inset).background(c.card).animateContentSize(tween(180)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                call?.toolName ?: "result",
                style = WhistleType.secondaryStrong,
                color = if (isError) c.ember else c.wool,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                call?.let { toolSummary(it) } ?: result?.text?.lineSequence()?.firstOrNull().orEmpty(),
                style = WhistleType.mono,
                color = c.sage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                call != null && result == null -> Text("…", color = c.sky, style = WhistleType.secondary)
                isError -> Text("✗", color = c.ember, style = WhistleType.secondary)
                else -> Text("✓", color = c.moss, style = WhistleType.secondary)
            }
        }
        if (open && call != null) {
            ToolInput(call)
        }
        if (result != null && (open || call == null)) {
            ToolResult(result, resultOpen || call == null) { resultOpen = !resultOpen }
        }
    }
}

@Composable
private fun ToolInput(call: WMessage) {
    // Decoded only here, when the call is expanded.
    val input = remember(call) { call.toolInput }
    val diff = remember(call.toolName, input) { toolDiff(call.toolName, input as? JsonObject) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        if (diff != null) {
            diff.path?.let { Text(it, style = WhistleType.mono, color = Whistle.colors.sage, modifier = Modifier.padding(bottom = 4.dp)) }
            DiffView(diff.lines, Modifier.clip(WhistleShapes.Inset).background(Whistle.colors.inset).heightIn(max = 480.dp).verticalScroll(rememberScrollState()))
        } else {
            val text = remember(input, call.text) { inputText(input, call.text) }
            MonoBlock(text, Modifier.copyOnLongPress(text))
        }
        if (call.truncated) TruncatedNote()
    }
}

private fun inputText(input: JsonElement?, fallback: String): String = when {
    input == null -> fallback
    input is JsonObject && truncatedPrefix(input) != null -> truncatedPrefix(input)!!
    input is JsonPrimitive && input.isString -> input.content
    else -> PrettyJson.encodeToString(JsonElement.serializer(), input)
}

@Composable
private fun ToolResult(result: WMessage, open: Boolean, onToggle: () -> Unit) {
    val c = Whistle.colors
    val color = if (result.isError) c.ember else c.sage
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        val lines = remember(result.text) { result.text.lines().size }
        Text(
            (if (open) "Result ▾" else "Result ▸") + "  $lines line${if (lines == 1) "" else "s"}" + if (result.isError) " · error" else "",
            style = WhistleType.small,
            color = color,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        )
        if (open) {
            MonoBlock(result.text, Modifier.copyOnLongPress(result.text), color = if (result.isError) c.ember else c.insetText)
            if (result.truncated) TruncatedNote()
        }
    }
}

@Composable
private fun MonoBlock(text: String, modifier: Modifier = Modifier, color: Color = Whistle.colors.insetText) {
    val shown = if (text.length > MAX_INLINE_CHARS) text.take(MAX_INLINE_CHARS) + "\n…" else text
    Text(
        shown,
        style = WhistleType.mono,
        color = color,
        softWrap = false,
        modifier = modifier.fillMaxWidth()
            .clip(WhistleShapes.Inset)
            .background(Whistle.colors.inset)
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Final screen capture of an agent without a transcript. */
@Composable
private fun ScreenBlock(msg: WMessage) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MonoBlock(msg.text, Modifier.copyOnLongPress(msg.text))
    }
}

/** Live strip line caps: keyboard open, closed, and tapped open. */
const val LIVE_LINES_IME = 3
const val LIVE_LINES = 8
const val LIVE_LINES_EXPANDED = 12
private const val LIVE_KEEP_EXPANDED = 200

/**
 * The in-progress screen of the current turn as an inset strip (last 8 lines, 3 while the
 * keyboard is up; tap for up to 12, scrollable), and a "working" line.
 */
@Composable
fun LiveBubble(text: String?, working: Boolean, imeOpen: Boolean = false) {
    if (text == null && !working) return
    val c = Whistle.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("liveBubble")) {
        if (text != null) {
            val cap = when {
                expanded -> LIVE_LINES_EXPANDED
                imeOpen -> LIVE_LINES_IME
                else -> LIVE_LINES
            }
            InsetTerminal(
                text,
                Modifier.animateContentSize(tween(180)).testTag(LIVE_STRIP_TAG).clickable(onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded },
                maxLines = cap,
                keepLines = if (expanded) LIVE_KEEP_EXPANDED else cap,
            )
        }
        if (working) StatusLine("working", c.sky, Modifier.padding(top = 8.dp, start = 2.dp))
    }
}

const val LIVE_STRIP_TAG = "liveStrip"

/** A prompt sent from the phone that the transcript does not show yet. */
@Composable
fun PendingBubble(p: Pending, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val c = Whistle.colors
    UserBubble(
        p.text,
        dim = true,
        footer = {
            val (label, isError) = when (val s = p.state) {
                PendingState.Sending -> "sending…" to false
                PendingState.Sent -> "sent · waiting for the agent" to false
                PendingState.NotSeen -> "not seen in transcript" to true
                PendingState.Unconfirmed -> "not confirmed — check the chat before resending" to true
                is PendingState.Failed -> (if (s.code == "agent_blocked") "agent is waiting for approval" else "failed: ${s.message}") to true
            }
            Text(
                label,
                style = WhistleType.small,
                color = if (isError) c.ember else c.sage,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (p.state != PendingState.Sending && p.state != PendingState.Sent) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.widthIn(min = 120.dp)) {
                    TextButton(onClick = onDismiss) { Text("Dismiss", color = c.sage) }
                    TextButton(onClick = onRetry) { Text(if (p.state is PendingState.Failed) "Retry" else "Resend", color = c.wool) }
                }
            }
        },
    )
}

/** Slim "updating…" pill shown while the cached chat may be behind. */
@Composable
fun UpdatingBar() {
    val c = Whistle.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        StatusLine("updating…", c.sky, Modifier.clip(WhistleShapes.Pill).background(c.card).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
