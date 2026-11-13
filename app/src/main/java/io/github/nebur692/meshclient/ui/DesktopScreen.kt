package io.github.nebur692.meshclient.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.DesktopModel
import io.github.nebur692.meshclient.DesktopQuality
import io.github.nebur692.meshclient.DesktopState
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.DesktopEvent
import io.github.nebur692.meshclient.core.MouseButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(vm: AppViewModel) {
    val m = vm.desktop ?: run { LaunchedEffect(Unit) { vm.leaveTool() }; return }
    BackHandler { vm.leaveTool() }
    var keyboard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    PhoneClipboard(m)
    Scaffold(
        topBar = {
            Column {
                TopAppBar(expandedHeight = BarHeight,
                    title = {
                        Column {
                            Text(m.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (m.touchpad) R.string.desk_touchpad else R.string.desk_direct), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    navigationIcon = { IconButton(onClick = vm::leaveTool) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                    actions = {
                        val open = m.state == DesktopState.Open
                        IconButton(onClick = { keyboard = !keyboard }, enabled = open) { Icon(painterResource(R.drawable.ic_keyboard), stringResource(R.string.desk_keyboard)) }
                        IconButton(onClick = { m.touchpad = !m.touchpad }, enabled = open) {
                            Icon(painterResource(if (m.touchpad) R.drawable.ic_pointer else R.drawable.ic_touchpad),
                                stringResource(if (m.touchpad) R.string.desk_direct else R.string.desk_touchpad))
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = open) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_options)) }
                            DesktopMenu(m, menu) { menu = false }
                        }
                    },
                )
                when (val s = m.state) {
                    DesktopState.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is DesktopState.Closed -> DeskBar(
                        when {
                            s.error == null -> stringResource(R.string.desk_closed)
                            s.error.key.startsWith("tunel/ver-pantalla/") -> stringResource(R.string.desk_closed_by_device)
                            else -> errorText(s.error)
                        }, s.error != null, stringResource(R.string.term_again)) { vm.openDesktop(m.device.id) }
                    DesktopState.Open -> when {
                        m.message != null && m.bitmap == null -> DeskBar(m.message!!, false, null) {}
                        m.noImage -> DeskBar(stringResource(R.string.desk_no_image), false, stringResource(R.string.desk_refresh)) { m.refresh() }
                        m.clipNotice != null -> m.clipNotice!!.let { n ->
                            // A moment, then gone. / Un momento y se va.
                            LaunchedEffect(n) { kotlinx.coroutines.delay(4000); if (m.clipNotice === n) m.clipNotice = null }
                            DeskBar(n.error?.let { stringResource(n.text) + " " + errorText(it) } ?: stringResource(n.text), n.error != null, null) {}
                        }
                        else -> HorizontalDivider()
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                RemoteScreen(m, Modifier.fillMaxSize())
                if (keyboard) KeyCatcher(m, Modifier.size(1.dp)) { keyboard = false }
            }
            if (keyboard) DeskKeyBar(m)
        }
    }
}

@Composable
private fun DeskBar(text: String, error: Boolean, action: String?, onAction: () -> Unit) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
            if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * The phone's side of the automatic clipboard. What the person copies while the desktop is on screen goes to the device
 * (Android tells only the focused app); coming back from another app, the clipboard is read only when it changed since
 * last seen (its timestamp), so Android's "pasted from your clipboard" note does not show every time. A clip already old
 * when the desktop opened is not sent. What the device copies is put on the phone's clipboard.
 * El lado del móvil del portapapeles automático. Lo que copia la persona con el escritorio a la vista va al equipo
 * (Android solo avisa a la app con el foco); al volver de otra app, el portapapeles se lee solo si cambió desde la última
 * vez (por su hora), para que el aviso de Android «ha pegado de tu portapapeles» no salga cada vez. Uno que ya era viejo al
 * abrir el escritorio no se manda. Lo que copia el equipo se pone en el portapapeles del móvil.
 */
@Composable
private fun PhoneClipboard(m: DesktopModel) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) } ?: return
    val opened = remember(m) { System.currentTimeMillis() }
    val seen = remember(m) { longArrayOf(0L) }
    val check = remember(m) {
        {
            val desc = clipboard.primaryClipDescription
            val stamp = desc?.timestamp ?: 0L
            if (desc != null && stamp != seen[0] && desc.hasMimeType("text/*")) {
                val first = seen[0] == 0L
                seen[0] = stamp
                if (!first || opened - stamp < FRESH_CLIP_MS) {
                    m.phoneCopied(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
                }
            }
        }
    }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(m) {
        resumed++
        onPauseOrDispose { }
    }
    // The window has the focus a moment after resuming; before, Android gives an empty clipboard. / La ventana tiene el foco un momento después.
    LaunchedEffect(resumed, m.autoClipboard, m.state) { if (m.autoClipboard && m.state == DesktopState.Open) { kotlinx.coroutines.delay(300); check() } }
    DisposableEffect(m) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener { check() }
        clipboard.addPrimaryClipChangedListener(listener)
        m.onDeviceCopied = { text ->
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(m.device.name, text))
            seen[0] = clipboard.primaryClipDescription?.timestamp ?: seen[0]
            // Android 13 and newer say it themselves. / Android 13 y posteriores lo dicen ellos.
            if (android.os.Build.VERSION.SDK_INT < 33) m.clipNotice = io.github.nebur692.meshclient.Notice(R.string.desk_clip_got)
        }
        onDispose {
            clipboard.removePrimaryClipChangedListener(listener)
            m.onDeviceCopied = null
        }
    }
}

