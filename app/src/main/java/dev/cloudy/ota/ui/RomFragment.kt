package dev.cloudy.ota.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.cloudy.ota.R
import dev.cloudy.ota.data.Release
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.databinding.FragmentRomBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ROM info tab: every available build (picker) and the changelog of the selected build.
 * Download/install lives in the OTA tab.
 */
class RomFragment : Fragment() {

    private var _b: FragmentRomBinding? = null
    private val b get() = _b!!
    private val repo by lazy { UpdateRepository(requireContext()) }

    private var releases: List<Release> = emptyList()
    private var selectedIndex: Int = 0

    private val jsonUrl: String
        get() = requireContext()
            .getSharedPreferences("cloudy", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: OtaFragment.DEFAULT_JSON_URL

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRomBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.rowVersionPicker.setOnClickListener { showBuildPicker() }
        check()
    }

    private fun check() {
        viewLifecycleOwner.lifecycleScope.launch {
            repo.fetchManifest(jsonUrl)
                .onSuccess { m ->
                    releases = m.allReleases.sortedWith(
                        compareByDescending<Release> { it.versionCode ?: Long.MIN_VALUE }
                            .thenByDescending { it.version }
                    )
                    if (releases.isNotEmpty()) {
                        selectedIndex = 0
                        renderSelected()
                    }
                }
                .onFailure { }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun formatOneUiVersion(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.length != 5 || s.any { !it.isDigit() }) return null
        val major = s[0]
        val minor = s[2]
        val patch = s[4]
        return if (patch == '0') "$major.$minor" else "$major.$minor.$patch"
    }

    private fun selectedRelease(): Release? = releases.getOrNull(selectedIndex)

    private fun releaseLabel(r: Release): String =
        "${r.version} · ${formatBytes(r.download.sizeBytes)}"

    private fun renderSelected() {
        val v = _b ?: return
        val sel = selectedRelease() ?: return
        v.rowVersionPicker.summary = releaseLabel(sel)
        v.rowBuildDate.summary = sel.buildDate
        v.rowDownloadSize.summary = formatBytes(sel.download.sizeBytes)
        v.rowRemoteAndroid.summary = sel.androidVersion
        v.rowRemoteOneUi.summary = formatOneUiVersion(sel.oneuiVersion) ?: "-"
        v.rowRemoteSecurity.summary = sel.securityPatch
        v.rowRemoteFingerprint.summary = sel.fingerprint
        v.changelog.text = sel.changelog.joinToString("\n") { "•  $it" }
    }

    private fun showBuildPicker() {
        if (releases.isEmpty()) return
        val labels = releases.map { releaseLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.f_select_build)
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(R.string.dlg_ok) { d, _ ->
                d.dismiss()
                renderSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}