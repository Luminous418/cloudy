package dev.cloudy.ota

import android.app.Application
import dev.cloudy.ota.ota.RootManager

class CloudyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Preload the root shell config early so first use in the flasher is fast.
        // Goes through RootManager so the libsu builder is set before the shell exists.
        RootManager.preload()
    }
}
