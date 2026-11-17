package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

/** How a sign-in went. / Cómo fue una entrada. */
enum class LoginKind { Valid, Token, WrongSecondFactor, Locked, WrongPassword, Other }

/** One sign-in: when (ms since 1970), how, the login token used and where from (address, browser, system). / Una entrada. */
data class LoginEntry(val timeMs: Long, val kind: LoginKind, val tokenName: String?, val details: List<String>)

/**
 * This account's previous sign-ins, good and failed, newest first, as the website's "Previous logins" (meshuser.js
 * previousLogins): 107 valid (or with a login token), 108 wrong second factor, 109 locked, 110 wrong password. MeshCentral
 * notes only sign-ins through its web pages, not the control channel the apps use with a password.
 * Las entradas anteriores de esta cuenta, buenas y fallidas, primero las nuevas. MeshCentral solo apunta las de sus
 * páginas web, no las del canal de control que usan las apps con contraseña.
 */
suspend fun MeshSession.previousLogins(): List<LoginEntry> {
    val r = request(buildJsonObject { put("action", "previousLogins") },
        { it.str("action") == "previousLogins" && it["events"] is JsonArray && it.str("userid") == null },
        "usuarios/cambiar-ajustes/tiempo-agotado", "usuarios/cambiar-ajustes/conexion-cortada")
    return (r["events"] as JsonArray).filterIsInstance<JsonObject>().map { e ->
        val tn = e.str("tn")
        val kind = when (e.long("m")) {
            107L -> if (tn == null) LoginKind.Valid else LoginKind.Token
            108L -> LoginKind.WrongSecondFactor; 109L -> LoginKind.Locked; 110L -> LoginKind.WrongPassword; else -> LoginKind.Other
        }
        // A browser or system it could not tell comes as null, not as text. / Un navegador o sistema que no supo llega como null.
        val details = (e["a"] as? JsonArray)?.mapNotNull { it.text?.takeIf(String::isNotEmpty) }.orEmpty()
        LoginEntry(timeOf(e["t"]), kind, tn, details)
    }.sortedByDescending { it.timeMs }
}

/** The server sends dates as milliseconds or as text. / El servidor manda las fechas en milisegundos o en texto. */
private fun timeOf(t: JsonElement?): Long {
    val p = t as? JsonPrimitive ?: return 0
    p.longOrNull?.let { return it }
    return runCatching { OffsetDateTime.parse(p.content).toInstant().toEpochMilli() }.getOrDefault(0)
}
