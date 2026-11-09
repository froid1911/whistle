package dev.whistle.app.ui

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.ui.theme.ambientMotionAllowed
import dev.whistle.app.ui.theme.animatorScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReducedMotionTest {
    @Test
    fun readsAnimatorDurationScale() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(ambientMotionAllowed(animatorScale(ctx))) // default 1
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(0f, animatorScale(ctx))
        assertFalse(ambientMotionAllowed(animatorScale(ctx)))
    }
}
