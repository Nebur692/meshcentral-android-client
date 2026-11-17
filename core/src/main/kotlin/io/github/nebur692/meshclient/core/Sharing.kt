package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil

/** What a guest may use through a share link (MeshCentral's "p" bits). / Lo que puede usar un invitado. */
object ShareKinds {
    const val TERMINAL = 1
    const val DESKTOP = 2
    const val FILES = 4
}

/**
 * One link that lets a guest without an account reach a device. [url]: only for the account that made it, or a server
 * administrator (the server hides it from others). Times in milliseconds since 1970.
 * Un enlace que deja a un invitado sin cuenta llegar a un equipo. [url]: solo para quien lo hizo o un administrador.
 */
data class DeviceShare(
    val publicId: String, val guestName: String, val kinds: Int, val startMs: Long?, val expiresMs: Long?,
    val viewOnly: Boolean, val asksConsent: Boolean, val url: String?, val userId: String?,
) {
    internal companion object {
        fun from(o: JsonObject) = DeviceShare(
            o.str("publicid").orEmpty(), o.str("guestName") ?: o.str("guestname").orEmpty(), (o.long("p") ?: 0).toInt(),
            o.long("startTime")?.takeIf { it > 0 }, o.long("expireTime")?.takeIf { it > 0 },
            o.bool("viewOnly"), (o.long("consent") ?: 0) and 0x38L != 0L, o.str("url"), o.str("userid"))
    }
}

/*
 * Sharing a device with a guest, as the website's "Share" (meshuser.js createDeviceShareLink, deviceShares and
 * removeDeviceShare), the same requests the Windows app measured on MeshCentral 1.3.1. It needs remote control and guest
 * sharing rights on the device, without limited input.
 * Compartir un equipo con un invitado, como «Compartir» de la web, las mismas peticiones que midió la app de Windows.
 * Pide control remoto y compartir con invitados, sin entrada limitada.
 */

fun MeshSession.canShare(device: Device): Boolean {
    val r = rightsOn(device)
    return device.hasAgent && (r == MeshRights.FULL ||
        (MeshRights.has(r, MeshRights.REMOTE_CONTROL) && MeshRights.has(r, MeshRights.GUEST_SHARING) && !MeshRights.has(r, MeshRights.LIMITED_INPUT)))
}

private fun cannotShare(what: String) = MeshClientException(MeshFailure.NotAllowed, "ficha/compartir/no-autorizado", "This account has no rights to $what on this device.")

/** Makes a link for [guest], valid [durationMs] from now; [askConsent] asks the person at the device before each use. / Hace un enlace. */
suspend fun MeshSession.shareDevice(device: Device, guest: String, kinds: Int, durationMs: Long, askConsent: Boolean): DeviceShare {
    if (!canShare(device)) throw cannotShare("share it")
    val name = guest.trim()
    if (name.isEmpty() || name.length > 64 || kinds and 7 == 0 || durationMs <= 0)
        throw MeshClientException(MeshFailure.Rejected, "ficha/compartir/dato-invalido", "A share needs the guest's name, what it may use and a time.")
    // Consent bits: 8 desktop, 16 terminal, 32 files ask; the website also notifies (1, 2, 4). / Pedir y avisar por cada cosa.
    var consent = 0
    if (askConsent) {
        if (kinds and ShareKinds.DESKTOP != 0) consent = consent or 8 or 1
        if (kinds and ShareKinds.TERMINAL != 0) consent = consent or 16 or 2
        if (kinds and ShareKinds.FILES != 0) consent = consent or 32 or 4
    }
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "createDeviceShareLink"); put("nodeid", device.id); put("guestname", name); put("p", kinds and 7)
        put("expire", ceil(durationMs / 60_000.0).toInt()); put("consent", consent); put("responseid", id)
    }, { (it.str("action") == "createDeviceShareLink" || it.str("action") == "deviceShares") && it.str("responseid") == id },
        "ficha/compartir/tiempo-agotado", "ficha/compartir/conexion-cortada")
    val url = r.str("url")
    if (r.str("result") != "OK" || url.isNullOrEmpty())
        throw MeshClientException(MeshFailure.Rejected, "ficha/compartir/rechazo-remoto", "The server did not make the link: ${r.str("result")}.").apply { serverMessage = r.str("result") }
    val now = System.currentTimeMillis()
    return DeviceShare(r.str("publicid").orEmpty(), name, kinds and 7, now, now + durationMs, r.bool("viewOnly"), askConsent, url, user.id)
}

/** The device's links still valid (the server drops the expired). / Los enlaces del equipo que siguen valiendo. */
suspend fun MeshSession.deviceShares(device: Device): List<DeviceShare> {
    if (!canShare(device)) throw cannotShare("see its shares")
    // The answer carries no responseid: it is told by its device. / La respuesta no trae responseid: se reconoce por su equipo.
    val r = request(buildJsonObject { put("action", "deviceShares"); put("nodeid", device.id) },
        { it.str("action") == "deviceShares" && it.str("nodeid") == device.id && it["deviceShares"] is JsonArray },
        "ficha/compartir/tiempo-agotado", "ficha/compartir/conexion-cortada")
    return (r["deviceShares"] as JsonArray).filterIsInstance<JsonObject>().map(DeviceShare::from)
}

/** Ends a link: the guest can no longer use it. / Acaba un enlace: el invitado ya no puede usarlo. */
suspend fun MeshSession.removeDeviceShare(device: Device, share: DeviceShare) {
    if (!canShare(device)) throw cannotShare("remove its shares")
    val id = MeshSession.newId()
    val r = request(buildJsonObject { put("action", "removeDeviceShare"); put("nodeid", device.id); put("publicid", share.publicId); put("responseid", id) },
        { it.str("action") == "removeDeviceShare" && it.str("responseid") == id }, "ficha/compartir/tiempo-agotado", "ficha/compartir/conexion-cortada")
    val res = r.str("result")
    if (res != null && res != "OK")
        throw MeshClientException(MeshFailure.Rejected, "ficha/compartir/rechazo-remoto", "The server did not remove the link: $res.").apply { serverMessage = res }
}
