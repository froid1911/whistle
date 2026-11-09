package dev.whistle.app.widget

import android.content.SharedPreferences
import dev.whistle.app.protocol.AgentStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Agent-pane counts across all machines (shell panes are never passed in). UNKNOWN is folded into
 * [idle]: the agent exists but reports nothing actionable. [asOf] is epoch millis of the last sync
 * the numbers reflect (0 = never).
 */
data class Counts(val working: Int, val blocked: Int, val done: Int, val idle: Int, val asOf: Long) {
    companion object {
        fun from(statuses: List<AgentStatus>, asOf: Long) = Counts(
            working = statuses.count { it == AgentStatus.WORKING },
            blocked = statuses.count { it == AgentStatus.BLOCKED },
            done = statuses.count { it == AgentStatus.DONE },
            idle = statuses.count { it == AgentStatus.IDLE || it == AgentStatus.UNKNOWN },
            asOf = asOf,
        )
    }
}

data class Pane(val machineId: String, val paneId: String, val status: AgentStatus)

data class BlockedTarget(val machineId: String, val paneId: String)

/**
 * What the widget and tile render. [synced] = every machine was Synced when this was computed;
 * [noMachines] = nothing is paired yet (first run, or the last machine was removed).
 */
data class Snapshot(val counts: Counts, val synced: Boolean, val onlyBlocked: BlockedTarget?, val noMachines: Boolean = false) {
    companion object {
        val EMPTY = Snapshot(Counts(0, 0, 0, 0, 0), false, null)
    }
}

/** asOf advances to [now] only while every machine is synced; otherwise the previous time is kept. */
fun reduceSnapshot(prev: Snapshot?, panes: List<Pane>, allSynced: Boolean, now: Long, noMachines: Boolean = false): Snapshot {
    if (noMachines) return Snapshot(Counts(0, 0, 0, 0, 0), false, null, noMachines = true)
    val asOf = if (allSynced) now else prev?.counts?.asOf ?: 0L
    val blocked = panes.filter { it.status == AgentStatus.BLOCKED }
    return Snapshot(
        Counts.from(panes.map { it.status }, asOf),
        allSynced,
        blocked.singleOrNull()?.let { BlockedTarget(it.machineId, it.paneId) },
    )
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** Null when fresh; otherwise how old the numbers are ("as of 14:02"). */
fun staleLabel(c: Counts, synced: Boolean, zone: ZoneId = ZoneId.systemDefault(), noMachines: Boolean = false): String? = when {
    noMachines -> "pair a machine"
    synced -> null
    c.asOf <= 0 -> "not synced yet"
    else -> "as of " + HHMM.format(Instant.ofEpochMilli(c.asOf).atZone(zone))
}

/** Widget footer: always shows when the numbers are from; [stale] de-emphasises it. */
data class WidgetLabel(val text: String, val stale: Boolean)

fun widgetLabel(s: Snapshot, zone: ZoneId = ZoneId.systemDefault()): WidgetLabel =
    if (s.noMachines) WidgetLabel("no machines yet · pair one", true)
    else if (s.counts.asOf <= 0) WidgetLabel("not synced yet", true)
    else WidgetLabel("as of " + HHMM.format(Instant.ofEpochMilli(s.counts.asOf).atZone(zone)), !s.synced)

const val FRESH_MS = 3 * 60_000L
const val OFFLINE_AFTER_MS = 60 * 60_000L

/** Synced only counts as current while recent, and never when it was persisted by an earlier process. */
fun Snapshot.aged(now: Long, publishedThisProcess: Boolean): Snapshot =
    if (synced && (!publishedThisProcess || now - counts.asOf >= FRESH_MS)) copy(synced = false) else this

/** Quick Settings tile label; a stale one carries its age, an old one says offline. */
fun tileLabel(s: Snapshot, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val c = s.counts
    if (s.noMachines) return "Herd: no machines"
    if (!s.synced && (c.asOf <= 0 || now - c.asOf > OFFLINE_AFTER_MS)) return "Herd: offline"
    val parts = buildList {
        if (c.working > 0) add("${c.working} working")
        if (c.blocked > 0) add("${c.blocked} blocked")
    }
    val base = if (parts.isEmpty()) "Herd: idle" else "Herd: " + parts.joinToString(" · ")
    return if (s.synced) base else base + " · " + HHMM.format(Instant.ofEpochMilli(c.asOf).atZone(zone))
}

/** The last published [Snapshot], shared by widget, tile and worker (separate processes' worth of entry points). */
class SnapshotStore(private val prefs: SharedPreferences) {
    @Volatile private var wrote = false

    /** Freshness is decided here, at read time (see [aged]). */
    fun read(now: Long = System.currentTimeMillis()): Snapshot = readRaw().aged(now, wrote)

    private fun readRaw(): Snapshot = Snapshot(
        Counts(prefs.getInt("w", 0), prefs.getInt("b", 0), prefs.getInt("d", 0), prefs.getInt("i", 0), prefs.getLong("asOf", 0)),
        prefs.getBoolean("synced", false),
        prefs.getString("bm", null)?.let { m -> prefs.getString("bp", null)?.let { BlockedTarget(m, it) } },
        prefs.getBoolean("none", false),
    )

    fun write(s: Snapshot) {
        wrote = true
        prefs.edit()
            .putInt("w", s.counts.working).putInt("b", s.counts.blocked).putInt("d", s.counts.done)
            .putInt("i", s.counts.idle).putLong("asOf", s.counts.asOf).putBoolean("synced", s.synced)
            .putString("bm", s.onlyBlocked?.machineId).putString("bp", s.onlyBlocked?.paneId)
            .putBoolean("none", s.noMachines)
            .apply()
    }
}
