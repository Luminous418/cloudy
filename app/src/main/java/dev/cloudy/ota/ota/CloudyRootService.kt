package dev.cloudy.ota.ota

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService as LibsuRootService
import dev.cloudy.ota.R
import java.io.File

/**
 * libsu RootService — hosts a persistent worker in a separate ROOT process.
 * Everything in [Ipc] runs as uid 0, so the app never has to spawn `su -c` per action.
 */
class CloudyRootService : LibsuRootService() {

    override fun onBind(intent: Intent): IBinder = Ipc()

    private inner class Ipc : IRootIpc.Stub() {

        override fun getProp(key: String): String = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            p.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        }.getOrDefault("")

        override fun moduleReady(): Boolean =
            File("/system/etc/cloudy_ready").exists() ||
            File("/data/adb/modules/cloudy_ota/cloudy_ready").exists() ||
            File("/data/adb/modules/cloudy_ota/module.prop").exists()

        override fun stageRecovery(pkgPath: String, filename: String): String {
            return try {
                val staged = "/data/media/0/cloudy/$filename"
                sh(
                    "mkdir -p /data/media/0/cloudy",
                    "cp '$pkgPath' '$staged'",
                    "chmod 0644 '$staged'",
                    "mkdir -p /cache/recovery",
                    "printf '%s\\n' '--update_package=$staged' '--wipe_cache' > /cache/recovery/command",
                    "printf '%s\\n' 'install $staged' 'reboot system' > /cache/recovery/openrecoveryscript",
                    "chmod 0644 /cache/recovery/command /cache/recovery/openrecoveryscript",
                    "sync"
                )
                ""
            } catch (e: Exception) {
                e.message ?: this@CloudyRootService.getString(R.string.rs_stage_failed)
            }
        }

        override fun rawFlash(
            pkgPath: String,
            partition: String,
            totalBytes: Long,
            cb: IFlashCallback
        ) {
            val safe = partition.filter { it.isLetterOrDigit() || it == '_' }
            try {
                // Resolve the by-name symlink so we never touch a hardcoded mmcblk number.
                val bn = resolveByName(safe)
                    ?: run { cb.onDone(false, this@CloudyRootService.getString(R.string.err_partition_not_found, safe)); return }

                // dd status=progress writes "<bytes> bytes ... copied" lines to STDERR.
                val proc = ProcessBuilder(
                    "sh", "-c",
                    "dd if='$pkgPath' of='$bn' bs=8M conv=fsync status=progress"
                ).redirectErrorStream(false).start()

                proc.errorStream.bufferedReader().forEachLine { line ->
                    val copied = Regex("^(\\d+) bytes").find(line.trim())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    val pct = if (copied != null && totalBytes > 0)
                        ((copied * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
                    cb.onProgress(pct, line.trim())
                }
                val code = proc.waitFor()
                if (code == 0) cb.onProgress(100, this@CloudyRootService.getString(R.string.rs_flush_complete))
                cb.onDone(code == 0, if (code == 0) this@CloudyRootService.getString(R.string.rs_flashed, safe) else this@CloudyRootService.getString(R.string.rs_dd_exited, code))
            } catch (e: Exception) {
                cb.onDone(false, e.message ?: this@CloudyRootService.getString(R.string.rs_raw_failed))
            }
        }

        override fun rebootRecovery(): Boolean = runCatching {
            sh("/system/bin/reboot recovery || reboot recovery"); true
        }.getOrDefault(false)

        private fun resolveByName(name: String): String? {
            // Dynamic-partition (super) devices expose logical partitions via device-mapper;
            // Samsung A-series also keeps some physical ones under /dev/block/by-name.
            for (base in listOf(
                "/dev/block/mapper",
                "/dev/block/by-name",
                "/dev/block/bootdevice/by-name"
            )) {
                val f = File("$base/$name")
                if (f.exists()) return f.absolutePath
            }
            return null
        }

        private fun sh(vararg cmds: String) {
            val p = ProcessBuilder("sh", "-c", cmds.joinToString(" && ")).start()
            if (p.waitFor() != 0) {
                val err = p.errorStream.bufferedReader().readText()
                throw RuntimeException(err.ifBlank { this@CloudyRootService.getString(R.string.rs_shell_failed) })
            }
        }
    }
}
