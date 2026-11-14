package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.AgentInstallMode
import io.github.nebur692.meshclient.core.DeviceGroup
import io.github.nebur692.meshclient.core.DeviceGroupKind
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshRights
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.saveGroupSettings
import io.github.nebur692.meshclient.core.NewUser
import io.github.nebur692.meshclient.core.ServerEvent
import io.github.nebur692.meshclient.core.UserGroup
import io.github.nebur692.meshclient.core.UserInfo
import io.github.nebur692.meshclient.core.addGroupMembers
import io.github.nebur692.meshclient.core.addUsersToUserGroup
import io.github.nebur692.meshclient.core.changeGroupMemberRights
import io.github.nebur692.meshclient.core.createGroup
import io.github.nebur692.meshclient.core.createInviteLink
import io.github.nebur692.meshclient.core.createUser
import io.github.nebur692.meshclient.core.createUserGroup
import io.github.nebur692.meshclient.core.deleteGroup
import io.github.nebur692.meshclient.core.deleteUser
import io.github.nebur692.meshclient.core.deleteUserGroup
import io.github.nebur692.meshclient.core.editGroup
import io.github.nebur692.meshclient.core.editUser
import io.github.nebur692.meshclient.core.editUserGroup
import io.github.nebur692.meshclient.core.removeGroupMember
import io.github.nebur692.meshclient.core.removeUserFromUserGroup
import io.github.nebur692.meshclient.core.sendNotification
import io.github.nebur692.meshclient.core.serverEvents
import io.github.nebur692.meshclient.core.setLocked
import io.github.nebur692.meshclient.core.setUserPassword
import io.github.nebur692.meshclient.core.userEvents
import io.github.nebur692.meshclient.core.userGroups
import io.github.nebur692.meshclient.core.users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The administration's lists. / Las listas de la administración. */
enum class AdminPage { Events, Groups, Users, UserGroups }

/**
 * Ready-made rights for a member of a device group, as a person thinks of them; anything else shows as "custom".
 * Permisos ya hechos para un miembro de un grupo de equipos, como los piensa una persona; lo demás sale como «a medida».
 */
enum class MemberRights(val rights: Long, val label: Int, val hint: Int) {
    Administrator(MeshRights.FULL, R.string.rights_admin, R.string.rights_admin_hint),
    Technician(MeshRights.MANAGE_DEVICES or MeshRights.REMOTE_CONTROL or MeshRights.AGENT_CONSOLE or MeshRights.WAKE or MeshRights.SET_NOTES or
        MeshRights.CHAT_NOTIFY or MeshRights.UNINSTALL or MeshRights.REMOTE_COMMAND or MeshRights.RESET_OFF or MeshRights.DEVICE_DETAILS,
        R.string.rights_technician, R.string.rights_technician_hint),
    RemoteControl(MeshRights.REMOTE_CONTROL or MeshRights.WAKE or MeshRights.CHAT_NOTIFY or MeshRights.DEVICE_DETAILS,
        R.string.rights_remote, R.string.rights_remote_hint),
    ViewOnly(MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW_ONLY or MeshRights.NO_TERMINAL or MeshRights.NO_FILES or MeshRights.DEVICE_DETAILS,
        R.string.rights_view, R.string.rights_view_hint);

    companion object {
        fun of(rights: Long): MemberRights? = entries.firstOrNull { it.rights == rights }
    }
}

/** How long an invitation link lasts (0 = never expires). / Cuánto dura un enlace de invitación (0 = no caduca). */
enum class InviteExpiry(val hours: Int, val label: Int) { Hour(1, R.string.invite_1h), Day(24, R.string.invite_24h), Week(168, R.string.invite_7d), Never(0, R.string.invite_never) }

/**
 * The server's administration: events, device groups, users and user groups. Each change reloads its list, so what is
 * shown is what the server has. It asks for the current connection each time (after a reconnection the old one is closed).
 * La administración del servidor: sucesos, grupos de equipos, usuarios y grupos de usuarios. Cada cambio recarga su lista,
 * así que lo que se ve es lo que tiene el servidor. Pide la conexión de ahora cada vez.
 */
class AdminModel(private val session: () -> MeshSession?, private val scope: CoroutineScope) {
    var events by mutableStateOf<List<ServerEvent>?>(null)
        private set
    var groups by mutableStateOf<List<DeviceGroup>?>(null)
        private set
    var users by mutableStateOf<List<UserInfo>?>(null)
        private set
    var userGroups by mutableStateOf<List<UserGroup>?>(null)
        private set
    /** One account's events, for its page. / Los sucesos de una cuenta, para su página. */
    var eventsOfUser by mutableStateOf<Pair<String, List<ServerEvent>>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
    var notice by mutableStateOf<Notice?>(null)
    /** The last invitation link made, to share. / El último enlace de invitación, para compartir. */
    var inviteLink by mutableStateOf<String?>(null)

    private var job: Job? = null

