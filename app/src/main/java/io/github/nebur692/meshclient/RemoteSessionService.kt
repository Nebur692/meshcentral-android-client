package io.github.nebur692.meshclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keeps the app alive while a terminal, the files or a desktop is open, so changing to another app does not cut the
 * device: Android freezes a hidden app and its connections die with it. It shows a notification with the device and a
 * "Disconnect" button, and stops as soon as the tool closes.
 * Mantiene viva la app mientras hay una terminal, los ficheros o un escritorio abiertos, para que cambiar de app no corte
 * el equipo: Android congela una app oculta y sus conexiones mueren con ella. Enseña un aviso con el equipo y un botón
 * «Desconectar», y se para en cuanto se cierra la herramienta.
 */
class RemoteSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnects.tryEmit(Unit)
            stop()
            return START_NOT_STICKY
        }
        val device = intent?.getStringExtra(EXTRA_DEVICE).orEmpty()
        val tool = intent?.getStringExtra(EXTRA_TOOL).orEmpty()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.keep_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, RemoteSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.keep_title, device))
            .setContentText(tool)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.keep_disconnect), disconnect)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        return START_NOT_STICKY
    }

    // Swiped away from the recent apps: nothing is left to keep. / Quitada de las recientes: no queda nada que mantener.
    override fun onTaskRemoved(rootIntent: Intent?) { disconnects.tryEmit(Unit); stop() }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "remote-session"
        private const val NOTIFICATION = 7
        private const val ACTION_DISCONNECT = "io.github.nebur692.meshclient.DISCONNECT"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_TOOL = "tool"

        private val disconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** "Disconnect" in the notification, or the app swiped away. / «Desconectar» en el aviso, o la app quitada. */
        val disconnectRequests: SharedFlow<Unit> = disconnects.asSharedFlow()

        /** Called with the app on screen (Android does not let it start from the background). / Con la app en pantalla. */
        fun start(context: Context, device: String, tool: String) {
            val intent = Intent(context, RemoteSessionService::class.java).putExtra(EXTRA_DEVICE, device).putExtra(EXTRA_TOOL, tool)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, RemoteSessionService::class.java)) }
    }
}
