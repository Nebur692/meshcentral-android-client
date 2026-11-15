package io.github.nebur692.meshclient.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The person's own account and the server's live state, ported from the Windows app (MeshServerSession.Users/Server)
 * with what was measured there on MeshCentral 1.3.1: a new password the server's rules refuse gets no answer at all,
 * backup codes are not answered without another second factor, and "serverstats" is dropped silently without the rights,
 * so all of that is checked first.
 * La cuenta propia y el estado en vivo del servidor, portados de la app de Windows con lo medido allí: una contraseña
 * nueva que rechazan las reglas del servidor no recibe respuesta, los códigos de respaldo no se contestan sin otro doble
 * factor y «serverstats» se tira sin decir nada si no hay permiso, así que todo eso se comprueba antes.
 */

/** One of the ten backup codes; each works once. / Uno de los diez códigos de respaldo; cada uno sirve una vez. */
data class BackupCode(val code: String, val used: Boolean)

enum class BackupCodeAction(val code: Int) { Show(0), New(1), Clear(2) }

/** A security key (FIDO) of the account; added only from a browser (WebAuthn). / Una llave de seguridad; solo se añade desde un navegador. */
data class SecurityKey(val index: Int, val name: String, val type: String?)

/** The authenticator app to add: its secret and the otpauth:// address. / La app de autenticación por añadir: su secreto y la dirección otpauth://. */
data class AuthenticatorSetup(val secret: String, val uri: String)

/**
 * The server's own state, sent every few seconds while asked for. [state]: UserAccounts, DeviceGroups, AgentSessions,
 * ConnectedUsers, UsersSessions, RelaySessions… as meshuser.js names them; [cpuAverage]: the 1, 5 and 15 minute load.
 * El estado del propio servidor, mandado cada pocos segundos mientras se pide.
 */
data class ServerStats(
    val totalMemory: Long, val freeMemory: Long, val availableMemory: Long?, val cpuAverage: List<Double>,
    val state: Map<String, Long>, val agentErrors: Map<String, Long>,
) {
    /** The memory in use, from what is available when the server says it (Linux), else free. / La memoria en uso. */
    val usedMemory: Long get() = (totalMemory - (availableMemory ?: freeMemory)).coerceAtLeast(0)

    internal companion object {
        private fun counters(o: JsonObject?): Map<String, Long> =
            o.orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap()

        fun from(o: JsonObject): ServerStats {
            val values = o["values"] as? JsonObject
            return ServerStats(o.long("totalmem") ?: 0, o.long("freemem") ?: 0, o.long("availablemem"),
                (o["cpuavg"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 },
                counters(values?.get("ServerState") as? JsonObject), counters(values?.get("AgentErrorCounters") as? JsonObject))
        }
    }
}

private fun accountError(failure: MeshFailure, key: String, message: String, said: String? = null) =
    MeshClientException(failure, key, message).apply { serverMessage = said }

private const val TIMEOUT = "usuarios/cambiar-ajustes/tiempo-agotado"
private const val LOST = "usuarios/cambiar-ajustes/conexion-cortada"

/** "Lock account settings": the server ignores password and two-factor changes. / El servidor ignora cambios de contraseña y doble factor. */
val MeshSession.ownSettingsLocked: Boolean get() = !user.isFullAdministrator && user.siteRights and SiteRights.LOCK_SETTINGS != 0L

private fun MeshSession.checkOwnSettings() {
    if (ownSettingsLocked) throw accountError(MeshFailure.NotAllowed, "usuarios/cambiar-ajustes/sin-permiso", "The settings of this account are locked by an administrator.")
}

/**
 * Changes the own password. The server answers with a notification (msgid 20 changed, 21 wrong current password, 17 used
 * before, 18 too common); a new password its rules refuse gets no answer at all, so the wait says that.
 * Cambia la contraseña propia. El servidor contesta con una notificación; una nueva que sus reglas rechazan no recibe
 * respuesta ninguna, y la espera lo dice.
 */
suspend fun MeshSession.changePassword(current: String, newPassword: String) {
    checkOwnSettings()
    if (current.isEmpty() || current.length > 256 || newPassword.isEmpty() || newPassword.length > 256)
        throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "Both passwords need 1 to 256 characters.")
    val r = request(buildJsonObject { put("action", "changepassword"); put("oldpass", current); put("newpass", newPassword) },
        { it.str("action") == "msg" && it.str("type") == "notify" && it.long("titleid") == 4L && (it.long("msgid") ?: 0) in 17..21 }, TIMEOUT, LOST)
    when (r.long("msgid")) {
        20L -> return
        21L -> throw accountError(MeshFailure.InvalidCredentials, "usuarios/cambiar-ajustes/credencial-invalida", "The current password is not right.", r.str("value"))
        17L, 18L -> throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "The server does not accept that new password (used before or too common).", r.str("value"))
        else -> throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/error-remoto", "The server said: ${r.str("value")}.", r.str("value"))
    }
}

