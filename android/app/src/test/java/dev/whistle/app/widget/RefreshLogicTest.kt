package dev.whistle.app.widget

import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.test.runTest

private class FakeOps(var foreground: Boolean, val synced: Boolean, val fgAfter: Boolean = false) : RefreshOps {
    val log = mutableListOf<String>()
    override fun isForeground() = if (log.contains("await")) fgAfter || foreground else foreground
    override fun openClients() { log += "open" }
    override suspend fun awaitSynced(timeoutMs: Long): Boolean { log += "await"; return synced }
    override suspend fun publish(allSynced: Boolean) { log += "publish:$allSynced" }
    override fun closeClients() { log += "close" }
}

class RefreshLogicTest {
    @Test fun foregroundDoesNothing() = runTest {
        val o = FakeOps(foreground = true, synced = true)
        RefreshLogic.run(o)
        assertEquals(emptyList<String>(), o.log)
    }

    @Test fun backgroundConnectsWaitsPublishesCloses() = runTest {
        val o = FakeOps(false, true)
        RefreshLogic.run(o)
        assertEquals(listOf("open", "await", "publish:true", "close"), o.log)
    }

    @Test fun timeoutPublishesStaleAndStillCloses() = runTest {
        val o = FakeOps(false, false)
        RefreshLogic.run(o)
        assertEquals(listOf("open", "await", "publish:false", "close"), o.log)
    }

    @Test fun uiOpeningMidRefreshKeepsConnections() = runTest {
        val o = FakeOps(false, true, fgAfter = true)
        RefreshLogic.run(o)
        assertEquals(listOf("open", "await", "publish:true"), o.log)
    }
}

class OwnedClientsTest {
    @Test fun closesOnlyWhatItOpened() {
        val started = mutableListOf<String>(); val stopped = mutableListOf<String>()
        val c = OwnedClients({ listOf("a", "b", "c") }, { it == "b" }, { started += it }, { stopped += it })
        c.open(); c.close()
        assertEquals(listOf("a", "c"), started)
        assertEquals(listOf("a", "c"), stopped)
    }
}
