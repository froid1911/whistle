package dev.whistle.app.security

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.robolectric.Robolectric
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        // Not in the app manifest, so not via ActivityScenario.
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    }

    private var now = 0L
    private val store = object : LockStore {
        var saved = LockSettings()
        override fun load() = saved
        override fun save(s: LockSettings) { saved = s }
    }

    private fun secureFlag() =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun gate(secure: Boolean): ActivityLock {
        val lock = AppLock(store, { now })
        val g = ActivityLock(activity, lock)
        lock.deviceSecure = secure
        return g
    }

    @Test
    fun lockedHidesContentUntilAuthenticated() {
        // A real prompt can't run here: the gate stays locked, the content is not reachable.
        val g = gate(secure = true)
        store.saved = LockSettings(hideInRecents = false)
        g.lock.setHideInRecents(false)
        activity.setContent { LockGate(g) { Text("secret herd") } }
        compose.onNodeWithTag("lockScreen").assertIsDisplayed()
        compose.onNodeWithText("Whistle is locked").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("secret herd")).fetchSemanticsNodes().size)
        assertTrue(secureFlag()) // recents thumbnail blank while locked

        compose.runOnIdle { g.lock.onAuthenticated() }
        compose.onNodeWithText("secret herd").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("lockScreen")).fetchSemanticsNodes().size)
        assertFalse(secureFlag()) // unlocked and "hide in recents" off

        compose.runOnIdle { g.lock.setHideInRecents(true) }
        compose.waitForIdle()
        assertTrue(secureFlag())
    }

    @Test
    fun noScreenLockWarnsOnceAndLetsTheUserIn() {
        val g = gate(secure = false)
        activity.setContent { LockGate(g) { Text("secret herd") } }
        compose.onNodeWithText("No screen lock").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("secret herd").assertIsDisplayed()
        assertTrue(store.saved.noLockWarned)
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun lockTearsDownDialogsSheetsAndMenusButKeepsTheScreen() {
        val g = gate(secure = true)
        g.lock.onAuthenticated()
        activity.setContent {
            LockGate(g) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "a") {
                    composable("a") { Button(onClick = { nav.navigate("b") }) { Text("go b") } }
                    composable("b") {
                        var draft by rememberSaveable { mutableStateOf("") }
                        var open by remember { mutableStateOf(false) }
                        Column {
                            Text("screen b")
                            TextField(draft, { draft = it }, Modifier.testTag("draft"))
                            Button(onClick = { open = true }) { Text("open all") }
                            Box { DropdownMenu(open, { open = false }) { DropdownMenuItem(text = { Text("menu item") }, onClick = {}) } }
                        }
                        if (open) {
                            AlertDialog(
                                onDismissRequest = { open = false },
                                confirmButton = { TextButton(onClick = {}) { Text("Close workspace") } },
                                text = { Text("dialog body") },
                            )
                            ModalBottomSheet(onDismissRequest = { open = false }) { Text("sheet body") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("go b").performClick()
        compose.onNodeWithTag("draft").performTextInput("half typed")
        compose.onNodeWithText("open all").performClick()
        compose.waitForIdle()
        assertEquals(1, count("dialog body"))
        assertEquals(1, count("sheet body"))
        assertEquals(1, count("menu item"))

        compose.runOnIdle { g.lock.lockNow() }
        compose.waitForIdle()
        // Separate windows are gone too: nothing readable or clickable above the lock screen.
        listOf("dialog body", "Close workspace", "sheet body", "menu item", "screen b").forEach { assertEquals(it, 0, count(it)) }
        compose.onNodeWithTag("lockScreen").assertIsDisplayed()

        compose.runOnIdle { g.lock.onAuthenticated() }
        compose.waitForIdle()
        compose.onNodeWithText("screen b").assertIsDisplayed() // back stack kept
        assertEquals(1, count("half typed")) // saveable draft kept
        listOf("dialog body", "sheet body", "menu item").forEach { assertEquals(it, 0, count(it)) }
    }

    @Test
    fun stepUpHasNoSilentDefault() {
        val e = runCatching {
            activity.setContent { LocalStepUp.current }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("$e", generateSequence(e) { it.cause }.any { it.message.orEmpty().contains("No LockGate") })
    }
}
