package org.futo.voiceinput.parakeet

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.futo.voiceinput.backend.SpeechBackend
import org.futo.voiceinput.settings.PARAKEET_KEEP_TWO_WARM
import org.futo.voiceinput.settings.PARAKEET_KEEP_WARM
import org.futo.voiceinput.settings.getSetting

internal class ParakeetEngineLease(
    internal val id: Long,
    private val backend: ParakeetBackend
) : SpeechBackend {
    override suspend fun load(context: Context) = Unit
    override suspend fun transcribe(samples: FloatArray): String = backend.transcribe(samples)
    override suspend fun close() = Unit
}

/** Runtime ids whose engine is kept loaded between recordings. One at a time, or two with PARAKEET_KEEP_TWO_WARM. */
internal val WARM_PARAKEET_RUNTIMES = setOf("parakeet", "parakeet_ultra", "parakeet_sk")

private fun warmParakeetBackend(runtimeId: String): ParakeetBackend = when (runtimeId) {
    "parakeet" -> ParakeetBackend()
    "parakeet_ultra" -> parakeetUltraBackend()
    "parakeet_sk" -> parakeetSlovakBackend()
    else -> throw IllegalArgumentException("$runtimeId is not a warm Parakeet runtime")
}

/** One loaded engine. Guarded by [ParakeetEngineManager]'s mutex. */
private class WarmEngine(val runtimeId: String, val backend: ParakeetBackend) {
    val leaseIds = mutableSetOf<Long>()
    var unloadJob: Job? = null
    var lastUsedNanos = System.nanoTime()
}

object ParakeetEngineManager {
    private val mutex = Mutex()
    /** One engine by default. With [PARAKEET_KEEP_TWO_WARM], two different runtimes (for SK | EN). */
    private val engines = mutableListOf<WarmEngine>()
    /** Read without the lock by [isWarm] and [warmRuntimeIds]; replaced under the lock. */
    @Volatile private var engineSnapshot: List<String> = emptyList()
    /** Background preloads in progress; they load outside the lock so releases are not delayed. */
    private val preloading = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var nextLeaseId = 0L
    /**
     * Unload timers outlive the screen or keyboard that started them, so an engine is never left
     * loaded because its lifecycle ended first.
     */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun publishLocked() { engineSnapshot = engines.map { it.runtimeId } }

    /** Waits until no preload runs, without holding the lock while waiting. */
    private suspend fun awaitPreloads() {
        while (true) {
            val pending = mutex.withLock { preloading.values.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.await() }
        }
    }

    private suspend fun capacity(context: Context): Int =
        if (context.getSetting(PARAKEET_KEEP_WARM) && context.getSetting(PARAKEET_KEEP_TWO_WARM)) 2 else 1

    internal suspend fun acquire(context: Context, runtimeId: String = "parakeet"): ParakeetEngineLease {
        val capacity = capacity(context)
        // A preload of this or another runtime would otherwise load a third engine next to this one.
        awaitPreloads()
        return mutex.withLock {
            val startedAt = System.nanoTime()
            val existing = engines.firstOrNull { it.runtimeId == runtimeId }
            // Make room first: a second 0.6B engine only loads when two are allowed.
            evictLocked(keep = existing, maxOthers = capacity - 1)
            if (existing != null) {
                existing.unloadJob?.cancel()
                existing.unloadJob = null
                existing.backend.configureDiagnostics(context)
                existing.backend.logEngineAcquired(warm = true, elapsedMs = (System.nanoTime() - startedAt) / 1_000_000)
                return@withLock newLease(existing)
            }
            val loaded = warmParakeetBackend(runtimeId).also {
                it.load(context.applicationContext)
                it.logEngineAcquired(warm = false, elapsedMs = (System.nanoTime() - startedAt) / 1_000_000)
            }
            newLease(WarmEngine(runtimeId, loaded).also { engines += it; publishLocked() })
        }
    }

    /**
     * Loads [runtimeId] in the background so a later SK | EN switch finds it warm. Does nothing unless
     * two engines are allowed, the runtime is not loaded yet, and loading it would close no engine in use.
     * The engine unloads after [timeoutMs] unless a recording acquires it first.
     */
    internal suspend fun preload(
        context: Context,
        runtimeId: String,
        timeoutMs: Long
    ) {
        if (runtimeId !in WARM_PARAKEET_RUNTIMES || capacity(context) < 2) return
        val done = CompletableDeferred<Unit>()
        mutex.withLock {
            if (engines.any { it.runtimeId == runtimeId } || preloading.isNotEmpty()) return
            if (engines.size >= 2 && engines.all { it.leaseIds.isNotEmpty() }) return
            preloading[runtimeId] = done
        }
        try {
            // Loads outside the lock: a recording's Stop releases its engine meanwhile without waiting.
            val loaded = warmParakeetBackend(runtimeId).also { it.load(context.applicationContext) }
            mutex.withLock {
                evictLocked(keep = null, maxOthers = 1)
                val engine = WarmEngine(runtimeId, loaded).also { engines += it }
                publishLocked()
                scheduleUnloadLocked(engine, timeoutMs)
            }
        } finally {
            mutex.withLock { preloading.remove(runtimeId) }
            done.complete(Unit)
        }
    }

    private fun newLease(engine: WarmEngine): ParakeetEngineLease {
        val id = ++nextLeaseId
        engine.leaseIds += id
        engine.lastUsedNanos = System.nanoTime()
        return ParakeetEngineLease(id, engine.backend)
    }

    /** Closes engines other than [keep] until at most [maxOthers] remain; idle and least recently used first. */
    private suspend fun evictLocked(keep: WarmEngine?, maxOthers: Int) {
        while (engines.count { it !== keep } > maxOthers.coerceAtLeast(0)) {
            val victim = engines.filter { it !== keep }
                .minWith(compareBy<WarmEngine>({ it.leaseIds.isNotEmpty() }, { it.lastUsedNanos }))
            // A lease still held for it fails with "not loaded" instead of using freed memory.
            closeLocked(victim)
        }
    }

    internal suspend fun release(
        lease: ParakeetEngineLease,
        scope: LifecycleCoroutineScope,
        keepWarm: Boolean,
        timeoutMs: Long = 0L
    ) = mutex.withLock {
        val engine = engines.firstOrNull { lease.id in it.leaseIds } ?: return@withLock
        engine.leaseIds -= lease.id
        engine.lastUsedNanos = System.nanoTime()
        if (engine.leaseIds.isNotEmpty()) return@withLock
        if (!keepWarm) {
            closeLocked(engine)
            return@withLock
        }
        scheduleUnloadLocked(engine, timeoutMs)
    }

    private fun scheduleUnloadLocked(engine: WarmEngine, timeoutMs: Long) {
        engine.unloadJob?.cancel()
        engine.unloadJob = timerScope.launch {
            delay(timeoutMs)
            mutex.withLock {
                if (engine in engines && engine.leaseIds.isEmpty()) closeLocked(engine)
            }
        }
    }

    /** Closes the warm engines. With [runtimeId], only that runtime's engine. */
    suspend fun forceClose(runtimeId: String? = null) = mutex.withLock {
        engines.filter { runtimeId == null || it.runtimeId == runtimeId }.forEach { closeLocked(it) }
    }

    private suspend fun closeLocked(engine: WarmEngine) {
        engine.unloadJob?.cancel()
        engine.unloadJob = null
        engine.leaseIds.clear()
        engines.remove(engine)
        publishLocked()
        engine.backend.close()
    }

    fun isWarm(): Boolean = engineSnapshot.isNotEmpty()

    internal fun warmRuntimeIds(): List<String> = engineSnapshot
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
