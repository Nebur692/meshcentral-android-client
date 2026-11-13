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
import io.github.nebur692.meshclient.core.rightsOn
import io.github.nebur692.meshclient.core.TerminalKeys
import io.github.nebur692.meshclient.core.TerminalConnection
import io.github.nebur692.meshclient.core.TunnelProtocol
import io.github.nebur692.meshclient.core.deviceClipboard
import io.github.nebur692.meshclient.core.setDeviceClipboard
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
class TerminalModel(
    val device: Device,
    /** What kind of terminal, for the title (cmd, PowerShell, SSH · user@host…). / Qué terminal, para el título. */
    val label: String,
    /** Opens it at the page's size: the agent's terminal or SSH. / La abre al tamaño de la página: la del agente o SSH. */
    private val opener: suspend (cols: Int, rows: Int) -> TerminalConnection,
    private val scope: CoroutineScope,
    /** The same terminal again, after it ended. / La misma terminal otra vez, cuando acabó. */
    val again: () -> Unit,
) {
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

    // The phone's clipboard, set by the screen: a finger selection (where it ended, in page pixels), a copy from a hardware
    // keyboard, a paste asked from it. / El portapapeles del móvil, lo pone la pantalla: una selección con el dedo, una
    // copia desde un teclado físico, un pegado pedido desde él.
    var onSelection: ((text: String, x: Float, y: Float) -> Unit)? = null
    var onSelectionGone: (() -> Unit)? = null
    var onCopy: ((String) -> Unit)? = null
    var onPasteRequest: (() -> Unit)? = null

    /** Ctrl+V pastes, as in Windows Terminal: the device's own shells on Windows, not SSH. / Ctrl+V pega en las shells de Windows. */
    val windowsKeys: Boolean get() = device.isWindows && !label.startsWith("SSH")

    private var term: TerminalConnection? = null
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
            "selection" -> onSelection?.invoke((m["text"] as? JsonPrimitive)?.content.orEmpty(), m.float("x"), m.float("y"))
            "selection-gone" -> onSelectionGone?.invoke()
            "copy" -> (m["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { onCopy?.invoke(it) }
            "paste-request" -> onPasteRequest?.invoke()
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
                val opened = opener(cols, rows)
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

    fun selectAll(x: Float, y: Float) = toPage?.invoke(buildJsonObject { put("t", "select-all"); put("x", x); put("y", y) }.toString())

    fun clearSelection() = toPage?.invoke("""{"t":"clear-selection"}""")

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
    private fun JsonObject.float(k: String) = (this[k] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f

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
class FilesModel(private val session: () -> MeshSession?, val device: Device, private val scope: CoroutineScope, private val resolver: ContentResolver) {
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
        files?.takeIf { it.tunnel.isOpen } ?: FilesSession.open(
            session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "tunel/abrir-detalle/conexion-cortada", "no session"), device.id
        ).also { files = it }

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

// ------------------------------------------------------------------ remote desktop / escritorio remoto

enum class DesktopQuality(val quality: Int, val scaling: Int, val frameMs: Int, val label: Int) {
    // The same three as the Windows app. / Las mismas tres que la app de Windows.
    High(85, 1024, 50, R.string.desk_quality_high),
    Balanced(60, 1024, 100, R.string.desk_quality_balanced),
    Saver(35, 512, 200, R.string.desk_quality_saver),
}

sealed interface DesktopState {
    data object Connecting : DesktopState
    data object Open : DesktopState
    data class Closed(val error: UiError?) : DesktopState
}

/**
 * One remote desktop. The tiles are decoded off the main thread and drawn on a bitmap of the remote screen; [frame]
 * changes when there is something new to show. Ctrl and Alt of the key bar stay pressed for the next key; a letter or a
 * digit with them goes as its virtual key, so Ctrl+C or Alt+F4 work whatever the keyboard.
 * Un escritorio remoto. Las teselas se decodifican fuera del hilo principal y se pintan en un mapa de bits de la
 * pantalla remota; [frame] cambia cuando hay algo nuevo que enseñar. Ctrl y Alt de la barra se quedan pulsadas para la
 * tecla siguiente; una letra o un número con ellas va como su tecla virtual, para que Ctrl+C o Alt+F4 funcionen.
 */
/** Out of sight, one frame every so many milliseconds. / Fuera de la vista, una imagen cada tantos milisegundos. */
private const val HIDDEN_FRAME_MS = 2000
/** How often the device's clipboard is asked while on screen (the website asks every second). / Cada cuánto se pregunta. */
private const val CLIP_POLL_MS = 2000L
/** An empty clipboard gets no answer from the agent: waited this long. / Un portapapeles vacío no recibe respuesta: se espera esto. */
private const val CLIP_WAIT_MS = 2500L

class DesktopModel(private val session: () -> MeshSession?, val device: Device, private val scope: CoroutineScope) {
    var state by mutableStateOf<DesktopState>(DesktopState.Connecting)
        private set
    /** The remote screen, drawn on the main thread. / La pantalla remota, pintada en el hilo principal. */
    var bitmap by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    var frame by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var noImage by mutableStateOf(false)
        private set
    var displays by mutableStateOf<io.github.nebur692.meshclient.core.DesktopEvent.Displays?>(null)
        private set
    var quality by mutableStateOf(DesktopQuality.Balanced)
        private set
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    /** Touchpad: the finger moves a pointer; otherwise a tap clicks where it is. / Touchpad: el dedo mueve un puntero. */
    var touchpad by mutableStateOf(false)
    /** The pointer in touchpad mode, in remote pixels. / El puntero en modo touchpad, en píxeles remotos. */
    var pointer by mutableStateOf(android.graphics.PointF(0f, 0f))
        private set

    private var desk: io.github.nebur692.meshclient.core.DesktopSession? = null
    private var job: Job? = null
    private var closedByPerson = false
    private var hidden = false

    fun open() {
        if (job != null) return
        job = scope.launch {
            try {
                val d = io.github.nebur692.meshclient.core.DesktopSession.open(
                    session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "tunel/abrir-detalle/conexion-cortada", "no session"), device.id, scope)
                desk = d
                d.setQuality(quality.quality, quality.scaling, quality.frameMs)
                if (hidden) onScreen(false)
                state = DesktopState.Open
                startClipboard()
                d.events.collect { e ->
                    when (e) {
                        is io.github.nebur692.meshclient.core.DesktopEvent.Size -> newScreen(e.width, e.height)
                        is io.github.nebur692.meshclient.core.DesktopEvent.Tile -> draw(e)
                        is io.github.nebur692.meshclient.core.DesktopEvent.Message -> message = e.text
                        is io.github.nebur692.meshclient.core.DesktopEvent.Displays -> displays = e
                        io.github.nebur692.meshclient.core.DesktopEvent.NoImage -> noImage = true
                        is io.github.nebur692.meshclient.core.DesktopEvent.Cursor -> cursor = e.shape
                        else -> Unit
                    }
                }
                end(null)
            } catch (e: MeshClientException) {
                end(UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    private fun newScreen(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val old = bitmap
        if (old != null && old.width == w && old.height == h) return
        bitmap = androidx.core.graphics.createBitmap(w, h).apply { eraseColor(android.graphics.Color.BLACK) }
        pointer = android.graphics.PointF(w / 2f, h / 2f)
        frame++
    }

    private suspend fun draw(t: io.github.nebur692.meshclient.core.DesktopEvent.Tile) {
        val target = bitmap ?: return
        if (hidden) return // repainted whole on return / se repinta entera al volver
        // Decoded off the main thread; drawn on it, where the screen reads the bitmap, so no half-drawn piece is shown.
        // Se decodifica fuera del hilo principal y se pinta en él, donde la pantalla lee el mapa de bits: nada a medias.
        val piece = withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size) } ?: return
        if (bitmap !== target) { piece.recycle(); return }
        android.graphics.Canvas(target).drawBitmap(piece, t.x.toFloat(), t.y.toFloat(), null)
        piece.recycle()
        noImage = false
        frame++
    }

    private fun end(error: UiError?) {
        if (state is DesktopState.Closed) return
        state = DesktopState.Closed(if (closedByPerson) null else error ?: UiError(MeshFailure.ConnectionLost, "tunel/ver-pantalla/conexion-cortada"))
    }

    // ------------------------------------------------------------------ input / entrada

    private fun clamp(x: Float, y: Float): Pair<Int, Int> {
        val b = bitmap ?: return 0 to 0
        return x.toInt().coerceIn(0, b.width - 1) to y.toInt().coerceIn(0, b.height - 1)
    }

    fun click(x: Float, y: Float, button: io.github.nebur692.meshclient.core.MouseButton = io.github.nebur692.meshclient.core.MouseButton.Left) {
        val d = desk ?: return
        val (px, py) = clamp(x, y)
        d.mouseMove(px, py); d.mouseButton(button, true, px, py); d.mouseButton(button, false, px, py)
    }

    fun doubleClick(x: Float, y: Float) { val (px, py) = clamp(x, y); desk?.doubleClick(px, py) }

    fun press(x: Float, y: Float, down: Boolean) {
        val (px, py) = clamp(x, y)
        desk?.mouseButton(io.github.nebur692.meshclient.core.MouseButton.Left, down, px, py)
    }

    // ------------------------------------------------------------------ physical keyboard and mouse / teclado y ratón físicos

    /** The device's pointer shape (MeshCentral's numbers: 2 cross, 3 hand, 5 text). / La forma del puntero del equipo. */
    var cursor by mutableIntStateOf(0)
        private set
    private val keys = PhysicalKeyboard()
    private var lastMouse = -1 to -1

    /** A physical key; false if it is not for the device (Back, volume). / Una tecla física; false si no es para el equipo. */
    fun physicalKey(e: android.view.KeyEvent): Boolean {
        if (desk == null || state != DesktopState.Open) return false
        val out = when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> keys.press(e.keyCode, e.getUnicodeChar(e.metaState), e.repeatCount)
            android.view.KeyEvent.ACTION_UP -> keys.release(e.keyCode)
            else -> null
        } ?: return false
        send(out)
        return true
    }

    /** Nothing stays held on the device. / Nada se queda pulsado en el equipo. */
    fun releaseKeys() = send(keys.releaseAll())

    private fun send(out: List<KeyOut>) {
        val d = desk ?: return
        for (o in out) when (o) {
            is KeyOut.Key -> d.key(o.vk, o.down, o.extended)
            is KeyOut.Text -> String(Character.toChars(o.codePoint)).forEach(d::char)
        }
    }

    /** A mouse moving over the picture; the same pixel again is not sent. / Un ratón moviéndose; el mismo píxel no se manda otra vez. */
    fun mouseMove(x: Float, y: Float) {
        val p = clamp(x, y)
        if (p == lastMouse) return
        lastMouse = p
        if (touchpad) pointer = android.graphics.PointF(p.first.toFloat(), p.second.toFloat())
        desk?.mouseMove(p.first, p.second)
    }

    /** A mouse button, with the keyboard's Ctrl or Shift if held. / Un botón del ratón, con Ctrl o Mayús si están pulsadas. */
    fun mouseButton(x: Float, y: Float, button: io.github.nebur692.meshclient.core.MouseButton, down: Boolean) {
        send(keys.beforeClick())
        val (px, py) = clamp(x, y)
        desk?.mouseMove(px, py)
        desk?.mouseButton(button, down, px, py)
    }

    fun move(x: Float, y: Float) { val (px, py) = clamp(x, y); desk?.mouseMove(px, py) }

    fun wheel(x: Float, y: Float, delta: Int) { val (px, py) = clamp(x, y); desk?.wheel(px, py, delta) }

    /** Touchpad: moves the pointer by a finger's drag, already in remote pixels. / Touchpad: mueve el puntero. */
    fun movePointer(dx: Float, dy: Float) {
        val b = bitmap ?: return
        pointer = android.graphics.PointF((pointer.x + dx).coerceIn(0f, b.width - 1f), (pointer.y + dy).coerceIn(0f, b.height - 1f))
        move(pointer.x, pointer.y)
    }

    /** Text from the phone's keyboard. / Texto del teclado del móvil. */
    fun type(text: String) {
        val d = desk ?: return
        for (c in text) {
            when {
                c == '\n' -> tap(13)
                (ctrl || alt) && (c.isLetterOrDigit() && c.code < 128) -> withModifiers { d.key(c.uppercaseChar().code, true); d.key(c.uppercaseChar().code, false) }
                else -> { d.char(c); ctrl = false; alt = false }
            }
        }
    }

    /** A key of the bar (Windows virtual key), with Ctrl and Alt if pressed. / Una tecla de la barra con Ctrl y Alt si están pulsadas. */
    fun tap(virtualKey: Int, extended: Boolean = false) {
        val d = desk ?: return
        withModifiers { d.key(virtualKey, true, extended); d.key(virtualKey, false, extended) }
    }

    private inline fun withModifiers(block: () -> Unit) {
        val d = desk ?: return
        val c = ctrl; val a = alt
        if (c) d.key(17, true); if (a) d.key(18, true)
        block()
        if (a) d.key(18, false); if (c) d.key(17, false)
        ctrl = false; alt = false
    }

    fun ctrlAltDel() = desk?.ctrlAltDel()

    fun refresh() = desk?.refresh()

    /**
     * Out of sight the desktop stays connected but costs little: paused (where the agent honours it; Linux's does not),
     * one frame every [HIDDEN_FRAME_MS] and nothing decoded. Back, its quality again and the whole screen.
     * Fuera de la vista el escritorio sigue conectado pero gasta poco: en pausa (donde el agente la respeta; el de Linux
     * no), una imagen cada [HIDDEN_FRAME_MS] y nada decodificado. Al volver, su calidad otra vez y la pantalla entera.
     */
    fun onScreen(visible: Boolean) {
        hidden = !visible
        if (!visible) releaseKeys()
        if (visible) startClipboard() else stopClipboard()
        val d = desk ?: return
        d.setPaused(!visible)
        d.setQuality(quality.quality, quality.scaling, if (visible) quality.frameMs else HIDDEN_FRAME_MS)
    }

    /** What a clipboard action did, shown for a moment. / Lo que hizo una acción del portapapeles, un momento. */
    var clipNotice by mutableStateOf<Notice?>(null)

    /**
     * The clipboard follows both ways while the desktop is on screen, as the website's "Automatic Clipboard": what the
     * device copies is asked every [CLIP_POLL_MS] (tag 3, out of the device's history; the agent has no push), and what
     * the phone copies goes at once. [lastSynced] is what both sides hold, so nothing bounces back. The first reading is
     * only a starting point: what the device had before is not poured onto the phone.
     * El portapapeles va en los dos sentidos mientras se ve el escritorio, como el «Automatic Clipboard» de la web: lo que
     * copia el equipo se pregunta cada [CLIP_POLL_MS] (el agente no avisa), y lo que copia el móvil va al momento.
     * [lastSynced] es lo que tienen los dos lados, para que nada rebote. La primera lectura solo es el punto de partida.
     */
    var autoClipboard by mutableStateOf(true)
        private set
    /** Set by the screen: the device copied this text. / Lo pone la pantalla: el equipo copió este texto. */
    var onDeviceCopied: ((String) -> Unit)? = null
    @Volatile private var lastSynced: String? = null
    private var clipJob: Job? = null
    private var baseline = false

    private fun clipboardAllowed(get: Boolean): Boolean {
        val s = session() ?: return false
        return io.github.nebur692.meshclient.core.MeshRights.has(s.rightsOn(device), io.github.nebur692.meshclient.core.MeshRights.REMOTE_CONTROL) &&
            (if (get) s.serverInfo.clipboardGetAllowed else s.serverInfo.clipboardSetAllowed)
    }

    private fun startClipboard() {
        if (clipJob != null || !autoClipboard || hidden || state != DesktopState.Open || !clipboardAllowed(get = true)) return
        clipJob = scope.launch {
            while (true) {
                val text = try { session()?.deviceClipboard(device, CLIP_WAIT_MS) } catch (e: MeshClientException) { null }
                if (!baseline) { baseline = true; if (lastSynced == null) lastSynced = text }
                else if (!text.isNullOrEmpty() && text != lastSynced) { lastSynced = text; onDeviceCopied?.invoke(text) }
                kotlinx.coroutines.delay(CLIP_POLL_MS)
            }
        }
    }

    private fun stopClipboard() { clipJob?.cancel(); clipJob = null }

    fun changeAutoClipboard(on: Boolean) {
        autoClipboard = on
        if (on) startClipboard() else stopClipboard()
    }

    /** The phone's clipboard has new text: to the device, unless it came from there. / El móvil tiene texto nuevo: al equipo, si no vino de allí. */
    fun phoneCopied(text: String?) {
        if (!autoClipboard || text.isNullOrEmpty() || text == lastSynced || state != DesktopState.Open || !clipboardAllowed(get = false)) return
        lastSynced = text
        scope.launch {
            clipNotice = try {
                session()?.setDeviceClipboard(device, text)
                Notice(R.string.desk_clip_synced)
            } catch (e: MeshClientException) {
                Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    /**
     * The phone's text to the device's clipboard (MeshCentral's own: text only), to paste there with Ctrl+V.
     * El texto del móvil al portapapeles del equipo (el de MeshCentral: solo texto), para pegarlo allí con Ctrl+V.
     */
    fun pasteToDevice(text: String?) {
        if (text.isNullOrEmpty()) { clipNotice = Notice(R.string.desk_clip_empty); return }
        scope.launch {
            clipNotice = try {
                val s = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "portapapeles/sincronizar/conexion-cortada", "no session")
                s.setDeviceClipboard(device, text)
                Notice(R.string.desk_clip_sent)
            } catch (e: MeshClientException) {
                Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    /** The device's clipboard text, handed to [onText] for the phone's. / El texto del portapapeles del equipo, para el del móvil. */
    fun copyFromDevice(onText: (String) -> Unit) {
        scope.launch {
            clipNotice = try {
                val s = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "portapapeles/sincronizar/conexion-cortada", "no session")
                val text = s.deviceClipboard(device)
                if (text.isNullOrEmpty()) Notice(R.string.desk_clip_device_empty) else { onText(text); Notice(R.string.desk_clip_got) }
            } catch (e: MeshClientException) {
                Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            }
        }
    }

    fun changeQuality(q: DesktopQuality) { quality = q; desk?.setQuality(q.quality, q.scaling, q.frameMs) }

    fun selectDisplay(id: Int) = desk?.selectDisplay(id)

    fun close() {
        closedByPerson = true
        stopClipboard()
        job?.cancel()
        desk?.close()
        if (state !is DesktopState.Closed) state = DesktopState.Closed(null)
    }
}
