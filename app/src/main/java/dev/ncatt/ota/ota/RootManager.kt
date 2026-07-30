package dev.ncatt.ota.ota

import com.topjohnwu.superuser.Shell

/**
 * Thin wrapper over libsu. Detects a working root shell and, specifically, the
 * Cloudy Magisk/KernelSU module that grants us the SELinux + permission rules
 * needed to stage a recovery update on this A-Only device.
 */
object RootManager {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
    }

    /** True only if a real root shell is available (Magisk / KernelSU / other su). */
    fun hasRoot(): Boolean = Shell.getShell().isRoot

    /** The module drops this marker in post-fs-data.sh so the app can confirm its rules are live. */
    fun cloudyModulePresent(): Boolean {
        val paths = listOf(
            "/data/adb/modules/cloudy_ota/module.prop",
            "/data/adb/modules/cloudy_ota/cloudy_ready"
        )
        val res = Shell.cmd(paths.joinToString(" || ") { "[ -e $it ]" } + " && echo YES").exec()
        return res.isSuccess && res.out.any { it.trim() == "YES" }
    }

    /** Run a command as root, returning stdout lines. Empty on failure. */
    fun exec(vararg cmds: String): Shell.Result = Shell.cmd(*cmds).exec()
}
