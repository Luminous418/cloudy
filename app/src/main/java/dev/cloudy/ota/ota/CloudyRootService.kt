package dev.cloudy.ota.ota

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService as LibsuRootService
import java.io.File

/**
 * libsu RootService — hosts a persistent worker in a separate ROOT process.
 * Everything in [Ipc] runs as uid 0, so the app never has to spawn `su -c` per action.
 */
class CloudyRootService : LibsuRootService() {

    override fun onBind(intent: Intent): IBinder = Ipc()

    private class Ipc : IRootIpc.Stub() {

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
                    "chmod 0644 /cache/recovery/command",
                    "sync"
                )
                ""
            } catch (e: Exception) {
                e.message ?: "stage failed"
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
                    ?: run { cb.onDone(false, "Partition '$safe' not found"); return }

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
                if (code == 0) cb.onProgress(100, "flush complete")
                cb.onDone(code == 0, if (code == 0) "Flashed $safe" else "dd exited $code")
            } catch (e: Exception) {
                cb.onDone(false, e.message ?: "raw flash failed")
            }
        }

        override fun rebootRecovery(): Boolean = runCatching {
            sh("/system/bin/reboot recovery || reboot recovery"); true
        }.getOrDefault(false)

        private fun resolveByName(name: String): String? {
            for (base in listOf("/dev/block/by-name", "/dev/block/bootdevice/by-name")) {
                val f = File("$base/$name")
                if (f.exists()) return f.absolutePath
            }
            return null
        }

        private fun sh(vararg cmds: String) {
            val p = ProcessBuilder("sh", "-c", cmds.joinToString(" && ")).start()
            if (p.waitFor() != 0) {
                val err = p.errorStream.bufferedReader().readText()
                throw RuntimeException(err.ifBlank { "shell command failed" })
            }
        }
    }
}
