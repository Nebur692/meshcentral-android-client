package io.github.nebur692.meshclient.core

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Which server certificates are accepted: any the system trusts for that name, plus ONE pinned by its SHA-256
 * fingerprint, the one the person accepted for a server with its own certificate. What was refused is kept, so the app
 * can show its fingerprint and ask.
 * Qué certificados de servidor se aceptan: cualquiera en el que confíe el sistema para ese nombre, más UNO fijado por su
 * huella SHA-256, el que la persona aceptó para un servidor con certificado propio. Lo rechazado se guarda, para que la
 * app enseñe su huella y pregunte.
 */
class CertificateTrust(pinnedSha256: String? = null) {

    val pinnedSha256: String? = normalise(pinnedSha256)

    @Volatile var rejectedSha256: String? = null
        private set
    @Volatile var rejectedSubject: String? = null
        private set

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    /** The certificate the person accepted, by its fingerprint (also for the sign-on browser). / El que aceptó la persona, por su huella. */
    fun isPinned(cert: X509Certificate): Boolean {
        val pin = pinnedSha256 ?: return false
        return MessageDigest.isEqual(fingerprint(cert).toByteArray(), pin.toByteArray())
    }

    private fun reject(cert: X509Certificate) {
        rejectedSha256 = fingerprint(cert)
        rejectedSubject = cert.subjectX500Principal.name
    }

    internal val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not checked here")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("the server sent no certificate")
            if (isPinned(chain[0])) return
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                reject(chain[0])
                throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /** The system's check of the name, unless this is the pinned certificate. / La comprobación del nombre del sistema, salvo el fijado. */
    internal val hostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
        if (isPinned(leaf)) return@HostnameVerifier true
        val ok = defaultVerifier.verify(host, session)
        if (!ok) reject(leaf)
        ok
    }

    internal val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    internal fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(socketFactory, trustManager).hostnameVerifier(hostnameVerifier)

    companion object {
        private val defaultVerifier: HostnameVerifier = OkHttpClient().hostnameVerifier

        /** Upper-case hex, no separators. / Hexadecimal en mayúsculas, sin separadores. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }

        /** "AB:CD:…" for people. / «AB:CD:…» para personas. */
        fun display(fingerprint: String): String = fingerprint.chunked(2).joinToString(":")

        internal fun normalise(fp: String?): String? {
            if (fp.isNullOrBlank()) return null
            val hex = fp.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.uppercase()
            require(hex.length == 64) { "A SHA-256 fingerprint has 64 hex digits." }
            return hex
        }
    }
}
