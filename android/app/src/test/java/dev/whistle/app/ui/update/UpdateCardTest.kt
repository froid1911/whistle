package dev.whistle.app.ui.update

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.whistle.app.ui.HerdrTheme
import dev.whistle.app.ui.links.LinkRequest
import dev.whistle.app.ui.links.LocalLinkSheet
import dev.whistle.app.update.FetchResult
import dev.whistle.app.update.LatestRelease
import dev.whistle.app.update.MemoryUpdateStore
import dev.whistle.app.update.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCardTest {
    @get:Rule val compose = createComposeRule()

    private val apk = "https://github.com/froid1911/whistle/releases/download/v0.7.0/whistle-0.7.0.apk"

    @Test fun downloadGoesThroughLinkSheetAndLaterHides() {
        val checker = UpdateChecker(MemoryUpdateStore(), "0.6.0", { 1L }) { FetchResult.Ok(LatestRelease("0.7.0", apk)) }
        runBlocking { checker.checkIfDue() }
        val opened = mutableListOf<LinkRequest>()
        compose.setContent {
            HerdrTheme { CompositionLocalProvider(LocalLinkSheet provides { opened += it }) { UpdateCard(checker) } }
        }
        compose.onNodeWithText("Whistle 0.7.0 is available").assertIsDisplayed()
        compose.onNodeWithTag("updateDownload").performClick()
        assertEquals(apk, opened.single().url)
        compose.onNodeWithTag("updateLater").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("updateCard").assertDoesNotExist()
    }
}
