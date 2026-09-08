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
import dev.cloudy.ota.databinding.FragmentOtaBinding
import dev.cloudy.ota.ota.DeviceInfo
import dev.cloudy.ota.ota.DownloadService
import dev.cloudy.ota.ota.InstallResult
import dev.cloudy.ota.ota.OtaInstaller
import dev.cloudy.ota.ota.UpdateChecker
import dev.cloudy.ota.ota.VersionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Main OTA tab: checks for updates automatically, lets the user pick a build and
 * downloads + installs it. Only packages coming from the LumiROM manifest are offered
 * (no local-file flashing), and each package is signature-verified before install.
 */
class OtaFragment : Fragment() {

    private var _b: FragmentOtaBinding? = null
    private val b get() = _b!!
    private val repo by lazy { UpdateRepository(requireContext()) }

    /** Every build the manifest offers, sorted newest first. The selector lists these. */
    private var releases: List<Release> = emptyList()

    /** Index into [releases] of the build currently shown + targeted by Download. */
    private var selectedIndex: Int = 0

    private val jsonUrl: String
        get() = requireContext()
            .getSharedPreferences("cloudy", 0)
            .getString(UpdateChecker.KEY_OTA_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: CheckUpdateFragment.DEFAULT_OTA_URL

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentOtaBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The "Available OTA" section appears only when a newer build exists.
        b.sepAvailable.visibility = View.GONE
        b.cardAvailable.visibility = View.GONE
        // This device details live here (changelog + full build list live in the ROM tab).
        b.rowVersionPicker.setOnClickListener { showBuildPicker() }
        b.btnDownload.setOnClickListener { selectedRelease()?.let { downloadAndInstall(it.download) } }
        renderLocalDeviceRows()
        check()
        observeDownload()
    }

