package dev.whistle.app.ui.broadcast

import dev.whistle.app.protocol.AgentStatus
import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.ui.chat.PromptOutbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastTest {
    @Test fun broadcastReportsPerTargetErrors() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String>>()
        val backend = object : PromptOutbox.Backend {
            override suspend fun cmd(machineId: String, method: String, params: JsonObject): JsonElement {
                val target = params["target"]!!.jsonPrimitive.content
                sent += target to params["text"]!!.jsonPrimitive.content
                if (target == "w1:p2") throw BridgeError("agent_blocked", "agent is waiting for approval")
                return JsonPrimitive("ok")
            }
            override suspend fun headSeq(machineId: String) = 0L
            override fun status(machineId: String, paneId: String) = flowOf(AgentStatus.WORKING) // never settles in time
        }
        val outbox = PromptOutbox(backgroundScope, backend)
        val bc = Broadcaster(outbox)
        val targets = listOf("w1:p1", "w1:p2")
        bc.send("m1", targets, "  run tests  ")
        assertEquals(setOf("w1:p1" to "run tests", "w1:p2" to "run tests"), sent.toSet())
        val results = bc.results("m1").first { r -> r.size == 2 && r.values.none { it is TargetResult.Sending } }
        assertEquals(TargetResult.Ok, results["w1:p1"])
        assertEquals(TargetResult.Failed("agent_blocked", "agent is waiting for approval"), results["w1:p2"])
        // Went through the outbox: a normal chat of that pane sees the same pending state.
        assertTrue(outbox.pending("m1", "w1:p2").value.single().state.toString().contains("Failed"))
        // nothing auto-retried
        assertEquals(2, sent.size)
    }

    @Test fun blankTextSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val backend = object : PromptOutbox.Backend {
            override suspend fun cmd(machineId: String, method: String, params: JsonObject): JsonElement = error("no")
            override suspend fun headSeq(machineId: String) = 0L
            override fun status(machineId: String, paneId: String) = emptyFlow<AgentStatus>()
        }
        val bc = Broadcaster(PromptOutbox(backgroundScope, backend))
        bc.send("m1", listOf("w1:p1"), "  ")
        assertTrue(bc.results("m1").first().isEmpty())
    }
}
