package io.github.nebur692.meshclient.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 (SHA-1, 30 s, 6 digits), as authenticator apps; only for the tests. / Como las apps de autenticación; solo para las pruebas. */
internal fun totp(base32: String, atMs: Long = System.currentTimeMillis()): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var bits = 0; var value = 0
    val out = mutableListOf<Byte>()
    for (c in base32.uppercase().filter { it != '=' && !it.isWhitespace() }) {
        value = (value shl 5) or alphabet.indexOf(c); bits += 5
        if (bits >= 8) { out += (value shr (bits - 8) and 0xFF).toByte(); bits -= 8 }
    }
    val counter = atMs / 1000 / 30
    val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i) and 0xFF).toByte() }
    val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(out.toByteArray(), "HmacSHA1")) }.doFinal(msg)
    val o = h.last().toInt() and 0x0F
    val n = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
    return "%06d".format(n % 1_000_000)
}
