package dev.cloudy.ota.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.cloudy.ota.R
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.databinding.FragmentMaintainerBinding
import dev.cloudy.ota.ota.DeviceInfo
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
                    loadAvatar(v, mt.avatarUrl)
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

    /**
     * Replaces the default hero glyph with the maintainer's avatar when the manifest
     * provides an avatar_url. The photo fills the halo circle (kept as a ring via margins)
     * and is clipped to an oval; on any failure the cloud placeholder stays untouched.
     */
    private fun loadAvatar(v: FragmentMaintainerBinding, url: String?) {
        if (url.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { fetchAvatar(url) }
            val img = _b?.avatar ?: return@launch
            if (bmp == null) return@launch
            img.setImageBitmap(bmp)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.clipToOutline = true
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            // Grow to the full halo so the avatar IS the circle, leaving the accent ring.
            (img.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                setMargins(12, 12, 12, 12)
                img.layoutParams = this
            }
        }
    }

    private suspend fun fetchAvatar(url: String): Bitmap? =
        repo.fetchImageBytes(url)?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
