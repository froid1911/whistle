package dev.whistle.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Ambient motion is allowed unless animations are off (animator duration scale 0, "Remove animations"). */
fun ambientMotionAllowed(animatorScale: Float): Boolean = animatorScale > 0f

/** The system animator duration scale (1 = default; 0 = animations off / reduced motion). */
fun animatorScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
        .getOrDefault(1f)

/** Override for tests/screenshots; null = read the system setting. */
val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/** True when the one ambient animation (the lamp ripple) must not run. */
@Composable
fun rememberReducedMotion(): Boolean {
    LocalReducedMotion.current?.let { return it }
    val ctx = LocalContext.current
    return remember(ctx) { !ambientMotionAllowed(animatorScale(ctx)) }
}
