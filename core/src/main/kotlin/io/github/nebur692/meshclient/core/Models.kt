package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** [manageAllDeviceGroups]: this account manages every group (a server setting). / Esta cuenta gestiona todos los grupos. */
data class ServerInfo(val name: String, val domain: String, val version: String?, val manageAllDeviceGroups: Boolean = false) {
    internal companion object {
        fun from(o: JsonObject) = ServerInfo(o.str("name").orEmpty(), o.str("domain").orEmpty(), o.str("serverVersion") ?: o.str("version"),
            o.bool("manageAllDeviceGroups"))
    }
}

/** [links]: rights given to this account directly and its user groups ("ugrp/…"); [removeRights]: rights taken away.
 *  [links]: permisos dados a esta cuenta y sus grupos de usuarios; [removeRights]: permisos quitados. */
data class UserInfo(
    val id: String, val name: String, val siteRights: Long,
    val links: Map<String, Long> = emptyMap(), val removeRights: Long? = null,
) {
    val isFullAdministrator: Boolean get() = siteRights == 0xFFFFFFFFL

    internal companion object {
        fun from(o: JsonObject) = UserInfo(o.str("_id").orEmpty(), o.str("name") ?: o.str("_id").orEmpty(), o.long("siteadmin") ?: 0,
            links(o), o.long("removeRights"))
    }
}

enum class DeviceGroupKind { Unknown, IntelAmtOnly, Agents, LocalDevices, IpKvm }

/** [links]: who has rights on the group, accounts and user groups. / Quién tiene permisos en el grupo. */
data class DeviceGroup(val id: String, val name: String, val description: String?, val kind: DeviceGroupKind, val links: Map<String, Long> = emptyMap()) {
    internal companion object {
        fun from(o: JsonObject) = DeviceGroup(
            o.str("_id") ?: o.str("meshid").orEmpty(), o.str("name").orEmpty(), o.str("desc"),
            DeviceGroupKind.entries.getOrElse((o.long("mtype") ?: 0).toInt()) { DeviceGroupKind.Unknown },
            links(o),
        )
    }
}

/** How a device is reachable (flags of "conn"). / Cómo se llega a un equipo (marcas de «conn»). */
object Connectivity {
    const val AGENT = 1
    const val AMT_CIRA = 2
    const val AMT_LOCAL = 4
    const val RELAY = 8
    const val MQTT = 16
}

/** What the agent can do (flags of "agent.caps"). / Lo que sabe hacer el agente (marcas de «agent.caps»). */
object AgentCaps {
    const val DESKTOP = 1
    const val TERMINAL = 2
    const val FILES = 4
}

enum class OsFamily { Windows, Linux, Mac, Android, Other }

data class Device(
    val id: String,
    val groupId: String,
    val name: String,
    val osDescription: String?,
    val address: String?,
    val icon: Int,
    val connectivity: Int,
    val powerState: Int,
    val capabilities: Int,
    val tags: List<String>,
    val agentId: Int = 0,
    val description: String? = null,
    /** When it last started (ms since 1970), as the agent says. / Cuándo arrancó por última vez, según el agente. */
    val lastBoot: Long? = null,
    /** Who is signed in on it now, and which of them have the screen locked. / Quién tiene sesión ahora y quién la tiene bloqueada. */
    val users: List<String> = emptyList(),
    val lockedUsers: List<String> = emptyList(),
    val security: DeviceSecurity = DeviceSecurity(),
    /** Rights given on this device alone. / Permisos dados solo en este equipo. */
    val links: Map<String, Long> = emptyMap(),
    /** The agent's core version, as it reports it. / La versión del núcleo del agente, como la da. */
    val agentCore: String? = null,
    /** Intel AMT is set up on it (any provisioning state but 0). / Tiene Intel AMT preparado. */
    val hasIntelAmt: Boolean = false,
) {
    val isOnline: Boolean get() = connectivity != 0
    val hasAgent: Boolean get() = connectivity and Connectivity.AGENT != 0
    val isWindows: Boolean get() = agentId in windowsAgents
    val canOpenDesktop: Boolean get() = hasAgent && capabilities and AgentCaps.DESKTOP != 0
    val canOpenTerminal: Boolean get() = hasAgent && capabilities and AgentCaps.TERMINAL != 0
    val canOpenFiles: Boolean get() = hasAgent && capabilities and AgentCaps.FILES != 0

    /** By the agent's type (MeshCentral's own table), then by the description the agent reports. Devices without an agent
     *  (local groups) use the same ids: 4 Windows, 6 Linux, 29 macOS, and report no description.
     *  Por el tipo de agente (la tabla de MeshCentral) y luego por la descripción que da el agente. Los equipos sin agente
     *  (grupos locales) usan los mismos números: 4 Windows, 6 Linux, 29 macOS, y no traen descripción. */
    val osFamily: OsFamily
        get() = when {
            agentId in windowsAgents -> OsFamily.Windows
            agentId in macAgents -> OsFamily.Mac
            agentId in androidAgents -> OsFamily.Android
            agentId in linuxAgents -> OsFamily.Linux
            osDescription?.contains("Windows", ignoreCase = true) == true -> OsFamily.Windows
            osDescription?.contains("mac", ignoreCase = true) == true -> OsFamily.Mac
            osDescription?.contains("Android", ignoreCase = true) == true -> OsFamily.Android
            osDescription != null -> OsFamily.Linux
            else -> OsFamily.Other
        }

    /** A change's details, keeping the live state it does not carry. / Los datos de un cambio, con el estado en vivo que no trae. */
    fun withDetails(changed: Device) = changed.copy(connectivity = connectivity, powerState = powerState)

    internal companion object {
        // From MeshCentral's agenttypes.js (1.3.1): platform «win32», «osx», «android», «linux». / De agenttypes.js de MeshCentral.
        // (11 and 12 say «linux» there but are macOS and Android builds. / El 11 y el 12 pone «linux» pero son de macOS y Android.)
        private val windowsAgents = setOf(1, 2, 3, 4, 21, 22, 42, 43, 10003, 10004)
        private val macAgents = setOf(11, 16, 29, 10005)
        private val androidAgents = setOf(12, 14)
        private val linuxAgents = setOf(5, 6, 7, 8, 9, 10, 13, 15, 18, 19, 20, 24, 25, 26, 27, 28, 32, 33, 35, 36, 40, 41, 45)

        fun from(o: JsonObject, groupId: String? = null): Device {
            val agent = o["agent"] as? JsonObject
            return Device(
                id = o.str("_id").orEmpty(),
                groupId = groupId ?: o.str("meshid").orEmpty(),
                name = o.str("name") ?: o.str("rname").orEmpty(),
                osDescription = o.str("osdesc"),
                address = o.str("ip") ?: o.str("host"),
                icon = (o.long("icon") ?: 1).toInt(),
                connectivity = (o.long("conn") ?: 0).toInt(),
                powerState = (o.long("pwr") ?: 0).toInt(),
                capabilities = (agent?.long("caps") ?: 0).toInt(),
                tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }.orEmpty(),
                agentId = (agent?.long("id") ?: 0).toInt(),
                description = o.str("desc"),
                lastBoot = o.long("lastbootuptime")?.takeIf { it > 0 },
                users = strings(o["users"]),
                lockedUsers = strings(o["lusers"]),
                security = DeviceSecurity.from(o),
                links = links(o),
                agentCore = agent?.str("core"),
                hasIntelAmt = ((o["intelamt"] as? JsonObject)?.long("state") ?: 0) != 0L,
            )
        }

        private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }.orEmpty()
    }
}

