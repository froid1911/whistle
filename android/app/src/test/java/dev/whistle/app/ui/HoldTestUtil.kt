package dev.whistle.app.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTouchInput

/** Press-and-hold an answer pill past the hold time, then let go (approval answers need a hold). */
fun SemanticsNodeInteraction.hold(compose: ComposeTestRule, ms: Long = 800) {
    performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(ms)
    // An answered chat card leaves the tree as soon as the keys went out: then there is nothing to lift from.
    runCatching { performTouchInput { up() } }
    compose.waitForIdle()
}
