package dev.cloudy.ota.ota

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

    /**
     * Warm up the root shell in the background. MUST go through this object so the
     * [init] builder is set before the main shell is created - calling Shell.getShell
     * directly first (e.g. from Application.onCreate) makes setDefaultBuilder throw
     * "The main shell was already created" on the first real use.
     */
    fun preload() = Shell.getShell { }

    /** Baked-ROM marker: dropped at /system/etc when the ROM provides Cloudy's SELinux rules + staging dirs itself. */
    private const val BAKED_MARKER = "/system/etc/cloudy_ready"

    /**
     * True when the environment has Cloudy's SELinux rules live. Either the ROM bakes them
     * (marker file in /system/etc) or the Magisk/KernelSU module dropped its readiness marker.
     */
    fun cloudyModulePresent(): Boolean {
        val paths = listOf(
            BAKED_MARKER,
            "/data/adb/modules/cloudy_ota/module.prop",
            "/data/adb/modules/cloudy_ota/cloudy_ready"
        )
        val res = Shell.cmd(paths.joinToString(" || ") { "[ -e $it ]" } + " && echo YES").exec()
        return res.isSuccess && res.out.any { it.trim() == "YES" }
    }

    /** Run a command as root, returning stdout lines. Empty on failure. */
    fun exec(vararg cmds: String): Shell.Result = Shell.cmd(*cmds).exec()
}
