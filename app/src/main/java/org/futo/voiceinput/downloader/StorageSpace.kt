package org.futo.voiceinput.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Plain free space under [dir]. Fast enough for the main thread. */
@SuppressLint("UsableSpace") // Callers refresh with allocatableBytes() off the main thread.
internal fun usableBytes(dir: File): Long = dir.usableSpace

/**
 * Bytes this app can write under [dir], including cached data Android can clear on request.
 * Never lower than [usableBytes]. Blocking: call off the main thread.
 */
internal fun Context.allocatableBytes(dir: File): Long {
    val usable = usableBytes(dir)
    return try {
        val storage = getSystemService(StorageManager::class.java)
        maxOf(usable, storage.getAllocatableBytes(storage.getUuidForPath(dir)))
    } catch (_: IOException) {
        usable
    } catch (_: RuntimeException) {
        usable
    }
}

/**
 * Asks Android to clear cached data so [bytes] fit under [dir]. Blocking: call off the main thread.
 * Failure is ignored; a download that does not fit then fails the same way as before.
 */
internal fun Context.reserveBytes(dir: File, bytes: Long) {
    if (bytes <= 0L || usableBytes(dir) >= bytes) return
    try {
        val storage = getSystemService(StorageManager::class.java)
        storage.allocateBytes(storage.getUuidForPath(dir), bytes)
    } catch (_: IOException) {
    } catch (_: RuntimeException) {
    }
}
