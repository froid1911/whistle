package dev.whistle.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.data.FakeClient
import dev.whistle.app.data.HerdrDb
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.toEntity
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.CursorStore
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import dev.whistle.app.ui.components.RippleKey
import dev.whistle.app.ui.herd.HerdScreen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val M = "m1"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspacesScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: HerdrDb
    private lateinit var scope: CoroutineScope
    private lateinit var repo: HerdrRepository
    private val fakes = mutableMapOf<String, FakeClient>()
    private val machine = Machine(M, "myserver", "http://100.64.0.1:8787")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HerdrDb::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = HerdrRepository(db, { id, _: CursorStore -> FakeClient().also { fakes[id] = it } }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun cache(workspaces: List<WWorkspace>, tabs: List<WTab>, panes: List<WPane>) = runBlocking {
        db.sync().upsertWorkspaces(workspaces.map { it.toEntity(M) })
        db.sync().upsertTabs(tabs.map { it.toEntity(M) })
        db.sync().upsertPanes(panes.map { it.toEntity(M) })
    }

    private fun pane(id: String, tab: String, ws: String, status: String? = null, kind: String = PaneKind.AGENT) =
        WPane(id, tab, ws, label = "", cwd = "/home/you/$id", kind = kind, agent = if (kind == PaneKind.AGENT) "claude" else null, agentStatus = status)

    private fun waitForText(text: String) =
        compose.waitUntil(2_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    @Config(qualifiers = "w411dp-h1400dp") // the whole herd on screen (LazyColumn composes visible items only)
    fun showsCachedHerdImmediately() {
        cache(
            listOf(WWorkspace("w1", 1, "whistle"), WWorkspace("w2", 2, "dotfiles")),
            listOf(WTab("t1", "w1", 1, "main"), WTab("t2", "w2", 1, "main")),
            listOf(pane("p1", "t1", "w1", "idle"), pane("p2", "t1", "w1", "working"), pane("s1", "t2", "w2", kind = PaneKind.SHELL)),
        )
        compose.setContent { NoLockGate { HerdScreen(repo, machine, onOpenPane = {}, onOpenWorkspace = {}, onOpenMachines = {}) } }
        // The client never connects (the fake stays Offline): everything comes from the cache.
        assertEquals(ConnState.Offline, fakes.getValue(M).connection.value)
        waitForText("whistle")
        compose.onNodeWithText("Your herd").assertIsDisplayed()
        compose.onNodeWithText("whistle").assertIsDisplayed()
        compose.onNodeWithText("dotfiles").assertIsDisplayed()
        compose.onNodeWithText("2 agents on myserver · 1 working").assertIsDisplayed()
        compose.onNodeWithTag("shell:s1").assertIsDisplayed()
        compose.onNodeWithText(BannerText.OFFLINE).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp") // the whole herd on screen (LazyColumn composes visible items only)
    fun orbColorsFollowStatus() {
        val statuses = listOf("idle", "working", "blocked", "done", null)
        cache(
            listOf(WWorkspace("w0", 1, "ws0")),
            listOf(WTab("t0", "w0", 1, "t")),
            statuses.mapIndexed { i, st -> pane("p$i", "t0", "w0", st) },
        )
        compose.setContent { NoLockGate { HerdScreen(repo, machine, onOpenPane = {}, onOpenWorkspace = {}, onOpenMachines = {}) } }
        waitForText("ws0")
        val expected = mapOf(
            "p0" to StatusColors.IDLE, "p1" to StatusColors.WORKING, "p2" to StatusColors.BLOCKED,
            "p3" to StatusColors.DONE, "p4" to StatusColors.UNKNOWN,
        )
        for ((p, color) in expected) {
            compose.onNodeWithTag("orb:$p", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(StatusColorKey, color))
        }
        // Only the needs-you orb carries the lamp ripple.
        compose.onNodeWithTag("orb:p2", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(RippleKey, true))
        compose.onNodeWithTag("orb:p1", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(RippleKey, false))
        // done counts as settled, like idle.
        assertTrue(AgentStatus.DONE.settled)
        assertEquals(StatusColors.DONE, statusColor(AgentStatus.DONE))
    }

    @Test
    fun needsYouCardAnswersInline() {
        val screen = """
            Bash command
              ssh-keygen -lf ~/.ssh/authorized_keys
            Do you want to proceed?
            ❯ 1. Yes
              2. No, and tell Claude what to do differently
        """.trimIndent()
        cache(
            listOf(WWorkspace("w1", 1, "home")),
            listOf(WTab("t1", "w1", 1, "main")),
            listOf(pane("p1", "t1", "w1", "blocked")),
        )
        repo.connect(M)
        val fake = { fakes.getValue(M) }
        fake().onCall = { m, _ -> if (m == "agent.screen") buildJsonObject { put("text", screen) } else JsonObject(emptyMap()) }
        compose.setContent { NoLockGate { HerdScreen(repo, machine, onOpenPane = {}, onOpenWorkspace = {}, onOpenMachines = {}) } }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Do you want to proceed?", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Needs you").assertIsDisplayed()
        compose.onNodeWithText("home · waiting for you").assertIsDisplayed()
        compose.onNodeWithTag("approvalPrompt", useUnmergedTree = true).assertIsDisplayed() // the full prompt, in the inset
        // A quick tap does not answer: it says to hold.
        compose.onNodeWithText("Yes").performClick()
        compose.onNodeWithTag("holdHint", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(fake().calls.any { it.first == "agent.send_keys" })
        compose.onNodeWithText("Yes").hold(compose)
        compose.waitUntil(2_000) { fake().calls.any { it.first == "agent.send_keys" } }
        val keys = fake().calls.first { it.first == "agent.send_keys" }.second
        assertEquals("p1", (keys["target"] as JsonPrimitive).content)
        assertEquals(listOf("1"), (keys["keys"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun closePaneAsksConfirmation() {
        cache(
            listOf(WWorkspace("w1", 1, "whistle", activeTabId = "t1")),
            listOf(WTab("t1", "w1", 1, "main")),
            listOf(pane("p1", "t1", "w1", kind = PaneKind.SHELL)),
        )
        compose.setContent { NoLockGate { WorkspaceScreen(repo, M, "w1", onBack = {}) } }
        val fake = fakes.getValue(M)
        fake.onCall = { _, _ -> JsonObject(emptyMap()) }
        // A single tab shows no tab pills: wait for the pane header instead.
        compose.waitUntil(2_000) { compose.onAllNodesWithTag(PANE_HEADER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Pane actions").performClick()
        compose.onNodeWithText("Close pane").performClick()
        compose.onNodeWithText("Close pane?").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue("nothing sent before confirming", fake.calls.isEmpty())

        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(2_000) { fake.calls.isNotEmpty() }
        val (method, params) = fake.calls.single()
        assertEquals("pane.close", method)
        assertEquals("p1", (params["pane_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse("never sends focus", "focus" in params)
    }

    @Test
    fun bannerTextPerConnState() {
        var state by mutableStateOf(ConnState.Connecting)
        compose.setContent { NoLockGate { ConnBanner(state) } }
        compose.onNodeWithText(BannerText.SYNCING).assertIsDisplayed()
        mapOf(
            ConnState.Syncing to BannerText.SYNCING,
            ConnState.Offline to BannerText.OFFLINE,
            ConnState.HerdrOffline to BannerText.HERDR_OFFLINE,
        ).forEach { (st, text) ->
            state = st
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        state = ConnState.Synced
        compose.waitForIdle()
        for (t in listOf(BannerText.SYNCING, BannerText.OFFLINE, BannerText.HERDR_OFFLINE)) {
            assertTrue(compose.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty())
        }
        assertEquals("Syncing…", BannerText.SYNCING)
        assertEquals("Offline — showing cached", BannerText.OFFLINE)
        assertEquals("herdr offline", BannerText.HERDR_OFFLINE)
    }
}
