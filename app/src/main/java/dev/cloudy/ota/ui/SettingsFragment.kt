package dev.cloudy.ota.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import dev.cloudy.ota.BuildConfig
import dev.cloudy.ota.R
import dev.cloudy.ota.data.AppUpdate
import dev.cloudy.ota.data.DownloadState
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.ota.UpdateAlarm
import dev.cloudy.ota.ota.UpdateChecker
import dev.cloudy.ota.ota.UpdateNotifier
import kotlinx.coroutines.launch
import java.io.File

/** OneUI-style app info header (icon, name, version) with a self-update pill. */
class AppInfoPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    /** Fired when the update-check pill is tapped. */
    var onCheckUpdatesClick: (() -> Unit)? = null

    /** Label shown on the pill; "Check app updates" until the first check. */
    var pillLabel: CharSequence? = null
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.pref_app_info_header
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        holder.itemView.findViewById<TextView>(R.id.appVersion)?.text =
            context.getString(R.string.app_version, version)
        holder.itemView.findViewById<TextView>(R.id.appUpdatePill)?.apply {
            text = pillLabel ?: context.getString(R.string.prefs_check_app_update)
            setOnClickListener { onCheckUpdatesClick?.invoke() }
        }
    }
}

/** Wiring shared by the Settings tab and the Advanced settings screen. */
object SettingsPrefs {
    fun wire(f: PreferenceFragmentCompat) {
        f.findPreference<EditTextPreference>("json_url")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            if (text.isNullOrBlank()) text = CheckUpdateFragment.DEFAULT_JSON_URL
        }
        f.findPreference<ListPreference>("update_interval")?.apply {
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                // The SESL fork's ListPreference dialog fails to persist through the
                // preference's own persistString, so write the value here explicitly.
                f.requireContext().getSharedPreferences(UpdateChecker.PREFS_NAME, 0)
                    .edit().putString(UpdateChecker.KEY_INTERVAL, newValue.toString()).apply()
                rescheduleNotifications(f)
                true
            }
        }
        f.findPreference<Preference>("reset")?.setOnPreferenceClickListener {
            f.requireContext().getSharedPreferences("cloudy", 0).edit().clear().apply()
            // Re-seed the default URL so the app stays usable after a reset.
            f.requireContext().getSharedPreferences("cloudy", 0).edit()
                .putString("json_url", CheckUpdateFragment.DEFAULT_JSON_URL).apply()
            f.findPreference<EditTextPreference>("json_url")?.text = CheckUpdateFragment.DEFAULT_JSON_URL
            // The interval pref was cleared, so the pending alarm must match the default.
            rescheduleNotifications(f)
            true
        }
    }

    /** Re-arms the background alarm so a changed/reset interval applies immediately. */
    private fun rescheduleNotifications(f: PreferenceFragmentCompat) {
        val ctx = f.requireContext()
        if (UpdateChecker.notificationsEnabled(ctx)) {
            UpdateAlarm.cancel(ctx)
            UpdateAlarm.schedule(ctx)
        }
    }
}

