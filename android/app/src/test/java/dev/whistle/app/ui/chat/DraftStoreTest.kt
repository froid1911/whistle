package dev.whistle.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftStoreTest {
    @Test fun keyedByMachineAndPane() {
        val d = DraftStore()
        d.set("m1", "p1", "a")
        d.set("m2", "p1", "b")
        assertEquals("a", d.get("m1", "p1"))
        assertEquals("b", d.get("m2", "p1"))
        assertEquals("", d.get("m1", "p2"))
    }

    @Test fun appendPutsLinesAndClearEmpties() {
        val d = DraftStore()
        d.append("m", "p", "one")
        d.append("m", "p", "two")
        assertEquals("one\ntwo", d.get("m", "p"))
        d.set("m", "p", "x\n")
        d.append("m", "p", "y")
        assertEquals("x\ny", d.get("m", "p"))
        d.clear("m", "p")
        assertEquals("", d.get("m", "p"))
    }

    @Test fun paneDraftObservesStore() {
        val d = DraftStore()
        val pd = PaneDraft(d, "m", "p")
        d.append("m", "p", "hi")
        assertEquals("hi", pd.text.value)
        pd.clear()
        assertEquals("", d.get("m", "p"))
    }
}

class DraftPersistenceTest {
    private class MapBacking(val map: MutableMap<Pair<String, String>, String> = HashMap()) : DraftBacking {
        override fun loadAll() = map.toMap()
        override fun save(machineId: String, paneId: String, text: String) {
            if (text.isEmpty()) map.remove(machineId to paneId) else map[machineId to paneId] = text
        }
    }

    @Test fun restoredByNewInstanceAfterFlush() {
        val b = MapBacking()
        val a = DraftStore(b)
        a.append("m", "p", "https://x")
        a.append("m", "p", "Image: /up/1.png")
        a.flush()
        assertEquals("https://x\nImage: /up/1.png", DraftStore(b).get("m", "p"))
    }

    @Test fun clearIsWrittenAtOnce() {
        val b = MapBacking()
        val a = DraftStore(b)
        a.set("m", "p", "x")
        a.flush()
        a.clear("m", "p")
        assertEquals("", DraftStore(b).get("m", "p"))
    }

    @Test fun takeAndClearReturnsAndEmpties() {
        val b = MapBacking()
        val a = DraftStore(b)
        a.set("m", "p", "hello")
        assertEquals("hello", a.takeAndClear("m", "p"))
        assertEquals("", a.get("m", "p"))
        assertEquals("", a.takeAndClear("m", "p"))
        assertEquals("", DraftStore(b).get("m", "p"))
    }

    @Test fun appendAfterSnapshotIsNotLostBySendOfLatest() {
        val a = DraftStore()
        a.set("m", "p", "typed")
        a.append("m", "p", "Image: /up/2.png") // lands before the composer recomposed
        assertEquals("typed\nImage: /up/2.png", a.takeAndClear("m", "p"))
    }
}
