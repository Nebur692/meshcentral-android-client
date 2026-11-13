package io.github.nebur692.meshclient.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Power actions the agent performs (poweraction actiontype; MeshCentral has no hibernate). / Acciones de energía del agente. */
enum class PowerAction(val code: Int) { PowerOff(2), Restart(3), Sleep(4) }

/** Which shell runs the commands (runcommands type). / Qué intérprete ejecuta las órdenes. */
enum class CommandShell(val code: Int) { Cmd(1), PowerShell(2), Shell(3) }

/*
 * A device's details and actions, the same requests and replies the Windows app measured on MeshCentral 1.3.1. Every
 * action checks the rights first, as the website does: several requests answer "ok", or nothing at all, without them.
 * Los datos y las acciones de un equipo, las mismas peticiones y respuestas que midió la app de Windows en MeshCentral
 * 1.3.1. Cada acción mira antes los permisos, como la web: varias contestan «ok», o nada, sin tenerlos.
 */

/** The rights of this account on a device (the groups must have been listed). / Los permisos de la cuenta en un equipo. */
fun MeshSession.rightsOn(device: Device): Long = MeshRights.ofDevice(user, serverInfo, groups[device.groupId], device)

fun MeshSession.abilitiesOn(device: Device): DeviceAbilities = DeviceAbilities.of(rightsOn(device), device)

private fun denied(key: String, what: String) = MeshClientException(MeshFailure.NotAllowed, key, "This account has no rights to $what on this device.")
/** The server's clipboardget / clipboardset is off (features2). / El servidor tiene apagado el portapapeles. */
private fun serverForbidsClipboard() = MeshClientException(MeshFailure.NotAllowed, "portapapeles/sincronizar/no-autorizado", "This server does not allow the device's clipboard.")
private fun offline(key: String) = MeshClientException(MeshFailure.DeviceOffline, key, "The device's agent is not connected.")
private fun refused(key: String, result: String?) =
    MeshClientException(MeshFailure.Rejected, key, "The server said: $result.").apply { serverMessage = result }

private const val DETAIL_TIMEOUT = "ficha/abrir-detalle/tiempo-agotado"
private const val DETAIL_LOST = "ficha/abrir-detalle/conexion-cortada"

/** The agent's inventory: null when it never sent one. / El inventario del agente: null si nunca lo mandó. */
suspend fun MeshSession.systemInfo(nodeId: String): Pair<HardwareSummary, String?>? {
    val r = request(buildJsonObject { put("action", "getsysinfo"); put("nodeid", nodeId); put("nodeinfo", true) },
        { it.str("action") == "getsysinfo" && it.str("nodeid") == nodeId }, DETAIL_TIMEOUT, DETAIL_LOST)
    val hw = r["hardware"] as? JsonObject ?: return null
    return HardwareSummary.from(hw) to Os.describe(hw)
}

suspend fun MeshSession.lastConnect(nodeId: String): LastConnect? {
    val r = request(buildJsonObject { put("action", "lastconnect"); put("nodeid", nodeId) },
        { it.str("action") == "lastconnect" && it.str("nodeid") == nodeId }, DETAIL_TIMEOUT, DETAIL_LOST)
    if (r.bool("noinfo")) return null
    return LastConnect(r.long("time")?.takeIf { it > 0 }, r.str("addr"))
}

suspend fun MeshSession.networkInfo(nodeId: String): List<NetworkInterface> {
    val r = request(buildJsonObject { put("action", "getnetworkinfo"); put("nodeid", nodeId) },
        { it.str("action") == "getnetworkinfo" && it.str("nodeid") == nodeId }, DETAIL_TIMEOUT, DETAIL_LOST)
    return NetworkInterface.from(r["netif2"] as? JsonObject ?: return emptyList())
}

/** Null when there are none. Without access the server does not answer at all. / Null si no hay. Sin acceso no contesta. */
suspend fun MeshSession.notes(nodeId: String, timeoutMs: Long = 8_000): String? {
    val r = request(buildJsonObject { put("action", "getNotes"); put("id", nodeId) },
        { it.str("action") == "getNotes" && it.str("id") == nodeId }, DETAIL_TIMEOUT, DETAIL_LOST, timeoutMs)
    return r.str("notes")?.takeIf(String::isNotEmpty)
}

/** Empty clears them. The server does not answer: read them back to be sure. / Vacío las borra. No contesta: se releen. */
fun MeshSession.setNotes(device: Device, notes: String) {
    if (!abilitiesOn(device).notes) throw denied("ficha/cambiar-ajustes/no-autorizado", "write notes")
    send(buildJsonObject { put("action", "setNotes"); put("id", device.id); put("notes", notes) }, "ficha/cambiar-ajustes/conexion-cortada")
}

