package dev.whistle.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.whistle.app.protocol.Machine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Machines saved by 0.3.x (no pairing fields) load unchanged and unpaired; pairing fields round-trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MachineStoreMigrationTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)

    @Test
    fun oldRowsLoadUnpaired() {
        prefs.edit().putString("machines", """[{"id":"myserver","name":"myserver","baseUrl":"http://100.64.0.10:8787"},{"id":"x","name":"box","baseUrl":"http://100.64.0.2:8787"}]""").commit()
        val s = MachineStore(prefs)
        assertEquals(listOf(Machine.DEFAULT, Machine("x", "box", "http://100.64.0.2:8787")), s.machines.value)
        assertFalse(s.machines.value.any { it.paired })
    }

    @Test
    fun pairingRoundTrips() {
        val s = MachineStore(prefs)
        val m = Machine.DEFAULT.copy(deviceId = "d1", keyAlias = "a", bridgeKey = "K", deviceName = "Pixel 8a")
        s.upsert(m)
        val again = MachineStore(prefs).get("myserver")!!
        assertEquals(m, again)
        assertTrue(again.paired)
        assertFalse(again.unpaired().paired)
    }
}
