package io.github.nebur692.meshclient.core

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Security keys of the account, against the bench: a key in software is added the way the website does (over the control
 * channel), and the app lists and removes it. MeshCentral numbers keys with an unsigned 32-bit keyIndex (it did not fit an
 * Int), and sends its "removed" event even for an index that is not there: the app checks the count went down.
 * Uses the "mfa" account.
 * Las llaves de la cuenta contra el banco: una llave por software se añade como la web, y la app la lista y la quita.
 */
class SecurityKeysIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private val server get() = ServerAddress.parse(url)!!
    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())

    private suspend fun connect() = MeshSession.connect(server, PasswordCredentials("mfa", "Mfa-Pass-1234", null), CertificateTrust(cert))

    @Test fun `an index above the Int range comes through whole`() {
        val k = securityKeyOf(buildJsonObject { put("i", 4_000_000_000L); put("name", "YubiKey") })
        assertEquals(4_000_000_000L, k.index)
    }

    @Test fun `a key added as the website does is listed and removed, and a missing one is not removed`() = runBlocking<Unit> {
        need()
        val s = connect()
        try {
            addSoftwareKey(s, "Llave de prueba")
            val keys = s.securityKeys()
            assertEquals(listOf("Llave de prueba"), keys.map { it.name })
            assertTrue("MeshCentral's keyIndex is unsigned 32-bit", keys.single().index in 0..0xFFFFFFFFL)

            val none = try { s.removeSecurityKey(SecurityKey(1, "none", null)); null } catch (e: MeshClientException) { e }
            assertEquals("usuarios/cambiar-ajustes/rechazo-remoto", none?.key)

            s.removeSecurityKey(keys.single())
            assertTrue(s.securityKeys().isEmpty())
            s.close()
            connect().close()
        } finally {
            runCatching { s.securityKeys().forEach { s.removeSecurityKey(it) } }
            s.close()
        }
    }

    /** What the website does with navigator.credentials.create: a P-256 key, attestation "none". / Lo que hace la web. */
    private suspend fun addSoftwareKey(s: MeshSession, name: String) {
        val start = s.request(buildJsonObject { put("action", "webauthn-startregister"); put("name", name) },
            { it.str("action") == "webauthn-startregister" }, "usuarios/cambiar-ajustes/no-autorizado", "usuarios/cambiar-ajustes/conexion-cortada")
        val challenge = Base64.getDecoder().decode(((start["request"] as JsonObject)["challenge"])!!.jsonPrimitive.content)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = pair.public as ECPublicKey
        fun fixed(b: java.math.BigInteger) = b.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val id = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cose = Cbor.map(Cbor.int(1) to Cbor.int(2), Cbor.int(3) to Cbor.int(-7), Cbor.int(-1) to Cbor.int(1),
            Cbor.int(-2) to Cbor.bytes(fixed(pub.w.affineX)), Cbor.int(-3) to Cbor.bytes(fixed(pub.w.affineY)))
        val authData = MessageDigest.getInstance("SHA-256").digest(server.host.toByteArray()) + byteArrayOf(0x45, 0, 0, 0, 0) + ByteArray(16) +
            byteArrayOf(0, id.size.toByte()) + id + cose
        val att = Cbor.map(Cbor.text("fmt") to Cbor.text("none"), Cbor.text("attStmt") to Cbor.map(), Cbor.text("authData") to Cbor.bytes(authData))
        val clientData = """{"type":"webauthn.create","challenge":"${Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)}","origin":"https://${server.host}"}"""
        val r = s.request(buildJsonObject {
            put("action", "webauthn-endregister"); put("name", name)
            putJsonObject("response") {
                put("rawId", Base64.getEncoder().encodeToString(id))
                putJsonObject("response") {
                    put("attestationObject", Base64.getEncoder().encodeToString(att)); put("clientDataJSON", Base64.getEncoder().encodeToString(clientData.toByteArray()))
                }
            }
        }, { it.str("action") == "otp-hkey-setup-response" }, "usuarios/cambiar-ajustes/tiempo-agotado", "usuarios/cambiar-ajustes/conexion-cortada")
        assertTrue("the server took the key", r["result"]?.jsonPrimitive?.content == "true")
    }

    /** Just enough CBOR (RFC 8949) for an attestation. / Lo justo de CBOR para una atestación. */
    private object Cbor {
        private fun head(major: Int, n: Long): ByteArray = when {
            n < 24 -> byteArrayOf(((major shl 5) or n.toInt()).toByte())
            n < 0x100 -> byteArrayOf(((major shl 5) or 24).toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(((major shl 5) or 25).toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(((major shl 5) or 26).toByte(), (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        fun int(v: Long): ByteArray = if (v >= 0) head(0, v) else head(1, -1 - v)
        fun int(v: Int) = int(v.toLong())
        fun bytes(b: ByteArray) = head(2, b.size.toLong()) + b
        fun text(s: String) = s.toByteArray().let { head(3, it.size.toLong()) + it }
        fun map(vararg items: Pair<ByteArray, ByteArray>) = items.fold(head(5, items.size.toLong())) { acc, (k, v) -> acc + k + v }
    }
}
