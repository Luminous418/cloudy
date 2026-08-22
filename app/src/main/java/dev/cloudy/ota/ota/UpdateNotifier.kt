package dev.cloudy.ota.ota

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.cloudy.ota.R
import dev.cloudy.ota.ui.MainActivity

/** Posts the "new update available" notifications for the app and the ROM. */
object UpdateNotifier {

    const val CHANNEL_ID = "update_notifications"
    private const val PROGRESS_CHANNEL_ID = "download_progress"
    private const val APP_NOTIF_ID = 1001
    private const val ROM_NOTIF_ID = 1002
    const val DOWNLOAD_NOTIF_ID = 1003

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                // HIGH = alert level: shows the heads-up pop-up while the screen is on
                // (DEFAULT only lands in the status bar + shade).
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        // Silent channel for the ROM download progress: it ticks every few seconds,
        // so IMPORTANCE_LOW keeps it out of the way (no sound, no heads-up).
        nm.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                context.getString(R.string.notif_progress_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /** Android 13+ requires the POST_NOTIFICATIONS runtime grant before anything shows. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Posts one notification per newer item found. No-op when nothing is new. */
    fun notifyIfNeeded(context: Context, result: UpdateChecker.Result) {
        if (!canPost(context)) return
        result.appVersion?.let { v ->
            post(
                context, APP_NOTIF_ID,
                context.getString(R.string.notif_app_title),
                context.getString(R.string.notif_app_msg, v),
                UpdateChecker.TAB_SETTINGS
            )
        }
        result.romVersion?.let { v ->
            post(
                context, ROM_NOTIF_ID,
                context.getString(R.string.notif_rom_title),
                context.getString(R.string.notif_rom_msg, v),
                UpdateChecker.TAB_UPDATE
            )
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String, tab: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, tab)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_cloud)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    /**
     * Silent progress notification for a ROM download, shown by [DownloadService]
     * (both as its startForeground notification and via per-percent notify() updates).
     * Tapping it opens the Update tab.
     */
    fun buildProgressNotification(context: Context, percent: Int, speedText: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, UpdateChecker.TAB_UPDATE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, DOWNLOAD_NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_cloud)
            .setContentTitle(context.getString(R.string.status_downloading))
            .setContentText(context.getString(R.string.download_progress_format, percent, speedText))
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }
}