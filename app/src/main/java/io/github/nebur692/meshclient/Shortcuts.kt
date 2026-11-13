package io.github.nebur692.meshclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.nebur692.meshclient.core.Device

/** What a shortcut opens on its device. / Lo que abre un acceso directo en su equipo. */
enum class LinkTool { Device, Desktop, Terminal, PowerShell, Files }

/**
 * A device of a saved server and what to open on it. It carries no secret: signing in is the app's usual way (fingerprint,
 * remembered password, the provider or the form). / Un equipo de un servidor guardado y lo que abrir. No lleva ningún secreto:
 * se entra como siempre (huella, contraseña recordada, el proveedor o el formulario).
 */
data class DeviceLink(val server: String, val deviceId: String, val tool: LinkTool)

/**
 * The launcher's shortcuts: the last devices opened (a long press on the app's icon) and the ones the person puts on the
 * home screen. Everything stays on the phone; forgetting a server takes its shortcuts away.
 * Los accesos directos del lanzador: los últimos equipos abiertos (pulsación larga en el icono de la app) y los que la
 * persona pone en la pantalla de inicio. Todo se queda en el móvil; olvidar un servidor quita sus accesos.
 */
object Shortcuts {
    private const val ACTION = "io.github.nebur692.meshclient.OPEN_DEVICE"
    private const val EXTRA_SERVER = "server"
    private const val EXTRA_DEVICE = "device"
    private const val EXTRA_TOOL = "tool"

    private fun recentId(server: String, deviceId: String) = "r|$server|$deviceId"
    private fun pinnedId(link: DeviceLink) = "p|${link.tool.name}|${link.server}|${link.deviceId}"

    private fun intent(context: Context, link: DeviceLink, to: Class<*> = ShortcutActivity::class.java) = Intent(context, to).setAction(ACTION)
        .putExtra(EXTRA_SERVER, link.server).putExtra(EXTRA_DEVICE, link.deviceId).putExtra(EXTRA_TOOL, link.tool.name)

    /** The app's window with only the link, never the rest of what came. / La ventana de la app solo con el enlace. */
    fun forward(context: Context, link: DeviceLink): Intent = intent(context, link, MainActivity::class.java)

    /** The link an intent carries, or null if it is not one of ours. / El enlace que lleva un intent, o null si no es nuestro. */
    fun parse(intent: Intent?): DeviceLink? {
        if (intent?.action != ACTION) return null
        val server = intent.getStringExtra(EXTRA_SERVER)?.takeIf { it.isNotBlank() } ?: return null
        val device = intent.getStringExtra(EXTRA_DEVICE)?.takeIf { it.isNotBlank() } ?: return null
        val tool = LinkTool.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_TOOL) } ?: LinkTool.Device
        return DeviceLink(server, device, tool)
    }

    /** Puts a device first among the recent ones; the launcher keeps the few it shows. / Pone un equipo el primero de los recientes. */
    fun recent(context: Context, server: String, serverName: String, d: Device) {
        val link = DeviceLink(server, d.id, LinkTool.Device)
        val info = ShortcutInfoCompat.Builder(context, recentId(server, d.id))
            .setShortLabel(d.name.ifBlank { serverName })
            .setLongLabel(listOf(d.name, serverName).filter { it.isNotBlank() }.joinToString(" · "))
            .setIcon(icon(context, io.github.nebur692.meshclient.ui.kindIcon(d)))
            .setIntent(intent(context, link))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }
    }

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to put the shortcut on the home screen; it asks the person itself. / El lanzador se lo pregunta a la persona. */
    fun pin(context: Context, link: DeviceLink, d: Device, toolName: String): Boolean {
        val id = pinnedId(link)
        val label = d.name.ifBlank { toolName }
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel("$toolName · $label")
            .setIcon(icon(context, toolIcon(link.tool, d)))
            .setIntent(intent(context, link))
            .build()
        // Pinned again after its server was forgotten and saved again. / Puesto otra vez tras olvidar y guardar su servidor.
        runCatching { ShortcutManagerCompat.enableShortcuts(context, listOf(info)) }
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
    }

    /** A forgotten server: its recent devices go, its pinned ones stay greyed with [why]. / Un servidor olvidado. */
    fun forgetServer(context: Context, server: String, why: String) = forget(context, why) { it.contains("|$server|") }

    /** A removed device. / Un equipo borrado. */
    fun forgetDevice(context: Context, server: String, deviceId: String, why: String) = forget(context, why) { it.endsWith("|$server|$deviceId") }

    private fun forget(context: Context, why: String, match: (String) -> Boolean) {
        runCatching {
            val ids = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }.filter(match).distinct()
            if (ids.isEmpty()) return
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids.filter { it.startsWith("r|") })
            ShortcutManagerCompat.disableShortcuts(context, ids.filter { it.startsWith("p|") }, why)
        }
    }

    private fun toolIcon(tool: LinkTool, d: Device) = when (tool) {
        LinkTool.Device -> io.github.nebur692.meshclient.ui.kindIcon(d)
        LinkTool.Desktop -> R.drawable.ic_monitor
        LinkTool.Terminal, LinkTool.PowerShell -> R.drawable.ic_terminal
        LinkTool.Files -> R.drawable.ic_folder
    }

    /**
     * An adaptive icon: the app's blue to teal on the whole square and the glyph in white inside the part every launcher
     * shows. / Un icono adaptable: el azul a turquesa de la app en todo el cuadro y el dibujo en blanco en la parte que
     * enseñan todos los lanzadores.
     */
    private fun icon(context: Context, glyph: Int): IconCompat {
        val px = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bmp = createBitmap(px, px)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, px.toFloat(), 0f, 0xFF0259CF.toInt(), 0xFF01BEC0.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, px.toFloat(), px.toFloat(), paint)
        ContextCompat.getDrawable(context, glyph)?.mutate()?.let { g ->
            g.setTint(0xFFFFFFFF.toInt())
            val size = px * 44 / 108
            val o = (px - size) / 2
            g.setBounds(o, o, o + size, o + size)
            g.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp.copy(Bitmap.Config.ARGB_8888, false))
    }
}

/**
 * Where a shortcut lands: it hands the link to the app's window and goes. The launcher starts a shortcut clearing its task;
 * landing here, in a task of its own, the app's window keeps its open session instead of starting over.
 * Donde cae un acceso directo: pasa el enlace a la ventana de la app y se va. El lanzador abre un acceso limpiando su tarea;
 * al caer aquí, en una tarea propia, la ventana de la app conserva su sesión abierta en vez de empezar de cero.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shortcuts.parse(intent)?.let { link ->
            startActivity(Shortcuts.forward(this, link)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
