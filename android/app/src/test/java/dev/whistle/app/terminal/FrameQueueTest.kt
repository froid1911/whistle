package dev.whistle.app.terminal

import dev.whistle.app.terminal.FrameQueue.Added
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueTest {
    @Test fun drainsQueuedEntriesAsOneJsArrayBody() {
        val q = FrameQueue()
        assertEquals(Added.Queued, q.add("aGk=", 80, 24, clear = true))
        assertEquals(Added.Queued, q.add("eA==", 80, 24, clear = false))
        assertEquals("['aGk=',80,24,true],['eA==',80,24,false]", q.drain())
        assertNull(q.drain())
        assertTrue(q.isEmpty)
    }

    @Test fun clearFrameDropsEverythingBeforeIt() {
        val q = FrameQueue()
        q.add("AAAA", 80, 24, clear = true)
        q.add("BBBB", 80, 24, clear = false)
        q.add("CCCC", 120, 40, clear = true) // full redraw: earlier frames are redundant
        q.add("DDDD", 120, 40, clear = false)
        assertEquals("['CCCC',120,40,true],['DDDD',120,40,false]", q.drain())
    }

    @Test fun overflowDropsQueueAndReports() {
        val q = FrameQueue(capChars = 100)
        q.add("A".repeat(40), 80, 24, clear = false)
        assertEquals(Added.Overflow, q.add("B".repeat(80), 80, 24, clear = false))
        assertTrue(q.isEmpty)
        assertNull(q.drain())
        assertEquals(Added.Queued, q.add("C".repeat(8), 80, 24, clear = false))
    }

    @Test fun clearFrameResetsTheBudget() {
        val q = FrameQueue(capChars = 100)
        q.add("A".repeat(60), 80, 24, clear = false)
        assertEquals(Added.Queued, q.add("B".repeat(60), 80, 24, clear = true))
    }

    @Test fun rejectsNonBase64Bytes() {
        val q = FrameQueue()
        assertEquals(Added.Invalid, q.add("aGk=');alert(1);//", 80, 24, clear = false))
        assertEquals(Added.Invalid, q.add("a b", 80, 24, clear = false))
        assertTrue(q.isEmpty)
        assertEquals(Added.Queued, q.add("", 80, 24, clear = false)) // empty diff is valid
        assertEquals(Added.Queued, q.add("Zm9v+/8=", 80, 24, clear = false))
    }
}