/** Asks for a new authenticator secret; nothing is saved until [confirmAuthenticator]. / Pide un secreto nuevo; no se guarda hasta confirmarlo. */
suspend fun MeshSession.startAuthenticator(): AuthenticatorSetup {
    checkOwnSettings()
    if (user.hasAuthenticator) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/conflicto-estado", "This account already has an authenticator app; remove it first.")
    val r = request(buildJsonObject { put("action", "otpauth-request") }, { it.str("action") == "otpauth-request" }, TIMEOUT, LOST)
    val secret = r.str("secret"); val url = r.str("url")
    if (secret != null && url != null) return AuthenticatorSetup(secret, url)
    throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/rechazo-remoto", when (r.long("err")) {
        1L, 4L -> "This server does not allow authenticator apps."
        5L -> "The settings of this account are locked by an administrator."
        else -> "The server refused (error ${r.long("err")})."
    })
}

/** Saves the authenticator once a code from it is right. / Guarda la app cuando un código suyo es correcto. */
suspend fun MeshSession.confirmAuthenticator(setup: AuthenticatorSetup, code: String) {
    checkOwnSettings()
    val digits = code.filter(Char::isDigit)
    if (digits.length != 6) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "The code has 6 digits.")
    val r = request(buildJsonObject { put("action", "otpauth-setup"); put("secret", setup.secret); put("token", digits) },
        { it.str("action") == "otpauth-setup" }, TIMEOUT, LOST)
    if (!r.bool("success")) throw accountError(MeshFailure.InvalidTwoFactorCode, "usuarios/cambiar-ajustes/credencial-invalida", "That code is not right; check the phone's clock and try the next one.")
}

suspend fun MeshSession.removeAuthenticator() {
    checkOwnSettings()
    val r = request(buildJsonObject { put("action", "otpauth-clear") }, { it.str("action") == "otpauth-clear" }, TIMEOUT, LOST)
    if (!r.bool("success")) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/conflicto-estado", "This account has no authenticator app.")
}

/**
 * Shows, makes new or clears the backup codes. The server only answers while the account has another second factor, so
 * without one it is not sent. / Enseña, hace nuevos o borra los códigos de respaldo. El servidor solo contesta mientras la
 * cuenta tiene otro doble factor, así que sin él no se manda.
 */
