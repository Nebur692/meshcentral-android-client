package io.github.nebur692.meshclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.HostKeyPrompt
import io.github.nebur692.meshclient.KnownPort
import io.github.nebur692.meshclient.MappingView
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.Device

/** SSH to the device, or to a machine it reaches; the password is never kept. / SSH al equipo o a una máquina a la que llega. */
@Composable
fun SshSheet(vm: AppViewModel, d: Device) {
    val place = remember(d.id) { vm.sshPlaces.get(d.id) }
    var host by rememberSaveable { mutableStateOf(place.host) }
    var port by rememberSaveable { mutableStateOf(place.port.toString()) }
    var user by rememberSaveable { mutableStateOf(place.user) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<ByteArray?>(null) }
    var keyName by rememberSaveable { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { key = vm.readKey(uri); keyName = uri.lastPathSegment?.substringAfterLast('/') }
    }
    val p = port.toIntOrNull()
    Sheet(stringResource(R.string.ssh_title), d.name, { vm.sheet = null }, buttons = {
        SheetButtons(stringResource(R.string.ssh_connect), user.isNotBlank() && p != null && p in 1..65535 && (password.isNotEmpty() || key != null), false, { vm.sheet = null }) {
            vm.openSsh(d.id, host, p!!, user, password.ifEmpty { null }, key, passphrase.ifEmpty { null })
        }
    }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.ssh_host)) }, placeholder = { Text(stringResource(R.string.ssh_host_this)) },
                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.ssh_port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.45f), shape = RoundedCornerShape(12.dp))
            }
            OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(12.dp))
            OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) { Text(keyName?.let { stringResource(R.string.ssh_key_chosen, it) } ?: stringResource(R.string.ssh_key)) }
            if (key != null) OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.ssh_passphrase)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp))
            Text(stringResource(R.string.ssh_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The SSH server's identity: new (accept once) or CHANGED (a warning). / La identidad del servidor SSH: nueva o CAMBIADA. */
@Composable
fun HostKeyDialog(p: HostKeyPrompt, answer: (Boolean) -> Unit) {
    val changed = p.before != null
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(stringResource(if (changed) R.string.ssh_key_changed_title else R.string.ssh_key_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (changed) R.string.ssh_key_changed_text else R.string.ssh_key_new_text, p.destination))
                Text("${p.key.algorithm}\n${p.key.fingerprint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (changed) Text(stringResource(R.string.ssh_key_before, p.before.orEmpty()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(stringResource(if (changed) R.string.ssh_key_trust_anyway else R.string.ssh_key_trust)) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A port of the device (or of a machine it reaches) on this phone's 127.0.0.1. / Un puerto del equipo en el 127.0.0.1 del móvil. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortSheet(vm: AppViewModel, d: Device) {
    var remote by rememberSaveable { mutableStateOf("3389") }
    var host by rememberSaveable { mutableStateOf("") }
    var local by rememberSaveable { mutableStateOf("") }
    val r = remote.toIntOrNull()
    val l = local.toIntOrNull() ?: 0
    Sheet(stringResource(R.string.ports_new), d.name, { vm.sheet = null }, buttons = {
        SheetButtons(stringResource(R.string.ports_start), r != null && r in 1..65535 && l in 0..65535, false, { vm.sheet = null }) {
            if (vm.startMapping(d.id, r!!, host, l)) { vm.sheet = null; vm.show(Screen.Ports) }
        }
    }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnownPort.entries.forEach { k -> FilterChip(remote == k.port.toString(), { remote = k.port.toString() }, label = { Text("${k.label} ${k.port}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.ssh_host)) }, placeholder = { Text(stringResource(R.string.ssh_host_this)) },
                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(remote, { remote = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.ports_remote)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.5f), shape = RoundedCornerShape(12.dp))
            }
            OutlinedTextField(local, { local = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.ports_local)) },
                placeholder = { Text(stringResource(R.string.ports_local_any)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
            Text(stringResource(R.string.ports_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The mappings open now, with what to do with each. / Las redirecciones abiertas, con qué hacer con cada una. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortsScreen(vm: AppViewModel) {
    val back = { vm.show(Screen.Devices) }
    BackHandler(onBack = back)
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = { Text(stringResource(R.string.ports_title)) },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
            )
            HorizontalDivider()
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom + 16.dp)) {
            if (vm.mappings.isEmpty()) Text(stringResource(R.string.ports_none), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.mappings.toList().forEach { m -> MappingCard(vm, m) }
        }
    }
}

@Composable
private fun MappingCard(vm: AppViewModel, m: MappingView) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val t = m.mapping.target
    val address = "127.0.0.1:${m.mapping.localPort}"
    val web = KnownPort.entries.firstOrNull { it.port == t.remotePort }?.takeIf { it.web }
    val s = m.stats
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.destination, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ports_on, address), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(pluralStringResource(R.plurals.ports_connections, s.active, s.active) + " · " + stringResource(R.string.ports_traffic, size(s.bytesDown), size(s.bytesUp)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.lastError?.let { e -> Text(stringResource(R.string.ports_failed) + " " + errorText(io.github.nebur692.meshclient.UiError(e.failure, e.key, e.httpStatus)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (web != null) FilledTonalButton(onClick = { uri.openUri("${if (web == KnownPort.Https) "https" else "http"}://$address/") }) { Text(stringResource(R.string.ports_open)) }
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(t.destination, address))
                }) { Text(stringResource(R.string.ports_copy)) }
                TextButton(onClick = { vm.stopMapping(m) }) { Text(stringResource(R.string.ports_stop), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
