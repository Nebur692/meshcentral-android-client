package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.BiometricOffer
import io.github.nebur692.meshclient.DeviceSheet
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.PowerAction
import io.github.nebur692.meshclient.core.shellFor
import io.github.nebur692.meshclient.core.TunnelProtocol
import io.github.nebur692.meshclient.core.rightsOn
import io.github.nebur692.meshclient.core.canUpdateAgent

/** A panel from the bottom, with the device's name on top. / Un panel desde abajo, con el nombre del equipo arriba. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, buttons: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            // With the keyboard up the fields scroll and the buttons stay in sight above it.
            // Con el teclado abierto los campos se desplazan y los botones siguen a la vista encima de él.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
                subtitle?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.height(8.dp))
                content()
            }
            buttons?.invoke()
        }
    }
}

@Composable
fun PowerSheet(vm: AppViewModel, d: Device) {
    val can = vm.abilities(d)
    val users = d.users.takeIf { it.isNotEmpty() }?.joinToString(", ")
    Sheet(stringResource(R.string.act_power), users?.let { stringResource(R.string.signed_in_user, it) } ?: d.name, { vm.sheet = null }) {
        SheetOption(R.drawable.ic_wake, stringResource(R.string.wake), stringResource(R.string.wake_hint), enabled = can.wake && !vm.actionBusy) { vm.wake(d) }
        SheetOption(R.drawable.ic_sleep, stringResource(R.string.sleep), stringResource(R.string.sleep_hint), enabled = can.power && !vm.actionBusy) { vm.power(d, PowerAction.Sleep) }
        SheetOption(R.drawable.ic_restart, stringResource(R.string.restart), null, enabled = can.power && !vm.actionBusy, danger = true) { vm.sheet = DeviceSheet.ConfirmRestart }
        SheetOption(R.drawable.ic_power, stringResource(R.string.power_off), null, enabled = can.power && !vm.actionBusy, danger = true) { vm.sheet = DeviceSheet.ConfirmPowerOff }
        if (!d.isOnline && !can.wake) Text(stringResource(R.string.power_offline), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MoreSheet(vm: AppViewModel, d: Device, onCopy: () -> Unit) {
    val can = vm.abilities(d)
    Sheet(d.name, null, { vm.sheet = null }) {
        if (can.edit) SheetOption(R.drawable.ic_edit, stringResource(R.string.edit_device), null) { vm.sheet = DeviceSheet.Edit }
        SheetOption(R.drawable.ic_copy, stringResource(R.string.copy_details), stringResource(R.string.copy_details_hint), onClick = onCopy)
        if (vm.canRelay(d)) SheetOption(R.drawable.ic_globe, stringResource(R.string.ports_new), stringResource(R.string.ports_new_hint)) { vm.sheet = DeviceSheet.Port }
        if (vm.session?.canUpdateAgent(d) == true)
            SheetOption(R.drawable.ic_update, stringResource(R.string.update_agent), stringResource(R.string.update_agent_hint)) { vm.sheet = DeviceSheet.ConfirmUpdateAgent }
        if (vm.moveTargets(d).isNotEmpty()) SheetOption(R.drawable.ic_folder, stringResource(R.string.move_device), stringResource(R.string.move_device_hint)) { vm.sheet = DeviceSheet.Move }
        val context = androidx.compose.ui.platform.LocalContext.current
        if (remember { io.github.nebur692.meshclient.Shortcuts.canPin(context) })
            SheetOption(R.drawable.ic_home, stringResource(R.string.shortcut_pin), stringResource(R.string.shortcut_pin_hint)) { vm.sheet = DeviceSheet.Pin }
        if (can.uninstall || can.remove) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (can.uninstall) SheetOption(R.drawable.ic_uninstall, stringResource(R.string.uninstall), null, danger = true) { vm.sheet = DeviceSheet.ConfirmUninstall }
        if (can.remove) SheetOption(R.drawable.ic_trash, stringResource(R.string.remove), null, danger = true) { vm.sheet = DeviceSheet.ConfirmRemove }
    }
}

/**
 * What the home screen shortcut opens: the sheet, or a tool this account may use on the device (offered even while it is
 * off; tapped then, the sheet says so). / Lo que abre el acceso en la pantalla de inicio: la ficha, o una herramienta que
 * esta cuenta puede usar en el equipo (se ofrece aunque esté apagado; tocado entonces, la ficha lo dice).
 */
