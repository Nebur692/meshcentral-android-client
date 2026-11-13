package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The services answer in the two shapes the website reads (showDeskToolsServices of default.handlebars 1.3.1): Windows
 * with status.state, Linux with serviceType. / La respuesta de servicios en las dos formas que lee la web.
 */
class DeviceToolsParseTest {
    @Test fun `Windows services come with their state and pid, sorted by the name a person reads`() {
        val list = DeviceToolsParse.services("""[{"name":"wuauserv","displayName":"Windows Update","status":{"state":"RUNNING","pid":1234}},
            {"name":"Spooler","displayName":"Print Spooler","status":{"state":"STOPPED","pid":0}}]""")!!
        assertEquals(listOf("Print Spooler", "Windows Update"), list.map { it.displayName })
        assertTrue(list[1].isRunning); assertEquals(1234, list[1].pid)
        assertEquals(null, list[0].pid)
    }

    @Test fun `Linux services come with their type and their name as display name`() {
        val list = DeviceToolsParse.services("""[{"name":"ssh","serviceType":"service"}]""")!!
        assertEquals(DeviceService("ssh", "ssh", "service", null), list.single())
    }

    @Test fun `nothing readable is not a list`() {
        assertNull(DeviceToolsParse.services(null)); assertNull(DeviceToolsParse.services("no"))
        assertNull(DeviceToolsParse.processes("[]"))
    }

    @Test fun `processes by pid, without pid 0 or entries with no command`() {
        val list = DeviceToolsParse.processes("""{"0":{"cmd":"idle"},"12":{"cmd":"sshd","user":"root"},"7":{"user":"x"},"40":{"cmd":"bash","user":""}}""")!!
        assertEquals(listOf(DeviceProcess(40, "bash", null), DeviceProcess(12, "sshd", "root")), list)
    }
}

/** Processes against the bench's real Linux agent. / Procesos contra el agente Linux de verdad del banco. */
class DeviceToolsIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")

    @Test fun `the agent lists its processes, and an order to end one reaches it`() = runBlocking<Unit> {
        assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())
        val s = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            s.deviceGroups()
            var d: Device? = null
            repeat(60) { if (d == null) { d = s.devices().firstOrNull { it.hasAgent }; if (d == null) delay(1000) } }
            val device = assertNotNull("no agent", d).let { d!! }
            val list = s.processes(device)
            assertTrue("no meshagent in ${list?.take(10)}", list!!.any { "meshagent" in it.command })
            // A process of our own to end: started with Run, found, killed, gone. / Uno propio: se lanza, se encuentra, se mata.
            val marker = "sleep 7${System.nanoTime() % 100000}"
            launch { runCatching { s.runCommands(device, "$marker &", shellFor(device, false), 30_000) } }
            var mine: DeviceProcess? = null
            repeat(30) { if (mine == null) { mine = s.processes(device)?.firstOrNull { it.command.contains(marker.substringAfter(' ')) }; if (mine == null) delay(1000) } }
            assertNotNull("the sleep never showed", mine)
            s.killProcess(device, mine!!)
            // The order reaches the agent (it logs "Killing process"). On Linux the agent's process.kill does not end a
            // process it did not start (seen: still S after it), on Windows it does: the app checks the list after.
            // La orden llega al agente. En Linux su process.kill no acaba un proceso que no lanzó él; en Windows sí.
            var logged = false
            repeat(20) { if (!logged) { logged = s.events(device.id, 10).any { it.message.startsWith("Killing process ${mine!!.pid} ") }; if (!logged) delay(500) } }
            assertTrue("the agent never got the kill of ${mine!!.pid}", logged)
            s.runCommands(device, "kill ${mine!!.pid}", shellFor(device, false), 30_000) // tidy up / recoger
        } finally { s.close() }
    }
}
