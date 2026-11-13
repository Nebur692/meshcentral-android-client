package io.github.nebur692.meshclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.DeviceSheet
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceDetails
import io.github.nebur692.meshclient.core.Encryption
import io.github.nebur692.meshclient.core.MeshRights
import io.github.nebur692.meshclient.core.rightsOn
import kotlinx.coroutines.launch

/** A section of the sheet, and its place in the list for the chips that jump to it. / Una sección y su sitio en la lista. */
private enum class Section(val title: Int) { Summary(R.string.sec_summary), Security(R.string.sec_security), System(R.string.sec_system),
    Hardware(R.string.sec_hardware), Network(R.string.sec_network), Notes(R.string.sec_notes), Events(R.string.sec_events) }

/**
 * Everything MeshCentral knows about a device and everything this account may do with it, in one page that scrolls: the
 * actions on top, then each section; the chips jump to them. Live: it follows the device's changes.
 * Todo lo que MeshCentral sabe de un equipo y todo lo que esta cuenta puede hacer con él, en una página que se desplaza:
 * las acciones arriba y luego cada sección; las pastillas saltan a ellas. En vivo: sigue los cambios del equipo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: AppViewModel, id: String, inPane: Boolean = false) {
    val d = vm.devices[id]
    // Removed meanwhile: back to the list. / Borrado mientras tanto: vuelta a la lista.
    if (d == null) { LaunchedEffect(Unit) { vm.show(Screen.Devices) }; return }
    val details = vm.details
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    vm.notice?.let { n ->
        val text = stringResource(n.text) + (n.error?.let { e -> "\n" + errorText(e) + " · " + e.key } ?: "")
        LaunchedEffect(n) { snack.showSnackbar(text); vm.notice = null }
    }
    val list = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val sections = buildList {
        add(Section.Summary)
        if (d.security.isKnown || details?.hardware?.volumes?.any { it.encryption != null } == true) add(Section.Security)
        add(Section.System)
        if (details?.hardware != null) add(Section.Hardware)
        if (details?.network?.isNotEmpty() == true || d.address != null) add(Section.Network)
        add(Section.Notes)
        if (details?.events?.isNotEmpty() == true) add(Section.Events)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(expandedHeight = BarHeight,
                    title = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        // Beside the list there is nothing to go back to. / Al lado de la lista no hay adónde volver.
                        if (!inPane) IconButton(onClick = { vm.show(Screen.Devices) }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                    },
                    actions = {
                        if (vm.abilities(d).edit) IconButton(onClick = { vm.sheet = DeviceSheet.Edit }) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_device))
                        }
                        IconButton(onClick = { vm.sheet = DeviceSheet.More }) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_options)) }
                    },
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)) {
                    items(sections) { s ->
                        FilterChip(false, { scope.launch { list.animateScrollToItem(2 + sections.indexOf(s)) } }, { Text(stringResource(s.title)) })
                    }
                }
                if (vm.detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Room for the keyboard over the navigation bar, which the bottom padding already counts: the notes being typed stay
        // in sight. / Sitio para el teclado, menos la barra de navegación que ya cuenta: las notas que se escriben se ven.
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
            // On a tablet or an open foldable: the actions in one row and the sections in two columns.
            // En una tableta o un plegable abierto: las acciones en una fila y las secciones en dos columnas.
            val wide = maxWidth >= 840.dp
            LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(if (wide) 2 else 1), Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(bottom = bottom + 24.dp), verticalItemSpacing = 12.dp) {
                item(key = "header", span = StaggeredGridItemSpan.FullLine) { Header(vm, d) }
                item(key = "actions", span = StaggeredGridItemSpan.FullLine) { Actions(vm, d, if (wide) 8 else 4) }
                sections.forEach { s ->
                    item(key = s.name) {
                        when (s) {
                            Section.Summary -> SummarySection(d, details)
                            Section.Security -> SecuritySection(d, details)
                            Section.System -> SystemSection(vm, d, details)
                            Section.Hardware -> HardwareSection(details!!)
                            Section.Network -> NetworkSection(d, details)
                            Section.Notes -> NotesSection(vm, d, details)
                            Section.Events -> EventsSection(details!!)
                        }
                    }
                }
            }
        }
    }
    DeviceSheets(vm, d, details, context)
}

@Composable
private fun Header(vm: AppViewModel, d: Device) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DeviceTile(d, size = 56)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val status = LocalStatusColors.current
            Text(stringResource(if (d.isOnline) R.string.online else R.string.offline) +
                (d.lastBoot?.takeIf { d.isOnline }?.let { " · " + stringResource(R.string.on_since, ago(it).replaceFirstChar { c -> c.lowercase() }) } ?: ""),
                style = MaterialTheme.typography.titleSmall, color = if (d.isOnline) status.online else status.offline)
            Text(listOfNotNull(d.osDescription, vm.groupNames[d.groupId]?.let { stringResource(R.string.in_group, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.users.isNotEmpty()) Text(stringResource(R.string.signed_in_user, d.users.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One action button of the grid. / Un botón de la cuadrícula. */
