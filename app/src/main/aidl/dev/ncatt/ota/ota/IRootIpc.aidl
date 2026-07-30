// Persistent root worker interface. Implemented inside the libsu RootService process,
// so the app can call these without spawning a fresh `su -c` for every action.
package dev.ncatt.ota.ota;

import dev.ncatt.ota.ota.IFlashCallback;

interface IRootIpc {
    // Read any system property from the root context (e.g. ro.cloudy.version).
    String getProp(String key);

    // True if the Cloudy module dropped its readiness marker.
    boolean moduleReady();

    // Stage a recovery-flashable package and write /cache/recovery/command.
    // Returns "" on success or an error string.
    String stageRecovery(String pkgPath, String filename);

    // Raw block flash with live progress via the callback. Total is the file size in bytes
    // so the worker can compute a percentage from `dd status=progress`.
    void rawFlash(String pkgPath, String partition, long totalBytes, IFlashCallback cb);

    // Reboot into recovery to apply a staged update.
    boolean rebootRecovery();
}
