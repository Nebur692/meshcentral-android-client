package io.github.nebur692.meshclient

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.FilesSession
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.RemoteEntry
import io.github.nebur692.meshclient.core.RemoteEntryKind
import io.github.nebur692.meshclient.core.RemotePaths
import io.github.nebur692.meshclient.core.TerminalKeys
import io.github.nebur692.meshclient.core.TerminalSession
import io.github.nebur692.meshclient.core.TunnelProtocol
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// ------------------------------------------------------------------ terminal

sealed interface TerminalState {
    data object Connecting : TerminalState
    data object Open : TerminalState
    /** [error] null: closed by the person. / [error] null: la cerró la persona. */
    data class Closed(val error: UiError?) : TerminalState
}

/**
 * One terminal on one device. The page (xterm.js) only draws and passes keys; Ctrl and Alt of the key bar, copy and
 * paste are here. What the device printed is kept (up to [KEEP] bytes), so a page made again (the screen turned, the
 * app came back) shows it all again. / Una terminal en un equipo. La página (xterm.js) solo pinta y pasa las teclas;
 * el Ctrl y el Alt de la barra, copiar y pegar están aquí. Lo que escribió el equipo se guarda (hasta [KEEP] bytes),
 * para que una página hecha de nuevo (se giró la pantalla, se volvió a la app) lo enseñe todo otra vez.
 */
class TerminalModel(private val session: MeshSession, val device: Device, val kind: TunnelProtocol, private val scope: CoroutineScope) {
    var state by mutableStateOf<TerminalState>(TerminalState.Connecting)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var recorded by mutableStateOf(false)
        private set
    /** The whole text, for the selection sheet; null when it is not shown. / Todo el texto, para la hoja de selección. */
    var selection by mutableStateOf<String?>(null)
    var fontSize by mutableIntStateOf(13)
        private set

    /** Posts to the page; set by the screen while it has one. / Manda a la página; lo pone la pantalla mientras tiene una. */
    var toPage: ((String) -> Unit)? = null

    private var term: TerminalSession? = null
    private var reading: Job? = null
    private val kept = ByteArrayOutputStream()

