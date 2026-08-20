package dev.cloudy.ota.ota

import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Reads the *installed* device state so Tab 1 can compare it against the remote release. */
object DeviceInfo {
    val model: String get() = getProp("ro.product.vendor.model") ?: Build.MODEL
    val bootloader: String get() = getProp("ro.boot.bootloader") ?: Build.BOOTLOADER
    val codename: String get() = Build.DEVICE
    val androidVersion: String get() = Build.VERSION.RELEASE ?: "?"
    val securityPatch: String get() = Build.VERSION.SECURITY_PATCH
    val fingerprint: String get() = Build.FINGERPRINT
    val buildDisplay: String get() = Build.DISPLAY

    /** Cached: /proc/version is a file read, and Tab 1 re-renders it on every check. */
    val kernelVersion: String by lazy {
        runCatching { File("/proc/version").readText().trim() }
            .getOrElse { System.getProperty("os.version") ?: "?" }
    }

    /**
     * The ROM's own version stamp: `ro.cloudy.rom.ver` (e.g. "8.6.4").
     * Most reliable signal for what's installed - better than fingerprint diffing.
     * Empty when unset (e.g. Cloudy running on a non-LumiROM build).
     */
    val romVersion: String get() = getProp(PROP_ROM_VER).orEmpty()

    /** Optional numeric companion for clean integer comparison. */
    val romVersionCode: Long? get() = getProp(PROP_ROM_VER_CODE)?.toLongOrNull()

    /** Maintainer name baked into the ROM: `ro.cloudy.maintainer`. */
    val maintainer: String get() = getProp(PROP_MAINTAINER).orEmpty()

    /** Samsung One UI build stamp, e.g. "80500" → formatted as "8.5" by the UI. */
    val oneUiVersion: String get() = getProp("ro.build.version.oneui").orEmpty()

    /** Device codename used to build the default OTA manifest URL (e.g. "a32"). */
    val deviceCodename: String
        get() = getProp("ro.product.vendor.device").orEmpty().ifBlank { Build.DEVICE ?: "unknown" }

    /** A-Only vs A/B, detected from the ROM slot suffix property. */
    val isAOnly: Boolean get() = getProp("ro.boot.slot_suffix").isNullOrEmpty()

    const val PROP_ROM_VER = "ro.cloudy.rom.ver"
    const val PROP_ROM_VER_CODE = "ro.cloudy.rom.ver.code"
    const val PROP_MAINTAINER = "ro.cloudy.maintainer"

    // Every getProp() forks a `getprop` process (~10-30ms). Tab 1 alone reads six of them on
    // each render, on the main thread, which is a visible stutter on an A32. These are all
    // ro.* properties - immutable for the life of the boot - so one read each is enough.
    private val cache = ConcurrentHashMap<String, String>()

    fun getProp(key: String): String? {
        cache[key]?.let { return it.ifEmpty { null } }
        val value = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val line = p.inputStream.bufferedReader().use { it.readLine() }?.trim()
            p.waitFor()          // reap the child instead of leaking a zombie per call
            line
        }.getOrNull()
        cache[key] = value.orEmpty()
        return value
    }
}
