package dev.cloudy.ota.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.cloudy.ota.R
import dev.cloudy.ota.data.Download
import dev.cloudy.ota.data.DownloadState
import dev.cloudy.ota.data.Release
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.databinding.FragmentRomBinding
import dev.cloudy.ota.ota.DownloadService
import dev.cloudy.ota.ota.InstallResult
import dev.cloudy.ota.ota.OtaInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * ROM tab: pick any build (not just the latest), download + install it, read the changelog.
 * Unlike OTA, the user can freely choose a build before downloading.
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
        b.btnDownload.setOnClickListener { selectedRelease()?.let { downloadAndInstall(it.download) } }
        check()
        observeDownload()
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
                        val downloading = DownloadService.state.value is DownloadState.Progress
                        if (!downloading) {
                            b.btnDownload.visibility = View.VISIBLE
                            b.btnDownload.isEnabled = true
                        }
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
                b.btnDownload.isEnabled = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstall(dl: Download) {
        b.btnDownload.isEnabled = false
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE
        DownloadService.start(requireContext(), dl)
    }

    private fun observeDownload() {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadService.state.collect { st ->
                val v = _b ?: return@collect
                when (st) {
                    null -> Unit
                    is DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        v.downloadBar.isIndeterminate = false
                        v.downloadBar.visibility = View.VISIBLE
                        v.downloadBar.progress = pct
                        v.downloadProgress.visibility = View.VISIBLE
                        v.downloadProgress.text =
                            getString(R.string.download_progress_format, pct, formatSpeed(st.bytesPerSecond))
                        v.btnDownload.isEnabled = false
                    }
                    is DownloadState.Failed -> {
                        v.downloadBar.visibility = View.GONE
                        v.downloadProgress.visibility = View.GONE
                        v.btnDownload.isEnabled = true
                        DownloadService.consume()
                    }
                    is DownloadState.Done -> {
                        v.downloadBar.visibility = View.GONE
                        v.downloadProgress.visibility = View.GONE
                        DownloadService.consume()
                        install(st.file)
                    }
                }
            }
        }
    }

    /**
     * Signature-verifies the package against the system's otacerts.zip (LumiROM's OTA cert
     * on this ROM) before handing it to recovery.
     */
    private fun install(pkg: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val installer = OtaInstaller(requireContext().applicationContext)
                when (val privileged = installer.tryPrivilegedInstall(pkg)) {
                    is InstallResult.NeedsRoot -> installer.rootStageRecovery(pkg)
                    else -> privileged
                }
            }
            val v = _b ?: return@launch
            when (result) {
                is InstallResult.StagedRebootingToRecovery ->
                    v.btnDownload.visibility = View.GONE
                is InstallResult.NeedsRoot -> v.btnDownload.isEnabled = true
                is InstallResult.Failed -> v.btnDownload.isEnabled = true
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "0 B/s" else "${formatBytes(bytesPerSecond)}/s"

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}