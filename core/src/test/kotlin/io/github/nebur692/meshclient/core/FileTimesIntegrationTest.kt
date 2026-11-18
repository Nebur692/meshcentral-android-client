package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A file just written on a Linux device on Madrid time shows, on a phone on Madrid time, the time the device's clock
 * read: the agent sends that reading with a false "Z" (Windows agents send true UTC). As the Windows app's test.
 * Un fichero recién escrito en un equipo Linux en hora de Madrid sale, en un móvil en hora de Madrid, con la hora de su reloj.
 */
class FileTimesIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val groupId = System.getenv("MESHTEST_GROUP_ID")

    @Test fun `a new file shows the device clock`() = runBlocking {
        assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())
        // The bench's agent and these tests run on Madrid time (run-integration.sh). / Los dos en hora de Madrid.
        assertEquals("Europe/Madrid", ZoneId.systemDefault().id)
        val s = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
        try {
            s.deviceGroups()
            var d: Device? = null
            repeat(60) { if (d == null) { d = s.devices().firstOrNull { it.groupId == groupId && it.hasAgent }; if (d == null) delay(500) } }
            val dev = d ?: run { fail("no online agent"); error("") }
            assertFalse(dev.isWindows)
            val f = FilesSession.open(s, dev)
            val name = "hora-" + MeshSession.newId().take(6) + ".txt"
            f.upload("/tmp", name, byteArrayOf(1, 2, 3).inputStream(), 3)
            try {
                val e = f.list("/tmp").entries.first { it.name == name }
                val shown = LocalDateTime.ofInstant(e.modified!!, ZoneId.systemDefault())
                val clock = LocalDateTime.now(ZoneId.of("Europe/Madrid"))
                assertTrue("shown $shown, device clock $clock", Duration.between(shown, clock).abs().toMinutes() < 2)
            } finally { f.delete("/tmp", listOf(name), false) }
            f.close()
        } finally { s.close() }
    }

    @Test fun `Windows times stay UTC and others keep their clock reading`() {
        val raw = JsonPrimitive("2026-11-18T11:05:30Z")
        assertEquals(java.time.Instant.parse("2026-11-18T11:05:30Z"), FilesSession.date(raw, deviceClock = false))
        assertEquals(LocalDateTime.of(2026, 11, 18, 11, 5, 30), LocalDateTime.ofInstant(FilesSession.date(raw, deviceClock = true)!!, ZoneId.systemDefault()))
    }
}
