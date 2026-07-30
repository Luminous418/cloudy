// Streams progress from the root worker back to the app process.
package dev.ncatt.ota.ota;

interface IFlashCallback {
    // percent is -1 when unknown (indeterminate); line is a raw status line for logs.
    oneway void onProgress(int percent, String line);
    oneway void onDone(boolean success, String message);
}
