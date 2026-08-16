package dev.cloudy.ota.data

import com.google.gson.annotations.SerializedName

/**
 * 1:1 mapping of the remote update JSON. Every field here is surfaced in Tab 1.
 * See updater/a32.json in the repo root for the canonical example.
 *
 * A manifest may now carry MANY releases (the LumiHub-style "pick the build you want"
 * index). The legacy single `release` field is still parsed for backwards compatibility;
 * [allReleases] merges both shapes so callers only ever deal with one list.
 */
data class UpdateManifest(
    @SerializedName("rom_name") val romName: String,
    @SerializedName("maintainer") val maintainer: Maintainer,
    @SerializedName("releases") val releases: List<Release>? = null,
    @Deprecated("Prefer the releases list") @SerializedName("release") val release: Release? = null
) {
    /** All builds this device can install, newest first if the manifest is ordered. */
    val allReleases: List<Release>
        get() = if (!releases.isNullOrEmpty()) releases else listOfNotNull(release)
}

data class Maintainer(
    @SerializedName("name") val name: String,
    @SerializedName("handle") val handle: String,
    @SerializedName("device") val device: String,
    @SerializedName("codename") val codename: String,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("telegram") val telegram: String?,
    @SerializedName("donate_url") val donateUrl: String?
)

data class Release(
    @SerializedName("version") val version: String,
    @SerializedName("version_code") val versionCode: Long? = null, // preferred: compare vs ro.cloudy.rom.ver.code
    @SerializedName("build_date") val buildDate: String,          // Tab1: Build Date
    @SerializedName("android_version") val androidVersion: String, // Tab1: Android Version
    @SerializedName("oneui_version") val oneuiVersion: String? = null, // "80500" → "8.5" (see CheckUpdateFragment.formatOneUiVersion)
    @SerializedName("security_patch") val securityPatch: String,   // Tab1: Security Patch Level
    @SerializedName("build_fingerprint") val fingerprint: String,  // Tab1: Build Fingerprint
    @SerializedName("device_model") val deviceModel: String,       // Tab1: Device Model
    @SerializedName("kernel_version") val kernelVersion: String,   // Tab1: Kernel Version
    @SerializedName("partition_layout") val partitionLayout: String, // "a-only" expected
    @SerializedName("changelog") val changelog: List<String>,      // Tab1: Changelogs
    @SerializedName("download") val download: Download
)

data class Download(
    @SerializedName("url") val url: String,
    @SerializedName("filename") val filename: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    // Optional integrity check. When null/blank the download is accepted without a hash
    // (some builds in the index predate checksums); whenever present it is enforced.
    @SerializedName("sha256") val sha256: String? = null,
    @SerializedName("install_type") val installType: String // "recovery_zip" | "raw_image"
)
