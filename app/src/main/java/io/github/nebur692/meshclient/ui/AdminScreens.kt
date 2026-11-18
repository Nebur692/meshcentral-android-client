package io.github.nebur692.meshclient.ui

import io.github.nebur692.meshclient.core.setGroupNotify
import io.github.nebur692.meshclient.core.notifyOn
import io.github.nebur692.meshclient.core.MeshRights
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.content.Intent
import io.github.nebur692.meshclient.core.UserCompletion
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AdminModel
import io.github.nebur692.meshclient.AdminPage
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.InviteExpiry
import io.github.nebur692.meshclient.MemberRights
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.DeviceGroup
import io.github.nebur692.meshclient.core.DeviceGroupKind
import io.github.nebur692.meshclient.core.LocalDeviceOs
import io.github.nebur692.meshclient.core.canAddLocalDevice
import io.github.nebur692.meshclient.core.validLocalHost
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.NewUser
import io.github.nebur692.meshclient.core.ServerEvent
import io.github.nebur692.meshclient.core.UserGroup
import io.github.nebur692.meshclient.core.UserInfo
import io.github.nebur692.meshclient.core.canCreateGroups
import io.github.nebur692.meshclient.core.canEditUser
import io.github.nebur692.meshclient.core.canManageUserGroups
import io.github.nebur692.meshclient.core.groupAbilitiesOn
import java.text.DateFormat
import java.util.Date