/** A clip copied this long before the desktop opened was meant for it. / Un recorte copiado hasta esto antes de abrir era para él. */
private const val FRESH_CLIP_MS = 120_000L

@Composable
private fun DesktopMenu(m: DesktopModel, open: Boolean, dismiss: () -> Unit) {
    DropdownMenu(open, dismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.desk_cad)) }, onClick = { m.ctrlAltDel(); dismiss() })
        DropdownMenuItem(text = { Text(stringResource(R.string.desk_refresh)) }, onClick = { m.refresh(); dismiss() })
        DropdownMenuItem(text = { Text(stringResource(R.string.desk_clip_auto)) },
            leadingIcon = { androidx.compose.material3.Checkbox(m.autoClipboard, null) }, onClick = { m.changeAutoClipboard(!m.autoClipboard); dismiss() })
        val context = LocalContext.current
        val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
        DropdownMenuItem(text = { Text(stringResource(R.string.desk_clip_to)) }, onClick = {
            m.pasteToDevice(clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
            dismiss()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.desk_clip_from)) }, onClick = {
            m.copyFromDevice { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(m.device.name, it)) }
            dismiss()
        })
        HorizontalDivider()
        Text(stringResource(R.string.desk_quality), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DesktopQuality.entries.forEach { q ->
            DropdownMenuItem(text = { Text(stringResource(q.label)) }, leadingIcon = { RadioButton(m.quality == q, null) }, onClick = { m.changeQuality(q); dismiss() })
        }
        val d = m.displays
        if (d != null && d.displays.size > 1) {
            HorizontalDivider()
            val choices = d.displays + DesktopEvent.Displays.ALL
            choices.forEach { id ->
                DropdownMenuItem(
                    text = { Text(if (id == DesktopEvent.Displays.ALL) stringResource(R.string.desk_all_displays) else stringResource(R.string.desk_display, id)) },
                    leadingIcon = { RadioButton(d.selected == id, null) }, onClick = { m.selectDisplay(id); dismiss() })
            }
        }
    }
}

