package dev.whistle.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.data.FakeClient
import dev.whistle.app.data.HerdrDb
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.TabUi
import dev.whistle.app.data.toEntity
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.CursorStore
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val M = "m1"

/** Keyboard-open chrome collapse and the tab strip's visibility / labels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceChromeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: HerdrDb
    private lateinit var scope: CoroutineScope
    private lateinit var repo: HerdrRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HerdrDb::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = HerdrRepository(db, { _, _: CursorStore -> FakeClient() }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun cache(tabs: Int) = runBlocking {
        db.sync().upsertWorkspaces(listOf(WWorkspace("w1", 1, "ws", activeTabId = "t1").toEntity(M)))
        db.sync().upsertTabs((1..tabs).map { WTab("t$it", "w1", it, "PARK: Reverse engineer the app").toEntity(M) })
        db.sync().upsertPanes(
            listOf(WPane("p1", "t1", "w1", label = "PARK: Reverse engineer the app", cwd = "/x", kind = PaneKind.AGENT, agent = "omp", agentStatus = "working").toEntity(M)),
        )
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(3_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun imeCollapsesChromeIntoOneCompactBar() {
        cache(tabs = 2)
        var ime by mutableStateOf(false)
        compose.setContent { NoLockGate { CompositionLocalProvider(LocalImeVisibleOverride provides ime) { WorkspaceScreen(repo, M, "w1", onBack = {}) } } }
        waitForTag(PANE_HEADER_TAG)
        waitForTag(TAB_STRIP_TAG)
        assertEquals(0, count(COMPACT_BAR_TAG))

        ime = true
        compose.waitForIdle()
        compose.onNodeWithTag(COMPACT_BAR_TAG).assertIsDisplayed().assertHeightIsEqualTo(48.dp)
        assertEquals(0, count(PANE_HEADER_TAG))
        assertEquals(0, count(TAB_STRIP_TAG))
        assertEquals(0, compose.onAllNodesWithText("ws").fetchSemanticsNodes().size) // full title row gone

        ime = false
        compose.waitForIdle()
        assertEquals(0, count(COMPACT_BAR_TAG))
        compose.onNodeWithTag(PANE_HEADER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TAB_STRIP_TAG).assertIsDisplayed()
    }

    @Test
    fun singleTabHidesTheTabStrip() {
        cache(tabs = 1)
        compose.setContent { NoLockGate { WorkspaceScreen(repo, M, "w1", onBack = {}) } }
        waitForTag(PANE_HEADER_TAG)
        assertEquals(0, count(TAB_STRIP_TAG))
        runBlocking { db.sync().upsertTabs(listOf(WTab("t2", "w1", 2, "tests").toEntity(M))) }
        waitForTag(TAB_STRIP_TAG)
        // The selected tab's label repeats the pane header, so its pill shows just the number.
        compose.waitUntil(3_000) { compose.onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("2 · tests").fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    @Test
    fun tabPillLabels() {
        fun tab(n: Int, label: String) = TabUi("t$n", "w1", n, label, AgentStatus.UNKNOWN)
        assertEquals("3", tabPillLabel(tab(3, ""), null))
        assertEquals("1 · tests", tabPillLabel(tab(1, "tests"), null))
        assertEquals("1 · π PARK", tabPillLabel(tab(1, "1 · π PARK"), null))
        assertEquals("1", tabPillLabel(tab(1, "1 · π 🐳 PARK: Reverse engineer the app"), "π 🐳 PARK: Reverse engineer the app"))
        assertEquals("2 · api", tabPillLabel(tab(2, "api"), "omp"))
    }
}
