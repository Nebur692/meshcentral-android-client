package io.github.nebur692.meshclient.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What kind of session was recorded (relay "protocol"). / Qué tipo de sesión se grabó. */
enum class RecordedKind { Terminal, Desktop, Files, AdminPowerShell, UserShell, UserPowerShell, AmtWsman, AmtRedirection, Chat, Other;
    internal companion object {
        fun of(p: Long?) = when (p) {
            1L -> Terminal; 2L -> Desktop; 5L -> Files; 6L -> AdminPowerShell; 8L -> UserShell; 9L -> UserPowerShell
            100L -> AmtWsman; 101L -> AmtRedirection; 200L -> Chat; else -> Other
        }
    }
}

/**
 * One recorded session, as the website's "Recordings" lists it. [present]: the file is still on the server;
 * [userIds]: who was in it.
 * Una sesión grabada, como la lista la web. [present]: el fichero sigue en el servidor; [userIds]: quién estuvo.
 */
data class Recording(
    val fileName: String, val kind: RecordedKind, val deviceId: String?, val deviceName: String?, val groupName: String?,
    val userIds: List<String>, val startMs: Long, val seconds: Long, val bytes: Long, val present: Boolean,
) {
    internal companion object {
        fun from(o: JsonObject): Recording? {
            val f = o.str("filename") ?: return null
            return Recording(f, RecordedKind.of(o.long("protocol")), o.str("nodeid"), o.str("name"), o.str("meshname"),
                (o["userids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                o.long("startTime") ?: 0, o.long("lengthTime") ?: 0, o.long("size") ?: 0, o.long("present") == 1L)
        }
    }
}

/** This account may see recordings (site right 512); the server still has to have recording on. / Puede ver grabaciones. */
val MeshSession.canSeeRecordings: Boolean get() = user.isFullAdministrator || SiteRights.has(user.siteRights, SiteRights.MANAGE_RECORDINGS)

/**
 * The most recent recordings, newest first. The server stays silent when recording is off (meshuser.js "recordings"
 * returns without answering), so silence for [quietMs] means just that.
 * Las grabaciones más recientes, primero las nuevas. El servidor calla si la grabación está apagada, así que el silencio
 * durante [quietMs] significa eso.
 */
suspend fun MeshSession.recordings(limit: Int = 200, quietMs: Long = 8_000): List<Recording> {
    if (!canSeeRecordings) throw MeshClientException(MeshFailure.NotAllowed, "grabaciones/ver-grabaciones/no-autorizado", "This account has no rights to see recordings.")
    val tag = MeshSession.newId()
    val r = try {
        withTimeout(quietMs) {
            request(buildJsonObject { put("action", "recordings"); put("limit", limit); put("tag", tag) },
                { it.str("action") == "recordings" && it.str("tag") == tag },
                "grabaciones/ver-grabaciones/tiempo-agotado", "grabaciones/ver-grabaciones/conexion-cortada")
        }
    } catch (e: TimeoutCancellationException) {
        throw MeshClientException(MeshFailure.NotFound, "grabaciones/ver-grabaciones/no-encontrado", "This server does not record sessions.")
    }
    if (r.long("error") != null) throw MeshClientException(MeshFailure.Rejected, "grabaciones/ver-grabaciones/rechazo-remoto",
        "The server could not read its recordings (error ${r.long("error")}).")
    return (r["events"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull(Recording::from).sortedByDescending { it.startMs }
}

/**
 * Where the website downloads a recording. The server serves it only to a signed-in web session (webserver.js
 * handleGetRecordings reads req.session), never to the x-meshauth header nor the relay cookie: measured on 1.3.1,
 * both answer 401. So it can be downloaded with the cookies of a single sign-on, or opened in the browser.
 * Dónde baja la web una grabación. El servidor solo la da a una sesión web iniciada, nunca a la cabecera x-meshauth ni a
 * la cookie del relé (medido en 1.3.1: las dos dan 401). Así que se baja con las cookies del inicio de sesión único o se
 * abre en el navegador.
 */
fun MeshSession.recordingUrl(r: Recording): String =
    server.resolve("recordings.ashx").newBuilder().addQueryParameter("file", r.fileName).build().toString()

/**
 * The website's own list of recordings (viewmode 52 of default.handlebars), where the browser signs in if it has to and
 * then downloads or plays them. / La lista de grabaciones de la propia web, donde el navegador entra si hace falta.
 */
fun MeshSession.recordingsPageUrl(): String =
    server.resolve("").newBuilder().addQueryParameter("viewmode", "52").build().toString()

/** The website's player for a desktop or terminal recording. / El reproductor de la web. */
fun MeshSession.recordingPlayerUrl(r: Recording): String =
    server.resolve("player.htm").newBuilder().addQueryParameter("stream", r.fileName).build().toString()

/**
 * Downloads a recording into [out] with the web session's cookies; [progress] gets the bytes so far. Without a web
 * session there is nothing to send that the server accepts, so it says so before trying.
 * Baja una grabación a [out] con las cookies de la sesión web; [progress] recibe los bytes hasta ahora. Sin sesión web no
 * hay nada que el servidor acepte, así que lo dice antes de intentarlo.
 */
suspend fun MeshSession.downloadRecording(r: Recording, out: java.io.OutputStream, progress: (Long) -> Unit = {}): Long =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cookies = webCookies ?: throw MeshClientException(MeshFailure.NotAllowed, "grabaciones/descargar-grabacion/sesion-cerrada",
            "The server only gives recordings to a web session: open it in the browser.")
        if (!r.present) throw MeshClientException(MeshFailure.NotFound, "grabaciones/descargar-grabacion/no-encontrado", "The recording's file is no longer on the server.")
        val call = http.newCall(okhttp3.Request.Builder().url(recordingUrl(r)).header("Cookie", cookies).build())
        val response = try { call.execute() } catch (e: java.io.IOException) {
            throw MeshClientException(MeshFailure.ConnectionLost, "grabaciones/descargar-grabacion/conexion-cortada", "The download was cut.", e)
        }
        response.use { resp ->
            when {
                resp.code == 401 -> throw MeshClientException(MeshFailure.SessionExpired, "grabaciones/descargar-grabacion/sesion-cerrada",
                    "The server did not accept the web session: sign in again.")
                resp.code == 404 -> throw MeshClientException(MeshFailure.NotFound, "grabaciones/descargar-grabacion/no-encontrado", "The recording's file is no longer on the server.")
                !resp.isSuccessful -> throw MeshClientException(MeshFailure.HttpError, "grabaciones/descargar-grabacion/rechazo-remoto", "The server answered HTTP ${resp.code}.")
            }
            var total = 0L
            try {
                resp.body!!.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n; progress(total)
                    }
                }
            } catch (e: java.io.IOException) {
                throw MeshClientException(MeshFailure.ConnectionLost, "grabaciones/descargar-grabacion/conexion-cortada", "The download was cut.", e)
            }
            total
        }
    }
