package dev.whistle.app.ui.chat

import dev.whistle.app.data.ChatState
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.protocol.Role
import dev.whistle.app.protocol.WMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PANE = "w1:p1"
private const val SK = "claude:abc"

class FakeChatSource : ChatSource {
    val msgs = MutableStateFlow<List<WMessage>>(emptyList())
    val state = MutableStateFlow<ChatState>(ChatState.Fresh)
    val liveText = MutableStateFlow<String?>(null)
    val status = MutableStateFlow(AgentStatus.IDLE)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var onCmd: suspend (String, JsonObject) -> JsonElement = { _, _ -> JsonObject(emptyMap()) }
    var loadOlderCalls = 0
    var loadOlderGate: CompletableDeferred<Unit>? = null
    var ensureCalls = 0
    val watched = mutableListOf<String>()

    override fun messages(sessionKey: String): Flow<List<WMessage>> = msgs
    override fun chatState(sessionKey: String): Flow<ChatState> = state
    override fun live(paneId: String): StateFlow<String?> = liveText
    override fun status(paneId: String): Flow<AgentStatus> = status
    override suspend fun cmd(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return onCmd(method, params)
    }
    override suspend fun ensureHistory(sessionKey: String) { ensureCalls++ }
    override suspend fun loadOlder(sessionKey: String) {
        loadOlderCalls++
        loadOlderGate?.await()
    }
    override fun watch(paneId: String) { watched += "+$paneId" }
    override fun unwatch(paneId: String) { watched += "-$paneId" }

    /** The machine's last applied seq; defaults to the cached head. */
    var head: Long? = null
    val backend = object : PromptOutbox.Backend {
        override suspend fun cmd(machineId: String, method: String, params: JsonObject) = this@FakeChatSource.cmd(method, params)
        override suspend fun headSeq(machineId: String) = head ?: (msgs.value.maxOfOrNull { it.seq } ?: 0L)
        override fun status(machineId: String, paneId: String) = status
    }
}