@Composable
private fun ActionButton(icon: Int, label: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit = {}) {
    // What works carries colour; what is not possible now stays grey. / Lo que funciona, con color; lo que no se puede ahora, gris.
    val bg = if (!enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer
    val fg = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier.heightIn(min = 88.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = bg, contentColor = if (enabled) fg else fg.copy(alpha = 0.6f),
            modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp)) }
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** What this account may do; what it may not is not shown. / Lo que puede hacer esta cuenta; lo que no, no sale. */
@Composable
private fun Actions(vm: AppViewModel, d: Device, columns: Int) {
    val rights = vm.session?.rightsOn(d) ?: 0
    val can = vm.abilities(d)
    fun has(r: Long) = MeshRights.has(rights, r)
    data class A(val icon: Int, val label: Int, val enabled: Boolean, val onClick: () -> Unit)
    val all = buildList {
        if (MeshRights.tool(rights, MeshRights.NO_DESKTOP)) add(A(R.drawable.ic_monitor, R.string.act_desktop, d.canOpenDesktop) { vm.openDesktop(d.id) })
        if (MeshRights.tool(rights, MeshRights.NO_TERMINAL)) add(A(R.drawable.ic_terminal, R.string.act_terminal, d.canOpenTerminal) { vm.sheet = DeviceSheet.Terminal })
        if (MeshRights.tool(rights, MeshRights.NO_FILES)) add(A(R.drawable.ic_folder, R.string.act_files, d.canOpenFiles) { vm.openFiles(d.id) })
        if (has(MeshRights.WAKE) || has(MeshRights.RESET_OFF)) add(A(R.drawable.ic_power, R.string.act_power, can.wake || can.power) { vm.sheet = DeviceSheet.Power })
        // What the website's desktop tools ask with remote control or view only; services, on Windows (as the website).
        // Lo que piden las herramientas de la web con control remoto o solo ver; los servicios, en Windows (como la web).
        if (has(MeshRights.REMOTE_CONTROL) || has(MeshRights.REMOTE_VIEW_ONLY)) add(A(R.drawable.ic_processes, R.string.act_processes, d.hasAgent) { vm.openDeviceTools(d.id, services = false) })
        if ((has(MeshRights.REMOTE_CONTROL) || has(MeshRights.REMOTE_VIEW_ONLY)) && d.isWindows) add(A(R.drawable.ic_services, R.string.act_services, d.hasAgent) { vm.openDeviceTools(d.id, services = true) })
        if (has(MeshRights.CHAT_NOTIFY) || has(MeshRights.REMOTE_CONTROL)) add(A(R.drawable.ic_chat, R.string.act_message, can.anyMessage) { vm.sheet = DeviceSheet.Message })
        if (has(MeshRights.REMOTE_COMMAND)) add(A(R.drawable.ic_run, R.string.act_run, can.runCommands) { vm.sheet = DeviceSheet.Run })
    }
    if (all.isEmpty()) return
    Column(Modifier.padding(horizontal = 8.dp)) {
        all.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { a -> ActionButton(a.icon, a.label, a.enabled, Modifier.weight(1f), a.onClick) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

// ------------------------------------------------------------------ sections / secciones

/** A titled card of rows. / Una tarjeta con título y filas. */
@Composable
private fun Card(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), Modifier.padding(start = 8.dp, top = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

enum class Tone { Good, Warn, Neutral }

/** One fact: what it is, its value, and a coloured word when it is good or worth a look. / Un dato y, si toca, una etiqueta de color. */
@Composable
private fun Line(label: String, value: String, badge: String? = null, tone: Tone = Tone.Neutral, fraction: Float? = null, first: Boolean = false) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            fraction?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = if (it > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
            }
        }
        badge?.let { Badge(it, tone) }
    }
}

@Composable
private fun Badge(text: String, tone: Tone) {
    val s = LocalStatusColors.current
    val (bg, fg) = when (tone) {
        Tone.Good -> s.goodContainer to s.onGood
        Tone.Warn -> s.warnContainer to s.onWarn
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg, contentColor = fg) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ago(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
private fun bytes(b: Long): String = Formatter.formatShortFileSize(LocalContext.current, b)

@Composable
private fun SummarySection(d: Device, details: DeviceDetails?) {
    Card(R.string.sec_connection) {
        Line(stringResource(R.string.agent), stringResource(if (d.hasAgent) R.string.agent_connected else R.string.agent_not_connected),
            stringResource(if (d.hasAgent) R.string.badge_active else R.string.badge_off), if (d.hasAgent) Tone.Good else Tone.Neutral, first = true)
        if (d.hasIntelAmt) Line(stringResource(R.string.intel_amt), stringResource(R.string.intel_amt_ready))
        details?.lastConnect?.timeMs?.let { Line(stringResource(R.string.last_contact), if (d.isOnline) stringResource(R.string.now) else ago(it)) }
        d.lastBoot?.let { Line(stringResource(R.string.last_boot), ago(it)) }
        d.agentCore?.let { Line(stringResource(R.string.agent_version), it) }
    }
}

@Composable
private fun SecuritySection(d: Device, details: DeviceDetails?) {
    val sec = d.security
    val ok = stringResource(R.string.badge_ok)
    val check = stringResource(R.string.badge_check)
    Card(R.string.sec_security) {
        var first = true
        @Composable fun line(label: String, value: String, badge: String?, tone: Tone) { Line(label, value, badge, tone, first = first); first = false }
        if (sec.antivirus.isNotEmpty()) sec.antivirus.forEach { a ->
            val good = a.enabled && a.updated
            line(stringResource(R.string.antivirus), a.product, when {
                good -> ok
                !a.enabled -> stringResource(R.string.badge_disabled)
                else -> stringResource(R.string.badge_outdated)
            }, if (good) Tone.Good else Tone.Warn)
        } else sec.antivirusOk?.let { line(stringResource(R.string.antivirus), stringResource(if (it) R.string.av_ok else R.string.av_bad), if (it) ok else check, if (it) Tone.Good else Tone.Warn) }
        sec.realTimeProtection?.let { line(stringResource(R.string.realtime), stringResource(if (it) R.string.on else R.string.off), if (it) ok else check, if (it) Tone.Good else Tone.Warn) }
        sec.firewallOk?.let { line(stringResource(R.string.firewall), stringResource(if (it) R.string.on else R.string.firewall_bad), if (it) ok else check, if (it) Tone.Good else Tone.Warn) }
        sec.updatesOk?.let { line(stringResource(R.string.updates), stringResource(if (it) R.string.updates_ok else R.string.updates_bad), if (it) ok else check, if (it) Tone.Good else Tone.Warn) }
        if (sec.pendingRestart.isNotEmpty()) line(stringResource(R.string.pending_restart), sec.pendingRestart.joinToString(", "), stringResource(R.string.badge_pending), Tone.Warn)
        details?.hardware?.volumes?.filter { it.encryption != null }?.forEach { v ->
            val e = v.encryption!!
            line(stringResource(R.string.bitlocker_of, v.name), stringResource(when (e) {
                Encryption.Encrypted -> R.string.enc_encrypted
                Encryption.Encrypting -> R.string.enc_encrypting
                Encryption.Decrypting -> R.string.enc_decrypting
                Encryption.Paused -> R.string.enc_paused
                Encryption.Locked -> R.string.enc_locked
                Encryption.NotEncrypted -> R.string.enc_none
            }), if (e == Encryption.Encrypted) ok else null, if (e == Encryption.Encrypted) Tone.Good else Tone.Neutral)
        }
    }
}

@Composable
private fun SystemSection(vm: AppViewModel, d: Device, details: DeviceDetails?) {
    Card(R.string.sec_system) {
        Line(stringResource(R.string.system), details?.operatingSystem ?: d.osDescription ?: stringResource(R.string.unknown), first = true)
        val locked = stringResource(R.string.locked)
        if (d.users.isNotEmpty()) Line(stringResource(R.string.users_signed_in), d.users.joinToString("\n") { u -> if (u in d.lockedUsers) "$u ($locked)" else u })
        Line(stringResource(R.string.group), vm.groupNames[d.groupId] ?: d.groupId)
        d.description?.takeIf(String::isNotBlank)?.let { Line(stringResource(R.string.description), it) }
        if (d.tags.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tags), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { d.tags.forEach { Badge(it, Tone.Neutral) } }
            }
        }
    }
}

@Composable
private fun HardwareSection(details: DeviceDetails) {
    val hw = details.hardware ?: return
    Card(R.string.sec_hardware) {
        var first = true
        @Composable fun line(label: String, value: String, fraction: Float? = null) { Line(label, value, fraction = fraction, first = first); first = false }
        listOfNotNull(hw.maker, hw.model).joinToString(" ").ifEmpty { null }?.let { line(stringResource(R.string.model), it) }
        hw.cpu?.let { line(stringResource(R.string.cpu), it) }
        if (hw.totalMemory > 0) {
            val modules = hw.memory.groupBy { listOfNotNull(it.type, it.speed).joinToString(" ") to it.bytes }
                .map { (k, v) -> "${v.size} × ${bytes(k.second)}" + (k.first.takeIf(String::isNotEmpty)?.let { " $it" } ?: "") }
            line(stringResource(R.string.memory), bytes(hw.totalMemory) + (modules.takeIf { hw.memory.size > 1 || hw.memory.firstOrNull()?.type != null }
                ?.joinToString(", ", " · ") ?: ""))
        }
        hw.graphics.forEach { line(stringResource(R.string.graphics), it) }
        hw.volumes.forEach { v ->
            line(stringResource(R.string.volume_of, v.name), stringResource(R.string.free_of, bytes(v.freeBytes), bytes(v.bytes)) +
                (v.fileSystem?.let { " · $it" } ?: ""), v.usedFraction)
        }
        hw.disks.forEach { k -> line(stringResource(R.string.disk), k.name + " · " + (k.bytes?.let { bytes(it) } ?: k.sizeText.orEmpty())) }
        hw.board?.let { line(stringResource(R.string.board), it) }
        hw.bios?.let { line(stringResource(R.string.bios), it) }
        hw.serial?.let { line(stringResource(R.string.serial), it) }
    }
}

@Composable
private fun NetworkSection(d: Device, details: DeviceDetails?) {
    Card(R.string.sec_network) {
        var first = true
        @Composable fun line(label: String, value: String, badge: String? = null, tone: Tone = Tone.Neutral) { Line(label, value, badge, tone, first = first); first = false }
        d.address?.let { line(stringResource(R.string.address_seen), it) }
        details?.lastConnect?.address?.takeIf { it != d.address }?.let { line(stringResource(R.string.address_public), it) }
        val nics = details?.network.orEmpty().filter { n -> n.addresses.any { !it.address.startsWith("127.") && it.address != "::1" } || n.mac != null }
        nics.sortedBy { !it.isUp }.forEach { n ->
            val v4 = n.addresses.filter { it.family.equals("IPv4", true) || (it.family == null && '.' in it.address) }.map { it.address }
            val shown = (v4.ifEmpty { n.addresses.map { it.address } }).filter { !it.startsWith("127.") && it != "::1" }
            line(n.name, (shown + listOfNotNull(n.mac?.let { "MAC $it" })).joinToString("\n").ifEmpty { "—" },
                if (n.status != null) stringResource(if (n.isUp) R.string.badge_up else R.string.badge_down) else null,
                if (n.isUp) Tone.Good else Tone.Neutral)
        }
    }
}

@Composable
private fun NotesSection(vm: AppViewModel, d: Device, details: DeviceDetails?) {
    val canWrite = vm.abilities(d).notes
    val saved = details?.notes.orEmpty()
    var text by rememberSaveable(d.id, saved) { mutableStateOf(saved) }
    Card(R.string.sec_notes) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canWrite) {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.notes_label)) }, shape = RoundedCornerShape(12.dp))
                if (text != saved) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { text = saved }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { vm.saveNotes(d, text) }, enabled = !vm.actionBusy) { Text(stringResource(R.string.save)) }
                }
            } else Text(saved.ifEmpty { stringResource(R.string.no_notes) }, style = MaterialTheme.typography.bodyLarge,
                color = if (saved.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        }
    }
}