/**
 * The remote screen, fitted at first. Direct: a tap clicks where it is, a long press right-clicks, a drag drags, two
 * fingers zoom and move the picture. Touchpad: the finger moves a pointer, a tap clicks there, a two-finger tap
 * right-clicks and a two-finger drag scrolls. / La pantalla remota, ajustada al principio. Directo: un toque hace clic
 * donde toca, mantener es clic derecho, arrastrar arrastra, dos dedos amplían y mueven. Touchpad: el dedo mueve un
 * puntero, un toque hace clic ahí, un toque con dos dedos es clic derecho y arrastrar con dos desplaza.
 *
 * A mouse (Bluetooth, USB, DeX) works as on the device in either mode: it points where it is, its three buttons press and
 * release, and its wheel scrolls; the phone's pointer takes the device's shape (hand, text). / Un ratón funciona como en el
 * equipo en los dos modos: apunta donde está, sus tres botones pulsan y sueltan, y su rueda desplaza; el puntero del móvil
 * toma la forma del del equipo (mano, texto).
 */
@Composable
private fun RemoteScreen(m: DesktopModel, modifier: Modifier) {
    var view by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val bmp = m.bitmap
    val fit = if (bmp == null || view.width == 0) 1f else min(view.width / bmp.width.toFloat(), view.height / bmp.height.toFloat())
    val scale = fit * zoom
    // Where the picture's corner is, so the picture stays centred while it is smaller than the view.
    // Dónde está la esquina de la imagen, para que siga centrada mientras sea más pequeña que la vista.
    fun origin(): Offset {
        val b = bmp ?: return Offset.Zero
        val w = b.width * scale; val h = b.height * scale
        val x = if (w <= view.width) (view.width - w) / 2 else pan.x.coerceIn(view.width - w, 0f)
        val y = if (h <= view.height) (view.height - h) / 2 else pan.y.coerceIn(view.height - h, 0f)
        return Offset(x, y)
    }
    val toRemote = rememberUpdatedState { p: Offset -> val o = origin(); Offset((p.x - o.x) / scale, (p.y - o.y) / scale) }
    val originNow = rememberUpdatedState { origin() }
    val current = rememberUpdatedState(m)
    val scaleNow = rememberUpdatedState(scale)
    val icon = when (m.cursor) { 2 -> PointerIcon.Crosshair; 3 -> PointerIcon.Hand; 5 -> PointerIcon.Text; else -> PointerIcon.Default }
    Canvas(modifier.onSizeChanged { view = it }.semantics { contentDescription = m.device.name }.pointerHoverIcon(icon).pointerInput(m) {
        // Seen first, before the touch gestures: a mouse is never taken for a finger. / Antes que los gestos: un ratón nunca es un dedo.
        awaitPointerEventScope {
            var buttons = 0
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                val model = current.value
                val r = toRemote.value(c.position)
                if (e.type == PointerEventType.Scroll) {
                    // Android: down is positive; Windows: up is, 120 per notch. / Android: abajo es positivo; Windows: arriba, 120 por paso.
                    val notches = c.scrollDelta.y
                    if (notches != 0f) model.wheel(r.x, r.y, -(notches * 120).roundToInt())
                } else {
                    val now = (if (e.buttons.isPrimaryPressed) 1 else 0) or (if (e.buttons.isSecondaryPressed) 2 else 0) or (if (e.buttons.isTertiaryPressed) 4 else 0)
                    if (now != buttons) {
                        for ((bit, b) in MOUSE_BUTTONS) if ((now xor buttons) and bit != 0) model.mouseButton(r.x, r.y, b, now and bit != 0)
                        buttons = now
                    } else model.mouseMove(r.x, r.y)
                }
                // Taken here: Android would turn a right click nobody took into Back. / Tomado aquí: Android haría Atrás de un clic derecho.
                e.changes.forEach { it.consume() }
            }
        }
    }.pointerInput(m) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            // The mouse has its own handling above. / El ratón se atiende arriba.
            if (first.type == PointerType.Mouse) return@awaitEachGesture
            val start = first.position
            val t0 = first.uptimeMillis
            var moved = false; var dragging = false; var two = false; var twoMoved = false; var scrolled = 0f
            val model = current.value
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Main)
                val pressed = e.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    val up = e.changes.first()
                    when {
                        two && !twoMoved -> { val r = toRemote.value(start); if (model.touchpad) model.click(model.pointer.x, model.pointer.y, MouseButton.Right) else model.click(r.x, r.y, MouseButton.Right) }
                        two -> Unit
                        dragging -> { val r = toRemote.value(up.position); model.move(r.x, r.y); model.press(r.x, r.y, false) }
                        !moved && model.touchpad -> model.click(model.pointer.x, model.pointer.y)
                        !moved && up.uptimeMillis - t0 > 500 -> { val r = toRemote.value(start); model.click(r.x, r.y, MouseButton.Right) }
                        !moved -> { val r = toRemote.value(start); model.click(r.x, r.y) }
                    }
                    break
                }
                if (pressed.size >= 2) {
                    two = true
                    val a = pressed[0]; val b = pressed[1]
                    val now = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                    val before = hypot(a.previousPosition.x - b.previousPosition.x, a.previousPosition.y - b.previousPosition.y)
                    val centre = (a.position + b.position) / 2f
                    val shift = (a.positionChange() + b.positionChange()) / 2f
                    if (abs(now - before) > 0.5f || shift.getDistance() > 0.5f) twoMoved = true
                    if (model.touchpad) {
                        // Two fingers scroll the page: 120 per notch, every 40 px of finger. / Dos dedos desplazan.
                        scrolled += shift.y
                        while (abs(scrolled) >= 40f) { model.wheel(model.pointer.x, model.pointer.y, if (scrolled > 0) 120 else -120); scrolled -= if (scrolled > 0) 40f else -40f }
                    } else {
                        val newZoom = (zoom * (if (before > 0f) now / before else 1f)).coerceIn(1f, 6f)
                        val o = originNow.value()
                        val k = newZoom / zoom
                        pan = Offset(centre.x - (centre.x - o.x) * k + shift.x, centre.y - (centre.y - o.y) * k + shift.y)
                        zoom = newZoom
                    }
                    e.changes.forEach { it.consume() }
                    continue
                }
                val c = pressed.first()
                if (!moved && (c.position - start).getDistance() > slop) moved = true
                if (moved && !two) {
                    if (model.touchpad) {
                        val d = c.positionChange()
                        model.movePointer(d.x / scaleNow.value * 1.5f, d.y / scaleNow.value * 1.5f)
                    } else {
                        val r = toRemote.value(c.position)
                        if (!dragging) { val s0 = toRemote.value(start); model.move(s0.x, s0.y); model.press(s0.x, s0.y, true); dragging = true }
                        model.move(r.x, r.y)
                    }
                    c.consume()
                }
            }
        }
    }) {
        if (m.frame < 0) return@Canvas // read here so each new piece redraws / se lee aquí para repintar con cada trozo
        val b = bmp ?: return@Canvas
        val o = origin()
        drawImage(b.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height),
            dstOffset = IntOffset(o.x.toInt(), o.y.toInt()), dstSize = IntSize((b.width * scale).toInt(), (b.height * scale).toInt()))
        if (m.touchpad) {
            val p = Offset(o.x + m.pointer.x * scale, o.y + m.pointer.y * scale)
            drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black, 9.dp.toPx(), p, style = Stroke(1.dp.toPx()))
        }
    }
}

