package io.github.nebur692.meshclient.core

import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Devices without an agent against the bench: a group of local devices, a device added by its address (the agent's
 * container, whose sshd answers), SSH and a port mapping to it through the server alone, and through the group's relay
 * agent to an address only that agent reaches (127.0.0.1 is the agent's own machine, not the server's).
 * Equipos sin agente contra el banco: un grupo de equipos locales, un equipo añadido por su dirección (el contenedor del
 * agente, con sshd), SSH y una redirección a él solo por el servidor, y por el agente relé del grupo hasta una dirección a
 * la que solo llega ese agente (127.0.0.1 es la máquina del agente, no la del servidor).
 */
class LocalDevicesIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val agentHost = System.getenv("MESHTEST_AGENT_HOST")
    private fun need() = assumeTrue("no test server with the agent's address", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && !agentHost.isNullOrEmpty())
    private suspend fun admin() = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) { devices().firstOrNull { it.hasAgent }?.let { return it }; delay(1000) }
        fail("no online agent"); error("")
    }

    private suspend fun MeshSession.appear(name: String): Device {
        repeat(20) { devices().firstOrNull { it.name == name }?.let { return it }; delay(300) }
        fail("$name did not appear"); error("")
    }

    /** The first bytes port 22 sends through a tunnel, waiting for sshd (it starts after apt). / El saludo de sshd. */
    private suspend fun banner(s: MeshSession, d: Device): String {
        var last: Throwable? = null
        repeat(90) {
            val relay = try { TcpRelay.open(s, d.id, 22) } catch (e: MeshClientException) { last = e; delay(1000); return@repeat }
            val first = withTimeout(10_000) { relay.receive() }
            relay.close()
            if (first != null) return String(first)
            delay(1000)
        }
        fail("no SSH banner from ${d.name}: $last"); error("")
    }

    @Test fun `a device without an agent is reached by the server itself and through the group's relay agent`() = runBlocking<Unit> {
        need()
        val s = admin()
        val made = mutableListOf<String>()
        try {
            val agent = s.agent()

            // A group of local devices with no relay: the server connects itself. / Sin relé: conecta el servidor.
            val direct = s.createLocalGroup("Locales ${System.nanoTime()}").also(made::add)
            val g = s.deviceGroups().first { it.id == direct }
            assertEquals(DeviceGroupKind.LocalDevices, g.kind)
            assertNull(g.relayId)
            s.addLocalDevice(g, "caja-ssh", agentHost, LocalDeviceOs.Linux)
            val box = s.appear("caja-ssh")
            assertTrue("the server marks it as a local device", box.isLocal && s.isLocal(box))
            assertEquals(agentHost, box.address)
            assertTrue(!box.hasAgent)
            assertTrue(banner(s, box).startsWith("SSH-2.0-"))

            // SSH signs in on it, and a mapping on 127.0.0.1 brings the banner. / SSH entra y la redirección trae el saludo.
            val t = SshTerminal.open(s, box.id, box.name, null, 22, SshLogin("tecnico", "Ssh-Pass-1234"), { true }, 80, 24)
            t.close()
            val m = PortMapping.start(s, PortTarget(box.id, box.name, 22))
            try {
                val line = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", m.localPort).use { it.soTimeout = 15_000; it.getInputStream().bufferedReader().readLine() }
                }
                assertTrue("odd banner $line", line.startsWith("SSH-2.0-"))
            } finally { m.close() }

            // Its address changes, as the website's edit does. / Su dirección cambia, como la edición de la web.
            s.changeDevice(box, host = "otra-$agentHost")
            assertEquals("otra-$agentHost", s.devices().first { it.id == box.id }.address)

            // Through the agent: 127.0.0.1 is the agent's machine, where sshd is; the server has none.
            // Por el agente: 127.0.0.1 es la máquina del agente, donde está sshd; el servidor no tiene.
            val relayed = s.createLocalGroup("Locales relé ${System.nanoTime()}", relayId = agent.id).also(made::add)
            val rg = s.deviceGroups().first { it.id == relayed }
            assertEquals(agent.id, rg.relayId)
            s.addLocalDevice(rg, "detras-del-agente", "127.0.0.1", LocalDeviceOs.Linux)
            val behind = s.appear("detras-del-agente")
            assertEquals(agent.id, s.relayOf(behind))
            assertTrue(banner(s, behind).startsWith("SSH-2.0-"))

            // What the server would refuse is refused before sending. / Lo que rechazaría el servidor, antes de mandarlo.
            val agents = s.deviceGroups().first { it.id == agent.groupId }
            val wrongGroup = try { s.addLocalDevice(agents, "x", "10.0.0.1", LocalDeviceOs.Windows); null } catch (e: MeshClientException) { e }
            assertEquals("equipos/anadir-equipo/dato-invalido", wrongGroup?.key)
            val badHost = try { s.addLocalDevice(g, "x", "con espacios", LocalDeviceOs.Windows); null } catch (e: MeshClientException) { e }
            assertEquals("equipos/anadir-equipo/dato-invalido", badHost?.key)
        } finally {
            for (id in made) s.deviceGroups().firstOrNull { it.id == id }?.let { runCatching { s.deleteGroup(it) } }
            s.close()
        }
    }
}
