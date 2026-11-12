package io.github.nebur692.meshclient

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.nebur692.meshclient.core.ServerNotification

/**
 * A notification the server sent this person, as an Android notification while the app is out of sight (inside it, a
 * window shows it). Without the notifications permission, the window waits for the person to come back.
 * Un aviso que el servidor mandó a esta persona, como notificación de Android mientras la app está fuera de la vista
 * (dentro, lo enseña una ventana). Sin el permiso de notificaciones, la ventana espera a que vuelva.
 */
object ServerNotices {
    private const val CHANNEL = "server-notices"
    private var next = 100

    fun canPost(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, server: String, n: ServerNotification) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val title = n.title.ifBlank { server }
        manager.notify(next++, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.notice_from, title))
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build())
    }
}
