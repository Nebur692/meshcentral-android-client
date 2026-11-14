package io.github.nebur692.meshclient.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What happens in a chat. / Lo que pasa en un chat. */
sealed interface ChatEvent {
    /** The person opened the chat page on the device; [recorded]: the server records it. / La persona abrió la página. */
    data class Joined(val recorded: Boolean) : ChatEvent
    /** A message from the person at the device. / Un mensaje de la persona del equipo. */
    data class Message(val text: String) : ChatEvent
    /** The person is writing (or stopped). / La persona está escribiendo (o dejó de hacerlo). */
    data class Typing(val writing: Boolean) : ChatEvent
}

/**
 * A chat with the person at a device, as the website's "Chat" opens it (measured on MeshCentral 1.3.1: meshuser.js
 * "meshmessenger", meshrelay.js and messenger.handlebars). The server asks the agent to open its chat page in the
 * device's browser, and both sides meet in the relay at "meshmessenger/<node>/<user>". The relay keeps a lonely side
 * only 15 s, so while the person has not opened the page this side keeps calling again, for [waitMs] at most. Text
 * goes as {"action":"chat"} and the "writing" sign as {"action":"outtext"}; the page's WebRTC is never started (this
 * side does not answer "random"), so everything stays on the relay and the server can record it.
 * Un chat con la persona del equipo, como lo abre «Chat» en la web (medido en 1.3.1). El servidor pide al agente que abra
 * su página de chat en el navegador del equipo y los dos lados se encuentran en el relé. El relé solo guarda 15 s a un
 * lado solo, así que mientras la persona no abre la página este lado vuelve a llamar, [waitMs] como mucho. El texto va
 * como «chat» y el «escribiendo» como «outtext»; el WebRTC de la página nunca arranca (no se contesta «random»), así que
 * todo va por el relé y el servidor puede grabarlo.
 */
class MeshChat private constructor(
    private val session: MeshSession, val nodeId: String, private val url: okhttp3.HttpUrl, private val waitMs: Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<ChatEvent>(Channel.UNLIMITED)
    private val ended = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var caller: Job? = null

    /** Everything the person does; it ends with the reason as its cause (none after [close]). / Todo lo que hace la persona. */
    val events: ReceiveChannel<ChatEvent> get() = inbox

    /** The person is in the chat. / La persona está en el chat. */
    @Volatile var joined: Boolean = false
        private set

    val isOpen: Boolean get() = !ended.get()

    /** Sends a message; false while the person has not joined. / Manda un mensaje; false si la persona aún no está. */
    fun say(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) throw MeshClientException(MeshFailure.Rejected, "chat/enviar-mensaje/dato-invalido",
            "A message has from 1 to $MAX_TEXT characters.")
        return sendJson(buildJsonObject { put("action", "chat"); put("msg", t) })
    }

    /** Tells the person whether this side is writing. / Dice a la persona si este lado está escribiendo. */
    fun writing(on: Boolean) { sendJson(buildJsonObject { put("action", "outtext"); put("value", on) }) }

    private fun sendJson(o: JsonObject): Boolean {
        val s = socket
        if (ended.get() || !joined || s == null) return false
        if (!s.send(o.toString())) throw MeshSession.lost(LOST, null)
        return true
    }

    fun close() {
        if (!ended.compareAndSet(false, true)) return
        caller?.cancel()
        runCatching { socket?.close(1000, null) }
        inbox.close()
        scope.cancel()
    }

    private fun end(reason: MeshClientException) {
        if (!ended.compareAndSet(false, true)) return
        caller?.cancel()
        runCatching { socket?.cancel() }
        inbox.close(reason)
        scope.cancel()
    }

    /** Calls until the person joins or [waitMs] passes. / Llama hasta que entra la persona o pasa [waitMs]. */
    private fun call() {
        caller = scope.launch {
            val deadline = System.currentTimeMillis() + waitMs
            while (!ended.get() && !joined) {
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val s = session.http.newWebSocket(Request.Builder().url(url).build(), Listener(closed))
                socket = s
                // The wait ends on time, not when the relay drops this call. / La espera acaba a su hora, no cuando el relé corta.
                val left = deadline - System.currentTimeMillis()
                if (left > 0) withTimeoutOrNull(left) { closed.await() }
                if (joined || ended.get()) return@launch
                if (System.currentTimeMillis() >= deadline) {
                    runCatching { s.cancel() }
                    end(MeshClientException(MeshFailure.Timeout, "chat/abrir-chat/dispositivo-mudo",
                        "Nobody opened the chat on the device: there may be nobody signed in, or its browser did not open."))
                    return@launch
                }
                delay(RECALL_MS)
            }
        }
    }

    private inner class Listener(private val closed: kotlinx.coroutines.CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!joined) {
                if (text == "c" || text == "cr") { joined = true; inbox.trySend(ChatEvent.Joined(text == "cr")) }
                return
            }
            inbox.trySend(parse(text) ?: return)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); gone() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone()
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!joined && response != null && response.code in 400..499) {
                end(MeshClientException(MeshFailure.NotAllowed, "chat/abrir-chat/no-autorizado", "The server refused the chat (HTTP ${response.code}).", t))
            }
            gone(t)
        }

        private fun gone(t: Throwable? = null) {
            if (joined) end(MeshClientException(MeshFailure.ConnectionLost, "chat/ver-chat/conexion-cortada", "The chat ended: the person closed it or the connection was lost.", t))
            closed.complete(Unit)
        }
    }

    companion object {
        const val MAX_TEXT = 4000
        const val DEFAULT_WAIT_MS = 120_000L
        private const val RECALL_MS = 300L
        private const val LOST = "chat/enviar-mensaje/conexion-cortada"

        /** Reads one message of the page; null for what this side does not use. / Lee un mensaje de la página. */
        internal fun parse(text: String): ChatEvent? {
            if (!text.startsWith('{')) return null
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            if (o.str("ctrlChannel") != null) return null
            return when (o.str("action")) {
                "chat" -> o.str("msg")?.let { ChatEvent.Message(it) }
                "outtext" -> ChatEvent.Typing((o["value"] as? JsonPrimitive)?.booleanOrNull ?: false)
                else -> null
            }
        }

        /** Opens the chat page on the device and waits for the person in the relay. / Abre el chat en el equipo y espera. */
        suspend fun open(session: MeshSession, device: Device, waitMs: Long = DEFAULT_WAIT_MS): MeshChat {
            if (!MeshRights.has(session.rightsOn(device), MeshRights.CHAT_NOTIFY))
                throw MeshClientException(MeshFailure.NotAllowed, "chat/abrir-chat/no-autorizado", "This account has no rights to chat with this device.")
            if (!device.hasAgent) throw MeshClientException(MeshFailure.DeviceOffline, "chat/abrir-chat/sin-conexion", "The device is not connected.")
            val cookies = session.relayCookies()
            val url = session.server.resolve("meshrelay.ashx").newBuilder()
                .addQueryParameter("id", "meshmessenger/${device.id}/${session.user.id}")
                .addQueryParameter("auth", cookies.cookie).build()
            val chat = MeshChat(session, device.id, url, waitMs)
            // This side waits first, so the page always finds it. / Este lado espera primero, así la página siempre lo encuentra.
            chat.call()
            try {
                session.send(buildJsonObject { put("action", "meshmessenger"); put("nodeid", device.id) }, "chat/abrir-chat/conexion-cortada")
            } catch (e: Throwable) { chat.close(); throw e }
            return chat
        }
    }
}
