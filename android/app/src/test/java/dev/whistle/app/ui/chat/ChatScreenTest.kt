package dev.whistle.app.ui.chat

import dev.whistle.app.ui.NoLockGate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextContains
import dev.whistle.app.ui.hold
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import dev.whistle.app.data.ChatState
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.protocol.Role
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private val pane = PaneUi(
        id = "w1:p1", tabId = "t1", workspaceId = "w1", label = "", cwd = "/src", kind = PaneKind.AGENT,
        agent = "claude", agentStatus = AgentStatus.WORKING, sessionKey = "claude:abc", hasTranscript = true,
        x = 0, y = 0, w = 1, h = 1,
    )

    @Test
    fun rendersTurnLiveBubbleAndSends() {
        val src = FakeChatSource()
        src.msgs.value = listOf(
            msg(1, Role.USER, "fix the build"),
            msg(2, Role.ASSISTANT, "Sure, **looking** at `build.gradle`"),
            msg(3, Role.TOOL_CALL, callId = "c1", tool = "Edit", input = buildJsonObject {
                put("file_path", "/src/build.gradle"); put("old_string", "a"); put("new_string", "b")
            }),
            msg(4, Role.TOOL_RESULT, "ok", callId = "c1"),
        )
        src.status.value = AgentStatus.WORKING
        src.liveText.value = "⏺ Running tests…"
        src.state.value = ChatState.Stale
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane) } }

        compose.onNodeWithText("fix the build").assertIsDisplayed()
        compose.onNodeWithText("edit build.gradle").assertIsDisplayed() // the turn's steps, folded into chips
        compose.onNodeWithText("⏺ Running tests…").assertIsDisplayed()
        compose.onNodeWithText("updating…").assertIsDisplayed()
        compose.onNodeWithText("Whistle to claude").assertIsDisplayed() // composer placeholder
        compose.onNodeWithTag("toolChips").performClick()
        compose.onNodeWithText("/src/build.gradle").assertIsDisplayed() // tool summary
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("+b").assertIsDisplayed()

        compose.onNodeWithTag("composer").performTextInput("next")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
        assertEquals("agent.prompt", src.calls.last().first)
        compose.onNodeWithText("next").assertIsDisplayed()
        assertEquals(listOf("+w1:p1"), src.watched)
    }

    @Test
    fun approvalLookalikeInChatTextShowsNoCard() {
        val src = FakeChatSource()
        src.msgs.value = listOf(
            msg(1, Role.USER, "go"),
            msg(2, Role.ASSISTANT, "Do you want to proceed? 1. Yes 2. No"),
        )
        src.status.value = AgentStatus.IDLE
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane.copy(agentStatus = AgentStatus.IDLE)) } }
        compose.onNodeWithText("Do you want to proceed? 1. Yes 2. No").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("approvalCard").fetchSemanticsNodes().size)
        assertTrue(src.calls.none { it.first == "agent.screen" })
    }

    @Test
    fun approvalCardNeedsAHoldAndWarnsAboutDestructiveCommands() {
        val screen = "────────────────────\n Bash command\n   sudo rm -rf /var/lib/app\n Do you want to proceed?\n ❯ 1. Yes\n   2. No"
        val src = FakeChatSource()
        src.onCmd = { m, _ -> if (m == "agent.screen") buildJsonObject { put("text", screen) } else kotlinx.serialization.json.JsonObject(emptyMap()) }
        src.status.value = AgentStatus.BLOCKED
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane.copy(agentStatus = AgentStatus.BLOCKED)) } }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("approvalCard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("approvalPrompt").assertTextContains("sudo rm -rf /var/lib/app", substring = true)
        compose.onNodeWithTag("destructiveWarning").assertTextContains("rm -rf, sudo", substring = true)
        compose.onNodeWithText("1. Yes").performClick()
        compose.onNodeWithText("Hold to answer").assertIsDisplayed()
        assertTrue(src.calls.none { it.first == "agent.send_keys" })
        compose.onNodeWithText("1. Yes").hold(compose)
        compose.waitUntil(2_000) { src.calls.any { it.first == "agent.send_keys" } }
    }

    @Test
    fun errorStateOffersRetry() {
        val src = FakeChatSource()
        src.state.value = ChatState.Error("herdr_unavailable")
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane) } }
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(2, src.ensureCalls)
    }

    /** Records step-up requests; [allow] decides them on the spot. */
    private class RecordingStepUp(var allow: Boolean) : dev.whistle.app.security.StepUp {
        val asked = mutableListOf<Pair<String, String?>>()
        override fun require(reason: String, detail: String?, onDenied: () -> Unit, action: () -> Unit) {
            asked += reason to detail
            if (allow) action() else onDenied()
        }
    }

    private class TestLock : dev.whistle.app.security.LockStatus {
        override val locked = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val epoch = kotlinx.coroutines.flow.MutableStateFlow(1)
    }

    @androidx.compose.runtime.Composable
    private fun Gated(stepUp: RecordingStepUp, lock: TestLock, content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.runtime.CompositionLocalProvider(
            dev.whistle.app.security.LocalStepUp provides stepUp,
            dev.whistle.app.security.LocalAppLock provides lock,
            content = content,
        )

    @Test
    fun composerSendStepsUpOncePerLockEpochAndADenialKeepsTheDraft() {
        val src = FakeChatSource()
        val stepUp = RecordingStepUp(allow = false)
        val lock = TestLock()
        compose.setContent { Gated(stepUp, lock) { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane) } }
        fun send(text: String) {
            compose.onNodeWithTag("composer").performTextInput(text)
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitForIdle()
        }
        fun prompts() = src.calls.count { it.first == "agent.prompt" }

        send("deploy")
        assertEquals(1, stepUp.asked.size)
        assertEquals(0, prompts())
        compose.onNodeWithTag("composer").assertTextContains("deploy") // denied: back in the draft
        compose.onNodeWithTag("composer").performTextClearance()

        stepUp.allow = true
        send("deploy")
        compose.waitUntil(2_000) { prompts() == 1 }
        send("and test")
        compose.waitUntil(2_000) { prompts() == 2 }
        assertEquals(2, stepUp.asked.size) // same epoch: no second prompt

        compose.runOnIdle { lock.epoch.value++ } // the app locked and was unlocked
        send("again")
        compose.waitUntil(2_000) { prompts() == 3 }
        assertEquals(3, stepUp.asked.size)
    }

    @Test
    fun retryStepsUpAfterALock() {
        val src = FakeChatSource()
        src.onCmd = { m, _ -> if (m == "agent.prompt") throw java.io.IOException("down") else kotlinx.serialization.json.JsonObject(emptyMap()) }
        val stepUp = RecordingStepUp(allow = true)
        val lock = TestLock()
        compose.setContent { Gated(stepUp, lock) { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane) } }
        compose.onNodeWithTag("composer").performTextInput("ship it")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(2_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Resend")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, stepUp.asked.size)
        compose.runOnIdle { lock.epoch.value++ }
        stepUp.allow = false
        compose.onNodeWithText("Resend").performClick()
        compose.waitForIdle()
        assertEquals(2, stepUp.asked.size)
        assertEquals(1, src.calls.count { it.first == "agent.prompt" }) // denied: not re-sent
    }

    @Test
    fun approvalStepUpNamesTheChosenAnswer() {
        val screen = "────────────────────\n Bash command\n   ls\n Do you want to proceed?\n ❯ 1. Yes\n   2. No"
        val src = FakeChatSource()
        src.onCmd = { m, _ -> if (m == "agent.screen") buildJsonObject { put("text", screen) } else kotlinx.serialization.json.JsonObject(emptyMap()) }
        src.status.value = AgentStatus.BLOCKED
        val stepUp = RecordingStepUp(allow = true)
        compose.setContent { Gated(stepUp, TestLock()) { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane.copy(agentStatus = AgentStatus.BLOCKED)) } }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("approvalCard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1. Yes").hold(compose)
        compose.waitUntil(2_000) { src.calls.any { it.first == "agent.send_keys" } }
        assertEquals("Answer the agent" to "Answer: 1. Yes", stepUp.asked.single())
    }
}
