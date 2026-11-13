package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.BulkAction
import io.github.nebur692.meshclient.BulkModel
import io.github.nebur692.meshclient.BulkSheet
import io.github.nebur692.meshclient.R

/**
 * The header while picking devices: how many, select all that are shown, and the actions; it takes the search bar's
 * place, as Android's own lists do. / La cabecera mientras se eligen equipos: cuántos, elegir todos los que se ven y las
 * acciones; ocupa el sitio del buscador, como las listas del propio Android.
 */
@Composable
internal fun BulkBar(b: BulkModel, shownIds: List<String>) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = b::clear) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.bulk_stop)) }
            Text(pluralStringResource(R.plurals.bulk_selected, b.selected.size, b.selected.size), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!b.selected.containsAll(shownIds)) IconButton(onClick = { b.selectAll(shownIds) }) {
                Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.bulk_select_all))
            }
            IconButton(onClick = { b.sheet = BulkSheet.Power }) { Icon(painterResource(R.drawable.ic_power), stringResource(R.string.act_power)) }
            IconButton(onClick = { b.sheet = BulkSheet.Notify }) { Icon(painterResource(R.drawable.ic_chat), stringResource(R.string.bulk_notify)) }
            IconButton(onClick = { b.sheet = BulkSheet.Run }) { Icon(painterResource(R.drawable.ic_run), stringResource(R.string.run_title)) }
        }
    }
}

/** What the last action did, for a moment. / Lo que hizo la última acción, un momento. */
@Composable
internal fun BulkNotice(b: BulkModel) {
    val n = b.notice ?: return
    LaunchedEffect(n) { kotlinx.coroutines.delay(5000); if (b.notice === n) b.notice = null }
    val (went, left) = b.noticeCounts
    val text = buildString {
        append(stringResource(n.text))
        n.error?.let { append(" ").append(errorText(it)) }
        if (n.error == null && went > 0) append(" ").append(pluralStringResource(R.plurals.bulk_went, went, went))
        if (left > 0) append(" ").append(pluralStringResource(R.plurals.bulk_left_out, left, left))
    }
    Surface(color = if (n.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** How many of the picked ones an action reaches, when not all. / A cuántos de los elegidos llega una acción, si no a todos. */
@Composable
private fun Reach(vm: AppViewModel, b: BulkModel, action: BulkAction) {
    val picked = b.devices(vm.devices)
    val go = b.eligible(action, picked).size
    if (go == picked.size) return
    Text(if (go == 0) stringResource(R.string.bulk_none) else stringResource(R.string.bulk_reach, go, picked.size),
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun BulkSheets(vm: AppViewModel, b: BulkModel) {
    val picked = b.devices(vm.devices)
    val count = picked.size
    fun can(a: BulkAction) = b.eligible(a, picked).isNotEmpty() && !b.busy
    when (b.sheet) {
        BulkSheet.Power -> Sheet(stringResource(R.string.act_power), pluralStringResource(R.plurals.bulk_selected, count, count), { b.sheet = null }) {
            SheetOption(R.drawable.ic_wake, stringResource(R.string.wake), stringResource(R.string.wake_hint), enabled = can(BulkAction.Wake)) { b.run(BulkAction.Wake, vm.devices) }
            SheetOption(R.drawable.ic_sleep, stringResource(R.string.sleep), stringResource(R.string.sleep_hint), enabled = can(BulkAction.Sleep)) { b.run(BulkAction.Sleep, vm.devices) }
            SheetOption(R.drawable.ic_restart, stringResource(R.string.restart), null, enabled = can(BulkAction.Restart), danger = true) { b.sheet = BulkSheet.ConfirmRestart }
            SheetOption(R.drawable.ic_power, stringResource(R.string.power_off), null, enabled = can(BulkAction.PowerOff), danger = true) { b.sheet = BulkSheet.ConfirmPowerOff }
            Reach(vm, b, BulkAction.Restart)
        }
        BulkSheet.Notify -> {
            var title by rememberSaveable { mutableStateOf("") }
            var text by rememberSaveable { mutableStateOf("") }
            Sheet(stringResource(R.string.bulk_notify), pluralStringResource(R.plurals.bulk_selected, count, count), { b.sheet = null }, buttons = {
                SheetButtons(stringResource(R.string.send), text.isNotBlank() && can(BulkAction.Notify), b.busy, { b.sheet = null }) {
                    b.run(BulkAction.Notify, vm.devices, title = title.ifBlank { "MeshCentral" }, text = text.trim())
                }
            }) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text(stringResource(R.string.message_heading)) }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.app_name)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(text, { text = it.take(1000) }, label = { Text(stringResource(R.string.message_text)) }, minLines = 3,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
                Reach(vm, b, BulkAction.Notify)
            }
        }
        BulkSheet.Run -> {
            var commands by rememberSaveable { mutableStateOf("") }
            var powerShell by rememberSaveable { mutableStateOf(false) }
            val close = { b.clearOutputs(); b.sheet = null }
            Sheet(stringResource(R.string.run_title), pluralStringResource(R.plurals.bulk_selected, count, count), close, buttons = {
                SheetButtons(stringResource(R.string.run), commands.isNotBlank() && can(BulkAction.Run), b.busy, close,
                    dismiss = if (b.outputs != null) R.string.close else R.string.cancel) {
                    b.run(BulkAction.Run, vm.devices, commands = commands, powerShell = powerShell)
                }
            }) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.bulk_run_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (b.anyWindows(vm.devices)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedChoice(!powerShell, "cmd", Modifier.weight(1f)) { powerShell = false }
                        OutlinedChoice(powerShell, "PowerShell", Modifier.weight(1f)) { powerShell = true }
                    }
                    OutlinedTextField(commands, { commands = it }, label = { Text(stringResource(R.string.run_commands)) }, minLines = 3, maxLines = 8,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    if (b.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.run_waiting), style = MaterialTheme.typography.bodyMedium)
                    }
                    b.outputs?.forEach { o ->
                        Text(o.device.name, style = MaterialTheme.typography.titleSmall, color = if (o.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                                Text(o.output?.trimEnd() ?: errorText(o.error!!), fontFamily = if (o.output != null) FontFamily.Monospace else null,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Reach(vm, b, BulkAction.Run)
            }
        }
        BulkSheet.ConfirmRestart -> BulkConfirm(b, pluralStringResource(R.plurals.bulk_confirm_restart, b.eligible(BulkAction.Restart, picked).size,
            b.eligible(BulkAction.Restart, picked).size), stringResource(R.string.bulk_confirm_text), R.string.restart) { b.run(BulkAction.Restart, vm.devices) }
        BulkSheet.ConfirmPowerOff -> BulkConfirm(b, pluralStringResource(R.plurals.bulk_confirm_off, b.eligible(BulkAction.PowerOff, picked).size,
            b.eligible(BulkAction.PowerOff, picked).size), stringResource(R.string.bulk_confirm_off_text), R.string.power_off) { b.run(BulkAction.PowerOff, vm.devices) }
        null -> Unit
    }
}

@Composable
private fun BulkConfirm(b: BulkModel, title: String, text: String, action: Int, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = { b.sheet = null },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes, enabled = !b.busy) { Text(stringResource(action), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { b.sheet = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
