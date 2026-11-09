package dev.whistle.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.whistle.app.R

/** The spec's type scale (sp): title 28/32 · heading 20/26 · name 16/20 · body 15/22 · secondary 13/18 · small 12/16. */
object WhistleType {
    val title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val heading = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val name = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val secondary = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val secondaryStrong = secondary.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = CommitMono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
}

/** M3 roles mapped onto the scale, so untouched Material components use the brand fonts too. */
val WhistleTypography = Typography(
    displaySmall = WhistleType.title,
    headlineLarge = WhistleType.title,
    headlineMedium = WhistleType.title.copy(fontSize = 24.sp, lineHeight = 28.sp),
    headlineSmall = WhistleType.heading,
    titleLarge = WhistleType.heading,
    titleMedium = WhistleType.name,
    titleSmall = WhistleType.bodyStrong,
    bodyLarge = WhistleType.body,
    bodyMedium = WhistleType.body,
    bodySmall = WhistleType.secondary,
    labelLarge = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = WhistleType.small,
)
