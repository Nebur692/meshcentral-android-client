package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.ChatEvent
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.MeshChat
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One line of the chat: [mine] is what this side wrote. / Una línea del chat: [mine] es lo que escribió este lado. */
data class ChatLine(val text: String, val mine: Boolean, val timeMs: Long = System.currentTimeMillis())

enum class ChatState { Opening, Waiting, Talking, Ended }

/**
 * The chat with the person at a device, while its screen is open: it opens the chat page on the device, waits for the
 * person (the core calls again while waiting), and keeps the conversation. Nothing is sent before the person is in, so
 * nothing is written to nobody. / El chat con la persona del equipo, mientras su pantalla está abierta: abre la página
 * en el equipo, espera a la persona y guarda la conversación. Nada se manda antes de que entre, así nada se escribe a nadie.
 */
class ChatModel(private val session: () -> MeshSession?, val device: Device, private val scope: CoroutineScope) {
    val lines = mutableStateListOf<ChatLine>()
    var state by mutableStateOf(ChatState.Opening)
        private set
    /** The person is writing. / La persona está escribiendo. */
    var personWriting by mutableStateOf(false)
        private set
    /** The server records this chat. / El servidor graba este chat. */
    var recorded by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    /** It ended because the person closed it (or the line to them dropped), not by a failure here. / La persona lo cerró. */
    var personLeft by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")

    private var chat: MeshChat? = null
    private var job: Job? = null
    private var writingSent = false
    private var writingJob: Job? = null

    fun start() {
        if (job?.isActive == true) return
        state = ChatState.Opening
        error = null
        personLeft = false
        personWriting = false
        job = scope.launch {
            try {
                val s = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "chat/abrir-chat/conexion-cortada", "no session")
                val c = MeshChat.open(s, device)
                chat = c
                state = ChatState.Waiting
                for (e in c.events) when (e) {
                    is ChatEvent.Joined -> { recorded = e.recorded; state = ChatState.Talking }
                    is ChatEvent.Message -> { personWriting = false; lines += ChatLine(e.text, mine = false) }
                    is ChatEvent.Typing -> personWriting = e.writing
                }
                state = ChatState.Ended
            } catch (e: MeshClientException) {
                // Once in, the chat ending is the person closing it, not this app failing. / Ya dentro, acabar es que la persona cerró.
                if (e.key == "chat/ver-chat/conexion-cortada") personLeft = true else error = UiError(e.failure, e.key, e.httpStatus)
                state = ChatState.Ended
            } finally {
                personWriting = false
                chat = null
            }
        }
    }

    /** Sends what is written; it stays in the box if it could not go. / Manda lo escrito; si no sale, se queda en la caja. */
    fun send() {
        val text = draft.trim()
        val c = chat ?: return
        if (text.isEmpty() || state != ChatState.Talking) return
        try {
            if (c.say(text)) { lines += ChatLine(text, mine = true); draft = ""; stopWriting() }
        } catch (e: MeshClientException) {
            error = UiError(e.failure, e.key, e.httpStatus)
        }
    }

    /** The box changed: the person sees "writing" while there is text and some typing in the last seconds. / «Escribiendo». */
    fun typed(text: String) {
        draft = text
        val c = chat ?: return
        if (state != ChatState.Talking) return
        if (text.isBlank()) { stopWriting(); return }
        if (!writingSent) { runCatching { c.writing(true) }; writingSent = true }
        writingJob?.cancel()
        writingJob = scope.launch { delay(WRITING_MS); stopWriting() }
    }

    private fun stopWriting() {
        writingJob?.cancel()
        if (writingSent) { runCatching { chat?.writing(false) }; writingSent = false }
    }

    fun close() {
        writingJob?.cancel()
        job?.cancel()
        chat?.close()
        chat = null
    }

    private companion object { const val WRITING_MS = 4000L }
}
