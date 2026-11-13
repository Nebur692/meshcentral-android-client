package io.github.nebur692.meshclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.DeviceToolsModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.DeviceProcess
import io.github.nebur692.meshclient.core.DeviceService
import io.github.nebur692.meshclient.core.MeshRights
import io.github.nebur692.meshclient.core.ServiceAction
import io.github.nebur692.meshclient.core.rightsOn

/** A device's processes or services: a list to search, refresh and act on. / Procesos o servicios: buscar, actualizar y actuar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(vm: AppViewModel) {
    val t = vm.deviceTools ?: run { LaunchedEffect(Unit) { vm.show(Screen.Devices) }; return }
    val back = { vm.leaveDeviceTools() }
    BackHandler(onBack = back)
    LaunchedEffect(t) { t.load() }
    // Ending or starting needs remote control; view-only only looks. / Actuar pide control remoto; solo ver solo mira.
    val canAct = vm.session?.rightsOn(t.device)?.let { MeshRights.has(it, MeshRights.REMOTE_CONTROL) } == true
    var killing by remember { mutableStateOf<DeviceProcess?>(null) }
    var service by remember { mutableStateOf<DeviceService?>(null) }
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = {
                    Column {
                        Text(t.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (t.services) R.string.act_services else R.string.act_processes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                actions = { IconButton(onClick = t::load, enabled = !t.loading) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) } },
            )
            OutlinedTextField(t.filter, { t.filter = it }, placeholder = { Text(stringResource(R.string.tools_search)) }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(24.dp))
            if (t.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            (t.error?.let { errorText(it) to true } ?: t.notice?.let { n ->
                LaunchedEffect(n) { kotlinx.coroutines.delay(4000); if (t.notice === n) t.notice = null }
                (n.error?.let { stringResource(n.text) + " " + errorText(it) } ?: stringResource(n.text)) to (n.error != null || n.text == R.string.tools_kill_failed)
            })?.let { (text, bad) ->
                Surface(color = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val q = t.filter.trim().lowercase()
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (t.unavailable) Text(stringResource(if (t.services) R.string.tools_no_services else R.string.tools_no_processes), Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
                if (t.services) {
                    val list = t.serviceList.orEmpty().filter { q.isEmpty() || q in it.displayName.lowercase() || q in it.name.lowercase() }
                    items(list, key = { it.name }) { s ->
                        ToolRow(s.displayName, listOfNotNull(s.name.takeIf { it != s.displayName }, s.pid?.let { "PID $it" }).joinToString(" · "),
                            s.state?.let { stateText(it) }, s.isRunning, onClick = if (canAct) ({ service = s }) else null)
                    }
                } else {
                    val list = t.processes.orEmpty().filter { q.isEmpty() || q in it.command.lowercase() || q == it.pid.toString() }
                    items(list, key = { it.pid }) { p ->
                        ToolRow(p.command, listOfNotNull("PID ${p.pid}", p.user).joinToString(" · "), null, false, onClick = if (canAct) ({ killing = p }) else null)
                    }
                }
            }
        }
    }
    killing?.let { p ->
        AlertDialog(
            onDismissRequest = { killing = null },
            title = { Text(stringResource(R.string.tools_kill_title)) },
            text = { Text(stringResource(R.string.tools_kill_text, p.command, p.pid)) },
            confirmButton = { TextButton(onClick = { t.kill(p); killing = null }) { Text(stringResource(R.string.tools_kill), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { killing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    service?.let { s ->
        AlertDialog(
            onDismissRequest = { service = null },
            title = { Text(s.displayName) },
            text = { Text(s.name + (s.state?.let { " · " + it } ?: "")) },
            confirmButton = {
                Row {
                    if (s.isRunning) {
                        TextButton(onClick = { t.act(s, ServiceAction.Restart); service = null }) { Text(stringResource(R.string.tools_restart)) }
                        TextButton(onClick = { t.act(s, ServiceAction.Stop); service = null }) { Text(stringResource(R.string.tools_stop), color = MaterialTheme.colorScheme.error) }
                    } else TextButton(onClick = { t.act(s, ServiceAction.Start); service = null }) { Text(stringResource(R.string.tools_start)) }
                }
            },
            dismissButton = { TextButton(onClick = { service = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun stateText(state: String): String = when (state.uppercase()) {
    "RUNNING" -> stringResource(R.string.tools_running)
    "STOPPED" -> stringResource(R.string.tools_stopped)
    else -> state.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun ToolRow(title: String, subtitle: String, badge: String?, good: Boolean, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        badge?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
