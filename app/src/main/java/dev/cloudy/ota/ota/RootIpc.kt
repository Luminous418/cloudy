package dev.cloudy.ota.ota

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService as LibsuRootService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Binds [CloudyRootService] once and keeps the connection alive for the
 * session. Call [connect] before flashing; [disconnect] when the screen is done.
 */
class RootIpc(private val context: Context) {

    @Volatile var worker: IRootIpc? = null
        private set

    private var connection: ServiceConnection? = null

    val isConnected: Boolean get() = worker != null

    /** Suspends until the root worker is bound (or returns false if root is unavailable). */
    suspend fun connect(): Boolean = suspendCancellableCoroutine { cont ->
        if (!RootManager.hasRoot()) { cont.resume(false); return@suspendCancellableCoroutine }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                worker = IRootIpc.Stub.asInterface(binder)
                if (cont.isActive) cont.resume(worker != null)
            }
            override fun onServiceDisconnected(name: ComponentName?) { worker = null }
        }
        connection = conn
        val intent = Intent(context, CloudyRootService::class.java)
        LibsuRootService.bind(intent, conn)
        cont.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        connection?.let { LibsuRootService.stop(Intent(context, CloudyRootService::class.java)) }
        connection = null
        worker = null
    }

    /** Convenience: read the ROM stamp / maintainer props via the root worker. */
    fun romVersionProp(): String = worker?.getProp(DeviceInfo.PROP_ROM_VER).orEmpty()
    fun maintainerProp(): String = worker?.getProp(DeviceInfo.PROP_MAINTAINER).orEmpty()
}
