package io.github.nebur692.meshclient.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A single sign-on provider a MeshCentral domain offers. / Un proveedor de inicio de sesión único que ofrece un dominio de MeshCentral. */
data class SingleSignOn(val strategy: String) {
    /** Where the browser starts it, relative to the server address. / Dónde lo arranca el navegador, respecto a la dirección. */
    val path: String get() = "auth-$strategy"

    /** A readable name, without assuming any particular company. / Un nombre legible, sin dar por hecho ninguna empresa. */
    val displayName: String
        get() = when (strategy) {
            "azure" -> "Microsoft"
            "google" -> "Google"
            "github" -> "GitHub"
            "twitter" -> "X (Twitter)"
            "reddit" -> "Reddit"
            "jumpcloud" -> "JumpCloud"
            "intel" -> "Intel"
            "saml" -> "SAML"
            else -> "OpenID Connect"
        }

    /** Where the browser starts it on this server. / Dónde lo arranca el navegador en este servidor. */
    fun startUrl(server: ServerAddress): String = server.resolve(path).toString()
}

/**
 * Which sign-in methods a server offers, read from its own login page, as the Windows app does: MeshCentral writes them
 * as `authStrategies = 'oidc,saml'.split(',')`, or `authStrategies="oidc".split(",")` when it minifies its pages. The
 * page names every possible method in its code, so only that list counts.
 * Qué formas de entrar ofrece un servidor, leídas de su propia página de entrada, como hace la app de Windows.
 */
object SignInMethods {
    private val known = setOf("oidc", "saml", "azure", "google", "github", "twitter", "reddit", "jumpcloud", "intel")
    private val strategies = Regex("""authStrategies\s*=\s*(['"])([a-z0-9,\-]*)\1\s*\.split""")

    fun parseLoginPage(html: String): List<SingleSignOn> {
        val m = strategies.find(html) ?: return emptyList()
        return m.groupValues[2].split(',').map(String::trim).filter { it in known }.distinct().map(::SingleSignOn)
    }

    /** Never throws: if the page cannot be read, the server simply offers nothing extra.
     *  No lanza nunca: si la página no se puede leer, el servidor sencillamente no ofrece nada más. */
    suspend fun discover(server: ServerAddress, trust: CertificateTrust, userAgent: String = "MeshCentralAndroidClient"): List<SingleSignOn> =
        withContext(Dispatchers.IO) {
            try {
                val client = trust.applyTo(OkHttpClient.Builder()).callTimeout(10, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(server.baseUrl).header("User-Agent", userAgent).build()).execute().use { r ->
                    if (!r.isSuccessful) emptyList() else parseLoginPage(r.body.string())
                }
            } catch (_: IOException) {
                emptyList()
            } catch (_: IllegalStateException) {
                emptyList()
            }
        }
}
