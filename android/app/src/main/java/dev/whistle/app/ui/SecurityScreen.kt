package dev.whistle.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whistle.app.security.AppLock
import dev.whistle.app.security.LockTimeout
import dev.whistle.app.ui.components.PillButton
import dev.whistle.app.ui.components.PillStyle
import dev.whistle.app.ui.components.SectionLabel
import dev.whistle.app.ui.components.WhistleCard
import dev.whistle.app.ui.components.WhistleTopBar
import dev.whistle.app.ui.theme.Whistle
import dev.whistle.app.ui.theme.WhistleType
import dev.whistle.app.update.UpdateChecker

/** Settings: app lock (when to lock again, recents/screenshots) and the update check. */
@Composable
fun SecurityScreen(lock: AppLock, updates: UpdateChecker? = null, onBack: () -> Unit) {
    val s by lock.settings.collectAsStateWithLifecycle()
    val c = Whistle.colors
    Scaffold(
        containerColor = c.night,
        contentWindowInsets = WindowInsets(0),
        topBar = { WhistleTopBar("Settings", onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!lock.deviceSecure) {
                WhistleCard {
                    Text("No screen lock on this phone", style = WhistleType.bodyStrong, color = c.ember)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Whistle can't lock without a PIN, pattern or fingerprint. Set one in Android settings.",
                        style = WhistleType.secondary,
                        color = c.sage,
                    )
                }
            }
            SectionLabel("Lock Whistle")
            WhistleCard(contentPadding = 6.dp) {
                LockTimeout.entries.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.timeout == t, role = Role.RadioButton, onClick = { lock.setTimeout(t) })
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag("timeout:${t.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = s.timeout == t,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = c.wool, unselectedColor = c.sage),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(t.label + " in the background", style = WhistleType.body, color = c.wool)
                    }
                }
            }
            Text(
                "Whistle always locks when it starts. Answering an agent, typing in a terminal, committing, starting " +
                    "an agent and broadcasting ask again if you last unlocked more than a minute ago.",
                style = WhistleType.secondary,
                color = c.sage,
            )
            SectionLabel("Privacy")
            WhistleCard {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = s.hideInRecents, role = Role.Switch, onValueChange = lock::setHideInRecents)
                        .testTag("hideInRecents"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide content in recents", style = WhistleType.body, color = c.wool)
                        Text(
                            "Blank app preview and no screenshots, also while unlocked.",
                            style = WhistleType.secondary,
                            color = c.sage,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = s.hideInRecents,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.night,
                            checkedTrackColor = c.wool,
                            uncheckedThumbColor = c.sage,
                            uncheckedTrackColor = c.card,
                            uncheckedBorderColor = c.line,
                        ),
                    )
                }
            }
            if (updates != null) {
                val checkUpdates by updates.enabled.collectAsStateWithLifecycle()
                SectionLabel("Updates")
                WhistleCard {
                    SwitchRow(
                        title = "Check for updates",
                        detail = "Once a day, asks GitHub (api.github.com) whether a newer Whistle release exists. " +
                            "Nothing is downloaded or installed automatically.",
                        checked = checkUpdates,
                        onChange = updates::setEnabled,
                        modifier = Modifier.testTag("checkUpdates"),
                    )
                }
            }
            if (lock.deviceSecure) {
                PillButton("Lock now", onClick = lock::lockNow, style = PillStyle.Outline, modifier = Modifier.testTag("lockNow"))
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Whistle.colors
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WhistleType.body, color = c.wool)
            Text(detail, style = WhistleType.secondary, color = c.sage)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.night,
                checkedTrackColor = c.wool,
                uncheckedThumbColor = c.sage,
                uncheckedTrackColor = c.card,
                uncheckedBorderColor = c.line,
            ),
        )
    }
}
