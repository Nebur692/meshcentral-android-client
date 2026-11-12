package io.github.nebur692.meshclient.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.CertificateTrust
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.ServerAddress

/**
 * The single sign-on provider's page in a browser inside the app, as the Windows app does with its own browser: the
 * person signs in there (never in the app), and when the browser is back on MeshCentral its session cookies go to the
 * control channel. MeshCentral's cookies are cleared before and after, so each time is a fresh session; the provider's
 * own cookies stay, so it can remember the person.
 * La página del proveedor en un navegador dentro de la app, como hace la de Windows: la persona entra ahí (nunca en la
 * app) y, cuando el navegador vuelve a MeshCentral, sus cookies de sesión van al canal de control. Las de MeshCentral
 * se borran antes y después; las del proveedor se quedan, para que pueda recordar a la persona.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignOnScreen(how: Screen.SignOn, onCancel: () -> Unit, onDone: (Map<String, String>) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf(how.address.host) }
    // Back goes back inside the provider's pages first. / Atrás vuelve primero dentro de las páginas del proveedor.
    BackHandler { view?.takeIf { it.canGoBack() }?.goBack() ?: onCancel() }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.signon_title, how.method.displayName), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Where the page really is, so the person can tell. / Dónde está de verdad la página, para que se vea.
                    Text(host, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            },
            navigationIcon = {
                IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel)) }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        configure(this, how, onPage = { loading = it.loading; host = it.host }, onGone = onCancel) { cookies -> onDone(cookies) }
                        view = this
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private data class PageState(val loading: Boolean, val host: String)

// The provider's page needs JavaScript; nothing of the app is exposed to it (no JavaScript interface).
// La página del proveedor necesita JavaScript; no se le expone nada de la app (sin interfaz de JavaScript).
@SuppressLint("SetJavaScriptEnabled")
private fun configure(web: WebView, how: Screen.SignOn, onPage: (PageState) -> Unit, onGone: () -> Unit, done: (Map<String, String>) -> Unit) {
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.allowFileAccess = false
    web.settings.allowContentAccess = false
    CookieManager.getInstance().setAcceptCookie(true)
    forgetSession(how.address)
    val trust = CertificateTrust(how.pinnedSha256)
    var finished = false
    web.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            onPage(PageState(true, url.toUri().host.orEmpty()))
        }

        override fun onPageFinished(view: WebView, url: String) {
            val u = url.toUri()
            onPage(PageState(false, u.host.orEmpty()))
            // Back on MeshCentral, past its sign-on pages ("/auth-…"). / De vuelta en MeshCentral, pasadas sus páginas de entrada.
            if (finished || !sameOrigin(url, how.address) || u.path.orEmpty().startsWith("/auth-")) return
            val cookies = MeshSession.parseCookies(CookieManager.getInstance().getCookie(how.address.toString()))
            if ("xid" !in cookies) return
            finished = true
            forgetSession(how.address)
            done(cookies)
        }

        // The page's process died (out of memory, or killed): without this the whole app would close. The person starts again.
        // Murió el proceso de la página (sin memoria, o lo mataron): sin esto se cerraría la app entera. La persona empieza otra vez.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (!finished) { finished = true; onGone() }
            return true
        }

        // Only the certificate the person already accepted for this server, by its fingerprint; anything else is refused.
        // Solo el certificado que la persona ya aceptó para este servidor, por su huella; lo demás se rechaza.
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val cert = error.certificate.x509Certificate
            if (cert != null && sameOrigin(error.url, how.address) && trust.isPinned(cert)) handler.proceed() else handler.cancel()
        }
    }
    web.loadUrl(how.method.startUrl(how.address))
}

/** Same scheme, name and port as the server: a provider on the same name but another port is not MeshCentral.
 *  Mismo esquema, nombre y puerto que el servidor: un proveedor con el mismo nombre en otro puerto no es MeshCentral. */
internal fun sameOrigin(url: String, server: ServerAddress): Boolean {
    fun origin(u: android.net.Uri): String? {
        val scheme = u.scheme?.lowercase() ?: return null
        val port = if (u.port != -1) u.port else when (scheme) { "https" -> 443; "http" -> 80; else -> return null }
        return "$scheme://${u.host?.lowercase()}:$port"
    }
    return origin(url.toUri()) != null && origin(url.toUri()) == origin(server.toString().toUri())
}

/** Clears MeshCentral's own cookies for this server (not the provider's). / Borra las cookies de MeshCentral (no las del proveedor). */
private fun forgetSession(server: ServerAddress) {
    val jar = CookieManager.getInstance()
    val url = server.toString()
    MeshSession.parseCookies(jar.getCookie(url)).keys.forEach { jar.setCookie(url, "$it=; Max-Age=0; Path=/") }
    jar.flush()
}
