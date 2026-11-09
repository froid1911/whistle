package dev.whistle.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType

/** Card surface: radius 20, padding 14, no shadow (elevation by surface color only). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhistleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    lamp: Boolean = false,
    color: Color = Whistle.colors.card,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Whistle.colors
    val glow = remember(colors, lamp) { colors.lamp.copy(alpha = if (colors.isDark) 0.20f else 0.16f) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(WhistleShapes.Card)
            .background(color)
            .then(
                if (lamp) Modifier.drawBehind {
                    // The lamp glow: a warm radial wash from the top-left corner.
                    drawRect(
                        Brush.radialGradient(
                            0f to glow, 0.6f to Color.Transparent,
                            center = Offset.Zero,
                            radius = maxOf(size.width, size.height) * 1.2f,
                        ),
                    )
                } else Modifier,
            )
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else Modifier,
            )
            .padding(contentPadding),
        content = content,
    )
}

/** Lamp-glow card: the one loud signal (needs you). */
@Composable
fun LampCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    WhistleCard(modifier, onClick = onClick, lamp = true, content = content)

enum class PillStyle { Lamp, Outline, Wool, Quiet }

/** Pill button (radius 999): lamp-filled for the answer, outlined for the alternatives. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Outline,
    enabled: Boolean = true,
) {
    val c = Whistle.colors
    val (bg, fg) = when (style) {
        PillStyle.Lamp -> c.lamp to c.onLamp
        PillStyle.Wool -> c.wool to c.night
        PillStyle.Outline -> Color.Transparent to c.wool
        PillStyle.Quiet -> c.cardHi to c.wool
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(WhistleShapes.Pill)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, c.line, WhistleShapes.Pill) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WhistleType.secondaryStrong,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small quiet chip (tool steps, soft keys): pill on the card color. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val c = Whistle.colors
    Box(
        modifier
            .clip(WhistleShapes.Pill)
            .background(if (selected) c.wool else c.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WhistleType.small,
            color = when {
                selected -> c.night
                color != Color.Unspecified -> color
                else -> c.sage
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row of chips that wraps to the screen width (no scrolling). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** The last [maxLines] lines of [text], blank tail trimmed: what an inset strip shows. */
fun tailLines(text: String, maxLines: Int): String =
    text.trimEnd().lines().dropWhile { it.isBlank() }.takeLast(maxLines).joinToString("\n")

/** Semantics: the line cap an inset strip is showing (for tests). */
val InsetLinesKey = SemanticsPropertyKey<Int>("InsetLines")
var SemanticsPropertyReceiver.insetLines by InsetLinesKey

/**
 * Inset terminal strip: Commit Mono on the inset color, the last [keepLines] lines, wrapped, at
 * most [maxLines] lines tall; taller text scrolls and starts at the bottom (the newest line).
 */
@Composable
fun InsetTerminal(text: String, modifier: Modifier = Modifier, maxLines: Int = 8, keepLines: Int = maxLines) {
    val c = Whistle.colors
    val shown = remember(text, keepLines) { tailLines(text, keepLines) }
    val lineHeight = with(LocalDensity.current) { WhistleType.mono.lineHeight.toDp() }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, shown, maxLines) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    Box(
        modifier
            .fillMaxWidth()
            .semantics { insetLines = maxLines }
            .clip(WhistleShapes.Inset)
            .background(c.inset)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .heightIn(max = lineHeight * maxLines)
            .verticalScroll(scroll),
    ) {
        Text(shown, style = WhistleType.mono, color = c.insetText, softWrap = true)
    }
}

/** One status line: a dot in [color] and a short sentence ("working"). */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = WhistleType.secondary, color = color)
    }
}

/**
 * Top bar of a pushed screen: back, optional leading (orb), title + subtitle, actions; draws
 * under the status bar (edge-to-edge) and pads itself.
 */
@Composable
fun WhistleTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Whistle.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.night)
            .statusBarsPadding()
            .heightIn(min = 60.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.wool) }
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (subtitle == null) WhistleType.heading else WhistleType.name,
                color = c.wool,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = WhistleType.secondary, color = c.sage, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** Quiet section heading (workspace label on the herd screen). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable BoxScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = WhistleType.secondaryStrong,
            color = Whistle.colors.sage,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(content = trailing)
    }
}

/** Round icon-sized surface (send button). */
@Composable
fun RoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Whistle.colors
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) c.wool else c.cardHi)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
