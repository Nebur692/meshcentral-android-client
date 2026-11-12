package io.github.nebur692.meshclient.core

import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Port mapping and SSH through the bench's real agent, whose container runs an SSH server (tecnico / Ssh-Pass-1234).
 * Redirección de puertos y SSH por el agente de verdad del banco, cuyo contenedor tiene un servidor SSH.
 */
class ConnectionsIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val withAgentServices = System.getenv("MESHTEST_DESKTOP") == "1"
    private fun need() = assumeTrue("no test server with the agent's SSH", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && withAgentServices)
    private suspend fun admin() = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

    private suspend fun MeshSession.agent(): Device {
        deviceGroups()
        repeat(60) { devices().firstOrNull { it.hasAgent }?.let { return it }; delay(1000) }
        fail("no online agent"); error("")
    }

    /** The SSH banner through a relay, waiting for sshd to be up (it starts after apt). / El saludo SSH por un relé. */
    private suspend fun banner(s: MeshSession, d: Device): String {
        repeat(90) {
            val relay = try { TcpRelay.open(s, d.id, 22) } catch (_: MeshClientException) { delay(1000); return@repeat }
            val first = withTimeout(10_000) { relay.receive() }
            relay.close()
            if (first != null) return String(first)
            delay(1000)
        }
        fail("no SSH banner"); error("")
    }

    @Test fun `a relay to port 22 brings the SSH banner, and a mapping on 127_0_0_1 does too`() = runBlocking<Unit> {
        need()
        val s = admin()
        try {
            val d = s.agent()
            assertTrue(banner(s, d).startsWith("SSH-2.0-"))
            val mapping = PortMapping.start(s, PortTarget(d.id, d.name, 22))
            try {
                assertTrue(mapping.localPort > 0)
                val line = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", mapping.localPort).use { c -> c.soTimeout = 15_000; c.getInputStream().bufferedReader().readLine() }
                }
                assertTrue("odd banner $line", line.startsWith("SSH-2.0-"))
                // A client that sends and half-closes ("echo | nc") still gets the answer. / Uno que manda y medio cierra recibe la respuesta.
                val half = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", mapping.localPort).use { c ->
                        c.soTimeout = 15_000
                        c.getOutputStream().write("\n".toByteArray()); c.shutdownOutput()
                        c.getInputStream().bufferedReader().readLine()
                    }
                }
                assertTrue("half-closed client got $half", half != null && half.startsWith("SSH-2.0-"))
                withTimeout(5_000) { mapping.stats.first { it.total == 2 && it.bytesDown > 0 } }
            } finally { mapping.close() }
        } finally { s.close() }
    }

    @Test fun `a port nobody listens on ends the relay instead of hanging`() = runBlocking<Unit> {
        need()
        val s = admin()
        try {
            val d = s.agent()
            banner(s, d) // the agent is ready / el agente está listo
            val ended = try {
                val r = TcpRelay.open(s, d.id, 2)
                withTimeout(15_000) { r.receive() }.also { r.close() } == null
            } catch (e: MeshClientException) { true }
            assertTrue("port 2 gave bytes", ended)
        } finally { s.close() }
    }

    @Test fun `an SSH terminal through the agent signs in, runs a command, and refuses a wrong password and an untrusted identity`() = runBlocking<Unit> {
        need()
        val s = admin()
        try {
            val d = s.agent()
            banner(s, d)
            var seen: SshHostKey? = null
            val t = SshTerminal.open(s, d.id, d.name, null, 22, SshLogin("tecnico", "Ssh-Pass-1234"), { seen = it; true }, 80, 24)
            try {
                assertTrue("odd key $seen", seen!!.fingerprint.startsWith("SHA256:") && seen!!.algorithm.isNotEmpty())
                assertEquals(seen, t.hostKey)
                val out = StringBuilder()
                val reading = launch(Dispatchers.IO) { t.output.collect { synchronized(out) { out.append(String(it)) } } }
                t.write("echo hola-$$-ssh | tr a-z A-Z\n")
                withTimeout(20_000) { while (synchronized(out) { "HOLA-" !in out.toString() || "-SSH" !in out.toString() }) delay(100) }
                t.resize(100, 30)
                reading.cancel()
            } finally { t.close() }

            val wrong = try { SshTerminal.open(s, d.id, d.name, null, 22, SshLogin("tecnico", "mala"), { true }, 80, 24).close(); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.InvalidCredentials, wrong?.failure)

            val untrusted = try { SshTerminal.open(s, d.id, d.name, null, 22, SshLogin("tecnico", "Ssh-Pass-1234"), { false }, 80, 24).close(); null } catch (e: MeshClientException) { e }
            assertEquals(MeshFailure.UntrustedCertificate, untrusted?.failure)
        } finally { s.close() }
    }
}
