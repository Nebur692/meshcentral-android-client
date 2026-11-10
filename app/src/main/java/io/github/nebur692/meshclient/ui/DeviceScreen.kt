package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen

/** The device's sheet; live, so it follows the device's changes. / La ficha del equipo; en vivo, sigue sus cambios. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: AppViewModel, id: String) {
    val d = vm.devices[id]
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(d?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { vm.show(Screen.Devices) }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
            },
        )
    }) { padding ->
        // Removed meanwhile: back to the list. / Borrado mientras tanto: vuelta a la lista.
        if (d == null) { vm.show(Screen.Devices); return@Scaffold }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OsTile(d, size = 52)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusLabel(d.isOnline)
                        Text(listOfNotNull(d.osDescription, vm.groupNames[d.groupId]).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                        d.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.agent_tools), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                InfoRow(stringResource(R.string.group), vm.groupNames[d.groupId] ?: d.groupId)
                d.osDescription?.let { InfoRow(stringResource(R.string.system), it) }
                d.address?.let { InfoRow(stringResource(R.string.address), it) }
                d.description?.takeIf(String::isNotBlank)?.let { InfoRow(stringResource(R.string.description), it) }
                if (d.tags.isNotEmpty()) {
                    Text(stringResource(R.string.tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { d.tags.forEach { AssistChip(onClick = {}, label = { Text(it) }) } }
                }
            }
        }
    }
}
