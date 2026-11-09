package dev.whistle.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.whistle.app.security.LocalStepUp
import dev.whistle.app.security.StepUp

/** Screens rendered without an activity's LockGate: step-up explicitly off ([StepUp.None]). */
@Composable
fun NoLockGate(stepUp: StepUp = StepUp.None, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalStepUp provides stepUp, content = content)
