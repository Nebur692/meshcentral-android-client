package io.github.nebur692.meshclient.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** What a relay tunnel carries (MeshCentral "protocol"). / Lo que lleva un túnel de relé («protocol» de MeshCentral). */
enum class TunnelProtocol(val code: Int) {
    /** cmd on Windows, the root shell elsewhere. / cmd en Windows, la shell de root en el resto. */
    Terminal(1),
    Desktop(2),
    Files(5),
    PowerShell(6),
    /** Shell of the signed-in user (not Windows). / Shell del usuario con sesión (no Windows). */
    UserShell(8),
    UserPowerShell(9),
}

/** One message from the agent. / Un mensaje del agente. */
sealed interface TunnelFrame {
    class Binary(val bytes: ByteArray) : TunnelFrame
    class Text(val text: String) : TunnelFrame
    /** Control messages other than ping, pong, rtt and close (e.g. "console"). / De control salvo ping, pong, rtt y close. */
    class Control(val message: JsonObject) : TunnelFrame
}

/**
 * A relay tunnel to a device's agent through meshrelay.ashx, opened as the website and the Windows app open them:
 * connect the browser side, ask the agent over the control channel to connect the other side to the same id, wait for
 * "c", then send the options and the protocol. Frames arrive in [frames]; it closes with the reason as its cause.
 * Un túnel de relé al agente de un equipo por meshrelay.ashx, abierto como lo abren la web y la app de Windows: se
 * conecta el lado del navegador, se pide al agente por el canal de control que conecte el otro lado al mismo id, se
 * espera «c» y se mandan las opciones y el protocolo. Los mensajes llegan por [frames], que se cierra con el motivo.
 */
class MeshTunnel private constructor(val nodeId: String, val protocol: TunnelProtocol) {
    private lateinit var socket: WebSocket
    private val ended = AtomicBoolean(false)
    private val inbox = Channel<TunnelFrame>(Channel.UNLIMITED)
    private val hello = CompletableDeferred<String?>()

    /** Everything after "c", in order. / Todo lo que llega tras «c», en orden. */
    val frames: ReceiveChannel<TunnelFrame> get() = inbox

    /** The server records this session (it answered "cr"). / El servidor graba esta sesión (contestó «cr»). */
    var isRecorded: Boolean = false
        private set

    val isOpen: Boolean get() = !ended.get()

    fun send(bytes: ByteArray, lostKey: String = LOST) {
        if (ended.get() || !socket.send(bytes.toByteString())) throw MeshSession.lost(lostKey, null)
    }

    fun sendText(text: String, lostKey: String = LOST) {
        if (ended.get() || !socket.send(text)) throw MeshSession.lost(lostKey, null)
    }

    fun sendControl(message: JsonObject, lostKey: String = LOST) =
        sendText(JsonObject(message + ("ctrlChannel" to JsonPrimitive(CONTROL_CHANNEL))).toString(), lostKey)

    /** Says goodbye and closes; [frames] ends without a cause. / Se despide y cierra; [frames] acaba sin causa. */
    fun close() {
        if (!ended.compareAndSet(false, true)) return
        runCatching { socket.send("""{"ctrlChannel":"$CONTROL_CHANNEL","type":"close"}""") }
        runCatching { socket.close(1000, null) }
        inbox.close()
        hello.complete(null)
    }

    private fun end(reason: MeshClientException) {
        if (!ended.compareAndSet(false, true)) return
        runCatching { socket.cancel() }
        inbox.close(reason)
        hello.complete(null)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val control = control(text)
            if (control != null) {
                when (control.str("type")) {
                    "ping" -> runCatching { sendControl(buildJsonObject { put("type", "pong") }) }
                    "close" -> end(MeshSession.lost(CLOSED, null))
                    "pong", "rtt" -> Unit
                    else -> if (hello.isCompleted) inbox.trySend(TunnelFrame.Control(control))
                }
                return
            }
            if (!hello.isCompleted) { hello.complete(text); return }
            inbox.trySend(TunnelFrame.Text(text))
        }

        // Binary frames before "c" are not this session's. / Los binarios antes de «c» no son de esta sesión.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (hello.isCompleted) inbox.trySend(TunnelFrame.Binary(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            end(MeshSession.lost(CLOSED, null))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(MeshSession.lost(CLOSED, null))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(MeshSession.lost(CLOSED, t))
    }

    companion object {
        internal const val CONTROL_CHANNEL = "102938"
        private const val LOST = "tunel/ver-pantalla/conexion-cortada"
        private const val CLOSED = "tunel/ver-pantalla/conexion-cortada"

        /** A control message has the control channel's number; anything else is the session's. / Uno de control lleva su número. */
        internal fun control(text: String): JsonObject? {
            if (!text.startsWith('{') || !text.contains(CONTROL_CHANNEL)) return null
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            return o.takeIf { it.str("ctrlChannel") == CONTROL_CHANNEL || it["ctrlChannel"]?.toString() == CONTROL_CHANNEL }
        }

        suspend fun open(session: MeshSession, nodeId: String, protocol: TunnelProtocol, options: JsonObject? = null): MeshTunnel {
            val cookies = session.relayCookies()
            val id = MeshSession.newId()
            val p = protocol.code
            val browserSide = session.server.resolve("meshrelay.ashx").newBuilder()
                .addQueryParameter("browser", "1").addQueryParameter("p", p.toString())
                .addQueryParameter("nodeid", nodeId).addQueryParameter("id", id)
                .addQueryParameter("auth", cookies.cookie).build()
            val tunnel = MeshTunnel(nodeId, protocol)
            tunnel.socket = session.http.newWebSocket(Request.Builder().url(browserSide).build(), tunnel.Listener())
            try {
                // The agent connects its side to the same id. / El agente conecta su lado al mismo id.
                session.send(buildJsonObject {
                    put("action", "msg"); put("type", "tunnel"); put("nodeid", nodeId); put("usage", p)
                    put("value", "*${session.server.baseUrl.encodedPath}meshrelay.ashx?p=$p&nodeid=$nodeId&id=$id&rauth=${cookies.relayCookie}")
                }, "tunel/abrir-detalle/conexion-cortada")
                val first = withTimeout(session.timeoutMs) { tunnel.hello.await() }
                if (first == null) {
                    val cause = runCatching { tunnel.inbox.receive() }.exceptionOrNull()
                    throw MeshClientException(MeshFailure.ConnectionLost, "tunel/abrir-detalle/conexion-cortada",
                        "The server closed the tunnel before it opened (no rights on this device, or the device refused).", cause)
                }
                if (first != "c" && first != "cr")
                    throw MeshClientException(MeshFailure.UnexpectedResponse, "tunel/abrir-detalle/respuesta-ilegible",
                        "The relay answered \"${first.take(60)}\" instead of connecting.")
                tunnel.isRecorded = first == "cr"
                if (options != null) tunnel.sendText(JsonObject(options + ("type" to JsonPrimitive("options"))).toString())
                tunnel.sendText(p.toString())
                return tunnel
            } catch (e: TimeoutCancellationException) {
                tunnel.close()
                throw MeshClientException(MeshFailure.Timeout, "tunel/abrir-detalle/dispositivo-mudo",
                    "The device did not answer: its agent may be offline, busy or not allowed to open this kind of session.")
            } catch (e: Throwable) {
                tunnel.close()
                throw e
            }
        }
    }
}