suspend fun MeshSession.events(nodeId: String, limit: Int = 50): List<DeviceEvent> {
    val tag = MeshSession.newId()
    val r = request(buildJsonObject { put("action", "events"); put("nodeid", nodeId); put("limit", limit); put("tag", tag) },
        { it.str("action") == "events" && it.str("tag") == tag }, DETAIL_TIMEOUT, DETAIL_LOST)
    return (r["events"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map { e ->
        DeviceEvent(e.str("time")?.let(::parseTime), e.str("username") ?: e.str("userid")?.substringAfterLast('/'), e.str("action"), e.str("msg").orEmpty())
    }.orEmpty()
}

internal fun parseTime(t: String): Long? = try { java.time.Instant.parse(t).toEpochMilli() } catch (_: java.time.format.DateTimeParseException) { null }

/**
 * Everything for the sheet at once; a part that fails or that this account may not see is left out, never the whole.
 * Todo para la ficha a la vez; la parte que falla o que esta cuenta no puede ver se queda fuera, nunca el conjunto.
 */
suspend fun MeshSession.details(device: Device): DeviceDetails = coroutineScope {
    suspend fun <T> part(block: suspend () -> T): T? = try { block() } catch (e: MeshClientException) {
        if (e.failure == MeshFailure.ConnectionLost) throw e else null
    }
    val sys = async { part { systemInfo(device.id) } }
    val net = async { part { networkInfo(device.id) } }
    val last = async { part { lastConnect(device.id) } }
    val notes = async { part { notes(device.id) } }
    val events = async { part { events(device.id) } }
    val s = sys.await()
    DeviceDetails(s?.first, s?.second, net.await().orEmpty(), last.await(), notes.await(), events.await().orEmpty())
}

/** Changes the name, description and/or tags (null = unchanged). / Cambia nombre, descripción o etiquetas (null = igual). */
suspend fun MeshSession.changeDevice(device: Device, name: String? = null, description: String? = null, tags: List<String>? = null) {
    if (!abilitiesOn(device).edit) throw denied("ficha/cambiar-ajustes/no-autorizado", "edit it")
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "changedevice"); put("nodeid", device.id); put("responseid", id)
        name?.let { put("name", it) }
        description?.let { put("desc", it) }
        tags?.let { put("tags", JsonArray(it.map(::JsonPrimitive))) }
    }, { it.str("action") == "changedevice" && it.str("responseid") == id }, "ficha/cambiar-ajustes/tiempo-agotado", "ficha/cambiar-ajustes/conexion-cortada")
    r.str("result")?.takeIf { it != "ok" }?.let { throw refused("ficha/cambiar-ajustes/rechazo-remoto", it) }
}

private fun ids(devices: List<Device>) = JsonArray(devices.map { JsonPrimitive(it.id) })

private fun requireSome(devices: List<Device>) = require(devices.isNotEmpty()) { "no devices" }

/** Wake-on-LAN, sent by other agents of its network. Returns what the server says. / Lo mandan otros agentes de su red. */
suspend fun MeshSession.wake(device: Device): String = wake(listOf(device))

/** Several at once: every one must allow it, or none is sent. / Varios a la vez: todos tienen que permitirlo, o no se manda ninguno. */
suspend fun MeshSession.wake(devices: List<Device>): String {
    requireSome(devices)
    for (d in devices) if (!MeshRights.has(rightsOn(d), MeshRights.WAKE)) throw denied("acciones/accionar-dispositivo/no-autorizado", "wake it")
    val id = MeshSession.newId()
    val r = request(buildJsonObject { put("action", "wakedevices"); put("nodeids", ids(devices)); put("responseid", id) },
        { it.str("action") == "wakedevices" && it.str("responseid") == id },
        "acciones/accionar-dispositivo/tiempo-agotado", "acciones/accionar-dispositivo/conexion-cortada")
    val result = r.str("result").orEmpty()
    // Measured on 1.3.1: "Used N device(s) to send wake packets", or "Invalid nodeid" / "Invalid domain".
    if (result.startsWith("Invalid", ignoreCase = true)) throw refused("acciones/accionar-dispositivo/rechazo-remoto", result)
    return result
}

/** Power off, restart or sleep, done by the agent. / Apagar, reiniciar o suspender, lo hace el agente. */
suspend fun MeshSession.power(device: Device, action: PowerAction) = power(listOf(device), action)

