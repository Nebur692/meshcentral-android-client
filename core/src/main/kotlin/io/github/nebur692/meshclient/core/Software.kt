package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One program installed on a device. / Un programa instalado en un equipo. */
data class InstalledProgram(val name: String, val version: String?, val publisher: String?, val date: String?)

/*
 * The programs installed on a device, as the website's "Software" tab (meshuser.js software, type installedapps): the
 * server passes it to the agent, which answers with its list as text. Sent without responseid: 1.3.1 answers any
 * responseid at once with "Denied" before asking the agent (measured, as the Windows app).
 * Los programas instalados en un equipo, como la pestaña «Software» de la web. Se manda sin responseid: 1.3.1 contesta a
 * cualquier responseid con «Denied» antes de preguntar al agente (medido, como la app de Windows).
 */

/** Full rights, or any without "no software" (0x800000), and the agent connected. / Permisos completos o sin «sin software». */
fun MeshSession.canSeeSoftware(device: Device): Boolean {
    val r = rightsOn(device)
    return device.hasAgent && (r == MeshRights.FULL || (r != 0L && r and MeshRights.NO_SOFTWARE == 0L))
}

suspend fun MeshSession.installedPrograms(device: Device): List<InstalledProgram> {
    if (!canSeeSoftware(device)) throw MeshClientException(MeshFailure.NotAllowed, "ficha/abrir-detalle/no-autorizado", "This account has no rights to see its programs on this device.")
    val r = request(buildJsonObject { put("action", "software"); put("nodeid", device.id); put("type", "installedapps") },
        { it.str("action") == "software" && it["value"] != null && (it.str("nodeid") == null || it.str("nodeid") == device.id) },
        "ficha/abrir-detalle/tiempo-agotado", "ficha/abrir-detalle/conexion-cortada", 90_000)
    val raw = r["value"]
    val value: JsonElement? = if (raw is JsonPrimitive && raw.isString) runCatching { Json.parseToJsonElement(raw.content) }.getOrNull() else raw
    (value as? JsonObject)?.str("error")?.let { e ->
        throw MeshClientException(MeshFailure.Rejected, "ficha/abrir-detalle/rechazo-remoto", "The agent could not list the programs: $e.").apply { serverMessage = e }
    }
    val list = value as? JsonArray
        ?: throw MeshClientException(MeshFailure.UnexpectedResponse, "ficha/abrir-detalle/rechazo-remoto", "The agent's answer was not a list of programs.")
    fun blank(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
    return list.filterIsInstance<JsonObject>()
        .map { InstalledProgram(it.str("name").orEmpty().trim(), blank(it.str("version")), blank(it.str("publisher")), blank(it.str("date") ?: it.str("installdate"))) }
        .filter { it.name.isNotEmpty() }.sortedBy { it.name.lowercase() }
}
