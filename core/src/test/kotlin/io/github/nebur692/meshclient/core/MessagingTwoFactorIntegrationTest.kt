package io.github.nebur692.meshclient.core

import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two-factor by messaging through ntfy, against the bench: the bench's mailbox is also an ntfy server (HTTPS, its
 * certificate trusted only by the test server), where MeshCentral POSTs the codes. What the app does with its UnifiedPush
 * endpoint (an ntfy topic): set it as the account's messaging handle with the check code that arrives there, then sign in
 * with the code that arrives there. Uses the "mfa" account.
 * Doble factor por mensajería con ntfy, contra el banco: el buzón es también un servidor ntfy, donde MeshCentral manda los
 * códigos. Lo que hace la app con su dirección de UnifiedPush (un tema de ntfy).
 */
class MessagingTwoFactorIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val sink = System.getenv("MESHTEST_SINK")
    private val server get() = ServerAddress.parse(url)!!
    private fun need() = assumeTrue("no test server with its ntfy mailbox", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && !sink.isNullOrEmpty())

    private suspend fun connect(code: String? = null) = MeshSession.connect(server, PasswordCredentials("mfa", "Mfa-Pass-1234", code), CertificateTrust(cert))

    private fun pushes(topic: String): List<String> = ((Json.parseToJsonElement(URI("$sink/read").toURL().readText()) as JsonObject)["ntfy"] as JsonArray)
        .map { it as JsonObject }.filter { it["topic"]!!.jsonPrimitive.content == topic }.map { it["msg"]!!.jsonPrimitive.content }

    /** The code of the newest push to [topic] after [since] of them, as the app reads it. / El código del último aviso, como lo lee la app. */
    private suspend fun code(topic: String, since: Int): String {
        repeat(40) {
            val list = pushes(topic)
            if (list.size > since) return codeIn(list.last()) ?: error("no code in: ${list.last()}")
            delay(250)
        }
        error("nothing arrived at $topic")
    }

    @Test fun `an ntfy topic is checked as the account's messaging handle and its codes sign in`() = runBlocking<Unit> {
        need()
        val topic = "up" + System.nanoTime().toString(36)
        var s = connect()
        try {
            assertTrue("the bench's server offers messaging codes", s.serverInfo.messagingTwoFactorAllowed)

            // The handle: a wrong code is refused, the one pushed to the topic is taken. / El canal: el código malo no, el del aviso sí.
            val check = s.startNtfy(topic)
            val good = code(topic, 0)
            val bad = try { s.confirmMessaging(check, ((good.toInt() + 1) % 1_000_000).toString().padStart(6, '0')); null } catch (e: MeshClientException) { e }
            assertEquals("usuarios/cambiar-ajustes/credencial-invalida", bad?.key)
            assertNull(s.user.messagingHandle)
            val again = s.startNtfy(topic)
            s.confirmMessaging(again, code(topic, 1))
            assertEquals("ntfy:$topic", s.user.messagingHandle)

            // Signing in: the server offers the messaging code, pushes it to the topic, and it lets in. / Al entrar: llega al tema y deja entrar.
            s.close()
            val asked = try { connect(); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.TwoFactorRequired, asked?.failure)
            assertTrue("the server offers the messaging code", asked!!.messagingCodeAvailable)
            val before = pushes(topic).size
            val sent = try { connect(PasswordCredentials.MESSAGING_CODE); null } catch (e: MeshClientException) { e }
            assertTrue("the server says it sent it", sent!!.messagingCodeSent)
            // Asking is not a wrong code: the app keeps asking for the code, it does not say it is not valid. / Pedirlo no es un código malo.
            assertEquals(MeshFailure.TwoFactorRequired, sent.failure)
            s = connect(code(topic, before))

            // Off again: the account signs in with its password alone. / Se quita: vuelve a entrar solo con su contraseña.
            s.removeMessaging()
            assertNull(s.user.messagingHandle)
            s.close()
            s = connect()

            val notTopic = try { s.startNtfy("with spaces"); null } catch (e: MeshClientException) { e }
            assertEquals("usuarios/cambiar-ajustes/dato-invalido", notTopic?.key)
        } finally {
            if (runCatching { s.removeMessaging() }.isFailure) cleanUp(topic)
            s.close()
        }
    }

    /**
     * A test that failed halfway leaves the account with the handle and its session closed: sign in again with the topic's
     * code and remove it, so the other tests of the "mfa" account do not fail because of this one.
     * Una prueba que falla a medias deja el canal puesto: se vuelve a entrar con el código del tema y se quita.
     */
    private suspend fun cleanUp(topic: String) {
        val asked = try { connect().close(); return } catch (e: MeshClientException) { e }
        if (asked.failure != MeshFailure.TwoFactorRequired || !asked.messagingCodeAvailable) return
        val before = pushes(topic).size
        runCatching { connect(PasswordCredentials.MESSAGING_CODE).close() }
        val s = connect(code(topic, before))
        s.removeMessaging()
        s.close()
    }
}
