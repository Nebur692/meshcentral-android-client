package io.github.nebur692.meshclient.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A MeshCentral server's address as a person types it: "remote.example.com", "https://remote.example.com/sub/",
 * even a pasted ".../control.ashx" or ".../login". Always HTTPS, with no query, fragment or user.
 * La dirección de un servidor MeshCentral tal como la escribe una persona. Siempre HTTPS, sin consulta, fragmento ni usuario.
 */
class ServerAddress private constructor(val baseUrl: HttpUrl) {

    val host: String get() = baseUrl.host

    /** The control web socket. / El web socket de control. */
    val controlUrl: HttpUrl get() = resolve("control.ashx")

    internal fun resolve(relative: String): HttpUrl = baseUrl.resolve(relative) ?: error("bad relative url $relative")

    override fun toString(): String = baseUrl.toString()
    override fun equals(other: Any?): Boolean = other is ServerAddress && other.baseUrl == baseUrl
    override fun hashCode(): Int = baseUrl.hashCode()

    companion object {
        private val pages = listOf("control.ashx", "login", "auth-oidc")

        fun parse(text: String?): ServerAddress? {
            var t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (!t.contains("://")) t = "https://$t"
            val scheme = t.substringBefore("://").lowercase()
            if (scheme != "https" && scheme != "wss") return null
            t = "https://" + t.substringAfter("://")
            // OkHttp's parser accepts these silently; a server address never has them.
            // El analizador de OkHttp los acepta sin decir nada; una dirección de servidor nunca los lleva.
            val rest = t.substringAfter("://")
            if (rest.contains('?') || rest.contains('#') || rest.substringBefore('/').contains('@')) return null
            val url = t.toHttpUrlOrNull() ?: return null
            if (url.host.isEmpty()) return null
            var path = url.encodedPath
            for (page in pages) if (path.endsWith("/$page", ignoreCase = true)) path = path.dropLast(page.length)
            if (!path.endsWith("/")) path += "/"
            return ServerAddress(url.newBuilder().encodedPath(path).build())
        }
    }
}
