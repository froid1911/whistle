package dev.whistle.app.ui.herd

import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.AgentStatus.BLOCKED
import dev.whistle.app.protocol.AgentStatus.DONE
import dev.whistle.app.protocol.AgentStatus.IDLE
import dev.whistle.app.protocol.AgentStatus.UNKNOWN
import dev.whistle.app.protocol.AgentStatus.WORKING
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.ui.chat.approvalQuestion
import dev.whistle.app.ui.components.tailLines
import dev.whistle.app.ui.theme.AgentGlyph
import dev.whistle.app.ui.theme.ambientMotionAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdModelTest {
    private fun ws(id: String, n: Int, label: String = "") = WorkspaceUi(id, n, label, UNKNOWN, null)
    private fun agent(id: String, ws: String, status: AgentStatus, kind: String = "claude") =
        PaneUi(id, "t-$ws", ws, "", "/src/$id", PaneKind.AGENT, kind, status, null, true, 0, 0, 1, 1)
    private fun shell(id: String, ws: String) =
        PaneUi(id, "t-$ws", ws, "", "/src/$id", PaneKind.SHELL, null, UNKNOWN, null, false, 0, 0, 1, 1)

    @Test
    fun glyphPerAgentKind() {
        assertEquals(AgentGlyph.Spokes, AgentGlyph.of("claude"))
        assertEquals(AgentGlyph.Letter("π"), AgentGlyph.of("omp"))
        assertEquals(AgentGlyph.Diamond, AgentGlyph.of("codex"))
        assertEquals(AgentGlyph.Letter("G"), AgentGlyph.of("gemini"))
        assertEquals(AgentGlyph.Letter("?"), AgentGlyph.of(null))
        assertEquals("✳", AgentGlyph.of("Claude").symbol)
    }

    @Test
    fun needsYouPinnedThenWorkingDoneIdle() {
        val herd = buildHerd(
            "myserver",
            listOf(ws("w1", 1, "api"), ws("w2", 2, "home")),
            listOf(
                agent("a", "w1", IDLE), agent("b", "w1", DONE), shell("s", "w1"), agent("c", "w1", WORKING),
                agent("d", "w1", UNKNOWN), agent("e", "w2", BLOCKED, "omp"), agent("f", "w2", WORKING),
            ),
        )
        assertEquals(listOf("e"), herd.needsYou.map { it.pane.id })
        assertEquals("home · waiting for you", herd.needsYou.single().subline)
        assertEquals(listOf("w1", "w2"), herd.groups.map { it.workspace.id })
        assertEquals(listOf("c", "b", "a", "d"), herd.groups[0].agents.map { it.pane.id })
        assertEquals(listOf("s"), herd.groups[0].shells.map { it.id })
        assertEquals(listOf("f"), herd.groups[1].agents.map { it.pane.id }) // blocked one is pinned, not repeated
        assertEquals("api · working", herd.groups[0].agents[0].subline)
        assertEquals("6 agents on myserver · 1 needs you", herd.subline)
    }

    @Test
    fun sublineTexts() {
        assertEquals("3 agents on myserver · 1 needs you", herdSubline("myserver", listOf(BLOCKED, WORKING, IDLE)))
        assertEquals("2 agents on myserver · 2 need you", herdSubline("myserver", listOf(BLOCKED, BLOCKED)))
        assertEquals("2 agents on myserver · 1 working", herdSubline("myserver", listOf(WORKING, DONE)))
        assertEquals("1 agent on myserver · all quiet", herdSubline("myserver", listOf(IDLE)))
        assertEquals("No agents on myserver", herdSubline("myserver", emptyList()))
    }

    @Test
    fun emptyWorkspaceStillListed() {
        val herd = buildHerd("myserver", listOf(ws("w1", 1)), emptyList())
        assertEquals(1, herd.groups.size)
        assertFalse(herd.isEmpty)
        assertEquals("No agents on myserver", herd.subline)
    }

    @Test
    fun reducedMotionWhenAnimatorScaleIsZero() {
        assertTrue(ambientMotionAllowed(1f))
        assertTrue(ambientMotionAllowed(0.5f))
        assertFalse(ambientMotionAllowed(0f))
    }

    @Test
    fun questionAboveTheOptions() {
        val screen = """
            ⏺ I'll check the key.
            ╭──────────────────────────────╮
            │ Bash command                 │
            │   ssh-keygen -lf ~/.ssh/ak   │
            │ Do you want to proceed?      │
            │ ❯ 1. Yes                     │
            │   2. No                      │
            ╰──────────────────────────────╯
            Esc to cancel · Tab to amend
        """.trimIndent()
        assertEquals("Bash command\nssh-keygen -lf ~/.ssh/ak\nDo you want to proceed?", approvalQuestion(screen))
        assertEquals("Do you want to proceed?", approvalQuestion(screen, maxLines = 1))
        assertEquals("foo\nwaiting", approvalQuestion("foo\n\nwaiting\n"))
    }

    @Test
    fun insetStripShowsLastLines() {
        val text = (1..12).joinToString("\n") { "line $it" } + "\n\n"
        assertEquals((5..12).joinToString("\n") { "line $it" }, tailLines(text, 8))
        assertEquals("a", tailLines("a", 8))
    }
}