fun msg(seq: Long, role: String, text: String = "", callId: String? = null, tool: String? = null, input: JsonObject? = null) =
    WMessage(id = "m$seq", seq = seq, sessionKey = SK, role = role, text = text, toolCallId = callId, toolName = tool, input = input)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private fun TestScope.vm(
        src: FakeChatSource,
        outbox: PromptOutbox = PromptOutbox(backgroundScope, src.backend),
        scope: CoroutineScope = backgroundScope,
        sessionKey: String = SK,
    ): ChatViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ChatViewModel(src, PANE, sessionKey, scope, outbox.forPane("m1", PANE), dispatcher).also {
            it.start()
            runCurrent()
        }
    }

    @Test
    fun sendShowsPendingThenClears() = runTest {
        val src = FakeChatSource()
        src.msgs.value = listOf(msg(1, Role.USER, "old"), msg(2, Role.ASSISTANT, "hi"))
        val vm = vm(src)

        vm.send("  hello there \n")
        runCurrent()
        val (method, params) = src.calls.single()
        assertEquals("agent.prompt", method)
        assertEquals(PANE, params["target"]!!.jsonPrimitive.content)
        assertEquals("hello there", params["text"]!!.jsonPrimitive.content)
        assertNull("fire-and-forget: no wait", params["wait"])
        assertEquals(1, vm.pending.value.size)
        assertEquals(PendingState.Sent, vm.pending.value.single().state)

        // An older user message with the same text does not match (sent before).
        src.msgs.value = src.msgs.value + msg(3, Role.ASSISTANT, "x")
        runCurrent()
        assertEquals(1, vm.pending.value.size)

        src.msgs.value = src.msgs.value + msg(4, Role.USER, "hello there")
        runCurrent()
        assertTrue(vm.pending.value.isEmpty())
    }

    @Test
    fun pendingNotSeenAfterTimeoutWhenSettled() = runTest {
        val src = FakeChatSource()
        val vm = vm(src)
        vm.send("hi")
        runCurrent()
        src.status.value = AgentStatus.WORKING
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(PendingState.Sent, vm.pending.value.single().state)
        src.status.value = AgentStatus.DONE
        runCurrent()
        assertEquals(PendingState.NotSeen, vm.pending.value.single().state)
    }

    @Test
    fun sendErrorShowsRetry() = runTest {
        val src = FakeChatSource()
        src.onCmd = { _, _ -> throw BridgeError("herdr_unavailable", "down") }
        val vm = vm(src)
        vm.send("hi")
        runCurrent()
        val p = vm.pending.value.single()
        assertTrue(p.state is PendingState.Failed)
        src.onCmd = { _, _ -> JsonObject(emptyMap()) }
        vm.retry(p.id)
        runCurrent()
        assertEquals(PendingState.Sent, vm.pending.value.single().state)
        assertEquals(2, src.calls.size)
    }

    @Test
    fun stalledPromptIsUnconfirmedNotRetried() = runTest {
        val src = FakeChatSource()
        src.onCmd = { _, _ -> throw BridgeError("agent_prompt_stalled", "stalled") }
        val vm = vm(src)
        vm.send("hi")
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(PendingState.Unconfirmed, vm.pending.value.single().state)
        assertEquals(1, src.calls.size)
        // It may still show up in the transcript.
        src.msgs.value = listOf(msg(1, Role.USER, "hi"))
        runCurrent()
        assertTrue(vm.pending.value.isEmpty())
    }

    /** I1: errors after which the prompt may have reached the agent are Unconfirmed (cleared by the transcript). */
    @Test
    fun ambiguousSendErrorsAreUnconfirmedAndClearedByMatch() = runTest {
        for (code in listOf("disconnected", "client_timeout", "internal", "timeout", "agent_prompt_stalled")) {
            val src = FakeChatSource()
            src.onCmd = { _, _ -> throw BridgeError(code, "x") }
            val vm = vm(src)
            vm.send("hi $code")
            runCurrent()
            assertEquals(code, PendingState.Unconfirmed, vm.pending.value.single().state)
            src.msgs.value = listOf(msg(1, Role.USER, "hi $code"))
            runCurrent()
            assertTrue(code, vm.pending.value.isEmpty())
        }
        // A non-BridgeError failure is just as ambiguous.
        val src = FakeChatSource()
        src.onCmd = { _, _ -> throw IllegalStateException("boom") }
        val vm = vm(src)
        vm.send("hi")
        runCurrent()
        assertEquals(PendingState.Unconfirmed, vm.pending.value.single().state)
    }

    /** I1: errors that mean "not delivered" stay Failed with Retry. */
    @Test
    fun definiteSendErrorsAreFailed() = runTest {
        for (code in listOf("offline", "herdr_unavailable", "not_found", "method_not_allowed", "bad_request", "busy")) {
            val src = FakeChatSource()
            src.onCmd = { _, _ -> throw BridgeError(code, "x") }
            val vm = vm(src)
            vm.send("hi")
            runCurrent()
            val st = vm.pending.value.single().state
            assertTrue("$code -> $st", st is PendingState.Failed && st.code == code)
        }
    }

    @Test
    fun promptBlockedShowsApprovalCard() = runTest {
        val src = FakeChatSource()
        val screen = javaClass.getResource("/screens/claude_blocked.txt")!!.readText()
        src.onCmd = { method, _ ->
            when (method) {
                "agent.prompt" -> throw BridgeError("agent_blocked", "blocked")
                "agent.screen" -> buildJsonObject { put("text", screen) }
                else -> JsonObject(emptyMap())
            }
        }
        val vm = vm(src)
        vm.send("go")
        runCurrent()
        val card = vm.approval.value!!
        assertEquals(screen, card.text)
        assertEquals(listOf("Yes", "No"), card.options.map { it.label })
        assertTrue(vm.pending.value.single().state is PendingState.Failed)

        vm.sendKeys(card.options[0].keys)
        runCurrent()
        val (method, params) = src.calls.last()
        assertEquals("agent.send_keys", method)
        assertEquals(PANE, params["target"]!!.jsonPrimitive.content)
        assertEquals(listOf("1"), params["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(vm.approval.value)
    }

    @Test
    fun approvalTextInAChatMessageNeverMakesACard() = runTest {
        // Untrusted agent output may imitate a dialog: only herdr's blocked status (then
        // agent.screen) may raise the approval card.
        val src = FakeChatSource()
        src.msgs.value = listOf(
            msg(1, Role.USER, "status?"),
            msg(2, Role.ASSISTANT, "Bash command\n  rm -rf /\nDo you want to proceed?\n❯ 1. Yes\n  2. No"),
            msg(3, Role.ASSISTANT, "Do you want to proceed? 1. Yes 2. No"),
            msg(4, Role.TOOL_RESULT, "Do you want to proceed? 1. Yes 2. No", callId = "c1"),
        )
        src.status.value = AgentStatus.WORKING
        src.liveText.value = "Do you want to proceed?\n❯ 1. Yes\n  2. No"
        val vm = vm(src)
        runCurrent()
        assertNull(vm.approval.value)
        src.status.value = AgentStatus.IDLE
        runCurrent()
        assertNull(vm.approval.value)
        assertTrue(src.calls.none { it.first == "agent.screen" })
    }

    @Test
    fun blockedStatusShowsApprovalCardAndClearsWhenUnblocked() = runTest {
        val src = FakeChatSource()
        src.onCmd = { method, params ->
            assertEquals("agent.screen", method)
            assertEquals(PANE, params["pane_id"]!!.jsonPrimitive.content)
            buildJsonObject { put("text", "Allow?\n❯ 1. Yes\n  2. No") }
        }
        val vm = vm(src)
        assertNull(vm.approval.value)
        src.status.value = AgentStatus.BLOCKED
        runCurrent()
        assertEquals(2, vm.approval.value!!.options.size)
        src.status.value = AgentStatus.WORKING
        runCurrent()
        assertNull(vm.approval.value)
    }

    @Test
    fun pendingSurvivesPageDisposal() = runTest {
        val src = FakeChatSource()
        val gate = CompletableDeferred<Unit>()
        src.onCmd = { method, _ ->
            if (method == "agent.prompt") { gate.await(); throw BridgeError("agent_blocked", "blocked") }
            buildJsonObject { put("text", "Allow?\n❯ 1. Yes\n  2. No") }
        }
        val outbox = PromptOutbox(backgroundScope, src.backend)
        val page1 = backgroundScope + Job()
        val vm1 = vm(src, outbox, page1)
        vm1.send("deploy")
        runCurrent()
        assertEquals(PendingState.Sending, vm1.pending.value.single().state)
        // Swiped away while agent.prompt is in flight: the page's scope is cancelled.
        vm1.stop()
        page1.cancel()
        gate.complete(Unit)
        runCurrent()
        // The outcome was still recorded...
        val vm2 = vm(src, outbox)
        val p = vm2.pending.value.single()
        assertEquals("deploy", p.text)
        assertEquals("agent_blocked", (p.state as PendingState.Failed).code)
        // ...and the returning page opens the approval card for it (once).
        assertEquals(2, vm2.approval.value!!.options.size)
        assertTrue(vm2.pending.value.single().blockedAcked)
    }

    @Test
    fun pendingOutcomeKeptAcrossRecreation() = runTest {
        val src = FakeChatSource()
        src.onCmd = { _, _ -> throw BridgeError("agent_prompt_stalled", "stalled") }
        val outbox = PromptOutbox(backgroundScope, src.backend)
        val page1 = backgroundScope + Job()
        vm(src, outbox, page1).send("hi")
        runCurrent()
        page1.cancel()
        val vm2 = vm(src, outbox)
        assertEquals(PendingState.Unconfirmed, vm2.pending.value.single().state)
    }

    @Test
    fun sessionSwitchKeepsPending() = runTest {
        val src = FakeChatSource()
        val outbox = PromptOutbox(backgroundScope, src.backend)
        val page1 = backgroundScope + Job()
        vm(src, outbox, page1, sessionKey = "pane:$PANE").send("start")
        runCurrent()
        page1.cancel()
        // The agent session appeared: the pane's chat is now keyed by the new session.
        src.msgs.value = emptyList()
        val vm2 = vm(src, outbox, sessionKey = "claude:new")
        assertEquals("start", vm2.pending.value.single().text)
        src.msgs.value = listOf(WMessage(id = "n1", seq = 50, sessionKey = "claude:new", role = Role.USER, text = "start"))
        runCurrent()
        assertTrue(vm2.pending.value.isEmpty())
    }

    @Test
    fun olderIdenticalMessageDoesNotClearPending() = runTest {
        val src = FakeChatSource()
        // Cache holds an older "yes" (seq 3) but the machine has applied up to 10 (cache stale / not loaded).
        src.msgs.value = listOf(msg(3, Role.USER, "yes"))
        src.head = 10
        val vm = vm(src)
        src.msgs.value = emptyList() // e.g. first Room emission not in yet
        runCurrent()
        vm.send("yes")
        runCurrent()
        src.msgs.value = listOf(msg(3, Role.USER, "yes"))
        runCurrent()
        assertEquals(PendingState.Sent, vm.pending.value.single().state)
        src.msgs.value = src.msgs.value + msg(11, Role.USER, "yes")
        runCurrent()
        assertTrue(vm.pending.value.isEmpty())
    }

    @Test
    fun numberedOptionsMapToKeys() {
        val screen = javaClass.getResource("/screens/claude_blocked.txt")!!.readText()
        val opts = parseApprovalOptions(screen)
        assertEquals(listOf("Yes", "No"), opts.map { it.label })
        assertEquals(listOf(listOf("1"), listOf("2")), opts.map { it.keys })
        assertTrue(opts[0].selected)

        // A numbered list earlier in the reply is not the dialog: the last run starting at 1 wins.
        val s2 = """
            Plan:
            1. Read the file
            2. Edit it
            │ Do you want to make this edit?                     │
            │ ❯ 1. Yes                                           │
            │   2. Yes, allow all edits during this session      │
            │   3. No, and tell Claude what to do differently    │
        """.trimIndent()
        val o2 = parseApprovalOptions(s2)
        assertEquals(3, o2.size)
        assertEquals("Yes, allow all edits during this session", o2[1].label)
        assertEquals(listOf("3"), o2[2].keys)
        assertTrue(parseApprovalOptions("nothing to choose").isEmpty())
        assertEquals(listOf("enter"), APPROVE_KEYS)
        assertEquals(listOf("esc"), CANCEL_KEYS)
    }

    @Test
    fun toolResultPairsWithCall() {
        val input = buildJsonObject { put("command", "ls -la") }
        val rows = groupMessages(
            listOf(
                msg(1, Role.USER, "list"),
                msg(2, Role.THINKING, "hmm"),
                msg(3, Role.TOOL_CALL, "", callId = "c1", tool = "Bash", input = input),
                msg(4, Role.TOOL_RESULT, "a\nb", callId = "c1"),
                msg(5, Role.ASSISTANT, "done"),
                msg(6, Role.USER, "thanks"),
                msg(7, Role.TOOL_RESULT, "orphan", callId = "zz"),
            ),
        )
        assertEquals(listOf("m1", "m2", "m3", "m5", "m6", "m7"), rows.map { it.key })
        val tool = rows[2] as ChatRow.Tool
        assertEquals("m4", tool.result!!.id)
        assertEquals("Bash", tool.call!!.toolName)
        assertEquals("ls -la", toolSummary(tool.call!!))
        // m2..m5 form one turn; the first row of the turn starts it.
        val turnRows = rows.subList(1, 4)
        assertTrue(turnRows.all { it.turnKey == "m2" })
        assertEquals(listOf(true, false, false), turnRows.map { it.firstInTurn })
        val orphan = rows[5] as ChatRow.Tool
        assertNull(orphan.call)
        assertEquals("m7", orphan.result!!.id)
        assertTrue(orphan.firstInTurn)
    }

    @Test
    fun groupingReusesUnchangedRows() {
        val a = listOf(msg(1, Role.USER, "a"), msg(2, Role.ASSISTANT, "b"))
        val first = groupMessages(a)
        // New instances with equal content (the repository decodes per emission).
        val second = groupMessages(a.map { it.copy() } + msg(3, Role.ASSISTANT, "c"), first)
        assertTrue(first[0] === second[0])
        assertTrue(first[1] === second[1])
    }

    @Test
    fun historyPaginates() = runTest {
        val src = FakeChatSource()
        val gate = CompletableDeferred<Unit>()
        src.loadOlderGate = gate
        val vm = vm(src)
        assertEquals(1, src.ensureCalls)
        vm.onReachedTop()
        runCurrent()
        assertEquals("nothing cached: the first page is ensureHistory's", 0, src.loadOlderCalls)
        src.msgs.value = listOf(msg(5, Role.USER, "q"))
        runCurrent()
        vm.onReachedTop()
        vm.onReachedTop()
        runCurrent()
        assertEquals("one load at a time", 1, src.loadOlderCalls)
        gate.complete(Unit)
        runCurrent()
        vm.onReachedTop()
        runCurrent()
        assertEquals(2, src.loadOlderCalls)
        vm.retryHistory()
        runCurrent()
        assertEquals(2, src.ensureCalls)
    }

    @Test
    fun watchesWhileStarted() = runTest {
        val src = FakeChatSource()
        val vm = vm(src)
        vm.stop()
        assertEquals(listOf("+$PANE", "-$PANE"), src.watched)
    }

    @Test
    fun liveBubbleClearsOnLiveClear() = runTest {
        val src = FakeChatSource()
        src.msgs.value = listOf(msg(1, Role.USER, "q"))
        val vm = vm(src)
        src.liveText.value = "thinking about it"
        runCurrent()
        assertEquals("thinking about it", vm.live.value)

        // live.clear before the reply reached the cache: keep the text (no flicker).
        src.liveText.value = null
        runCurrent()
        assertEquals("thinking about it", vm.live.value)
        // A tool call of the turn is not the final reply.
        src.msgs.value = src.msgs.value + msg(2, Role.TOOL_CALL, callId = "c", tool = "Bash")
        runCurrent()
        assertEquals("thinking about it", vm.live.value)
        src.msgs.value = src.msgs.value + msg(3, Role.ASSISTANT, "answer")
        runCurrent()
        assertNull(vm.live.value)

        // Second turn: the reply never shows up -> cleared after 3 s.
        src.liveText.value = "again"
        runCurrent()
        src.liveText.value = null
        runCurrent()
        advanceTimeBy(2_900)
        runCurrent()
        assertEquals("again", vm.live.value)
        advanceTimeBy(200)
        runCurrent()
        assertNull(vm.live.value)

        // Reply already cached when live.clear arrives -> cleared at once.
        src.liveText.value = "third"
        runCurrent()
        src.msgs.value = src.msgs.value + msg(4, Role.ASSISTANT, "r3")
        runCurrent()
        assertEquals("third", vm.live.value)
        src.liveText.value = null
        runCurrent()
        assertNull(vm.live.value)
    }

    @Test
    fun liveHeldWhenChatOpensMidTurn() = runTest {
        val src = FakeChatSource()
        // live is a StateFlow (already set) while the first cache read is still pending.
        src.liveText.value = "working on it"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ChatViewModel(src, PANE, SK, backgroundScope, PromptOutbox(backgroundScope, src.backend).forPane("m1", PANE), dispatcher)
        src.msgs.value = listOf(msg(1, Role.USER, "q"), msg(2, Role.ASSISTANT, "previous reply"))
        vm.start()
        runCurrent()
        assertEquals("working on it", vm.live.value)
        src.liveText.value = null
        runCurrent()
        assertEquals("the old reply is not this turn's", "working on it", vm.live.value)
        src.msgs.value = src.msgs.value + msg(3, Role.ASSISTANT, "new reply")
        runCurrent()
        assertNull(vm.live.value)
    }

    @Test
    fun editInputsRenderAsDiff() {
        val edit = buildJsonObject { put("file_path", "/a.kt"); put("old_string", "a\nb\nc"); put("new_string", "a\nB\nc") }
        val d = toolDiff("Edit", edit)!!
        assertEquals("/a.kt", d.path)
        assertEquals(
            listOf(DiffKind.Context to "a", DiffKind.Removed to "b", DiffKind.Added to "B", DiffKind.Context to "c"),
            d.lines.map { it.kind to it.text },
        )
        val multi = buildJsonObject {
            put("path", "/b.ts")
            put("edits", buildJsonArray {
                add(buildJsonObject { put("oldText", "x"); put("newText", "y") })
                add(buildJsonObject { put("oldText", "p"); put("newText", "q") })
            })
        }
        val md = toolDiff("edit", multi)!!
        assertEquals(2, md.lines.count { it.kind == DiffKind.Added })
        assertEquals(1, md.lines.count { it.kind == DiffKind.Hunk })
        val write = toolDiff("Write", buildJsonObject { put("file_path", "/c"); put("content", "1\n2") })!!
        assertEquals(listOf(DiffKind.Added, DiffKind.Added), write.lines.map { it.kind })
        assertNull(toolDiff("Bash", buildJsonObject { put("command", "ls") }))

        val uni = parseUnifiedDiff("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n a\n-b\n+c")
        assertEquals(
            listOf(DiffKind.Header, DiffKind.Header, DiffKind.Header, DiffKind.Hunk, DiffKind.Context, DiffKind.Removed, DiffKind.Added),
            uni.map { it.kind },
        )
        assertEquals("b", uni[5].text)
    }
}
