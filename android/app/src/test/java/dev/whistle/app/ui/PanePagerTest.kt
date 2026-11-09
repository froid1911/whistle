package dev.whistle.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.data.FakeClient
import dev.whistle.app.data.HerdrDb
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.toEntity
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val M = "m1"

/** The pane pager follows the shown pane by id, and never fights the user's swipe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanePagerTest {
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
        // No layout rows: panes are ordered by id (p1 "A", p2 "B", p3 "C").
        runBlocking {
            db.sync().upsertWorkspaces(listOf(WWorkspace("w1", 1, "ws", activeTabId = "t1").toEntity(M)))
            db.sync().upsertTabs(listOf(WTab("t1", "w1", 1, "main").toEntity(M)))
            db.sync().upsertPanes(listOf(pane("p1", "A"), pane("p2", "B"), pane("p3", "C")).map { it.toEntity(M) })
        }
        compose.setContent { NoLockGate { WorkspaceScreen(repo, M, "w1", onBack = {}) } }
        waitFor("1/3")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun pane(id: String, label: String, status: String = "idle") =
        WPane(id, "t1", "w1", label = label, cwd = "/x", kind = PaneKind.AGENT, agent = "claude", agentStatus = status)

    private fun waitFor(text: String) =
        compose.waitUntil(3_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun db(block: suspend HerdrDb.() -> Unit) = runBlocking { db.block() }

    private fun assertShowing(label: String, position: String) {
        waitFor(position)
        compose.onNodeWithText(position).assertIsDisplayed()
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun swipeToB() {
        compose.onNodeWithTag(PANE_PAGER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertShowing("B", "2/3")
    }

    @Test
    fun statusUpdateOfAnotherPaneKeepsCurrentPage() {
        swipeToB()
        db { sync().setPaneStatus(M, "p3", "working") }
        Thread.sleep(300) // Room emits on its own thread
        compose.waitForIdle()
        assertShowing("B", "2/3")
    }

    @Test
    fun statusUpdateDuringSwipeAnimationDoesNotSnapBack() {
        compose.mainClock.autoAdvance = false
        // Drag from A past halfway toward B and let go: the settle animation to B is running.
        compose.onNodeWithTag(PANE_PAGER_TAG).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(-width * 0.05f, 0f)) }
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // Another pane's status flips while the page is still animating.
        db { sync().setPaneStatus(M, "p3", "working") }
        Thread.sleep(300) // Room emits on its own thread
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertShowing("B", "2/3")
    }

    @Test
    fun swipingStillTracksShownPaneAfterInterruptedFollow() {
        // An update during a drag must not stop the pager from tracking the shown pane afterwards.
        compose.onNodeWithTag(PANE_PAGER_TAG).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(-width * 0.05f, 0f)) }
        }
        db { sync().setPaneStatus(M, "p3", "working") }
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onNodeWithTag(PANE_PAGER_TAG).performTouchInput { up() }
        compose.waitForIdle()
        assertShowing("B", "2/3")
        // B is now the shown pane: inserting one before it must keep B on screen.
        db { sync().upsertPanes(listOf(pane("p0", "Z").toEntity(M))) }
        waitFor("3/4")
        compose.waitForIdle()
        assertShowing("B", "3/4")
    }

    @Test
    fun removingShownPaneMovesToNeighbour() {
        swipeToB()
        db { sync().deletePane(M, "p2") }
        waitFor("2/2")
        compose.waitForIdle()
        compose.onNodeWithText("C").assertIsDisplayed()
    }

    @Test
    fun insertingPaneBeforeShownKeepsSamePane() {
        swipeToB()
        db { sync().upsertPanes(listOf(pane("p0", "Z").toEntity(M))) } // sorts first
        waitFor("3/4")
        compose.waitForIdle()
        assertShowing("B", "3/4")
    }
}
