package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * This app as the account's authenticator, against the real server: the secret the server gives is confirmed with the
 * code the app makes itself (Totp), and that code lets the account in; without it, the server asks for it. What the app
 * does when the person taps "Use this app as the authenticator" and later signs in with the fingerprint.
 * La app como autenticador de la cuenta, contra el servidor de verdad: el secreto que da el servidor se confirma con el
 * código que hace la propia app, y ese código deja entrar; sin él, el servidor lo pide.
 */
class OwnAuthenticatorIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!
    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun connect(code: String? = null) = MeshSession.connect(server, PasswordCredentials("user", "User-Pass-1234", code), CertificateTrust(cert))

    @Test fun `the app's own codes set up the authenticator and sign in with it`() = runBlocking<Unit> {
        need()
        var s = connect()
        try {
            val setup = s.startAuthenticator()
            assertTrue("the server's secret is one the app can use", Totp.validSecret(setup.secret))
            s.confirmAuthenticator(setup, Totp.code(setup.secret))
            repeat(20) { if (s.user.hasAuthenticator) return@repeat; delay(250) }
            assertTrue(s.user.hasAuthenticator)
            s.close()

            val asked = try { connect(); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.TwoFactorRequired, asked?.failure)
            // A code from another moment is refused (the server allows one step either side). / Un código de otro momento se rechaza.
            val old = try { connect(Totp.code(setup.secret, System.currentTimeMillis() - 10 * 60_000)); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.InvalidTwoFactorCode, old?.failure)
            s = connect(Totp.code(setup.secret))
            assertTrue(s.user.hasAuthenticator)

            // The "remember the second factor" cookie reconnects without a new code, as the app does coming back.
            // La cookie de «recordar el doble factor» reconecta sin código nuevo, como hace la app al volver.
            val cookie = s.twoFactorCookie()
            assertTrue("the server gives the cookie", !cookie.isNullOrEmpty())
            connect("cookie=$cookie").close()
            val forged = try { connect("cookie=${cookie!!.reversed()}"); null } catch (e: MeshClientException) { e }
            assertTrue("a forged cookie is not a code", forged?.failure == MeshFailure.InvalidTwoFactorCode || forged?.failure == MeshFailure.TwoFactorRequired)

            s.removeAuthenticator()
            repeat(20) { if (!s.user.hasAuthenticator) return@repeat; delay(250) }
            assertFalse(s.user.hasAuthenticator)
            s.close()
            s = connect()
        } finally {
            runCatching { if (s.user.hasAuthenticator) s.removeAuthenticator() }
            s.close()
        }
    }
}
