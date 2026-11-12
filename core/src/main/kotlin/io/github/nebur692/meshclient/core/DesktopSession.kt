package io.github.nebur692.meshclient.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class MouseButton(val code: Int) { None(0x00), Left(0x02), Right(0x08), Middle(0x20) }

/** One display of the device, where it is in the stitched "all displays" image. / Una pantalla y dónde está en la imagen cosida. */
data class DisplayRect(val id: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/** What the agent says about its screen, in order. / Lo que dice el agente de su pantalla, en orden. */
sealed interface DesktopEvent {
    /** A JPEG piece of the screen at [x], [y]. / Un trozo JPEG de la pantalla en [x], [y]. */
    class Tile(val x: Int, val y: Int, val jpeg: ByteArray) : DesktopEvent
    data class Size(val width: Int, val height: Int) : DesktopEvent
    /** [all] (65535) shows every display stitched. / [all] (65535) enseña todas cosidas. */
    data class Displays(val displays: List<Int>, val selected: Int) : DesktopEvent { companion object { const val ALL = 65535 } }
    data class Layout(val displays: List<DisplayRect>) : DesktopEvent
    /** Something the agent says (no screen, waiting for consent…). / Algo que dice el agente (sin pantalla, esperando permiso…). */
    data class Message(val text: String) : DesktopEvent
    data class InputLock(val locked: Boolean) : DesktopEvent
    data class Cursor(val shape: Int) : DesktopEvent
    /** Asked several times and no image came, nor a word why. / Pedida varias veces, no llegó imagen ni por qué. */
    data object NoImage : DesktopEvent
}

/**
 * The remote desktop over a relay tunnel (protocol 2), as the website's agent-desktop and the Windows app speak it:
 * binary commands [cmd u16][size u16][data], JPEG tiles, and the jumbo header for those over 64 KB.
 * El escritorio remoto por un túnel de relé (protocolo 2), como lo hablan agent-desktop de la web y la app de Windows:
 * órdenes binarias [orden u16][tamaño u16][datos], teselas JPEG y la cabecera jumbo para las de más de 64 KB.
 */
class DesktopSession private constructor(val tunnel: MeshTunnel) {
    private val out = Channel<DesktopEvent>(Channel.UNLIMITED)
    private val parser = DesktopFrameParser(::process)
    private val tiles = AtomicLong()
    @Volatile private var agentSpoke = false
    private var jobs: List<Job> = emptyList()

    /** Everything the agent says about the screen; one collector at a time. / Todo lo que dice el agente; un colector a la vez. */
    val events: Flow<DesktopEvent> = out.receiveAsFlow()

    var quality = 60; private set
    /** 1024 = 100 %. */
    var scaling = 1024; private set
    var frameMs = 100; private set
    var width = 0; private set
    var height = 0; private set
    val tilesReceived: Long get() = tiles.get()

    private fun start(scope: CoroutineScope) {
        jobs = listOf(
            scope.launch {
                try {
                    for (f in tunnel.frames) when (f) {
                        is TunnelFrame.Binary -> parser.feed(f.bytes)
                        is TunnelFrame.Control -> if (f.message.str("type") == "console") f.message.str("msg")?.takeIf(String::isNotEmpty)?.let {
                            agentSpoke = true; out.trySend(DesktopEvent.Message(it))
                        }
                        is TunnelFrame.Text -> Unit
                    }
                    out.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    out.close(e)
                }
            },
            scope.launch {
                if (NoImageWatch.gaveUp({ tiles.get() > 0 || agentSpoke }, { refresh() }, NO_IMAGE_RETRIES, NO_IMAGE_WAIT_MS))
                    out.trySend(DesktopEvent.NoImage)
            },
        )
    }

    // ------------------------------------------------------------------ incoming / entrante

    internal fun process(cmd: Int, v: ByteArray) {
        when {
            cmd == 3 && v.size > 8 -> { tiles.incrementAndGet(); out.trySend(DesktopEvent.Tile(u16(v, 4), u16(v, 6), v.copyOfRange(8, v.size))) }
            cmd == 7 && v.size >= 8 -> {
                width = u16(v, 4); height = u16(v, 6)
                // What the website does on a new size: quality, unpause, ask the input lock, and no key left pressed.
                // Lo que hace la web con un tamaño nuevo: calidad, quitar la pausa, preguntar el bloqueo, ninguna tecla pulsada.
                send(qualityCommand(), bytes(0, 8, 0, 5, 0), bytes(0, 87, 0, 5, 2),
                    keyCommand(16, false, false), keyCommand(17, false, false), keyCommand(18, false, false), keyCommand(91, false, false), keyCommand(92, false, false),
                    bytes(0, 14, 0, 4))
                out.trySend(DesktopEvent.Size(width, height))
            }
            cmd == 11 && v.size >= 6 -> {
                val count = u16(v, 4)
                val list = (0 until count).takeWhile { 8 + it * 2 <= v.size }.map { u16(v, 6 + it * 2) }
                val selected = if (v.size >= 8 + count * 2) u16(v, 6 + count * 2) else 0
                out.trySend(DesktopEvent.Displays(list, selected))
            }
            (cmd == 17 || cmd == 65) && v.size > 4 -> {
                val text = String(v, 4, v.size - 4, Charsets.UTF_8)
                if (!text.startsWith('.')) { agentSpoke = true; out.trySend(DesktopEvent.Message(text)) }
            }
            cmd == 82 -> parseLayout(v)?.takeIf { it.isNotEmpty() }?.let { out.trySend(DesktopEvent.Layout(it)) }
            cmd == 87 && v.size == 5 -> out.trySend(DesktopEvent.InputLock(v[4].toInt() != 0))
            cmd == 88 && v.size == 5 -> out.trySend(DesktopEvent.Cursor(v[4].toInt() and 0xff))
        }
    }

    // ------------------------------------------------------------------ outgoing / saliente

    private fun send(vararg commands: ByteArray) {
        for (c in commands) try { tunnel.send(c) } catch (_: MeshClientException) { return } // the end comes through [events] / el final llega por [events]
    }

    private fun qualityCommand() = bytes(0, 5, 0, 10, 1, quality, scaling shr 8, scaling, frameMs shr 8, frameMs)

    fun key(virtualKey: Int, down: Boolean, extended: Boolean = false) = send(keyCommand(virtualKey, down, extended))

    /** Types a character whatever the remote keyboard layout. / Escribe un carácter sea cual sea el teclado remoto. */
    fun char(c: Char) = send(bytes(0, 85, 0, 7, 0, c.code shr 8, c.code), bytes(0, 85, 0, 7, 1, c.code shr 8, c.code))

    fun mouseMove(x: Int, y: Int) = send(mouseCommand(MouseButton.None, null, x, y))

    fun mouseButton(button: MouseButton, down: Boolean, x: Int, y: Int) = send(mouseCommand(button, down, x, y))

    fun doubleClick(x: Int, y: Int) = send(bytes(0, 2, 0, 10, 0, 0x88, x shr 8, x, y shr 8, y))

    /** 120 per wheel notch, positive = up. / 120 por paso de rueda, positivo = arriba. */
    fun wheel(x: Int, y: Int, delta: Int) = send(wheelCommand(x, y, delta))

    fun ctrlAltDel() = send(bytes(0, 10, 0, 4))

    fun refresh() = send(bytes(0, 6, 0, 4))

    fun setPaused(paused: Boolean) = send(bytes(0, 8, 0, 5, if (paused) 1 else 0))

    fun selectDisplay(display: Int) = send(bytes(0, 12, 0, 6, display shr 8, display))

    /** quality 1-100, scaling 128-1024 (1024 = 100 %), frameMs 20-5000. */
    fun setQuality(quality: Int, scaling: Int, frameMs: Int) {
        this.quality = quality.coerceIn(1, 100)
        this.scaling = scaling.coerceIn(128, 1024)
        this.frameMs = frameMs.coerceIn(20, 5000)
        send(qualityCommand(), bytes(0, 6, 0, 4))
    }

    fun close() {
        jobs.forEach { it.cancel() }
        tunnel.close()
        out.close()
    }

    companion object {
        /** Measured on the Windows app (DesktopReopenTests): asking again brought an image that had not come. / Medido en la app de Windows. */
        internal const val NO_IMAGE_WAIT_MS = 3000L
        internal const val NO_IMAGE_RETRIES = 5

        suspend fun open(session: MeshSession, nodeId: String, scope: CoroutineScope): DesktopSession =
            DesktopSession(MeshTunnel.open(session, nodeId, TunnelProtocol.Desktop)).also { it.start(scope) }

        internal fun u16(v: ByteArray, at: Int) = ((v[at].toInt() and 0xff) shl 8) or (v[at + 1].toInt() and 0xff)
        private fun s16(v: ByteArray, at: Int) = u16(v, at).toShort().toInt()

        internal fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

        internal fun keyCommand(virtualKey: Int, down: Boolean, extended: Boolean): ByteArray {
            // agent-desktop: down 0, up 1; extended keys 4 (down) and 3 (up). / extendidas 4 (abajo) y 3 (arriba).
            val action = if (extended) (if (down) 4 else 3) else (if (down) 0 else 1)
            return bytes(0, 1, 0, 6, action, virtualKey)
        }

        internal fun mouseCommand(button: MouseButton, down: Boolean?, x: Int, y: Int): ByteArray {
            // down: the button; up: the button × 2; move: 0. / Bajar: el botón; subir: el botón × 2; mover: 0.
            val state = when (down) { true -> button.code; false -> (button.code * 2) and 0xff; null -> 0 }
            return bytes(0, 2, 0, 10, 0, state, x shr 8, x, y shr 8, y)
        }

        internal fun wheelCommand(x: Int, y: Int, delta: Int): ByteArray {
            val d = delta.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            // The website writes negative deltas as 255 - |d|, i.e. one's complement. / La web escribe los negativos en complemento a uno.
            val (hi, lo) = if (d < 0) { val a = -d; (255 - (a shr 8)) to (255 - (a and 0xff)) } else (d shr 8) to (d and 0xff)
            return bytes(0, 2, 0, 12, 0, 0, x shr 8, x, y shr 8, y, hi, lo)
        }

        /**
         * cmd 82: 10 bytes per display: id, x, y, width, height. Windows puts monitors left of or above the main one at negative
         * coordinates, so x and y are signed and moved so the top-left one starts at 0,0, where it is in the stitched image.
         * orden 82: 10 bytes por pantalla. Windows pone a la izquierda o encima coordenadas negativas: se leen con signo y se
         * desplazan para que la de arriba a la izquierda empiece en 0,0, donde está en la imagen cosida.
         */
        internal fun parseLayout(v: ByteArray): List<DisplayRect>? {
            if (v.size < 14 || (v.size - 4) % 10 != 0) return null
            val raw = (4 until v.size step 10).map { p -> DisplayRect(u16(v, p), s16(v, p + 2), s16(v, p + 4), u16(v, p + 6), u16(v, p + 8)) }
            val minX = raw.minOf { it.x }; val minY = raw.minOf { it.y }
            return raw.filter { it.width > 0 && it.height > 0 }.map { it.copy(x = it.x - minX, y = it.y - minY) }
                .sortedWith(compareBy({ it.y }, { it.x }))
        }
    }
}

/** Waits and asks again a few times; true if it all came to nothing. / Espera y vuelve a pedir unas veces; true si no sirvió de nada. */
internal object NoImageWatch {
    suspend fun gaveUp(settled: () -> Boolean, askAgain: () -> Unit, retries: Int, waitMs: Long): Boolean {
        // One more wait than asks: what the last ask brings also counts. / Una espera más que peticiones.
        var i = 0
        while (true) {
            delay(waitMs)
            if (settled()) return false
            if (i == retries) return true
            askAgain()
            i++
        }
    }
}

/**
 * Cuts the agent's desktop stream into commands: padding up to 8 bytes, the jumbo header (cmd 27) and commands cut
 * across frames, as agent-redir-ws does. Apart so it can be tested byte for byte.
 * Corta el flujo de escritorio del agente en órdenes: relleno hasta 8 bytes, la cabecera jumbo (orden 27) y órdenes
 * partidas entre mensajes, como agent-redir-ws. Aparte para probarlo byte a byte.
 */
internal class DesktopFrameParser(private val onCommand: (Int, ByteArray) -> Unit) {
    private var acc: ByteArray? = null
    private var accCmd = 0
    private var accSize = 0
    private var accLen = 0

    @Synchronized
    fun feed(frame: ByteArray) {
        acc?.let { a ->
            val n = minOf(frame.size, accSize - accLen)
            System.arraycopy(frame, 0, a, accLen, n)
            accLen += n
            if (accLen < accSize) return
            acc = null
            onCommand(accCmd, a)
            return
        }
        if (frame.size < 4) return
        var view = frame
        var c = DesktopSession.u16(view, 0)
        var size = DesktopSession.u16(view, 2)
        if (c == 27 && size == 8 && view.size >= 10) {
            size = ((view[5].toInt() and 0xff) shl 16) or ((view[6].toInt() and 0xff) shl 8) or (view[7].toInt() and 0xff)
            c = DesktopSession.u16(view, 8)
            view = view.copyOfRange(8, view.size)
        }
        if (size < 4) return
        if (size > view.size) {
            acc = ByteArray(size).also { System.arraycopy(view, 0, it, 0, view.size) }
            accCmd = c; accSize = size; accLen = view.size
            return
        }
        onCommand(c, view.copyOfRange(0, size))
    }
}
