package dev.cloudy.ota.ota

import android.content.Context
import dev.cloudy.ota.BuildConfig
import dev.cloudy.ota.data.Release
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.ui.CheckUpdateFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared "is anything newer available?" logic used by both the in-app check and the
 * background update notifications. Only newer things are reported - an up-to-date app
 * or ROM yields nulls and no notification.
 */
object UpdateChecker {

    /** App self-update manifest (the same file the in-app pill reads). */
    const val APP_MANIFEST_URL =
        "https://raw.githubusercontent.com/Luminous418/cloudy/refs/heads/main/updater/app.json"

    const val PREFS_NAME = "cloudy"
    const val KEY_NOTIFICATIONS = "update_notifications"
    const val KEY_INTERVAL = "update_interval"

    /** Notification tap targets, matched against MainActivity extras / tab indexes. */
    const val TAB_UPDATE = "update"
    const val TAB_SETTINGS = "settings"

    data class Result(
        val appVersion: String? = null,
        val appVersionCode: Long? = null,
        val romVersion: String? = null,
    )

    fun notificationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_NOTIFICATIONS, true)

    /** Default ROM manifest URL (device codename based), same as the Update tab. */
    private fun romJsonUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: CheckUpdateFragment.DEFAULT_JSON_URL

    /**
     * Fetches both manifests and reports what is newer than what's installed.
     * Runs off the caller thread; safe to call from any dispatcher.
     */
    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val repo = UpdateRepository(context)
        var appVersion: String? = null
        var appVersionCode: Long? = null
        var romVersion: String? = null

        repo.fetchAppUpdate(APP_MANIFEST_URL).onSuccess { upd ->
            if (upd.versionCode > BuildConfig.VERSION_CODE) {
                appVersion = upd.version
                appVersionCode = upd.versionCode
            }
        }
        repo.fetchManifest(romJsonUrl(context)).onSuccess { m ->
            val newest: Release? = m.allReleases.sortedWith(
                compareByDescending<Release> { it.versionCode ?: Long.MIN_VALUE }
                    .thenByDescending { it.version }
            ).firstOrNull()
            if (newest != null && VersionCheck.evaluate(newest, context.resources).updateAvailable) {
                romVersion = newest.version
            }
        }
        Result(appVersion, appVersionCode, romVersion)
    }
}