package dev.cloudy.ota.ota

import android.content.Context
import android.os.RecoverySystem
import dev.cloudy.ota.R
import java.io.File

sealed interface InstallResult {
    data object StagedRebootingToRecovery : InstallResult
    data class NeedsRoot(val why: String) : InstallResult
    data class Failed(val why: String) : InstallResult
}

/**
 * Handles applying a downloaded package on an **A-Only** device.
 *
 * A-Only means there is no inactive slot to flash into safely, so the correct,
 * recoverable strategy is: hand the package to **recovery** (which flips into an
 * update-applying mode with its own fail-safes) rather than dd-ing a live system.
 *
 * Three tiers, tried in order of safety:
 *   1. Privileged system-app path (RecoverySystem) — only if Cloudy is in priv-app.
 *   2. Root path via the Cloudy module — writes /cache/recovery/command and reboots.
 *   3. Raw block write — DANGEROUS, gated behind an explicit user confirmation flag.
 */
class OtaInstaller(private val context: Context) {

    /** Tier 1: works only when Cloudy holds the system RECOVERY permission (priv-app in ROM). */
    fun tryPrivilegedInstall(pkg: File): InstallResult = try {
        RecoverySystem.verifyPackage(pkg, null, null)
        RecoverySystem.installPackage(context, pkg)   // triggers reboot to recovery
        InstallResult.StagedRebootingToRecovery
    } catch (se: SecurityException) {
        InstallResult.NeedsRoot(context.getString(R.string.err_not_priv_app, se.message))
    } catch (e: Exception) {
        InstallResult.Failed(context.getString(R.string.err_verify_failed, e.message))
    }

    /**
     * Tier 2: root staging. Relies on the Cloudy module having relaxed SELinux so we
     * can write the recovery command file. This is exactly what OEM/AOSP recovery reads
     * on the next boot to apply an OTA and then wipe the command.
     */
    fun rootStageRecovery(pkg: File): InstallResult {
        if (!RootManager.hasRoot()) return InstallResult.NeedsRoot(context.getString(R.string.err_no_root_shell))
        if (!RootManager.cloudyModulePresent())
            return InstallResult.NeedsRoot(context.getString(R.string.err_module_missing))

        // Recovery expects the package on a partition it can read early. /data/media/0 (== internal
        // /sdcard) is standard for sideload-style OTAs on A-only Samsung.
        val staged = "/data/media/0/cloudy/${pkg.name}"
        // Two automation files so both recoveries pick it up:
        //  - /cache/recovery/command        -> stock/OEM recovery (--update_package)
        //  - /cache/recovery/openrecoveryscript -> TWRP, executed automatically at boot
        val commands = """
            mkdir -p /data/media/0/cloudy
            cp '${pkg.absolutePath}' '$staged'
            chmod 0644 '$staged'
            mkdir -p /cache/recovery
            printf '%s\n' '--update_package=$staged' '--wipe_cache' > /cache/recovery/command
            printf '%s\n' 'install $staged' 'reboot system' > /cache/recovery/openrecoveryscript
            chmod 0644 /cache/recovery/command /cache/recovery/openrecoveryscript
            sync
        """.trimIndent()

        val res = RootManager.exec(*commands.lines().map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
        if (!res.isSuccess) return InstallResult.Failed(context.getString(R.string.err_staging_failed, res.err.joinToString()))

        // Reboot into recovery to apply. Prefer the framework reboot reason; module SELinux
        // rule allows the fallback binary too.
        val reboot = RootManager.exec("/system/bin/reboot recovery || reboot recovery")
        return if (reboot.isSuccess) InstallResult.StagedRebootingToRecovery
        else InstallResult.Failed(context.getString(R.string.err_reboot_failed, reboot.err.joinToString()))
    }

    /**
     * Tier 3: raw image write straight to a by-name block device. Only reachable when the
     * caller passes [confirmedRawFlash] = true from a scary confirmation dialog, because on
     * A-Only a bad write here has no second slot to fall back on = hard brick risk.
     *
     * [target] must be a validated partition name (e.g. "system", "boot"); we resolve it via
     * the by-name symlink rather than a hardcoded block number so it survives across units.
     */
    fun rawBlockFlash(pkg: File, target: String, confirmedRawFlash: Boolean): InstallResult {
        if (!confirmedRawFlash) return InstallResult.Failed(context.getString(R.string.err_raw_not_confirmed))
        if (!RootManager.hasRoot() || !RootManager.cloudyModulePresent())
            return InstallResult.NeedsRoot(context.getString(R.string.err_raw_need_root))

        val safe = target.filter { it.isLetterOrDigit() || it == '_' }
        val cmd = """
            BN=/dev/block/mapper/$safe
            [ -e "${'$'}BN" ] || BN=/dev/block/by-name/$safe
            [ -e "${'$'}BN" ] || BN=/dev/block/bootdevice/by-name/$safe
            [ -e "${'$'}BN" ] || { echo "NO_PARTITION"; exit 1; }
            dd if='${pkg.absolutePath}' of="${'$'}BN" bs=8M conv=fsync
        """.trimIndent()
        val res = RootManager.exec(cmd)
        return when {
            res.out.any { it.contains("NO_PARTITION") } -> InstallResult.Failed(context.getString(R.string.err_partition_not_found, safe))
            res.isSuccess -> InstallResult.StagedRebootingToRecovery
            else -> InstallResult.Failed(context.getString(R.string.err_dd_failed, res.err.joinToString()))
        }
    }
}
