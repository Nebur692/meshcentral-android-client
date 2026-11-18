package io.github.nebur692.meshclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.UiError
import io.github.nebur692.meshclient.core.AgentInstallMode
import io.github.nebur692.meshclient.core.AgentPlatform
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceGroup
import io.github.nebur692.meshclient.core.DeviceIcon
import io.github.nebur692.meshclient.core.DeviceLocation
import io.github.nebur692.meshclient.core.DeviceSettings
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.downloadAgent
import io.github.nebur692.meshclient.core.linuxInstallCommand
import io.github.nebur692.meshclient.core.saveDeviceSettings
import io.github.nebur692.meshclient.core.sendConsoleCommand
import io.github.nebur692.meshclient.core.serverKeepsLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Phase 18, as the Windows app: the agent's console, the device's icon, own ports and place, and the Windows agent and
 * Linux command of a group. / La fase 18, como la app de Windows.
 */

private fun MeshClientException.ui() = UiError(failure, key, httpStatus)

/**
 * The agent's own console, as the website's "Console" tab: commands to the agent (help, info, netinfo…) and what it
 * answers. / La consola del propio agente, como la pestaña «Consola» de la web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConsoleScreen(vm: AppViewModel, id: String) {
    val d = vm.devices[id] ?: run { LaunchedEffect(Unit) { vm.show(Screen.Devices) }; return }
    val back = { vm.show(Screen.DeviceDetail(id)) }
    BackHandler(onBack = back)
    val lines = remember(id) { mutableStateListOf<String>() }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<UiError?>(null) }
    val list = rememberLazyListState()
    val help = stringResource(R.string.console_help)
    LaunchedEffect(id) {
        if (lines.isEmpty()) lines += help
        vm.session?.consoleOutput?.collect { t -> if (t.deviceId == id) lines += t.text.trimEnd('\n') }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    fun send() {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        lines += "> $cmd"; input = ""; error = null
        try { vm.session?.sendConsoleCommand(d, cmd) } catch (e: MeshClientException) { error = e.ui() }
    }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = BarHeight, title = { Text(stringResource(R.string.console_title, d.name)) },
            navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = list) {
                items(lines) { l -> Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.console_input)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false), keyboardActions = KeyboardActions(onSend = { send() }))
                IconButton(onClick = ::send, enabled = input.isNotBlank()) { Icon(painterResource(R.drawable.ic_send), stringResource(R.string.console_send)) }
            }
        }
    }
}

private val icons = listOf(DeviceIcon.Desktop to R.string.icon_desktop, DeviceIcon.Laptop to R.string.icon_laptop, DeviceIcon.Phone to R.string.icon_phone,
    DeviceIcon.Server to R.string.icon_server, DeviceIcon.Storage to R.string.icon_storage, DeviceIcon.Network to R.string.icon_network,
    DeviceIcon.Board to R.string.icon_board, DeviceIcon.VirtualMachine to R.string.icon_vm)

/** Icon, own ports (empty = the usual one) and place on the map. / Icono, puertos propios (vacío = el normal) y sitio en el mapa. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DeviceSettingsDialog(vm: AppViewModel, d: Device, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val now = remember(d.id) { DeviceSettings.of(d) }
    val withPlace = vm.session?.serverKeepsLocations == true
    var icon by remember { mutableStateOf(now.icon) }
    val usual = listOf("RDP" to 3389, "SSH" to 22, "VNC" to 5900, "HTTP" to 80, "HTTPS" to 443)
    val have = listOf(now.rdpPort, now.sshPort, now.vncPort, now.httpPort, now.httpsPort)
    val ports = remember { mutableStateListOf(*have.map { it?.toString().orEmpty() }.toTypedArray()) }
    var lat by remember { mutableStateOf(now.location?.latitude?.let { String.format(Locale.ROOT, "%.6f", it) }.orEmpty()) }
    var lon by remember { mutableStateOf(now.location?.longitude?.let { String.format(Locale.ROOT, "%.6f", it) }.orEmpty()) }
    var bad by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    fun save() {
        val read = ports.mapIndexed { i, t -> if (t.isBlank()) null else t.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let { if (it == usual[i].second) null else it } ?: -1 }
        if (read.any { it == -1 }) { bad = R.string.ports_own_bad; return }
        val la = lat.trim().replace(',', '.'); val lo = lon.trim().replace(',', '.')
        val place = when {
            !withPlace -> now.location
            la.isEmpty() && lo.isEmpty() -> null
            else -> {
                val a = la.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }; val b = lo.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
                if (a == null || b == null) { bad = R.string.place_bad; return }
                DeviceLocation(a, b)
            }
        }
        bad = null; busy = true; error = null
        val s = vm.session ?: return
        scope.launch {
            try { s.saveDeviceSettings(d, DeviceSettings(icon, read[0], read[1], read[2], read[3], read[4], place)); onDone() }
            catch (e: MeshClientException) { error = e.ui() } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.device_settings_title, d.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_icon), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    icons.forEach { (i, label) -> FilterChip(icon == i, { icon = i }, label = { Text(stringResource(label)) }) }
                }
                Text(stringResource(R.string.ports_own), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.ports_own_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                usual.forEachIndexed { i, (name, port) ->
                    OutlinedTextField(ports[i], { ports[i] = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text(name) },
                        placeholder = { Text(stringResource(R.string.ports_usual, port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                if (withPlace) {
                    Text(stringResource(R.string.place_label), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.place_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(lat, { lat = it }, singleLine = true, label = { Text(stringResource(R.string.place_latitude)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lon, { lon = it }, singleLine = true, label = { Text(stringResource(R.string.place_longitude)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                bad?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The Windows agent of a group, saved where the person chooses, and the install command for Linux, BSD and macOS, to
 * copy or send. / El agente de Windows del grupo, guardado donde elija la persona, y la orden de instalación para Linux.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentForGroupDialog(vm: AppViewModel, g: DeviceGroup, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var platform by remember { mutableStateOf(AgentPlatform.Windows64) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    val command = remember(g.id) { vm.session?.linuxInstallCommand(g).orEmpty() }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.microsoft.portable-executable")) { uri ->
        val s = vm.session
        if (uri == null || s == null) return@rememberLauncherForActivityResult
        busy = true; error = null; done = false
        scope.launch {
            var ok = false
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { s.downloadAgent(g, platform, AgentInstallMode.BackgroundAndInteractive, it) }
                }
                ok = true; done = true
            } catch (e: MeshClientException) { error = e.ui() }
            finally {
                busy = false
                // Half a program is worse than none. / Medio programa es peor que ninguno.
                if (!ok) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.agent_for_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.agent_windows), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((p, label) in listOf(AgentPlatform.Windows64 to R.string.agent_win64, AgentPlatform.Windows32 to R.string.agent_win32, AgentPlatform.WindowsArm64 to R.string.agent_arm64))
                        FilterChip(platform == p, { platform = p }, label = { Text(stringResource(label)) })
                }
                TextButton(onClick = { saveTo.launch("meshagent-${g.name.filter(Char::isLetterOrDigit)}.exe") }, enabled = !busy) { Text(stringResource(R.string.agent_download)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (done) Text(stringResource(R.string.agent_saved), color = MaterialTheme.colorScheme.primary)
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.agent_linux), style = MaterialTheme.typography.titleSmall)
                Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(g.name, command))
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command), null)) }
                    }) { Text(stringResource(R.string.share)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.close)) } },
    )
}