    private fun check() {
        setHero(R.drawable.ic_cloud_large, getString(R.string.status_checking), getString(R.string.status_checking_sub))
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = repo.fetchManifest(jsonUrl)
            val v = _b ?: return@launch

            result
                .onSuccess { m ->
                    releases = m.allReleases.sortedWith(
                        compareByDescending<Release> { it.versionCode ?: Long.MIN_VALUE }
                            .thenByDescending { it.version }
                    )

                    if (releases.isEmpty()) {
                        setHero(
                            R.drawable.ic_status_error,
                            getString(R.string.status_failed),
                            getString(R.string.err_no_releases)
                        )
                        v.btnDownload.visibility = View.GONE
                        v.btnDownload.isEnabled = false
                        return@onSuccess
                    }

                    selectedIndex = 0
                    renderSelected()

                    val verdict = withContext(Dispatchers.IO) { VersionCheck.evaluate(releases[0], resources) }
                    v.rowInstalledVersion.summary = verdict.installed

                    if (verdict.updateAvailable) {
                        setHero(
                            R.drawable.ic_status_available,
                            getString(R.string.status_update_available),
                            getString(R.string.status_update_available_sub, releases[0].version)
                        )
                        // "Available OTA" section: only when a newer build actually exists.
                        v.sepAvailable.visibility = View.VISIBLE
                        v.cardAvailable.visibility = View.VISIBLE
                    } else {
                        setHero(
                            R.drawable.ic_status_uptodate,
                            getString(R.string.status_up_to_date),
                            getString(R.string.status_up_to_date_sub, verdict.installed)
                        )
                        v.sepAvailable.visibility = View.GONE
                        v.cardAvailable.visibility = View.GONE
                    }

                    val downloading = DownloadService.state.value is DownloadState.Progress
                    if (!downloading) {
                        v.btnDownload.visibility = View.VISIBLE
                        v.btnDownload.isEnabled = true
                    }
                }
                .onFailure { t ->
                    releases = emptyList()
                    selectedIndex = 0
                    setHero(
                        R.drawable.ic_status_error,
                        getString(R.string.status_failed),
                        repo.describe(t)
                    )
                    v.btnDownload.visibility = View.GONE
                    v.btnDownload.isEnabled = false
                }

            if (DownloadService.state.value !is DownloadState.Progress) {
                v.downloadBar.visibility = View.GONE
                v.downloadProgress.visibility = View.GONE
            }
        }
    }

    private fun setHero(iconRes: Int, title: String, subtitle: String) {
        val v = _b ?: return
        v.heroIcon.setImageResource(iconRes)
        v.heroTitle.text = title
        v.heroSubtitle.text = subtitle
    }

    /**
     * Device rows come from `getprop` and /proc/version - process forks and a file read.
     * They run on the main thread otherwise, which stutters the first frame of the tab.
     */
    private fun renderLocalDeviceRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                LocalInfo(
                    installed = DeviceInfo.romVersion.ifBlank { "${DeviceInfo.PROP_ROM_VER} ${getString(R.string.prop_unset)}" },
                    model = DeviceInfo.model,
                    bootloader = DeviceInfo.bootloader,
                    android = DeviceInfo.androidVersion,
                    oneUi = DeviceInfo.oneUiVersion,
                    patch = DeviceInfo.securityPatch,
                    fingerprint = DeviceInfo.fingerprint,
                    kernel = DeviceInfo.kernelVersion
                )
            }
            val v = _b ?: return@launch
            v.rowInstalledVersion.summary = info.installed
            v.rowDeviceModel.summary = info.model
            v.rowBootloader.summary = info.bootloader
            v.rowAndroid.summary = info.android
            v.rowOneUi.summary = formatOneUiVersion(info.oneUi) ?: "-"
            v.rowSecurity.summary = info.patch
            v.rowFingerprint.summary = info.fingerprint
            v.rowKernel.summary = info.kernel
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
        if (v.heroTitle.text?.toString() == getString(R.string.status_update_available)) {
            v.heroSubtitle.text = getString(R.string.status_update_available_sub, sel.version)
        }
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
        setHero(
            R.drawable.ic_status_available,
            getString(R.string.status_downloading),
            getString(R.string.status_downloading_sub)
        )
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
                        setHero(
                            R.drawable.ic_status_available,
                            getString(R.string.status_downloading),
                            getString(R.string.status_downloading_sub)
                        )
                        v.btnDownload.isEnabled = false
                    }
                    is DownloadState.Failed -> {
                        v.downloadBar.visibility = View.GONE
                        v.downloadProgress.visibility = View.GONE
                        setHero(R.drawable.ic_status_error, getString(R.string.status_failed), st.reason)
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
     * on this ROM) before handing it to recovery. A package that fails verification is
     * discarded instead of being flashed.
     */
    private fun install(pkg: File) {
        setHero(R.drawable.ic_status_available, getString(R.string.status_installing), "")

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
                    setHero(R.drawable.ic_status_available, getString(R.string.hero_staged), getString(R.string.hero_rebooting))
                is InstallResult.NeedsRoot -> {
                    setHero(R.drawable.ic_status_error, getString(R.string.hero_root_required),
                        getString(R.string.hero_root_required_sub, result.why))
                    v.btnDownload.isEnabled = true
                }
                is InstallResult.Failed -> {
                    setHero(R.drawable.ic_status_error, getString(R.string.hero_install_failed), result.why)
                    v.btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "0 B/s" else "${formatBytes(bytesPerSecond)}/s"

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private data class LocalInfo(
        val installed: String,
        val model: String,
        val bootloader: String,
        val android: String,
        val oneUi: String,
        val patch: String,
        val fingerprint: String,
        val kernel: String
    )

    companion object {
        private const val OTA_BASE = "https://raw.githubusercontent.com/Luminous418/cloudy/refs/heads/main/updater"
        val DEFAULT_JSON_URL: String get() = "$OTA_BASE/${DeviceInfo.deviceCodename}.json"
    }
}