suspend fun MeshSession.power(devices: List<Device>, action: PowerAction) {
    requireSome(devices)
    for (d in devices) {
        if (!MeshRights.has(rightsOn(d), MeshRights.RESET_OFF)) throw denied("acciones/accionar-dispositivo/no-autorizado", "power it off or restart it")
        if (!d.hasAgent) throw offline("acciones/accionar-dispositivo/sin-conexion")
    }
    val id = MeshSession.newId()
    request(buildJsonObject {
        put("action", "poweraction"); put("nodeids", ids(devices)); put("actiontype", action.code); put("responseid", id)
    }, { it.str("action") == "poweraction" && it.str("responseid") == id },
        "acciones/accionar-dispositivo/tiempo-agotado", "acciones/accionar-dispositivo/conexion-cortada")
}

/** A system notification on the device (toast). / Una notificación del sistema en el equipo. */
suspend fun MeshSession.notify(device: Device, title: String, message: String) = notify(listOf(device), title, message)

suspend fun MeshSession.notify(devices: List<Device>, title: String, message: String) {
    requireSome(devices)
    for (d in devices) {
        if (!MeshRights.has(rightsOn(d), MeshRights.CHAT_NOTIFY)) throw denied("acciones/enviar-mensaje/no-autorizado", "send notifications")
        if (!d.hasAgent) throw offline("acciones/enviar-mensaje/sin-conexion")
    }
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "toast"); put("nodeids", ids(devices)); put("title", title); put("msg", message); put("responseid", id)
    }, { it.str("action") == "toast" && it.str("responseid") == id }, "acciones/enviar-mensaje/tiempo-agotado", "acciones/enviar-mensaje/conexion-cortada")
    if (!r.str("result").equals("ok", ignoreCase = true)) throw refused("acciones/enviar-mensaje/rechazo-remoto", r.str("result"))
}

/** A message window on the device that stays until closed or [timeoutMs]. / Una ventana de mensaje en el equipo. */
suspend fun MeshSession.messageBox(device: Device, title: String, message: String, timeoutMs: Long = 120_000) {
    val can = abilitiesOn(device)
    if (!can.messageBox) throw if (device.hasAgent) denied("acciones/enviar-mensaje/no-autorizado", "show messages") else offline("acciones/enviar-mensaje/sin-conexion")
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "msg"); put("type", "messagebox"); put("nodeid", device.id); put("title", title); put("msg", message)
        put("timeout", timeoutMs); put("responseid", id)
    }, { it.str("action") == "msg" && it.str("responseid") == id }, "acciones/enviar-mensaje/tiempo-agotado", "acciones/enviar-mensaje/conexion-cortada")
    if (r.str("result") != "OK") throw refused("acciones/enviar-mensaje/rechazo-remoto", r.str("result"))
}

/** cmd or PowerShell on Windows, sh elsewhere. / cmd o PowerShell en Windows, sh en los demás. */
fun shellFor(device: Device, powerShell: Boolean): CommandShell =
    if (device.isWindows) (if (powerShell) CommandShell.PowerShell else CommandShell.Cmd) else CommandShell.Shell

/**
 * Runs commands on the device as its agent (the system account) and returns their output, stdout and stderr together.
 * The agent runs one batch at a time and ignores another one meanwhile.
 * Ejecuta órdenes en el equipo como su agente y devuelve su salida, stdout y stderr juntas. El agente ejecuta una tanda
 * cada vez y no hace caso a otra mientras.
 */
suspend fun MeshSession.runCommands(device: Device, commands: String, shell: CommandShell, timeoutMs: Long = 5 * 60_000): String {
    if (!MeshRights.has(rightsOn(device), MeshRights.REMOTE_COMMAND)) throw denied("acciones/ejecutar-ordenes/no-autorizado", "run commands")
    if (!device.hasAgent) throw offline("acciones/ejecutar-ordenes/sin-conexion")
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "runcommands"); put("nodeids", JsonArray(listOf(JsonPrimitive(device.id)))); put("type", shell.code); put("cmds", commands)
        put("runAsUser", 0); put("reply", true); put("responseid", id)
    },
        // The output comes as a "msg" from the agent; refusals come from the server as "runcommands".
        // La salida llega como «msg» del agente; los rechazos, del servidor como «runcommands».
        { it.str("responseid") == id && ((it.str("action") == "msg" && it.str("type") == "runcommands") || it.str("action") == "runcommands") },
        "acciones/ejecutar-ordenes/tiempo-agotado", "acciones/ejecutar-ordenes/conexion-cortada", timeoutMs)
    if (r.str("action") == "msg") return r.str("result").orEmpty()
    throw when (val result = r.str("result").orEmpty()) {
        "Access denied" -> denied("acciones/ejecutar-ordenes/no-autorizado", "run commands").apply { serverMessage = result }
        "Agent not connected" -> offline("acciones/ejecutar-ordenes/sin-conexion").apply { serverMessage = result }
        else -> refused("acciones/ejecutar-ordenes/rechazo-remoto", result)
    }
}

