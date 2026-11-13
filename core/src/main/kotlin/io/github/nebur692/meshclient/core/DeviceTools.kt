package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A device's processes and services, as the website's desktop tools ask for them (default.handlebars, MeshCentral 1.3.1):
 * {action:'msg', type:'ps'|'services'} to the agent, which answers with the list as a JSON string in "value"; 'pskill'
 * ("pid|name") and 'serviceStart'/'serviceStop'/'serviceRestart' (by name) get no answer, so the list is asked again.
 * The server routes them with remote control or view-only rights (routeCommandToNode). When the agent cannot list
 * (no service manager), it answers nothing: that is "not available" here, not an error.
 * Los procesos y servicios de un equipo, como los piden las herramientas de escritorio de la web: el agente contesta con
 * la lista como texto JSON en «value»; matar y arrancar/parar/reiniciar no contestan, así que se vuelve a pedir la lista.
 * Cuando el agente no puede listar, no contesta: aquí eso es «no disponible», no un error.
 */
data class DeviceProcess(val pid: Int, val command: String, val user: String?)

/** [state] as the agent says it (Windows: RUNNING, STOPPED…); null on Linux, where only the type comes. */
data class DeviceService(val name: String, val displayName: String, val state: String?, val pid: Int?) {
    val isRunning: Boolean get() = state.equals("RUNNING", ignoreCase = true)
}

object DeviceToolsParse {
    private val json = Json { ignoreUnknownKeys = true }

    /** The ps answer: an object by pid with cmd and user (pid 0 left out, as the website does). / La respuesta de ps. */
    fun processes(value: String?): List<DeviceProcess>? {
        val o = value?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        return o.mapNotNull { (pid, v) ->
            val p = v as? JsonObject ?: return@mapNotNull null
            val id = pid.toIntOrNull() ?: return@mapNotNull null
            val cmd = p.str("cmd") ?: return@mapNotNull null
            if (id == 0) null else DeviceProcess(id, cmd, p.str("user")?.takeIf { it.isNotBlank() })
        }.sortedBy { it.command.lowercase() }
    }

    /** The services answer: a list (Windows: name, displayName, status.state; Linux: name, serviceType). */
    fun services(value: String?): List<DeviceService>? {
        val e: JsonElement = value?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return null
        val items = when (e) { is JsonArray -> e.toList(); is JsonObject -> e.values.toList(); else -> return null }
        return items.mapNotNull { v ->
            val s = v as? JsonObject ?: return@mapNotNull null
            val name = s.str("name") ?: return@mapNotNull null
            val status = s["status"] as? JsonObject
            DeviceService(name, s.str("displayName")?.takeIf { it.isNotBlank() } ?: name, status?.str("state") ?: s.str("serviceType"),
                status?.long("pid")?.toInt()?.takeIf { it > 0 })
        }.sortedBy { it.displayName.lowercase() }
    }
}

private fun MeshSession.checkTools(device: Device, change: Boolean, key: String) {
    val r = rightsOn(device)
    val ok = if (change) MeshRights.has(r, MeshRights.REMOTE_CONTROL) else MeshRights.has(r, MeshRights.REMOTE_CONTROL) || MeshRights.has(r, MeshRights.REMOTE_VIEW_ONLY)
    if (!ok) throw MeshClientException(MeshFailure.NotAllowed, key, "This account has no rights to do that on this device.")
    if (!device.hasAgent) throw MeshClientException(MeshFailure.DeviceOffline, key.substringBeforeLast('/') + "/sin-conexion", "The device's agent is not connected.")
}

private suspend fun MeshSession.askList(device: Device, type: String): String? = try {
    request(buildJsonObject { put("action", "msg"); put("type", type); put("nodeid", device.id) },
        { it.str("action") == "msg" && it.str("type") == type && (it.str("nodeid") == null || it.str("nodeid") == device.id) },
        "herramientas/abrir-detalle/tiempo-agotado", "herramientas/abrir-detalle/conexion-cortada", 20_000).str("value")
} catch (e: MeshClientException) {
    if (e.failure == MeshFailure.Timeout) null else throw e
}

/** The running processes, or null when the agent gives none. / Los procesos, o null si el agente no da ninguno. */
suspend fun MeshSession.processes(device: Device): List<DeviceProcess>? {
    checkTools(device, false, "herramientas/abrir-detalle/no-autorizado")
    return DeviceToolsParse.processes(askList(device, "ps"))
}

/** Ends a process; the agent does not answer, so the list is asked again after. / Acaba un proceso; el agente no contesta. */
fun MeshSession.killProcess(device: Device, p: DeviceProcess) {
    checkTools(device, true, "herramientas/accionar-dispositivo/no-autorizado")
    send(buildJsonObject { put("action", "msg"); put("type", "pskill"); put("nodeid", device.id); put("value", "${p.pid}|${p.command}") },
        "herramientas/accionar-dispositivo/conexion-cortada")
}

/** The services (Windows), or null when the agent has no service manager. / Los servicios, o null si no hay gestor. */
suspend fun MeshSession.services(device: Device): List<DeviceService>? {
    checkTools(device, false, "herramientas/abrir-detalle/no-autorizado")
    return DeviceToolsParse.services(askList(device, "services"))
}

enum class ServiceAction(val type: String) { Start("serviceStart"), Stop("serviceStop"), Restart("serviceRestart") }

fun MeshSession.serviceAction(device: Device, s: DeviceService, action: ServiceAction) {
    checkTools(device, true, "herramientas/accionar-dispositivo/no-autorizado")
    send(buildJsonObject { put("action", "msg"); put("type", action.type); put("nodeid", device.id); put("serviceName", s.name) },
        "herramientas/accionar-dispositivo/conexion-cortada")
}
