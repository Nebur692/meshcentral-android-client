package io.github.nebur692.meshclient.core

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
 * Updating the agent, a group's consent and features, and the recordings, against the bench
 * (tests/server/run-integration.sh, whose server records every session).
 * Actualizar el agente, el consentimiento y las funciones de un grupo, y las grabaciones, contra el banco (cuyo servidor
 * graba todas las sesiones).
 */
class GroupAndAgentIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!

    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun admin() = MeshSession.connect(server, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))

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

    @Test fun `the agent's own answer to an update comes back`() = runBlocking {
        need()
        val s = admin()
        try {
            val d = s.agent()
            assertTrue(s.canUpdateAgent(d))
            val said = s.updateAgents(listOf(d))
            // Measured on 1.3.1: the bench's agent runs with "connect", not as a service, so it cannot replace itself.
            // Medido en 1.3.1: el agente del banco corre con «connect», no como servicio, así que no puede reemplazarse.
            assertEquals("Self Update Failed because this agent is not an instance of (meshagent)", said[d])
            assertTrue("the session goes on", s.isOpen)
        } finally { s.close() }
    }

    @Test fun `consent and features are saved as the website saves them and read back`() = runBlocking {
        need()
        val s = admin()
        try {
            val g = s.deviceGroups().first { it.kind == DeviceGroupKind.Agents }
            val before = g.settings
            try {
                val wanted = GroupSettings(
                    consent = GroupSettings.Consent.DESKTOP_PROMPT or GroupSettings.Consent.DESKTOP_TOOLBAR or GroupSettings.Consent.FILES_NOTIFY,
                    // PREFER_AGENT_NAME without SYNC_NAME falls; RECORD falls too (the bench records every group already).
                    // PREFER_AGENT_NAME sin SYNC_NAME cae; RECORD también (el banco ya graba todos los grupos).
                    flags = GroupSettings.Feature.PREFER_AGENT_NAME or GroupSettings.Feature.RECORD,
                    removeInactiveDays = 30)
                val saved = s.saveGroupSettings(g, wanted)
                assertEquals(GroupSettings(0x8 or 0x40 or 0x4, 0, 30), saved)
                assertEquals(saved, s.deviceGroups().first { it.id == g.id }.settings)
                // Removing on disconnect and removing the inactive ones do not go together. / Las dos no van juntas.
                val other = s.saveGroupSettings(g, GroupSettings(0, GroupSettings.Feature.REMOVE_ON_DISCONNECT or GroupSettings.Feature.SYNC_NAME
                    or GroupSettings.Feature.ALLOW_OVERRIDE, 30))
                assertEquals(GroupSettings(0, 0x1 or 0x2 or 0x10, 0), other)
                assertEquals(other, s.deviceGroups().first { it.id == g.id }.settings)
                assertEquals("grupos/gestionar-grupos/dato-invalido", failureOf { s.saveGroupSettings(g, GroupSettings(0, 0, 2001)) }.key)
            } finally {
                s.saveGroupSettings(s.deviceGroups().first { it.id == g.id }, before)
            }
            assertEquals(before, s.deviceGroups().first { it.id == g.id }.settings)
        } finally { s.close() }
    }

    @Test fun `a session leaves its recording in the list, and a password session cannot download it`() = runBlocking {
        need()
        val s = admin()
        try {
            assertTrue(s.canSeeRecordings)
            assertFalse(s.hasWebSession)
            val d = s.agent()
            val t = TerminalSession.open(s, d.id, TunnelProtocol.Terminal, 80, 24)
            t.write("echo grabado\n"); delay(1500); t.close()
            var found: Recording? = null
            repeat(20) {
                found = s.recordings().firstOrNull { it.kind == RecordedKind.Terminal && it.deviceId == d.id && it.present }
                if (found != null) return@repeat
                delay(1000)
            }
            val r = assertNotNullAndGet(found)
            assertTrue(r.fileName, r.fileName.endsWith(".mcrec"))
            assertTrue(r.userIds.contains(s.user.id))
            assertTrue(r.bytes > 0)
            assertEquals("grabaciones/descargar-grabacion/sesion-cerrada",
                failureOf { s.downloadRecording(r, java.io.ByteArrayOutputStream()) }.key)
        } finally { s.close() }
    }

    private fun assertNotNullAndGet(r: Recording?): Recording { assertNotNull("the terminal session was not recorded", r); return r!! }
}
