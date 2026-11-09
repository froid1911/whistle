package dev.whistle.app.widget

import dev.whistle.app.protocol.AgentStatus.*
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class CountsTest {
    private val utc = ZoneId.of("UTC")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 8, h, m, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun countsByStatus() {
        val c = Counts.from(listOf(WORKING, WORKING, BLOCKED, DONE, IDLE, UNKNOWN), asOf = 5)
        assertEquals(Counts(working = 2, blocked = 1, done = 1, idle = 2, asOf = 5), c) // unknown folds into idle
    }

    @Test fun staleLabelShowsAge() {
        val c = Counts.from(listOf(WORKING), asOf = at(14, 2))
        assertEquals("as of 14:02", staleLabel(c, synced = false, utc))
        assertNull(staleLabel(c, synced = true, utc))
        assertEquals("not synced yet", staleLabel(Counts.from(emptyList(), 0), synced = false, utc))
    }

    @Test fun widgetLabelAlwaysShowsTime() {
        val t = at(14, 2)
        assertEquals(WidgetLabel("as of 14:02", false), widgetLabel(Snapshot(Counts(1, 0, 0, 0, t), true, null), utc))
        assertEquals(WidgetLabel("as of 14:02", true), widgetLabel(Snapshot(Counts(1, 0, 0, 0, t), false, null), utc))
        assertEquals(WidgetLabel("not synced yet", true), widgetLabel(Snapshot.EMPTY, utc))
    }

    @Test fun tileLabels() {
        val now = at(14, 5)
        fun s(c: Counts, synced: Boolean = true) = Snapshot(c, synced, null)
        fun l(x: Snapshot) = tileLabel(x, now, utc)
        assertEquals("Herd: 2 working · 1 blocked", l(s(Counts(2, 1, 3, 0, 9))))
        assertEquals("Herd: 1 blocked", l(s(Counts(0, 1, 0, 0, 9))))
        assertEquals("Herd: idle", l(s(Counts(0, 0, 2, 1, 9))))
        assertEquals("Herd: offline", l(s(Counts(0, 0, 0, 0, 0), synced = false)))
        assertEquals("Herd: 2 working · 14:02", l(s(Counts(2, 0, 0, 0, at(14, 2)), synced = false)))
        assertEquals("Herd: idle · 14:02", l(s(Counts(0, 0, 1, 0, at(14, 2)), synced = false)))
        assertEquals("Herd: offline", l(s(Counts(2, 0, 0, 0, at(12, 0)), synced = false))) // older than 1 h
    }

    @Test fun syncedAgesAtReadTime() {
        val snap = Snapshot(Counts(1, 0, 0, 0, at(14, 0)), true, null)
        assertEquals(true, snap.aged(at(14, 2), publishedThisProcess = true).synced)
        assertEquals(false, snap.aged(at(14, 3), publishedThisProcess = true).synced)
        assertEquals(false, snap.aged(at(14, 1), publishedThisProcess = false).synced) // persisted by an earlier process
    }

    @Test fun reducerAsOfOnlyAdvancesWhenSynced() {
        val p = listOf(WORKING)
        val s1 = reduceSnapshot(null, listOf(Pane("m", "p1", WORKING)), allSynced = true, now = 100)
        assertEquals(100, s1.counts.asOf)
        val s2 = reduceSnapshot(s1, listOf(Pane("m", "p1", BLOCKED)), allSynced = false, now = 200)
        assertEquals(100, s2.counts.asOf)
        assertEquals(false, s2.synced)
        assertEquals(BlockedTarget("m", "p1"), s2.onlyBlocked)
        val s3 = reduceSnapshot(s2, listOf(Pane("m", "p1", BLOCKED), Pane("m", "p2", BLOCKED)), true, 300)
        assertNull(s3.onlyBlocked)
        assertEquals(300, s3.counts.asOf)
        assertEquals(0, reduceSnapshot(null, p.map { Pane("m", "x", it) }, false, 50).counts.asOf)
    }

    @Test fun noMachinesSaysPairOne() {
        val prev = reduceSnapshot(null, listOf(Pane("m", "p", BLOCKED)), true, at(9, 0))
        val s = reduceSnapshot(prev, emptyList(), false, at(9, 5), noMachines = true)
        assertEquals(Snapshot(Counts(0, 0, 0, 0, 0), false, null, noMachines = true), s)
        assertEquals("Herd: no machines", tileLabel(s, at(9, 5), utc))
        assertEquals(WidgetLabel("no machines yet · pair one", true), widgetLabel(s, utc))
        assertEquals("pair a machine", staleLabel(s.counts, s.synced, utc, noMachines = true))
    }
}
