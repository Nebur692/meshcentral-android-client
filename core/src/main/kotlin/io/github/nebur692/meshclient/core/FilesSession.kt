package io.github.nebur692.meshclient.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class RemoteEntryKind { Drive, Folder, File }

/** One item of a remote folder (MeshCentral "ls": n, t, s, d, f). / Un elemento de una carpeta remota. */
data class RemoteEntry(val name: String, val kind: RemoteEntryKind, val size: Long, val modified: Instant?, val free: Long?)

data class RemoteFolder(val path: String, val entries: List<RemoteEntry>)

/**
 * The Files tunnel (protocol 5), as the website, meshctrl and the Windows app use it. The agent answers commands with
 * JSON sent as BINARY frames, and file data as binary frames with a 4-byte header, so frames are told apart by their
 * first byte ('{' = JSON). One operation at a time: download data carries no request id.
 * El túnel de ficheros (protocolo 5), como lo usan la web, meshctrl y la app de Windows. El agente contesta las órdenes
 * con JSON en mensajes BINARIOS y los datos con mensajes binarios de 4 bytes de cabecera, así que se distinguen por el
 * primer byte («{» = JSON). Una operación cada vez: los datos de una descarga no llevan identificador.
 */
class FilesSession private constructor(val tunnel: MeshTunnel, private val timeoutMs: Long) {
    private val one = Mutex()

    // ------------------------------------------------------------------ listing / listar

    /** "" lists the drives on Windows and "/" elsewhere. / «» lista las unidades en Windows y «/» en el resto. */
    suspend fun list(path: String): RemoteFolder = one.withLock {
        val reqid = newId()
        send(buildJsonObject { put("action", "ls"); put("reqid", reqid); put("path", path) }, "ficheros/ver-pantalla/conexion-cortada")
        val reply = receiveJson("ficheros/ver-pantalla/tiempo-agotado", "ficheros/ver-pantalla/conexion-cortada") {
            it.str("reqid") == reqid && (it.str("action") == null || it.containsKey("dir"))
        }
        val dir = reply["dir"] as? JsonArray
            ?: throw MeshClientException(MeshFailure.NotFound, "ficheros/ver-pantalla/no-encontrado", "The folder \"$path\" does not exist or cannot be read.")
        val entries = dir.filterIsInstance<JsonObject>().map {
            RemoteEntry(
                it.str("n").orEmpty(),
                when (it.long("t")) { 1L -> RemoteEntryKind.Drive; 2L -> RemoteEntryKind.Folder; else -> RemoteEntryKind.File },
                it.long("s") ?: 0,
                date(it["d"]),
                it.long("f"),
            )
        }.filter { it.name.isNotEmpty() }
        RemoteFolder(reply.str("path") ?: path, entries)
    }

    // ------------------------------------------------------------------ changes / cambios

    // The agent does not answer these: whoever calls checks with a new listing (the app always does).
    // El agente no contesta a estas: quien llama lo comprueba volviendo a listar (la app lo hace siempre).

    suspend fun createFolder(folderPath: String) =
        command(buildJsonObject { put("action", "mkdir"); put("reqid", newId()); put("path", folderPath) })

    suspend fun rename(folder: String, oldName: String, newName: String) =
        command(buildJsonObject { put("action", "rename"); put("reqid", newId()); put("path", folder); put("oldname", oldName); put("newname", newName) })

    suspend fun delete(folder: String, names: List<String>, recursive: Boolean) =
        command(buildJsonObject {
            put("action", "rm"); put("reqid", newId()); put("path", folder)
            put("delfiles", JsonArray(names.map(::JsonPrimitive))); put("rec", recursive)
        })

    private suspend fun command(cmd: JsonObject) = one.withLock { send(cmd, "ficheros/gestionar-ficheros/conexion-cortada") }

    // ------------------------------------------------------------------ upload / subir

