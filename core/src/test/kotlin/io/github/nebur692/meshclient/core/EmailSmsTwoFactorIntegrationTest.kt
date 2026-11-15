package io.github.nebur692.meshclient.core

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two-factor by email and by SMS against the bench, whose server sends both to the bench's mailbox (tests/server/sink.mjs):
 * the account turns each on, signs in asking for the code, reads it from the mailbox as the person would from their email
 * or phone, signs in with it, and turns it off. Uses the "user" account, which no other test signs in with a code.
 * Doble factor por correo y por SMS contra el banco, cuyo servidor manda los dos al buzón del banco: la cuenta activa cada
 * uno, entra pidiendo el código, lo lee del buzón como la persona de su correo o su móvil, entra con él y lo quita.
 */
class EmailSmsTwoFactorIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val sink = System.getenv("MESHTEST_SINK")
    private val server get() = ServerAddress.parse(url)!!
    private fun need() = assumeTrue("no test server with its mailbox", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && !sink.isNullOrEmpty())

    private suspend fun connect(code: String? = null) = MeshSession.connect(server, PasswordCredentials("user", "User-Pass-1234", code), CertificateTrust(cert))

    private fun mailbox(): JsonObject = Json.parseToJsonElement(URI("$sink/read").toURL().readText()) as JsonObject

    /** The code of the newest message after [since] messages: 8 digits by email, 6 by SMS (webserver.js). / El código del último. */
    private suspend fun newestCode(kind: String, since: Int): String {
        val digits = if (kind == "sms") 6 else 8
        repeat(40) {
            val list = mailbox()[kind] as JsonArray
            if (list.size > since) {
                val m = list.last() as JsonObject
                val text = if (kind == "sms") m["msg"]!!.jsonPrimitive.content else readable(m["data"]!!.jsonPrimitive.content)
                Regex("""(?<!\d)(\d{""" + digits + """})(?!\d)""").find(text)?.let { return it.groupValues[1] }
                error("no code in: ${text.take(400)}")
            }
            delay(250)
        }
        error("nothing arrived by $kind")
    }

    /** An email's body as text: quoted-printable joined, base64 parts decoded, headers out. / El cuerpo de un correo como texto. */
    private fun readable(raw: String): String {
        val body = raw.substringAfter("\n\n")
        val decoded = Regex("""(?m)^([A-Za-z0-9+/=]{20,})$""").findAll(body).joinToString("\n") { runCatching { String(Base64.getMimeDecoder().decode(it.value)) }.getOrDefault("") }
        return body.replace("=\n", "").replace(Regex("=([0-9A-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() } + "\n" + decoded
    }

    private fun count(kind: String) = (mailbox()[kind] as JsonArray).size

    @Test fun `codes by email and by SMS are turned on, used to sign in, and turned off`() = runBlocking<Unit> {
        need()
        var s = connect()
        try {
            assertTrue("the bench's server offers email codes", s.serverInfo.emailTwoFactorAllowed)
            assertTrue("and SMS codes", s.serverInfo.smsTwoFactorAllowed)

            // Email: on, then a sign-in asks for a code, the email brings it, it lets in. / Correo: se activa y deja entrar con su código.
            s.setEmailTwoFactor(true)
            assertTrue(s.user.hasEmailTwoFactor)
            s.close()
            val asked = try { connect(); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.TwoFactorRequired, asked?.failure)
            assertTrue("the server offers the email code", asked!!.emailCodeAvailable)
            val mails = count("mails")
            val sent = try { connect(PasswordCredentials.EMAIL_CODE); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.TwoFactorRequired, sent?.failure)
            val code = newestCode("mails", mails)
            assertEquals(8, code.length)
            val wrong = try { connect(((code.toLong() + 1) % 100_000_000).toString().padStart(8, '0')); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.InvalidTwoFactorCode, wrong?.failure)
            s = connect(code)
            assertTrue(s.user.hasEmailTwoFactor)

            // SMS: the phone is verified with the code it gets, a wrong code is refused. / SMS: el teléfono se verifica con su código.
            val texts = count("sms")
            val check = s.startPhone("+34 600 000 001")
            val smsCode = newestCode("sms", texts)
            val bad = try { s.confirmPhone(check, ((smsCode.toInt() + 1) % 1_000_000).toString().padStart(6, '0')); null } catch (e: MeshClientException) { e }
            assertEquals("usuarios/cambiar-ajustes/credencial-invalida", bad?.key)
            assertNull(s.user.phone)
            val again = s.startPhone("+34 600 000 001")
            s.confirmPhone(again, newestCode("sms", texts + 1))
            assertEquals("+34 600 000 001", s.user.phone)

            // Signing in, the SMS code is offered too and lets in. / Al entrar se ofrece también el SMS y deja entrar.
            s.close()
            val both = try { connect(); null } catch (e: MeshClientException) { e }
            assertTrue("the server offers the SMS code", both!!.smsCodeAvailable)
            val before = count("sms")
            try { connect(PasswordCredentials.SMS_CODE) } catch (_: MeshClientException) { }
            s = connect(newestCode("sms", before))

            // Off again: the phone and the email codes go, and the account signs in with its password alone.
            // Se quitan: el teléfono y los códigos por correo, y la cuenta vuelve a entrar solo con su contraseña.
            s.removePhone(); s.setEmailTwoFactor(false)
            assertNull(s.user.phone); assertFalse(s.user.hasEmailTwoFactor)
            s.close()
            s = connect()
            assertFalse(s.user.hasEmailTwoFactor)

            // Refused before sending: not a phone. / Rechazado antes de mandar: no es un teléfono.
            val notPhone = try { s.startPhone("123"); null } catch (e: MeshClientException) { e }
            assertEquals("usuarios/cambiar-ajustes/dato-invalido", notPhone?.key)
        } finally {
            // Whatever happened, the account is left without second factors for the other tests. / Se deja sin doble factor.
            runCatching { s.removePhone(); s.setEmailTwoFactor(false) }
            s.close()
        }
    }
}
