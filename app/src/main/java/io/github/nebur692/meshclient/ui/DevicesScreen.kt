package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.DeviceFilter
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.OsFamily

/**
 * The devices, from edge to edge: no bar at the bottom (the account is the initial in the search bar), and the list runs
 * under the gesture bar. / Los equipos de borde a borde: sin barra abajo (la cuenta es la inicial del buscador) y la
 * lista pasa por debajo de la barra de gestos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(vm: AppViewModel) {
    // Worked out again only when the devices, the filter, the search or the group names change, not on every redraw
    // of the screen (a live event, the keyboard, a banner). / Se vuelve a calcular solo cuando cambian los equipos, el
    // filtro, la búsqueda o los nombres de los grupos, no en cada repintado de la pantalla.
    val view by remember(vm) { derivedStateOf {
        val all = vm.devices.values.toList()
        val shown = all.filter { d ->
            when (vm.filter) {
                DeviceFilter.All -> true
                DeviceFilter.Online -> d.isOnline
                DeviceFilter.Windows -> d.osFamily == OsFamily.Windows
                DeviceFilter.Linux -> d.osFamily == OsFamily.Linux
                DeviceFilter.Mac -> d.osFamily == OsFamily.Mac
            } && d.matches(vm.query, vm.groupNames[d.groupId])
        }
        val counts = mapOf(DeviceFilter.All to all.size, DeviceFilter.Online to all.count { it.isOnline },
            DeviceFilter.Windows to all.count { it.osFamily == OsFamily.Windows }, DeviceFilter.Linux to all.count { it.osFamily == OsFamily.Linux },
            DeviceFilter.Mac to all.count { it.osFamily == OsFamily.Mac })
        ListView(all.isEmpty(), shown.isEmpty(), counts, shown.groupBy { vm.groupNames[it.groupId] ?: it.groupId }.toSortedMap(String.CASE_INSENSITIVE_ORDER))
    } }
    val byGroup = view.byGroup
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(top = 4.dp)) {
            SearchBar(vm)
            if (vm.lost) LostBanner(vm)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun count(f: DeviceFilter) = view.counts[f] ?: 0
                // A system with no device here is not offered. / Un sistema sin equipos aquí no se ofrece.
                items(DeviceFilter.entries.filter { it == DeviceFilter.All || it == DeviceFilter.Online || count(it) > 0 || vm.filter == it }) { f ->
                    FilterChip(vm.filter == f, { vm.filter = f }, { Text(stringResource(when (f) {
                        DeviceFilter.All -> R.string.filter_all
                        DeviceFilter.Online -> R.string.filter_online
                        DeviceFilter.Windows -> R.string.filter_windows
                        DeviceFilter.Linux -> R.string.filter_linux
                        DeviceFilter.Mac -> R.string.filter_mac
                    }, count(f))) })
                }
            }
        }
        if (view.noneShown) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                // Without a search, it was the filter that left nothing. / Sin búsqueda, lo que no deja nada es el filtro.
                Text(stringResource(when { view.noDevices -> R.string.no_devices; vm.query.isBlank() -> R.string.no_match_filter; else -> R.string.no_match }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
            byGroup.forEach { (group, list) ->
                stickyHeader(key = "g:$group") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)) {
                        Text(group, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.devices_count, list.size, list.size), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Online first, then by name. / Primero los que están en línea, luego por nombre.
                items(list.sortedWith(compareBy<Device>({ !it.isOnline }, { it.name.lowercase() })), key = { it.id }) { d ->
                    DeviceRow(d) { vm.openDevice(d.id) }
                }
            }
        }
    }
}

/** The search bar is the screen's header: it says which server and holds the account. / El buscador es la cabecera. */
@Composable
private fun SearchBar(vm: AppViewModel) {
    val s = vm.session
    val host = s?.server?.toString()?.let(::hostOf).orEmpty()
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val hint = stringResource(R.string.search_in, host)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (vm.query.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear_search), Modifier.size(20.dp))
            }
            val account = stringResource(R.string.account_of, s?.user?.name.orEmpty())
            IconButton(onClick = { vm.show(Screen.Account) }, modifier = Modifier.semantics { contentDescription = account }) {
                Initial(s?.user?.name.orEmpty(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, size = 36)
            }
        }
    }
}

@Composable
private fun LostBanner(vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.connection_lost), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = vm::reconnect) { Text(stringResource(R.string.reconnect)) }
        }
    }
}

private fun Device.matches(q: String, group: String?): Boolean {
    if (q.isBlank()) return true
    val t = q.trim()
    return listOfNotNull(name, osDescription, address, description, group).any { it.contains(t, ignoreCase = true) } ||
        tags.any { it.contains(t, ignoreCase = true) } || users.any { it.contains(t, ignoreCase = true) }
}

/** What kind of machine, by MeshCentral's own icon (1 desktop, 2 and 8 laptop, 3 phone, 4 server), else by system.
 *  Qué tipo de máquina, por el icono de MeshCentral; si no, por el sistema. */
fun kindIcon(d: Device): Int = when (d.icon) {
    2, 8 -> R.drawable.ic_laptop
    3 -> R.drawable.ic_phone
    4 -> R.drawable.ic_server
    1 -> R.drawable.ic_monitor
    else -> when (d.osFamily) {
        OsFamily.Android -> R.drawable.ic_phone
        OsFamily.Mac -> R.drawable.ic_laptop
        OsFamily.Linux -> if (d.capabilities and io.github.nebur692.meshclient.core.AgentCaps.DESKTOP == 0) R.drawable.ic_server else R.drawable.ic_monitor
        else -> R.drawable.ic_monitor
    }
}

/** The device's tile, with its state as a dot on the corner. / El cuadro del equipo, con su estado como un punto en la esquina. */
@Composable
fun DeviceTile(d: Device, size: Int = 44) {
    val on = d.isOnline
    val status = LocalStatusColors.current
    Box(Modifier.size((size + 2).dp)) {
        Surface(shape = RoundedCornerShape((size / 3).dp), modifier = Modifier.size(size.dp),
            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(kindIcon(d)), null, Modifier.size((size / 2).dp)) }
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(14.dp)
            .background(if (on) status.online else status.offline, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape))
    }
}

@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit) {
    val state = stringResource(if (d.isOnline) R.string.online else R.string.offline)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${d.name}, $state" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DeviceTile(d)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(d.osDescription, d.address).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Only the ones that are on say it: a word on every row is noise. / Solo lo dicen los encendidos.
        if (d.isOnline) Text(state, style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.online)
    }
}

/** What the list shows, worked out in one go. / Lo que enseña la lista, calculado de una vez. */
private class ListView(val noDevices: Boolean, val noneShown: Boolean, val counts: Map<DeviceFilter, Int>, val byGroup: Map<String, List<Device>>)