@Composable
private fun EventsSection(details: DeviceDetails) {
    var all by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val shown = if (all) details.events else details.events.take(6)
    Card(R.string.sec_events) {
        shown.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(e.timeMs?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) } ?: "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.message.ifEmpty { e.action.orEmpty() }, style = MaterialTheme.typography.bodyMedium)
                    e.user?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (details.events.size > shown.size || all) TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(if (all) stringResource(R.string.show_less) else pluralStringResource(R.plurals.show_all_events, details.events.size, details.events.size))
        }
    }
}

/** The device's data as text, to paste in a ticket. / Los datos del equipo como texto, para pegar en un ticket. */
private fun summary(context: Context, d: Device, details: DeviceDetails?, group: String?): String = buildList {
    add(d.name)
    (details?.operatingSystem ?: d.osDescription)?.let(::add)
    group?.let { add("${context.getString(R.string.group)}: $it") }
    d.address?.let { add("IP: $it") }
    details?.network?.mapNotNull { it.mac }?.distinct()?.forEach { add("MAC: $it") }
    details?.hardware?.let { hw ->
        listOfNotNull(hw.maker, hw.model).joinToString(" ").ifEmpty { null }?.let(::add)
        hw.cpu?.let(::add)
        hw.serial?.let { add("${context.getString(R.string.serial)}: $it") }
    }
    if (d.users.isNotEmpty()) add("${context.getString(R.string.users_signed_in)}: ${d.users.joinToString(", ")}")
}.joinToString("\n")

