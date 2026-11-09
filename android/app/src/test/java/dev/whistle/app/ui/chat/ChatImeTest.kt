package dev.whistle.app.ui.chat

import dev.whistle.app.ui.NoLockGate

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.ui.LocalImeVisibleOverride
import dev.whistle.app.ui.components.InsetLinesKey
import dev.whistle.app.voice.SpeechEngine
import dev.whistle.app.voice.SpeechErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatImeTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private val pane = PaneUi(
        id = "w1:p1", tabId = "t1", workspaceId = "w1", label = "", cwd = "/src", kind = PaneKind.AGENT,
        agent = "omp", agentStatus = AgentStatus.WORKING, sessionKey = "omp:abc", hasTranscript = true,
        x = 0, y = 0, w = 1, h = 1,
    )

    private class FakeEngine : SpeechEngine {
        var listener: SpeechEngine.Listener? = null
        var starts = 0
        var stops = 0
        override val available = true
        override fun start(listener: SpeechEngine.Listener) { starts++; this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private fun lines(n: Int) = SemanticsMatcher.expectValue(InsetLinesKey, n)

    @Test
    fun liveStripCapsAt3WithKeyboard8Without12Expanded() {
        val src = FakeChatSource()
        src.status.value = AgentStatus.WORKING
        src.liveText.value = (1..30).joinToString("\n") { "line $it " + "x".repeat(it * 4) }
        var ime by mutableStateOf(false)
        compose.setContent { NoLockGate {
            CompositionLocalProvider(LocalImeVisibleOverride provides ime) {
                ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane)
            }
        } }
        compose.onNodeWithTag(LIVE_STRIP_TAG).assert(lines(8))
        ime = true
        compose.waitForIdle()
        compose.onNodeWithTag(LIVE_STRIP_TAG).assert(lines(3))
        // Height follows the cap: 3 mono lines (17sp) + 2×10dp padding, not the whole text.
        val h = compose.onNodeWithTag(LIVE_STRIP_TAG).fetchSemanticsNode().size.height
        val density = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density
        assertEquals((3 * 17 + 20) * density, h.toFloat(), 2 * density)
        compose.onNodeWithTag(LIVE_STRIP_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LIVE_STRIP_TAG).assert(lines(12))
        compose.onNodeWithTag(LIVE_STRIP_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LIVE_STRIP_TAG).assert(lines(3))
    }

    @Test
    fun tapOnMicHintsAndNeverStartsTheRecognizer() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val src = FakeChatSource()
        val engine = FakeEngine()
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane, speech = { engine }) } }
        compose.onNodeWithTag("mic").performTouchInput { down(center); advanceEventTime(80); up() }
        compose.waitForIdle()
        compose.onNodeWithTag(MIC_HINT_TAG).assertIsDisplayed()
        assertEquals(0, engine.starts)
    }

    @Test
    fun holdShowsRecordingBarAndReleaseFinishesIntoDraft() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val src = FakeChatSource()
        val engine = FakeEngine()
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane, speech = { engine }) } }
        compose.onNodeWithTag("mic").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1, engine.starts)
        compose.onNodeWithTag(RECORDING_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("‹ Slide to cancel").assertIsDisplayed()
        engine.listener!!.onPartial("hello wor")
        compose.waitForIdle()
        compose.onNodeWithText("hello wor").assertIsDisplayed()
        compose.onNodeWithTag("mic").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, engine.stops)
        compose.onNodeWithText("Finishing…").assertIsDisplayed()
        engine.listener!!.onFinal("hello world")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(RECORDING_BAR_TAG).fetchSemanticsNodes().size)
        compose.onNodeWithText("hello world").assertIsDisplayed() // in the draft, not sent
        // Cursor at the end of the dictated text, field focused to keep typing.
        compose.onNodeWithTag("composer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange("hello world".length)))
            .assertIsFocused()
        assertEquals(0, src.calls.count { it.first == "agent.prompt" })
    }

    @Test
    fun shortHoldClientErrorIsSilent() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val src = FakeChatSource()
        val engine = FakeEngine()
        compose.setContent { NoLockGate { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane, speech = { engine }) } }
        compose.onNodeWithTag("mic").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("mic").performTouchInput { up() }
        compose.waitForIdle()
        engine.listener!!.onError("Speech recognition failed (code 5).", SpeechErrorKind.Client)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(RECORDING_BAR_TAG).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Speech recognition failed (code 5).").fetchSemanticsNodes().size)
    }
}
