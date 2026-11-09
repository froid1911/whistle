package dev.whistle.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.whistle.app.data.PaneUi
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.ui.chat.ApprovalUi
import dev.whistle.app.ui.chat.parseApprovalOptions
import dev.whistle.app.ui.herd.HerdCallbacks
import dev.whistle.app.ui.herd.HerdContent
import dev.whistle.app.ui.herd.buildHerd
import dev.whistle.app.ui.theme.LocalReducedMotion
import dev.whistle.app.ui.theme.WhistleTheme
import java.io.File
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.data.FakeClient
import dev.whistle.app.data.HerdrDb
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.toEntity
import dev.whistle.app.protocol.CursorStore
import dev.whistle.app.protocol.Role
import dev.whistle.app.protocol.WPane
import dev.whistle.app.protocol.WTab
import dev.whistle.app.protocol.WWorkspace
import dev.whistle.app.terminal.TerminalRouteScreen
import dev.whistle.app.ui.chat.ChatScreen
import dev.whistle.app.ui.chat.FakeChatSource
import dev.whistle.app.ui.chat.PromptOutbox
import dev.whistle.app.ui.chat.msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.whistle.app.R
import dev.whistle.app.ui.theme.DarkWhistle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val M = "m1"

/** Where review screenshots go (android/app/build/ui-shots). */
private val SHOTS = File("build/ui-shots")