private val MOUSE_BUTTONS = listOf(1 to MouseButton.Left, 2 to MouseButton.Right, 4 to MouseButton.Middle)

/**
 * Takes the phone keyboard's letters, backspace and Enter and sends them to the device; asked as a visible password
 * without suggestions, so the keyboard sends letter by letter instead of composing words.
 * Recoge las letras, el retroceso y el Intro del teclado del móvil y los manda al equipo; pedido como contraseña visible
 * sin sugerencias, así el teclado manda letra a letra en vez de componer palabras.
 */
// Made in code only, never from a layout file. / Solo se crea en código, nunca desde un fichero de diseño.
@SuppressLint("ViewConstructor")
private class KeyView(context: Context, val onText: (String) -> Unit, val onKey: (Int, Boolean) -> Unit) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { composing = ""; onText(text.toString()); return true }
            // A keyboard that composes anyway: what it settles on is sent once. / Un teclado que compone igualmente: se manda una vez.
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean { composing = text.toString(); return true }
            override fun finishComposingText(): Boolean { if (composing.isNotEmpty()) onText(composing); composing = ""; return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { onKey(8, false) }; return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) key(event); return true }
            override fun performEditorAction(actionCode: Int): Boolean { onKey(13, false); return true }
        }
    }

    // A hardware keyboard too. / También un teclado físico.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event) || super.onKeyDown(keyCode, event)

    private fun key(e: KeyEvent): Boolean {
        val vk = when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> 8; KeyEvent.KEYCODE_ENTER -> 13; KeyEvent.KEYCODE_TAB -> 9; KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_DPAD_LEFT -> 37; KeyEvent.KEYCODE_DPAD_UP -> 38; KeyEvent.KEYCODE_DPAD_RIGHT -> 39; KeyEvent.KEYCODE_DPAD_DOWN -> 40
            KeyEvent.KEYCODE_FORWARD_DEL -> 46
            else -> 0
        }
        if (vk != 0) { onKey(vk, vk in 37..40 || vk == 46); return true }
        val c = e.unicodeChar
        if (c != 0) { onText(String(Character.toChars(c))); return true }
        return false
    }
}

