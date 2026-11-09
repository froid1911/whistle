package dev.whistle.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.whistle.app.data.WorkspaceUi
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType

fun WorkspaceUi.title(): String = label.ifBlank { "Workspace $number" }

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = WhistleType.body, color = Whistle.colors.sage, textAlign = TextAlign.Center)
    }
}
