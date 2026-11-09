package dev.whistle.app.ui.pair

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.whistle.app.MainActivity
import dev.whistle.app.protocol.Machine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairDeepLinkTest {
    @get:Rule val compose = createComposeRule()

    private val link =
        "whistle://pair?v=1&u=100.64.0.10%3A8787&t=YYHQ7CZP3HHWAWWQO72E5EIULY&f=MkR9tVZeCyn4s6dBt2aCjTQQfhPzyXnLrICFTJuIP-g&n=myserver"

    private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test
    fun onlyWhistlePairViewLinksAreTaken() {
        assertEquals(link, MainActivity.pairLink(view(link)))
        assertEquals("WHISTLE://PAIR?v=1", MainActivity.pairLink(view("WHISTLE://PAIR?v=1")))
        assertNull(MainActivity.pairLink(view("whistle://open?x=1")))
        assertNull(MainActivity.pairLink(view("https://pair/?v=1")))
        assertNull(MainActivity.pairLink(Intent(Intent.ACTION_SEND, Uri.parse(link))))
        assertNull(MainActivity.pairLink(view("whistle://pair?v=1&n=" + "x".repeat(3000))))
        assertNull(MainActivity.pairLink(null))
    }

    @Test
    fun aDeepLinkOpensTheConfirmationFilledInAndNeverPairsByItself() {
        var paired = 0
        compose.setContent {
            PairScreen(
                onBack = {}, pair = { _, _ -> paired++; error("no") }, onOpenMachine = { _: Machine -> },
                defaultDeviceName = "Pixel", initialLink = link,
            )
        }
        compose.onNodeWithText("Pair with myserver").assertIsDisplayed()
        compose.onNodeWithText("100.64.0.10:8787").assertIsDisplayed()
        compose.onNodeWithTag("pairConfirm").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, paired)
    }

    @Test
    fun aMalformedDeepLinkShowsTheError() {
        compose.setContent {
            PairScreen(
                onBack = {}, pair = { _, _ -> error("no") }, onOpenMachine = { _: Machine -> },
                defaultDeviceName = "Pixel", initialLink = "whistle://pair?v=1&u=100.64.0.10%3A8787&t=SHORT",
            )
        }
        compose.onNodeWithText("Pairing link has no valid code").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("pairConfirm")).fetchSemanticsNodes().size)
    }
}
