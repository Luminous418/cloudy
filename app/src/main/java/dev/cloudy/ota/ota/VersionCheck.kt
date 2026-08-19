package dev.cloudy.ota.ota

import android.content.res.Resources
import dev.cloudy.ota.R
import dev.cloudy.ota.data.Release

/**
 * Decides whether a remote [Release] is newer than what's installed, using the ROM's own
 * stamp `ro.cloudy.rom.ver` (and `ro.cloudy.rom.ver.code` when available) as the source of truth.
 *
 * Order of preference:
 *   1. Integer compare: manifest.version_code  vs  ro.cloudy.rom.ver.code
 *   2. Semver compare:  manifest.version        vs  ro.cloudy.rom.ver
 *   3. Fallback:        build fingerprint differs (last resort, least reliable)
 */
object VersionCheck {

    data class Result(val updateAvailable: Boolean, val installed: String, val reason: String)

    fun evaluate(release: Release, res: Resources): Result {
        val installedVersion = DeviceInfo.romVersion            // ro.cloudy.rom.ver
        val installedCode = DeviceInfo.romVersionCode           // ro.cloudy.rom.ver.code

        // 1) numeric version codes — cleanest
        if (release.versionCode != null && installedCode != null) {
            return Result(
                release.versionCode > installedCode,
                installedVersion.ifBlank { installedCode.toString() },
                "version_code $installedCode → ${release.versionCode}"
            )
        }

        // 2) semver from ro.cloudy.rom.ver
        if (installedVersion.isNotBlank()) {
            val cmp = compareSemver(extractSemver(release.version), extractSemver(installedVersion))
            return Result(cmp > 0, installedVersion, "${DeviceInfo.PROP_ROM_VER} $installedVersion")
        }

        // 3) fingerprint fallback (non-LumiROM base, prop unset)
        val differs = release.fingerprint.isNotBlank() &&
                release.fingerprint != DeviceInfo.fingerprint
        return Result(differs, res.getString(R.string.installed_unknown, DeviceInfo.PROP_ROM_VER), "fingerprint fallback")
    }

    /** Pulls the first x[.y[.z]] token out of a label like "LumiROM 8.6.4 Beta". */
    private fun extractSemver(s: String): List<Int> =
        Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(s)
            ?.let { m -> m.groupValues.drop(1).map { it.toIntOrNull() ?: 0 } }
            ?: listOf(0, 0, 0)

    private fun compareSemver(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
