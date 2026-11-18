package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** The device's icon, as the website's eight. / El icono del equipo, los ocho de la web. */
enum class DeviceIcon(val code: Int) { Desktop(1), Laptop(2), Phone(3), Server(4), Storage(5), Network(6), Board(7), VirtualMachine(8);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Desktop }
}

/** Icon, own ports (null = the usual one) and place on the map. / Icono, puertos propios (null = el normal) y sitio en el mapa. */
data class DeviceSettings(val icon: DeviceIcon, val rdpPort: Int?, val sshPort: Int?, val vncPort: Int?, val httpPort: Int?, val httpsPort: Int?, val location: DeviceLocation?) {
    companion object {
        fun of(d: Device) = DeviceSettings(DeviceIcon.of(d.icon), d.rdpPort, d.sshPort, d.vncPort, d.httpPort, d.httpsPort, d.location)
    }
}

/*
 * The device's icon, own ports and place on the map, as the website's device page (changedevice), the same as the
 * Windows app. Sending the usual port removes the own one. The place is kept only with geolocation on (feature 0x8000).
 * El icono, los puertos propios y el sitio en el mapa, como la ficha de la web. Mandar el puerto normal quita el propio.
 * El sitio solo se guarda con la geolocalización encendida.
 */

fun MeshSession.canChangeDeviceSettings(device: Device): Boolean = MeshRights.has(rightsOn(device), MeshRights.MANAGE_DEVICES)

val MeshSession.serverKeepsLocations: Boolean get() = serverInfo.features and 0x8000L != 0L

suspend fun MeshSession.saveDeviceSettings(device: Device, wanted: DeviceSettings) {
    if (!canChangeDeviceSettings(device)) throw MeshClientException(MeshFailure.NotAllowed, "ficha/cambiar-ajustes/sin-permiso", "This account has no rights to change this device.")
    val ports = listOf(wanted.rdpPort, wanted.sshPort, wanted.vncPort, wanted.httpPort, wanted.httpsPort)
    val l = wanted.location
    if (ports.any { it != null && it !in 1..65535 } || (l != null && (l.latitude !in -90.0..90.0 || l.longitude !in -180.0..180.0)))
        throw MeshClientException(MeshFailure.Rejected, "ficha/cambiar-ajustes/dato-invalido", "Ports go from 1 to 65535, latitude from -90 to 90 and longitude from -180 to 180.")
    val now = DeviceSettings.of(device)
    val id = MeshSession.newId()
    var changes = 0
    val cmd = buildJsonObject {
        put("action", "changedevice"); put("nodeid", device.id); put("responseid", id)
        if (wanted.icon != now.icon) { put("icon", wanted.icon.code); changes++ }
        fun port(field: String, want: Int?, have: Int?, usual: Int) { if (want != have) { put(field, want ?: usual); changes++ } }
        port("rdpport", wanted.rdpPort, now.rdpPort, 3389)
        port("sshport", wanted.sshPort, now.sshPort, 22)
        port("rfbport", wanted.vncPort, now.vncPort, 5900)
        port("httpport", wanted.httpPort, now.httpPort, 80)
        port("httpsport", wanted.httpsPort, now.httpsPort, 443)
        if (serverKeepsLocations && wanted.location != now.location) {
            put("userloc", JsonArray(wanted.location?.let { w -> listOf(w.latitude, w.longitude).map { JsonPrimitive(String.format(Locale.ROOT, "%.6f", it)) } }.orEmpty()))
            changes++
        }
    }
    if (changes == 0) return // nothing changes / no cambia nada
    val r = request(cmd, { it.str("action") == "changedevice" && it.str("responseid") == id },
        "ficha/cambiar-ajustes/tiempo-agotado", "ficha/cambiar-ajustes/conexion-cortada")
    val res = r.str("result")
    if (res != null && res != "ok")
        throw MeshClientException(MeshFailure.Rejected, "ficha/cambiar-ajustes/rechazo-remoto", "The server refused the change ($res).").apply { serverMessage = res }
}
