package io.github.nebur692.meshclient.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A UDP port mapping through the test agent to the bench's UDP echo (the mailbox container, port 7777): what goes to the
 * local port comes back, and two local senders each get their own answers. The same as the Windows app's.
 * Una redirección UDP por el agente de prueba hasta el eco UDP del banco: lo que va al puerto local vuelve, y dos
 * remitentes locales reciben cada uno sus respuestas.
 */
class UdpPortsIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val sink = System.getenv("MESHTEST_SINK")
    private fun need() = assumeTrue("no test server with its mailbox", !url.isNullOrEmpty() && !cert.isNullOrEmpty() && !sink.isNullOrEmpty())

    private fun ask(socket: DatagramSocket, port: Int, text: String): String? {
        socket.soTimeout = 2_000
        repeat(10) { // the first datagrams wait while the relay opens / los primeros esperan a que se abra el relé
            socket.send(DatagramPacket(text.toByteArray(), text.toByteArray().size, InetAddress.getByName("127.0.0.1"), port))
            val buffer = ByteArray(2048)
            val answer = DatagramPacket(buffer, buffer.size)
            try { socket.receive(answer); return String(answer.data, 0, answer.length) } catch (_: SocketTimeoutException) { }
        }
        return null
    }

    @Test fun `datagrams go to the device's network and answers come back to each sender`() = runBlocking<Unit> {
        need()
        val s = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            s.deviceGroups()
            var device: Device? = null
            repeat(60) { if (device == null) { device = s.devices().firstOrNull { it.isOnline && it.hasAgent }; if (device == null) delay(500) } }
            val d = device ?: error("the test agent never came online")
            val mapping = PortMapping.start(s, PortTarget(d.id, d.name, 7777, URI(sink).host, udp = true))
            try {
                DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { one ->
                    DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { two ->
                        assertEquals("hola desde uno", ask(one, mapping.localPort, "hola desde uno"))
                        assertEquals("y desde dos", ask(two, mapping.localPort, "y desde dos"))
                        assertEquals("otra vez uno", ask(one, mapping.localPort, "otra vez uno"))
                    }
                }
                delay(600) // the stats are told at most twice a second / las cifras se dicen dos veces por segundo como mucho
                assertEquals(2, mapping.stats.value.total)
                assertNull(mapping.stats.value.lastError)
            } finally {
                mapping.close()
            }
        } finally {
            s.close()
        }
    }
}