/**
 * The server's administration: a list (events, device groups, users, user groups) and, with an [id], one of them.
 * La administración del servidor: una lista y, con un [id], uno de ellos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: AppViewModel, page: AdminPage, id: String?) {
    val a = vm.admin ?: return
    val s = vm.session ?: return
    val back = { vm.show(if (id != null) Screen.Admin(page) else Screen.Account) }
    BackHandler(onBack = back)
    LaunchedEffect(page) { a.load(page) }
    var creating by remember { mutableStateOf(false) }
    val title = when (page) {
        AdminPage.Events -> R.string.admin_events
        AdminPage.Groups -> R.string.admin_groups
        AdminPage.Users -> R.string.admin_users
        AdminPage.UserGroups -> R.string.admin_user_groups
    }
    val canCreate = id == null && when (page) {
        AdminPage.Groups -> s.canCreateGroups
        AdminPage.Users -> true
        AdminPage.UserGroups -> s.canManageUserGroups
        AdminPage.Events -> false
    }
        // One item: its own name on top. / Un elemento: su nombre arriba.
        val item = id?.let { i ->
            when (page) {
                AdminPage.Groups -> a.groups?.firstOrNull { it.id == i }?.name
                AdminPage.Users -> a.users?.firstOrNull { it.id == i }?.name
                AdminPage.UserGroups -> a.userGroups?.firstOrNull { it.id == i }?.name
                AdminPage.Events -> null
            }
        }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(expandedHeight = BarHeight,
                    title = { Text(item ?: stringResource(title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                    actions = { IconButton(onClick = { a.load(page) }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) } },
                )
                if (a.loading || a.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
                a.error?.let { Banner(errorText(it), true) }
                a.notice?.let { n ->
                    LaunchedEffect(n) { kotlinx.coroutines.delay(4000); if (a.notice === n) a.notice = null }
                    Banner(n.error?.let { stringResource(n.text) + " " + errorText(it) } ?: stringResource(n.text), n.error != null)
                }
            }
        },
        floatingActionButton = {
            // Above the navigation bar: drawn over it, it was seen but missing for accessibility (TalkBack, uiautomator).
            // Encima de la barra de navegación: dibujado sobre ella se veía, pero no existía para la accesibilidad.
            val label = stringResource(if (page == AdminPage.Users) R.string.admin_new_user else R.string.admin_new_group)
            // Its text, said out loud: the animated label reached accessibility with no size. / Su texto, dicho en voz alta.
            if (canCreate) ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.navigationBarsPadding().semantics { contentDescription = label },
                icon = { Icon(painterResource(if (page == AdminPage.Users) R.drawable.ic_person else R.drawable.ic_folder_add), null) },
                text = { Text(label) })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentPadding = PaddingValues(bottom = bottom + 96.dp)
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                AdminPage.Events -> EventList(vm, a.events, contentPadding)
                AdminPage.Groups -> {
                    val g = id?.let { i -> a.groups?.firstOrNull { it.id == i } }
                    if (id == null) GroupList(vm, a, contentPadding) else if (g != null) GroupPage(vm, a, s, g, back)
                }
                AdminPage.Users -> {
                    val u = id?.let { i -> a.users?.firstOrNull { it.id == i } }
                    if (id == null) UserList(vm, a, contentPadding) else if (u != null) UserPage(a, s, u, back)
                }
                AdminPage.UserGroups -> {
                    val g = id?.let { i -> a.userGroups?.firstOrNull { it.id == i } }
                    if (id == null) UserGroupList(vm, a, contentPadding) else if (g != null) UserGroupPage(a, s, g, back)
                }
            }
        }
    }
    if (creating) when (page) {
        AdminPage.Users -> NewUserDialog({ creating = false }) { a.createUser(it); creating = false }
        AdminPage.Groups -> NewGroupDialog(vm, { creating = false }) { n, d, local, relay ->
            if (local) a.createLocalGroup(n, d, relay) else a.createGroup(n, d); creating = false
        }
        AdminPage.UserGroups -> NameDialog(R.string.admin_new_group, "", "", { creating = false }) { n, d -> a.createUserGroup(n, d); creating = false }
        AdminPage.Events -> Unit
    }
}

@Composable
private fun Banner(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Row2(title: String, subtitle: String?, badge: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        badge?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun Empty(text: Int) {
    Text(stringResource(text), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun whenText(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }.orEmpty()

// ------------------------------------------------------------------ events / sucesos

@Composable
private fun EventList(vm: AppViewModel, events: List<ServerEvent>?, padding: PaddingValues) {
    if (events == null) return
    if (events.isEmpty()) return Empty(R.string.admin_no_events)
    LazyColumn(contentPadding = padding) {
        items(events) { e ->
            val device = e.nodeId?.let { vm.devices[it]?.name }
            Row2(e.message, listOfNotNull(whenText(e.timeMs), e.userName, device).joinToString(" · "))
        }
    }
}

// ------------------------------------------------------------------ device groups / grupos de equipos

@Composable
private fun GroupList(vm: AppViewModel, a: AdminModel, padding: PaddingValues) {
    val groups = a.groups ?: return
    if (groups.isEmpty()) return Empty(R.string.admin_no_groups)
    LazyColumn(contentPadding = padding) {
        items(groups, key = { it.id }) { g ->
            val count = vm.devices.values.count { it.groupId == g.id }
            Row2(g.name, listOfNotNull(g.description, stringResource(R.string.group_kind_local).takeIf { g.kind == DeviceGroupKind.LocalDevices },
                pluralStringResource(R.plurals.admin_devices_count, count, count)).joinToString(" · ")) {
                vm.show(Screen.Admin(AdminPage.Groups, g.id))
            }
        }
    }
}

@Composable
private fun GroupPage(vm: AppViewModel, a: AdminModel, s: MeshSession, g: DeviceGroup, back: () -> Unit) {
    val can = s.groupAbilitiesOn(g)
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var member by remember { mutableStateOf<String?>(null) }
    var addingLocal by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    var agentFor by remember { mutableStateOf(false) }
    val local = g.kind == DeviceGroupKind.LocalDevices
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row2(g.name, g.description)
        // A group of devices without an agent: through which the server reaches them. / Por dónde llega el servidor.
        if (local) Row2(stringResource(R.string.group_kind_local),
            stringResource(R.string.local_reached_by, g.relayId?.let { vm.devices[it]?.name ?: it.substringAfterLast('/').take(8) } ?: stringResource(R.string.local_by_server)))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (can.edit) FilledTonalButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }
            if (can.addDevices) FilledTonalButton(onClick = { inviting = true }) { Text(stringResource(R.string.admin_invite)) }
            if (s.canAddLocalDevice(g)) FilledTonalButton(onClick = { addingLocal = true }) { Text(stringResource(R.string.local_add)) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (can.edit) OutlinedButton(onClick = { settings = true }) { Text(stringResource(R.string.group_settings)) }
            OutlinedButton(onClick = { notices = true }) { Text(stringResource(R.string.group_notices)) }
        }
        if (can.addDevices) OutlinedButton(onClick = { agentFor = true }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.agent_for_group_short))
        }
        GroupSummary(g, s.serverInfo)
        a.inviteLink?.let { link ->
            Surface(Modifier.fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.admin_invite_ready), style = MaterialTheme.typography.titleSmall)
                    Text(link, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                    }) { Text(stringResource(R.string.share)) }
                }
            }
        }
        Text(stringResource(R.string.admin_members), Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        // Accounts by their name (and email when the accounts are known), not by their id: "~oidc:…" means nothing to a person.
        // Las cuentas por su nombre (y su correo si se conocen las cuentas), no por su id: «~oidc:…» no le dice nada a nadie.
        LaunchedEffect(g.id) { a.usersForNames() }
        g.links.entries.sortedBy { memberName(g, it.key, a.users).lowercase() }.forEach { (who, rights) ->
            val name = memberName(g, who, a.users) + if (who.startsWith("ugrp/")) " · " + stringResource(R.string.admin_user_group) else ""
            val label = MemberRights.of(rights)?.let { stringResource(it.label) } ?: stringResource(R.string.rights_custom)
            Row2(name, label, onClick = if (can.manageUsers && who != s.user.id) ({ member = who }) else null)
        }
        if (can.manageUsers) OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.admin_add_member))
        }
        if (can.delete) TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.admin_delete_group), color = MaterialTheme.colorScheme.error)
        }
    }
    if (notices) GroupNoticesDialog(vm, g) { notices = false }
    if (agentFor) AgentForGroupDialog(vm, g) { agentFor = false }
    if (editing) NameDialog(R.string.edit, g.name, g.description.orEmpty(), { editing = false }) { n, d -> a.editGroup(g, n, d); editing = false }
    if (settings) GroupSettingsDialog(g, s.serverInfo, a.busy, { settings = false }) { new -> a.saveGroupSettings(g, new) { settings = false } }
    if (adding) { LaunchedEffect(Unit) { a.usersForNames() }; MemberDialog(null, null, { adding = false }, onRemove = null, users = a.users) { name, r -> a.addMember(g, name, r); adding = false } }
    member?.let { who ->
        MemberDialog(memberName(g, who, a.users), g.links[who] ?: 0L, { member = null }, onRemove = { a.removeMember(g, who); member = null }) { _, r ->
            a.changeMember(g, who, r); member = null
        }
    }
    if (addingLocal) AddLocalDeviceDialog(a.busy, { addingLocal = false }) { n, h, os -> a.addLocalDevice(g, n, h, os) { addingLocal = false } }
    if (inviting) ChoiceDialog(R.string.admin_invite, InviteExpiry.entries.map { stringResource(it.label) }, { inviting = false }) { i ->
        a.invite(g, InviteExpiry.entries[i]); inviting = false
    }
    if (deleting) ConfirmDialog(stringResource(R.string.admin_delete_group), stringResource(R.string.admin_delete_group_hint, g.name), { deleting = false }) {
        a.deleteGroup(g, back); deleting = false
    }
}

/** Each right of a group member, allowed or limited, as the website's and the Windows app's dialog. / Cada permiso de un miembro. */
private val allowedRights = listOf(
    0x1L to R.string.right_edit_group, 0x2L to R.string.right_manage_users, 0x4L to R.string.right_manage_devices,
    0x8L to R.string.right_remote_control, 0x10L to R.string.right_agent_console, 0x20L to R.string.right_server_files,
    0x40L to R.string.right_wake, 0x80L to R.string.right_set_notes, 0x100000L to R.string.right_device_details,
    0x4000L to R.string.right_chat_notify, 0x8000L to R.string.right_uninstall, 0x20000L to R.string.right_remote_command,
    0x40000L to R.string.right_reset_off, 0x80000L to R.string.right_guest_sharing, 0x200000L to R.string.right_relay,
)
private val limitRights = listOf(
    0x100L to R.string.right_view_only, 0x1000L to R.string.right_limited_input, 0x10000L to R.string.right_no_desktop,
    0x200L to R.string.right_no_terminal, 0x400L to R.string.right_no_files, 0x800L to R.string.right_no_amt, 0x2000L to R.string.right_limit_events,
)

