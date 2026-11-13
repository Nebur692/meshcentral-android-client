package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The own account, the server's live state and actions on several devices, against the bench's real MeshCentral: the
 * same steps the Windows app's UsersIntegrationTests and ServerIntegrationTests measured.
 * La cuenta propia, el estado en vivo del servidor y las acciones en varios equipos, contra el MeshCentral de verdad del banco.
 */
class AccountIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())
    private val server get() = ServerAddress.parse(url)!!
    private suspend fun connect(user: String, pass: String, code: String? = null) =
        MeshSession.connect(server, PasswordCredentials(user, pass, code), CertificateTrust(cert))
    private val stamp = System.nanoTime().toString().takeLast(6)

    private suspend fun failure(block: suspend () -> Unit): MeshClientException =
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }

    /** Until the session's own account shows it (the server's live event), 10 s at most. / Hasta que la cuenta lo enseñe. */
    private suspend fun MeshSession.until(what: String, ok: (UserInfo) -> Boolean) {
        repeat(40) { if (ok(user)) return; delay(250) }
        fail("the own account never showed: $what")
    }

    /** A code of the next 30 s window when this one is about to end: the server takes each code once. / Un código de la ventana siguiente si esta acaba. */
    private fun freshCode(secret: String, used: String? = null): String {
        val now = System.currentTimeMillis()
        val c = totp(secret, now)
        return if (c == used || now / 1000 % 30 > 26) totp(secret, now + 30_000).also { Thread.sleep(30_000 - now % 30_000 + 200) } else c
    }

    @Test fun `a person changes the password, adds an authenticator, backup codes and a login token, and removes them`() = runBlocking {
        need()
        val admin = connect("admin", "Admin-Pass-1234")
        val name = "yo$stamp"
        val id = admin.createUser(NewUser(name, "Mine-Pass-1234"))
        try {
            connect(name, "Mine-Pass-1234").let { me ->
                try {
                    assertFalse(me.ownSettingsLocked)
                    assertEquals("usuarios/cambiar-ajustes/credencial-invalida", failure { me.changePassword("not-it-1234", "Mine-New-5678") }.key)
                    me.changePassword("Mine-Pass-1234", "Mine-New-5678")
                } finally { me.close() }
            }
            assertEquals(MeshFailure.InvalidCredentials, failure { connect(name, "Mine-Pass-1234").close() }.failure)
            val s = connect(name, "Mine-New-5678")
            try {
                // Backup codes need a second factor first: the server would not answer. / Los códigos piden antes otro factor.
                assertEquals("usuarios/cambiar-ajustes/conflicto-estado", failure { s.backupCodes(BackupCodeAction.New) }.key)
                assertTrue(s.securityKeys().isEmpty())

                val setup = s.startAuthenticator()
                assertTrue(setup.uri, setup.uri.startsWith("otpauth://totp/"))
                val wrong = if (totp(setup.secret) == "000000") "111111" else "000000"
                assertEquals("usuarios/cambiar-ajustes/credencial-invalida", failure { s.confirmAuthenticator(setup, wrong) }.key)
                val first = freshCode(setup.secret)
                s.confirmAuthenticator(setup, first)
                s.until("the authenticator") { it.hasAuthenticator && it.hasTwoFactor }
                assertTrue(admin.users().first { it.id == id }.hasAuthenticator)
                assertEquals("usuarios/cambiar-ajustes/conflicto-estado", failure { s.startAuthenticator() }.key)

                // Signing in now asks for the code. / Entrar ahora pide el código.
                assertEquals(MeshFailure.TwoFactorRequired, failure { connect(name, "Mine-New-5678").close() }.failure)
                connect(name, "Mine-New-5678", freshCode(setup.secret, used = first)).let { assertEquals(id, it.user.id); it.close() }

                val codes = s.backupCodes(BackupCodeAction.New)
                assertEquals(10, codes.size)
                assertTrue(codes.all { it.code.matches(Regex("[0-9]{8}")) && !it.used })
                s.until("backup codes") { it.hasBackupCodes }
                assertEquals(codes.map { it.code }, s.backupCodes(BackupCodeAction.Show).map { it.code })
                // A backup code signs in instead of the app's code, once. / Un código de respaldo sirve para entrar, una vez.
                connect(name, "Mine-New-5678", codes[0].code).let { assertEquals(id, it.user.id); it.close() }
                assertTrue(s.backupCodes(BackupCodeAction.Show)[0].used)
                assertTrue(s.backupCodes(BackupCodeAction.Clear).isEmpty())
                s.until("the backup codes gone") { !it.hasBackupCodes }

                val token = s.createLoginToken("script $stamp", 60)
                assertNotNull(token.tokenPass)
                assertTrue(token.expire > System.currentTimeMillis())
                assertTrue(s.loginTokens().any { it.tokenUser == token.tokenUser && it.tokenPass == null })
                // A login token signs in without the second factor, as the account. / Un token entra sin el doble factor.
                connect(token.tokenUser, token.tokenPass!!).let { assertEquals(id, it.user.id); it.close() }
                assertTrue(s.removeLoginTokens(listOf(token.tokenUser)).none { it.tokenUser == token.tokenUser })
                assertEquals(MeshFailure.InvalidCredentials, failure { connect(token.tokenUser, token.tokenPass).close() }.failure)

                s.removeAuthenticator()
                s.until("the authenticator gone") { !it.hasAuthenticator }
                assertEquals("usuarios/cambiar-ajustes/conflicto-estado", failure { s.removeAuthenticator() }.key)
                connect(name, "Mine-New-5678").let { assertEquals(id, it.user.id); it.close() }
            } finally { s.close() }
        } finally {
            admin.users().firstOrNull { it.id == id }?.let { admin.deleteUser(it) }
            admin.close()
        }
    }

    @Test fun `locked settings are refused before asking the server`() = runBlocking {
        need()
        val admin = connect("admin", "Admin-Pass-1234")
        val name = "fijo$stamp"
        val id = admin.createUser(NewUser(name, "Fixed-Pass-1234", siteRights = SiteRights.LOCK_SETTINGS))
        try {
            val s = connect(name, "Fixed-Pass-1234")
            try {
                assertTrue(s.ownSettingsLocked)
                assertEquals("usuarios/cambiar-ajustes/sin-permiso", failure { s.changePassword("Fixed-Pass-1234", "Other-Pass-5678") }.key)
                assertEquals("usuarios/cambiar-ajustes/sin-permiso", failure { s.startAuthenticator() }.key)
            } finally { s.close() }
        } finally {
            admin.users().firstOrNull { it.id == id }?.let { admin.deleteUser(it) }
            admin.close()
        }
    }

    @Test fun `the server's live state is for its administrators, and stops when no longer collected`() = runBlocking {
        need()
        val admin = connect("admin", "Admin-Pass-1234")
        try {
            assertTrue(admin.canSeeServer)
            val st = withTimeout(15_000) { admin.serverStats(1_000).first() }
            assertTrue("memory ${st.freeMemory}/${st.totalMemory}", st.totalMemory > st.freeMemory && st.freeMemory > 0)
            assertTrue(st.usedMemory in 1..st.totalMemory)
            assertEquals(3, st.cpuAverage.size)
            assertTrue(st.state.toString(), (st.state["UserAccounts"] ?: 0) >= 3)
            assertTrue(st.state.toString(), (st.state["AgentSessions"] ?: 0) >= 1)
            // Collected again, it starts again: the first stop did not leave the session deaf. / Recogido otra vez, vuelve a empezar.
            assertNotNull(withTimeout(15_000) { admin.serverStats(1_000).first() })
        } finally { admin.close() }

        val user = connect("user", "User-Pass-1234")
        try {
            assertFalse(user.canSeeServer)
            assertEquals("servidor/abrir-detalle/no-autorizado", failure { user.serverStats() }.key)
        } finally { user.close() }
    }

    @Test fun `several devices at once go in one request, and one without rights stops them all`() = runBlocking {
        need()
        val admin = connect("admin", "Admin-Pass-1234")
        try {
            admin.deviceGroups()
            var d: Device? = null
            repeat(60) { if (d == null) { d = admin.devices().firstOrNull { it.hasAgent }; if (d == null) delay(1000) } }
            val agent = d ?: run { fail("no online agent"); error("") }
            admin.notify(listOf(agent), "Prueba", "Aviso a varios")
            val said = admin.wake(listOf(agent))
            assertFalse(said, said.startsWith("Invalid"))
            try { admin.notify(emptyList(), "a", "b"); fail("an empty list was sent") } catch (_: IllegalArgumentException) { }

            // The same device seen by an account without rights: refused before anything is sent. / Sin permisos: rechazado antes de mandar nada.
            val user = connect("user", "User-Pass-1234")
            try {
                assertEquals(MeshFailure.NotAllowed, failure { user.power(listOf(agent), PowerAction.Restart) }.failure)
                assertEquals(MeshFailure.NotAllowed, failure { user.notify(listOf(agent), "a", "b") }.failure)
            } finally { user.close() }
        } finally { admin.close() }
    }
}
