package io.github.nebur692.meshclient.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The chat against the bench (tests/server/run-integration.sh). The bench's agent has no browser, so the person at the
 * device is played here by what its chat page does: it connects to the relay at the same id, with no sign-in.
 * El chat contra el banco. El agente del banco no tiene navegador, así que la persona del equipo la hace aquí lo que
 * hace su página de chat: se conecta al relé con el mismo id, sin iniciar sesión.
 */
class ChatIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun admin() = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) {
            devices().firstOrNull { it.hasAgent }?.let { return it }
            delay(1000)
        }
        fail("no online agent"); error("")
    }

    /** What the device's chat page does. / Lo que hace la página de chat del equipo. */
    private inner class Person(nodeId: String, userId: String) {
        val heard = LinkedBlockingQueue<String>()
        private val client = CertificateTrust(cert).applyTo(OkHttpClient.Builder()).build()
        val socket: WebSocket = client.newWebSocket(Request.Builder().url(server.resolve("meshrelay.ashx").newBuilder()
            .addQueryParameter("id", "meshmessenger/$nodeId/$userId").build()).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { heard.add(text) }
        })
        fun next(): String? = heard.poll(10, TimeUnit.SECONDS)
    }

    @Test fun `the person who opens the page late still finds the technician, and both hear each other`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            val chat = MeshChat.open(s, d)
            // Longer than the 15 s the relay keeps a lonely side: the technician has to have called again.
            // Más que los 15 s que el relé guarda a un lado solo: el técnico ha tenido que volver a llamar.
            delay(20_000)
            assertTrue(chat.isOpen)
            assertFalse(chat.joined)
            assertFalse("nothing can be sent before the person is in", chat.say("antes"))
            val person = Person(d.id, s.user.id)
            val joined = withTimeout(15_000) { chat.events.receive() }
            assertTrue(joined.toString(), joined is ChatEvent.Joined)
            assertTrue(person.next().let { it == "c" || it == "cr" })
            assertTrue(chat.say("Hola, soy el técnico: ñ, á, €"))
            chat.writing(true)
            assertEquals("""{"action":"chat","msg":"Hola, soy el técnico: ñ, á, €"}""", person.next())
            assertEquals("""{"action":"outtext","value":true}""", person.next())
            // What the page also sends and this side does not use (WebRTC) is ignored. / Lo que no usa (WebRTC) se ignora.
            person.socket.send("""{"action":"random","random":0.99}""")
            person.socket.send("""{"action":"outtext","value":true}""")
            person.socket.send("""{"action":"chat","msg":"Hola, aquí la persona"}""")
            assertEquals(ChatEvent.Typing(true), withTimeout(10_000) { chat.events.receive() })
            assertEquals(ChatEvent.Message("Hola, aquí la persona"), withTimeout(10_000) { chat.events.receive() })
            // The person closes the page: the chat ends with its reason. / La persona cierra: el chat acaba con su motivo.
            person.socket.close(1000, null)
            val end = runCatching { withTimeout(15_000) { chat.events.receive() } }.exceptionOrNull()
            assertEquals("chat/ver-chat/conexion-cortada", (end as? MeshClientException)?.key)
            assertFalse(chat.isOpen)
        } finally { s.close() }
    }

    @Test fun `nobody opens the page and the chat says so when the wait is over`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            val t0 = System.currentTimeMillis()
            val chat = MeshChat.open(s, d, waitMs = 3_000)
            val end = runCatching { withTimeout(30_000) { chat.events.receive() } }.exceptionOrNull()
            val took = System.currentTimeMillis() - t0
            assertEquals("chat/abrir-chat/dispositivo-mudo", (end as? MeshClientException)?.key)
            // On time, not when the relay drops the call after its 15 s. / A su hora, no a los 15 s del relé.
            assertTrue("took $took ms", took < 8_000)
            assertFalse(chat.isOpen)
        } finally { s.close() }
    }

    @Test fun `closing while waiting ends quietly`() = runBlocking {
        need()
        val s = admin()
        try {
            val chat = MeshChat.open(s, s.agent())
            chat.close()
            assertNull(runCatching { chat.events.receiveCatching().exceptionOrNull() }.getOrNull())
            assertFalse(chat.isOpen)
        } finally { s.close() }
    }
}
