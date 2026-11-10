package io.github.nebur692.meshclient.core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test fun `what a person types becomes the server's base address`() {
        assertEquals("https://remote.example.com/", ServerAddress.parse("remote.example.com").toString())
        assertEquals("https://remote.example.com/", ServerAddress.parse("  https://remote.example.com  ").toString())
        assertEquals("https://remote.example.com:4443/mesh/", ServerAddress.parse("remote.example.com:4443/mesh").toString())
        assertEquals("https://remote.example.com/", ServerAddress.parse("wss://remote.example.com/control.ashx").toString())
        assertEquals("https://remote.example.com/sub/", ServerAddress.parse("https://remote.example.com/sub/login").toString())
        assertEquals("https://remote.example.com/", ServerAddress.parse("https://remote.example.com:443/").toString())
        assertEquals("https://remote.example.com/control.ashx", ServerAddress.parse("remote.example.com")!!.controlUrl.toString())
    }

    @Test fun `what is not a server address is refused`() {
        for (bad in listOf(null, "", "   ", "http://remote.example.com", "ftp://x", "https://user@remote.example.com",
            "https://remote.example.com/?a=1", "https://remote.example.com/#x", "https://"))
            assertNull("accepted: $bad", ServerAddress.parse(bad))
    }
}

class CredentialsTest {
    private fun parts(c: PasswordCredentials) = c.header().split(",").map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }

    @Test fun `the header carries each part in base64, the code only when there is one`() {
        assertEquals(listOf("ana", "pa,ss ñ"), parts(PasswordCredentials("ana", "pa,ss ñ")))
        assertEquals(listOf("ana", "x", "123456"), parts(PasswordCredentials("ana", "x", " 123456 ")))
        assertEquals(listOf("ana", "x"), parts(PasswordCredentials("ana", "x", "  ")))
    }

    @Test fun `the password never shows in text`() {
        assertFalse(PasswordCredentials("ana", "Secret-1").toString().contains("Secret-1"))
    }
}

class LoginFailureTest {
    private fun close(s: String) = Json.parseToJsonElement(s).jsonObject
    private val plain = PasswordCredentials("ana", "x")

    @Test fun `each refusal of the server becomes its own failure`() {
        assertEquals(MeshFailure.InvalidCredentials, MeshSession.loginFailure(close("""{"action":"close","cause":"noauth","msg":"noauth-2a"}"""), plain).failure)
        assertEquals(MeshFailure.TooManyAttempts, MeshSession.loginFailure(close("""{"action":"close","cause":"banned"}"""), plain).failure)
        assertEquals(MeshFailure.ToolsNotAllowed, MeshSession.loginFailure(close("""{"action":"close","cause":"notools"}"""), plain).failure)
        assertEquals(MeshFailure.Rejected, MeshSession.loginFailure(close("""{"action":"close","cause":"other"}"""), plain).failure)
    }

    @Test fun `a second factor asked for, and a wrong one, are told apart`() {
        val asked = MeshSession.loginFailure(close("""{"action":"close","cause":"noauth","msg":"tokenrequired","email2fa":true}"""), plain)
        assertEquals(MeshFailure.TwoFactorRequired, asked.failure)
        assertTrue(asked.emailCodeAvailable)
        val wrong = MeshSession.loginFailure(close("""{"action":"close","cause":"noauth","msg":"tokenrequired"}"""), plain.withCode("000000"))
        assertEquals(MeshFailure.InvalidTwoFactorCode, wrong.failure)
        // Asking for an email code is not a wrong code. / Pedir el código por correo no es un código malo.
        val email = MeshSession.loginFailure(close("""{"action":"close","cause":"noauth","msg":"tokenrequired","email2fasent":true}"""), plain.withCode(PasswordCredentials.EMAIL_CODE))
        assertEquals(MeshFailure.TwoFactorRequired, email.failure)
        assertTrue(email.emailCodeSent)
    }
}

class DeviceTest {
    private fun node(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `a node from the server becomes a device`() {
        val d = Device.from(node("""{"_id":"node//abc","name":"RECEPCION-01","osdesc":"Windows 11 Pro","ip":"192.168.1.20","conn":1,"pwr":1,
            "tags":["recepción","",3],"agent":{"id":4,"caps":15}}"""), "mesh//g1")
        assertEquals("node//abc", d.id)
        assertEquals("mesh//g1", d.groupId)
        assertEquals(listOf("recepción"), d.tags)
        assertEquals(OsFamily.Windows, d.osFamily)
        assertTrue(d.isOnline && d.canOpenDesktop && d.canOpenTerminal && d.canOpenFiles)
    }

    @Test fun `offline or without capabilities, nothing can be opened`() {
        val off = Device.from(node("""{"_id":"n","name":"x","conn":0,"agent":{"id":6,"caps":15},"osdesc":"Ubuntu 24.04"}"""))
        assertFalse(off.isOnline || off.canOpenDesktop)
        assertEquals(OsFamily.Linux, off.osFamily)
        val noDesk = Device.from(node("""{"_id":"n","name":"x","conn":1,"agent":{"id":6,"caps":2}}"""))
        assertFalse(noDesk.canOpenDesktop)
        assertTrue(noDesk.canOpenTerminal)
    }

    @Test fun `the system comes from the agent type, also for devices without an agent`() {
        fun family(json: String) = Device.from(node(json)).osFamily
        // Local groups (no agent, no description): 4 Windows, 6 Linux, 29 macOS. / Grupos locales: sin agente ni descripción.
        assertEquals(OsFamily.Windows, family("""{"_id":"n","name":"pc","mtype":3,"agent":{"id":4,"caps":0}}"""))
        assertEquals(OsFamily.Linux, family("""{"_id":"n","name":"srv","mtype":3,"agent":{"id":6,"caps":0}}"""))
        assertEquals(OsFamily.Mac, family("""{"_id":"n","name":"mac","mtype":3,"agent":{"id":29,"caps":0}}"""))
        // Agents of other systems. / Agentes de otros sistemas.
        assertEquals(OsFamily.Mac, family("""{"_id":"n","name":"m","agent":{"id":16,"caps":15}}"""))
        assertEquals(OsFamily.Android, family("""{"_id":"n","name":"a","agent":{"id":14,"caps":0}}"""))
        assertEquals(OsFamily.Linux, family("""{"_id":"n","name":"pi","agent":{"id":26,"caps":15}}"""))
        // Unknown type: the description decides; with neither, Other. / Tipo desconocido: decide la descripción; sin nada, Otro.
        assertEquals(OsFamily.Linux, family("""{"_id":"n","name":"b","agent":{"id":0},"osdesc":"FreeBSD 14"}"""))
        assertEquals(OsFamily.Other, family("""{"_id":"n","name":"z"}"""))
    }

    @Test fun `a change keeps the live state it does not carry`() {
        val live = Device.from(node("""{"_id":"n","name":"old","conn":1,"pwr":1}"""))
        val changed = Device.from(node("""{"_id":"n","name":"new"}"""))
        val merged = live.withDetails(changed)
        assertEquals("new", merged.name)
        assertEquals(1, merged.connectivity)
    }
}

class CertificateTrustTest {
    @Test fun `a fingerprint is accepted with or without separators`() {
        val hex = "AB".repeat(32)
        assertEquals(hex, CertificateTrust(hex.chunked(2).joinToString(":").lowercase()).pinnedSha256)
        assertEquals("AB:AB", CertificateTrust.display("ABAB"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short fingerprint is refused`() {
        CertificateTrust("ABCD")
    }
}