/**
 * A member's rights: ready-made choices, or right by right; [name] null means a new member by user name.
 * Los permisos de un miembro: ya hechos, o permiso a permiso; [name] null es un miembro nuevo por su nombre de usuario.
 */
@Composable
private fun MemberDialog(name: String?, current: Long?, dismiss: () -> Unit, onRemove: (() -> Unit)?, users: List<UserInfo>? = null, onSave: (String, Long) -> Unit) {
    var userName by rememberSaveable { mutableStateOf("") }
    var rights by rememberSaveable { mutableStateOf(current ?: MemberRights.RemoteControl.rights) }
    var oneByOne by rememberSaveable { mutableStateOf(current != null && MemberRights.of(current) == null) }
    val full = rights == MeshRights.FULL
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(name ?: stringResource(R.string.admin_add_member)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (name == null) UserNamesField(userName, { userName = it }, users)
                MemberRights.entries.forEach { r ->
                    Row(Modifier.fillMaxWidth().selectable(rights == r.rights, role = Role.RadioButton) { rights = r.rights }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(rights == r.rights, null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(r.label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(r.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { oneByOne = !oneByOne }) {
                    Text(stringResource(if (oneByOne) R.string.rights_hide_each else R.string.rights_each))
                }
                if (oneByOne) {
                    @Composable fun Box(bit: Long, label: Int, enabled: Boolean) {
                        val on = full || rights and bit != 0L
                        Row(Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Checkbox) { rights = if (it) rights or bit else rights and bit.inv() }
                            .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null, enabled = enabled)
                            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth().toggleable(full, role = Role.Checkbox) { rights = if (it) MeshRights.FULL else 0L }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(full, null)
                        Text(stringResource(R.string.right_full), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.rights_allowed), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    allowedRights.forEach { (bit, label) -> Box(bit, label, !full) }
                    Text(stringResource(R.string.rights_limits), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    limitRights.forEach { (bit, label) -> Box(bit, label, !full) }
                }
                onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.admin_remove_member), color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(userName, rights) }, enabled = name != null || userName.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** This account's notices for any device of the group, saved on the server. / Los avisos de la cuenta para el grupo. */
@Composable
private fun GroupNoticesDialog(vm: AppViewModel, g: DeviceGroup, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<io.github.nebur692.meshclient.UiError?>(null) }
    NoticesDialog(stringResource(R.string.notices_group, g.name), vm.session?.notifyOn(g.id) ?: 0, busy, error, onDone) { wanted ->
        val s = vm.session ?: return@NoticesDialog
        busy = true; error = null
        scope.launch {
            try { s.setGroupNotify(g, wanted); onDone() }
            catch (e: io.github.nebur692.meshclient.core.MeshClientException) { error = io.github.nebur692.meshclient.UiError(e.failure, e.key, e.httpStatus) }
            finally { busy = false }
        }
    }
}

// ------------------------------------------------------------------ users / usuarios

@Composable
private fun UserList(vm: AppViewModel, a: AdminModel, padding: PaddingValues) {
    val users = a.users ?: return
    LazyColumn(contentPadding = padding) {
        items(users, key = { it.id }) { u ->
            val badge = when {
                u.isFullAdministrator -> stringResource(R.string.admin_badge_admin)
                u.isLocked -> stringResource(R.string.admin_badge_locked)
                else -> null
            }
            Row2(u.name, listOfNotNull(u.realName, u.email).joinToString(" · "), badge) { vm.show(Screen.Admin(AdminPage.Users, u.id)) }
        }
    }
}

@Composable
private fun UserPage(a: AdminModel, s: MeshSession, u: UserInfo, back: () -> Unit) {
    val can = s.canEditUser(u) && u.id != s.user.id
    var editing by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row2(u.name, listOfNotNull(u.realName, u.email).joinToString(" · "))
        Row2(stringResource(R.string.admin_last_login), u.lastLoginSec?.let { whenText(it * 1000) } ?: stringResource(R.string.admin_never))
        if (u.hasTwoFactor) Row2(stringResource(R.string.admin_two_factor), stringResource(R.string.admin_two_factor_on))
        if (can) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }
                FilledTonalButton(onClick = { password = true }) { Text(stringResource(R.string.admin_set_password)) }
            }
            OutlinedButton(onClick = { a.setLocked(u, !u.isLocked) }, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(if (u.isLocked) R.string.admin_unlock else R.string.admin_lock))
            }
        }
        Text(stringResource(R.string.admin_events), Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        LaunchedEffect(u.id) { a.loadEventsOf(u) }
        val events = a.eventsOfUser?.takeIf { it.first == u.id }?.second
        if (events != null && events.isEmpty()) Empty(R.string.admin_no_events)
        events?.take(50)?.forEach { e -> Row2(e.message, whenText(e.timeMs)) }
        if (can) TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.admin_delete_user), color = MaterialTheme.colorScheme.error)
        }
    }
    if (editing) {
        var email by rememberSaveable { mutableStateOf(u.email.orEmpty()) }
        var real by rememberSaveable { mutableStateOf(u.realName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(u.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(real, { real = it }, label = { Text(stringResource(R.string.admin_real_name)) }, singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.admin_email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
            },
            confirmButton = { TextButton(onClick = { a.editUser(u, email, real); editing = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (password) {
        var pass by remember { mutableStateOf("") }
        var mustChange by rememberSaveable { mutableStateOf(true) }
        var removeTwoFactor by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { password = false },
            title = { Text(stringResource(R.string.admin_set_password)) },
            text = {
                Column {
                    OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Check(stringResource(R.string.admin_must_change), mustChange) { mustChange = it }
                    if (u.hasTwoFactor) Check(stringResource(R.string.admin_remove_two_factor), removeTwoFactor) { removeTwoFactor = it }
                }
            },
            confirmButton = { TextButton(onClick = { a.setPassword(u, pass, mustChange, removeTwoFactor); password = false }, enabled = pass.isNotEmpty()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { password = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) ConfirmDialog(stringResource(R.string.admin_delete_user), stringResource(R.string.admin_delete_user_hint, u.name), { deleting = false }) {
        a.deleteUser(u, back); deleting = false
    }
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NewUserDialog(dismiss: () -> Unit, onCreate: (NewUser) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var real by rememberSaveable { mutableStateOf("") }
    var mustChange by rememberSaveable { mutableStateOf(true) }
    var verified by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.admin_new_user)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.username)) }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(real, { real = it }, label = { Text(stringResource(R.string.admin_real_name)) }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.admin_email)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Check(stringResource(R.string.admin_must_change), mustChange) { mustChange = it }
                if (email.isNotBlank()) Check(stringResource(R.string.admin_email_verified), verified) { verified = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(NewUser(name, pass, email.ifBlank { null }, real.ifBlank { null }, mustChangePassword = mustChange, emailVerified = verified && email.isNotBlank())) },
                enabled = name.isNotBlank() && pass.isNotEmpty()) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ user groups / grupos de usuarios

@Composable
private fun UserGroupList(vm: AppViewModel, a: AdminModel, padding: PaddingValues) {
    val groups = a.userGroups ?: return
    if (groups.isEmpty()) return Empty(R.string.admin_no_groups)
    LazyColumn(contentPadding = padding) {
        items(groups, key = { it.id }) { g ->
            Row2(g.name, listOfNotNull(g.description, pluralStringResource(R.plurals.admin_members_count, g.members.size, g.members.size)).joinToString(" · ")) {
                vm.show(Screen.Admin(AdminPage.UserGroups, g.id))
            }
        }
    }
}

@Composable
private fun UserGroupPage(a: AdminModel, s: MeshSession, g: UserGroup, back: () -> Unit) {
    val can = s.canManageUserGroups
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row2(g.name, g.description)
        if (g.managedElsewhere) Row2(stringResource(R.string.admin_managed_elsewhere), null)
        if (can) FilledTonalButton(onClick = { editing = true }, modifier = Modifier.padding(horizontal = 20.dp)) { Text(stringResource(R.string.edit)) }
        Text(stringResource(R.string.admin_members), Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        g.members.entries.sortedBy { it.value.lowercase() }.forEach { (id, name) ->
            Row2(name, null, onClick = if (can && !g.managedElsewhere) ({ removing = id to name }) else null)
        }
        if (can && !g.managedElsewhere) OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.admin_add_member))
        }
        if (can) TextButton(onClick = { deleting = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.admin_delete_group), color = MaterialTheme.colorScheme.error)
        }
    }
    if (editing) NameDialog(R.string.edit, g.name, g.description.orEmpty(), { editing = false }) { n, d -> a.editUserGroup(g, n, d); editing = false }
    if (adding) {
        var names by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.admin_add_member)) },
            text = { LaunchedEffect(Unit) { a.usersForNames() }; UserNamesField(names, { names = it }, a.users) },
            confirmButton = { TextButton(onClick = { a.addToUserGroup(g, names); adding = false }, enabled = names.isNotBlank()) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    removing?.let { (id, name) ->
        ConfirmDialog(stringResource(R.string.admin_remove_member), name, { removing = null }) { a.removeFromUserGroup(g, id); removing = null }
    }
    if (deleting) ConfirmDialog(stringResource(R.string.admin_delete_group), g.name, { deleting = false }) { a.deleteUserGroup(g, back); deleting = false }
}

// ------------------------------------------------------------------ common dialogs / diálogos comunes

@Composable
private fun NameDialog(title: Int, name0: String, description0: String, dismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(name0) }
    var description by rememberSaveable { mutableStateOf(description0) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(128) }, label = { Text(stringResource(R.string.admin_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it.take(1024) }, label = { Text(stringResource(R.string.admin_description)) }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A new device group: with the agent (devices install it with an invitation) or without one (machines the server reaches
 * by their address, for Windows Remote Desktop, SSH or a port), and then through which agent, as the website asks.
 * Un grupo nuevo: con agente (se instala con una invitación) o sin él (máquinas a las que llega el servidor por su
 * dirección), y entonces por qué agente, como pregunta la web.
 */
@Composable
private fun NewGroupDialog(vm: AppViewModel, dismiss: () -> Unit, onSave: (String, String, Boolean, String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var local by rememberSaveable { mutableStateOf(false) }
    var relay by rememberSaveable { mutableStateOf<String?>(null) }
    val agents = vm.devices.values.filter { it.hasAgent }.sortedBy { it.name.lowercase() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.admin_new_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(128) }, label = { Text(stringResource(R.string.admin_name)) }, singleLine = true)
                OutlinedTextField(description, { description = it.take(1024) }, label = { Text(stringResource(R.string.admin_description)) }, minLines = 2)
                Radio(stringResource(R.string.group_kind_agents), stringResource(R.string.group_kind_agents_hint), !local) { local = false }
                Radio(stringResource(R.string.group_kind_local), stringResource(R.string.group_kind_local_hint), local) { local = true }
                if (local) {
                    Text(stringResource(R.string.local_reach_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Radio(stringResource(R.string.local_by_server), stringResource(R.string.local_by_server_hint), relay == null) { relay = null }
                    agents.forEach { d -> Radio(d.name, stringResource(R.string.local_by_agent_hint), relay == d.id) { relay = d.id } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, description, local, relay.takeIf { local }) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A device without an agent: its name, the address the server reaches it at, and its system. / Un equipo sin agente. */
@Composable
private fun AddLocalDeviceDialog(busy: Boolean, dismiss: () -> Unit, onSave: (String, String, LocalDeviceOs) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var os by rememberSaveable { mutableStateOf(LocalDeviceOs.Windows) }
    val hostOk = validLocalHost(host)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.local_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(256) }, label = { Text(stringResource(R.string.device_name)) }, singleLine = true)
                OutlinedTextField(host, { host = it.trim().take(256) }, label = { Text(stringResource(R.string.local_host)) }, singleLine = true,
                    isError = host.isNotEmpty() && !hostOk, supportingText = { Text(stringResource(R.string.local_host_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text(stringResource(R.string.local_os), style = MaterialTheme.typography.titleSmall)
                LocalDeviceOs.entries.forEach { o ->
                    Radio(stringResource(when (o) { LocalDeviceOs.Windows -> R.string.os_windows; LocalDeviceOs.Linux -> R.string.os_linux; LocalDeviceOs.Mac -> R.string.os_mac }),
                        null, os == o) { os = o }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, host, os) }, enabled = name.isNotBlank() && hostOk && !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Radio(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected, null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChoiceDialog(title: Int, choices: List<String>, dismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = { Column { choices.forEachIndexed { i, c -> Row2(c, null) { onPick(i) } } } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, dismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(title, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A notification to everyone connected now. / Un aviso a todas las personas conectadas ahora. */
@Composable
fun NotifyDialog(dismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.admin_notify)) },
        text = {
            Column {
                Text(stringResource(R.string.admin_notify_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it.take(512) }, label = { Text(stringResource(R.string.message_text)) }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.send)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A box of comma-separated user names with suggestions: typing a name, an email or a user name shows the matching
 * accounts below, and taking one writes its sign-in name (UserCompletion; "~oidc:…" for single sign-on). Without the
 * accounts (no right to list them) it is a plain box, as before.
 * Una caja de nombres con comas y sugerencias: al escribir salen las cuentas que encajan, y al tomar una se escribe su
 * usuario de entrar. Sin las cuentas, una caja normal.
 */
@Composable
private fun UserNamesField(value: String, onValue: (String) -> Unit, users: List<UserInfo>?) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val matches = remember(field.text, users) { users?.let { UserCompletion.matches(it, field.text) }.orEmpty() }
    var closed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(field, { field = it; closed = false; onValue(it.text) }, label = { Text(stringResource(R.string.admin_user_names)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        DropdownMenu(expanded = matches.isNotEmpty() && !closed, onDismissRequest = { closed = true }, properties = PopupProperties(focusable = false)) {
            matches.forEach { u ->
                DropdownMenuItem(text = { Text(UserCompletion.label(u), maxLines = 2) }, onClick = {
                    val t = UserCompletion.take(field.text, u)
                    field = TextFieldValue(t, TextRange(t.length)); onValue(t)
                })
            }
        }
    }
}

/** How a member shows: the account's name and email, or the name the server keeps with it, or (last) the end of its id. / Cómo sale un miembro. */
private fun memberName(g: DeviceGroup, who: String, users: List<UserInfo>?): String =
    users?.firstOrNull { it.id == who }?.let { u ->
        val name = u.realName?.takeIf { it.isNotBlank() } ?: u.name
        listOfNotNull(name, u.email?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }).joinToString(" · ")
    } ?: g.memberNames[who] ?: who.substringAfterLast('/')
