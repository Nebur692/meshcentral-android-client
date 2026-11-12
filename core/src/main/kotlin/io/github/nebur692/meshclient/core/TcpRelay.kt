package io.github.nebur692.meshclient.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * One TCP connection to a port of a device (or of another machine the device reaches), carried by its agent: what the
 * website and MeshCentral Router call a relay. Measured on MeshCentral 1.3.1 (meshrelay.js): meshrelay.ashx with nodeid,
 * tcpport and tcpaddr; the server checks remote control or relay rights, asks the agent to connect that port and sends
 * "c" (or "cr", recorded) when the agent joins; from then on the bytes go as binary messages both ways (the agent drops
 * text). If the agent cannot reach the port, it closes. Ported from the Windows app.
 * Una conexión TCP a un puerto de un equipo (o de otra máquina a la que llega el equipo), por su agente: lo que la web y
 * MeshCentral Router llaman relé. Medido en 1.3.1: meshrelay.ashx con nodeid, tcpport y tcpaddr; el servidor comprueba
 * los permisos, pide al agente conectar ese puerto y manda «c» («cr» si se graba); desde ahí los bytes van en mensajes
 * binarios en los dos sentidos. Si el agente no llega al puerto, cierra. Portado de la app de Windows.
 */
class TcpRelay private constructor() {
    private lateinit var socket: WebSocket
    private val ended = AtomicBoolean(false)
    private val hello = CompletableDeferred<String?>()
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val up = AtomicLong()
    private val down = AtomicLong()

    var isRecorded = false
        private set
    val bytesUp: Long get() = up.get()
    val bytesDown: Long get() = down.get()

    fun send(bytes: ByteArray, count: Int = bytes.size) {
        if (ended.get() || !socket.send(bytes.toByteString(0, count))) throw MeshSession.lost(LOST, null)
        up.addAndGet(count.toLong())
    }

    /** The next bytes from the device, or null when it ended. / Los siguientes bytes del equipo, o null si acabó. */
    suspend fun receive(): ByteArray? = inbox.receiveCatching().getOrNull()

    fun close() {
        if (!ended.compareAndSet(false, true)) return
        runCatching { socket.close(1000, null) }
        inbox.close()
        hello.complete(null)
    }

    private fun end() {
        if (!ended.compareAndSet(false, true)) return
        runCatching { socket.cancel() }
        inbox.close()
        hello.complete(null)
    }

    /**
     * Carries bytes between a local stream pair and the device until either side ends; then both are closed.
     * Lleva los bytes entre un par de flujos locales y el equipo hasta que uno acaba; entonces se cierran los dos.
     */
    suspend fun pump(input: InputStream, output: OutputStream, onClose: () -> Unit) = withContext(Dispatchers.IO) {
        // A client that has finished sending (half-close: "echo | nc") still waits for the answer: the device's side stays
        // open until it closes, or [AFTER_LOCAL_END_MS] without data. Closing at once lost the SSH banner (seen with nc).
        // Un cliente que acabó de mandar (medio cierre) sigue esperando la respuesta: el lado del equipo sigue abierto
        // hasta que cierre, o [AFTER_LOCAL_END_MS] sin datos. Cerrar enseguida perdía el saludo SSH (visto con nc).
        val localEnded = AtomicBoolean(false)
        coroutineScope {
            val toDevice = launch {
                val buffer = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) send(buffer, n)
                    }
                    localEnded.set(true)
                    return@launch
                } catch (_: IOException) {
                } catch (_: MeshClientException) {
                }
                close()
            }
            try {
                while (true) {
                    val bytes = (if (localEnded.get()) kotlinx.coroutines.withTimeoutOrNull(AFTER_LOCAL_END_MS) { receive() } else receive()) ?: break
                    output.write(bytes)
                    output.flush()
                }
            } catch (_: IOException) {
            }
            close()
            onClose()
            toDevice.cancel()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!hello.isCompleted) hello.complete(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!hello.isCompleted) return
            down.addAndGet(bytes.size.toLong())
            inbox.trySend(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); end() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end()
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end()
    }

    companion object {
        private const val LOST = "redireccion/llevar-conexion/conexion-cortada"
        private const val AFTER_LOCAL_END_MS = 30_000L

        suspend fun open(session: MeshSession, nodeId: String, port: Int, host: String? = null): TcpRelay {
            if (port !in 1..65535) throw MeshClientException(MeshFailure.Rejected, "redireccion/llevar-conexion/dato-invalido", "$port is not a TCP port (1 to 65535).")
            val cookies = session.relayCookies()
            val url = session.server.resolve("meshrelay.ashx").newBuilder()
                .addQueryParameter("nodeid", nodeId).addQueryParameter("tcpport", port.toString())
                .apply { if (!host.isNullOrBlank()) addQueryParameter("tcpaddr", host.trim()) }
                .addQueryParameter("auth", cookies.cookie).build()
            val relay = TcpRelay()
            relay.socket = session.http.newWebSocket(Request.Builder().url(url).build(), relay.Listener())
            try {
                val first = withTimeout(session.timeoutMs) { relay.hello.await() }
                if (first != "c" && first != "cr") throw MeshClientException(MeshFailure.ConnectionLost, LOST,
                    "The server closed the connection to ${if (host.isNullOrBlank()) "the device" else host}:$port (no remote control or relay rights on this device, or the device refused).")
                relay.isRecorded = first == "cr"
                return relay
            } catch (e: TimeoutCancellationException) {
                relay.close()
                throw MeshClientException(MeshFailure.Timeout, "redireccion/llevar-conexion/dispositivo-mudo", "The device did not answer: its agent may be offline or busy.")
            } catch (e: Throwable) {
                relay.close()
                throw e
            }
        }
    }
}
