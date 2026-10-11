package dev.cloudy.ota.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Dev/testing hook: lets `adb shell` point Cloudy at a locally served manifest without
 * unlocking or typing anything on the device - handy to test an OTA/ROM build end to end.
 *
 *   # both tabs -> one manifest
 *   adb shell am broadcast -n dev.cloudy.ota/.ota.DevConfigReceiver \
 *       --es url "http://127.0.0.1:8000/a32.json"
 *
 *   # or one per tab
 *   adb shell am broadcast -n dev.cloudy.ota/.ota.DevConfigReceiver \
 *       --es ota_url "http://127.0.0.1:8000/a32.json" \
 *       --es rom_url "http://127.0.0.1:8000/a32.json"
 *
 *   # back to the built-in defaults
 *   adb shell am broadcast -n dev.cloudy.ota/.ota.DevConfigReceiver --ez clear true
 *
 * The cleartext base URL only needs to be `http://127.0.0.1:<port>`; pair it with
 * `adb reverse tcp:<port> tcp:<port>` so the device reaches the dev machine. See
 * `tools/adb-local-ota.sh` for the whole loop in one command.
 *
 * Access control is via `android:permission="android.permission.WRITE_SECURE_SETTINGS"`
 * in the manifest: only the adb shell / system (which hold it) can reach the receiver, so a
 * regular third-party app cannot repoint the updater. A UID check inside onReceive does NOT
 * work here - Binder.getCallingUid() reports the receiver's own uid and getSentFromUid() is
 * -1 for shell-originated broadcasts.
 */
class DevConfigReceiver : BroadcastReceiver() {

    @Suppress("ApplySharedPref")
    override fun onReceive(context: Context, intent: Intent) {
        val edit = context.getSharedPreferences(UpdateChecker.PREFS_NAME, 0).edit()

        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            edit.remove(UpdateChecker.KEY_OTA_URL).remove(UpdateChecker.KEY_ROM_URL)
                .putBoolean(UpdateChecker.KEY_DEV_UNLOCKED, true)
            // commit(), not apply(): a short-lived broadcast process can be killed before an
            // async write lands, and the dev would then think the URL never stuck.
            edit.commit()
            Log.i(TAG, "sources reset to defaults")
            return
        }

        // A single `url` applies to both tabs; the per-tab extras win when both are given.
        val both = intent.getStringExtra(EXTRA_URL)?.trim()?.takeIf { it.isNotEmpty() }
        val ota = intent.getStringExtra(EXTRA_OTA_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: both
        val rom = intent.getStringExtra(EXTRA_ROM_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: both
        if (ota == null && rom == null) return

        if (ota != null) edit.putString(UpdateChecker.KEY_OTA_URL, ota)
        if (rom != null) edit.putString(UpdateChecker.KEY_ROM_URL, rom)
        // Surface the overrides in Advanced settings so what's active is visible on-device.
        edit.putBoolean(UpdateChecker.KEY_DEV_UNLOCKED, true)
        edit.commit()
        Log.i(TAG, "sources updated: ota=$ota rom=$rom")
    }

    companion object {
        private const val TAG = "CloudyDevConfig"
        const val ACTION = "dev.cloudy.ota.action.SET_SOURCE"
        const val EXTRA_URL = "url"
        const val EXTRA_OTA_URL = "ota_url"
        const val EXTRA_ROM_URL = "rom_url"
        const val EXTRA_CLEAR = "clear"
    }
}