suspend fun MeshSession.backupCodes(action: BackupCodeAction): List<BackupCode> {
    if (action != BackupCodeAction.Show) checkOwnSettings()
    if (!user.hasAuthenticator && user.securityKeys == 0 && !user.hasTwoFactor)
        throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/conflicto-estado", "Backup codes need an authenticator app or a security key first.")
    val r = request(buildJsonObject { put("action", "otpauth-getpasswords"); put("subaction", action.code) },
        { it.str("action") == "otpauth-getpasswords" }, TIMEOUT, LOST)
    // "u" is true while the code is still unused. / «u» es true mientras el código no se ha usado.
    return (r["passwords"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .map { k -> BackupCode((k.long("p") ?: 0).toString().padStart(8, '0'), !k.bool("u")) }
}

suspend fun MeshSession.securityKeys(): List<SecurityKey> {
    val r = request(buildJsonObject { put("action", "otp-hkey-get") }, { it.str("action") == "otp-hkey-get" }, TIMEOUT, LOST)
    return (r["keys"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .map { k -> SecurityKey((k.long("i") ?: 0).toInt(), k.str("name").orEmpty(), k.str("type")) }
}

/** The server never answers otp-hkey-remove: confirmed by its "Removed security key" event (msgid 94). / Se confirma con su aviso. */
suspend fun MeshSession.removeSecurityKey(key: SecurityKey) {
    checkOwnSettings()
    val me = user.id
    request(buildJsonObject { put("action", "otp-hkey-remove"); put("index", key.index) },
        { it.str("action") == "event" && (it["event"] as? JsonObject)?.let { e ->
            e.str("action") == "accountchange" && e.long("msgid") == 94L && (e["account"] as? JsonObject)?.str("_id") == me } == true },
        TIMEOUT, LOST)
}

// ------------------------------------------------------------------ email and SMS / correo y SMS

/** An own-account change the server confirms only with its event (msgid). / Un cambio de la cuenta que solo confirma su aviso. */
private fun MeshSession.ownAccountEvent(msgid: Long): (JsonObject) -> Boolean {
    val me = user.id
    return { it.str("action") == "event" && (it["event"] as? JsonObject)?.let { e ->
        e.str("action") == "accountchange" && e.long("msgid") == msgid && (e["account"] as? JsonObject)?.str("_id") == me } == true }
}

/**
 * Codes by email at sign-in on or off (otpemail), as the website's "Email verification" in the account. The server must
 * offer it (a mail server) and the account have an email; it answers otpemail with success.
 * Códigos por correo al entrar, sí o no, como «Verificación por correo» de la web. El servidor tiene que ofrecerlo (con
 * servidor de correo) y la cuenta tener correo; contesta otpemail con success.
 */
suspend fun MeshSession.setEmailTwoFactor(enabled: Boolean) {
    checkOwnSettings()
    if (!serverInfo.emailTwoFactorAllowed) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/no-autorizado", "This server does not offer codes by email.")
    if (enabled && user.email.isNullOrEmpty()) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/falta-dato", "This account has no email for the codes.")
    if (user.hasEmailTwoFactor == enabled) return
    val r = request(buildJsonObject { put("action", "otpemail"); put("enabled", enabled) }, { it.str("action") == "otpemail" }, TIMEOUT, LOST)
    if (!r.bool("success")) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/rechazo-remoto", "The server did not change the codes by email.")
    user = user.copy(hasEmailTwoFactor = enabled, hasTwoFactor = enabled || user.hasTwoFactor)
}

/** True for what MeshCentral takes as a phone: an optional +, then 10 or more digits, spaces, dashes or dots. / Lo que admite como teléfono. */
fun validPhone(phone: String): Boolean {
    val p = phone.trim().removePrefix("+")
    return p.length in 10..17 && p.all { it.isDigit() || it == ' ' || it == '-' || it == '.' }
}

/** A phone being verified: the server sent it a code by SMS and keeps this cookie. / Un teléfono a verificar. */
class PhoneCheck internal constructor(val phone: String, internal val cookie: String)

/** Sends a code by SMS to [phone] (verifyPhone); then [confirmPhone] with it. / Manda un código por SMS; luego confirmPhone. */
suspend fun MeshSession.startPhone(phone: String): PhoneCheck {
    checkOwnSettings()
    if (!serverInfo.smsTwoFactorAllowed) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/no-autorizado", "This server does not send SMS.")
    if (!validPhone(phone)) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "\"$phone\" is not a phone number (+ and 10 digits or more).")
    val r = request(buildJsonObject { put("action", "verifyPhone"); put("phone", phone.trim()) }, { it.str("action") == "verifyPhone" }, TIMEOUT, LOST)
    val cookie = r.str("cookie")
    if (!r.bool("success") || cookie == null) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/error-remoto", "The server could not send the SMS.")
    return PhoneCheck(phone.trim(), cookie)
}

/**
 * Confirms the phone with the code it got. A right code is answered only by the account's event (msgid 96); a wrong one
 * by verifyPhone again, 2 to 6 s later (the server slows guesses down).
 * Confirma el teléfono con el código recibido. Un código bueno solo lo contesta el aviso de la cuenta; uno malo, verifyPhone
 * otra vez, de 2 a 6 s después (el servidor frena los intentos).
 */
suspend fun MeshSession.confirmPhone(check: PhoneCheck, code: String) {
    checkOwnSettings()
    val digits = code.filter(Char::isDigit)
    if (digits.length != 6) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "The code has 6 digits.")
    val verified = ownAccountEvent(96)
    val r = request(buildJsonObject { put("action", "confirmPhone"); put("cookie", check.cookie); put("code", digits) },
        { verified(it) || (it.str("action") == "verifyPhone" && it.str("cookie") == check.cookie) }, TIMEOUT, LOST, maxOf(timeoutMs, 10_000))
    if (!verified(r)) throw accountError(MeshFailure.InvalidTwoFactorCode, "usuarios/cambiar-ajustes/credencial-invalida", "That code is not the one sent by SMS.")
    user = user.copy(phone = check.phone, hasTwoFactor = true)
}

/** Removes the phone (removePhone); confirmed by the account's event (msgid 97). / Quita el teléfono; lo confirma su aviso. */
suspend fun MeshSession.removePhone() {
    checkOwnSettings()
    if (user.phone == null) return
    request(buildJsonObject { put("action", "removePhone") }, ownAccountEvent(97), TIMEOUT, LOST)
    user = user.copy(phone = null)
}

// ------------------------------------------------------------------ messaging (ntfy) / mensajería (ntfy)

/** A messaging handle being verified: the server sent it a code and keeps this cookie. / Un canal de mensajería a verificar. */
class MessagingCheck internal constructor(val handle: String, internal val cookie: String)

/**
 * Sends a check code to an ntfy topic (verifyMessaging, service 64). The server must have ntfy among its messaging
 * services; if not it answers nothing, so the wait ends saying so. With UnifiedPush and the ntfy distributor, the topic
 * is this app's own endpoint, so the code comes to the app itself.
 * Manda un código de comprobación a un tema de ntfy. El servidor tiene que tener ntfy en su mensajería; si no, no contesta y
 * la espera lo dice. Con UnifiedPush y el distribuidor ntfy, el tema es la dirección de la propia app.
 */
suspend fun MeshSession.startNtfy(topic: String): MessagingCheck {
    checkOwnSettings()
    if (!serverInfo.messagingTwoFactorAllowed) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/no-autorizado", "This server does not send codes by messaging.")
    val t = topic.trim()
    if (t.isEmpty() || t.length > 64 || !t.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "\"$topic\" is not an ntfy topic.")
    val r = try {
        request(buildJsonObject { put("action", "verifyMessaging"); put("service", 64); put("handle", t) }, { it.str("action") == "verifyMessaging" },
            "usuarios/cambiar-ajustes/no-autorizado", LOST, 10_000)
    } catch (e: MeshClientException) {
        if (e.failure != MeshFailure.Timeout) throw e
        throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/no-autorizado", "This server does not have ntfy among its messaging services.")
    }
    val cookie = r.str("cookie")
    if (!r.bool("success") || cookie == null) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/error-remoto", "The server could not send to ntfy.")
    return MessagingCheck("ntfy:$t", cookie)
}

/** Confirms the handle with the code it got: right = the account's event 156, wrong = verifyMessaging again. / Confirma el canal. */
suspend fun MeshSession.confirmMessaging(check: MessagingCheck, code: String) {
    checkOwnSettings()
    val digits = code.filter(Char::isDigit)
    if (digits.length != 6) throw accountError(MeshFailure.Rejected, "usuarios/cambiar-ajustes/dato-invalido", "The code has 6 digits.")
    val verified = ownAccountEvent(156)
    val r = request(buildJsonObject { put("action", "confirmMessaging"); put("cookie", check.cookie); put("code", digits) },
        { verified(it) || (it.str("action") == "verifyMessaging" && it.str("cookie") == check.cookie) }, TIMEOUT, LOST, maxOf(timeoutMs, 10_000))
    if (!verified(r)) throw accountError(MeshFailure.InvalidTwoFactorCode, "usuarios/cambiar-ajustes/credencial-invalida", "That code is not the one sent.")
    user = user.copy(messagingHandle = check.handle, hasTwoFactor = true)
}

/** Removes the messaging handle (removeMessaging); confirmed by the account's event 157. / Quita el canal de mensajería. */
suspend fun MeshSession.removeMessaging() {
    checkOwnSettings()
    if (user.messagingHandle == null) return
    request(buildJsonObject { put("action", "removeMessaging") }, ownAccountEvent(157), TIMEOUT, LOST)
    user = user.copy(messagingHandle = null)
}

/** The first run of 6 digits in a message from the server (its code). / El primer grupo de 6 cifras de un mensaje (su código). */
fun codeIn(message: String): String? = Regex("""(?<!\d)(\d{6})(?!\d)""").find(message)?.groupValues?.get(1)

// ------------------------------------------------------------------ server state / estado del servidor

/** Server backup, restore or update rights (siteadmin bits 1, 4 or 16), as the website asks. / Copia, restauración o actualización. */
val MeshSession.canSeeServer: Boolean get() = user.isFullAdministrator || user.siteRights and 21L != 0L

/**
 * The server's live state every [everyMs] while collected; stopping the collection stops it on the server.
 * El estado en vivo del servidor cada [everyMs] mientras se recoge; dejar de recogerlo lo para en el servidor.
 */
fun MeshSession.serverStats(everyMs: Long = 5_000): Flow<ServerStats> {
    if (!canSeeServer) throw accountError(MeshFailure.NotAllowed, "servidor/abrir-detalle/no-autorizado",
        "Only an administrator with server backup, restore or update rights sees the server's state.")
    return serverStatsFlow
        // Asked once listening, so the first one (sent at once) is not missed. / Se pide ya escuchando, para no perder el primero.
        .onSubscription { send(buildJsonObject { put("action", "serverstats"); put("interval", everyMs.coerceIn(1_000, 1_000_000)) }, "servidor/abrir-detalle/conexion-cortada") }
        .map(ServerStats::from)
        // An interval out of range clears the server's timer. / Un intervalo fuera de rango quita el temporizador del servidor.
        .onCompletion { runCatching { send(buildJsonObject { put("action", "serverstats") }, "servidor/abrir-detalle/conexion-cortada") } }
}
