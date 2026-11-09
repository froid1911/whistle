package dev.whistle.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.whistle.app.R

/** Names and screen titles. Static 600/700 instances of the variable font (opsz 24). */
val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
)

/** All body and UI text. */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
)

/** Terminal, commands, tool inputs only. */
val CommitMono = FontFamily(Font(R.font.commit_mono_regular, FontWeight.Normal))

