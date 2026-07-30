package dev.ncatt.ota.ui

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.ncatt.ota.R

/**
 * Cloudy internal settings, using the SESL preference fork so the rows match OneUI.
 *   • "Custom JSON URL"      → SeslEditTextPreference-style dialog (androidx.preference sesl)
 *   • "Reset Configurations" → clears every SharedPreference flag
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "cloudy"
        setPreferencesFromResource(R.xml.prefs, rootKey)

        (findPreference<EditTextPreference>("json_url"))?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            if (text.isNullOrBlank()) text = CheckUpdateFragment.DEFAULT_JSON_URL
        }

        findPreference<Preference>("reset")?.setOnPreferenceClickListener {
            requireContext().getSharedPreferences("cloudy", 0).edit().clear().apply()
            // Re-seed the default URL so the app stays usable after a reset.
            requireContext().getSharedPreferences("cloudy", 0).edit()
                .putString("json_url", CheckUpdateFragment.DEFAULT_JSON_URL).apply()
            findPreference<EditTextPreference>("json_url")?.text = CheckUpdateFragment.DEFAULT_JSON_URL
            true
        }
    }
}