/** The panels and confirmations of the sheet. / Los paneles y confirmaciones de la ficha. */
@Composable
private fun DeviceSheets(vm: AppViewModel, d: Device, details: DeviceDetails?, context: Context) {
    when (vm.sheet) {
        DeviceSheet.Terminal -> TerminalSheet(vm, d)
        DeviceSheet.Power -> PowerSheet(vm, d)
        DeviceSheet.More -> MoreSheet(vm, d) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(d.name, summary(context, d, details, vm.groupNames[d.groupId])))
            vm.sheet = null
        }
        DeviceSheet.Pin -> PinSheet(vm, d)
        DeviceSheet.Message -> MessageSheet(vm, d)
        DeviceSheet.Run -> RunSheet(vm, d)
        DeviceSheet.Edit -> EditSheet(vm, d)
        DeviceSheet.Move -> MoveSheet(vm, d)
        DeviceSheet.Ssh -> SshSheet(vm, d)
        DeviceSheet.Port -> PortSheet(vm, d)
        DeviceSheet.ConfirmRestart -> Confirm(vm, R.string.confirm_restart_title, if (d.users.isEmpty()) R.string.confirm_restart_text else R.string.confirm_restart_users,
            whoOrName(d), R.string.restart) { vm.power(d, io.github.nebur692.meshclient.core.PowerAction.Restart) }
        DeviceSheet.ConfirmPowerOff -> Confirm(vm, R.string.confirm_off_title, if (d.users.isEmpty()) R.string.confirm_off_text else R.string.confirm_off_users,
            whoOrName(d), R.string.power_off) { vm.power(d, io.github.nebur692.meshclient.core.PowerAction.PowerOff) }
        DeviceSheet.ConfirmUninstall -> Confirm(vm, R.string.confirm_uninstall_title, R.string.confirm_uninstall_text, d.name, R.string.uninstall) { vm.uninstall(d) }
        DeviceSheet.ConfirmRemove -> Confirm(vm, R.string.confirm_remove_title, R.string.confirm_remove_text, d.name, R.string.remove) { vm.remove(d) }
        null -> Unit
    }
}

