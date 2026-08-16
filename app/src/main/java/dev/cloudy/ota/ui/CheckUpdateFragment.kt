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
import dev.cloudy.ota.databinding.FragmentCheckUpdateBinding
import dev.cloudy.ota.ota.DeviceInfo
import dev.cloudy.ota.ota.IFlashCallback
import dev.cloudy.ota.ota.InstallResult
import dev.cloudy.ota.ota.OtaInstaller
import dev.cloudy.ota.ota.RootIpc
import dev.cloudy.ota.ota.VersionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CheckUpdateFragment : Fragment() {

    private var _b: FragmentCheckUpdateBinding? = null
    private val b get() = _b!!
    private val repo = UpdateRepository()
    private val rootIpc by lazy { RootIpc(requireContext().applicationContext) }

    /** Every build the manifest offers, sorted newest first. The selector lists these. */
    private var releases: List<Release> = emptyList()

    /** Index into [releases] of the build currently shown + targeted by Download. */
    private var selectedIndex: Int = 0

    /** Falls back to the default when the stored value is missing OR blank - a user who
     *  cleared the Settings field used to leave an empty string here, which OkHttp rejects. */
    private val jsonUrl: String
        get() = requireContext()
            .getSharedPreferences("cloudy", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_JSON_URL

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCheckUpdateBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderLocalDeviceRows()
        b.btnCheck.setOnClickListener { check() }
        b.rowVersionPicker.setOnClickListener { showBuildPicker() }
        b.btnDownload.setOnClickListener { selectedRelease()?.let { downloadAndInstall(it.download) } }
        check()
    }

    /**
     * Device rows come from `getprop` and /proc/version - process forks and a file read.
     * They ran on the main thread before, which stuttered the first frame of the tab.
     */
    private fun renderLocalDeviceRows() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                LocalInfo(
                    installed = DeviceInfo.romVersion.ifBlank { "${DeviceInfo.PROP_ROM_VER} unset" },
                    model = DeviceInfo.model,
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
            v.rowAndroid.summary = info.android
            v.rowOneUi.summary = formatOneUiVersion(info.oneUi) ?: "-"
            v.rowSecurity.summary = info.patch
            v.rowFingerprint.summary = info.fingerprint
            v.rowKernel.summary = info.kernel
        }
    }

    private fun check() {
        setHero(R.drawable.ic_cloud_large, getString(R.string.status_checking), getString(R.string.status_checking_sub))
        b.downloadBar.isIndeterminate = true
        b.downloadBar.visibility = View.VISIBLE
        b.btnCheck.isEnabled = false

        // viewLifecycleOwner, not lifecycleScope: a plain lifecycleScope job outlives
        // onDestroyView, so switching tabs mid-check resumed into `b` after it was nulled
        // and crashed with an NPE. The view scope cancels at onDestroyView instead.
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
                            "Manifest contains no releases"
                        )
                        showReleaseSections(false)
                        v.btnDownload.visibility = View.GONE
                        v.btnDownload.isEnabled = false
                        return@onSuccess
                    }

                    // The selector defaults to the newest build; the hero still answers
                    // "is there anything newer than what I have?" against that newest one.
                    selectedIndex = 0
                    renderSelected()

                    val verdict = withContext(Dispatchers.IO) { VersionCheck.evaluate(releases[0]) }
                    v.rowInstalledVersion.summary = verdict.installed

                    if (verdict.updateAvailable) {
                        setHero(
                            R.drawable.ic_status_available,
                            getString(R.string.status_update_available),
                            getString(R.string.status_update_available_sub, releases[0].version)
                        )
                    } else {
                        setHero(
                            R.drawable.ic_status_uptodate,
                            getString(R.string.status_up_to_date),
                            getString(R.string.status_up_to_date_sub, verdict.installed)
                        )
                    }
                    // Unlike the old single-release screen, the build selector stays on the
                    // screen even when up to date - that is the whole point of choosing a ROM.
                    showReleaseSections(true)
                    v.btnDownload.visibility = View.VISIBLE
                    v.btnDownload.isEnabled = true
                }
                .onFailure { t ->
                    releases = emptyList()
                    selectedIndex = 0
                    setHero(
                        R.drawable.ic_status_error,
                        getString(R.string.status_failed),
                        UpdateRepository.describe(t)
                    )
                    showReleaseSections(false)
                    v.btnDownload.visibility = View.GONE
                    v.btnDownload.isEnabled = false
                }

            v.downloadBar.visibility = View.GONE
            v.btnCheck.isEnabled = true
        }
    }

    private fun setHero(iconRes: Int, title: String, subtitle: String) {
        val v = _b ?: return
        v.heroIcon.setImageResource(iconRes)
        v.heroTitle.text = title
        v.heroSubtitle.text = subtitle
    }

    private fun showReleaseSections(visible: Boolean) {
        val v = _b ?: return
        val vis = if (visible) View.VISIBLE else View.GONE
        v.sepAvailable.visibility = vis
        v.cardAvailable.visibility = vis
        v.sepChangelog.visibility = vis
        v.cardChangelog.visibility = vis
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "-"
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /**
     * Renders a Samsung One UI version code into its human form, e.g. "80500" → "8.5".
     * The code is five digits: the 2nd and 4th become dots, and the 5th is dropped when zero,
     * so only the 1st, 3rd and (non-zero) 5th digits appear as numbers.
     */
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

    /** Paints the picker row + release details + changelog for [selectedIndex]. */
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

    /** Radio list of every build, mirroring LumiHub's version combo. */
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
        val dest = File(requireContext().getExternalFilesDir(null), dl.filename)
        b.btnDownload.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            repo.download(dl, dest).collect { st ->
                val v = _b ?: return@collect
                when (st) {
                    is DownloadState.Progress -> {
                        val pct = (st.fraction * 100).toInt()
                        v.downloadBar.isIndeterminate = false
                        v.downloadBar.visibility = View.VISIBLE
                        v.downloadBar.progress = pct
                        setHero(
                            R.drawable.ic_status_available,
                            getString(R.string.status_downloading),
                            getString(R.string.status_downloading_sub, pct)
                        )
                    }
                    is DownloadState.Failed -> {
                        v.downloadBar.visibility = View.GONE
                        setHero(R.drawable.ic_status_error, getString(R.string.status_failed), st.reason)
                        v.btnDownload.isEnabled = true
                    }
                    is DownloadState.Done -> {
                        v.downloadBar.visibility = View.GONE
                        install(st.file)
                    }
                }
            }
        }
    }

    private fun install(pkg: File) {
        val dl = selectedRelease()?.download ?: return
        if (dl.installType.equals("raw_image", ignoreCase = true)) {
            confirmRawFlash(pkg, dl)   // dangerous path -> explicit confirmation first
            return
        }
        setHero(R.drawable.ic_status_available, getString(R.string.status_installing), "")

        viewLifecycleOwner.lifecycleScope.launch {
            // RecoverySystem.verifyPackage re-hashes the whole zip and RootManager.exec blocks
            // on a shell round-trip. On a multi-GB ROM that is an ANR if it runs on Main.
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
                    setHero(R.drawable.ic_status_available, "Staged", "Rebooting to recovery to apply…")
                is InstallResult.NeedsRoot -> {
                    setHero(R.drawable.ic_status_error, "Root required", "Root + Cloudy module required (${result.why})")
                    v.btnDownload.isEnabled = true
                }
                is InstallResult.Failed -> {
                    setHero(R.drawable.ic_status_error, "Install failed", result.why)
                    v.btnDownload.isEnabled = true
                }
            }
        }
    }

    /** Scary, unambiguous confirmation before any direct-to-partition write on an A-only device. */
    private fun confirmRawFlash(pkg: File, dl: Download) {
        val target = "system"   // for LumiROM full images; a boot/recovery image would pass its own name
        AlertDialog.Builder(requireContext())
            .setTitle("Flash directly to /$target?")
            .setMessage(
                "This writes the image straight to the $target partition.\n\n" +
                "The Galaxy A32 is A-only — there is no backup slot. If the write is " +
                "interrupted or the image is wrong, the device may not boot and will need " +
                "recovery/Odin to restore.\n\nOnly continue if you understand the risk."
            )
            .setPositiveButton("I understand, flash") { _, _ -> startRawFlash(pkg, target, dl.sizeBytes) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRawFlash(pkg: File, partition: String, totalBytes: Long) {
        val progressView = layoutInflater.inflate(R.layout.dialog_flash_progress, null)
        // AlertDialog inflates against its own themed context, so the activity's font factory
        // does not reach this view tree - apply the family by hand.
        OneUiFont.applyRecursively(progressView)
        val bar = progressView.findViewById<android.widget.ProgressBar>(R.id.flashBar)
        val label = progressView.findViewById<android.widget.TextView>(R.id.flashLabel)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Flashing $partition")
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            if (!rootIpc.connect()) {
                dialog.dismiss()
                setHero(
                    R.drawable.ic_status_error,
                    getString(R.string.status_failed),
                    "Root worker unavailable — is the Cloudy module installed?"
                )
                return@launch
            }
            val cb = object : IFlashCallback.Stub() {
                override fun onProgress(percent: Int, line: String?) {
                    this@CheckUpdateFragment.view?.post {
                        if (percent >= 0) { bar.isIndeterminate = false; bar.progress = percent }
                        label.text = if (percent >= 0) "$percent%  ·  ${line.orEmpty()}" else line.orEmpty()
                    }
                }
                override fun onDone(success: Boolean, message: String?) {
                    // post-to-view instead of requireActivity().runOnUiThread: the callback can
                    // land after the fragment is detached, and requireActivity() throws then.
                    this@CheckUpdateFragment.view?.post {
                        dialog.dismiss()
                        if (success) {
                            setHero(R.drawable.ic_status_available, "Flashed", "Rebooting to recovery to finalize…")
                            rootIpc.worker?.rebootRecovery()
                        } else {
                            setHero(R.drawable.ic_status_error, "Flash failed", message.orEmpty())
                        }
                    }
                }
            }
            // Runs entirely in the root worker process; progress streams back via cb.
            withContext(Dispatchers.IO) {
                rootIpc.worker?.rawFlash(pkg.absolutePath, partition, totalBytes, cb)
            }
        }
    }

    override fun onDestroyView() {
        rootIpc.disconnect()
        super.onDestroyView()
        _b = null
    }

    private data class LocalInfo(
        val installed: String,
        val model: String,
        val android: String,
        val oneUi: String,
        val patch: String,
        val fingerprint: String,
        val kernel: String
    )

    companion object {
        /**
         * Default manifest location. The device codename is auto-detected from
         * `ro.product.vendor.device` (falling back to Build.DEVICE), e.g. .../16.2/a32.json
         */
        private const val OTA_BASE = "https://raw.githubusercontent.com/Luminous418/cloudy/refs/heads/main/updater"
        val DEFAULT_JSON_URL: String get() = "$OTA_BASE/${DeviceInfo.deviceCodename}.json"
    }
}
