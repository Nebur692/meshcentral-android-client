package io.github.nebur692.meshclient.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.TerminalModel
import io.github.nebur692.meshclient.TerminalState
import io.github.nebur692.meshclient.core.TunnelProtocol
import kotlinx.coroutines.launch

private const val PAGE_HOST = "appassets.androidplatform.net"

/** The name of each kind of terminal. / El nombre de cada tipo de terminal. */
fun terminalName(kind: TunnelProtocol, windows: Boolean): Int = when (kind) {
    TunnelProtocol.PowerShell, TunnelProtocol.UserPowerShell -> R.string.term_powershell
    TunnelProtocol.UserShell -> R.string.term_user_shell
    else -> if (windows) R.string.term_cmd else R.string.term_shell
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: AppViewModel) {
    val t = vm.terminal ?: run { LaunchedEffect(Unit) { vm.leaveTool() }; return }
    val context = LocalContext.current
    val res = LocalResources.current
    val snack = remember { SnackbarHostState() }
    // A page whose process died is made again; it gets everything printed so far. / Una página cuyo proceso murió se hace de nuevo.
    var page by remember { mutableIntStateOf(0) }
    // Several lines are asked first: a shell without bracketed paste runs each one as it arrives (seen on Ubuntu 22.04).
    // Varias líneas se preguntan antes: una shell sin pegado entre corchetes ejecuta cada una al llegar (visto en Ubuntu 22.04).
    var pasteAsk by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    BackHandler { vm.leaveTool() }

    // Why it ended, in the person's words, written on the page too. / Por qué acabó, en palabras, también en la página.
    val closedText = stringResource(R.string.term_closed)
    DisposableEffect(t) {
        t.closedOnPage = { c -> t.postText("closed", closedText + (c.error?.let { " · " + it.key } ?: "")) }
        onDispose { t.closedOnPage = null }
    }
    LaunchedEffect(dark) { t.setDark(dark) }
    // The page talks to the app through web messages (WebView 83 or newer). An older one (an Android 10 without updates came with
    // 74) would wait for ever: it is said, with the way to update it. / La página habla con la app por mensajes web (WebView 83 o
    // más nuevo). Uno más viejo (un Android 10 sin actualizar traía el 74) esperaría para siempre: se dice, con cómo actualizarlo.
    val webViewReady = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(t.title ?: t.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(terminalName(t.kind, t.device.isWindows)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    navigationIcon = { IconButton(onClick = vm::leaveTool) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                    actions = {
                        if (webViewReady) IconButton(onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (text.isNullOrEmpty()) scope.launch { snack.showSnackbar(res.getString(R.string.term_paste_empty)) }
                            else if (text.contains('\n') || text.contains('\r')) pasteAsk = text
                            else { t.paste(text); t.focus() }
                        }, enabled = t.state == TerminalState.Open) { Icon(painterResource(R.drawable.ic_paste), stringResource(R.string.term_paste)) }
                        if (webViewReady) IconButton(onClick = t::requestText) { Icon(painterResource(R.drawable.ic_select), stringResource(R.string.term_select)) }
                    },
                )
                if (webViewReady) when (val s = t.state) {
                    TerminalState.Connecting -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Notice(stringResource(R.string.term_connecting)) }
                    is TerminalState.Closed -> ClosedBar(s.error, onAgain = { vm.openTerminal(t.device.id, t.kind) })
                    TerminalState.Open -> if (t.recorded) Notice(stringResource(R.string.term_recorded)) else HorizontalDivider()
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // The page and the key bar sit above the keyboard. / La página y la barra de teclas quedan encima del teclado.
        Column(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            if (webViewReady) {
                key(page) { TerminalPage(t, dark, Modifier.weight(1f).fillMaxWidth(), onGone = { page++ }) }
                KeyBar(t)
            } else OldWebView(Modifier.weight(1f).fillMaxWidth())
        }
    }

    pasteAsk?.let { text ->
        val lines = text.trimEnd('\r', '\n').lines().size
        AlertDialog(
            onDismissRequest = { pasteAsk = null },
            title = { Text(pluralStringResource(R.plurals.term_paste_lines, lines, lines)) },
            text = {
                Column {
                    Text(stringResource(R.string.term_paste_lines_text))
                    Surface(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 200.dp), tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                        Text(text.trimEnd(), Modifier.verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pasteAsk = null; t.paste(text); t.focus() }) { Text(stringResource(R.string.term_paste)) } },
            dismissButton = { TextButton(onClick = { pasteAsk = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    t.selection?.let { text ->
        ModalBottomSheet(onDismissRequest = { t.selection = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text(stringResource(R.string.term_select), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.term_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(Modifier.padding(vertical = 8.dp).fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp), tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium) {
                    SelectionContainer(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { t.selection = null }) { Text(stringResource(R.string.close)) }
                    Button(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text))
                        t.selection = null
                        scope.launch { snack.showSnackbar(res.getQuantityString(R.plurals.term_copied, text.length, text.length)) }
                    }) { Text(stringResource(R.string.term_copy_all)) }
                }
            }
        }
    }
}

/** The WebView is too old for the terminal: what to do, and a button to its page on Google Play. / El WebView es demasiado viejo. */
@Composable
private fun OldWebView(modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.term_webview_old), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)?.packageName ?: "com.google.android.webview"
            val market = android.content.Intent(android.content.Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
            runCatching { context.startActivity(market) }.onFailure {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())) }
            }
        }, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.term_webview_update)) }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ClosedBar(error: io.github.nebur692.meshclient.UiError?, onAgain: () -> Unit) {
    Surface(color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // A cut tunnel is the device or the relay closing this terminal, not the server connection (that one has its own banner).
            // Un túnel cortado es el equipo o el relé cerrando esta terminal, no la conexión con el servidor (esa tiene su propio aviso).
            val text = when {
                error == null -> stringResource(R.string.term_closed)
                error.key.startsWith("tunel/ver-pantalla/") -> stringResource(R.string.term_closed_by_device)
                else -> errorText(error)
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            FilledTonalButton(onClick = onAgain) { Text(stringResource(R.string.term_again)) }
        }
    }
}