/** Removes it from MeshCentral (the agent, if any, stays installed). / Lo quita de MeshCentral (el agente sigue instalado). */
suspend fun MeshSession.removeDevice(device: Device) {
    if (!abilitiesOn(device).remove) throw denied("ficha/borrar/no-autorizado", "remove it")
    val id = MeshSession.newId()
    val r = request(buildJsonObject { put("action", "removedevices"); put("nodeids", JsonArray(listOf(JsonPrimitive(device.id)))); put("responseid", id) },
        { it.str("action") == "removedevices" && it.str("responseid") == id }, "ficha/borrar/tiempo-agotado", "ficha/borrar/conexion-cortada")
    if (r.str("result") != "ok") throw refused("ficha/borrar/rechazo-remoto", r.str("result"))
}

/** Asks the connected agent to uninstall itself; the server does not answer. / Pide al agente que se desinstale; no contesta. */
fun MeshSession.uninstallAgent(device: Device) {
    val can = abilitiesOn(device)
    if (!can.uninstall) throw if (device.hasAgent) denied("ficha/desinstalar/no-autorizado", "uninstall its agent") else offline("ficha/desinstalar/sin-conexion")
    send(buildJsonObject { put("action", "uninstallagent"); put("nodeids", JsonArray(listOf(JsonPrimitive(device.id)))) }, "ficha/desinstalar/conexion-cortada")
}

// ------------------------------------------------------------------ clipboard / portapapeles

/**
 * MeshCentral's own clipboard: text only (meshcore.js setclip). The agent answers {action:'msg', type:'setclip',
 * success:true}; a server with clipboardset off, or an agent still busy with the previous one, answers nothing.
 * El portapapeles de MeshCentral: solo texto. El agente contesta success; un servidor con clipboardset desactivado, o un
 * agente ocupado con el anterior, no contesta nada.
 */
suspend fun MeshSession.setDeviceClipboard(device: Device, text: String) {
    if (!MeshRights.has(rightsOn(device), MeshRights.REMOTE_CONTROL)) throw denied("portapapeles/sincronizar/no-autorizado", "use the clipboard")
    if (!serverInfo.clipboardSetAllowed) throw serverForbidsClipboard()
    if (!device.hasAgent) throw offline("portapapeles/sincronizar/sin-conexion")
    request(buildJsonObject { put("action", "msg"); put("type", "setclip"); put("nodeid", device.id); put("data", text) },
        { it.str("action") == "msg" && it.str("type") == "setclip" },
        "portapapeles/sincronizar/tiempo-agotado", "portapapeles/sincronizar/conexion-cortada", 15_000)
}

/**
 * The device's text, or null when there is none: the agent does not answer an empty clipboard, nor the server one it
 * forbids. Tag 3 keeps it out of the device's history, as the Windows app does.
 * El texto del equipo, o null si no hay: el agente no contesta si está vacío, ni el servidor si lo prohíbe. El tag 3 lo
 * deja fuera del historial del equipo, como hace la app de Windows.
 */
suspend fun MeshSession.deviceClipboard(device: Device, timeoutMs: Long = 8_000): String? {
    if (!MeshRights.has(rightsOn(device), MeshRights.REMOTE_CONTROL)) throw denied("portapapeles/sincronizar/no-autorizado", "use the clipboard")
    if (!serverInfo.clipboardGetAllowed) throw serverForbidsClipboard()
    if (!device.hasAgent) throw offline("portapapeles/sincronizar/sin-conexion")
    return try {
        request(buildJsonObject { put("action", "msg"); put("type", "getclip"); put("nodeid", device.id); put("tag", 3) },
            { it.str("action") == "msg" && it.str("type") == "getclip" && (it.str("nodeid") == null || it.str("nodeid") == device.id) },
            "portapapeles/sincronizar/tiempo-agotado", "portapapeles/sincronizar/conexion-cortada", timeoutMs).str("data")
    } catch (e: MeshClientException) {
        if (e.failure == MeshFailure.Timeout) null else throw e
    }
}
