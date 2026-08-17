package dev.cloudy.ota.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import dev.cloudy.ota.R

/** Sub-screen opened from the Settings tab: Update source + Maintenance preferences. */
class AdvancedSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "cloudy"
        setPreferencesFromResource(R.xml.prefs_advanced, rootKey)
        SettingsPrefs.wire(this)
    }
}