@Composable
fun PinSheet(vm: AppViewModel, d: Device) {
    val rights = vm.session?.rightsOn(d) ?: 0L
    fun may(no: Long, cap: Int) = io.github.nebur692.meshclient.core.MeshRights.tool(rights, no) && d.capabilities and cap != 0
    Sheet(stringResource(R.string.shortcut_pin), d.name, { vm.sheet = null }) {
        @Composable fun option(icon: Int, label: Int, tool: io.github.nebur692.meshclient.LinkTool) {
            val name = stringResource(label)
            SheetOption(icon, name, null) { vm.pinShortcut(d, tool, name) }
        }
        option(kindIcon(d), R.string.shortcut_device, io.github.nebur692.meshclient.LinkTool.Device)
        if (may(io.github.nebur692.meshclient.core.MeshRights.NO_DESKTOP, io.github.nebur692.meshclient.core.AgentCaps.DESKTOP))
            option(R.drawable.ic_monitor, R.string.act_desktop, io.github.nebur692.meshclient.LinkTool.Desktop)
        if (may(io.github.nebur692.meshclient.core.MeshRights.NO_TERMINAL, io.github.nebur692.meshclient.core.AgentCaps.TERMINAL)) {
            option(R.drawable.ic_terminal, terminalName(TunnelProtocol.Terminal, d.isWindows), io.github.nebur692.meshclient.LinkTool.Terminal)
            if (d.isWindows) option(R.drawable.ic_terminal, terminalName(TunnelProtocol.PowerShell, true), io.github.nebur692.meshclient.LinkTool.PowerShell)
        }
        if (may(io.github.nebur692.meshclient.core.MeshRights.NO_FILES, io.github.nebur692.meshclient.core.AgentCaps.FILES))
            option(R.drawable.ic_folder, R.string.act_files, io.github.nebur692.meshclient.LinkTool.Files)
    }
}

/** Another device group of the same kind, where this account may move it. / Otro grupo del mismo tipo donde puede moverlo. */
@Composable
fun MoveSheet(vm: AppViewModel, d: Device) {
    Sheet(stringResource(R.string.move_device), d.name, { vm.sheet = null }) {
        vm.moveTargets(d).forEach { g -> SheetOption(R.drawable.ic_folder, g.name, g.description, enabled = !vm.actionBusy) { vm.moveDevice(d, g) } }
    }
}

@Composable
fun MessageSheet(vm: AppViewModel, d: Device) {
    val can = vm.abilities(d)
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    // A window that waits to be closed needs remote control; a notification, the notify right. / La ventana pide control remoto.
    var window by rememberSaveable { mutableStateOf(!can.notify) }
    Sheet(stringResource(R.string.message_title), d.users.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: d.name, { vm.sheet = null }, buttons = {
        SheetButtons(stringResource(R.string.send), text.isNotBlank(), vm.actionBusy, { vm.sheet = null }) {
            vm.sendMessage(d, title.ifBlank { "MeshCentral" }, text.trim(), window && can.messageBox)
        }
    }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(100) }, label = { Text(stringResource(R.string.message_heading)) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.app_name)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(text, { text = it.take(1000) }, label = { Text(stringResource(R.string.message_text)) }, minLines = 3,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (can.notify && can.messageBox) Row(
                Modifier.fillMaxWidth().toggleable(window, role = Role.Switch, onValueChange = { window = it }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.message_window), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.message_window_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = window, onCheckedChange = null)
            }
        }
    }
}

/**
 * Commands as the device's agent (the system account on Windows, root elsewhere), with their output below.
 * Órdenes como el agente del equipo (la cuenta del sistema en Windows, root en los demás), con su salida debajo.
 */