    /** Uploads [source] as [folder]/[name] (overwrites); [progress] gets the bytes sent. / Sube [source] (sobrescribe). */
    suspend fun upload(folder: String, name: String, source: InputStream, size: Long, progress: (Long) -> Unit = {}) = one.withLock {
        val reqid = newId()
        var started = false
        try {
            send(buildJsonObject { put("action", "upload"); put("reqid", reqid); put("path", folder); put("name", name); put("size", size) },
                "ficheros/subir/conexion-cortada")
            val first = receiveJson("ficheros/subir/tiempo-agotado", "ficheros/subir/conexion-cortada") { it.str("action") in setOf("uploadstart", "uploaderror") }
            if (first.str("action") == "uploaderror")
                throw MeshClientException(MeshFailure.Rejected, "ficheros/subir/rechazo-remoto", "The device cannot write \"$name\" in \"$folder\" (no permission, or the folder does not exist).")
            started = true
            val buffer = ByteArray(UPLOAD_BLOCK + 1)
            var sent = 0L
            var inFlight = 0
            var eof = false
            while (true) {
                while (!eof && inFlight < UPLOAD_WINDOW) {
                    val n = try { source.read(buffer, 1, UPLOAD_BLOCK) } catch (e: IOException) {
                        throw MeshClientException(MeshFailure.Rejected, "ficheros/subir/permisos-fichero", "The file on this phone could not be read.", e)
                    }
                    if (n <= 0) { eof = true; break }
                    // A block that starts with 0 or '{' gets a 0 in front, or the agent would take it for a command.
                    // A un bloque que empieza por 0 o «{» se le pone un 0 delante, o el agente lo tomaría por una orden.
                    val escape = buffer[1] == 0.toByte() || buffer[1] == '{'.code.toByte()
                    val block = if (escape) { buffer[0] = 0; buffer.copyOfRange(0, n + 1) } else buffer.copyOfRange(1, n + 1)
                    tunnel.send(block, "ficheros/subir/conexion-cortada")
                    inFlight++
                    sent += n
                    progress(sent)
                }
                if (inFlight == 0) break
                val ack = receiveJson("ficheros/subir/tiempo-agotado", "ficheros/subir/conexion-cortada") { it.str("action") in setOf("uploadack", "uploaderror") }
                if (ack.str("action") == "uploaderror")
                    throw MeshClientException(MeshFailure.Rejected, "ficheros/subir/rechazo-remoto", "The device failed writing \"$name\" (disk full or no permission).")
                inFlight--
            }
            send(buildJsonObject { put("action", "uploaddone"); put("reqid", reqid) }, "ficheros/subir/conexion-cortada")
            receiveJson("ficheros/subir/tiempo-agotado", "ficheros/subir/conexion-cortada") { it.str("action") == "uploaddone" }
            started = false
        } finally {
            // Cancelled or failed half-way: the agent deletes the partial file. / Cancelada o fallida a medias: el agente borra el fichero a medias.
            if (started) { runCatching { tunnel.sendText(buildJsonObject { put("action", "uploadcancel"); put("reqid", reqid) }.toString()) }; withContext(NonCancellable) { drain() } }
        }
    }

    // ------------------------------------------------------------------ download / bajar

    /** Downloads [remotePath] into [destination]; [progress] gets the bytes received. / Baja [remotePath] a [destination]. */
    suspend fun download(remotePath: String, destination: OutputStream, progress: (Long) -> Unit = {}) = one.withLock {
        val id = newId()
        var started = false
        fun cmd(sub: String) = buildJsonObject { put("action", "download"); put("sub", sub); put("id", id) }
        try {
            send(buildJsonObject { put("action", "download"); put("sub", "start"); put("id", id); put("path", remotePath) }, "ficheros/descargar/conexion-cortada")
            val first = receiveJson("ficheros/descargar/tiempo-agotado", "ficheros/descargar/conexion-cortada") { it.str("action") == "download" && it.str("id") == id }
            if (first.str("sub") != "start")
                throw MeshClientException(MeshFailure.NotFound, "ficheros/descargar/no-encontrado", "The device cannot open \"$remotePath\" (it does not exist, it is a folder, or there is no permission).")
            started = true
            send(cmd("startack"), "ficheros/descargar/conexion-cortada")
            repeat(DOWNLOAD_WINDOW - 1) { send(cmd("ack"), "ficheros/descargar/conexion-cortada") }
            var received = 0L
            while (true) {
                val frame = receive("ficheros/descargar/tiempo-agotado", "ficheros/descargar/conexion-cortada")
                if (frame.isNotEmpty() && frame[0] == '{'.code.toByte()) {
                    val o = parse(frame)
                    if (o?.str("action") == "download" && o.str("sub") == "cancel")
                        throw MeshClientException(MeshFailure.Rejected, "ficheros/descargar/rechazo-remoto", "The device stopped sending \"$remotePath\".")
                    continue
                }
                if (frame.size < 4) continue
                val header = ((frame[0].toInt() and 0xff) shl 24) or ((frame[1].toInt() and 0xff) shl 16) or ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
                if (frame.size > 4) {
                    try { destination.write(frame, 4, frame.size - 4) } catch (e: IOException) {
                        throw MeshClientException(MeshFailure.Rejected, "ficheros/descargar/permisos-fichero", "The downloaded file could not be written on this phone.", e)
                    }
                    received += frame.size - 4
                    progress(received)
                }
                if (header and 1 != 0) { started = false; break }
                send(cmd("ack"), "ficheros/descargar/conexion-cortada")
            }
        } finally {
            if (started) { runCatching { tunnel.sendText(cmd("stop").toString()) }; withContext(NonCancellable) { drain() } }
        }
    }

