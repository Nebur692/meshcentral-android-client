package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the server sends, read the way meshuser.js writes it. / Lo que manda el servidor, leído como lo escribe meshuser.js. */
class AccountUnitTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `the clipboard switches come from features2, each on its own`() {
        assertTrue(ServerInfo.from(obj("""{"name":"a"}""")).let { it.clipboardGetAllowed && it.clipboardSetAllowed })
        val noGet = ServerInfo.from(obj("""{"features2":2048}"""))
        assertFalse(noGet.clipboardGetAllowed); assertTrue(noGet.clipboardSetAllowed)
        val noSet = ServerInfo.from(obj("""{"features2":4096}"""))
        assertTrue(noSet.clipboardGetAllowed); assertFalse(noSet.clipboardSetAllowed)
        assertFalse(ServerInfo.from(obj("""{"features2":6144}""")).let { it.clipboardGetAllowed || it.clipboardSetAllowed })
    }

    @Test fun `the second factors are told apart, and a phone counts as one`() {
        val none = UserInfo.from(obj("""{"_id":"user//a","name":"a"}"""))
        assertFalse(none.hasTwoFactor || none.hasAuthenticator || none.hasBackupCodes); assertEquals(0, none.securityKeys)
        val app = UserInfo.from(obj("""{"_id":"user//a","otpsecret":1,"otpkeys":1}"""))
        assertTrue(app.hasTwoFactor && app.hasAuthenticator && app.hasBackupCodes)
        val keys = UserInfo.from(obj("""{"_id":"user//a","otphkeys":2}"""))
        assertTrue(keys.hasTwoFactor); assertFalse(keys.hasAuthenticator); assertEquals(2, keys.securityKeys)
        assertTrue(UserInfo.from(obj("""{"_id":"user//a","phone":"+34600000000"}""")).hasTwoFactor)
    }

    @Test fun `the server's state reads memory, load and counters, and Windows' has no available memory`() {
        val linux = ServerStats.from(obj("""{"action":"serverstats","totalmem":8000,"freemem":1000,"availablemem":3000,"cpuavg":[0.5,0.25,0.1],
            "values":{"ServerState":{"UserAccounts":3,"AgentSessions":1,"Odd":"x"},"AgentErrorCounters":{"unknownAgentActionCount":2}}}"""))
        assertEquals(5000, linux.usedMemory)
        assertEquals(listOf(0.5, 0.25, 0.1), linux.cpuAverage)
        assertEquals(mapOf("UserAccounts" to 3L, "AgentSessions" to 1L), linux.state)
        assertEquals(2L, linux.agentErrors["unknownAgentActionCount"])
        val windows = ServerStats.from(obj("""{"totalmem":8000,"freemem":1000}"""))
        assertNull(windows.availableMemory)
        assertEquals(7000, windows.usedMemory)
        assertTrue(windows.cpuAverage.isEmpty())
    }
}