@Composable
private fun KeyCatcher(m: DesktopModel, modifier: Modifier, gone: () -> Unit) {
    var view by remember { mutableStateOf<View?>(null) }
    val host = LocalView.current
    // Hidden through the screen's own view, which is always in the window: hiding it through this one, as it went away,
    // left the keyboard up, now on the next view (seen on Android 17). / Se oculta por la vista de la pantalla, que siempre
    // está en la ventana: hacerlo por esta, al irse, dejaba el teclado arriba, ya en la siguiente vista (visto en Android 17).
    DisposableEffect(Unit) {
        onDispose {
            view?.clearFocus()
            ViewCompat.getWindowInsetsController(host)?.hide(WindowInsetsCompat.Type.ime())
            (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(host.windowToken, 0)
            gone()
        }
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        KeyView(ctx, onText = m::type, onKey = { vk, ext -> m.tap(vk, ext) }).also { v ->
            view = v
            v.post {
                v.requestFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
            }
        }
    })
}

/** The keys a phone keyboard lacks; Ctrl and Alt stay pressed for the next key. / Las teclas que le faltan al móvil. */
@Composable
private fun DeskKeyBar(m: DesktopModel) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun cap(label: String, description: String? = null, onClick: () -> Unit) = Box(
                Modifier.heightIn(min = 48.dp).widthIn(min = 44.dp).clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = onClick)
                    .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary) }
            cap("Esc") { m.tap(27) }
            cap("Tab") { m.tap(9) }
            FilterChip(m.ctrl, { m.ctrl = !m.ctrl }, { Text("Ctrl") })
            FilterChip(m.alt, { m.alt = !m.alt }, { Text("Alt") })
            cap("Win", stringResource(R.string.key_win)) { m.tap(91, true) }
            cap("←", stringResource(R.string.key_left)) { m.tap(37, true) }
            cap("↑", stringResource(R.string.key_up)) { m.tap(38, true) }
            cap("↓", stringResource(R.string.key_down)) { m.tap(40, true) }
            cap("→", stringResource(R.string.key_right)) { m.tap(39, true) }
            cap(stringResource(R.string.key_del)) { m.tap(46, true) }
            cap("Home") { m.tap(36, true) }; cap("End") { m.tap(35, true) }; cap("PgUp") { m.tap(33, true) }; cap("PgDn") { m.tap(34, true) }
            for (f in 1..12) cap("F$f") { m.tap(111 + f) }
        }
    }
}