@Composable
fun RunSheet(vm: AppViewModel, d: Device) {
    var commands by rememberSaveable { mutableStateOf("") }
    var powerShell by rememberSaveable { mutableStateOf(false) }
    Sheet(stringResource(R.string.run_title), stringResource(if (d.isWindows) R.string.run_as_system else R.string.run_as_root), { vm.clearCommandOutput(); vm.sheet = null }, buttons = {
        // Once something has run there is nothing to cancel. / Cuando ya se ha ejecutado algo no hay nada que cancelar.
        SheetButtons(stringResource(R.string.run), commands.isNotBlank(), vm.actionBusy, { vm.clearCommandOutput(); vm.sheet = null },
            dismiss = if (vm.commandOutput != null) R.string.close else R.string.cancel) {
            vm.runCommands(d, commands, shellFor(d, powerShell))
        }
    }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.isWindows) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedChoice(!powerShell, "cmd", Modifier.weight(1f)) { powerShell = false }
                OutlinedChoice(powerShell, "PowerShell", Modifier.weight(1f)) { powerShell = true }
            }
            OutlinedTextField(commands, { commands = it }, label = { Text(stringResource(R.string.run_commands)) }, minLines = 3, maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(if (d.isWindows) "ipconfig /all" else "uptime", fontFamily = FontFamily.Monospace) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (vm.actionBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.run_waiting), style = MaterialTheme.typography.bodyMedium)
            }
            vm.commandOutput?.let { out ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(out.trimEnd(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun EditSheet(vm: AppViewModel, d: Device) {
    var name by rememberSaveable(d.id) { mutableStateOf(d.name) }
    var description by rememberSaveable(d.id) { mutableStateOf(d.description.orEmpty()) }
    var tags by rememberSaveable(d.id) { mutableStateOf(d.tags.joinToString(", ")) }
    Sheet(stringResource(R.string.edit_device), null, { vm.sheet = null }, buttons = {
        SheetButtons(stringResource(R.string.save), name.isNotBlank(), vm.actionBusy, { vm.sheet = null }) {
            vm.saveDevice(d, name, description, tags.split(',').map(String::trim).filter(String::isNotEmpty).distinct())
        }
    }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text(stringResource(R.string.device_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(description, { description = it.take(1024) }, label = { Text(stringResource(R.string.description)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(tags, { tags = it }, label = { Text(stringResource(R.string.tags)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.tags_hint)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        }
    }
}

/** After signing in: the fingerprint next time? / Tras entrar: ¿la huella la próxima vez? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricOfferSheet(offer: BiometricOffer, answer: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = { answer(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_fingerprint), null, Modifier.size(40.dp)) }
            }
            Text(stringResource(R.string.biometric_offer_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(if (offer.session) pluralStringResource(R.plurals.biometric_offer_session, offer.days, offer.days) else stringResource(R.string.biometric_offer_password),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = { answer(true) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.biometric_offer_yes), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { answer(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.remember_no)) }
        }
    }
}

/** Which terminal: cmd or PowerShell on Windows, the agent's or the user's shell elsewhere. / Qué terminal abrir. */
@Composable
fun TerminalSheet(vm: AppViewModel, d: Device) {
    Sheet(stringResource(R.string.term_choose), d.name, onDismiss = { vm.sheet = null }) {
        val kinds = if (d.isWindows) listOf(TunnelProtocol.Terminal to null, TunnelProtocol.PowerShell to null)
        else listOf(TunnelProtocol.Terminal to null, TunnelProtocol.UserShell to R.string.term_user_shell_hint)
        kinds.forEach { (kind, hint) ->
            ListItem(
                headlineContent = { Text(stringResource(terminalName(kind, d.isWindows))) },
                supportingContent = hint?.let { h -> { Text(stringResource(h)) } },
                leadingContent = { Icon(painterResource(R.drawable.ic_terminal), null) },
                modifier = Modifier.clickable { vm.openTerminal(d.id, kind) },
            )
        }
        // SSH to this device or to a machine it reaches, through its agent. / SSH a este equipo o a una máquina a la que llega.
        if (vm.canRelay(d)) ListItem(
            headlineContent = { Text(stringResource(R.string.ssh_title)) },
            supportingContent = { Text(stringResource(R.string.ssh_sheet_hint)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_lock), null) },
            modifier = Modifier.clickable { vm.sheet = DeviceSheet.Ssh },
        )
    }
}

/**
 * What each agent said after being asked to update. Silence is not failure: an agent that updates restarts and connects
 * again without a word. / Lo que dijo cada agente al pedirle actualizar. Callar no es fallar: uno que se actualiza se
 * reinicia y vuelve sin decir nada.
 */
@Composable
fun AgentAnswersDialog(a: io.github.nebur692.meshclient.AgentAnswers, dismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.update_agent_answers)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                a.said.forEach { (name, said) ->
                    androidx.compose.foundation.layout.Column {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(said ?: stringResource(R.string.update_agent_silent), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.accept)) } },
    )
}
