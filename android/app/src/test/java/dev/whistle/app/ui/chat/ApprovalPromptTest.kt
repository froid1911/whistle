package dev.whistle.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPromptTest {
    @Test
    fun fullPromptBetweenTheRuleAndTheOptions() {
        val screen = """
            ⏺ I'll clean the build.
            1. first a numbered list in the reply
            2. that is not the dialog
            ────────────────────────────────────────
             Bash command

               rm -rf build && ./gradlew assembleDebug --no-daemon --stacktrace --info --warning-mode all
               Clean and rebuild

             Do you want to proceed?
             ❯ 1. Yes
               2. Yes, and don't ask again for rm commands
               3. No, and tell Claude what to do differently (esc)

             Esc to cancel · Tab to add instructions
        """.trimIndent()
        val expected = listOf(
            "Bash command",
            "",
            "  rm -rf build && ./gradlew assembleDebug --no-daemon --stacktrace --info --warning-mode all",
            "  Clean and rebuild",
            "",
            "Do you want to proceed?",
        ).joinToString("\n")
        assertEquals(expected, approvalPrompt(screen))
        // Not truncated like the old three-line question.
        assertTrue(approvalPrompt(screen).contains("--warning-mode all"))
        assertEquals(listOf("rm -rf"), DestructiveCommands.find(approvalPrompt(screen)))
    }

    @Test
    fun boxBordersStripped() {
        val screen = listOf(
            "╭──────────────────────────────╮",
            "│ Edit file                    │",
            "│   src/main.rs                │",
            "│ Do you want to make this edit? │",
            "│ ❯ 1. Yes                     │",
            "│   2. No                      │",
            "╰──────────────────────────────╯",
        ).joinToString("\n")
        assertEquals("Edit file\n  src/main.rs\nDo you want to make this edit?", approvalPrompt(screen))
    }

    @Test
    fun withoutOptionsTheBottomOfTheScreen() {
        assertEquals("foo\n\nPress enter to continue", approvalPrompt("foo\n\n\n\nPress enter to continue\n"))
        assertEquals(3, approvalPrompt((1..100).joinToString("\n") { "l$it" }, maxLines = 3).lines().size)
    }
}
