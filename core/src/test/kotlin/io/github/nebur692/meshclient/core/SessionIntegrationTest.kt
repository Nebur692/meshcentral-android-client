package io.github.nebur692.meshclient.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real, throwaway MeshCentral (tests/server/run-integration.sh): the same accounts as the Windows app's tests.
 * Without MESHTEST_URL they are skipped. / Contra un MeshCentral de verdad y desechable; sin MESHTEST_URL se saltan.
 */
class SessionIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val otpSecret = System.getenv("MESHTEST_OTP_SECRET")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private fun failureOf(block: suspend () -> Unit): MeshClientException = runBlocking {
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }
    }

    @Test fun `a server with its own certificate is refused first, with its fingerprint`() {
        need()
        val e = failureOf { MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust()) }
        assertEquals(MeshFailure.UntrustedCertificate, e.failure)
        assertEquals(cert!!.uppercase(), e.certificateSha256)
    }

    @Test fun `with its fingerprint accepted, the admin signs in and sees the group and the agent`() = runBlocking {
        need()
        val s = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            assertEquals("admin", s.user.name.lowercase())
            assertTrue(s.user.isFullAdministrator)
            val groups = s.deviceGroups()
            assertTrue(groups.any { it.id == System.getenv("MESHTEST_GROUP_ID") })
            // The Linux agent of the test bench may take a moment to appear. / El agente Linux del banco puede tardar un poco.
            var devices = s.devices()
            repeat(30) { if (devices.none { it.isOnline }) { Thread.sleep(1000); devices = s.devices() } }
            val agent = devices.firstOrNull { it.isOnline }
            assertNotNull("no online device", agent)
            assertTrue(agent!!.canOpenTerminal)
            assertNull(s.closedReason.value)
        } finally {
            s.close()
        }
        assertNull("closing on purpose is not a loss", s.closedReason.value)
    }

    /** Signs in on the website as a browser does and gives its session cookies. / Entra en la web como un navegador y da sus cookies. */
    private fun webSessionCookies(user: String, password: String): Map<String, String> {
        val client = CertificateTrust(cert).applyTo(okhttp3.OkHttpClient.Builder()).followRedirects(false).build()
        val form = okhttp3.FormBody.Builder().add("action", "login").add("username", user).add("password", password).build()
        client.newCall(okhttp3.Request.Builder().url(server.resolve("login")).post(form).build()).execute().use { r ->
            return r.headers("Set-Cookie").map { it.substringBefore(";") }.associate { it.substringBefore("=") to it.substringAfter("=") }
        }
    }

    @Test fun `the website session cookie opens the control channel, and a made-up one does not`() = runBlocking {
        need()
        val cookies = webSessionCookies("admin", "Admin-Pass-1234")
        assertTrue("no xid cookie: $cookies", "xid" in cookies)
        val s = MeshSession.connectWithCookies(server, cookies, CertificateTrust(cert))
        try { assertEquals("admin", s.user.name.lowercase()) } finally { s.close() }
        val e = failureOf { MeshSession.connectWithCookies(server, mapOf("xid" to "made-up", "xid.sig" to "made-up"), CertificateTrust(cert)) }
        assertEquals(MeshFailure.SessionExpired, e.failure)
        val none = failureOf { MeshSession.connectWithCookies(server, emptyMap(), CertificateTrust(cert)) }
        assertEquals(MeshFailure.SessionExpired, none.failure)
    }

    @Test fun `a login token signs in without the password, until it is removed`() = runBlocking {
        need()
        val admin = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            val token = admin.createLoginToken("Android test", 60)
            assertTrue(token.tokenUser.startsWith("~t:"))
            val byToken = MeshSession.connect(server, PasswordCredentials(token.tokenUser, token.tokenPass!!), CertificateTrust(cert))
            try { assertEquals("admin", byToken.user.name.lowercase()) } finally { byToken.close() }
            assertTrue(admin.loginTokens().any { it.tokenUser == token.tokenUser && it.tokenPass == null })
            assertTrue(admin.removeLoginTokens(listOf(token.tokenUser)).none { it.tokenUser == token.tokenUser })
            val e = failureOf { MeshSession.connect(server, PasswordCredentials(token.tokenUser, token.tokenPass!!), CertificateTrust(cert)) }
            assertEquals(MeshFailure.InvalidCredentials, e.failure)
        } finally {
            admin.close()
        }
    }

    @Test fun `a wrong password is said so`() {
        need()
        val e = failureOf { MeshSession.connect(server, PasswordCredentials("admin", "wrong-password"), CertificateTrust(cert)) }
        assertEquals(MeshFailure.InvalidCredentials, e.failure)
    }

    @Test fun `two factor - asked for, wrong, then right`() {
        need()
        assumeTrue("no otp secret", !otpSecret.isNullOrEmpty())
        val creds = PasswordCredentials("otp", "Otp-Pass-1234")
        assertEquals(MeshFailure.TwoFactorRequired, failureOf { MeshSession.connect(server, creds, CertificateTrust(cert)) }.failure)
        assertEquals(MeshFailure.InvalidTwoFactorCode, failureOf { MeshSession.connect(server, creds.withCode("000000"), CertificateTrust(cert)) }.failure)
        runBlocking {
            val s = MeshSession.connect(server, creds.withCode(totp(otpSecret!!)), CertificateTrust(cert))
            assertEquals("otp", s.user.name.lowercase())
            s.close()
        }
    }

    @Test fun `a name that does not exist is said so`() {
        val e = failureOf { MeshSession.connect(ServerAddress.parse("no-such-host.invalid")!!, PasswordCredentials("a", "b"), CertificateTrust()) }
        assertEquals(MeshFailure.NameNotResolved, e.failure)
    }

    /** RFC 6238, 30 s, 6 digits, SHA-1, as authenticator apps. / Como las apps de autenticación. */
    private fun totp(base32: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0; var value = 0
        val out = mutableListOf<Byte>()
        for (c in base32.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            value = (value shl 5) or alphabet.indexOf(c); bits += 5
            if (bits >= 8) { out += (value shr (bits - 8) and 0xFF).toByte(); bits -= 8 }
        }
        val counter = System.currentTimeMillis() / 1000 / 30
        val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i) and 0xFF).toByte() }
        val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(out.toByteArray(), "HmacSHA1")) }.doFinal(msg)
        val o = h.last().toInt() and 0x0F
        val n = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        return "%06d".format(n % 1_000_000)
    }

    @Test fun `a notification another session broadcasts arrives as text`() = runBlocking {
        need()
        val me = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        val other = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            val got = kotlinx.coroutines.CompletableDeferred<ServerNotification>()
            val watch = launch(kotlinx.coroutines.Dispatchers.IO) { me.notifications.collect { got.complete(it) } }
            delay(300)
            // What the website's "Notify" and the Windows app send. / Lo que mandan el «Notificar» de la web y la app de Windows.
            val id = MeshSession.newId()
            val r = other.request(kotlinx.serialization.json.buildJsonObject {
                put("action", "userbroadcast"); put("msg", "Hola desde la otra sesión"); put("responseid", id)
            }, { it.str("responseid") == id }, "t", "l")
            assertEquals("ok", r.str("result"))
            val n = withTimeout(15_000) { got.await() }
            assertEquals("Hola desde la otra sesión", n.text)
            watch.cancel()
        } finally {
            me.close(); other.close()
        }
    }
}
