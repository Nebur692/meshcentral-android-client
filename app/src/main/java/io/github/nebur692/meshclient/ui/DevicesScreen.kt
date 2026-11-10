package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.BuildConfig
import io.github.nebur692.meshclient.DeviceFilter
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.CertificateTrust
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.OsFamily

/** The two places of a signed-in session. / Los dos sitios de una sesión abierta. */
@Composable
fun SignedInScreen(vm: AppViewModel) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = vm.screen == Screen.Devices, onClick = { vm.show(Screen.Devices) },
                    icon = { Icon(painterResource(R.drawable.ic_monitor), null) }, label = { Text(stringResource(R.string.tab_devices)) })
                NavigationBarItem(selected = vm.screen == Screen.Account, onClick = { vm.show(Screen.Account) },
                    icon = { Icon(painterResource(R.drawable.ic_person), null) }, label = { Text(stringResource(R.string.tab_account)) })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.lost) LostBanner(vm)
            if (vm.screen == Screen.Account) AccountPane(vm) else DevicesPane(vm)
        }
    }
}

@Composable
private fun LostBanner(vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connection_lost), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = vm::reconnect) { Text(stringResource(R.string.reconnect)) }
        }
    }
}

private fun Device.matches(q: String, group: String?): Boolean {
    if (q.isBlank()) return true
    val t = q.trim()
    return listOfNotNull(name, osDescription, address, description, group).any { it.contains(t, ignoreCase = true) } ||
        tags.any { it.contains(t, ignoreCase = true) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevicesPane(vm: AppViewModel) {
    val all = vm.devices.values.toList()
    val shown = all.filter { d ->
        when (vm.filter) {
            DeviceFilter.All -> true
            DeviceFilter.Online -> d.isOnline
            DeviceFilter.Windows -> d.osFamily == OsFamily.Windows
            DeviceFilter.Linux -> d.osFamily == OsFamily.Linux
        } && d.matches(vm.query, vm.groupNames[d.groupId])
    }
    val byGroup = shown.groupBy { vm.groupNames[it.groupId] ?: it.groupId }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_devices), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(vm.filter == DeviceFilter.All, { vm.filter = DeviceFilter.All }, { Text(stringResource(R.string.filter_all, all.size)) }) }
            item { FilterChip(vm.filter == DeviceFilter.Online, { vm.filter = DeviceFilter.Online }, { Text(stringResource(R.string.filter_online, all.count { it.isOnline })) }) }
            item { FilterChip(vm.filter == DeviceFilter.Windows, { vm.filter = DeviceFilter.Windows }, { Text(stringResource(R.string.filter_windows)) }) }
            item { FilterChip(vm.filter == DeviceFilter.Linux, { vm.filter = DeviceFilter.Linux }, { Text(stringResource(R.string.filter_linux)) }) }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (all.isEmpty()) R.string.no_devices else R.string.no_match),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            byGroup.forEach { (group, list) ->
                stickyHeader(key = "g:$group") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
                        Text(group, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.devices_count, list.size, list.size), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list.sortedBy { it.name.lowercase() }, key = { it.id }) { d -> DeviceRow(d) { vm.show(Screen.DeviceDetail(d.id)) } }
            }
        }
    }
}

@Composable
fun OsTile(d: Device, size: Int = 44) {
    val on = d.isOnline
    Surface(shape = RoundedCornerShape((size / 3).dp), modifier = Modifier.size(size.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(when (d.osFamily) {
                OsFamily.Windows -> R.drawable.ic_os_windows
                OsFamily.Mac -> R.drawable.ic_os_mac
                OsFamily.Linux, OsFamily.Android, OsFamily.Other -> R.drawable.ic_os_linux
            }), null, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
fun StatusLabel(online: Boolean) {
    val c = if (online) LocalStatusColors.current.online else LocalStatusColors.current.offline
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Text(stringResource(if (online) R.string.online else R.string.offline), color = c, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OsTile(d)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(d.osDescription, d.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusLabel(d.isOnline)
    }
}

@Composable
private fun AccountPane(vm: AppViewModel) {
    val s = vm.session ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InfoRow(stringResource(R.string.account_user), s.user.name)
        InfoRow(stringResource(R.string.account_server), s.server.toString())
        s.serverInfo.version?.let { InfoRow(stringResource(R.string.account_version), it) }
        InfoRow(stringResource(R.string.account_certificate),
            s.trust.pinnedSha256?.let(CertificateTrust::display) ?: stringResource(R.string.account_certificate_system))
        OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_out)) }
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(320.dp))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
