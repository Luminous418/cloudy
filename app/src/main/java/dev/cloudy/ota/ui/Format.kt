package dev.cloudy.ota.ui

import java.util.Locale

/** Shared byte/speed formatters for the download UI and its notification mirror. */

/** "3.4 MB/s" readout; switches to KB/s below 1 MB/s. */
fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec <= 0L -> "0 KB/s"
    bytesPerSec >= 1L shl 20 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
}
