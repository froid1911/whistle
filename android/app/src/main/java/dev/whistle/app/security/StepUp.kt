package dev.whistle.app.security

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Re-authentication before an action that runs something on the owner's server (terminal input,
 * chat prompts, approval answers, git commit, agent start, broadcast). Runs [action] at once when
 * the last successful auth is fresh ([LockPolicy.isFresh]), otherwise after a successful prompt;
 * [onDenied] when the user cancels or fails. [detail] is the prompt's subtitle (what exactly is
 * being confirmed, e.g. "Answer: 1. Yes").
 */
interface StepUp {
    fun require(reason: String, detail: String? = null, onDenied: () -> Unit = {}, action: () -> Unit)

    companion object {
        /**
         * No gate. Only for previews and UI tests that render a screen without an activity: every
         * activity wraps its content in [LockGate], which provides the real one. Must be provided
         * explicitly ([LocalStepUp] has no default).
         */
        val None: StepUp = object : StepUp {
            override fun require(reason: String, detail: String?, onDenied: () -> Unit, action: () -> Unit) = action()
        }
    }
}

/** No silent default: a screen composed outside [LockGate] must fail loudly, not run ungated. */
val LocalStepUp = staticCompositionLocalOf<StepUp> {
    error("No LockGate: wrap the content in LockGate (tests and previews: provide StepUp.None)")
}

/**
 * The app lock as screens see it: [locked] now, and [epoch], bumped every time the app locks.
 * Authorisations ("this pane may type / send") are only valid within the epoch they were given in.
 */
interface LockStatus {
    val locked: StateFlow<Boolean>
    val epoch: StateFlow<Int>

    companion object {
        /** No app lock (screens rendered without [LockGate]: previews, UI tests). */
        val Unlocked: LockStatus = object : LockStatus {
            override val locked: StateFlow<Boolean> = MutableStateFlow(false)
            override val epoch: StateFlow<Int> = MutableStateFlow(0)
        }
    }
}

val LocalAppLock = staticCompositionLocalOf { LockStatus.Unlocked }

/**
 * "Step up once per lock epoch" for a pane's chat sends and retries: the first one after an unlock
 * asks ([StepUp.require] skips the prompt within the freshness window), later ones pass until the
 * app locks again. Nothing runs while locked, nor when the app locked while the prompt was open.
 */
class StepUpOnce(private val status: LockStatus) {
    private var authorizedEpoch: Int? = null

    fun run(stepUp: StepUp, reason: String, detail: String? = null, onDenied: () -> Unit = {}, action: () -> Unit) {
        if (status.locked.value) return onDenied()
        val epoch = status.epoch.value
        if (authorizedEpoch == epoch) return action()
        stepUp.require(reason, detail, onDenied) {
            if (status.locked.value || status.epoch.value != epoch) {
                onDenied()
            } else {
                authorizedEpoch = epoch
                action()
            }
        }
    }
}

/**
 * Terminal keystrokes before the session was authorised (in this lock epoch): the first one asks
 * for step-up; keys typed while the prompt is open are held (in order) and flushed once it
 * succeeds, dropped if not. While locked every key is dropped; a lock revokes the authorisation
 * and drops whatever was held (a prompt that succeeds after the lock flushes nothing).
 */
class InputGate(private val status: LockStatus = LockStatus.Unlocked) {
    private var authorizedEpoch: Int? = null
    private var askEpoch: Int? = null
    private val held = StringBuilder()

    val authorized: Boolean get() = !status.locked.value && authorizedEpoch == status.epoch.value

    /** What to do with [data]. */
    sealed interface Decision {
        /** Send it now. */
        data object Pass : Decision
        /** Held; start the step-up prompt. */
        data object Ask : Decision
        /** Held; a prompt is already open. */
        data object Wait : Decision
        /** Locked: thrown away. */
        data object Drop : Decision
    }

    fun offer(data: String): Decision {
        if (status.locked.value) {
            held.clear()
            askEpoch = null
            return Decision.Drop
        }
        val epoch = status.epoch.value
        if (authorizedEpoch == epoch) return Decision.Pass
        if (askEpoch != null && askEpoch != epoch) { // asked before a lock: stale
            held.clear()
            askEpoch = null
        }
        held.append(data)
        if (askEpoch != null) return Decision.Wait
        askEpoch = epoch
        return Decision.Ask
    }

    /** The prompt succeeded: everything held, to send now ("" when the app locked meanwhile). */
    fun grant(): String {
        val epoch = status.epoch.value
        val valid = !status.locked.value && askEpoch == epoch
        askEpoch = null
        val out = if (valid) held.toString() else ""
        held.clear()
        if (valid) authorizedEpoch = epoch
        return out
    }

    /** The prompt was cancelled: the held keys are dropped; the next key asks again. */
    fun deny() {
        askEpoch = null
        held.clear()
    }
}
