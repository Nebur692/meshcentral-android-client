package io.github.nebur692.meshclient.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phase 18 against the bench, as the Windows app's tests: the agent's console answers; icon, own ports and place are
 * kept and the usual port takes the own one away; the Windows agent of a group comes as a program and the Linux command
 * carries the group. / La fase 18 contra el banco, como las pruebas de la app de Windows.
 */
class Phase18IntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val groupId = System.getenv("MESHTEST_GROUP_ID")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun admin() = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert)).also { it.deviceGroups() }

    private suspend fun MeshSession.agent(): Device {
        repeat(60) {
            devices().firstOrNull { it.groupId == groupId && it.hasAgent }?.let { return it }
            delay(500)
        }
        fail("no online agent"); error("")
    }

    @Test fun `the agent console answers a command`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            assertTrue(s.canUseAgentConsole(d))
            val said = StringBuilder()
            val heard = async { withTimeout(20_000) { s.consoleOutput.filter { it.deviceId == d.id }.first { said.append(it.text); "info" in said.toString().lowercase() } } }
            delay(200)
            s.sendConsoleCommand(d, "help")
            heard.await()
            try { s.sendConsoleCommand(d, "  "); fail("an empty command") } catch (e: MeshClientException) { assertEquals("ficha/consola/dato-invalido", e.key) }
        } finally { s.close() }
    }

    @Test fun `icon, own ports and place are kept and the usual port removes the own`() = runBlocking {
        need()
        val s = admin()
        try {
            assertTrue("the bench's server has geolocation on", s.serverKeepsLocations)
            val d = s.agent()
            val before = DeviceSettings.of(d)
            suspend fun reread(done: (Device) -> Boolean): Device {
                repeat(40) { s.devices().first { it.id == d.id }.let { if (done(it)) return it }; delay(250) }
                return s.devices().first { it.id == d.id }
            }
            try {
                s.saveDeviceSettings(d, before.copy(icon = DeviceIcon.Server, sshPort = 2222, rdpPort = 3390, location = DeviceLocation(40.4168, -3.7038)))
                val now = reread { it.sshPort == 2222 && it.location != null }
                assertEquals(DeviceIcon.Server.code, now.icon)
                assertEquals(2222, now.sshPort)
                assertEquals(3390, now.rdpPort)
                assertEquals(2222, now.portFor(22))
                assertNull(now.vncPort)
                assertEquals(40.4168, now.location!!.latitude, 0.0001)
                assertEquals(-3.7038, now.location!!.longitude, 0.0001)
                s.saveDeviceSettings(now, DeviceSettings.of(now).copy(sshPort = null, rdpPort = null, location = null))
                val back = reread { it.sshPort == null && it.location == null }
                assertNull(back.sshPort); assertNull(back.rdpPort); assertNull(back.location)
                assertEquals(22, back.portFor(22))
                try { s.saveDeviceSettings(back, DeviceSettings.of(back).copy(sshPort = 70000)); fail("a bad port") }
                catch (e: MeshClientException) { assertEquals("ficha/cambiar-ajustes/dato-invalido", e.key) }
            } finally {
                s.saveDeviceSettings(s.devices().first { it.id == d.id }, before)
            }
        } finally { s.close() }
    }

    @Test fun `the Windows agent of a group comes as a program and the Linux command carries the group`() = runBlocking {
        need()
        val s = admin()
        try {
            val g = s.deviceGroups().first { it.id == groupId }
            val out = java.io.ByteArrayOutputStream()
            val n = s.downloadAgent(g, AgentPlatform.Windows64, AgentInstallMode.BackgroundAndInteractive, out)
            val bytes = out.toByteArray()
            assertEquals(n, bytes.size.toLong())
            assertTrue("only $n bytes", n > 100_000)
            assertEquals('M'.code.toByte(), bytes[0]); assertEquals('Z'.code.toByte(), bytes[1])
            val cmd = s.linuxInstallCommand(g)
            assertTrue(cmd, cmd.contains("meshagents?script=1") && cmd.contains("'${shortGroupId(g.id)}'") && cmd.contains("--no-check-certificate"))
        } finally { s.close() }
    }
}
