package dev.whistle.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.links.LinkRequest
import dev.whistle.app.ui.links.LocalLinkSheet
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.update.AvailableUpdate
import dev.whistle.app.update.UpdateChecker

/** The "new version" card for [checker]'s offer; nothing when there is none. */
@Composable
fun UpdateCard(checker: UpdateChecker, modifier: Modifier = Modifier) {
    val update by checker.available.collectAsStateWithLifecycle()
    update?.let { UpdateCardContent(it, onLater = checker::dismiss, modifier = modifier) }
}

/** Quiet card: "Whistle x.y.z is available" with Download (link sheet → browser) and Later. */
@Composable
fun UpdateCardContent(update: AvailableUpdate, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = Whistle.colors
    val openLink = LocalLinkSheet.current
    WhistleCard(modifier.testTag("updateCard")) {
        Text("Whistle ${update.version} is available", style = WhistleType.bodyStrong, color = c.wool)
        Spacer(Modifier.height(2.dp))
        Text(
            "Download the APK from GitHub and open it to update. Your machines and pairings stay.",
            style = WhistleType.secondary,
            color = c.sage,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                "Download",
                onClick = { openLink(LinkRequest(update.url, listOf("Whistle ${update.version}"))) },
                style = PillStyle.Outline,
                modifier = Modifier.testTag("updateDownload"),
            )
            PillButton("Later", onClick = onLater, style = PillStyle.Quiet, modifier = Modifier.testTag("updateLater"))
        }
    }
}

/** Padding used where the card sits above a list. */
val UpdateCardPadding = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
