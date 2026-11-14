package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a device group asks of the person at the device and how it keeps its devices, as the website's "User Consent"
 * and "Features" dialogs edit them (measured on MeshCentral 1.3.1: default.handlebars p20editmeshconsent and
 * p20editmeshfeatures, meshuser.js "editmesh"). [consent]: bits of [Consent]; [flags]: bits of [Feature];
 * [removeInactiveDays]: 0 = never, 1 to 2000 days.
 * Lo que un grupo pide a la persona del equipo y cómo guarda sus equipos, como los editan los diálogos «Consentimiento»
 * y «Funciones» de la web (medido en 1.3.1). [consent]: marcas de [Consent]; [flags]: de [Feature];
 * [removeInactiveDays]: 0 = nunca, de 1 a 2000 días.
 */
data class GroupSettings(val consent: Int = 0, val flags: Int = 0, val removeInactiveDays: Int = 0) {
    fun has(bit: Int, of: Int = consent) = of and bit != 0

    object Consent {
        const val DESKTOP_NOTIFY = 0x1
        const val TERMINAL_NOTIFY = 0x2
        const val FILES_NOTIFY = 0x4
        const val DESKTOP_PROMPT = 0x8
        const val TERMINAL_PROMPT = 0x10
        const val FILES_PROMPT = 0x20
        const val DESKTOP_TOOLBAR = 0x40
        const val REGISTRY_NOTIFY = 0x80
        const val REGISTRY_PROMPT = 0x100
        const val ALL = 0x1FF
    }

    object Feature {
        /** Remove the device when it disconnects (agent and IP-KVM groups). / Borrar el equipo al desconectarse. */
        const val REMOVE_ON_DISCONNECT = 0x1
        /** Keep the server's device name equal to its host name. / Igualar el nombre del equipo al de su host. */
        const val SYNC_NAME = 0x2
        /** Record sessions (only when the server lets groups choose). / Grabar sesiones (si el servidor deja elegir). */
        const val RECORD = 0x4
        /** With SYNC_NAME: prefer the agent's --agentName. / Con SYNC_NAME: preferir --agentName. */
        const val PREFER_AGENT_NAME = 0x8
        /** With SYNC_NAME: allow renaming until the next connection. / Con SYNC_NAME: dejar renombrar hasta reconectar. */
        const val ALLOW_OVERRIDE = 0x10
    }

    companion object {
        const val MAX_DAYS = 2000

        /**
         * What the website would save from these choices: the name sub-options fall without SYNC_NAME, removing inactive
         * devices does not go with removing them on disconnect, and only kinds that have them keep those bits.
         * Lo que guardaría la web con estas elecciones: las opciones del nombre caen sin SYNC_NAME, borrar inactivos no va
         * con borrar al desconectarse y solo los tipos que las tienen conservan esas marcas.
         */
        fun normalized(s: GroupSettings, kind: DeviceGroupKind, server: ServerInfo): GroupSettings {
            var f = s.flags
            val agentLike = kind == DeviceGroupKind.Agents || kind == DeviceGroupKind.IpKvm
            if (!agentLike) f = f and Feature.REMOVE_ON_DISCONNECT.inv()
            if (f and Feature.SYNC_NAME == 0) f = f and (Feature.PREFER_AGENT_NAME or Feature.ALLOW_OVERRIDE).inv()
            if (!server.groupsChooseRecording) f = f and Feature.RECORD.inv()
            val days = if (f and Feature.REMOVE_ON_DISCONNECT != 0) 0 else s.removeInactiveDays
            return GroupSettings(s.consent or server.forcedConsent, f, days)
        }
    }
}

/**
 * Saves the consent and features of a group, as the website does; the server changes only what differs and answers
 * "ok" either way. Consent the server forces stays on.
 * Guarda el consentimiento y las funciones de un grupo, como la web; el servidor solo cambia lo distinto y contesta «ok»
 * igual. El consentimiento que impone el servidor sigue puesto.
 */
suspend fun MeshSession.saveGroupSettings(group: DeviceGroup, wanted: GroupSettings): GroupSettings {
    if (!MeshRights.has(MeshRights.ofGroup(user, serverInfo, group), MeshRights.EDIT_GROUP))
        throw MeshClientException(MeshFailure.NotAllowed, "grupos/gestionar-grupos/no-autorizado", "This account has no rights to edit this device group.")
    if (wanted.removeInactiveDays !in 0..GroupSettings.MAX_DAYS || wanted.consent !in 0..GroupSettings.Consent.ALL)
        throw MeshClientException(MeshFailure.Rejected, "grupos/gestionar-grupos/dato-invalido", "Inactive devices are removed after 1 to ${GroupSettings.MAX_DAYS} days.")
    val s = GroupSettings.normalized(wanted, group.kind, serverInfo)
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "editmesh"); put("meshid", group.id); put("consent", s.consent); put("flags", s.flags)
        put("expireDevs", s.removeInactiveDays); put("responseid", id)
    }, { it.str("action") == "editmesh" && it.str("responseid") == id }, "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    if (r.str("result") != "ok") throw MeshClientException(MeshFailure.Rejected, "grupos/gestionar-grupos/rechazo-remoto",
        "The server said: ${r.str("result")}.").apply { serverMessage = r.str("result") }
    deviceGroups()
    return s
}
