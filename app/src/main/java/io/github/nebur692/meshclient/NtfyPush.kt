package io.github.nebur692.meshclient

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.nebur692.meshclient.core.codeIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Codes by messaging through ntfy and UnifiedPush. Each saved server is a UnifiedPush instance; with the ntfy distributor
 * its endpoint is an ntfy topic, which the app sets as the account's messaging handle ("ntfy:<topic>"), so MeshCentral's
 * check and sign-in codes come to the app itself (its messaging module POSTs the text to the topic). No Google keys and no
 * change on the server: it only needs ntfy among its messaging services, on the same ntfy server as the distributor.
 * Códigos por mensajería con ntfy y UnifiedPush. Cada servidor guardado es una instancia; con el distribuidor ntfy su
 * dirección es un tema de ntfy, que la app pone como canal de la cuenta, así que los códigos de MeshCentral llegan a la
 * propia app. Sin claves de Google ni cambios en el servidor: solo necesita ntfy en su mensajería.
 */
object NtfyPush {
    /** A code that arrived for [instance] (the server's address). / Un código que llegó para esa instancia. */
    data class Code(val instance: String, val code: String)

    private val endpoints = MutableStateFlow<Map<String, String>>(emptyMap())
    private val codes = MutableSharedFlow<Code>(extraBufferCapacity = 8)
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Each instance's endpoint, as the distributor gave it. / La dirección de cada instancia. */
    val endpointsNow: StateFlow<Map<String, String>> = endpoints.asStateFlow()
    val codesNow: SharedFlow<Code> = codes.asSharedFlow()
    val failuresNow: SharedFlow<String> = failures.asSharedFlow()

    /** Whether the app is in front (then the code goes to the screen, not to a notification). / Si la app está delante. */
    @Volatile var inFront = false

    /** The last code, for a while, so a tap on its notification fills it even after the screen stopped waiting.
     *  El último código, un rato, para que tocar su aviso lo ponga aunque la pantalla ya no lo esperara. */
    private data class Kept(val code: Code, val atMs: Long, val used: Boolean)
    @Volatile private var kept: Kept? = null
    /** How long a kept code is offered (MeshCentral's messaging codes last 5 minutes). / Cuánto se ofrece. */
    const val KEEP_MS = 4 * 60_000L

    @Synchronized internal fun keep(code: Code, atMs: Long = System.currentTimeMillis()) { kept = Kept(code, atMs, used = false) }

    /** The kept code for [instance], once: null if none, too old or already used. / El código guardado, una sola vez. */
    @Synchronized fun take(instance: String, nowMs: Long = System.currentTimeMillis()): String? {
        val k = kept ?: return null
        if (k.used || k.code.instance != instance || nowMs - k.atMs > KEEP_MS) return null
        kept = k.copy(used = true)
        return k.code.code
    }

    /** The waiting screen got [code] by itself: it is not offered again. / La pantalla ya lo recibió: no se ofrece otra vez. */
    @Synchronized fun used(code: String) { kept?.takeIf { it.code.code == code }?.let { kept = it.copy(used = true) } }

    internal const val EXTRA_CODE = "io.github.nebur692.meshclient.CODE"

    private const val PREFS = "ntfy"
    private const val CHANNEL = "codes"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        endpoints.value = p.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }

    /** The ntfy topic of an endpoint (its last path part), or null if it is not one. / El tema de ntfy de una dirección. */
    fun topicOf(endpoint: String): String? = runCatching { java.net.URI(endpoint) }.getOrNull()?.path?.trim('/')
        ?.takeIf { it.isNotEmpty() && !it.contains('/') && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }

    /** The ntfy server of an endpoint, to tell the person which one must be the MeshCentral server's. / Su servidor ntfy. */
    fun hostOf(endpoint: String): String? = runCatching { java.net.URI(endpoint).host }.getOrNull()

    private const val TAG = "MeshClientPush"

    /** The catalog key when the check code does not arrive: the distributor is not listening. / ntfy no escucha. */
    const val NOT_LISTENING = "usuarios/cambiar-ajustes/dependencia-caida"
    const val NTFY_PACKAGE = "io.heckel.ntfy"

    internal fun newEndpoint(context: Context, instance: String, url: String) {
        android.util.Log.i(TAG, "endpoint for $instance")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(instance, url) }
        endpoints.value = endpoints.value + (instance to url)
    }

    internal fun gone(context: Context, instance: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(instance) }
        endpoints.value = endpoints.value - instance
    }

    internal fun failed(instance: String) { failures.tryEmit(instance) }

    /**
     * A message from the server: its code goes to the screen when the app is in front, otherwise to a notification for
     * signing in somewhere else. / Un mensaje del servidor: su código va a la pantalla si la app está delante; si no, a un aviso.
     */
    internal fun message(context: Context, instance: String, text: String) {
        val code = codeIn(text)
        android.util.Log.i(TAG, "message for $instance, code " + (if (code == null) "none" else "found") + ", collectors " + codes.subscriptionCount.value)
        if (code == null) return
        keep(Code(instance, code))
        codes.tryEmit(Code(instance, code))
        if (inFront) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ntfy_channel), NotificationManager.IMPORTANCE_HIGH))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val server = instance.removePrefix("https://").trimEnd('/')
        NotificationManagerCompat.from(context).notify(instance.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(context.getString(R.string.ntfy_code_title, server))
            .setContentText(code.chunked(3).joinToString(" "))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(KEEP_MS)
            // A tap opens the app, which fills the code if it is waiting for it; "Copy" is for signing in elsewhere.
            // Tocar abre la app, que pone el código si lo espera; «Copiar» es para entrar en otro sitio.
            .setContentIntent(android.app.PendingIntent.getActivity(context, 0,
                android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.ic_copy, context.getString(R.string.ntfy_copy), android.app.PendingIntent.getBroadcast(context, instance.hashCode(),
                android.content.Intent(context, CodeCopyReceiver::class.java).putExtra(EXTRA_CODE, code).putExtra(android.content.Intent.EXTRA_UID, instance.hashCode()),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build())
    }
}

/** "Copy" on a code's notification: the code to the clipboard, and the notification goes. / «Copiar» en el aviso de un código. */
class CodeCopyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val code = intent.getStringExtra(NtfyPush.EXTRA_CODE) ?: return
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(android.content.Intent.EXTRA_UID, 0))
    }
}

/** UnifiedPush's events for this app, handed to [NtfyPush]. / Los avisos de UnifiedPush para la app. */
class PushServiceImpl : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = NtfyPush.newEndpoint(this, instance, endpoint.url)
    override fun onMessage(message: PushMessage, instance: String) = NtfyPush.message(this, instance, String(message.content))
    override fun onRegistrationFailed(reason: FailedReason, instance: String) = NtfyPush.failed(instance)
    override fun onUnregistered(instance: String) = NtfyPush.gone(this, instance)
}
