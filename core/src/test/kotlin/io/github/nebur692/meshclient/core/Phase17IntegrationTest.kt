package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 17 against the bench, as the Windows app's tests: a guest link made, listed and ended; the device's installed
 * programs; group and device notices kept by the server; previous web sign-ins; and the server's own files.
 * La fase 17 contra el banco, como las pruebas de la app de Windows.
 */
class Phase17IntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val groupId = System.getenv("MESHTEST_GROUP_ID")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun connect(user: String, pass: String) = MeshSession.connect(server, PasswordCredentials(user, pass), CertificateTrust(cert)).also { it.deviceGroups() }

    private suspend fun MeshSession.agent(): Device {
        repeat(60) {
            devices().firstOrNull { it.groupId == groupId && it.hasAgent }?.let { return it }
            delay(500)
        }
        fail("no online agent"); error("")
    }

    private suspend fun <T> MeshSession.use(block: suspend (MeshSession) -> T): T = try { block(this) } finally { close() }

    private inline fun failsWith(key: String, block: () -> Unit) {
        try { block(); fail("no error, expected $key") } catch (e: MeshClientException) { assertEquals(key, e.key) }
    }

    @Test fun `a guest link is made, listed and ended`() = runBlocking {
        need()
        connect("admin", "Admin-Pass-1234").use { s ->
            val d = s.agent()
            assertTrue(s.canShare(d))
            val share = s.shareDevice(d, "Invitado de prueba", ShareKinds.DESKTOP or ShareKinds.TERMINAL, 3_600_000, askConsent = true)
            assertTrue(share.url!!, share.url!!.contains("sharing?c="))
            val mine = s.deviceShares(d).single { it.publicId == share.publicId }
            assertEquals("Invitado de prueba", mine.guestName)
            assertEquals(ShareKinds.DESKTOP or ShareKinds.TERMINAL, mine.kinds)
            assertTrue(mine.asksConsent)
            assertTrue(mine.expiresMs!! > System.currentTimeMillis() + 50 * 60_000)
            s.removeDeviceShare(d, share)
            assertTrue(s.deviceShares(d).none { it.publicId == share.publicId })
            failsWith("ficha/compartir/dato-invalido") { kotlinx.coroutines.runBlocking { s.shareDevice(d, " ", ShareKinds.DESKTOP, 3_600_000, false) } }
        }
    }

    @Test fun `the installed programs of the device are listed`() = runBlocking {
        need()
        connect("admin", "Admin-Pass-1234").use { s ->
            val d = s.agent()
            assertTrue(s.canSeeSoftware(d))
            val programs = s.installedPrograms(d)
            assertTrue("only ${programs.size} programs", programs.size > 20)
            val bash = programs.single { it.name == "bash" } // Debian always has it / Debian siempre lo tiene
            assertFalse(bash.version.isNullOrEmpty())
        }
    }

    @Test fun `group and device notices are kept by the server`() = runBlocking {
        need()
        connect("admin", "Admin-Pass-1234").use { s ->
            val group = s.deviceGroups().first { it.id == groupId }
            val device = s.devices().first { it.groupId == group.id }
            try {
                s.setGroupNotify(group, DeviceNotify.EMAIL_DISCONNECT or DeviceNotify.MESSAGE_CONNECT)
                s.setDeviceNotify(device, DeviceNotify.MESSAGE_DISCONNECT)
                assertEquals(DeviceNotify.EMAIL_DISCONNECT or DeviceNotify.MESSAGE_CONNECT, s.notifyOn(group.id))
                // A new session reads them from the server. / Una sesión nueva los lee del servidor.
                connect("admin", "Admin-Pass-1234").use { again ->
                    assertEquals(DeviceNotify.EMAIL_DISCONNECT or DeviceNotify.MESSAGE_CONNECT, again.notifyOn(group.id))
                    assertEquals(DeviceNotify.MESSAGE_DISCONNECT, again.notifyOn(device.id))
                }
                s.setDeviceNotify(device, DeviceNotify.MESSAGE_DISCONNECT) // the same again: nothing to do / lo mismo: nada que hacer
            } finally {
                s.setGroupNotify(group, 0)
                s.setDeviceNotify(device, 0)
            }
            connect("admin", "Admin-Pass-1234").use { after ->
                assertEquals(0, after.notifyOn(group.id))
                assertEquals(0, after.notifyOn(device.id))
            }
        }
    }

    @Test fun `failed and good web sign-ins show in the history`() = runBlocking {
        need()
        val started = System.currentTimeMillis() - 5_000
        val http = CertificateTrust(cert).applyTo(OkHttpClient.Builder()).build()
        for (pass in listOf("Not-The-Pass-1", "User-Pass-1234"))
            http.newCall(Request.Builder().url(server.resolve("")).post(FormBody.Builder().add("action", "login").add("username", "user").add("password", pass).build()).build()).execute().close()
        connect("user", "User-Pass-1234").use { s ->
            val logins = s.previousLogins()
            assertTrue(logins.any { it.kind == LoginKind.WrongPassword && it.timeMs >= started })
            // What the server could not tell is left out, never shown as "null". / Lo que no supo se omite, nunca «null».
            assertTrue(logins.flatMap { it.details }.toString(), logins.none { l -> l.details.any { it == "null" } })
            val good = logins.first { it.kind == LoginKind.Valid && it.timeMs >= started }
            assertTrue(good.details.isNotEmpty()) // its address and browser / su dirección y navegador
            assertEquals(logins.sortedByDescending { it.timeMs }, logins)
        }
    }

    @Test fun `a file goes up, comes back, is renamed and deleted on the server`() = runBlocking {
        need()
        connect("admin", "Admin-Pass-1234").use { s ->
            assertTrue(s.canUseServerFiles)
            var mine = s.serverFiles().first { !it.isGroup }
            assertEquals(s.user.id, mine.id)
            val folder = "prueba-" + MeshSession.newId().take(8)
            fun pick(roots: List<ServerFileRoot>) = roots.first { it.id == mine.id }
            mine = pick(s.createServerFolder(mine, emptyList(), folder))
            assertNotNull(mine.find(listOf(folder)))
            val data = ("¡Hola desde la prueba! " + "x".repeat(5000)).toByteArray()
            mine = pick(s.uploadServerFile(mine, listOf(folder), "nota.txt", data))
            val file = mine.find(listOf(folder))!!.single()
            assertEquals(data.size.toLong(), file.size)
            assertArrayEquals(data, s.downloadServerFile(mine, listOf(folder), file))
            mine = pick(s.renameServerFile(mine, listOf(folder), "nota.txt", "otra.txt"))
            assertEquals("otra.txt", mine.find(listOf(folder))!!.single().name)
            val now = mine
            failsWith("ficheros-servidor/gestionar-ficheros/dato-invalido") { kotlinx.coroutines.runBlocking { s.createServerFolder(now, listOf(folder), "a/b") } }
            failsWith("ficheros-servidor/descargar/no-implementado") { kotlinx.coroutines.runBlocking { s.downloadServerFile(now, listOf(folder), file.copy(size = 300_000)) } }
            mine = pick(s.deleteServerFiles(mine, emptyList(), listOf(folder)))
            assertNull(mine.find(listOf(folder)))
        }
        connect("user", "User-Pass-1234").use { s ->
            assertFalse(s.canUseServerFiles)
            failsWith("ficheros-servidor/ver-pantalla/no-autorizado") { kotlinx.coroutines.runBlocking { s.serverFiles() } }
        }
    }
}
