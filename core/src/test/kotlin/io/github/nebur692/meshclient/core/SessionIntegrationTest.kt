package io.github.nebur692.meshclient.core

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
}