/** Who is signed in, which the warning names; with nobody, the device. / Quién tiene sesión; si no hay nadie, el equipo. */
private fun whoOrName(d: Device) = d.users.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: d.name

@Composable
private fun Confirm(vm: AppViewModel, title: Int, text: Int, arg: String, action: Int, onYes: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.sheet = null },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text, arg)) },
        confirmButton = { TextButton(onClick = onYes, enabled = !vm.actionBusy) { Text(stringResource(action), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { vm.sheet = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SheetOption(icon: Int, label: String, hint: String?, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val ink = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(painterResource(icon), null, tint = if (enabled) ink else ink.copy(alpha = 0.4f))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) ink else ink.copy(alpha = 0.4f))
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** For the sheets' text fields. / Para los campos de los paneles. */
@Composable
fun SheetButtons(confirm: String, enabled: Boolean, busy: Boolean, onCancel: () -> Unit, dismiss: Int = R.string.cancel, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        TextButton(onClick = onCancel) { Text(stringResource(dismiss)) }
        androidx.compose.material3.Button(onClick = onConfirm, enabled = enabled && !busy) { Text(confirm) }
    }
}

@Composable
fun OutlinedChoice(selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier,
        colors = if (selected) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else
            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()) { Text(label, maxLines = 1) }
}
