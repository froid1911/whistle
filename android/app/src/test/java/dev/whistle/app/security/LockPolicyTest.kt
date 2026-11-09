package dev.whistle.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    @Test
    fun foregroundLocksOnlyAfterTheTimeout() {
        val t = LockTimeout.FiveMinutes
        assertFalse(LockPolicy.shouldLockOnForeground(null, 10_000_000, t)) // never left
        assertFalse(LockPolicy.shouldLockOnForeground(1_000, 1_000 + 299_999, t))
        assertTrue(LockPolicy.shouldLockOnForeground(1_000, 1_000 + 300_000, t)) // ≥ 5 min
        assertTrue(LockPolicy.shouldLockOnForeground(1_000, 1_000 + 3_600_000, t))
        assertTrue(LockPolicy.shouldLockOnForeground(5_000, 4_000, t)) // clock went back: fail closed
    }

    @Test
    fun immediatelyLocksOnAnyReturn() {
        assertTrue(LockPolicy.shouldLockOnForeground(1_000, 1_000, LockTimeout.Immediately))
        assertTrue(LockPolicy.shouldLockOnForeground(1_000, 1_001, LockTimeout.Immediately))
    }

    @Test
    fun timeoutsAndDefault() {
        assertEquals(listOf(0L, 60_000L, 300_000L, 900_000L), LockTimeout.entries.map { it.ms })
        assertEquals(LockTimeout.FiveMinutes, LockTimeout.DEFAULT)
        assertEquals(LockTimeout.FiveMinutes, LockTimeout.fromName(null))
        assertEquals(LockTimeout.FiveMinutes, LockTimeout.fromName("bogus"))
        assertEquals(LockTimeout.OneMinute, LockTimeout.fromName("OneMinute"))
    }

    @Test
    fun stepUpFreshnessIsSixtySeconds() {
        assertFalse(LockPolicy.isFresh(null, 5_000))
        assertTrue(LockPolicy.isFresh(1_000, 1_000))
        assertTrue(LockPolicy.isFresh(1_000, 60_999))
        assertFalse(LockPolicy.isFresh(1_000, 61_000)) // older than 60 s: ask again
        assertFalse(LockPolicy.isFresh(5_000, 4_000)) // clock went back: ask
    }
}

class AppLockTest {
    private var now = 0L
    private val store = object : LockStore {
        var saved = LockSettings()
        override fun load() = saved
        override fun save(s: LockSettings) { saved = s }
    }
    private fun lock() = AppLock(store, { now })

    @Test
    fun coldStartIsLockedUntilAuthenticated() {
        val l = lock()
        assertTrue(l.locked.value)
        assertFalse(l.stepUpFresh())
        l.onAuthenticated()
        assertFalse(l.locked.value)
        assertTrue(l.stepUpFresh())
        now += 60_000
        assertFalse(l.stepUpFresh())
    }

