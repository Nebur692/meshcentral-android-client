package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.DeviceGroup
import io.github.nebur692.meshclient.core.DeviceGroupKind
import io.github.nebur692.meshclient.core.GroupSettings
import io.github.nebur692.meshclient.core.GroupSettings.Consent
import io.github.nebur692.meshclient.core.GroupSettings.Feature
import io.github.nebur692.meshclient.core.ServerInfo

/**
 * What a group asks of the person at the device and how it keeps its devices, laid out as the website's two dialogs.
 * What the server forces stays ticked and cannot be changed; the name sub-options only exist with name sync.
 * Lo que un grupo pide a la persona del equipo y cómo guarda sus equipos, como los dos diálogos de la web. Lo que impone
 * el servidor sale marcado y no se puede cambiar; las opciones del nombre solo existen con la sincronización.
 */
@Composable
fun GroupSettingsDialog(g: DeviceGroup, server: ServerInfo, busy: Boolean, dismiss: () -> Unit, save: (GroupSettings) -> Unit) {
    var consent by rememberSaveable { mutableIntStateOf(g.settings.consent or server.forcedConsent) }
    var flags by rememberSaveable { mutableIntStateOf(g.settings.flags) }
    var removeInactive by rememberSaveable { mutableStateOf(g.settings.removeInactiveDays > 0) }
    var days by rememberSaveable { mutableStateOf(g.settings.removeInactiveDays.takeIf { it > 0 }?.toString() ?: "30") }
    val agentLike = g.kind == DeviceGroupKind.Agents || g.kind == DeviceGroupKind.IpKvm
    val daysValue = days.toIntOrNull()
    val daysOk = !removeInactive || (daysValue != null && daysValue in 1..GroupSettings.MAX_DAYS)

    @Composable fun Title(text: Int) = Text(stringResource(text), Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    @Composable fun Box(label: Int, checked: Boolean, enabled: Boolean = true, indent: Boolean = false, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled && !busy, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = if (indent) 24.dp else 0.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, null, enabled = enabled && !busy)
            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }

    @Composable fun ConsentBox(label: Int, bit: Int) {
        val forced = server.forcedConsent and bit != 0
        Box(label, consent and bit != 0, enabled = !forced) { on -> consent = if (on) consent or bit else consent and bit.inv() }
    }

    fun flag(bit: Int, on: Boolean) { flags = if (on) flags or bit else flags and bit.inv() }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.group_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(stringResource(R.string.group_consent_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (server.forcedConsent != 0) Text(stringResource(R.string.group_consent_forced), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Title(R.string.act_desktop)
                ConsentBox(R.string.consent_notify, Consent.DESKTOP_NOTIFY)
                ConsentBox(R.string.consent_prompt, Consent.DESKTOP_PROMPT)
                ConsentBox(R.string.consent_toolbar, Consent.DESKTOP_TOOLBAR)
                Title(R.string.act_terminal)
                ConsentBox(R.string.consent_notify, Consent.TERMINAL_NOTIFY)
                ConsentBox(R.string.consent_prompt, Consent.TERMINAL_PROMPT)
                Title(R.string.act_files)
                ConsentBox(R.string.consent_notify, Consent.FILES_NOTIFY)
                ConsentBox(R.string.consent_prompt, Consent.FILES_PROMPT)
                Title(R.string.consent_registry)
                ConsentBox(R.string.consent_notify, Consent.REGISTRY_NOTIFY)
                ConsentBox(R.string.consent_prompt, Consent.REGISTRY_PROMPT)

                Title(R.string.group_features)
                if (server.groupsChooseRecording) Box(R.string.feature_record, flags and Feature.RECORD != 0) { flag(Feature.RECORD, it) }
                Box(if (g.kind == DeviceGroupKind.IpKvm) R.string.feature_sync_port else R.string.feature_sync_name, flags and Feature.SYNC_NAME != 0) { on ->
                    flag(Feature.SYNC_NAME, on)
                    // As the website: ticking the sync ticks its two options. / Como la web: marcar la sincronización marca sus dos opciones.
                    if (on) { flag(Feature.PREFER_AGENT_NAME, true); flag(Feature.ALLOW_OVERRIDE, true) }
                }
                if (g.kind == DeviceGroupKind.Agents) {
                    val sync = flags and Feature.SYNC_NAME != 0
                    Box(R.string.feature_prefer_agent_name, sync && flags and Feature.PREFER_AGENT_NAME != 0, enabled = sync, indent = true) { flag(Feature.PREFER_AGENT_NAME, it) }
                    Box(R.string.feature_allow_override, sync && flags and Feature.ALLOW_OVERRIDE != 0, enabled = sync, indent = true) { flag(Feature.ALLOW_OVERRIDE, it) }
                }
                if (agentLike) Box(R.string.feature_remove_on_disconnect, flags and Feature.REMOVE_ON_DISCONNECT != 0) { flag(Feature.REMOVE_ON_DISCONNECT, it) }
                val onDisconnect = flags and Feature.REMOVE_ON_DISCONNECT != 0
                Box(R.string.feature_remove_inactive, removeInactive && !onDisconnect, enabled = !onDisconnect) { removeInactive = it }
                if (removeInactive && !onDisconnect) OutlinedTextField(days, { t -> days = t.filter(Char::isDigit).take(4) },
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.feature_remove_inactive_days)) },
                    isError = !daysOk, supportingText = if (!daysOk) ({ Text(pluralStringResource(R.plurals.feature_remove_inactive_range, GroupSettings.MAX_DAYS, GroupSettings.MAX_DAYS)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && daysOk, onClick = {
                val d = if (removeInactive && flags and Feature.REMOVE_ON_DISCONNECT == 0) daysValue ?: 0 else 0
                save(GroupSettings(consent, flags, d))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The group's consent and features in one line, as the website lists them. / En una línea, como los lista la web. */
@Composable
fun GroupSummary(g: DeviceGroup, server: ServerInfo) {
    val s = g.settings
    val c = s.consent or server.forcedConsent
    val notify = stringResource(R.string.consent_notify_short)
    val prompt = stringResource(R.string.consent_prompt_short)
    val bar = stringResource(R.string.consent_toolbar_short)
    val names = listOf(stringResource(R.string.act_desktop), stringResource(R.string.act_terminal), stringResource(R.string.act_files), stringResource(R.string.consent_registry))
    val record = stringResource(R.string.feature_record)
    val synced = stringResource(R.string.feature_sync_short)
    val onDisconnect = stringResource(R.string.feature_remove_on_disconnect)
    val inactive = pluralStringResource(R.plurals.feature_remove_inactive_short, s.removeInactiveDays, s.removeInactiveDays)
    val parts = buildList {
        fun tool(name: String, n: Int, p: Int, t: Int = 0) {
            val what = buildList {
                if (c and p != 0) add(prompt) else if (c and n != 0) add(notify)
                if (t != 0 && c and t != 0) add(bar)
            }
            if (what.isNotEmpty()) add(name + ": " + what.joinToString(", "))
        }
        tool(names[0], Consent.DESKTOP_NOTIFY, Consent.DESKTOP_PROMPT, Consent.DESKTOP_TOOLBAR)
        tool(names[1], Consent.TERMINAL_NOTIFY, Consent.TERMINAL_PROMPT)
        tool(names[2], Consent.FILES_NOTIFY, Consent.FILES_PROMPT)
        tool(names[3], Consent.REGISTRY_NOTIFY, Consent.REGISTRY_PROMPT)
        if (s.flags and Feature.RECORD != 0 && server.groupsChooseRecording) add(record)
        if (s.flags and Feature.SYNC_NAME != 0) add(synced)
        if (s.flags and Feature.REMOVE_ON_DISCONNECT != 0) add(onDisconnect)
        if (s.removeInactiveDays > 0) add(inactive)
    }
    Text(if (parts.isEmpty()) stringResource(R.string.group_settings_none) else parts.joinToString(" · "),
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
