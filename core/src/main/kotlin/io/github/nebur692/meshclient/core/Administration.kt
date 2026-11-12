package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The server's administration, ported from the Windows app (MeshServerSession.Groups/Users/Server), with what was
 * measured there on MeshCentral 1.3.1: several requests are dropped without any answer when they are not allowed
 * (editmesh, removemeshuser, changeDeviceMesh between kinds, edituser outside the rights mask, changeuserpass,
 * editusergroup), so the rights are checked first, as the website does, and those that never answer are confirmed by
 * the live event they cause.
 * La administración del servidor, portada de la app de Windows, con lo medido allí en MeshCentral 1.3.1: varias
 * peticiones se tiran sin contestar cuando no se pueden hacer, así que los permisos se comprueban antes, como la web, y
 * las que nunca contestan se confirman con el aviso en vivo que provocan.
 */

/** Server-wide rights of an account (siteadmin). / Permisos de una cuenta en todo el servidor. */
object SiteRights {
    const val SERVER_BACKUP = 1L
    const val MANAGE_USERS = 2L
    const val SERVER_RESTORE = 4L
    const val SERVER_UPDATES = 16L
    const val LOCKED = 32L
    const val NO_NEW_GROUPS = 64L
    const val NO_TOOLS = 128L
    const val MANAGE_USER_GROUPS = 256L
    const val MANAGE_RECORDINGS = 512L
    const val LOCK_SETTINGS = 1024L
    const val NO_NEW_DEVICES = 4096L
    const val FULL = 0xFFFFFFFFL

    fun has(rights: Long, r: Long) = rights == FULL || rights and r != 0L
}

/** One entry of the server's event log. / Una entrada del registro de sucesos del servidor. */
data class ServerEvent(val timeMs: Long?, val action: String?, val message: String, val userId: String?, val userName: String?, val nodeId: String?, val groupId: String?) {
    internal companion object {
        fun from(e: JsonObject) = ServerEvent(e.str("time")?.let(::parseTime) ?: e.long("time"), e.str("action"), e.str("msg").orEmpty(),
            e.str("userid"), e.str("username"), e.str("nodeid"), e.str("meshid"))
    }
}

