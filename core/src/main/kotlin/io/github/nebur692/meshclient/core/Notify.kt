package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Notices when a device connects or disconnects (MeshCentral's notify bits). / Avisos al conectarse o desconectarse un equipo. */
object DeviceNotify {
    const val EMAIL_CONNECT = 16
    const val EMAIL_DISCONNECT = 32
    const val MESSAGE_CONNECT = 128
    const val MESSAGE_DISCONNECT = 256
    /** The bits the apps change; the rest (the website's, help requests) are kept. / Las que cambian las apps; el resto se conserva. */
    const val OURS = EMAIL_CONNECT or EMAIL_DISCONNECT or MESSAGE_CONNECT or MESSAGE_DISCONNECT
}

/*
 * This account's notices for a device group or a device, as the website's "Notifications" (meshuser.js changemeshnotify
 * and changeusernotify). The server sends them by email or by the account's messaging even with no app open. It answers
 * with an account event (msgid 73 group, 130 device), and nothing at all when nothing changes.
 * Los avisos de la cuenta para un grupo o un equipo, como «Notificaciones» de la web. Los manda el servidor por correo o
 * mensajería aunque no haya ninguna app abierta. Contesta con un aviso de la cuenta, y nada si no cambia nada.
 */

fun MeshSession.notifyOn(groupOrDeviceId: String): Int = user.notify[groupOrDeviceId] ?: 0

suspend fun MeshSession.setGroupNotify(group: DeviceGroup, wanted: Int) = setNotify("changemeshnotify", "meshid", group.id, 73, wanted)

suspend fun MeshSession.setDeviceNotify(device: Device, wanted: Int) = setNotify("changeusernotify", "nodeid", device.id, 130, wanted)

private suspend fun MeshSession.setNotify(action: String, field: String, id: String, msgid: Long, wanted: Int) {
    checkOwnSettings()
    val now = notifyOn(id)
    val flags = (now and DeviceNotify.OURS.inv()) or (wanted and DeviceNotify.OURS)
    if (flags == now) return // nothing changes: the server would not answer / no cambia nada: no contestaría
    val m = request(buildJsonObject { put("action", action); put(field, id); put("notify", flags) }, ownAccountEvent(msgid),
        "usuarios/cambiar-ajustes/tiempo-agotado", "usuarios/cambiar-ajustes/conexion-cortada")
    val account = (m["event"] as? JsonObject)?.get("account") as? JsonObject
    user = if (account != null) user.copy(notify = UserInfo.from(account).notify) else user.copy(notify = user.notify + (id to flags))
}
