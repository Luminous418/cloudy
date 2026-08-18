package dev.cloudy.ota.ota

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.runBlocking

/** Arms one daily background update check (Doze-friendly, self-perpetuating). */
object UpdateAlarm {

    private const val ACTION_CHECK = "dev.cloudy.ota.action.CHECK_UPDATES"
    private const val REQUEST_CODE = 7301

    /** Interval from the Advanced settings "Check interval" pref, falling back to daily. */
    private fun intervalMillis(context: Context): Long =
        context.getSharedPreferences(UpdateChecker.PREFS_NAME, 0)
            .getString(UpdateChecker.KEY_INTERVAL, null)
            ?.toLongOrNull()
            ?: AlarmManager.INTERVAL_DAY

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, UpdateReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMillis(context),
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    /** Runs one background check + notification pass, then arms the next check. */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CHECK) return
            if (!UpdateChecker.notificationsEnabled(context)) return
            val pending = goAsync()
            Thread {
                try {
                    val result = runBlocking { UpdateChecker.check(context) }
                    UpdateNotifier.notifyIfNeeded(context, result)
                    UpdateAlarm.schedule(context)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    /** Re-arms the daily check after a reboot (alarms do not survive a reboot). */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
                UpdateChecker.notificationsEnabled(context)
            ) {
                UpdateAlarm.schedule(context)
            }
        }
    }
}