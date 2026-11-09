package dev.whistle.app.security

/** How long Whistle may sit in the background before it locks again. */
enum class LockTimeout(val ms: Long, val label: String) {
    Immediately(0L, "Immediately"),
    OneMinute(60_000L, "After 1 minute"),
    FiveMinutes(5 * 60_000L, "After 5 minutes"),
    FifteenMinutes(15 * 60_000L, "After 15 minutes");

    companion object {
        val DEFAULT = FiveMinutes
        fun fromName(name: String?): LockTimeout = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The app lock's decisions, pure (times are monotonic millis, e.g. `elapsedRealtime`).
 *
 * - Cold start is always locked (the holder starts locked; nothing to decide here).
 * - Back from the background: locked when it stayed away for at least the timeout.
 * - Step-up (terminal input, approvals, commit, agent start, broadcast): re-prompt unless the
 *   last successful authentication is younger than [STEP_UP_WINDOW_MS].
 */
object LockPolicy {
    const val STEP_UP_WINDOW_MS = 60_000L

    /**
     * Lock when coming back to the foreground? [backgroundedAt] null = never went to the
     * background (e.g. a second activity started while the app is visible): no.
     */
    fun shouldLockOnForeground(backgroundedAt: Long?, now: Long, timeout: LockTimeout): Boolean {
        if (backgroundedAt == null) return false
        // A clock going backwards (should not happen with elapsedRealtime) locks: fail closed.
        val away = now - backgroundedAt
        return away < 0 || away >= timeout.ms
    }

    /** Is the last successful auth recent enough to skip a step-up prompt? */
    fun isFresh(lastAuthAt: Long?, now: Long, windowMs: Long = STEP_UP_WINDOW_MS): Boolean {
        if (lastAuthAt == null) return false
        val age = now - lastAuthAt
        return age in 0 until windowMs
    }
}
