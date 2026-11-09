package dev.whistle.app.ui.chat

/**
 * What the chat list shows: message rows, except that the tool steps of one turn that follow each
 * other fold into one [Tools] item (a row of chips). Pure display grouping on top of [groupMessages].
 */
sealed interface ChatItem {
    val key: String

    data class Row(val row: ChatRow) : ChatItem {
        override val key: String get() = row.key
    }

    /** Consecutive tool steps of one turn, oldest first. Keyed by the first step: appends keep the item. */
    data class Tools(val steps: List<ChatRow.Tool>) : ChatItem {
        override val key: String get() = "tools:" + steps.first().key
    }
}

fun foldTools(rows: List<ChatRow>): List<ChatItem> {
    val out = ArrayList<ChatItem>(rows.size)
    var run = ArrayList<ChatRow.Tool>()
    fun flush() {
        if (run.isNotEmpty()) out += ChatItem.Tools(run)
        run = ArrayList()
    }
    for (r in rows) {
        if (r is ChatRow.Tool && (run.isEmpty() || run.last().turnKey == r.turnKey)) {
            run += r
            continue
        }
        flush()
        if (r is ChatRow.Tool) run += r else out += ChatItem.Row(r)
    }
    flush()
    return out
}

private const val CHIP_ARG_MAX = 18

/** Short chip text for a tool step: "read build.gradle", "grep schema", "bash ./gradlew…". */
fun chipLabel(step: ChatRow.Tool): String {
    val call = step.call ?: return "result"
    val name = call.toolName?.lowercase()?.substringAfterLast("__") ?: "tool"
    val summary = toolSummary(call)
    val arg = when {
        summary.isEmpty() -> ""
        ' ' !in summary && '/' in summary -> summary.trimEnd('/').substringAfterLast('/')
        else -> summary
    }
    val short = if (arg.length > CHIP_ARG_MAX) arg.take(CHIP_ARG_MAX - 1).trimEnd() + "…" else arg
    return "$name $short".trim()
}

/** At most [max] chips are shown; the rest are summed up as "+n". */
fun chipLabels(steps: List<ChatRow.Tool>, max: Int = 3): List<String> =
    if (steps.size <= max) steps.map(::chipLabel)
    else steps.take(max).map(::chipLabel) + "+${steps.size - max}"
