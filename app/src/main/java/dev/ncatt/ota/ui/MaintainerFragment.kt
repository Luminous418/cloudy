package dev.ncatt.ota.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.ncatt.ota.R
import dev.ncatt.ota.data.UpdateRepository
import dev.ncatt.ota.databinding.FragmentMaintainerBinding
import dev.ncatt.ota.ota.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tab 2 — SESL card layout with maintainer profile, device and contact/credits actions. */
class MaintainerFragment : Fragment() {

    private var _b: FragmentMaintainerBinding? = null
    private val b get() = _b!!
    private val repo = UpdateRepository()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMaintainerBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = requireContext().getSharedPreferences("cloudy", 0)
            .getString("json_url", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: CheckUpdateFragment.DEFAULT_JSON_URL

        viewLifecycleOwner.lifecycleScope.launch {
            // Populate from device props first so the tab is never blank while the network
            // call is in flight. Props fork `getprop`, so they go to IO like everything else.
            val local = withContext(Dispatchers.IO) {
                Triple(DeviceInfo.maintainer, DeviceInfo.model, DeviceInfo.romVersion)
            }
            _b?.let { v ->
                v.name.text = local.first.ifBlank { getString(R.string.unknown_maintainer) }
                v.device.summary = local.second
                v.rom.summary = local.third.ifBlank { "-" }
            }

            // This used to share the Check tab's bug: fetchManifest blocked on Main, threw
            // NetworkOnMainThreadException, and the result was dropped silently because there
            // was no onFailure branch - the tab just sat on the prop values forever.
            repo.fetchManifest(url)
                .onSuccess { m ->
                    val v = _b ?: return@onSuccess
                    val mt = m.maintainer
                    // ro.cloudy.maintainer (baked into the ROM) is authoritative; the JSON
                    // value is only a fallback for devices that don't set the prop.
                    v.name.text = local.first.ifBlank { mt.name }
                    v.handle.text = mt.handle
                    v.device.summary = "${mt.device} (${mt.codename})"
                    v.rom.summary = m.romName
                    v.btnTelegram.setOnClickListener { open(mt.telegram) }
                    v.btnDonate.setOnClickListener { open(mt.donateUrl) }
                }
                .onFailure { t ->
                    _b?.handle?.text = UpdateRepository.describe(t)
                }
        }
    }

    private fun open(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
