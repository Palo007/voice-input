package org.futo.voiceinput.parakeet

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.futo.voiceinput.backend.SpeechBackend

internal class ParakeetEngineLease(
    internal val id: Long,
    private val backend: ParakeetBackend
) : SpeechBackend {
    override suspend fun load(context: Context) = Unit
    override suspend fun transcribe(samples: FloatArray): String = backend.transcribe(samples)
    override suspend fun close() = Unit
}

/** Runtime ids whose engine is kept loaded between recordings. Only one is held at a time. */
internal val WARM_PARAKEET_RUNTIMES = setOf("parakeet", "parakeet_ultra", "parakeet_sk")

private fun warmParakeetBackend(runtimeId: String): ParakeetBackend = when (runtimeId) {
    "parakeet" -> ParakeetBackend()
    "parakeet_ultra" -> parakeetUltraBackend()
    "parakeet_sk" -> parakeetSlovakBackend()
    else -> throw IllegalArgumentException("$runtimeId is not a warm Parakeet runtime")
}

object ParakeetEngineManager {
    private val mutex = Mutex()
    private var backend: ParakeetBackend? = null
    private var backendRuntimeId: String? = null
    private var unloadJob: Job? = null
    private val activeLeaseIds = mutableSetOf<Long>()
    private var nextLeaseId = 0L

    internal suspend fun acquire(context: Context, runtimeId: String = "parakeet"): ParakeetEngineLease = mutex.withLock {
        val startedAt = System.nanoTime()
        unloadJob?.cancel()
        unloadJob = null

        if (backend != null && backendRuntimeId != runtimeId) {
            // Two 0.6B engines would not fit next to each other on most phones. A lease still
            // held for the old engine fails with "not loaded" instead of using freed memory.
            activeLeaseIds.clear()
            closeLocked()
        }

        val current = backend
        if (current != null) {
            current.configureDiagnostics(context)
            current.logEngineAcquired(warm = true, elapsedMs = (System.nanoTime() - startedAt) / 1_000_000)
            return@withLock newLease(current)
        }

        backend = null
        val loadedBackend = warmParakeetBackend(runtimeId).also {
            it.load(context.applicationContext)
            it.logEngineAcquired(warm = false, elapsedMs = (System.nanoTime() - startedAt) / 1_000_000)
            backend = it
            backendRuntimeId = runtimeId
        }
        newLease(loadedBackend)
    }

    private fun newLease(backend: ParakeetBackend): ParakeetEngineLease {
        val id = ++nextLeaseId
        activeLeaseIds += id
        return ParakeetEngineLease(id, backend)
    }

    internal suspend fun release(
        lease: ParakeetEngineLease,
        scope: LifecycleCoroutineScope,
        keepWarm: Boolean,
        timeoutMs: Long = 0L
    ) = mutex.withLock {
        if (!activeLeaseIds.remove(lease.id)) {
            return@withLock
        }
        if (activeLeaseIds.isNotEmpty()) {
            return@withLock
        }

        unloadJob?.cancel()
        unloadJob = null
        if (!keepWarm) {
            closeLocked()
            return@withLock
        }

        unloadJob = scope.launch {
            delay(timeoutMs)
            mutex.withLock { closeLocked() }
        }
    }

    /** Closes the warm engine. With [runtimeId], only if that runtime is the one loaded. */
    suspend fun forceClose(runtimeId: String? = null) = mutex.withLock {
        if (runtimeId != null && backend != null && backendRuntimeId != runtimeId) return@withLock
        unloadJob?.cancel()
        unloadJob = null
        activeLeaseIds.clear()
        closeLocked()
    }

    private suspend fun closeLocked() {
        backend?.close()
        backend = null
        backendRuntimeId = null
    }

    fun isWarm(): Boolean = backend != null

    internal fun warmRuntimeId(): String? = backendRuntimeId
}

internal suspend fun acquireParakeetRuntime(context: Context, runtimeId: String = "parakeet"): SpeechBackend =
    ParakeetEngineManager.acquire(context, runtimeId)

internal suspend fun releaseParakeetRuntime(
    backend: SpeechBackend,
    scope: LifecycleCoroutineScope,
    keepWarm: Boolean,
    timeoutMs: Long
): Boolean {
    val lease = backend as? ParakeetEngineLease ?: return false
    ParakeetEngineManager.release(lease, scope, keepWarm, timeoutMs)
    return true
}

internal suspend fun releaseParakeetArtifacts(runtimeId: String): Boolean {
    if (runtimeId !in WARM_PARAKEET_RUNTIMES) return false
    ParakeetEngineManager.forceClose(runtimeId)
    return true
}