/**
 * Cloudy internal settings, using the SESL preference fork so the rows match OneUI.
 *   • "Credits"         → shows the app/ROM credits dialog
 *   • "Advanced settings" → opens the AdvancedSettingsFragment sub-screen
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val repo by lazy { UpdateRepository(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "cloudy"
        setPreferencesFromResource(R.xml.prefs, rootKey)
        SettingsPrefs.wire(this)

        findPreference<AppInfoPreference>("app_info")?.onCheckUpdatesClick = { checkAppUpdate() }

        // Global "notify me about updates" switch: arm/cancel the daily alarm and ask for
        // the notification permission (Android 13+) when it is turned on.
        findPreference<SwitchPreferenceCompat>("update_notifications")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val ctx = requireContext()
                if (newValue == true) {
                    UpdateNotifier.ensureChannel(ctx)
                    UpdateAlarm.schedule(ctx)
                    if (!UpdateNotifier.canPost(ctx)) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIF_PERMISSION
                        )
                    }
                } else {
                    UpdateAlarm.cancel(ctx)
                }
                true
            }

        findPreference<Preference>("credits")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dlg_credits_title)
                .setMessage(R.string.dlg_credits_body)
                .setPositiveButton(R.string.dlg_credits_telegram) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_telegram)))
                        )
                    }
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<Preference>("advanced")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.pushFragment(AdvancedSettingsFragment(), R.string.prefs_advanced)
            true
        }
    }

    /** Fetches the app manifest and reports whether Cloudy itself is up to date. */
    private fun checkAppUpdate() {
        val checking = AlertDialog.Builder(requireContext())
            .setTitle(R.string.prefs_check_app_update)
            .setMessage(R.string.app_update_checking)
            .setCancelable(false)
            .create()
        checking.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = repo.fetchAppUpdate(APP_MANIFEST_URL)
            if (!checking.isShowing) return@launch
            checking.dismiss()
            result.fold(
                onSuccess = { upd -> handleAppUpdate(upd) },
                onFailure = { t ->
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.app_update_error_title)
                        .setMessage(repo.describe(t))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            )
        }
    }

    private fun handleAppUpdate(upd: AppUpdate) {
        val installed = BuildConfig.VERSION_CODE.toLong()
        val pill = findPreference<AppInfoPreference>("app_info")
        if (upd.versionCode <= installed) {
            pill?.pillLabel = getString(R.string.app_update_uptodate)
            Toast.makeText(requireContext(), getString(R.string.app_update_uptodate), Toast.LENGTH_SHORT).show()
        } else {
            pill?.pillLabel = getString(R.string.app_update_available_title)
            val changelog = upd.changelog.orEmpty().joinToString("\n") { "•  $it" }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.app_update_available_title)
                .setMessage(
                    getString(R.string.app_update_available_msg, upd.version) +
                        if (changelog.isEmpty()) "" else "\n\n$changelog"
                )
                .setPositiveButton(R.string.app_update_download) { _, _ -> downloadAppUpdate(upd) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Downloads the APK (with SHA-256 verification) and hands it to the system installer. */
    private fun downloadAppUpdate(upd: AppUpdate) {
        val ctx = requireContext()
        val dest = File(ctx.getExternalFilesDir(null), upd.download.filename)
        val view = layoutInflater.inflate(R.layout.dialog_app_update, null)
        // AlertDialog inflates against its own themed context, so the activity's font factory
        // does not reach this view tree - apply the family by hand (same as the flash dialog).
        OneUiFont.applyRecursively(view)
        val bar = view.findViewById<ProgressBar>(R.id.updateBar)
        val label = view.findViewById<TextView>(R.id.updateLabel)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.app_update_available_title)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            repo.download(upd.download, dest).collect { st ->
                if (!view.isAttachedToWindow) return@collect
                when (st) {
                    is DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        bar.isIndeterminate = false
                        bar.progress = pct
                        label.text = getString(R.string.app_update_downloading_sub, pct)
                    }
                    is DownloadState.Failed -> {
                        dialog.dismiss()
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.app_update_error_title)
                            .setMessage(st.reason)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is DownloadState.Done -> {
                        dialog.dismiss()
                        installApk(st.file)
                    }
                }
            }
        }
    }

    /** FileProvider URI + ACTION_VIEW: the system PackageInstaller shows the confirmation. */
    private fun installApk(file: File) {
        val ctx = requireContext()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { t ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.app_update_error_title)
                    .setMessage(getString(R.string.app_update_open_installer_failed, t.message))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
    }

    companion object {
        /** App self-update manifest, hosted in the repo like the ROM manifests. */
        private const val APP_MANIFEST_URL =
            "https://raw.githubusercontent.com/Luminous418/cloudy/refs/heads/main/updater/app.json"
        private const val REQUEST_NOTIF_PERMISSION = 9002
    }
}
