package dev.ncatt.ota.data

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data class Progress(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    data class Done(val file: File) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    /**
     * Fetch + parse the manifest from the user-configured Custom JSON URL.
     *
     * The `withContext(Dispatchers.IO)` is load-bearing, not tidiness. `Call.execute()` is the
     * BLOCKING OkHttp call, and both callers launch this from `lifecycleScope`, which dispatches
     * on Main. Without the switch, Android's StrictMode kills the request with
     * NetworkOnMainThreadException - whose `message` is null - so runCatching swallowed it and
     * the UI reported "Check failed: null" on every single check. Marking a function `suspend`
     * does NOT move it off the caller's thread; only a dispatcher switch does.
     */
    suspend fun fetchManifest(url: String): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.isNotBlank()) { "No manifest URL configured" }
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Manifest URL must start with http:// or https://"
            }
            val request = Request.Builder().url(url).header("User-Agent", "Cloudy/8.6.4").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error(
                        when (resp.code) {
                            404 -> "No manifest for this device (HTTP 404)"
                            403 -> "Manifest access denied (HTTP 403)"
                            in 500..599 -> "Update server error (HTTP ${resp.code})"
                            else -> "HTTP ${resp.code}"
                        }
                    )
                }
                val body = resp.body?.string()
                if (body.isNullOrBlank()) error("Update server returned an empty response")
                gson.fromJson(body, UpdateManifest::class.java)
                    ?: error("Manifest was empty or not an object")
            }
        }
    }

    /**
     * Streams the package to [dest], emitting progress, then verifies SHA-256.
     * A mismatched hash fails the download instead of handing a corrupt image to the flasher.
     */
    fun download(download: Download, dest: File): Flow<DownloadState> = flow {
        val request = Request.Builder().url(download.url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) { emit(DownloadState.Failed("HTTP ${resp.code}")); return@flow }
            val body = resp.body ?: run { emit(DownloadState.Failed("Empty body")); return@flow }
            val total = if (download.sizeBytes > 0) download.sizeBytes else body.contentLength()

            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        written += read
                        // Emitting per 64KB chunk floods the UI with thousands of updates on a
                        // multi-GB ROM zip; throttle to whole percentage points.
                        val pct = if (total > 0) written * 100 / total else 0
                        if (pct != lastEmit) {
                            lastEmit = pct
                            emit(DownloadState.Progress(written, total))
                        }
                    }
                    emit(DownloadState.Progress(written, total))
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(download.sha256, ignoreCase = true)) {
                dest.delete()
                emit(DownloadState.Failed("Checksum mismatch - download rejected"))
            } else {
                emit(DownloadState.Done(dest))
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /**
         * Turns a throwable into something worth showing on a status row. Several of the
         * failures that actually happen here (NetworkOnMainThreadException, some IOExceptions)
         * carry a null message, which is how the old code produced "Check failed: null".
         */
        fun describe(t: Throwable): String = when (t) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Update server timed out"
            is JsonSyntaxException -> "Manifest is not valid JSON"
            is IllegalArgumentException, is IllegalStateException ->
                t.message ?: "Could not read the update manifest"
            is IOException -> t.message ?: "Network error"
            else -> t.message ?: t::class.java.simpleName
        }
    }
}
