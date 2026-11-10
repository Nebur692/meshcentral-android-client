package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class ServerInfo(val name: String, val domain: String, val version: String?) {
    internal companion object {
        fun from(o: JsonObject) = ServerInfo(o.str("name").orEmpty(), o.str("domain").orEmpty(), o.str("serverVersion") ?: o.str("version"))
    }
}

data class UserInfo(val id: String, val name: String, val siteRights: Long) {
    val isFullAdministrator: Boolean get() = siteRights == 0xFFFFFFFFL

    internal companion object {
        fun from(o: JsonObject) = UserInfo(o.str("_id").orEmpty(), o.str("name") ?: o.str("_id").orEmpty(), o.long("siteadmin") ?: 0)
    }
}

enum class DeviceGroupKind { Unknown, IntelAmtOnly, Agents, LocalDevices, IpKvm }

data class DeviceGroup(val id: String, val name: String, val description: String?, val kind: DeviceGroupKind) {
    internal companion object {
        fun from(o: JsonObject) = DeviceGroup(
            o.str("_id") ?: o.str("meshid").orEmpty(), o.str("name").orEmpty(), o.str("desc"),
            DeviceGroupKind.entries.getOrElse((o.long("mtype") ?: 0).toInt()) { DeviceGroupKind.Unknown },
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
) {
    val isOnline: Boolean get() = connectivity != 0
    private val hasAgent: Boolean get() = connectivity and Connectivity.AGENT != 0
    val canOpenDesktop: Boolean get() = hasAgent && capabilities and AgentCaps.DESKTOP != 0
    val canOpenTerminal: Boolean get() = hasAgent && capabilities and AgentCaps.TERMINAL != 0
    val canOpenFiles: Boolean get() = hasAgent && capabilities and AgentCaps.FILES != 0

    /** Windows by the agent's type (the same list as the Windows app), the rest by the description the agent reports.
     *  Windows por el tipo de agente (la misma lista que la app de Windows), lo demás por la descripción que da el agente. */
    val osFamily: OsFamily
        get() = when {
            agentId in windowsAgents -> OsFamily.Windows
            osDescription?.contains("Windows", ignoreCase = true) == true -> OsFamily.Windows
            osDescription?.contains("mac", ignoreCase = true) == true -> OsFamily.Mac
            osDescription?.contains("Android", ignoreCase = true) == true -> OsFamily.Android
            osDescription != null -> OsFamily.Linux
            else -> OsFamily.Other
        }

    /** A change's details, keeping the live state it does not carry. / Los datos de un cambio, con el estado en vivo que no trae. */
    fun withDetails(changed: Device) = changed.copy(connectivity = connectivity, powerState = powerState)

    internal companion object {
        private val windowsAgents = setOf(1, 2, 3, 4, 21, 22, 42, 43, 10003, 10004)

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
            )
        }
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

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
