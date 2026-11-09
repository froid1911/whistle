package dev.whistle.app.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class DiffKind { Context, Added, Removed, Hunk, Header }

data class DiffLine(val kind: DiffKind, val text: String)

/** A diff to show: [path] of the file (if known) and its lines. */
data class DiffModel(val path: String?, val lines: List<DiffLine>)

private val OLD_NEW_KEYS = listOf("old_string" to "new_string", "oldText" to "newText", "old_str" to "new_str", "oldString" to "newString")
private val PATH_KEYS = listOf("file_path", "path", "filePath", "file")

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The diff an edit-style tool call makes, or null if its input is not one: any old/new string
 * pair (Claude `Edit`, omp `edit`), an `edits` array of such pairs (`MultiEdit`), or `Write`'s
 * `content` (all added).
 */
fun toolDiff(toolName: String?, input: JsonObject?): DiffModel? {
    input ?: return null
    val path = PATH_KEYS.firstNotNullOfOrNull { input.str(it) }
    fun pair(o: JsonObject): Pair<String, String>? =
        OLD_NEW_KEYS.firstNotNullOfOrNull { (a, b) ->
            val old = o.str(a)
            val new = o.str(b)
            if (old != null && new != null) old to new else null
        }
    pair(input)?.let { (old, new) -> return DiffModel(path, lineDiff(old, new)) }
    val edits = (input["edits"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::pair) }
    if (!edits.isNullOrEmpty()) {
        val lines = mutableListOf<DiffLine>()
        edits.forEachIndexed { i, (old, new) ->
            if (i > 0) lines += DiffLine(DiffKind.Hunk, "⋯")
            lines += lineDiff(old, new)
        }
        return DiffModel(path, lines)
    }
    if (toolName.equals("write", ignoreCase = true)) {
        input.str("content")?.let { c -> return DiffModel(path, c.lines().map { DiffLine(DiffKind.Added, it) }) }
    }
    return null
}

/** Line diff of two texts (LCS); falls back to all-removed + all-added for very large inputs. */
fun lineDiff(old: String, new: String): List<DiffLine> {
    val a = old.lines()
    val b = new.lines()
    // Common prefix/suffix first: edits are usually small changes in a larger snippet.
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val am = a.subList(pre, a.size - suf)
    val bm = b.subList(pre, b.size - suf)
    val out = ArrayList<DiffLine>(a.size + b.size)
    a.subList(0, pre).forEach { out += DiffLine(DiffKind.Context, it) }
    if (am.size.toLong() * bm.size > MAX_LCS_CELLS) {
        am.forEach { out += DiffLine(DiffKind.Removed, it) }
        bm.forEach { out += DiffLine(DiffKind.Added, it) }
    } else {
        val n = am.size
        val m = bm.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && am[i] == bm[j] -> { out += DiffLine(DiffKind.Context, am[i]); i++; j++ }
                j < m && (i == n || lcs[i][j + 1] > lcs[i + 1][j]) -> { out += DiffLine(DiffKind.Added, bm[j]); j++ }
                else -> { out += DiffLine(DiffKind.Removed, am[i]); i++ }
            }
        }
    }
    a.subList(a.size - suf, a.size).forEach { out += DiffLine(DiffKind.Context, it) }
    return out
}

private const val MAX_LCS_CELLS = 250_000L

/** Unified diff text (e.g. `git diff`) → lines; the +/-/space prefix is dropped from the text. */
fun parseUnifiedDiff(text: String): List<DiffLine> {
    var inHunk = false
    return text.lines().map { l ->
        when {
            l.startsWith("@@") -> { inHunk = true; DiffLine(DiffKind.Hunk, l) }
            l.startsWith("diff ") || l.startsWith("index ") -> { inHunk = false; DiffLine(DiffKind.Header, l) }
            !inHunk -> DiffLine(DiffKind.Header, l) // ---/+++ and other file headers
            l.startsWith("+") -> DiffLine(DiffKind.Added, l.substring(1))
            l.startsWith("-") -> DiffLine(DiffKind.Removed, l.substring(1))
            l.startsWith(" ") -> DiffLine(DiffKind.Context, l.substring(1))
            else -> DiffLine(DiffKind.Context, l) // "\ No newline at end of file"
        }
    }.let { if (it.lastOrNull()?.let { d -> d.text.isEmpty() && d.kind == DiffKind.Context } == true) it.dropLast(1) else it }
}


/**
 * Red/green line diff, monospace, horizontally scrollable. Reusable for tool edits ([toolDiff])
 * and git diffs ([parseUnifiedDiff]). [maxLines] caps what is rendered (the rest is summarized).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiffView(
    lines: List<DiffLine>,
    modifier: Modifier = Modifier,
    maxLines: Int = 400,
    /** Long-press on a row, with its index in [lines]. */
    onLongClickLine: ((Int) -> Unit)? = null,
) {
    val shown = if (lines.size > maxLines) lines.subList(0, maxLines) else lines
    Column(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        // Widest line sets the width, so every row's background spans it.
        Column(Modifier.width(IntrinsicSize.Max)) {
            shown.forEachIndexed { index, l ->
                DiffRow(l, if (onLongClickLine != null) ({ onLongClickLine(index) }) else null, Modifier.fillMaxWidth())
            }
        }
        if (lines.size > maxLines) {
            Text(
                "… ${lines.size - maxLines} more lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiffRow(l: DiffLine, onLongClick: (() -> Unit)?, modifier: Modifier) {
    // Red/green kept, tuned to the palette (spec: add #2E4A36/#9FD8A8, del #4A2A2A/#F0A8A0).
    val c = Whistle.colors
    val (prefix, bg, fg) = when (l.kind) {
        DiffKind.Added -> Triple("+", c.diffAddBg, c.diffAddFg)
        DiffKind.Removed -> Triple("-", c.diffDelBg, c.diffDelFg)
        DiffKind.Hunk -> Triple("", Color.Transparent, c.sky)
        DiffKind.Header -> Triple("", Color.Transparent, c.sage)
        DiffKind.Context -> Triple(" ", Color.Transparent, c.sage)
    }
    Text(
        prefix + l.text,
        style = WhistleType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = fg,
        softWrap = false,
        modifier = modifier.background(bg)
            .then(if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier)
            .padding(horizontal = 4.dp),
    )
}

/** Lazily composed diff for large git diffs (only visible rows compose); horizontally scrollable. */
@Composable
fun DiffListView(lines: List<DiffLine>, modifier: Modifier = Modifier, onLongClickLine: ((Int) -> Unit)? = null) {
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        items(lines.size) { i ->
            DiffRow(lines[i], if (onLongClickLine != null) ({ onLongClickLine(i) }) else null, Modifier.widthIn(min = 360.dp))
        }
    }
}
