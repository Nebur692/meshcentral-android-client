package io.github.nebur692.meshclient.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAP_HOST = "appassets.androidplatform.net"

/**
 * The devices on a map, as the website's and the Windows app: OpenStreetMap (Leaflet, inside the app) with a dot per
 * device that has a place, green when online; its "Open" opens the device. It follows the live list.
 * Los equipos en un mapa, como en la web y la app de Windows: un punto por equipo con sitio; su «Abrir» lo abre.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: AppViewModel) {
    val back = { vm.show(Screen.Account) }
    BackHandler(onBack = back)
    var page by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val all = vm.devices.values.toList()
    val placed = all.filter { it.mapPlace != null }.sortedBy { it.name.lowercase() }
    val labels = mapOf("online" to stringResource(R.string.map_online), "offline" to stringResource(R.string.map_offline),
        "open" to stringResource(R.string.map_open), "none" to stringResource(R.string.map_none))
    val kinds = mapOf("1" to stringResource(R.string.map_kind_ip), "2" to stringResource(R.string.map_kind_wifi),
        "3" to stringResource(R.string.map_kind_gps), "4" to stringResource(R.string.map_kind_user))
    LaunchedEffect(placed, loaded) {
        val view = page ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val json = buildJsonObject {
            put("devices", JsonArray(placed.map { d ->
                buildJsonObject {
                    put("id", d.id); put("name", d.name); put("lat", d.mapPlace!!.latitude); put("lon", d.mapPlace!!.longitude)
                    put("online", d.isOnline); put("kind", d.mapPlace!!.kind.code)
                }
            }))
            putJsonObject("labels") { labels.forEach { (k, v) -> put(k, v) }; putJsonObject("kinds") { kinds.forEach { (k, v) -> put(k, v) } } }
        }
        view.evaluateJavascript("setDevices($json)", null)
    }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = BarHeight, title = { Text(stringResource(R.string.map_title)) },
            navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } })
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                val loader = WebViewAssetLoader.Builder().setDomain(MAP_HOST).addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClientCompat() {
                        // Our files from the app; the map tiles from OpenStreetMap. / Nuestros ficheros de la app; los mapas de OpenStreetMap.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                        // The page never goes anywhere else. / La página nunca va a otro sitio.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                        override fun onPageFinished(view: WebView, url: String) { loaded = true }
                        // Its process died (out of memory, or killed): back, instead of closing the app. / Murió su proceso: atrás en vez de cerrar la app.
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            page = null; loaded = false
                            back()
                            return true
                        }
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(this, "app", setOf("https://$MAP_HOST")) { _, message, _, _, _ ->
                            message.data?.let { id -> post { vm.show(Screen.DeviceDetail(id)) } }
                        }
                    }
                    page = this
                    loadUrl("https://$MAP_HOST/assets/map/map.html")
                }
            })
            Text(stringResource(R.string.map_count, placed.size, all.size), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
