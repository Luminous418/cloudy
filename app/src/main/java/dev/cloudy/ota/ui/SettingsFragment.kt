package dev.cloudy.ota.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.cloudy.ota.R

/** Wiring shared by the Settings tab and the Advanced settings screen. */
object SettingsPrefs {
    fun wire(f: PreferenceFragmentCompat) {
        f.findPreference<EditTextPreference>("json_url")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            if (text.isNullOrBlank()) text = CheckUpdateFragment.DEFAULT_JSON_URL
        }
        f.findPreference<Preference>("reset")?.setOnPreferenceClickListener {
            f.requireContext().getSharedPreferences("cloudy", 0).edit().clear().apply()
            // Re-seed the default URL so the app stays usable after a reset.
            f.requireContext().getSharedPreferences("cloudy", 0).edit()
                .putString("json_url", CheckUpdateFragment.DEFAULT_JSON_URL).apply()
            f.findPreference<EditTextPreference>("json_url")?.text = CheckUpdateFragment.DEFAULT_JSON_URL
            true
        }
    }
}

/**
 * Cloudy internal settings, using the SESL preference fork so the rows match OneUI.
 *   • "Credits"         → shows the app/ROM credits dialog
 *   • "Advanced settings" → opens the AdvancedSettingsFragment sub-screen
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "cloudy"
        setPreferencesFromResource(R.xml.prefs, rootKey)
        SettingsPrefs.wire(this)

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
}
