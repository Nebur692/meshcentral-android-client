package io.github.nebur692.meshclient.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The codes of an authenticator app (RFC 6238: HMAC-SHA1, 30 s, 6 digits), as MeshCentral checks them (otplib). The app
 * uses it to be the authenticator itself: the secret is kept on the phone and the code made here.
 * Los códigos de una app de autenticación, como los comprueba MeshCentral. La app lo usa para ser ella misma el
 * autenticador: el secreto se guarda en el móvil y el código se hace aquí.
 */
object Totp {
    const val PERIOD_S = 30L

    /** The code at [atMs] (ms since 1970) for a base32 [secret]. / El código en ese momento para un secreto en base32. */
    fun code(secret: String, atMs: Long = System.currentTimeMillis()): String {
        val key = base32(secret)
        require(key.isNotEmpty()) { "empty secret" }
        val counter = atMs / 1000 / PERIOD_S
        val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i) and 0xFF).toByte() }
        val h = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(msg)
        val o = h.last().toInt() and 0x0F
        val n = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        return "%06d".format(n % 1_000_000)
    }

    /** Seconds until the code changes. / Segundos hasta que cambia el código. */
    fun secondsLeft(atMs: Long = System.currentTimeMillis()): Int = (PERIOD_S - (atMs / 1000) % PERIOD_S).toInt()

    /** True for a base32 secret an authenticator takes (16 characters or more). / Si es un secreto en base32 válido. */
    fun validSecret(secret: String): Boolean {
        val s = secret.uppercase().filter { it != '=' && !it.isWhitespace() }
        return s.length >= 16 && s.all { it in ALPHABET }
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32(secret: String): ByteArray {
        var bits = 0; var value = 0
        val out = mutableListOf<Byte>()
        for (c in secret.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "not base32" }
            value = (value shl 5) or v; bits += 5
            if (bits >= 8) { out += (value shr (bits - 8) and 0xFF).toByte(); bits -= 8 }
        }
        return out.toByteArray()
    }
}
