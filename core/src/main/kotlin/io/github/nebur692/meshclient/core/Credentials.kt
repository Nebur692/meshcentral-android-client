package io.github.nebur692.meshclient.core

import java.util.Base64

/**
 * Username, password and, when the server asks, the second-factor code ("**email**" asks it to email one).
 * Usuario, contraseña y, cuando el servidor lo pide, el código del doble factor («**email**» le pide que mande uno).
 */
class PasswordCredentials(val username: String, private val password: String, val twoFactorCode: String? = null) {

    /** The x-meshauth header: each part in base64, comma-separated. / La cabecera x-meshauth: cada parte en base64, con comas. */
    internal fun header(): String {
        fun b(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        var h = b(username) + "," + b(password)
        val code = twoFactorCode?.trim()
        if (!code.isNullOrEmpty()) h += "," + b(code)
        return h
    }

    fun withCode(code: String?) = PasswordCredentials(username, password, code)

    // Never prints the password. / Nunca escribe la contraseña.
    override fun toString() = "PasswordCredentials(username=$username)"

    companion object {
        const val EMAIL_CODE = "**email**"
        const val SMS_CODE = "**sms**"
    }
}