/** A live change from the server. / Un cambio en vivo del servidor. */
sealed interface DeviceChange {
    val deviceId: String
    data class Added(val device: Device) : DeviceChange { override val deviceId get() = device.id }
    data class Changed(val device: Device) : DeviceChange { override val deviceId get() = device.id }
    data class Removed(override val deviceId: String) : DeviceChange
    data class Connection(override val deviceId: String, val connectivity: Int, val powerState: Int) : DeviceChange
}

/** A MeshCentral login token: [tokenPass] only right after creating it. [expire] in ms since 1970, 0 = never.
 *  Un token de entrada de MeshCentral: [tokenPass] solo al crearlo. [expire] en ms desde 1970, 0 = nunca. */
data class LoginToken(val name: String, val tokenUser: String, val tokenPass: String?, val created: Long, val expire: Long) {
    internal companion object {
        fun from(o: JsonObject): LoginToken? {
            val user = o.str("tokenUser") ?: return null
            return LoginToken(o.str("name").orEmpty(), user, o.str("tokenPass"), o.long("created") ?: 0, o.long("expire") ?: 0)
        }
    }
}

/** One antivirus product the agent found. / Un antivirus que encontró el agente. */
data class Antivirus(val product: String, val enabled: Boolean, val updated: Boolean)

/**
 * What the agent tells about protection, as MeshCentral's website shows it: Windows Security Center ("wsc", values "OK"
 * or anything else meaning bad), its Linux counterpart ("lsc"), the antivirus list ("av"), Defender ("defender") and why a
 * restart is pending ("pr"). Null means the agent did not say. / Lo que cuenta el agente de la protección, como lo enseña
 * la web de MeshCentral. Null significa que el agente no lo dijo.
 */
data class DeviceSecurity(
    val antivirusOk: Boolean? = null,
    val firewallOk: Boolean? = null,
    val updatesOk: Boolean? = null,
    val antivirus: List<Antivirus> = emptyList(),
    val realTimeProtection: Boolean? = null,
    val pendingRestart: List<String> = emptyList(),
) {
    val isKnown: Boolean get() = antivirusOk != null || firewallOk != null || updatesOk != null || antivirus.isNotEmpty() || realTimeProtection != null

    internal companion object {
        fun from(o: JsonObject): DeviceSecurity {
            val center = (o["wsc"] as? JsonObject) ?: (o["lsc"] as? JsonObject)
            fun ok(k: String) = center?.str(k)?.let { it == "OK" }
            val pending = when (val pr = o["pr"]) {
                is JsonArray -> pr.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonObject -> pr.values.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }.filter(String::isNotEmpty)
            return DeviceSecurity(
                antivirusOk = ok("antiVirus"), firewallOk = ok("firewall"), updatesOk = ok("autoUpdate"),
                antivirus = (o["av"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull { a ->
                    a.str("product")?.let { Antivirus(it, a.bool("enabled"), a.bool("updated")) }
                }.orEmpty(),
                realTimeProtection = ((o["defender"] as? JsonObject)?.get("RealTimeProtection") as? JsonPrimitive)?.booleanOrNull,
                pendingRestart = pending,
            )
        }
    }
}

/** "links": { id: { rights } } → id → rights. / «links»: { id: { rights } } → id → permisos. */
internal fun links(o: JsonObject): Map<String, Long> =
    (o["links"] as? JsonObject)?.mapNotNull { (id, v) -> (v as? JsonObject)?.long("rights")?.let { id to it } }?.toMap().orEmpty()

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
