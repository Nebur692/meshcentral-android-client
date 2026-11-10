package io.github.nebur692.meshclient.core

/** Device rights of MeshCentral (meshuser.js MESHRIGHT_*). / Permisos sobre equipos de MeshCentral. */
object MeshRights {
    const val EDIT_GROUP = 0x1L
    const val MANAGE_USERS = 0x2L
    const val MANAGE_DEVICES = 0x4L
    const val REMOTE_CONTROL = 0x8L
    const val AGENT_CONSOLE = 0x10L
    const val WAKE = 0x40L
    const val SET_NOTES = 0x80L
    const val REMOTE_VIEW_ONLY = 0x100L
    const val NO_TERMINAL = 0x200L
    const val NO_FILES = 0x400L
    const val LIMIT_EVENTS = 0x2000L
    const val CHAT_NOTIFY = 0x4000L
    const val UNINSTALL = 0x8000L
    const val NO_DESKTOP = 0x10000L
    const val REMOTE_COMMAND = 0x20000L
    const val RESET_OFF = 0x40000L
    const val DEVICE_DETAILS = 0x100000L
    const val FULL = 0xFFFFFFFFL

    /** default.handlebars GetMeshRights. / GetMeshRights de la web. */
    fun ofGroup(user: UserInfo, server: ServerInfo, group: DeviceGroup?): Long {
        if (group == null) return 0
        if (server.manageAllDeviceGroups) return removeUserRights(user, FULL)
        var rights = 0L
        group.links[user.id]?.let { r -> if (r == FULL) return removeUserRights(user, FULL); rights = r }
        for (link in user.links.keys) if (link.startsWith("ugrp/")) group.links[link]?.let { g ->
            if (g == FULL) return removeUserRights(user, FULL)
            rights = rights or g
        }
        return removeUserRights(user, rights)
    }

    /** default.handlebars GetNodeRights. / GetNodeRights de la web. */
    fun ofDevice(user: UserInfo, server: ServerInfo, group: DeviceGroup?, device: Device): Long {
        var r = ofGroup(user, server, group)
        if (r == FULL) return r // already limited / ya limitado
        device.links[user.id]?.let { r = r or it }
        for ((link, rights) in device.links) if (link.startsWith("ugrp/") && link in user.links) r = r or rights
        return removeUserRights(user, r)
    }

    /** default.handlebars removeUserRights. / removeUserRights de la web. */
    internal fun removeUserRights(user: UserInfo, rights: Long): Long {
        val remove = user.removeRights ?: return rights
        var add = 0L
        var subtract = 0L
        for ((bit, adds) in listOf(
            0x8L to false, 0x10000L to true, 0x100L to true, 0x200L to true, 0x400L to true, 0x400000L to true, 0x800000L to true,
            0x10L to false, 0x8000L to false, 0x20000L to false, 0x40L to false, 0x40000L to false,
        )) if (remove and bit != 0L) { if (adds) add = add or bit else subtract = subtract or bit }
        var r = if (rights == FULL) 1L + 2 + 4 + 8 + 32 + 64 + 128 + 16384 + 32768 + 131072 + 262144 + 524288 + 1048576 else rights
        r = r or add
        r = r and (FULL - subtract)
        return r
    }

    fun has(rights: Long, right: Long) = rights and right == right

    /**
     * Remote control without the [no] restriction (NO_DESKTOP, NO_TERMINAL or NO_FILES). Full rights (0xFFFFFFFF) carry
     * those bits too, so the website reads them only below full. / Control remoto sin esa restricción. Los permisos
     * completos llevan también esos bits, así que la web solo los mira por debajo.
     */
    fun tool(rights: Long, no: Long) = has(rights, REMOTE_CONTROL) && (rights == FULL || rights and no == 0L)
}

/**
 * What a person can do on a device now, from the rights and, where the agent does it, the agent connected. Worked out
 * first, as the website does, because poweraction and toast answer "ok" even without the rights.
 * Lo que puede hacer una persona en un equipo ahora. Se calcula antes, como la web, porque poweraction y toast contestan
 * «ok» aunque no haya permisos.
 */
data class DeviceAbilities(
    val wake: Boolean,
    val power: Boolean,
    val notify: Boolean,
    val messageBox: Boolean,
    val runCommands: Boolean,
    val edit: Boolean,
    val notes: Boolean,
    val uninstall: Boolean,
    val remove: Boolean,
    val details: Boolean,
) {
    val anyMessage: Boolean get() = notify || messageBox

    companion object {
        val NONE = DeviceAbilities(false, false, false, false, false, false, false, false, false, false)

        fun of(rights: Long, device: Device): DeviceAbilities {
            fun has(r: Long) = MeshRights.has(rights, r)
            val agent = device.hasAgent
            return DeviceAbilities(
                wake = has(MeshRights.WAKE) && !device.isOnline,
                power = has(MeshRights.RESET_OFF) && agent,
                notify = has(MeshRights.CHAT_NOTIFY) && agent,
                // msg is routed only with remote control or view only (routeCommandToNode). / msg solo se enruta con control remoto o solo ver.
                messageBox = (has(MeshRights.REMOTE_CONTROL) || has(MeshRights.REMOTE_VIEW_ONLY)) && agent,
                runCommands = has(MeshRights.REMOTE_COMMAND) && agent,
                edit = has(MeshRights.MANAGE_DEVICES),
                notes = has(MeshRights.SET_NOTES),
                // The agent obeys only while connected; the server needs the uninstall right. / El agente solo obedece conectado.
                uninstall = has(MeshRights.UNINSTALL) && agent,
                // removedevices checks the uninstall right too (meshuser.js). / removedevices también mira el permiso de desinstalar.
                remove = has(MeshRights.UNINSTALL),
                details = rights != 0L,
            )
        }
    }
}
