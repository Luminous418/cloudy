package dev.ncatt.ota.data

import com.google.gson.annotations.SerializedName

/**
 * 1:1 mapping of the remote update JSON. Every field here is surfaced in Tab 1.
 * See sample_update.json in the repo root for the canonical example.
 */
data class UpdateManifest(
    @SerializedName("rom_name") val romName: String,
    @SerializedName("maintainer") val maintainer: Maintainer,
    @SerializedName("release") val release: Release
)

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
    @SerializedName("sha256") val sha256: String,   // integrity check before flashing
    @SerializedName("install_type") val installType: String // "recovery_zip" | "raw_image"
)