    private fun current(key: String) = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, key, "no session")

    fun load(page: AdminPage) {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val s = current("servidor/abrir-detalle/conexion-cortada")
                when (page) {
                    AdminPage.Events -> events = s.serverEvents(300)
                    AdminPage.Groups -> groups = s.deviceGroups().filter { it.kind == DeviceGroupKind.Agents || it.kind == DeviceGroupKind.LocalDevices || it.kind == DeviceGroupKind.IntelAmtOnly }
                        .sortedBy { it.name.lowercase() }
                    AdminPage.Users -> users = s.users()
                    AdminPage.UserGroups -> userGroups = s.userGroups()
                }
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            } finally {
                loading = false
            }
        }
    }

    /** Runs a change and reloads [reload]; [ok] is shown when it went well. / Hace un cambio y recarga la lista. */
    private fun change(ok: Int, reload: AdminPage, then: () -> Unit = {}, block: suspend (MeshSession) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block(current("servidor/abrir-detalle/conexion-cortada"))
                notice = Notice(ok)
                then()
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                busy = false
                load(reload)
            }
        }
    }

    // ---- groups / grupos
    fun createGroup(name: String, description: String) = change(R.string.done_group_created, AdminPage.Groups) { it.createGroup(name, description.ifBlank { null }) }
    fun editGroup(g: DeviceGroup, name: String, description: String) = change(R.string.done_saved, AdminPage.Groups) { it.editGroup(g, name, description) }
    fun saveGroupSettings(g: DeviceGroup, settings: io.github.nebur692.meshclient.core.GroupSettings, then: () -> Unit) =
        change(R.string.done_saved, AdminPage.Groups, then) { it.saveGroupSettings(g, settings) }
    fun deleteGroup(g: DeviceGroup, then: () -> Unit) = change(R.string.done_group_deleted, AdminPage.Groups, then) { it.deleteGroup(g) }
    fun addMember(g: DeviceGroup, userName: String, rights: MemberRights) =
        change(R.string.done_member_added, AdminPage.Groups) { it.addGroupMembers(g, userName.split(',', ' ', ';'), rights.rights) }
    fun changeMember(g: DeviceGroup, memberId: String, rights: MemberRights) =
        change(R.string.done_saved, AdminPage.Groups) { it.changeGroupMemberRights(g, memberId, rights.rights) }
    fun removeMember(g: DeviceGroup, memberId: String) = change(R.string.done_member_removed, AdminPage.Groups) { it.removeGroupMember(g, memberId) }
    fun invite(g: DeviceGroup, expiry: InviteExpiry) = change(R.string.done_invite, AdminPage.Groups) {
        inviteLink = it.createInviteLink(g, expiry.hours, AgentInstallMode.BackgroundAndInteractive)
    }

    // ---- users / usuarios
    fun createUser(n: NewUser) = change(R.string.done_user_created, AdminPage.Users) { it.createUser(n) }
    fun editUser(u: UserInfo, email: String, realName: String) = change(R.string.done_saved, AdminPage.Users) { it.editUser(u, email, realName, null) }
    fun setLocked(u: UserInfo, locked: Boolean) = change(if (locked) R.string.done_locked else R.string.done_unlocked, AdminPage.Users) { it.setLocked(u, locked) }
    fun setPassword(u: UserInfo, password: String, mustChange: Boolean, removeTwoFactor: Boolean) =
        change(R.string.done_password, AdminPage.Users) { it.setUserPassword(u, password, mustChange, removeTwoFactor) }
    fun deleteUser(u: UserInfo, then: () -> Unit) = change(R.string.done_user_deleted, AdminPage.Users, then) { it.deleteUser(u) }
    fun loadEventsOf(u: UserInfo) {
        scope.launch {
            try {
                eventsOfUser = u.id to current("servidor/abrir-detalle/conexion-cortada").userEvents(u, 100)
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    // ---- user groups / grupos de usuarios
    fun createUserGroup(name: String, description: String) = change(R.string.done_group_created, AdminPage.UserGroups) { it.createUserGroup(name, description) }
    fun editUserGroup(g: UserGroup, name: String, description: String) = change(R.string.done_saved, AdminPage.UserGroups) { it.editUserGroup(g, name, description) }
    fun deleteUserGroup(g: UserGroup, then: () -> Unit) = change(R.string.done_group_deleted, AdminPage.UserGroups, then) { it.deleteUserGroup(g) }
    fun addToUserGroup(g: UserGroup, names: String) = change(R.string.done_member_added, AdminPage.UserGroups) { it.addUsersToUserGroup(g, names.split(',', ' ', ';')) }
    fun removeFromUserGroup(g: UserGroup, userId: String) = change(R.string.done_member_removed, AdminPage.UserGroups) { it.removeUserFromUserGroup(g, userId) }

    // ---- notification / aviso
    fun notifyEveryone(text: String) = change(R.string.done_notified, AdminPage.Events) { it.sendNotification(text) }
}
