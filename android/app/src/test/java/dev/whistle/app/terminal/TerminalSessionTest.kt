package dev.whistle.app.terminal

import dev.whistle.app.protocol.ClientMsg
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.ServerMsg
import dev.whistle.app.protocol.TermClose
import dev.whistle.app.protocol.TermClosed
import dev.whistle.app.protocol.TermFrame
import dev.whistle.app.protocol.TermInput
import dev.whistle.app.protocol.TermOpen
import dev.whistle.app.protocol.TermResize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLink : TermLink {
    override val events = MutableSharedFlow<ServerMsg>(extraBufferCapacity = 64)
    override val connection = MutableStateFlow(ConnState.Offline)
    val sent = mutableListOf<ClientMsg>()
    var online = true
    override fun send(msg: ClientMsg): Boolean {
        if (!online) return false
        sent.add(msg)
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private val link = FakeLink()
    private val frames = mutableListOf<Pair<TermFrame, Boolean>>()
    private var n = 0

    private fun TestScope.session(): TerminalSession =
        TerminalSession(link, "w1:p1", backgroundScope, newSid = { "s${++n}" }) { f, fresh -> frames.add(f to fresh) }
            .also { it.start(); runCurrent() }

    private fun frame(sid: String, seq: Long, w: Int = 120, h: Int = 40) = TermFrame(sid, seq, w, h, seq == 1L, "aGk=")

    private fun TestScope.synced() {
        link.connection.value = ConnState.Synced
        runCurrent()
    }

    private fun TestScope.emit(m: ServerMsg) {
        link.events.tryEmit(m)
        runCurrent()
    }

    @Test fun opensObserveWithoutSizeOnceSynced() = runTest {
        val s = session()
        assertEquals(emptyList<ClientMsg>(), link.sent)
        assertEquals(TerminalSession.Phase.Waiting, s.state.value.phase)
        synced()
        assertEquals(listOf(TermOpen("s1", "w1:p1", "observe")), link.sent)
        assertEquals(TerminalSession.Phase.Opening, s.state.value.phase)
    }

    @Test fun framesOfCurrentSidReachSinkFirstOneFresh() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        emit(frame("zz", 5))
        emit(frame("s1", 2, w = 80, h = 24))
        assertEquals(listOf(1L to true, 2L to false), frames.map { it.first.seq to it.second })
        assertEquals(TerminalSession.Phase.Live, s.state.value.phase)
        assertEquals(80 to 24, s.state.value.size)
    }

    @Test fun inputGoesToCurrentSid() = runTest {
        val s = session()
        assertFalse(s.input("x")) // nothing open yet
        synced()
        emit(frame("s1", 1))
        assertTrue(s.input("ls\r"))
        assertEquals(TermInput("s1", text = "ls\r"), link.sent.last())
    }

    @Test fun reconnectReopensWithNewSidAndShowsReconnecting() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        link.online = false
        link.connection.value = ConnState.Offline
        runCurrent()
        assertEquals(TerminalSession.Phase.Reconnecting, s.state.value.phase)
        link.online = true
        link.connection.value = ConnState.Connecting
        runCurrent()
        emit(frame("s1", 2)) // stale session: ignored
        assertEquals(1, frames.size)
        synced()
        assertEquals(TermOpen("s2", "w1:p1", "observe"), link.sent.last())
        emit(frame("s2", 1))
        assertEquals(TerminalSession.Phase.Live, s.state.value.phase)
        assertTrue(frames.last().second)
    }

    @Test fun herdrOfflineClosesAndReopensOnReturn() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        link.connection.value = ConnState.HerdrOffline
        runCurrent()
        assertEquals(TermClose("s1"), link.sent.last()) // socket still up: free the slot
        assertEquals(TerminalSession.Phase.Reconnecting, s.state.value.phase)
        synced()
        assertEquals(TermOpen("s2", "w1:p1", "observe"), link.sent.last())
    }

    @Test fun closedShowsReasonAndOnlyReopenOpensAgain() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        emit(TermClosed("s1", "ended"))
        assertEquals(TerminalSession.Phase.Closed, s.state.value.phase)
        assertEquals("ended", s.state.value.reason)
        link.connection.value = ConnState.Offline
        runCurrent()
        synced()
        assertEquals(1, link.sent.count { it is TermOpen }) // no automatic reopen
        assertFalse(s.input("x"))
        s.reopen()
        runCurrent()
        assertEquals(TermOpen("s2", "w1:p1", "observe"), link.sent.last())
        assertNull(s.state.value.reason)
    }

    @Test fun tooManySessionsRetriesThenGivesUp() = runTest {
        val s = session()
        synced()
        repeat(TerminalSession.BUSY_RETRIES) { i ->
            emit(TermClosed("s${i + 1}", "too_many_sessions"))
            assertEquals(TerminalSession.Phase.Opening, s.state.value.phase)
            advanceTimeBy(TerminalSession.BUSY_RETRY_MS + 1)
            runCurrent()
            assertEquals(TermOpen("s${i + 2}", "w1:p1", "observe"), link.sent.last())
        }
        emit(TermClosed("s${TerminalSession.BUSY_RETRIES + 1}", "too_many_sessions"))
        assertEquals(TerminalSession.Phase.Closed, s.state.value.phase)
        assertEquals("too_many_sessions", s.state.value.reason)
    }

    @Test fun fitReopensObserveWithSizeAndUnfitRestores() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        s.fitTo(60, 20)
        runCurrent()
        assertEquals(listOf(TermClose("s1"), TermOpen("s2", "w1:p1", "observe", 60, 20)), link.sent.takeLast(2))
        assertEquals(60 to 20, s.state.value.fit)
        s.unfit()
        runCurrent()
        assertEquals(listOf(TermClose("s2"), TermOpen("s3", "w1:p1", "observe")), link.sent.takeLast(2))
        assertNull(s.state.value.fit)
        // The app never resizes the real pane: no control+size, no term.resize.
        assertTrue(link.sent.none { it is TermResize || (it is TermOpen && it.mode == "control") })
    }

    @Test fun fitSurvivesReconnect() = runTest {
        val s = session()
        synced()
        s.fitTo(60, 20)
        runCurrent()
        link.connection.value = ConnState.Offline
        runCurrent()
        synced()
        assertEquals(TermOpen("s3", "w1:p1", "observe", 60, 20), link.sent.last())
    }

    @Test fun typingOnFitSessionDropsFit() = runTest {
        val s = session()
        synced()
        s.fitTo(60, 20)
        runCurrent()
        emit(frame("s2", 1, 60, 20))
        s.input("x")
        // The bridge restarts the session in control mode at the desktop size.
        assertNull(s.state.value.fit)
        assertEquals(TermInput("s2", text = "x"), link.sent.last())
    }

    @Test fun stopClosesAndIgnoresLaterEvents() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        s.stop()
        runCurrent()
        assertEquals(TermClose("s1"), link.sent.last())
        val count = link.sent.size
        link.connection.value = ConnState.Offline
        runCurrent()
        synced()
        emit(frame("s1", 2))
        assertEquals(count, link.sent.size)
        assertEquals(1, frames.size)
    }

    @Test fun failShowsClosedAndReopenStartsAgain() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        s.fail("renderer_gone")
        assertEquals(TermClose("s1"), link.sent.last())
        assertEquals(TerminalSession.Phase.Closed, s.state.value.phase)
        assertEquals("renderer_gone", s.state.value.reason)
        emit(frame("s1", 2))
        assertEquals(1, frames.size)
        s.reopen()
        runCurrent()
        assertEquals(TermOpen("s2", "w1:p1", "observe"), link.sent.last())
    }

    @Test fun resyncReopensWithNewSidWithoutClosedPhase() = runTest {
        val s = session()
        synced()
        emit(frame("s1", 1))
        s.resync()
        runCurrent()
        assertEquals(listOf(TermClose("s1"), TermOpen("s2", "w1:p1", "observe")), link.sent.takeLast(2))
        emit(frame("s2", 1))
        assertTrue(frames.last().second) // fresh: the page clears and redraws
    }
}
