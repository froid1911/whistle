package dev.whistle.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.whistle.app.protocol.AgentStatus

/**
 * The mark on an agent's orb, by agent kind: claude ✳, omp π, codex ◆, others their first letter.
 * [Spokes] and [Diamond] are drawn (the characters fall back to emoji fonts on some phones).
 */
sealed interface AgentGlyph {
    /** Plain-text form (accessibility, tests). */
    val symbol: String

    data object Spokes : AgentGlyph { override val symbol = "✳" }
    data object Diamond : AgentGlyph { override val symbol = "◆" }
    data class Letter(override val symbol: String) : AgentGlyph

    companion object {
        fun of(kind: String?): AgentGlyph {
            val k = kind?.trim()?.lowercase().orEmpty()
            return when {
                k == "claude" || k.startsWith("claude") -> Spokes
                k == "omp" -> Letter("π")
                k == "codex" || k.startsWith("codex") -> Diamond
                k.isEmpty() -> Letter("?")
                else -> Letter(k.first().uppercase())
            }
        }
    }
}

/** Status → palette color: lamp = needs you, sky = working, heather = done, moss = idle/unknown. */
fun WhistleColors.status(s: AgentStatus): Color = when (s) {
    AgentStatus.BLOCKED -> lamp
    AgentStatus.WORKING -> sky
    AgentStatus.DONE -> heather
    AgentStatus.IDLE, AgentStatus.UNKNOWN -> moss
}

/** Sentence-case word for a status, as shown in sublines ("waiting for you", "working", …). */
fun statusPhrase(s: AgentStatus): String = when (s) {
    AgentStatus.BLOCKED -> "waiting for you"
    AgentStatus.WORKING -> "working"
    AgentStatus.DONE -> "done"
    AgentStatus.IDLE -> "idle"
    AgentStatus.UNKNOWN -> "quiet"
}