    fun close() = tunnel.close()

    // ------------------------------------------------------------------ plumbing / fontanería

    private fun send(cmd: JsonObject, lostKey: String) = tunnel.sendText(cmd.toString(), lostKey)

    private suspend fun receive(timeoutKey: String, lostKey: String): ByteArray = try {
        withTimeout(timeoutMs) {
            when (val f = tunnel.frames.receive()) {
                is TunnelFrame.Binary -> f.bytes
                is TunnelFrame.Text -> f.text.toByteArray(Charsets.UTF_8)
                is TunnelFrame.Control -> ByteArray(0)
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw MeshClientException(MeshFailure.Timeout, timeoutKey, "The device did not answer in ${timeoutMs / 1000} s.")
    } catch (e: ClosedReceiveChannelException) {
        throw MeshSession.lost(lostKey, e)
    } catch (e: MeshClientException) {
        throw MeshSession.lost(lostKey, e)
    }

    /** Waits for a JSON reply that matches, skipping anything else. / Espera una respuesta JSON que case, saltándose lo demás. */
    private suspend fun receiveJson(timeoutKey: String, lostKey: String, match: (JsonObject) -> Boolean): JsonObject {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (System.currentTimeMillis() > until) throw MeshClientException(MeshFailure.Timeout, timeoutKey, "The device did not answer in ${timeoutMs / 1000} s.")
            val frame = receive(timeoutKey, lostKey)
            if (frame.isEmpty() || frame[0] != '{'.code.toByte()) continue
            val o = parse(frame) ?: continue
            if (match(o)) return o
        }
    }

    /** After a cancelled transfer, late blocks must not reach the next operation. / Tras cancelar, los bloques tardíos no pueden llegar a la siguiente operación. */
    private suspend fun drain() {
        runCatching { withTimeout(DRAIN_MS) { while (true) tunnel.frames.receive() } }
    }

    companion object {
        private const val UPLOAD_BLOCK = 65564
        private const val UPLOAD_WINDOW = 32

        /**
         * The agent sends one block of 16 KB per ack, whatever "startack" says (meshcore.js), so acks go ahead of time (the
         * Windows app measured 0.2 MB/s with one and 18 MB/s with 128 at 80 ms of round trip).
         * El agente manda un bloque de 16 KB por confirmación, así que se adelantan (la app de Windows midió 0,2 MB/s con
         * una y 18 MB/s con 128 a 80 ms de ida y vuelta).
         */
        private const val DOWNLOAD_WINDOW = 128
        private const val DRAIN_MS = 1500L

        private fun newId(): String = MeshSession.newId().take(12)

        private fun parse(frame: ByteArray): JsonObject? = runCatching { Json.parseToJsonElement(frame.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

        internal fun date(n: kotlinx.serialization.json.JsonElement?): Instant? {
            val p = n as? JsonPrimitive ?: return null
            if (p.isString) return runCatching { Instant.parse(p.content) }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(p.content).toInstant() }.getOrNull()
            return p.longOrNull?.let(Instant::ofEpochMilli)
        }

        suspend fun open(session: MeshSession, nodeId: String): FilesSession =
            FilesSession(MeshTunnel.open(session, nodeId, TunnelProtocol.Files), session.timeoutMs)
    }
}

/**
 * Remote paths as the agent takes them, the same as the Windows app: "/" separators, "" for the list of drives of a
 * Windows device, "C:/" for a drive. / Rutas remotas como las toma el agente, igual que la app de Windows: separador
 * «/», «» para la lista de unidades de un equipo Windows, «C:/» para una unidad.
 */
object RemotePaths {
    fun join(folder: String, name: String): String =
        if (folder.isEmpty()) name.trimEnd('/', '\\') + "/" else folder.trimEnd('/') + "/" + name

    fun parent(path: String): String {
        val p = path.trimEnd('/')
        if (p.isEmpty()) return "/"
        val i = p.lastIndexOf('/')
        return when {
            i < 0 -> ""                                  // "C:" → the drives / las unidades
            i == 0 -> "/"
            i == 2 && p[1] == ':' -> p.substring(0, 3)   // "C:/Users" → "C:/"
            else -> p.substring(0, i)
        }
    }

    /** Where a device's files start. / Dónde empiezan los ficheros de un equipo. */
    fun start(device: Device): String = if (device.isWindows) "" else "/"

    /** The top: the drives of a Windows device, or "/" elsewhere. / Lo más alto: las unidades de Windows, o «/». */
    fun isTop(path: String): Boolean = path.isEmpty() || path == "/"
}
