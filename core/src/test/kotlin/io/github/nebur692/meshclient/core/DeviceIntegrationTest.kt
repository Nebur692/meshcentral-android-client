package io.github.nebur692.meshclient.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A device's sheet and actions against the bench's real Linux agent (tests/server/run-integration.sh). Nothing here powers
 * the agent off, restarts it or removes it: other tests need it, so those are tested only by their refusal.
 * La ficha y las acciones contra el agente Linux de verdad del banco. Aquí nada lo apaga, lo reinicia ni lo borra: otras
 * pruebas lo necesitan, así que esas se prueban solo por su rechazo.
 */
class DeviceIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val otpSecret = System.getenv("MESHTEST_OTP_SECRET")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun admin() = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    /** The bench's agent, online. / El agente del banco, en línea. */
    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) {
            devices().firstOrNull { it.hasAgent }?.let { return it }
            delay(1000)
        }
        fail("no online agent"); error("")
    }

    private suspend fun failureOf(block: suspend () -> Unit): MeshClientException =
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }

    @Test fun `the admin may do everything on the agent, and the sheet has its inventory, network and history`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            val can = s.abilitiesOn(d)
            assertTrue(can.power && can.runCommands && can.notify && can.edit && can.notes && can.remove && can.uninstall)
            assertFalse("an online device is not woken", can.wake)
            // The agent sends its inventory a few seconds after connecting. / El agente manda su inventario unos segundos después.
            var details = s.details(d)
            repeat(30) { if (details.hardware == null || details.network.isEmpty()) { delay(1000); details = s.details(d) } }
            assertNotNull("no inventory", details.hardware)
            val hw = details.hardware!!
            assertTrue("no memory or volumes: $hw", hw.totalMemory > 0 || hw.volumes.isNotEmpty())
            assertTrue("no network: ${details.network}", details.network.any { n -> n.addresses.any { it.address.isNotEmpty() } })
            assertNotNull("no last connection", details.lastConnect?.timeMs)
            // A fresh server has no history yet: a change makes one, and it must come back with who did it.
            // Un servidor recién hecho aún no tiene historial: un cambio lo crea, y debe volver con quién lo hizo.
            s.changeDevice(d, description = "Evento de prueba ${System.nanoTime()}")
            var events = s.events(d.id)
            repeat(20) { if (events.none { it.action == "changenode" }) { delay(250); events = s.events(d.id) } }
            val change = events.firstOrNull { it.action == "changenode" }
            assertNotNull("no change event: $events", change)
            assertEquals("admin", change!!.user?.lowercase())
            assertNotNull("event without time", change.timeMs)
            s.changeDevice(d, description = "")
        } finally {
            s.close()
        }
    }

    @Test fun `commands run on the agent and give their output`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            val out = s.runCommands(d, "echo hola-desde-el-agente", shellFor(d, powerShell = false), timeoutMs = 60_000)
            assertTrue(out, out.contains("hola-desde-el-agente"))
        } finally {
            s.close()
        }
    }

    @Test fun `a notification is accepted, and notes and the description are written and read back`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            s.notify(d, "Prueba", "Aviso de la prueba")
            val text = "Nota de prueba ${System.nanoTime()}"
            s.setNotes(d, text)
            var read: String? = null
            repeat(20) { if (read != text) { delay(250); read = s.notes(d.id) } }
            assertEquals(text, read)
            s.setNotes(d, "")
            s.changeDevice(d, description = "Descripción de prueba")
            var changed = s.devices().first { it.id == d.id }
            repeat(20) { if (changed.description != "Descripción de prueba") { delay(250); changed = s.devices().first { it.id == d.id } } }
            assertEquals("Descripción de prueba", changed.description)
            s.changeDevice(d, description = "")
        } finally {
            s.close()
        }
    }

    @Test fun `an account without rights on the group is refused before anything is sent`() = runBlocking {
        need()
        assumeTrue("no otp secret", !otpSecret.isNullOrEmpty())
        val admin = admin()
        val d = try { admin.agent() } finally { admin.close() }
        val s = MeshSession.connect(server, PasswordCredentials("otp", "Otp-Pass-1234").withCode(totp(otpSecret!!)), CertificateTrust(cert))
        try {
            s.deviceGroups()
            assertEquals(0L, s.rightsOn(d))
            assertEquals(DeviceAbilities.of(0, d), s.abilitiesOn(d))
            assertEquals(MeshFailure.NotAllowed, failureOf { s.power(d, PowerAction.Restart) }.failure)
            assertEquals(MeshFailure.NotAllowed, failureOf { s.removeDevice(d) }.failure)
            assertEquals(MeshFailure.NotAllowed, failureOf { s.runCommands(d, "id", CommandShell.Shell) }.failure)
            assertEquals(MeshFailure.NotAllowed, failureOf { s.uninstallAgent(d) }.failure)
        } finally {
            s.close()
        }
    }

    /** RFC 6238, as authenticator apps. / Como las apps de autenticación. */
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