/** Renders the main screens with the real theme and saves PNGs for the design review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HerdrDb
    private lateinit var scope: CoroutineScope
    private lateinit var repo: HerdrRepository
    private val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HerdrDb::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = HerdrRepository(db, { _, _: CursorStore -> FakeClient() }, scope)
    }

    @After
    fun tearDown() {
        chatScope.cancel()
        scope.cancel()
        db.close()
    }

    private fun cache(workspaces: List<WWorkspace>, tabs: List<WTab>, panes: List<WPane>) = runBlocking {
        db.sync().upsertWorkspaces(workspaces.map { it.toEntity(M) })
        db.sync().upsertTabs(tabs.map { it.toEntity(M) })
        db.sync().upsertPanes(panes.map { it.toEntity(M) })
    }

    private fun shot(name: String, dark: Boolean = true, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent { NoLockGate {
            WhistleTheme(dark = dark) {
                CompositionLocalProvider(LocalReducedMotion provides true) { content() }
            }
        } }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        // captureToImage() waits for a redraw Robolectric never reports; draw the window ourselves.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        if (SHOTS.parentFile?.isDirectory == true) {
            SHOTS.mkdirs()
            File(SHOTS, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun ws(id: String, n: Int, label: String) = WorkspaceUi(id, n, label, AgentStatus.UNKNOWN, null)
    private fun agent(id: String, ws: String, kind: String, status: AgentStatus, label: String = "") =
        PaneUi(id, "t-$ws", ws, label, "/home/you/$ws", PaneKind.AGENT, kind, status, null, true, 0, 0, 1, 1)
    private fun shell(id: String, ws: String, cwd: String) =
        PaneUi(id, "t-$ws", ws, "", cwd, PaneKind.SHELL, null, AgentStatus.UNKNOWN, null, false, 0, 1, 1, 1)

    private val herd = buildHerd(
        "myserver",
        listOf(ws("w1", 1, "home"), ws("w2", 2, "todo-api"), ws("w3", 3, "api")),
        listOf(
            agent("p1", "w1", "claude", AgentStatus.BLOCKED),
            shell("s1", "w1", "~/dotfiles"),
            agent("p2", "w2", "omp", AgentStatus.WORKING),
            agent("p3", "w2", "claude", AgentStatus.IDLE, label = "reviewer"),
            agent("p4", "w3", "codex", AgentStatus.DONE),
            shell("s2", "w3", "~/src/api"),
        ),
    )
    private val screen = """
        ⏺ Let me check which keys are authorized.
        ╭──────────────────────────────────────────╮
        │ Bash command                             │
        │   ssh-keygen -lf ~/.ssh/authorized_keys  │
        │ Do you want to proceed?                  │
        │ ❯ 1. Yes                                 │
        │   2. No                                  │
        ╰──────────────────────────────────────────╯
    """.trimIndent()

    @Test
    fun herd() = shot("herd") {
        HerdContent("myserver", ConnState.Synced, herd, mapOf("p1" to ApprovalUi(screen, parseApprovalOptions(screen))), HerdCallbacks())
    }

    @Test
    fun herdLight() = shot("herd-light", dark = false) {
        HerdContent("myserver", ConnState.Offline, herd, mapOf("p1" to ApprovalUi(screen, parseApprovalOptions(screen))), HerdCallbacks())
    }

    private fun call(seq: Long, tool: String, key: String, value: String) =
        msg(seq, Role.TOOL_CALL, callId = "c$seq", tool = tool, input = buildJsonObject { put(key, value) })

    private fun chatSource(status: AgentStatus): FakeChatSource = FakeChatSource().apply {
        msgs.value = listOf(
            msg(1, Role.USER, "add pagination to the todo list endpoint"),
            msg(2, Role.THINKING, "The user wants paginated todos; read the handler, then the store."),
            msg(3, Role.ASSISTANT, "Let me read the handler and the store first."),
            call(4, "Read", "file_path", "/home/you/src/todo-api/README.md"),
            msg(5, Role.TOOL_RESULT, "ok", callId = "c4"),
            call(6, "Grep", "pattern", "schema"),
            msg(7, Role.TOOL_RESULT, "ok", callId = "c6"),
            call(8, "Read", "file_path", "/src/todo-api/handlers/todos.go"),
            msg(9, Role.TOOL_RESULT, "ok", callId = "c8"),
            call(10, "Bash", "command", "grep -n ListTodos store/*.go"),
            msg(11, Role.TOOL_RESULT, "42: func (s *Store) ListTodos(ctx context.Context) ([]Todo, error) {", callId = "c10"),
            call(12, "Edit", "file_path", "/src/todo-api/store/todos.go"),
            msg(
                13, Role.ASSISTANT,
                "Found the list query in the store. I'll add `limit` and `offset` to " +
                    "`ListTodos` next:\n\n- **limit** capped at 100\n- **offset** from the query string",
            ),
        )
        this.status.value = status
        if (status == AgentStatus.WORKING) {
            liveText.value = "⏺ Writing todos.go…\n$ grep -n ListTodos store/*.go\n42:  func (s *Store) ListTodos(ctx context.Context) ([]Todo, error) {\n" +
                "57:  func (s *Store) CountTodos(ctx context.Context) (int, error) {\n\n✻ Cogitating… (12s · esc to interrupt)"
        }
        onCmd = { m, _ ->
            if (m == "agent.screen") buildJsonObject { put("text", screen) } else JsonObject(emptyMap())
        }
    }

    private fun workspace(status: String, kind: String = "omp") {
        cache(
            listOf(WWorkspace("w2", 2, "todo-api")),
            listOf(WTab("t1", "w2", 1, "main", agentStatus = status), WTab("t2", "w2", 2, "tests", agentStatus = "idle")),
            listOf(
                WPane("p2", "t1", "w2", label = "", cwd = "/src/todo-api", kind = PaneKind.AGENT, agent = kind, agentStatus = status),
                WPane("s2", "t1", "w2", label = "", cwd = "/src/todo-api", kind = PaneKind.SHELL),
            ),
        )
    }

    @Composable
    private fun WorkspaceWithChat(src: FakeChatSource) {
        WorkspaceScreen(
            repo, M, "w2", onBack = {},
            paneContent = { _, pane -> ChatScreen(src, PromptOutbox(chatScope, src.backend).forPane(M, pane.id), pane) },
            onOpenTerminal = {}, onOpenChanges = {},
        )
    }

    /** Chat with folded tool chips, thinking line, markdown prose and the live strip. */
    @Test
    fun chat() {
        workspace("working")
        val src = chatSource(AgentStatus.WORKING)
        shot("chat") { WorkspaceWithChat(src) }
    }

    /** Keyboard up (simulated 1100 px IME inset): compact bar, 3-line live strip, the list gets the rest. */
    @Test
    fun chatIme() {
        workspace("working")
        val src = chatSource(AgentStatus.WORKING)
        shot("chat-ime") {
            val imeDp = with(androidx.compose.ui.platform.LocalDensity.current) { 1100.toDp() }
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalImeVisibleOverride provides true) { WorkspaceWithChat(src) }
                }
                // Stand-in for the keyboard.
                Box(Modifier.fillMaxWidth().height(imeDp).background(androidx.compose.ui.graphics.Color(0xFF2A2F2C)))
            }
        }
    }

    /** Same chat with the tool steps expanded. */
    @Test
    fun chatSteps() {
        workspace("working")
        val src = chatSource(AgentStatus.WORKING)
        shot("chat-steps", before = { compose.onAllNodesWithTag("toolChips")[0].performClick() }) { WorkspaceWithChat(src) }
    }

    /** Workspace screen: tab pills, pane header, a blocked agent's approval card in the stream. */
    @Test
    fun workspace() {
        workspace("blocked", kind = "claude")
        val src = chatSource(AgentStatus.BLOCKED)
        shot("workspace") { WorkspaceWithChat(src) }
    }

    /** Terminal route: header, inset area (the xterm WebView does not render under Robolectric), soft keys. */
    @Test
    fun terminal() {
        workspace("idle")
        // The pane title comes from a Room read that does not land while the spinner animates under
        // Robolectric, so the header shows the pane-id fallback here.
        shot("terminal") { TerminalRouteScreen(repo, M, "s2", onBack = {}) }
    }

    @Test
    fun machines() = shot("machines") {
        MachinesScreen(
            listOf(dev.whistle.app.protocol.Machine(M, "myserver", "http://100.64.0.1:8787")),
            repo, onOpen = {}, onSave = {}, onRemove = {}, newId = { "x" },
        )
    }

    @Test
    fun machinesFirstRun() {
        shot("machines-first-run") {
            MachinesScreen(emptyList(), repo, onOpen = {}, onSave = {}, onRemove = {}, newId = { "x" })
        }
        compose.onNodeWithTag("firstRun").assertExists()
        compose.onNodeWithText("Run the Whistle bridge on your server and press Ctrl+Alt+W in herdr.").assertExists()
        compose.onNodeWithTag("pairMachine").assertExists()
    }

    @Test
    fun machinesPaired() = shot("machines-paired") {
        MachinesScreen(
            listOf(
                dev.whistle.app.protocol.Machine(M, "myserver", "http://100.64.0.1:8787", "d1", "k", "K", "Pixel 8a"),
                dev.whistle.app.protocol.Machine("box", "box", "http://100.64.0.2:8787"),
            ),
            repo, onOpen = {}, onSave = {}, onRemove = {}, newId = { "x" },
        )
    }

    @Test
    fun pairScan() = shot("pair-scan") {
        dev.whistle.app.ui.pair.PairScreen(onBack = {}, pair = { _, _ -> error("x") }, onOpenMachine = {}, defaultDeviceName = "Pixel 8a")
    }

    @Test
    fun pairManual() = shot("pair-manual") {
        dev.whistle.app.ui.pair.PairScreen(
            onBack = {}, pair = { _, _ -> error("x") }, onOpenMachine = {}, defaultDeviceName = "Pixel 8a",
            initialStep = dev.whistle.app.ui.pair.PairStep.Manual,
        )
    }

    @Test
    fun pairConfirm() = shot("pair-confirm") {
        dev.whistle.app.ui.pair.PairScreen(
            onBack = {}, pair = { _, _ -> error("x") }, onOpenMachine = {}, defaultDeviceName = "Pixel 8a",
            initialStep = dev.whistle.app.ui.pair.PairStep.Confirm(
                dev.whistle.app.protocol.PairInvite("100.64.0.10:8787", "YYHQ7CZP3HHWAWWQO72E5EIULY", "MkR9tVZeCyn4s6dBt2aCjTQQfhPzyXnLrICFTJuIP-g", "myserver"),
            ),
        )
    }

    @Test
    fun pairDone() = shot("pair-done") {
        dev.whistle.app.ui.pair.PairScreen(
            onBack = {}, pair = { _, _ -> error("x") }, onOpenMachine = {}, defaultDeviceName = "Pixel 8a",
            initialStep = dev.whistle.app.ui.pair.PairStep.Done(
                dev.whistle.app.protocol.Machine(M, "myserver", "http://100.64.0.10:8787", "d1", "k", "K", "Pixel 8a"),
            ),
        )
    }

    @Test
    fun lock() = shot("lock") { dev.whistle.app.security.LockScreen(error = null, onUnlock = {}) }

    /** Launcher icon layers (adaptive: lamp whistle on night; monochrome for themed icons) and the tile icon. */
    @Test
    fun icon() = shot("icon") {
        Row(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2A2F2C)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(8.dp).size(170.dp).clip(CircleShape).background(DarkWhistle.night)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Box(Modifier.padding(8.dp).size(110.dp).clip(RoundedCornerShape(28.dp)).background(androidx.compose.ui.graphics.Color(0xFFDCE5DF))) {
                Image(
                    painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF2B4A3A)),
                )
            }
            Image(painterResource(R.drawable.ic_whistle_tile), null, Modifier.padding(8.dp).size(48.dp))
        }
    }
}
