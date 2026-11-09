package dev.whistle.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import dev.whistle.app.ui.components.LampCard
import dev.whistle.app.ui.components.HoldPillButton
import dev.whistle.app.security.LocalStepUp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One numbered choice of an agent's approval dialog; [keys] selects it (herdr key names). */
data class ApprovalOption(val number: Int, val label: String, val keys: List<String>, val selected: Boolean)

/** Confirms the dialog's highlighted choice. */
val APPROVE_KEYS = listOf("enter")

/** Cancels the dialog. */
val CANCEL_KEYS = listOf("esc")

private val BOX_CHARS = Regex("[│┃╭╮╰╯─━┌┐└┘]")
private val OPTION = Regex("""^(?:[❯›>▶➤]\s*)?(\d{1,2})[.)]\s+(.+?)$""")
private val SELECTED_MARK = Regex("""^[❯›>▶➤]""")

/**
 * Finds the numbered options of an approval dialog in a cleaned screen capture: the LAST run of
 * lines numbered 1, 2, 3… (so a numbered list in the reply above the dialog is ignored). Box
 * borders and the `❯` cursor are stripped; the cursor marks [ApprovalOption.selected].
 */
fun parseApprovalOptions(screen: String): List<ApprovalOption> {
    data class Hit(val n: Int, val label: String, val selected: Boolean)
    val hits = screen.lines().map { raw ->
        val line = raw.replace(BOX_CHARS, " ").trim()
        OPTION.matchEntire(line)?.let { m ->
            Hit(m.groupValues[1].toInt(), m.groupValues[2].trim(), SELECTED_MARK.containsMatchIn(line))
        }
    }
    val start = hits.indexOfLast { it?.n == 1 }
    if (start < 0) return emptyList()
    val out = mutableListOf<ApprovalOption>()
    for (h in hits.subList(start, hits.size)) {
        if (h == null) continue // a wrapped label or blank line between options
        if (h.n != out.size + 1) break
        out += ApprovalOption(h.n, h.label, listOf(h.n.toString()), h.selected)
    }
    return out
}

/** One answer pill of an approval card. */
data class ApprovalAnswer(val label: String, val keys: List<String>, val primary: Boolean)

/** The chat card's answers: the numbered options (cursor = primary), "Yes (Enter)", "Esc". */
fun chatAnswers(options: List<ApprovalOption>): List<ApprovalAnswer> {
    val primary = options.firstOrNull { it.selected } ?: options.firstOrNull()
    return options.map { ApprovalAnswer("${it.number}. ${it.label}", it.keys, it == primary) } +
        ApprovalAnswer("Yes (Enter)", APPROVE_KEYS, primary == null) +
        ApprovalAnswer("Esc", CANCEL_KEYS, false)
}

/** The herd card's answers: the option labels, or Enter/Esc when the screen has no numbered options. */
fun herdAnswers(options: List<ApprovalOption>): List<ApprovalAnswer> {
    if (options.isEmpty()) return listOf(ApprovalAnswer("Enter", APPROVE_KEYS, true), ApprovalAnswer("Esc", CANCEL_KEYS, false))
    val primary = options.firstOrNull { it.selected } ?: options.first()
    return options.map { ApprovalAnswer(it.label, it.keys, it == primary) }
}

/**
 * The full prompt from `agent.screen` (never from chat text): Commit Mono on the inset color,
 * scrollable, opening at the bottom (the question sits right above the options).
 */
@Composable
fun ApprovalPromptInset(prompt: String, modifier: Modifier = Modifier) {
    val c = Whistle.colors
    Text(
        prompt,
        style = WhistleType.mono,
        color = c.insetText,
        modifier = modifier
            .testTag("approvalPrompt")
            .fillMaxWidth()
            .clip(WhistleShapes.Inset)
            .background(c.inset)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState(Int.MAX_VALUE))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * Answer pills shared by the chat card and the herd's needs-you card: each must be HELD
 * ([HoldPillButton]); a quick tap shows "Hold to answer". Then step-up auth, then [onKeys].
 * A destructive-looking [prompt] gets an ember warning above the pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalAnswers(
    answers: List<ApprovalAnswer>,
    prompt: String,
    enabled: Boolean,
    onKeys: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    labelMaxWidth: Dp = Dp.Unspecified,
    trailing: @Composable () -> Unit = {},
) {
    val c = Whistle.colors
    val stepUp = LocalStepUp.current
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) { delay(2_000); hint = false }
    }
    val warning = remember(prompt) { DestructiveCommands.warning(prompt) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (warning != null) {
            Text(warning, style = WhistleType.secondaryStrong, color = c.ember, modifier = Modifier.testTag("destructiveWarning"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            answers.forEach { a ->
                HoldPillButton(
                    a.label,
                    onConfirm = { stepUp.require("Answer the agent", detail = "Answer: ${a.label}") { onKeys(a.keys) } },
                    style = if (a.primary) PillStyle.Lamp else PillStyle.Outline,
                    enabled = enabled,
                    onTooShort = { hint = true },
                    modifier = Modifier.widthIn(max = labelMaxWidth),
                )
            }
            trailing()
        }
        if (hint) Text("Hold to answer", style = WhistleType.small, color = c.sage, modifier = Modifier.testTag("holdHint"))
    }
}

/** The pane is waiting for a decision: a lamp card in the stream with the full prompt and held answers. */
@Composable
fun ApprovalCard(
    state: ApprovalUi,
    onKeys: (List<String>) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Whistle.colors
    var showScreen by rememberSaveable { mutableStateOf(false) }
    LampCard(modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("approvalCard")) {
        Column(Modifier.animateContentSize(tween(180)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waiting for you", style = WhistleType.name, color = c.lamp)
            val prompt = remember(state.text) { approvalPrompt(state.text) }
            when {
                state.loading -> Text("Reading the screen…", style = WhistleType.secondary, color = c.sage)
                prompt.isNotBlank() && !showScreen -> ApprovalPromptInset(prompt)
            }
            if (showScreen && state.text.isNotEmpty()) {
                // Bottom of the screen matters most: show the last lines, scrollable.
                val tail = state.text.trimEnd().lines().takeLast(24).joinToString("\n")
                Text(
                    tail,
                    style = WhistleType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = c.insetText,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth().clip(WhistleShapes.Inset).background(c.inset).heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
            state.error?.let { Text(it, color = c.ember, style = WhistleType.secondary) }
            ApprovalAnswers(
                chatAnswers(state.options),
                prompt = prompt,
                enabled = !state.sending && !state.loading,
                onKeys = onKeys,
            )
            Row(Modifier.offset(x = (-12).dp)) {
                TextButton(onClick = { showScreen = !showScreen }) { Text(if (showScreen) "Hide screen" else "Show screen", color = c.sage) }
                TextButton(onClick = onRefresh, enabled = !state.sending && !state.loading) { Text("Refresh", color = c.sage) }
                TextButton(onClick = onDismiss) { Text("Hide", color = c.sage) }
            }
        }
    }
}
