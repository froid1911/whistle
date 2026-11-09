package dev.whistle.app.security

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** User settings of the app lock. */
data class LockSettings(
    val timeout: LockTimeout = LockTimeout.DEFAULT,
    /** FLAG_SECURE all the time (recents thumbnail and screenshots blank), not only while locked. */
    val hideInRecents: Boolean = true,
    /** The "this device has no screen lock" warning was shown once. */
    val noLockWarned: Boolean = false,
)

/** Where [AppLock] keeps its settings (SharedPreferences in the app, a map in tests). */
interface LockStore {
    fun load(): LockSettings
    fun save(s: LockSettings)
}

class PrefsLockStore(private val prefs: SharedPreferences) : LockStore {
    override fun load() = LockSettings(
        timeout = LockTimeout.fromName(prefs.getString(KEY_TIMEOUT, null)),
        hideInRecents = prefs.getBoolean(KEY_HIDE, true),
        noLockWarned = prefs.getBoolean(KEY_WARNED, false),
    )

    override fun save(s: LockSettings) {
        prefs.edit()
            .putString(KEY_TIMEOUT, s.timeout.name)
            .putBoolean(KEY_HIDE, s.hideInRecents)
            .putBoolean(KEY_WARNED, s.noLockWarned)
            .apply()
    }

    private companion object {
        const val KEY_TIMEOUT = "lock_timeout"
        const val KEY_HIDE = "hide_in_recents"
        const val KEY_WARNED = "no_lock_warned"
    }
}

/**
 * Process-wide app lock state. Starts LOCKED (cold start). Going to the background is reported by
 * the activities themselves ([onActivityStarted]/[onActivityStopped], so "Immediately" really is
 * immediate) with ProcessLifecycleOwner as a backstop ([onBackground]/[onForeground]);
 * [onAuthenticated] comes from a successful BiometricPrompt. Decisions are [LockPolicy]'s.
 * [clock] must be monotonic (elapsedRealtime).
 */
class AppLock(private val store: LockStore, private val clock: () -> Long) : LockStatus {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<LockSettings> = _settings.asStateFlow()

    private val _locked = MutableStateFlow(true)
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Bumped on every lock; random start so state saved by an earlier process never matches. */
    private val _epoch = MutableStateFlow(Random.nextInt(1, Int.MAX_VALUE / 2))
    override val epoch: StateFlow<Int> = _epoch.asStateFlow()

    @Volatile private var backgroundedAt: Long? = null
    @Volatile private var lastAuthAt: Long? = null
    @Volatile private var startedActivities = 0

    /**
     * When the open prompt was shown (null = none). The device-credential screen (API 29) is another
     * activity, so the app can go "to the background" while the user types the PIN. Not sticky:
     * it excuses at most the next return to the foreground, and only a short one
     * ([PROMPT_GRACE_MS]); a prompt whose callbacks never come can't switch background locking off.
     */
    @Volatile private var promptAt: Long? = null

    /**
     * false = the device has no screen lock at all: nothing to authenticate against, so the lock
     * can't be enforced (the UI warns once and lets the user in). Set by the UI from KeyguardManager.
     */
    @Volatile var deviceSecure: Boolean = true

    /** An activity of the app started (MainActivity/ShareActivity onStart). */
    fun onActivityStarted() {
        startedActivities++
        if (startedActivities == 1) onForeground()
    }

    /** An activity stopped; the last one going away (not for a rotation) is "the background". */
    fun onActivityStopped(changingConfigurations: Boolean) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !changingConfigurations) onBackground()
    }

    /** Always records the time (the earliest report wins: activity first, process backstop later). */
    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = clock()
    }

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val now = clock()
        val prompt = promptAt
        promptAt = null
        val auth = lastAuthAt
        // Authenticated while "away" (the credential screen): no lock.
        if (auth != null && auth > since) return
        // Away only for an open prompt's credential screen: once, briefly.
        if (prompt != null && prompt <= since && now - since in 0 until PROMPT_GRACE_MS) return
        if (LockPolicy.shouldLockOnForeground(since, now, _settings.value.timeout)) lock()
    }

    /** A step-up / unlock prompt is about to show. */
    fun promptStarted() {
        promptAt = clock()
    }

    /** The prompt finished (any callback) or its activity was destroyed. */
    fun promptEnded() {
        promptAt = null
    }

    fun onAuthenticated() {
        promptAt = null
        lastAuthAt = clock()
        _locked.value = false
    }

    /** Can the next sensitive action skip the step-up prompt? */
    fun stepUpFresh(): Boolean = !deviceSecure || LockPolicy.isFresh(lastAuthAt, clock())

    fun lockNow() {
        if (deviceSecure) lock()
    }

    private fun lock() {
        if (!_locked.value) _epoch.value = _epoch.value + 1
        _locked.value = true
    }

    fun setTimeout(t: LockTimeout) = edit { it.copy(timeout = t) }
    fun setHideInRecents(on: Boolean) = edit { it.copy(hideInRecents = on) }
    fun markNoLockWarned() = edit { it.copy(noLockWarned = true) }

    private fun edit(f: (LockSettings) -> LockSettings) {
        _settings.update(f)
        store.save(_settings.value)
    }

    companion object {
        /** Longest PIN entry on the credential screen that does not count as leaving. */
        const val PROMPT_GRACE_MS = 2 * 60_000L
    }
}
