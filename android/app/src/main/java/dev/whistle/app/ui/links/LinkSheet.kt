package dev.whistle.app.ui.links

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleShapes
import dev.whistle.app.ui.theme.WhistleType
import kotlinx.coroutines.launch

/** A tapped link: its destination and the visible text(s) that pointed at it (may be empty). */
data class LinkRequest(val url: String, val texts: List<String> = emptyList())

/** Shows the link sheet. Default: ignore (a link outside [SafeLinks] never opens on its own). */
val LocalLinkSheet = staticCompositionLocalOf<(LinkRequest) -> Unit> { {} }

/**
 * Every link tap below goes through the sheet, never straight to the browser: [LocalUriHandler]
 * is replaced (the Markdown renderer opens links through it) and [LocalLinkSheet] lets message
 * views pass the link text along.
 */
@Composable
fun SafeLinks(content: @Composable () -> Unit) {
    val platform = LocalUriHandler.current
    var request by remember { mutableStateOf<LinkRequest?>(null) }
    val show = remember { { r: LinkRequest -> request = r } }
    val handler = remember { object : UriHandler { override fun openUri(uri: String) = show(LinkRequest(uri)) } }
    CompositionLocalProvider(LocalLinkSheet provides show, LocalUriHandler provides handler) { content() }
    request?.let { r ->
        LinkSheet(
            r,
            onOpen = { url -> runCatching { platform.openUri(url) } },
            onDismiss = { request = null },
        )
    }
}

/** For a message view: link taps carry the link texts found in [markdown]. */
@Composable
fun MessageLinks(markdown: String, content: @Composable () -> Unit) {
    val show = LocalLinkSheet.current
    val texts = remember(markdown) { LinkSafety.linkTexts(markdown) }
    val handler = remember(texts, show) {
        object : UriHandler {
            override fun openUri(uri: String) = show(LinkRequest(uri, texts[uri].orEmpty()))
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) { content() }
}

/** Where the link really goes: scheme and full host highlighted, the whole URL below; Open / Copy / Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(request: LinkRequest, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Whistle.colors
    val target = remember(request.url) { LinkSafety.parse(request.url) }
    val differs = remember(request) { request.texts.firstOrNull { LinkSafety.textDiffers(it, target) } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
        shape = WhistleShapes.Sheet,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).testTag("linkSheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Open link?", style = WhistleType.heading, color = c.wool)
            if (differs != null) {
                Text("Link text and target differ", style = WhistleType.bodyStrong, color = c.ember, modifier = Modifier.testTag("linkMismatch"))
                Text("The text says “${differs.trim()}”, but the link goes to ${target.host.ifEmpty { "another place" }}.", style = WhistleType.secondary, color = c.sage)
            }
            // The real target: scheme and the FULL host, big and bold; nothing elided.
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.sage)) { append(if (target.scheme.isEmpty()) "" else "${target.scheme}://") }
                    withStyle(SpanStyle(color = c.wool, fontWeight = FontWeight.Bold)) { append(target.host.ifEmpty { "(no host)" }) }
                },
                style = WhistleType.mono.copy(fontSize = WhistleType.name.fontSize, lineHeight = WhistleType.name.lineHeight),
                modifier = Modifier.fillMaxWidth().clip(WhistleShapes.Inset).background(c.cardHi).padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("linkHost"),
            )
            SelectionContainer {
                Text(
                    target.url,
                    style = WhistleType.mono,
                    color = c.insetText,
                    modifier = Modifier.fillMaxWidth().clip(WhistleShapes.Inset).background(c.inset).heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp).testTag("linkUrl"),
                )
            }
            if (!target.openable) {
                Text("Only web links (http, https) open from Whistle. You can copy this one.", style = WhistleType.secondary, color = c.sage)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (target.openable) {
                    PillButton("Open", onClick = { onOpen(target.url); onDismiss() }, style = PillStyle.Wool, modifier = Modifier.testTag("linkOpen"))
                    Spacer(Modifier.width(8.dp))
                }
                PillButton(
                    "Copy link",
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("link", target.url)))
                            onDismiss()
                        }
                    },
                    style = PillStyle.Outline,
                    modifier = Modifier.testTag("linkCopy"),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.sage) }
            }
        }
    }
}
