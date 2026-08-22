package dev.cloudy.ota.ota

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.cloudy.ota.data.Download
import dev.cloudy.ota.data.DownloadState
import dev.cloudy.ota.data.UpdateRepository
import dev.cloudy.ota.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns a ROM download so it survives Cloudy being backgrounded.
 * A coroutine inside Tab 1 died whenever the system froze or killed the process - and left
 * a frozen progress notification behind. Under startForeground the process stays alive
 * while bytes flow, and the silent "download progress" notification doubles as the
 * mandatory foreground notification (dataSync type, required on API 34+).
 *
 * Tab 1 mirrors [state] into its own bar while open; when the app isn't open the
 * notification alone carries the progress.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { UpdateRepository(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val filename = intent?.getStringExtra(EXTRA_FILENAME)
        if (url.isNullOrEmpty() || filename.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val dl = Download(
            url = url,
            filename = filename,
            sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L),
            sha256 = intent.getStringExtra(EXTRA_SHA256),
            installType = intent.getStringExtra(EXTRA_INSTALL_TYPE) ?: "recovery_zip"
        )

        // startForegroundService allows only seconds to enter the foreground - do it first,
        // before any other work.
        startForeground(
            UpdateNotifier.DOWNLOAD_NOTIF_ID,
            UpdateNotifier.buildProgressNotification(this, 0, "")
        )
        running = true
        _state.value = null

        scope.launch {
            val dest = File(getExternalFilesDir(null), dl.filename)
            runCatching {
                repo.download(dl, dest).collect { st ->
                    _state.value = st
                    if (st is DownloadState.Progress) {
                        val pct = (st.fraction * 100).toInt()
                        getSystemService(NotificationManager::class.java).notify(
                            UpdateNotifier.DOWNLOAD_NOTIF_ID,
                            UpdateNotifier.buildProgressNotification(
                                this@DownloadService, pct, formatSpeed(st.bytesPerSecond)
                            )
                        )
                    }
                }
                Unit // collect returns normally only after Done/Failed was emitted
            }.onFailure { t ->
                _state.value = DownloadState.Failed(repo.describe(t))
            }
            // Drop the foreground pair so neither the notification nor the service
            // outlives the download.
            running = false
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()   // also cancels an in-flight download if the system stops us anyway
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILENAME = "filename"
        private const val EXTRA_SIZE_BYTES = "size_bytes"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_INSTALL_TYPE = "install_type"

        @Volatile
        var running: Boolean = false
            private set

        private val _state = MutableStateFlow<DownloadState?>(null)

        /** Latest download state for the tab's mirror; null when idle. */
        val state: StateFlow<DownloadState?> = _state.asStateFlow()

        /** Clears a terminal state so a fresh collector doesn't replay Done/Failed. */
        fun consume() {
            _state.value = null
        }

        fun start(context: Context, dl: Download) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, dl.url)
                .putExtra(EXTRA_FILENAME, dl.filename)
                .putExtra(EXTRA_SIZE_BYTES, dl.sizeBytes)
                .putExtra(EXTRA_SHA256, dl.sha256)
                .putExtra(EXTRA_INSTALL_TYPE, dl.installType)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
