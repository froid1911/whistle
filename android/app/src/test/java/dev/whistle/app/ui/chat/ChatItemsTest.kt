package dev.whistle.app.ui.chat

import dev.whistle.app.protocol.Role
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatItemsTest {
    private fun call(seq: Long, tool: String, key: String, value: String) =
        msg(seq, Role.TOOL_CALL, callId = "c$seq", tool = tool, input = buildJsonObject { put(key, value) })

    @Test
    fun foldsConsecutiveToolStepsOfATurn() {
        val rows = groupMessages(
            listOf(
                msg(1, Role.USER, "go"),
                msg(2, Role.ASSISTANT, "on it"),
                call(3, "Read", "file_path", "/a/SKILL.md"),
                msg(4, Role.TOOL_RESULT, "ok", callId = "c3"),
                call(5, "Grep", "pattern", "schema"),
                msg(6, Role.ASSISTANT, "found it"),
                call(7, "Bash", "command", "./gradlew -q :app:testDebugUnitTest"),
                msg(8, Role.USER, "thanks"),
                call(9, "Read", "file_path", "/b/x.kt"),
            ),
        )
        val items = foldTools(rows)
        assertEquals(
            listOf("Row", "Row", "Tools2", "Row", "Tools1", "Row", "Tools1"),
            items.map { if (it is ChatItem.Tools) "Tools${it.steps.size}" else "Row" },
        )
        assertEquals("tools:m3", items[2].key) // keyed by its first step: appends keep the item
        assertEquals(listOf("read SKILL.md", "grep schema"), chipLabels((items[2] as ChatItem.Tools).steps))
        assertEquals("bash ./gradlew -q :app…", chipLabel((items[4] as ChatItem.Tools).steps.single()))
    }

    @Test
    fun moreThanThreeStepsSummedUp() {
        val steps = (1L..5L).map { ChatRow.Tool(call(it, "Read", "file_path", "/f$it"), null, "t", it == 1L) }
        assertEquals(listOf("read f1", "read f2", "read f3", "+2"), chipLabels(steps))
    }
}