/**
 * The keys a phone keyboard lacks. Ctrl and Alt stay pressed for the next key (lit while so), as on Termux and ConnectBot.
 * Las teclas que no tiene un teclado de móvil. Ctrl y Alt se quedan pulsadas para la siguiente tecla (encendidas mientras).
 */
@Composable
private fun KeyBar(t: TerminalModel) {
    val enabled = t.state == TerminalState.Open
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            // Narrow keys (a button is at least 58 dp): more of them in sight, still 48 dp tall to tap.
            // Teclas estrechas (un botón mide al menos 58 dp): caben más a la vista, con 48 dp de alto para tocarlas.
            @Composable fun cap(label: String, description: String?, onClick: () -> Unit) = Box(
                Modifier.heightIn(min = 48.dp).widthIn(min = 44.dp).clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontFamily = FontFamily.Monospace, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            @Composable fun key(label: String, name: String, description: String? = null) = cap(label, description) { t.key(name) }
            @Composable fun char(label: String) = cap(label, null) { t.typeFromBar(label) }
            key("Esc", "Escape")
            key("Tab", "Tab")
            FilterChip(t.ctrl, { t.ctrl = !t.ctrl }, { Text("Ctrl") }, enabled = enabled)
            FilterChip(t.alt, { t.alt = !t.alt }, { Text("Alt") }, enabled = enabled)
            key("←", "ArrowLeft", stringResource(R.string.key_left))
            key("↑", "ArrowUp", stringResource(R.string.key_up))
            key("↓", "ArrowDown", stringResource(R.string.key_down))
            key("→", "ArrowRight", stringResource(R.string.key_right))
            key("Home", "Home"); key("End", "End"); key("PgUp", "PageUp"); key("PgDn", "PageDown")
            for (c in listOf("|", "~", "/", "-", "*", "&", ">", "<", "$")) char(c)
        }
    }
}

/**
 * A WebView whose keyboard types letter by letter. A phone keyboard composes words and sends each change of the word,
 * and xterm.js passed every one on ("hola" arrived as "hoho hoo ho hoh oola", seen on Android 17): asked as a visible
 * password without suggestions, the keyboard does not compose, nor learns what is typed in a shell.
 * Un WebView cuyo teclado escribe letra a letra. Un teclado de móvil compone palabras y manda cada cambio, y xterm.js
 * los pasaba todos («hola» llegaba como «hoho hoo ho hoh oola», visto en Android 17): pedido como contraseña visible y
 * sin sugerencias, el teclado no compone ni aprende lo que se escribe en una shell.
 */
private class TerminalWebView(context: Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }
}

/** xterm.js in a WebView, served from the app's own files under a fixed https origin. / xterm.js en un WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalPage(t: TerminalModel, dark: Boolean, modifier: Modifier, onGone: () -> Unit) {
    AndroidView(modifier = modifier, factory = { ctx ->
        val loader = WebViewAssetLoader.Builder().setDomain(PAGE_HOST).addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
        // Only a debug build can be inspected from a computer. / Solo una compilación de depuración se puede inspeccionar desde un ordenador.
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        TerminalWebView(ctx).apply {
            // AndroidView gives it WRAP_CONTENT, and a WebView takes its page's height then: 0 here (everything is absolute),
            // so xterm.js had one row and nowhere to tap (measured with DevTools). / AndroidView le da WRAP_CONTENT y un
            // WebView toma entonces la altura de su página: aquí 0, así que xterm.js tenía una fila y nada que tocar.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                // The page never goes anywhere else. / La página nunca va a otro sitio.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                // Its process died (out of memory, or killed): a new page instead of closing the app. / Murió su proceso: otra página en vez de cerrar la app.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    t.toPage = null
                    onGone()
                    return true
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "app", setOf("https://$PAGE_HOST")) { _, message, origin, isMainFrame, reply ->
                    if (!isMainFrame || origin.host != PAGE_HOST) return@addWebMessageListener
                    t.toPage = { reply.postMessage(it) }
                    message.data?.let(t::fromPage)
                }
            }
            val url = Uri.Builder().scheme("https").authority(PAGE_HOST).path("/assets/terminal/terminal.html")
                .appendQueryParameter("dark", if (dark) "1" else "0").appendQueryParameter("font", t.fontSize.toString()).build()
            loadUrl(url.toString())
        }
    }, onRelease = { view ->
        t.toPage = null
        view.destroy()
    })
}
