package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Devices without an agent: a group of "local devices" keeps machines the server reaches by their address (or through
 * the agent of another device, the group's relay), for Windows Remote Desktop, SSH or any port. What MeshCentral Router
 * does with them. Measured on MeshCentral 1.3.1 (meshuser.js, meshrelay.js): createmesh with meshtype 3 (and relayid),
 * addlocaldevice with type 4 (Windows), 6 (Linux) or 29 (macOS), getcookie for the node and port, then
 * localrelay.ashx?auth=, which answers "c" when the server is connected and then carries the bytes.
 * Equipos sin agente: un grupo de «equipos locales» guarda máquinas a las que el servidor llega por su dirección (o por
 * el agente de otro equipo, el relé del grupo), para el Escritorio remoto de Windows, SSH o cualquier puerto. Lo que hace
 * MeshCentral Router con ellos. Medido en 1.3.1: createmesh con meshtype 3 (y relayid), addlocaldevice con tipo 4, 6 o
 * 29, getcookie del equipo y el puerto, y luego localrelay.ashx?auth=, que contesta «c» al conectar y lleva los bytes.
 */
enum class LocalDeviceOs(val agentType: Int) { Windows(4), Linux(6), Mac(29) }

/** True for a device without an agent, by its own mark or its group's kind. / Si es un equipo sin agente. */
fun MeshSession.isLocal(device: Device): Boolean = device.isLocal || groups[device.groupId]?.kind == DeviceGroupKind.LocalDevices

/** The group's relay device, if the server reaches its local devices through an agent. / El equipo que hace de relé. */
fun MeshSession.relayOf(device: Device): String? = if (isLocal(device)) groups[device.groupId]?.relayId else null

/**
 * Creates a group of local devices and returns its id. [relayId]: a device with the agent through which the server
 * reaches them (needed when the server only works through the internet), or null for the server itself.
 * Crea un grupo de equipos locales. [relayId]: el equipo con agente por el que llega el servidor, o null para él mismo.
 */
suspend fun MeshSession.createLocalGroup(name: String, description: String? = null, relayId: String? = null): String {
    checkGroupText(name, description)
    if (!canCreateGroups) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account has no rights to create device groups.")
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "createmesh"); put("responseid", id); put("meshname", name.trim()); put("meshtype", 3)
        if (!description.isNullOrEmpty()) put("desc", description)
        if (relayId != null) put("relayid", relayId)
    }, { it.str("action") == "createmesh" && it.str("responseid") == id }, "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    val meshId = r.str("meshid")
    if (r.str("result") != "ok" || meshId == null) throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
    return meshId
}

/** Changes the agent through which the server reaches a local group's devices. / Cambia el relé de un grupo local. */
suspend fun MeshSession.setRelay(group: DeviceGroup, relayId: String) {
    if (group.kind != DeviceGroupKind.LocalDevices) throw adminError("grupos/gestionar-grupos/dato-invalido", "Only a group of local devices has a relay.")
    if (!groupAbilitiesOn(group).edit) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account has no rights to edit this device group.")
    val id = MeshSession.newId()
    val r = request(buildJsonObject { put("action", "editmesh"); put("responseid", id); put("meshid", group.id); put("relayid", relayId) },
        { it.str("action") == "editmesh" && it.str("responseid") == id }, "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
    deviceGroups()
}

/** Who may add devices without an agent: manage devices in a group of local devices (as the server checks). / Quién puede añadirlos. */
fun MeshSession.canAddLocalDevice(group: DeviceGroup): Boolean =
    group.kind == DeviceGroupKind.LocalDevices && MeshRights.has(MeshRights.ofGroup(user, serverInfo, group), MeshRights.MANAGE_DEVICES)

/** True for a name or address the server takes: 1 to 256 characters, no spaces in an address. / Si el servidor lo admite. */
fun validLocalHost(host: String) = host.trim().let { it.length in 1..256 && it.none(Char::isWhitespace) }

/**
 * Adds a device without an agent to a group of local devices; it shows in the list at once (the server sends addnode).
 * Añade un equipo sin agente a un grupo de equipos locales; sale en la lista al momento.
 */
suspend fun MeshSession.addLocalDevice(group: DeviceGroup, name: String, host: String, os: LocalDeviceOs) {
    if (group.kind != DeviceGroupKind.LocalDevices) throw adminError("grupos/gestionar-grupos/dato-invalido", "Devices without an agent only go in a group of local devices.")
    if (name.isBlank() || name.length > 256) throw adminError("grupos/gestionar-grupos/dato-invalido", "The name must have 1 to 256 characters.")
    if (!validLocalHost(host)) throw adminError("grupos/gestionar-grupos/dato-invalido", "\"$host\" is not a name or address.")
    if (!canAddLocalDevice(group)) throw adminError("grupos/gestionar-grupos/no-autorizado", "This account cannot add devices to this group.")
    val id = MeshSession.newId()
    // The server answers addlocaldevice when it worked, and changeDeviceMesh with the reason when not.
    // El servidor contesta addlocaldevice si funcionó, y changeDeviceMesh con el motivo si no.
    val r: JsonObject = request(buildJsonObject {
        put("action", "addlocaldevice"); put("responseid", id); put("meshid", group.id)
        put("devicename", name.trim()); put("hostname", host.trim()); put("type", os.agentType)
    }, { (it.str("action") == "addlocaldevice" || it.str("action") == "changeDeviceMesh") && it.str("responseid") == id },
        "grupos/gestionar-grupos/tiempo-agotado", "grupos/gestionar-grupos/conexion-cortada")
    if (r.str("result") != "ok") throw adminError("grupos/gestionar-grupos/rechazo-remoto", "The server said: ${r.str("result")}.", r.str("result"))
}

/**
 * The cookie localrelay.ashx takes for one device and port, as the website asks it (getcookie). The server only answers
 * with remote control rights; without them it says nothing, and the wait ends as "no rights".
 * La cookie que pide localrelay.ashx para un equipo y un puerto, como la pide la web. El servidor solo contesta con
 * control remoto; sin él calla, y la espera acaba como «sin permiso».
 */
internal suspend fun MeshSession.localCookie(nodeId: String, port: Int): String {
    val tag = MeshSession.newId()
    val r = try {
        request(buildJsonObject { put("action", "getcookie"); put("nodeid", nodeId); put("tcpport", port); put("tag", tag) },
            { it.str("action") == "getcookie" && it.str("tag") == tag }, "redireccion/llevar-conexion/no-autorizado", "redireccion/llevar-conexion/conexion-cortada")
    } catch (e: MeshClientException) {
        if (e.failure != MeshFailure.Timeout) throw e
        throw MeshClientException(MeshFailure.Rejected, "redireccion/llevar-conexion/no-autorizado", "The server did not give a connection to this device: this account has no remote control on it.")
    }
    return r.str("cookie") ?: throw MeshClientException(MeshFailure.Rejected, "redireccion/llevar-conexion/respuesta-ilegible", "The server answered getcookie without a cookie.")
}
