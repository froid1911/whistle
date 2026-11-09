package dev.whistle.app.security

import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import dev.whistle.app.R
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType

/**
 * One activity's side of the app lock: the BiometricPrompt (biometric or device PIN/pattern),
 * FLAG_SECURE, and [StepUp]. Create it in onCreate (before setContent) and call [onStart].
 */
class ActivityLock(val activity: FragmentActivity, val lock: AppLock) : StepUp {
    init {
        // Before the first frame: the cold start is locked, nothing may reach the recents thumbnail.
        applySecureFlag(true)
    }

    fun onStart() {
        lock.deviceSecure = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true
        lock.onActivityStarted()
    }

    /** From the activity's onStop: the last activity leaving (not rotating) starts the timeout now. */
    fun onStop() = lock.onActivityStopped(activity.isChangingConfigurations)

    /** From onDestroy: an open prompt's callbacks may never come now. */
    fun onDestroy() = lock.promptEnded()

    fun applySecureFlag(secure: Boolean) {
        val w = activity.window ?: return
        if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Biometric strong or the device credential; API 29 cannot combine STRONG with the credential. */
    private val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Shows the system prompt; [onSuccess] after [AppLock.onAuthenticated]. */
    fun authenticate(title: String, subtitle: String?, onSuccess: () -> Unit, onFail: (String?) -> Unit) {
        if (!lock.deviceSecure) {
            // No screen lock: nothing to check against (the gate warned once).
            lock.onAuthenticated()
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lock.onAuthenticated()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                lock.promptEnded()
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                onFail(if (cancelled) null else errString.toString())
            }
            // onAuthenticationFailed (one bad finger) keeps the prompt open: nothing to do.
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(allowed)
            .build()
        lock.promptStarted()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            lock.promptEnded()
            onFail(e.message ?: "Can't show the unlock prompt")
        }
    }

    override fun require(reason: String, detail: String?, onDenied: () -> Unit, action: () -> Unit) {
        if (lock.locked.value) return onDenied() // nothing runs behind the lock screen
        if (lock.stepUpFresh()) action()
        else authenticate(reason, detail ?: "Confirm it's you", onSuccess = action, onFail = { onDenied() })
    }
}

/**
 * Wraps an activity's content: while [AppLock.locked], only the opaque lock screen is composed. The
 * content leaves composition, and with it every dialog, bottom sheet and menu (separate windows that
 * an overlay could not cover), the terminal WebView and its focus. Its saveable state (navigation
 * back stack, scroll positions, saveable text) is kept in a [SaveableStateHolder] and comes back on
 * unlock; drafts live in DraftStore. FLAG_SECURE keeps the window out of the recents thumbnail.
 * Deep links (widget, tile, share, pairing) are held by the activity and handled after unlocking.
 * Provides [LocalStepUp] and [LocalAppLock].
 */
@Composable
fun LockGate(gate: ActivityLock, content: @Composable () -> Unit) {
    val lock = gate.lock
    val locked by lock.locked.collectAsStateWithLifecycle()
    val settings by lock.settings.collectAsStateWithLifecycle()
    // Read during composition (not inside the effect) so a settings change recomposes.
    val secureWindow = locked || settings.hideInRecents
    SideEffect { gate.applySecureFlag(secureWindow) }

    var warnNoLock by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = gate.activity
    val unlock = {
        error = null
        gate.authenticate("Unlock Whistle", null, onSuccess = {}, onFail = { error = it })
    }
    LaunchedEffect(locked) {
        if (!locked) return@LaunchedEffect
        focus.clearFocus(force = true)
        keyboard?.hide()
        if (!lock.deviceSecure) {
            // Can't enforce without a screen lock: let the user in, warn once.
            lock.onAuthenticated()
            if (!lock.settings.value.noLockWarned) warnNoLock = true
            return@LaunchedEffect
        }
        activity.lifecycle.withResumed { }
        unlock() // ask right away; the button stays for a cancelled prompt
    }

    val saved = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalStepUp provides gate, LocalAppLock provides lock) {
        Box(Modifier.fillMaxSize()) {
            if (locked) LockScreen(error, onUnlock = unlock)
            else saved.SaveableStateProvider("content") { content() }
        }
    }
    if (warnNoLock) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Whistle.colors.card,
            title = { Text("No screen lock", style = WhistleType.heading, color = Whistle.colors.wool) },
            text = {
                Text(
                    "This phone has no PIN, pattern or fingerprint, so Whistle can't lock. Anyone holding it " +
                        "unlocked can run commands on your machines. Set a screen lock in Android settings.",
                    style = WhistleType.body,
                    color = Whistle.colors.sage,
                )
            },
            confirmButton = {
                TextButton(onClick = { lock.markNoLockWarned(); warnNoLock = false }) { Text("Got it", color = Whistle.colors.wool) }
            },
        )
    }
}

/** "Whistle is locked": night background, the whistle, one Unlock button. Swallows every touch. */
@Composable
fun LockScreen(error: String?, onUnlock: () -> Unit) {
    val c = Whistle.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.night)
            // Nothing below may be touched while locked.
            .pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .focusRequester(focus)
            .focusable()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp)
            .testTag("lockScreen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Icon(painterResource(R.drawable.ic_whistle_tile), contentDescription = null, tint = c.sage, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text("Whistle is locked", style = WhistleType.title, color = c.wool, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Unlock to see your herd.", style = WhistleType.body, color = c.sage, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PillButton("Unlock", onClick = onUnlock, style = PillStyle.Wool, modifier = Modifier.testTag("unlockButton"))
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = WhistleType.secondary, color = c.ember, textAlign = TextAlign.Center)
            }
        }
    }
}