    fun fromPage(json: String) {
        val m = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        when ((m["t"] as? JsonPrimitive)?.content) {
            "ready" -> ready(m.int("cols") ?: 80, m.int("rows") ?: 24)
            "input" -> type((m["d"] as? JsonPrimitive)?.content.orEmpty())
            "size" -> runCatching { term?.resize(m.int("cols") ?: return, m.int("rows") ?: return) }
            "title" -> title = (m["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            "font" -> m.int("size")?.let { fontSize = it }
            "text" -> selection = (m["text"] as? JsonPrimitive)?.content.orEmpty()
            "error" -> android.util.Log.e("MeshTerminal", "page: " + (m["text"] as? JsonPrimitive)?.content)
        }
    }

    private fun ready(cols: Int, rows: Int) {
        // A new page: everything so far, then whatever comes. / Una página nueva: todo lo de hasta ahora, y lo que venga.
        if (kept.size() > 0) post("data", kept.toByteArray())
        (state as? TerminalState.Closed)?.let { closedOnPage?.invoke(it) }
        val t = term
        if (t != null) { runCatching { t.resize(cols, rows) }; return }
        if (reading != null) return
        reading = scope.launch {
            try {
                val opened = TerminalSession.open(session, device.id, kind, cols, rows)
                term = opened
                recorded = opened.isRecorded
                state = TerminalState.Open
                opened.output.collect { bytes ->
                    keep(bytes)
                    post("data", bytes)
                }
                end(null)
            } catch (e: MeshClientException) {
                end(UiError(e.failure, e.key, e.httpStatus))
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /** Says on the page why it ended, in the person's words; set by the screen. / Dice en la página por qué acabó. */
    var closedOnPage: ((TerminalState.Closed) -> Unit)? = null

    private fun end(error: UiError?) {
        if (state is TerminalState.Closed) return
        // The output ends without an error only when the person closed it; otherwise it was cut.
        // La salida acaba sin error solo si la cerró la persona; si no, se cortó.
        val closed = TerminalState.Closed(if (closedByPerson) null else error ?: UiError(MeshFailure.ConnectionLost, "tunel/ver-pantalla/conexion-cortada"))
        state = closed
        closedOnPage?.invoke(closed)
    }

    private var closedByPerson = false

    private fun keep(bytes: ByteArray) {
        kept.write(bytes)
        if (kept.size() > KEEP) {
            val all = kept.toByteArray()
            kept.reset()
            kept.write(all, all.size - KEEP / 2, KEEP / 2)
        }
    }

    private fun post(type: String, bytes: ByteArray) =
        toPage?.invoke(buildJsonObject { put("t", type); put("b64", Base64.getEncoder().encodeToString(bytes)) }.toString())

    fun postText(type: String, text: String) = toPage?.invoke(buildJsonObject { put("t", type); put("text", text) }.toString())

    /** A key typed on the page, or a key of the bar; Ctrl and Alt count once. / Una tecla; Ctrl y Alt valen una vez. */
    private fun type(input: String) {
        if (input.isEmpty()) return
        val out = TerminalKeys.modified(input, ctrl, alt)
        ctrl = false; alt = false
        write(out)
    }

    /** A character of the key bar, as if typed (Ctrl and Alt count). / Un carácter de la barra, como tecleado. */
    fun typeFromBar(text: String) = type(text)

    fun key(name: String) = toPage?.invoke(buildJsonObject { put("t", "key"); put("name", name) }.toString())

    fun paste(text: String) = postText("paste", text)

    fun requestText() = toPage?.invoke("""{"t":"text-request"}""")

    fun focus() = toPage?.invoke("""{"t":"focus"}""")

    fun setDark(dark: Boolean) = toPage?.invoke(buildJsonObject { put("t", "theme"); put("dark", dark) }.toString())

    private fun write(text: String) {
        val t = term ?: return
        try { t.write(text) } catch (e: MeshClientException) { end(UiError(e.failure, e.key, e.httpStatus)) }
    }

    /** Ends it (leaving the screen). / La acaba (al salir de la pantalla). */
    fun close() {
        closedByPerson = true
        reading?.cancel()
        term?.close()
        if (state !is TerminalState.Closed) state = TerminalState.Closed(null)
    }

    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    companion object {
        const val KEEP = 2 * 1024 * 1024
    }
}

// ------------------------------------------------------------------ files / ficheros

/** A copy going on: [up] to the device or down from it. / Una copia en marcha: [up] al equipo o desde él. */
data class Transfer(val name: String, val up: Boolean, val done: Long, val total: Long)

/**
 * The files of one device: one folder at a time, its changes, and one copy at a time with its progress. Every change is
 * checked by listing again, as the agent does not answer them. / Los ficheros de un equipo: una carpeta cada vez, sus
 * cambios y una copia cada vez con su avance. Cada cambio se comprueba volviendo a listar: el agente no los contesta.
 */
class FilesModel(private val session: MeshSession, val device: Device, private val scope: CoroutineScope, private val resolver: ContentResolver) {
    var path by mutableStateOf(RemotePaths.start(device))
        private set
    var entries by mutableStateOf<List<RemoteEntry>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
    var transfer by mutableStateOf<Transfer?>(null)
        private set
    /** Something done, for a short message: a string id. / Algo hecho, para un mensaje corto: un id de texto. */
    var done by mutableStateOf<Int?>(null)

    private var files: FilesSession? = null
    private var job: Job? = null
    private var copy: Job? = null

    private suspend fun files(): FilesSession =
        files?.takeIf { it.tunnel.isOpen } ?: FilesSession.open(session, device.id).also { files = it }

    fun open(to: String = path) {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val folder = files().list(to)
                path = folder.path.ifEmpty { to }
                // Folders first, then files; each by name, as a person looks for them. / Carpetas primero y luego ficheros, por nombre.
                entries = folder.entries.sortedWith(compareBy<RemoteEntry> { it.kind == RemoteEntryKind.File }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            } finally {
                loading = false
            }
        }
    }

    fun enter(e: RemoteEntry) = open(RemotePaths.join(path, e.name))

    fun up(): Boolean {
        if (RemotePaths.isTop(path)) return false
        open(RemotePaths.parent(path))
        return true
    }

    private fun change(ok: Int, block: suspend (FilesSession) -> Unit) {
        scope.launch {
            try {
                block(files())
                done = ok
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            }
            open()
        }
    }

    fun createFolder(name: String) = change(R.string.files_folder_created) { it.createFolder(RemotePaths.join(path, name)) }

    fun rename(e: RemoteEntry, newName: String) = change(R.string.files_renamed) { it.rename(path, e.name, newName) }

    fun delete(e: RemoteEntry) = change(R.string.files_deleted) { it.delete(path, listOf(e.name), recursive = e.kind != RemoteEntryKind.File) }

    /** A file of the phone (from the system's picker) to the folder shown. / Un fichero del móvil a la carpeta que se ve. */
    fun upload(uri: Uri) {
        if (copy?.isActive == true) return
        val folder = path
        copy = scope.launch {
            var name = "file"
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            transfer = Transfer(name, up = true, done = 0, total = size)
            try {
                // Opened and closed in one place, off the main thread. / Se abre y se cierra en un solo sitio, fuera del hilo principal.
                val sent = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { s -> files().upload(folder, name, s, size) { n -> transfer = Transfer(name, true, n, size) } } != null
                }
                if (!sent) throw MeshClientException(MeshFailure.Rejected, "ficheros/subir/permisos-fichero", "The file on this phone could not be read.")
                done = R.string.files_uploaded
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            } finally {
                transfer = null
            }
            open()
        }
    }

    /** A device's file to where the person chose (the system's "save as"). / Un fichero del equipo a donde eligió la persona. */
    fun download(e: RemoteEntry, to: Uri) {
        if (copy?.isActive == true) return
        val remote = RemotePaths.join(path, e.name)
        copy = scope.launch {
            transfer = Transfer(e.name, up = false, done = 0, total = e.size)
            var ok = false
            try {
                val written = withContext(Dispatchers.IO) {
                    resolver.openOutputStream(to, "wt")?.use { s -> files().download(remote, s) { n -> transfer = Transfer(e.name, false, n, e.size) } } != null
                }
                if (!written) throw MeshClientException(MeshFailure.Rejected, "ficheros/descargar/permisos-fichero", "The downloaded file could not be written on this phone.")
                ok = true
                done = R.string.files_downloaded
            } catch (x: MeshClientException) {
                error = UiError(x.failure, x.key, x.httpStatus)
            } finally {
                transfer = null
                // Half a file is worse than none. / Medio fichero es peor que ninguno.
                if (!ok) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, to) } }
            }
        }
    }

    fun cancelTransfer() { copy?.cancel() }

    fun close() {
        job?.cancel(); copy?.cancel()
        files?.close()
        files = null
    }
}