    @Test
    fun backgroundLongerThanTimeoutLocks() {
        val l = lock()
        l.onAuthenticated()
        l.onBackground(); now += 4 * 60_000; l.onForeground()
        assertFalse(l.locked.value)
        l.onBackground(); now += 5 * 60_000; l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test
    fun timeoutSettingIsPersistedAndUsed() {
        val l = lock()
        l.setTimeout(LockTimeout.Immediately)
        assertEquals(LockTimeout.Immediately, store.saved.timeout)
        l.onAuthenticated()
        l.onBackground(); now += 1; l.onForeground()
        assertTrue(l.locked.value)
        assertEquals(LockTimeout.Immediately, lock().settings.value.timeout)
    }

    @Test
    fun theCredentialScreenDoesNotCountAsLeaving() {
        val l = lock()
        l.setTimeout(LockTimeout.Immediately)
        l.onAuthenticated()
        l.promptStarted() // PIN screen is another activity: the app "stops"
        l.onBackground(); now += 10_000; l.onForeground()
        assertFalse(l.locked.value)
    }

    @Test
    fun aPromptWhoseCallbacksNeverFireDoesNotDisableLocking() {
        val l = lock()
        l.setTimeout(LockTimeout.Immediately)
        l.onAuthenticated()
        l.promptStarted() // prompt shown, then the activity died: no callback ever comes
        l.onBackground(); now += 10_000; l.onForeground() // at most this one return is excused
        l.onBackground(); now += 10_000; l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test
    fun aLongAbsenceDuringAPromptStillLocks() {
        val l = lock()
        l.setTimeout(LockTimeout.OneMinute)
        l.onAuthenticated()
        l.promptStarted()
        l.onBackground(); now += AppLock.PROMPT_GRACE_MS; l.onForeground()
        assertTrue(l.locked.value)
    }

    @Test
    fun theLastActivityStoppingStartsTheTimeoutAtOnce() {
        val l = lock()
        l.setTimeout(LockTimeout.Immediately)
        l.onActivityStarted()
        l.onAuthenticated()
        // Rotation: stop + start with changing configurations: not "the background".
        l.onActivityStopped(changingConfigurations = true); now += 100; l.onActivityStarted()
        assertFalse(l.locked.value)
        // Share -> chat hand-off: the new activity starts before the old one stops.
        l.onActivityStarted(); l.onActivityStopped(false); now += 100
        assertFalse(l.locked.value)
        // Home: the last activity stops; back 100 ms later (ProcessLifecycleOwner would not even have noticed).
        l.onActivityStopped(false); now += 100
        l.onBackground() // the process backstop, later: keeps the earlier time
        l.onActivityStarted()
        assertTrue(l.locked.value)
    }

    @Test
    fun everyLockStartsANewEpoch() {
        val l = lock()
        l.onAuthenticated()
        val e = l.epoch.value
        l.lockNow()
        assertEquals(e + 1, l.epoch.value)
        l.lockNow() // already locked: same epoch
        assertEquals(e + 1, l.epoch.value)
    }

    @Test
    fun noScreenLockCannotBeEnforced() {
        val l = lock()
        l.deviceSecure = false
        assertTrue(l.stepUpFresh())
        l.onAuthenticated()
        l.lockNow()
        assertFalse(l.locked.value)
    }

    @Test
    fun hideInRecentsDefaultsOn() {
        assertTrue(LockSettings().hideInRecents)
        val l = lock()
        l.setHideInRecents(false)
        assertFalse(store.saved.hideInRecents)
        l.markNoLockWarned()
        assertTrue(store.saved.noLockWarned)
    }
}

class InputGateTest {
    @Test
    fun firstKeyAsksThenHeldKeysFlushInOrder() {
        val g = InputGate()
        assertEquals(InputGate.Decision.Ask, g.offer("l"))
        assertEquals(InputGate.Decision.Wait, g.offer("s"))
        assertEquals("ls", g.grant())
        assertEquals(InputGate.Decision.Pass, g.offer("\r"))
    }

    @Test
    fun deniedDropsKeysAndAsksAgain() {
        val g = InputGate()
        assertEquals(InputGate.Decision.Ask, g.offer("rm"))
        g.deny()
        assertFalse(g.authorized)
        assertEquals(InputGate.Decision.Ask, g.offer("x"))
        assertEquals("x", g.grant())
    }
}

private class FakeLock : LockStatus {
    override val locked = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val epoch = kotlinx.coroutines.flow.MutableStateFlow(1)
    fun lock() { locked.value = true; epoch.value++ }
    fun unlock() { locked.value = false }
}

/** Records prompts; [answer] decides them later. */
private class FakeStepUp : StepUp {
    val asked = mutableListOf<Pair<String, String?>>()
    private val pending = mutableListOf<Pair<() -> Unit, () -> Unit>>()
    override fun require(reason: String, detail: String?, onDenied: () -> Unit, action: () -> Unit) {
        asked += reason to detail
        pending += onDenied to action
    }
    fun answer(ok: Boolean) {
        val (deny, act) = pending.removeAt(0)
        if (ok) act() else deny()
    }
}

class LockedInputTest {
    @Test
    fun aLockRevokesTheTerminalAndDropsKeys() {
        val l = FakeLock()
        val g = InputGate(l)
        assertEquals(InputGate.Decision.Ask, g.offer("l"))
        assertEquals("l", g.grant())
        assertEquals(InputGate.Decision.Pass, g.offer("s"))
        l.lock()
        // Bluetooth keyboard / late IME commit while locked: dropped, never sent.
        assertEquals(InputGate.Decision.Drop, g.offer("rm -rf ~\r"))
        assertFalse(g.authorized)
        l.unlock()
        // New epoch: the first key asks again; nothing from the locked time is held.
        assertEquals(InputGate.Decision.Ask, g.offer("x"))
        assertEquals("x", g.grant())
    }

    @Test
    fun aPromptThatSucceedsAfterALockFlushesNothing() {
        val l = FakeLock()
        val g = InputGate(l)
        assertEquals(InputGate.Decision.Ask, g.offer("e"))
        assertEquals(InputGate.Decision.Wait, g.offer("vil"))
        l.lock(); l.unlock()
        assertEquals("", g.grant())
        assertFalse(g.authorized)
    }

    @Test
    fun chatSendsStepUpOncePerLockEpoch() {
        val l = FakeLock()
        val once = StepUpOnce(l)
        val stepUp = FakeStepUp()
        val sent = mutableListOf<String>()
        var denied = 0
        once.run(stepUp, "Send to the agent", onDenied = { denied++ }) { sent += "a" }
        stepUp.answer(false)
        assertEquals(listOf<String>(), sent)
        assertEquals(1, denied)
        once.run(stepUp, "Send to the agent") { sent += "b" }
        stepUp.answer(true)
        once.run(stepUp, "Send to the agent") { sent += "c" } // same epoch: no prompt
        assertEquals(listOf("b", "c"), sent)
        assertEquals(2, stepUp.asked.size)
        l.lock()
        once.run(stepUp, "Send to the agent", onDenied = { denied++ }) { sent += "locked" }
        assertEquals(2, denied)
        l.unlock()
        once.run(stepUp, "Retry") { sent += "d" } // new epoch: asks again
        assertEquals(3, stepUp.asked.size)
        l.lock(); l.unlock()
        stepUp.answer(true) // the prompt was from before the lock
        assertEquals(listOf("b", "c"), sent)
    }
}