/** A group of accounts; its members get the rights given to the group. / Un grupo de cuentas; sus miembros reciben sus permisos. */
data class UserGroup(
    val id: String, val name: String, val description: String?,
    /** Member account id → name. / Id de la cuenta miembro → nombre. */
    val members: Map<String, String> = emptyMap(),
    /** Its members come from elsewhere (single sign-on, LDAP). / Sus miembros vienen de fuera (inicio único, LDAP). */
    val managedElsewhere: Boolean = false,
) {
    internal companion object {
        fun from(id: String, o: JsonObject): UserGroup {
            val members = (o["links"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                if (k.startsWith("user/") && v is JsonObject) k to (v.str("name") ?: k.substringAfterLast('/')) else null
            }.toMap()
            return UserGroup(id, o.str("name").orEmpty(), o.str("desc"), members, o["membershipType"] != null)
        }
    }
}

/** A new account. / Una cuenta nueva. */
data class NewUser(val userName: String, val password: String, val email: String? = null, val realName: String? = null,
                   val siteRights: Long? = null, val mustChangePassword: Boolean = false)

/** What a person can do with a device group. / Lo que puede hacer con un grupo de equipos. */
data class GroupAbilities(val edit: Boolean, val manageUsers: Boolean, val delete: Boolean, val addDevices: Boolean, val receiveDevices: Boolean)

/** How the agent installs (installflags). / Cómo se instala el agente. */
enum class AgentInstallMode(val flags: Int) { BackgroundAndInteractive(0), InteractiveOnly(1), BackgroundOnly(2) }

private fun adminError(key: String, message: String, said: String? = null) =
    MeshClientException(MeshFailure.Rejected, key, message).apply { serverMessage = said }

private suspend fun MeshSession.answered(cmd: JsonObject, timeoutKey: String, lostKey: String): JsonObject {
    val id = MeshSession.newId()
    val verb = cmd.str("action")
    val full = JsonObject(cmd + mapOf("responseid" to JsonPrimitive(id), "tag" to JsonPrimitive(id)))
    return request(full, { it.str("action") == verb && (it.str("responseid") == id || it.str("tag") == id) }, timeoutKey, lostKey)
}

/** For requests the server never answers: sent, then confirmed by the live event they cause. / Confirmadas por su aviso. */
private suspend fun MeshSession.confirmedByEvent(cmd: JsonObject, isTheEvent: (JsonObject) -> Boolean, timeoutKey: String, lostKey: String): JsonObject =
    request(cmd, { it.str("action") == "event" && (it["event"] as? JsonObject)?.let(isTheEvent) == true }, timeoutKey, lostKey)

private fun names(list: List<String>) = list.map(String::trim).filter(String::isNotEmpty)

// ------------------------------------------------------------------ events / sucesos

/** The events this account may see, newest first. / Los sucesos que esta cuenta puede ver, los más nuevos primero. */
suspend fun MeshSession.serverEvents(limit: Int = 500): List<ServerEvent> {
    // Without any link the server does not answer at all. / Sin ningún enlace el servidor no contesta nada.
    if (user.links.isEmpty() && !user.isFullAdministrator) return emptyList()
    return eventsOf(buildJsonObject { put("action", "events"); put("limit", limit.coerceIn(1, 100_000)) })
}

/** What an account did, for whoever manages users. / Lo que hizo una cuenta, para quien gestiona usuarios. */
suspend fun MeshSession.userEvents(target: UserInfo, limit: Int = 500): List<ServerEvent> {
    // Without the right the server answers with this account's own events instead. / Sin el permiso contesta con los propios.
    if (!canManageUsers) throw adminError("servidor/abrir-detalle/no-autorizado", "Only an account that manages users sees another one's events.")
    return eventsOf(buildJsonObject { put("action", "events"); put("userid", target.id); put("limit", limit.coerceIn(1, 100_000)) })
}

private suspend fun MeshSession.eventsOf(cmd: JsonObject): List<ServerEvent> {
    val tag = MeshSession.newId()
    val r = request(JsonObject(cmd + ("tag" to JsonPrimitive(tag))), { it.str("action") == "events" && it.str("tag") == tag },
        "servidor/abrir-detalle/tiempo-agotado", "servidor/abrir-detalle/conexion-cortada")
    return (r["events"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map(ServerEvent::from)?.sortedByDescending { it.timeMs ?: 0 }.orEmpty()
}

/** A notification to the people connected now: everyone, or one account. / Un aviso a las personas conectadas. */
suspend fun MeshSession.sendNotification(text: String, toUserId: String? = null) {
    if (!canManageUsers) throw adminError("servidor/enviar-mensaje/no-autorizado", "Only an account that manages users sends notifications.")
    val t = text.trim()
    if (t.isEmpty() || t.length > 512) throw adminError("servidor/enviar-mensaje/dato-invalido", "The notification needs 1 to 512 characters.")
    // "notifyuser" never answers; "userbroadcast" does. / «notifyuser» nunca contesta; «userbroadcast» sí.
    val r = answered(buildJsonObject { put("action", "userbroadcast"); put("msg", t); toUserId?.let { put("userid", it) } },
        "servidor/enviar-mensaje/tiempo-agotado", "servidor/enviar-mensaje/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("servidor/enviar-mensaje/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

// ------------------------------------------------------------------ device groups / grupos de equipos

/** The server lets this account create device groups (siteadmin bit 64 forbids it). / Deja crear grupos a esta cuenta. */
val MeshSession.canCreateGroups: Boolean get() = user.isFullAdministrator || user.siteRights and SiteRights.NO_NEW_GROUPS == 0L

fun MeshSession.groupAbilitiesOn(group: DeviceGroup): GroupAbilities {
    val r = MeshRights.ofGroup(user, serverInfo, group)
    return GroupAbilities(
        edit = MeshRights.has(r, MeshRights.EDIT_GROUP),
        manageUsers = MeshRights.has(r, MeshRights.MANAGE_USERS),
        delete = r == MeshRights.FULL,
        // getMeshActions of the website: agent groups, manage devices, and no siteadmin bit 4096. / Como la web.
        addDevices = group.kind == DeviceGroupKind.Agents && MeshRights.has(r, MeshRights.MANAGE_DEVICES) &&
            (user.isFullAdministrator || user.siteRights and SiteRights.NO_NEW_DEVICES == 0L),
        receiveDevices = MeshRights.has(r, MeshRights.EDIT_GROUP) && MeshRights.has(r, MeshRights.MANAGE_DEVICES),
    )
}

/** The device can be moved into [target]: same kind of group (otherwise the server drops it), rights on both. */
fun MeshSession.canMove(device: Device, target: DeviceGroup): Boolean {
    val from = groups[device.groupId] ?: return false
    return device.groupId != target.id && from.kind == target.kind &&
        MeshRights.has(MeshRights.ofDevice(user, serverInfo, from, device), MeshRights.EDIT_GROUP) && groupAbilitiesOn(target).receiveDevices
}

private fun checkGroupText(name: String, description: String?) {
    if (name.trim().isEmpty() || name.trim().length > 128 || (description?.length ?: 0) > 1024)
        throw adminError("grupos/gestionar-grupos/dato-invalido", "The name must have 1 to 128 characters and the description up to 1024.")
}

private suspend fun MeshSession.groupRequest(cmd: JsonObject, timeoutKey: String, lostKey: String): JsonObject {
    val id = MeshSession.newId()
    val verb = cmd.str("action")
    return request(JsonObject(cmd + ("responseid" to JsonPrimitive(id))), { it.str("action") == verb && it.str("responseid") == id }, timeoutKey, lostKey)
}

/** Creates a group for devices with the agent and returns its id; its creator gets every right. / Crea un grupo y da su id. */
suspend fun MeshSession.createGroup(name: String, description: String? = null): String {
    checkGroupText(name, description)
    if (!canCreateGroups) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account has no rights to create device groups.")
    val r = groupRequest(buildJsonObject {
        put("action", "createmesh"); put("meshname", name.trim()); put("meshtype", 2)
        if (!description.isNullOrEmpty()) put("desc", description)
    }, "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    val id = r.str("meshid")
    if (r.str("result") != "ok" || id == null) throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
    return id
}

/** Changes the name and description (null = unchanged). / Cambia nombre y descripción (null = igual). */
suspend fun MeshSession.editGroup(group: DeviceGroup, name: String?, description: String?) {
    checkGroupText(name ?: group.name, description)
    if (!groupAbilitiesOn(group).edit) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account has no rights to edit this device group.")
    val r = groupRequest(buildJsonObject {
        put("action", "editmesh"); put("meshid", group.id)
        name?.let { put("meshname", it.trim()) }; description?.let { put("desc", it) }
    }, "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
}

/** Deletes the group and, with it, its devices from the server. / Borra el grupo y, con él, sus equipos del servidor. */
suspend fun MeshSession.deleteGroup(group: DeviceGroup) {
    if (!groupAbilitiesOn(group).delete) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account has no rights to delete this device group.")
    val r = groupRequest(buildJsonObject { put("action", "deletemesh"); put("meshid", group.id) },
        "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
}

private fun MeshSession.checkMemberChange(group: DeviceGroup, memberId: String?, newRights: Long?) {
    val mine = MeshRights.ofGroup(user, serverInfo, group)
    if (!MeshRights.has(mine, MeshRights.MANAGE_USERS)) throw adminError("grupos/gestionar-usuarios-grupo/no-autorizado", "This account has no rights to manage the users of this device group.")
    if (memberId == user.id) throw adminError("grupos/gestionar-usuarios-grupo/conflicto-estado", "An account cannot change its own rights.")
    // Only a group administrator touches another administrator or makes one (the server ignores it otherwise).
    // Solo un administrador del grupo toca a otro administrador o nombra uno (si no, el servidor lo ignora).
    val current = memberId?.let { group.links[it] } ?: 0L
    if (mine != MeshRights.FULL && (current == MeshRights.FULL || newRights == MeshRights.FULL))
        throw adminError("grupos/gestionar-usuarios-grupo/no-autorizado", "Only a group administrator changes a group administrator.")
}

/** Gives accounts, by user name, these rights on the group; names the server does not know are named back.
 *  Da a unas cuentas, por nombre, estos permisos; los nombres que el servidor no conoce se dicen. */
suspend fun MeshSession.addGroupMembers(group: DeviceGroup, userNames: List<String>, rights: Long): String {
    val list = names(userNames)
    if (list.isEmpty()) throw adminError("grupos/gestionar-usuarios-grupo/falta-dato", "No user name was given.")
    if (list.any { it.equals(user.name, ignoreCase = true) || "user/${serverInfo.domain}/${it.lowercase()}" == user.id })
        throw adminError("grupos/gestionar-usuarios-grupo/conflicto-estado", "An account cannot change its own rights.")
    checkMemberChange(group, null, rights)
    return addMeshUser(group, "usernames", list, rights, list)
}

/** Changes the rights of an account or user group already in the group. / Cambia los permisos de un miembro. */
suspend fun MeshSession.changeGroupMemberRights(group: DeviceGroup, memberId: String, rights: Long): String {
    checkMemberChange(group, memberId, rights)
    return addMeshUser(group, "userids", listOf(memberId), rights, null)
}

private suspend fun MeshSession.addMeshUser(group: DeviceGroup, field: String, who: List<String>, rights: Long, askedNames: List<String>?): String {
    val r = groupRequest(buildJsonObject {
        put("action", "addmeshuser"); put("meshid", group.id); put("meshadmin", rights)
        put(field, JsonArray(who.map(::JsonPrimitive)))
    }, "grupos/gestionar-usuarios-grupo/tiempo-agotado", "grupos/gestionar-usuarios-grupo/conexion-cortada")
    val said = r.str("result").orEmpty()
    // By user name, the server silently drops the names it does not know (one unknown alone: "Nothing done").
    // Por nombre, el servidor tira sin decir nada los que no conoce (uno desconocido solo: «Nothing done»).
    if (askedNames != null && (r.long("failed") ?: 0) == 0L) {
        val added = said.split(", ").filter { it.startsWith("Added user ") }.map { it.removePrefix("Added user ").lowercase() }.toSet()
        val missing = askedNames.filter { it.lowercase() !in added }
        if (missing.isNotEmpty()) throw MeshClientException(MeshFailure.NotFound, "grupos/gestionar-usuarios-grupo/no-encontrado",
            "The server does not know ${missing.joinToString()}" + if (added.isNotEmpty()) "; added: ${added.joinToString()}." else ".").apply { serverMessage = said }
    }
    if ((r.long("failed") ?: 0) > 0 || (r.long("success") ?: 0) == 0L) throw adminError("grupos/gestionar-usuarios-grupo/rechazo-remoto", "The server said: $said.", said)
    deviceGroups()
    return said
}

/** Takes an account or user group out of the group. / Saca del grupo una cuenta o grupo de usuarios. */
suspend fun MeshSession.removeGroupMember(group: DeviceGroup, memberId: String) {
    checkMemberChange(group, memberId, null)
    val r = groupRequest(buildJsonObject { put("action", "removemeshuser"); put("meshid", group.id); put("userid", memberId) },
        "grupos/gestionar-usuarios-grupo/tiempo-agotado", "grupos/gestionar-usuarios-grupo/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("grupos/gestionar-usuarios-grupo/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
}

/** Moves a device to another group (one request each: the server answers once per device). / Mueve un equipo a otro grupo. */
suspend fun MeshSession.moveDevice(device: Device, target: DeviceGroup) {
    if (!canMove(device, target)) throw if (groups[device.groupId]?.kind?.let { it != target.kind } == true)
        adminError("grupos/mover-equipos/conflicto-estado", "Devices only move between groups of the same kind.")
    else adminError("grupos/mover-equipos/no-autorizado", "This account has no rights to move this device to that group.")
    val r = groupRequest(buildJsonObject { put("action", "changeDeviceMesh"); put("nodeids", JsonArray(listOf(JsonPrimitive(device.id)))); put("meshid", target.id) },
        "grupos/mover-equipos/tiempo-agotado", "grupos/mover-equipos/conexion-cortada")
    if (r.str("result") != "ok" && r.str("result") != "Device already in correct group")
        throw adminError("grupos/mover-equipos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

/** A public link to this group's installation page, valid [hours] (0 = never expires). / Un enlace público de instalación. */
suspend fun MeshSession.createInviteLink(group: DeviceGroup, hours: Int, mode: AgentInstallMode = AgentInstallMode.BackgroundAndInteractive): String {
    if (hours !in 0..99_999) throw adminError("grupos/invitar-agente/dato-invalido", "The expiry must be 0 to 99999 hours.")
    if (!groupAbilitiesOn(group).addDevices) throw adminError("grupos/invitar-agente/no-autorizado", "This account has no rights to add devices to this device group.")
    val r = groupRequest(buildJsonObject {
        put("action", "createInviteLink"); put("meshid", group.id); put("expire", hours); put("flags", mode.flags); put("agents", 0)
    }, "grupos/invitar-agente/tiempo-agotado", "grupos/invitar-agente/conexion-cortada")
    val url = r.str("url")?.takeIf { it.isNotEmpty() } ?: throw adminError("grupos/invitar-agente/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    // On a server without a domain name the link comes as a path. / Sin nombre de dominio llega como ruta.
    return if (url.startsWith("/")) server.baseUrl.resolve(url.trimStart('/'))?.toString() ?: url else url
}

// ------------------------------------------------------------------ users / usuarios

val MeshSession.canManageUsers: Boolean get() = SiteRights.has(user.siteRights, SiteRights.MANAGE_USERS)
val MeshSession.canManageUserGroups: Boolean get() = SiteRights.has(user.siteRights, SiteRights.MANAGE_USER_GROUPS)

/** This account may change [target]: it manages users, and only a full administrator touches another one. */
fun MeshSession.canEditUser(target: UserInfo): Boolean = canManageUsers && (!target.isFullAdministrator || user.isFullAdministrator)

/**
 * The server-wide rights this account may set on [target]: a full administrator, any; someone who manages users only
 * lock, "no new groups", "no tools" and the user-group and recording rights they hold (edituser mask). Outside that the
 * server answers "ok" and changes nothing. / Fuera de eso el servidor contesta «ok» y no cambia nada.
 */
fun MeshSession.canSetSiteRights(target: UserInfo, newRights: Long): Boolean {
    if (!canEditUser(target) || target.id == user.id) return false
    if (user.isFullAdministrator) return true
    var mask = 0xFFFFFF1DL
    if (user.siteRights and SiteRights.MANAGE_USER_GROUPS != 0L) mask -= SiteRights.MANAGE_USER_GROUPS
    if (user.siteRights and SiteRights.MANAGE_RECORDINGS != 0L) mask -= SiteRights.MANAGE_RECORDINGS
    return (target.siteRights xor newRights) and mask == 0L
}

suspend fun MeshSession.users(): List<UserInfo> {
    if (!canManageUsers) throw adminError("usuarios/gestionar-usuarios/no-autorizado", "This account cannot manage users.")
    val r = answered(buildJsonObject { put("action", "users") }, "usuarios/gestionar-usuarios/tiempo-agotado", "usuarios/gestionar-usuarios/conexion-cortada")
    val list = r["users"] as? JsonArray ?: throw adminError("usuarios/gestionar-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    return list.filterIsInstance<JsonObject>().map(UserInfo::from).sortedBy { it.name.lowercase() }
}

/** Creates an account and returns its id. / Crea una cuenta y devuelve su id. */
suspend fun MeshSession.createUser(n: NewUser): String {
    if (!canManageUsers) throw adminError("usuarios/gestionar-usuarios/no-autorizado", "This account cannot manage users.")
    val name = n.userName.trim()
    if (name.isEmpty() || name.length > 64 || name.startsWith("~") || '/' in name || name.any(Char::isWhitespace) || n.password.isEmpty() || n.password.length > 256)
        throw adminError("usuarios/gestionar-usuarios/dato-invalido", "The user name needs 1 to 64 characters without spaces or \"/\", and a password.")
    if (n.siteRights != null && !user.isFullAdministrator && n.siteRights and 0xFFFFFF1FL != 0L)
        throw adminError("usuarios/gestionar-usuarios/no-autorizado", "Only a full administrator gives those server rights.")
    val r = answered(buildJsonObject {
        put("action", "adduser"); put("username", name); put("pass", n.password); put("resetNextLogin", n.mustChangePassword)
        n.email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
        n.realName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("realname", it) }
        n.siteRights?.let { put("siteadmin", it) }
    }, "usuarios/gestionar-usuarios/tiempo-agotado", "usuarios/gestionar-usuarios/conexion-cortada")
    val said = r.str("result")
    if (said == "ok") return "user/${user.id.split('/').getOrElse(1) { "" }}/${name.lowercase()}"
    throw if (said == "User already exists") adminError("usuarios/gestionar-usuarios/conflicto-estado", "There is already an account with that name.", said)
    else adminError("usuarios/gestionar-usuarios/rechazo-remoto", "The server said: $said.", said)
}

/** Changes email, real name and, for another account, its server rights (null leaves each as it is). */
suspend fun MeshSession.editUser(target: UserInfo, email: String?, realName: String?, siteRights: Long?) {
    if (!canEditUser(target)) throw adminError("usuarios/gestionar-usuarios/no-autorizado", "This account cannot change that one.")
    if (siteRights != null && siteRights != target.siteRights && !canSetSiteRights(target, siteRights))
        throw adminError("usuarios/gestionar-usuarios/no-autorizado",
            if (target.id == user.id) "Nobody changes their own server rights." else "This account cannot give or take those server rights.")
    if (!email.isNullOrEmpty() && '@' !in email) throw adminError("usuarios/gestionar-usuarios/dato-invalido", "That is not an email address.")
    val r = answered(buildJsonObject {
        put("action", "edituser"); put("id", target.id)
        email?.let { put("email", it.trim()) }; realName?.let { put("realname", it.trim()) }
        if (siteRights != null && siteRights != target.siteRights) put("siteadmin", siteRights)
    }, "usuarios/gestionar-usuarios/tiempo-agotado", "usuarios/gestionar-usuarios/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("usuarios/gestionar-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

/** Locks (it cannot sign in, its sessions close) or unlocks an account. / Bloquea o desbloquea una cuenta. */
suspend fun MeshSession.setLocked(target: UserInfo, locked: Boolean) =
    editUser(target, null, null, if (locked) target.siteRights or SiteRights.LOCKED else target.siteRights and SiteRights.LOCKED.inv())

/**
 * Sets another account's password. The server never answers (changeuserpass): it is confirmed by the "Changed account
 * credentials" event (msgid 75); a password its rules refuse is dropped and ends in the time-out.
 * El servidor nunca contesta: se confirma con el aviso 75; una contraseña que sus reglas rechazan acaba en tiempo agotado.
 */
suspend fun MeshSession.setUserPassword(target: UserInfo, password: String, mustChange: Boolean, removeTwoFactor: Boolean) {
    if (!canEditUser(target)) throw adminError("usuarios/gestionar-usuarios/no-autorizado", "This account cannot change that one.")
    if (password.isEmpty() || password.length > 256) throw adminError("usuarios/gestionar-usuarios/dato-invalido", "The password needs 1 to 256 characters.")
    confirmedByEvent(buildJsonObject {
        put("action", "changeuserpass"); put("userid", target.id); put("pass", password)
        put("removeMultiFactor", removeTwoFactor); put("resetNextLogin", mustChange)
    }, { e -> e.str("action") == "accountchange" && e.long("msgid") == 75L && (e["account"] as? JsonObject)?.str("_id") == target.id },
        "usuarios/gestionar-usuarios/tiempo-agotado", "usuarios/gestionar-usuarios/conexion-cortada")
}

suspend fun MeshSession.deleteUser(target: UserInfo) {
    if (!canEditUser(target)) throw adminError("usuarios/gestionar-usuarios/no-autorizado", "This account cannot delete that one.")
    if (target.id == user.id) throw adminError("usuarios/gestionar-usuarios/conflicto-estado", "An account cannot delete itself from here.")
    val r = answered(buildJsonObject { put("action", "deleteuser"); put("userid", target.id) },
        "usuarios/gestionar-usuarios/tiempo-agotado", "usuarios/gestionar-usuarios/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("usuarios/gestionar-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

// ------------------------------------------------------------------ user groups / grupos de usuarios

/** Without "manage user groups" the server only gives their names. / Sin ese permiso el servidor solo da sus nombres. */
suspend fun MeshSession.userGroups(): List<UserGroup> {
    val r = answered(buildJsonObject { put("action", "usergroups") },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
    val all = r["ugroups"] as? JsonObject ?: return emptyList()
    return all.mapNotNull { (id, v) -> (v as? JsonObject)?.let { UserGroup.from(id, it) } }.sortedBy { it.name.lowercase() }
}

private fun MeshSession.checkUserGroups() {
    if (!canManageUserGroups) throw adminError("usuarios/gestionar-grupos-usuarios/no-autorizado", "This account cannot manage user groups.")
}

private fun checkUserGroupText(name: String, description: String?) {
    if (name.trim().isEmpty() || name.trim().length > 64 || (description?.length ?: 0) > 1024)
        throw adminError("usuarios/gestionar-grupos-usuarios/dato-invalido", "The name needs 1 to 64 characters and the description up to 1024.")
}

suspend fun MeshSession.createUserGroup(name: String, description: String? = null): String {
    checkUserGroups()
    checkUserGroupText(name, description)
    val r = answered(buildJsonObject { put("action", "createusergroup"); put("name", name.trim()); put("desc", description.orEmpty()) },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
    val id = r.str("ugrpid")
    if (r.str("result") != "ok" || id == null) throw adminError("usuarios/gestionar-grupos-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    return id
}

/** The server never answers editusergroup: confirmed by its "usergroupchange" event. / Se confirma con su aviso. */
suspend fun MeshSession.editUserGroup(group: UserGroup, name: String, description: String?) {
    checkUserGroups()
    checkUserGroupText(name, description)
    val n = name.trim()
    val d = description.orEmpty()
    if (group.managedElsewhere && n != group.name)
        throw adminError("usuarios/gestionar-grupos-usuarios/no-autorizado", "This group comes from single sign-on or LDAP: its name is kept there.")
    if (n == group.name && d == group.description.orEmpty()) return // no event would come / no llegaría aviso
    confirmedByEvent(buildJsonObject { put("action", "editusergroup"); put("ugrpid", group.id); put("name", n); put("desc", d) },
        { e -> e.str("action") == "usergroupchange" && e.str("ugrpid") == group.id && e.str("name") == n && e.str("desc").orEmpty() == d },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
}

suspend fun MeshSession.deleteUserGroup(group: UserGroup) {
    checkUserGroups()
    val r = answered(buildJsonObject { put("action", "deleteusergroup"); put("ugrpid", group.id) },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("usuarios/gestionar-grupos-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

/** Adds accounts by user name; names the server does not know are named back. / Los nombres que no conoce se dicen. */
suspend fun MeshSession.addUsersToUserGroup(group: UserGroup, userNames: List<String>) {
    checkUserGroups()
    if (group.managedElsewhere) throw adminError("usuarios/gestionar-grupos-usuarios/no-autorizado", "This group's members come from single sign-on or LDAP.")
    val list = names(userNames).distinctBy { it.lowercase() }
    if (list.isEmpty() || list.any { it.length > 128 }) throw adminError("usuarios/gestionar-grupos-usuarios/dato-invalido", "Write one or more user names.")
    val r = answered(buildJsonObject { put("action", "addusertousergroup"); put("ugrpid", group.id); put("usernames", JsonArray(list.map(::JsonPrimitive))) },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("usuarios/gestionar-grupos-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    val failed = r.long("failed") ?: 0
    if (failed > 0) throw MeshClientException(MeshFailure.NotFound, "usuarios/gestionar-grupos-usuarios/no-encontrado",
        "$failed of ${list.size} user name(s) are not accounts of this server; ${r.long("added") ?: 0} added.")
}

suspend fun MeshSession.removeUserFromUserGroup(group: UserGroup, userId: String) {
    checkUserGroups()
    if (group.managedElsewhere) throw adminError("usuarios/gestionar-grupos-usuarios/no-autorizado", "This group's members come from single sign-on or LDAP.")
    val r = answered(buildJsonObject { put("action", "removeuserfromusergroup"); put("ugrpid", group.id); put("userid", userId) },
        "usuarios/gestionar-grupos-usuarios/tiempo-agotado", "usuarios/gestionar-grupos-usuarios/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("usuarios/gestionar-grupos-usuarios/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}
