package dev.whistle.app.ui.links

import dev.whistle.app.ui.NoLockGate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.geometry.Offset
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import dev.whistle.app.data.PaneUi
import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.PaneKind
import dev.whistle.app.protocol.Role
import dev.whistle.app.ui.chat.ChatScreen
import dev.whistle.app.ui.chat.FakeChatSource
import dev.whistle.app.ui.chat.MarkdownImages
import dev.whistle.app.ui.chat.PromptOutbox
import dev.whistle.app.ui.chat.msg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownSafetyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private val pane = PaneUi(
        id = "w1:p1", tabId = "t1", workspaceId = "w1", label = "", cwd = "/src", kind = PaneKind.AGENT,
        agent = "claude", agentStatus = AgentStatus.IDLE, sessionKey = "claude:abc", hasTranscript = true,
        x = 0, y = 0, w = 1, h = 1,
    )

    /** The message row is full width; its (short) link text starts at the left. */
    private fun SemanticsNodeInteraction.tapLinkStart() = performTouchInput { click(centerLeft + Offset(8f, 0f)) }

    private fun show(vararg texts: String) {
        val src = FakeChatSource()
        src.msgs.value = texts.mapIndexed { i, t -> msg(i + 1L, Role.ASSISTANT, t) }
        compose.setContent { NoLockGate { SafeLinks { ChatScreen(src, PromptOutbox(scope, src.backend).forPane("m1", pane.id), pane) } } }
    }

    @Test
    fun imageTransformerNeverLoadsRemoteImages() {
        // Guard: no network image loader configured, none on the classpath.
        assertTrue(MarkdownImages is NoOpImageTransformerImpl)
        listOf("coil3.ImageLoader", "coil.ImageLoader", "com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl", "com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl")
            .forEach { n -> assertTrue(n, runCatching { Class.forName(n) }.isFailure) }
        MockWebServer().use { server ->
            server.start()
            show("before ![pixel](${server.url("/pixel.png")}) after")
            compose.waitForIdle()
            Thread.sleep(200)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun lyingLinkShowsTheRealTargetAndAWarning() {
        show("[github.com](https://github.com.evil.io/login)")
        compose.onNodeWithText("github.com").tapLinkStart()
        compose.onNodeWithTag("linkSheet").assertIsDisplayed()
        compose.onNodeWithTag("linkHost").assertTextContains("https://github.com.evil.io")
        compose.onNodeWithTag("linkUrl").assertTextContains("https://github.com.evil.io/login")
        compose.onNodeWithText("Link text and target differ").assertIsDisplayed()
        compose.onNodeWithTag("linkOpen").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("linkSheet").fetchSemanticsNodes().size)
    }

    @Test
    fun nonWebLinkCanOnlyBeCopied() {
        show("[docs](intent://x#Intent;scheme=http;end)")
        compose.onNodeWithText("docs").tapLinkStart()
        compose.onNodeWithTag("linkSheet").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("linkOpen").fetchSemanticsNodes().size)
        compose.onNodeWithTag("linkCopy").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("linkMismatch").fetchSemanticsNodes().size)
    }
}
