package org.futo.voiceinput

import org.futo.voiceinput.diagnostics.DiagnosticFailure

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.diagnostics.AppDiagnostics
import org.futo.voiceinput.diagnostics.DiagnosticEvent
import org.futo.voiceinput.diagnostics.DiagnosticMetric
import org.futo.voiceinput.diagnostics.DiagnosticSession
import org.futo.voiceinput.diagnostics.DiagnosticConfiguration
import org.futo.voiceinput.history.AudioHistoryStore
import org.futo.voiceinput.history.audioHistory
import org.futo.voiceinput.s1.S1MiniDiagnostics
import org.futo.voiceinput.s1.S1MiniTranscriptCapture
import org.futo.voiceinput.s1.S1MiniTranscriptDelivery
import org.futo.voiceinput.settings.S1_MINI_TRANSCRIPT_DIAGNOSTICS
import org.futo.voiceinput.settings.AUDIO_HISTORY_ENABLED
import org.futo.voiceinput.settings.AUDIO_HISTORY_RETENTION_HOURS
import org.futo.voiceinput.settings.ENABLE_30S_LIMIT
import org.futo.voiceinput.settings.END_OF_SPEECH_PROFILE
import org.futo.voiceinput.settings.IS_VAD_ENABLED
import org.futo.voiceinput.settings.MANUAL_STOP_DRAIN_MS
import org.futo.voiceinput.settings.MOONSHINE_MODEL_VARIANT
import org.futo.voiceinput.settings.ASR4ALL_VARIANT
import org.futo.voiceinput.settings.NEMOTRON_PROFILE
import org.futo.voiceinput.settings.PARAKEET_KEEP_WARM
import org.futo.voiceinput.settings.PARAKEET_KEEP_WARM_TIMEOUT_MS
import org.futo.voiceinput.settings.PARAKEET_USE_VAD
import org.futo.voiceinput.settings.PERSONAL_DICTIONARY
import org.futo.voiceinput.settings.SPEECH_BACKEND
import org.futo.voiceinput.settings.ROUTE_BY_LANGUAGE
import org.futo.voiceinput.settings.SpeechBackendType
import org.futo.voiceinput.settings.getSetting
import org.futo.voiceinput.parakeet.ParakeetEngineLease
import org.futo.voiceinput.parakeet.parakeetUnifiedRecognitionModel
import org.futo.voiceinput.backend.SpeechBackend
import org.futo.voiceinput.backend.StreamingSpeechBackend
import org.futo.voiceinput.backend.prepareRecordingFinalization
import org.futo.voiceinput.recognition.RecognitionModel
import org.futo.voiceinput.recognition.RecognitionModelLifecycle
import org.futo.voiceinput.recognition.RecognitionModelSelection
import org.futo.voiceinput.recognition.DictationLanguage
import org.futo.voiceinput.recognition.LanguageRouting
import org.futo.voiceinput.recognition.SwitchSegments
import org.futo.voiceinput.recognition.RecognitionRuntimeCallbacks
import org.futo.voiceinput.recognition.loadOrCloseOnFailure
import org.futo.voiceinput.settings.toSpeechBackendType
import org.futo.voiceinput.settings.toEndOfSpeechProfile
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val AUDIO_SAMPLE_RATE = 16000
private const val AUDIO_READ_SIZE = 1600
private const val AUTO_STOP_DRAIN_MS = 100L
private const val PARAKEET_AUTO_STOP_DRAIN_MS = 300L
private const val FINAL_SILENCE_PAD_MS = 200

internal class NoSpeechRecognizedException : Exception("The recognizer returned no text")

internal enum class StopReason {
    Manual,
    Vad,
    DurationLimit,
    Cancel,
    CaptureFailed
}

private enum class AppendResult {
    Accepted,
    DurationLimit,
    SessionEnded
}

internal object RecordingSessionPolicy {
    fun shouldRetryRecorderInitialization(failedAttempt: Int) = failedAttempt <= 32
    fun shouldReportRecorderReadFailure(readCount: Int, stopping: Boolean) = readCount <= 0 && !stopping
    fun shouldAcceptSamples(stopReason: StopReason?, captureGeneration: Long, currentGeneration: Long) =
        stopReason != StopReason.Cancel && captureGeneration == currentGeneration

    fun tailDrainMs(reason: StopReason, backendType: SpeechBackendType, manualDrainMs: Long) = when(reason) {
        StopReason.Manual -> manualDrainMs.coerceIn(0L, 1500L)
        StopReason.Vad -> if (backendType == SpeechBackendType.Parakeet) PARAKEET_AUTO_STOP_DRAIN_MS else AUTO_STOP_DRAIN_MS
        StopReason.DurationLimit -> AUTO_STOP_DRAIN_MS
        StopReason.Cancel, StopReason.CaptureFailed -> 0L
    }
}

internal class StreamingAudioReplay {
    private val pendingAudio = mutableListOf<FloatArray>()
    private var activeBackend: StreamingSpeechBackend? = null
    private var enabled = false

    @Synchronized
    fun reset(enabled: Boolean = false) {
        pendingAudio.clear()
        activeBackend = null
        this.enabled = enabled
    }

    @Synchronized
    fun isEnabled() = enabled

    @Synchronized
    fun acceptAudio(samples: FloatArray) {
        if (!enabled) return

        val backend = activeBackend
        if (backend == null) {
            pendingAudio.add(samples)
        } else {
            backend.acceptAudio(samples)
        }
    }

    @Synchronized
    fun start(
        backend: StreamingSpeechBackend,
        onPartial: (String) -> Unit,
        onCatchingUp: (Boolean) -> Unit
    ) {
        if (!enabled || activeBackend === backend) return
        check(activeBackend == null)

        backend.startStreaming(onPartial, onCatchingUp)
        pendingAudio.forEach(backend::acceptAudio)
        pendingAudio.clear()
        activeBackend = backend
    }
}

abstract class RecordingSession {
    internal var liveBenchmarkSession: org.futo.voiceinput.benchmark.LiveBenchmarkSession? = null
    protected var diagnostics: DiagnosticSession? = null
        private set
    private var diagnosticSamplingJob: Job? = null
    private data class OwnedParakeetLease(
        val generation: Long,
        val lease: ParakeetEngineLease
    )

    private var isRecording = false
    private var recorder: AudioRecord? = null
    private val routingHandler = Handler(Looper.getMainLooper())
    private var microphoneRouting: Pair<AudioRecord, MicrophoneRouting>? = null

    protected open fun microphoneRouteChanged(state: MicrophoneRouteState) {}

    fun selectMicrophone(device: MicrophoneDevice) {
        if (isRecording) microphoneRouting?.second?.select(device)
    }

    private fun failRecognition(error: Throwable, generation: Long) {
        liveBenchmarkSession?.failure("Recognition failed (${error.javaClass.simpleName})")
        if (generation != recognitionGeneration) return
        // End capture without clearing retained samples/history or restarting the utterance.
        cancelCapture()
        stopAndReleaseRecorder()
        isRecording = false
        unfocusAudio()
        microphoneRouting?.second?.close()
        microphoneRouting = null
        releaseModelSession(generation)
        failed(error)
    }

    private fun observeMicrophone(activeRecorder: AudioRecord, generation: Long) {
        routingHandler.post {
            if (generation != recognitionGeneration || recorder !== activeRecorder || !isRecording) return@post
            microphoneRouting?.second?.close()
            var lastCategories: List<Long>? = null
            val routing = MicrophoneRouting(AndroidMicrophonePlatform(context, activeRecorder)) { state ->
                if (generation == recognitionGeneration) {
                    // Categories only: no device IDs, names, addresses or platform exception text.
                    val categories = listOf(state.active?.kind?.ordinal?.toLong() ?: -1L,
                        state.pending?.kind?.ordinal?.toLong() ?: -1L,
                        state.failure?.ordinal?.toLong() ?: -1L)
                    if (state.recording && categories != lastCategories) {
                        diagnostics?.event(DiagnosticEvent.MICROPHONE_ROUTE_CHANGED, mapOf(
                            DiagnosticMetric.MICROPHONE_ACTIVE to categories[0],
                            DiagnosticMetric.MICROPHONE_PENDING to categories[1],
                            DiagnosticMetric.MICROPHONE_FAILURE to categories[2]))
                        lastCategories = categories
                    }
                    microphoneRouteChanged(state)
                }
            }
            microphoneRouting = activeRecorder to routing
            routing.start()
        }
    }

    private fun releaseMicrophone(activeRecorder: AudioRecord) {
        routingHandler.post {
            microphoneRouting?.takeIf { it.first === activeRecorder }?.let {
                it.second.close()
                microphoneRouting = null
            }
        }
    }
    @Volatile private var stopReason: StopReason? = null

    fun isCurrentlyRecording(): Boolean {
        return isRecording
    }

    private var backend: SpeechBackend? = null
    private var modelSession: Pair<Long, AutoCloseable>? = null
    private var backendGeneration = -1L
    private var parakeetLease: OwnedParakeetLease? = null
    @Volatile
    private var recognitionGeneration = 0L

    private var floatSamples: FloatBuffer = FloatBuffer.allocate(16000 * 30)
    private val waveform = RecordingWaveform()
    private var recorderJob: Job? = null
    private var modelJob: Job? = null
    private var loadModelJob: Job? = null
    private var personalVocabulary = ""
    private var backup: Pair<Long, AudioHistoryStore.Capture>? = null
    private var backupId: Pair<Long, String>? = null
    private class CaptureEvidence {
        var samples = 0L
        var vadFrames = 0L
        var speechFrames = 0L
        var drainSamples = 0L
        var drainRequestedMs: Long? = null
        var zeroReads = 0L
        var failedReads = 0L
    }
    private var captureEvidence: Pair<Long, CaptureEvidence>? = null

    private fun backupFailed(operation: Long = 0L, error: Throwable? = null) {
        runCatching { diagnostics?.event(DiagnosticEvent.HISTORY_OPERATION_FAILED,
            mapOf(DiagnosticMetric.OPERATION to operation), error = error) }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.audio_history_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBackupTranscript(id: String?, text: String) {
        if (id == null) return
        try {
            context.audioHistory().saveTranscript(id, text)
        } catch (error: Exception) {
            backupFailed(13L, error)
        }
    }
    private val streamingAudio = StreamingAudioReplay()
    private var selectedManagedModel: RecognitionModel? = null
    var selectedModelName: String? = null
        private set
    private val modelLifecycle by lazy {
        RecognitionModelLifecycle.create(context.filesDir, BuildConfig.BUNDLE_PARAKEET_MODEL)
    }

    private var canExpandSpace = true

    /** Transcripts of the audio before each SK | EN switch while speaking, in order. Guarded by this. */
    private class DecodedSegment(val text: String, val language: String?)
    private val decodedSegments = mutableListOf<DecodedSegment>()
    /** First sample not yet transcribed by an earlier model; the final model decodes from here. Guarded by this. */
    private var segmentStart = 0

    @Synchronized
    private fun clearCapturedSamples() {
        floatSamples.clear()
        waveform.clear()
        decodedSegments.clear()
        segmentStart = 0
    }

    @Synchronized
    private fun capturedSampleCount(): Int = floatSamples.position()

    @Synchronized
    private fun copyCapturedSamples(from: Int, to: Int): FloatArray = floatSamples.array().copyOfRange(from, to)

    @Synchronized
    private fun cancelCapture() {
        stopReason = StopReason.Cancel
    }

    private fun expandSpaceIfAllowed(): Boolean {
        if(canExpandSpace) {
            // Allocate an extra 30 seconds
            val newSampleBuffer = FloatBuffer.allocate(floatSamples.capacity() + 16000 * 30)
            newSampleBuffer.put(floatSamples.array(), 0, floatSamples.capacity() - floatSamples.remaining())
            floatSamples = newSampleBuffer
            return true
        }
        return false
    }


    protected abstract val context: Context
    protected abstract val lifecycleScope: LifecycleCoroutineScope

    protected abstract fun cancelled()
    protected abstract fun finished(result: String): Boolean
    protected abstract fun failed(error: Throwable)
    protected abstract fun languageDetected(result: String)
    protected abstract fun partialResult(result: String)
    protected abstract fun decodingStatus(status: RunState)

    protected abstract fun loading()
    protected abstract fun needParakeetModelDownload()
    protected abstract fun needRecognitionModelDownload(model: RecognitionModel)
    protected abstract fun needMoonshineModelDownload()
    protected abstract fun needWhisperModelDownload(models: List<ModelData>)
    protected abstract fun needPermission()
    protected abstract fun permissionRejected()

    protected abstract fun recordingStarted()
    protected open fun recordingInterrupted() {}
    protected abstract fun updateWaveform(bars: List<Pair<Float, Float>>, state: MagnitudeState)

    protected abstract fun processing()
    protected abstract fun cleaning()

    private var isVADPaused = false
    fun pauseVAD(v: Boolean) {
        isVADPaused = v
    }

    fun finishRecognizerIfRecording() {
        if (isRecording) {
            finishRecognizer()
        }
    }

    protected fun finishRecognizer() {
        println("Finish called")
        onFinishRecording()
    }

    fun cancelRecognizer() {
        liveBenchmarkSession?.failure("Reading cancelled")
        println("Cancelling recognition")
        diagnostics?.end(DiagnosticEvent.SESSION_CANCELLED)
        reset()

        cancelled()
    }

    @Synchronized
    private fun stopAndReleaseRecorder(target: AudioRecord? = recorder) {
        val current = target ?: return
        if (recorder !== current) {
            return
        }

        releaseMicrophone(current)

        try {
            if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                current.stop()
            }
        } catch(e: Exception) {
            Log.e("AudioRecognizer", "Recorder stop failed (${DiagnosticFailure.from(e).kind})")
        } finally {
            try {
                current.release()
            } catch(e: Exception) {
                Log.e("AudioRecognizer", "Recorder release failed (${DiagnosticFailure.from(e).kind})")
            } finally {
                if (recorder === current) {
                    recorder = null
                }
            }
        }
    }

    fun reset() {
        diagnostics?.end(DiagnosticEvent.SESSION_RESET)
        diagnosticSamplingJob?.cancel()
        isVADPaused = false
        cancelCapture()
        stopAndReleaseRecorder()
        val recorderJobToJoin: Job?
        val modelJobToJoin: Job?
        val loadModelJobToJoin: Job?
        val backendToClose: SpeechBackend?
        val parakeetLeaseToRelease: OwnedParakeetLease?
        val backupToClose: AudioHistoryStore.Capture?
        val modelSessionToRelease: AutoCloseable?
        synchronized(this) {
            modelSessionToRelease = modelSession?.second
            modelSession = null
            recognitionGeneration += 1
            backupToClose = backup?.second
            backup = null
            backupId = null
            recorderJobToJoin = recorderJob
            modelJobToJoin = modelJob
            loadModelJobToJoin = loadModelJob
            backendToClose = backend
            parakeetLeaseToRelease = parakeetLease
            recorderJob = null
            modelJob = null
            loadModelJob = null
            backend = null
            backendGeneration = -1L
            parakeetLease = null
            streamingAudio.reset()
            baseSelection = null
            sessionSelection = null
            routingLanguage = null
            switchableRuntimes = null
            modelSessionJob = null
        }
        recorderJobToJoin?.cancel()
        modelJobToJoin?.cancel()
        loadModelJobToJoin?.cancel()
        isRecording = false

        clearCapturedSamples()

        microphoneRouteChanged(MicrophoneRouteState())

        unfocusAudio()

        lifecycleScope.launch(NonCancellable) {
            recorderJobToJoin?.join()
            modelJobToJoin?.join()
            loadModelJobToJoin?.join()
            withContext(Dispatchers.IO) { runCatching { backupToClose?.close() } }
            try {
                if (parakeetLeaseToRelease != null) {
                    modelLifecycle.release(parakeetLeaseToRelease.lease, lifecycleScope)
                } else if (backendToClose != null) {
                    modelLifecycle.release(backendToClose, lifecycleScope)
                }
            } finally { modelSessionToRelease?.close() }
        }
    }

    protected fun openPermissionSettings() {
        val packageName = context.packageName
        val myAppSettings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(
                "package:$packageName"
            )
        )
        myAppSettings.addCategory(Intent.CATEGORY_DEFAULT)
        myAppSettings.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(myAppSettings)

        cancelRecognizer()
    }

    private var focusRequest: AudioFocusRequest? = null
    private fun focusAudio() {
        unfocusAudio()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .build()
                audioManager.requestAudioFocus(focusRequest!!)
            }
        }catch(e: Exception) {
            Log.e("AudioRecognizer", "Recorder setup failed (${DiagnosticFailure.from(e).kind})")
        }
    }
    private fun unfocusAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest!!)
                }
                focusRequest = null
            }
        }catch(e: Exception) {
            Log.e("AudioRecognizer", "Recorder setup failed (${DiagnosticFailure.from(e).kind})")
        }
    }

    internal suspend fun loadModelInner(
        loadBackend: suspend (Context, RecognitionModelSelection, RecognitionRuntimeCallbacks) -> SpeechBackend =
            { context, selection, callbacks ->
                // Dictation never writes the saved model choice; it only reads it (see language routing).
                modelLifecycle.load(context, selection, callbacks, persistSelection = false)
            }
    ) {
        val loadGeneration = recognitionGeneration
        val report = diagnostics
        val loadStarted = SystemClock.elapsedRealtime()
        try {
            val selection = liveBenchmarkSession?.selection ?: sessionSelection ?: savedSelection()
            runCatching {
                report?.event(DiagnosticEvent.MODEL_LOAD_STARTED, configuration = DiagnosticConfiguration(
                    runtime = selection.runtimeId,
                    modelVersion = modelLifecycle.readiness(selection)?.installedModel?.version,
                    runtimeVersion = if (selection.runtimeId in setOf("parakeet", "orukeet", "parakeet_ultra", "parakeet_sk", "parakeet_unified", "nemotron", "cohere"))
                        BuildConfig.SHERPA_ONNX_VERSION else null,
                    language = if (selection.runtimeId == "whisper_ggml") forcedLanguage else routingLanguage
                ))
            }
            val loadedBackend = if (liveBenchmarkSession != null) {
                modelLifecycle.acquireRuntime(selection, preserveInstallation = true) {
                    val independent = liveBenchmarkSession!!.model.backend(context, onPartial = { text ->
                        if (text.isNotBlank()) liveBenchmarkSession?.milestone("firstPartial")
                        lifecycleScope.launch(Dispatchers.Main) { partialResult(text) }
                    })
                    independent.loadOrCloseOnFailure { load(context) }
                    independent
                }
            } else loadBackend(
                context,
                selection,
                RecognitionRuntimeCallbacks(
                    onStatusUpdate = { status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (loadGeneration == recognitionGeneration) decodingStatus(status)
                        }
                    },
                    onPartialDecode = {
                        report?.partial(it.length)
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                if (loadGeneration == recognitionGeneration) partialResult(it)
                            }
                        }
                    },
                    forceLanguageProvider = { forcedLanguage }
                )
            )
            val published = synchronized(this) {
                if (loadGeneration != recognitionGeneration) {
                    false
                } else {
                    backend = loadedBackend
                    backendGeneration = loadGeneration
                    if (loadedBackend is ParakeetEngineLease) {
                        parakeetLease = OwnedParakeetLease(loadGeneration, loadedBackend)
                    }
                    true
                }
            }
            if (!published) {
                withContext(NonCancellable) {
                    modelLifecycle.release(loadedBackend, lifecycleScope)
                }
                throw CancellationException("Recognition was reset while the model loaded")
            }
            (loadedBackend as? StreamingSpeechBackend)?.let(::startStreaming)
            report?.event(DiagnosticEvent.MODEL_LOAD_FINISHED,
                mapOf(DiagnosticMetric.DURATION_MS to SystemClock.elapsedRealtime() - loadStarted))
        } catch (error: CancellationException) {
            throw error
        } catch(e: OutOfMemoryError) {
            report?.event(DiagnosticEvent.MODEL_LOAD_FAILED, error = e)
            if (loadGeneration != recognitionGeneration) {
                throw CancellationException("Recognition was reset while loading the model")
            }
            decodingStatus(RunState.OOMError)
            try {
                closeFailedBackend(loadGeneration)
            } catch (closeError: Exception) {
                e.addSuppressed(closeError)
            }
            withContext(Dispatchers.Main) { failRecognition(e, loadGeneration) }
        } catch (error: Exception) {
            report?.event(DiagnosticEvent.MODEL_LOAD_FAILED, error = error)
            if (loadGeneration == recognitionGeneration) {
                withContext(Dispatchers.Main) { failRecognition(error, loadGeneration) }
            }
        }
    }

    private fun loadModel() {
        if (backend == null) {
            loadModelJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    loadModelInner()
                }
            }
        }
    }

    private var forcedLanguage: String? = null
    fun forceLanguage(language: String?) {
        forcedLanguage = language
    }

    // Language routing (SK | EN button). Chooses the model per recording; SPEECH_BACKEND is never written.
    // Written on the main thread; read by the load and decode coroutines.
    @Volatile private var dictationLanguage: DictationLanguage? = null
    /** The saved model choice, read once per dictation in create(). */
    @Volatile private var baseSelection: RecognitionModelSelection? = null
    /** The model used for this dictation: [baseSelection] or the Slovak model. Null until create() commits it. */
    @Volatile private var sessionSelection: RecognitionModelSelection? = null
    /** ROUTE_BY_LANGUAGE is on and the Slovak model is installed (never during model comparison). */
    @Volatile private var routingAvailable = false
    /** "sk" or "en" for the Harper and S1 gates while routing is active. Whisper's forcedLanguage stays separate. */
    @Volatile private var routingLanguage: String? = null
    /** Runtimes the button may switch to in this dictation (installed); null until create() has checked. */
    @Volatile private var switchableRuntimes: Set<String>? = null
    /** Whisper's caption when the saved model is Whisper (it has no catalog entry). */
    private var whisperCaption: String? = null
    private var modelSessionJob: Job? = null

    private suspend fun savedSelection() = RecognitionModelSelection(
        runtimeId = context.getSetting(SPEECH_BACKEND),
        moonshineVariantId = context.getSetting(MOONSHINE_MODEL_VARIANT),
        nemotronVariantId = context.getSetting(NEMOTRON_PROFILE),
        asr4allVariantId = context.getSetting(ASR4ALL_VARIANT)
    )

    private fun routedSelection(language: DictationLanguage?): RecognitionModelSelection? {
        val base = baseSelection ?: return null
        val runtime = LanguageRouting.routeRuntime(base.runtimeId, routingAvailable, slovakInstalled = true, language)
        return if (runtime == base.runtimeId) base else RecognitionModelSelection(runtimeId = runtime)
    }

    /** Runtime of this dictation's model; model comparison keeps its own selection. */
    private suspend fun effectiveRuntimeId(): String =
        liveBenchmarkSession?.selection?.runtimeId ?: sessionSelection?.runtimeId ?: context.getSetting(SPEECH_BACKEND)

    private fun modelCaption(model: RecognitionModel?, backendType: SpeechBackendType): String? =
        if (backendType == SpeechBackendType.WhisperGGML) whisperCaption
        else model?.displayName?.let { name ->
            if (backendType == SpeechBackendType.Nemotron && !name.startsWith("Nemotron")) "Nemotron $name" else name
        }

    private suspend fun isInstalled(selection: RecognitionModelSelection): Boolean =
        if (selection.runtimeId.toSpeechBackendType() == SpeechBackendType.WhisperGGML) {
            context.selectedWhisperModelsForCurrentSettings(forcedLanguage).none { context.modelNeedsDownloading(it) }
        } else modelLifecycle.readiness(selection)?.isReady != false

    /** Applies the current SK | EN choice to this dictation's model state. Main thread, no suspension. */
    private fun commitSessionSelection(selection: RecognitionModelSelection) {
        val type = selection.runtimeId.toSpeechBackendType()
        val model = modelLifecycle.readiness(selection)?.model
        sessionSelection = selection
        routingLanguage = LanguageRouting.cleanupLanguage(routingAvailable, slovakInstalled = true, dictationLanguage)
        selectedManagedModel = model
        selectedModelName = modelCaption(model, type)
    }

    private fun isStreamingRuntime(backendType: SpeechBackendType) =
        backendType == SpeechBackendType.ParakeetUnified ||
            backendType == SpeechBackendType.Nemotron ||
            backendType == SpeechBackendType.Asr4all ||
            backendType == SpeechBackendType.Moonshine

    /** Whether the Slovak model is installed; the SK | EN button is only offered then. */
    fun slovakModelInstalled(): Boolean = runCatching {
        modelLifecycle.readiness(RecognitionModelSelection(runtimeId = LanguageRouting.SLOVAK_RUNTIME))?.isReady == true
    }.getOrDefault(false)

    protected open fun routedModelChanged(modelName: String?) {}

    /** Live-text models replay audio from the start of a recording, so they cannot be swapped in mid-recording. */
    fun dictationLanguageSwitchableWhileRecording(): Boolean = DictationLanguage.values().all { language ->
        routedSelection(language)?.runtimeId?.toSpeechBackendType()?.let { !isStreamingRuntime(it) } ?: true
    }

    /**
     * Sets the SK | EN choice for this dictation. Call on the main thread.
     * Before recording it only changes which model will load. During recording the old engine is
     * closed and the other model starts loading at once, while the user keeps talking.
     * Returns false when the switch is not possible now (after Stop, or a live-text model is involved).
     */
    fun setDictationLanguage(language: DictationLanguage?): Boolean {
        val next = routedSelection(language)
        val targets = switchableRuntimes
        if (next != null && targets != null && next.runtimeId !in targets) return false
        val previous = sessionSelection
        if (liveBenchmarkSession != null || previous == null || next == null) {
            // create() has not committed the model yet; it applies the latest language itself.
            dictationLanguage = language
            return true
        }
        val generation = recognitionGeneration
        val loading = synchronized(this) { loadModelJob != null || backendForGeneration(generation) != null }
        if (next == previous) {
            dictationLanguage = language
            routingLanguage = LanguageRouting.cleanupLanguage(routingAvailable, slovakInstalled = true, language)
            return true
        }
        val previousType = previous.runtimeId.toSpeechBackendType()
        val nextType = next.runtimeId.toSpeechBackendType()
        if (loading && (!isRecording || isStreamingRuntime(previousType) || isStreamingRuntime(nextType))) return false

        // Everything spoken before the tap stays with the model that was active then.
        val tapSample = capturedSampleCount()
        val previousLanguage = routingLanguage
        dictationLanguage = language
        commitSessionSelection(next)
        routedModelChanged(selectedModelName)
        if (!loading) streamingAudio.reset(isStreamingRuntime(nextType))
        swapModelSession(generation, selectedManagedModel)
        if (loading) reloadRoutedModel(generation, tapSample, previousLanguage)
        return true
    }

    /** Keeps model deletion blocked for the model this dictation now uses. */
    private fun swapModelSession(generation: Long, model: RecognitionModel?) {
        val pendingSession = modelSessionJob
        lifecycleScope.launch {
            // Wait until the dictation stored its first session, so the swap cannot be overwritten by it.
            pendingSession?.join()
            val lease = try { modelLifecycle.acquireSession(model) } catch (_: Exception) { return@launch }
            val old = synchronized(this@RecordingSession) {
                val current = modelSession
                if (generation == recognitionGeneration && current?.first == generation) {
                    modelSession = generation to lease
                    current.second
                } else lease // Recording never started or was reset.
            }
            old.close()
        }
    }

    /**
     * Transcribes the audio before the tap ([segmentStart] to a quiet point near [tapSample]) with the
     * model that was active then, so the next model only gets the rest. Keeps the audio for the next
     * model when there is too little of it or the decode fails, so nothing is dropped.
     */
    private suspend fun decodeSegmentBeforeSwitch(
        generation: Long,
        oldBackend: SpeechBackend,
        tapSample: Int,
        language: String?
    ) {
        val start = synchronized(this) { segmentStart }
        if (tapSample - start < SwitchSegments.MIN_SEGMENT_SAMPLES) return
        val audio = copyCapturedSamples(start, tapSample)
        val cut = SwitchSegments.quietCut(audio, start, tapSample)
        val text = try {
            oldBackend.transcribe(audio.copyOf(cut - start))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Includes OutOfMemoryError: the audio stays with the next model instead of being lost.
            if (error !is Exception && error !is OutOfMemoryError) throw error
            Log.e("AudioRecognizer", "Decoding before the model switch failed (${DiagnosticFailure.from(error).kind})")
            return
        }
        synchronized(this) {
            if (generation != recognitionGeneration || segmentStart != start) return
            decodedSegments += DecodedSegment(text, language)
            segmentStart = cut
        }
    }

    /** Releases this recording's engine and loads the routed model. Stop waits for this job. */
    private fun reloadRoutedModel(generation: Long, tapSample: Int, previousLanguage: String?) {
        synchronized(this) {
            val previousLoad = loadModelJob
            loadModelJob = lifecycleScope.launch {
                // reset() cancels only the latest job; take the earlier ones in the chain down with it.
                coroutineContext[Job]?.invokeOnCompletion { if (it is CancellationException) previousLoad?.cancel() }
                withContext(Dispatchers.Default) {
                    previousLoad?.join()
                    val (oldBackend, oldLease) = synchronized(this@RecordingSession) {
                        // A failed earlier load cancels the capture; do not load another engine for it.
                        if (generation != recognitionGeneration || stopReason == StopReason.Cancel) return@withContext
                        val loaded = backend.takeIf { backendGeneration == generation }
                        val lease = parakeetLease?.takeIf { it.generation == generation }
                        if (loaded != null) { backend = null; backendGeneration = -1L }
                        if (lease != null) parakeetLease = null
                        loaded to lease
                    }
                    try {
                        (oldBackend ?: oldLease?.lease)?.let { old ->
                            decodeSegmentBeforeSwitch(generation, old, tapSample, previousLanguage)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            // keepWarm = false: only one engine fits, so the old one is closed before the new load.
                            if (oldLease != null) modelLifecycle.release(oldLease.lease, lifecycleScope)
                            else if (oldBackend != null) modelLifecycle.release(oldBackend, lifecycleScope)
                        }
                    }
                    // The decode above takes seconds; a reset meanwhile must not load an engine for the next dictation.
                    if (generation != recognitionGeneration || stopReason == StopReason.Cancel) return@withContext
                    loadModelInner()
                }
            }
        }
    }

    fun create() {
        selectedModelName = null
        loading()

        lifecycleScope.launch {
            val base = liveBenchmarkSession?.selection ?: savedSelection()
            baseSelection = base
            routingAvailable = liveBenchmarkSession == null &&
                LanguageRouting.isActive(context.getSetting(ROUTE_BY_LANGUAGE), slovakModelInstalled())
            whisperCaption = if (base.runtimeId.toSpeechBackendType() == SpeechBackendType.WhisperGGML) {
                context.selectedWhisperModelsForCurrentSettings(forcedLanguage).joinToString(" / ") {
                    it.name.substringBefore(" (")
                }
            } else null
            switchableRuntimes = if (routingAvailable) {
                DictationLanguage.values().mapNotNull { routedSelection(it) }.distinct()
                    .filter { isInstalled(it) }.map { it.runtimeId }.toSet()
            } else null
            // The model checks below use the language chosen so far; a tap meanwhile is committed after them.
            val selection = if (liveBenchmarkSession != null) base else routedSelection(dictationLanguage) ?: base
            val backendType = selection.runtimeId.toSpeechBackendType()
            diagnostics?.end(DiagnosticEvent.SESSION_RESET)
            diagnosticSamplingJob?.cancel()
            personalVocabulary = if (liveBenchmarkSession != null) "" else context.getSetting(PERSONAL_DICTIONARY)
            val readiness = modelLifecycle.readiness(selection)
            selectedManagedModel = readiness?.model
            selectedModelName = modelCaption(readiness?.model, backendType)
            val report = AppDiagnostics.session(readiness?.model?.id ?: backendType.id)
            diagnostics = report
            report?.event(DiagnosticEvent.SESSION_STARTED)
            diagnosticSamplingJob = report?.let { activeReport ->
                lifecycleScope.launch {
                    AppDiagnostics.sample(activeReport.id, activeReport.model)
                    while (!activeReport.terminal) {
                        delay(5_000L)
                        if (!activeReport.terminal) AppDiagnostics.sample(activeReport.id, activeReport.model, detailed = true)
                    }
                }
            }
            if (readiness != null && !readiness.isReady) {
                report?.event(DiagnosticEvent.MODEL_REQUIRED)
                needRecognitionModelDownload(readiness.model)
                return@launch
            }
            streamingAudio.reset(isStreamingRuntime(backendType))
            if (backendType == SpeechBackendType.WhisperGGML) {
                    val requiredModels = liveBenchmarkSession?.model?.whisper?.let { listOf(it) }
                        ?: context.selectedWhisperModelsForCurrentSettings(forcedLanguage)
                    selectedModelName = requiredModels.joinToString(" / ") {
                        it.name.substringBefore(" (")
                    }
                    if (requiredModels.any { context.modelNeedsDownloading(it) }) {
                        report?.event(DiagnosticEvent.MODEL_REQUIRED)
                        needWhisperModelDownload(requiredModels)
                        return@launch
                    }
            }

            // No suspension from here on: commit the model for the latest SK | EN choice.
            if (liveBenchmarkSession != null) {
                sessionSelection = selection
            } else {
                val committed = routedSelection(dictationLanguage)?.takeIf {
                    it == selection || switchableRuntimes?.contains(it.runtimeId) == true
                } ?: selection
                commitSessionSelection(committed)
                if (committed != selection) {
                    streamingAudio.reset(isStreamingRuntime(committed.runtimeId.toSpeechBackendType()))
                    routedModelChanged(selectedModelName)
                }
            }

            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                report?.event(DiagnosticEvent.PERMISSION_REQUIRED)
                needPermission()
            } else {
                startRecordingWithModelSession()
            }
        }
    }

    fun permissionResultGranted() {
        startRecordingWithModelSession()
    }

    fun permissionResultRejected() {
        diagnostics?.event(DiagnosticEvent.PERMISSION_REJECTED)
        permissionRejected()
    }

    private fun readRecorder(activeRecorder: AudioRecord, samples: ShortArray, mode: Int): Int = try {
        activeRecorder.read(samples, 0, AUDIO_READ_SIZE, mode)
    } catch (_: RuntimeException) {
        AudioRecord.ERROR_INVALID_OPERATION
    }

    private fun releaseModelSession(generation: Long) {
        synchronized(this) {
            modelSession?.takeIf { it.first == generation }?.let {
                modelSession = null
                it.second.close()
            }
        }
    }

    private fun startRecordingWithModelSession() {
        val generation = recognitionGeneration
        modelSessionJob = lifecycleScope.launch {
            val lease = try { modelLifecycle.acquireSession(selectedManagedModel) } catch (failure: Exception) {
                failRecognition(failure, generation)
                return@launch
            }
            val current = synchronized(this@RecordingSession) {
                if (generation != recognitionGeneration || modelSession != null) false else {
                    lease?.let { modelSession = generation to it }
                    true
                }
            }
            if (!current) { lease?.close(); return@launch }
            try { startRecording() } catch (failure: Throwable) {
                releaseModelSession(generation)
                throw failure
            }
        }
    }

    private fun startRecording(numTries: Int = 0) {
        val report = diagnostics
        if (BuildConfig.DEBUG) Log.d("WaveformTiming", "recorder_requested t=${SystemClock.elapsedRealtime()}")
        if (isRecording) {
            throw IllegalStateException("Start recording when already recording")
        }

        isVADPaused = false
        stopReason = null
        clearCapturedSamples()
        canExpandSpace = true

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AUDIO_SAMPLE_RATE * 2 * 5
            )

            if(recorder!!.state == AudioRecord.STATE_UNINITIALIZED) {
                recorder!!.release()
                recorder = null

                println("Failed to initialize AudioRecord, retrying")
                report?.event(DiagnosticEvent.RECORDER_RETRY,
                    mapOf(DiagnosticMetric.ATTEMPT to numTries.toLong()))

                if(!RecordingSessionPolicy.shouldRetryRecorderInitialization(numTries)) {
                    throw IllegalStateException("AudioRecord could not be initialized in 32 tries")
                }

                return startRecording(numTries + 1)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    recorder!!.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                }
            } catch(e: Exception) {
                println("Failed to set preferred mic direction")
                Log.e("AudioRecognizer", "Recorder start failed (${DiagnosticFailure.from(e).kind})")
            }

            recorder!!.startRecording()
            report?.event(DiagnosticEvent.RECORDER_STARTED)
            if (BuildConfig.DEBUG) Log.d("WaveformTiming", "recorder_started t=${SystemClock.elapsedRealtime()}")
            val activeRecorder = recorder!!
            val captureGeneration = recognitionGeneration
            val evidence = CaptureEvidence()
            captureEvidence = captureGeneration to evidence

            focusAudio()
            isRecording = true
            observeMicrophone(activeRecorder, captureGeneration)

            (backend as? StreamingSpeechBackend)?.let(::startStreaming)

            val canMicBeBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(SensorPrivacyManager::class.java) as SensorPrivacyManager).supportsSensorToggle(
                    SensorPrivacyManager.Sensors.MICROPHONE
                )
            } else {
                false
            }

            recorderJob = lifecycleScope.launch {
                withContext(Dispatchers.Default) {
                    canExpandSpace = context.getSetting(ENABLE_30S_LIMIT) == false
                    val backendType = effectiveRuntimeId().toSpeechBackendType()
                    val shouldUseVad = context.getSetting(IS_VAD_ENABLED) &&
                        (backendType != SpeechBackendType.Parakeet || context.getSetting(PARAKEET_USE_VAD))
                    val endOfSpeechProfile = context.getSetting(END_OF_SPEECH_PROFILE).toEndOfSpeechProfile()
                    liveBenchmarkSession?.configuration(mapOf(
                        "vadEnabled" to shouldUseVad.toString(), "endOfSpeechProfile" to endOfSpeechProfile.toString(),
                        "durationLimit" to if (canExpandSpace) "180 seconds for benchmark" else "30 seconds",
                        "cleanup" to "disabled; ASR4ALL inseparable built-in processing disclosed",
                        "language" to "en", "personalVocabulary" to "disabled"))

                    var hasTalked = false
                    var anyNoiseAtAll = false
                    var isMicBlocked = false

                    val vad = VadWebRTC(
                        sampleRate = SampleRate.SAMPLE_RATE_16K,
                        frameSize = FrameSize.FRAME_SIZE_480,
                        mode = if (backendType == SpeechBackendType.Parakeet) Mode.AGGRESSIVE else Mode.VERY_AGGRESSIVE,
                        speechDurationMs = 150,
                        silenceDurationMs = 300
                    )
                    
                    val vadSampleBuffer = ShortBuffer.allocate(480)
                    var numConsecutiveNonSpeech = 0
                    var numConsecutiveSpeech = 0

                    val samples = ShortArray(AUDIO_READ_SIZE)
                    var firstRead = true
                    var firstUpdate = true

                    val capture = try {
                        val store = context.audioHistory()
                        store.purge(context.getSetting(AUDIO_HISTORY_RETENTION_HOURS))
                        if (liveBenchmarkSession == null && context.getSetting(AUDIO_HISTORY_ENABLED)) store.begin() else null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        backupFailed(10L, error)
                        null
                    }

                    try {
                        synchronized(this@RecordingSession) {
                            if (captureGeneration == recognitionGeneration) {
                                backup = capture?.let { captureGeneration to it }
                                backupId = capture?.let { captureGeneration to it.id }
                            }
                        }
                        captureLoop@ while(stopReason == null && activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING){
                            yield()
                            val nRead = readRecorder(activeRecorder, samples, AudioRecord.READ_BLOCKING)

                            if(nRead <= 0) {
                                if (nRead == 0) evidence.zeroReads++ else evidence.failedReads++
                                if (RecordingSessionPolicy.shouldReportRecorderReadFailure(nRead,
                                    stopReason != null || captureGeneration != recognitionGeneration || report?.terminal == true)) {
                                    report?.event(DiagnosticEvent.RECORDER_READ_FAILED,
                                        mapOf(DiagnosticMetric.ERROR_CODE to nRead.toLong()))
                                }
                                break
                            }
                            if (firstRead) {
                                firstRead = false
                                report?.event(DiagnosticEvent.FIRST_AUDIO)
                                if (BuildConfig.DEBUG) Log.d("WaveformTiming", "first_samples t=${SystemClock.elapsedRealtime()}")
                            }
                            yield()

                            // Persist before VAD or streaming recognition can fail.
                            val appendResult = appendSamples(samples, nRead, captureGeneration)
                            if (appendResult == AppendResult.SessionEnded) break
                            if (appendResult == AppendResult.DurationLimit) {
                                withContext(Dispatchers.Main) {
                                    if (isRecording && captureGeneration == recognitionGeneration) finishRecognizer()
                                }
                                break
                            }

                            // Run VAD
                            if(shouldUseVad && !isVADPaused) {
                                var remainingSamples = nRead
                                var offset = 0
                                while(remainingSamples > 0) {
                                    if(!vadSampleBuffer.hasRemaining()) {
                                        val isSpeech = vad.isSpeech(vadSampleBuffer.array())
                                        evidence.vadFrames++
                                        if (isSpeech) evidence.speechFrames++
                                        if (isSpeech) liveBenchmarkSession?.speech()
                                        vadSampleBuffer.clear()
                                        vadSampleBuffer.rewind()

                                        if(!isSpeech) {
                                            numConsecutiveNonSpeech++
                                            numConsecutiveSpeech = 0
                                        } else {
                                            numConsecutiveNonSpeech = 0
                                            numConsecutiveSpeech++
                                        }
                                    }

                                    val samplesToRead = min(min(remainingSamples, 480), vadSampleBuffer.remaining())
                                    for(i in 0 until samplesToRead) {
                                        vadSampleBuffer.put(samples[offset])
                                        offset += 1
                                        remainingSamples -= 1
                                    }
                                }
                            } else {
                                numConsecutiveNonSpeech = 0
                            }

                            if(stopReason != null) break

                            // Don't set hasTalked if the start sound may still be playing, otherwise on some
                            // devices the rms just explodes and `hasTalked` is always true
                            val startSoundPassed = (floatSamples.position() > AUDIO_SAMPLE_RATE*0.6)
                            if(!startSoundPassed){
                                numConsecutiveSpeech = 0
                                numConsecutiveNonSpeech = 0
                            }

                            val rms = sqrt(samples.sumOf { ((it.toFloat() / Short.MAX_VALUE.toFloat()).pow(2)).toDouble() } / samples.size).toFloat()

                            if(startSoundPassed && ((rms > 0.01) || (numConsecutiveSpeech > 8))) hasTalked = true

                            if(rms > 0.0001){
                                anyNoiseAtAll = true
                                isMicBlocked = false
                            }

                            // Check if mic is blocked
                            if(!anyNoiseAtAll && canMicBeBlocked && (floatSamples.position() > 2*AUDIO_SAMPLE_RATE)){
                                isMicBlocked = true
                            }

                            // End if VAD hasn't detected speech in a while
                            if(shouldUseVad && hasTalked && (numConsecutiveNonSpeech > endOfSpeechProfile.silenceFrames)) {
                                stopReason = StopReason.Vad
                                withContext(Dispatchers.Main){
                                    if(isRecording) {
                                        finishRecognizer()
                                    }
                                }
                                break
                            }

                            val state = if (!canExpandSpace && floatSamples.remaining() < (AUDIO_SAMPLE_RATE * 5)) {
                                MagnitudeState.ENDING_SOON_30S
                            } else if(hasTalked && shouldUseVad && (numConsecutiveNonSpeech > 33)) {
                                MagnitudeState.ENDING_SOON_VAD
                            } else if(hasTalked) {
                                MagnitudeState.TALKING
                            } else if(isMicBlocked) {
                                MagnitudeState.MIC_MAY_BE_BLOCKED
                            } else {
                                MagnitudeState.NOT_TALKED_YET
                            }

                            yield()
                            withContext(Dispatchers.Main) {
                                yield()
                                if(isRecording && captureGeneration == recognitionGeneration) {
                                    if (firstUpdate) {
                                        firstUpdate = false
                                        if (BuildConfig.DEBUG) Log.d("WaveformTiming", "first_update t=${SystemClock.elapsedRealtime()}")
                                    }
                                    updateWaveform(synchronized(this@RecordingSession) { waveform.snapshot() }, state)
                                }
                            }

                            // Skip ahead as much as possible, in case we are behind (taking more than
                            // 100ms to process 100ms)
                            while(stopReason == null){
                                yield()
                                val nRead2 = readRecorder(activeRecorder, samples, AudioRecord.READ_NON_BLOCKING)
                                if(nRead2 > 0) {
                                    when (appendSamples(samples, nRead2, captureGeneration)) {
                                        AppendResult.Accepted -> Unit
                                        AppendResult.SessionEnded -> break@captureLoop
                                        AppendResult.DurationLimit -> {
                                            yield()
                                            withContext(Dispatchers.Main){
                                                if(isRecording && captureGeneration == recognitionGeneration) {
                                                    finishRecognizer()
                                                }
                                            }
                                            break@captureLoop
                                        }
                                    }
                                } else {
                                    break
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (stopReason == null && captureGeneration == recognitionGeneration && isRecording) {
                                stopReason = StopReason.CaptureFailed
                                recordingInterrupted()
                                finishRecognizer()
                            }
                        }
                        val reason = stopReason
                        if(reason != null && reason != StopReason.Cancel && reason != StopReason.CaptureFailed &&
                            activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            drainRecorderTail(activeRecorder, reason, samples, captureGeneration)
                            appendFinalSilence(captureGeneration)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        report?.event(DiagnosticEvent.RECORDER_FAILED, error = error)
                        throw error
                    } finally {
                        runCatching {
                        report?.event(DiagnosticEvent.CAPTURE_SUMMARY, mapOf(
                            DiagnosticMetric.CAPTURED_SAMPLES to evidence.samples,
                            DiagnosticMetric.VAD_FRAMES to evidence.vadFrames,
                            DiagnosticMetric.VAD_SPEECH_FRAMES to evidence.speechFrames,
                            DiagnosticMetric.DRAIN_SAMPLES to evidence.drainSamples,
                            DiagnosticMetric.ZERO_READS to evidence.zeroReads,
                            DiagnosticMetric.READ_FAILURES to evidence.failedReads,
                            DiagnosticMetric.STOPPING_SILENCE_FRAMES to numConsecutiveNonSpeech.toLong(),
                            DiagnosticMetric.STOP_REASON to (stopReason?.ordinal ?: -1).toLong()) +
                            listOfNotNull(evidence.drainRequestedMs?.let { DiagnosticMetric.DRAIN_REQUESTED_MS to it }).toMap(),
                            configuration = DiagnosticConfiguration(vadEnabled = shouldUseVad, durationLimitEnabled = !canExpandSpace))
                        }
                        try {
                            capture?.finishWriting()
                            if (stopReason == null || stopReason == StopReason.Cancel ||
                                captureGeneration != recognitionGeneration) capture?.close()
                        } catch (error: Exception) {
                            runCatching { capture?.close() }
                            backupFailed(12L, error)
                        }
                        stopAndReleaseRecorder(activeRecorder)
                    }
                }
            }

            // We can only load model now, because the model loading may fail and need to cancel
            // everything we just did.
            // TODO: We could check if the model exists before doing all this work
            loadModel()

            liveBenchmarkSession?.milestone("recordingStarted")
            recordingStarted()
        } catch(e: SecurityException){
            report?.event(DiagnosticEvent.RECORDER_FAILED, error = e)
            releaseModelSession(recognitionGeneration)
            stopAndReleaseRecorder()
            // It's possible we may have lost permission, so let's just ask for permission again
            needPermission()
        } catch(e: Exception) {
            report?.event(DiagnosticEvent.RECORDER_FAILED, error = e)
            stopAndReleaseRecorder()
            throw e
        }
    }

    @Synchronized
    private fun appendSamples(samples: ShortArray, nRead: Int, captureGeneration: Long): AppendResult {
        if (!RecordingSessionPolicy.shouldAcceptSamples(stopReason, captureGeneration, recognitionGeneration)) {
            return AppendResult.SessionEnded
        }

        backup?.takeIf { it.first == captureGeneration }?.second?.let { capture ->
            try {
                capture.append(samples, nRead)
            } catch (error: Exception) {
                backup = null
                runCatching { capture.close() }
                backupFailed(11L, error)
            }
        }

        if(floatSamples.remaining() < nRead && !expandSpaceIfAllowed()) {
            stopReason = StopReason.DurationLimit
            return AppendResult.DurationLimit
        }
        if (liveBenchmarkSession != null && floatSamples.position() + nRead > 16000 * 180) {
            stopReason = StopReason.DurationLimit
            return AppendResult.DurationLimit
        }

        val streamingChunk = if (streamingAudio.isEnabled()) FloatArray(nRead) else null
        captureEvidence?.takeIf { it.first == captureGeneration }?.second?.let { it.samples += nRead }
        for(i in 0 until nRead) {
            // PCM16 is asymmetric: -32768 must remain within the recognizers' [-1, 1] range.
            val sample = samples[i].toFloat() / 32768f
            floatSamples.put(sample)
            streamingChunk?.set(i, sample)
        }
        streamingChunk?.let(streamingAudio::acceptAudio)
        waveform.append(samples, nRead)

        return AppendResult.Accepted
    }

    private fun startStreaming(backend: StreamingSpeechBackend) {
        val report = diagnostics
        val streamGeneration = recognitionGeneration
        streamingAudio.start(
            backend = backend,
            onPartial = { result ->
                report?.partial(result.length)
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        if (streamGeneration == recognitionGeneration) {
                            if (result.isNotBlank()) liveBenchmarkSession?.milestone("firstPartial")
                            partialResult(PersonalVocabulary.apply(result, personalVocabulary))
                        }
                    }
                }
            },
            onCatchingUp = { catchingUp ->
                report?.event(DiagnosticEvent.CATCHING_UP,
                    mapOf(DiagnosticMetric.CATCHING_UP to if (catchingUp) 1L else 0L), detailed = true)
                lifecycleScope.launch(Dispatchers.Main) {
                    if (streamGeneration == recognitionGeneration) {
                        decodingStatus(if (catchingUp) RunState.CatchingUp else RunState.Streaming)
                    }
                }
            }
        )
    }

    private suspend fun drainRecorderTail(
        recorder: AudioRecord,
        reason: StopReason,
        scratch: ShortArray,
        captureGeneration: Long
    ) {
        val drainMs = RecordingSessionPolicy.tailDrainMs(
            reason,
            effectiveRuntimeId().toSpeechBackendType(),
            context.getSetting(MANUAL_STOP_DRAIN_MS)
        )
        captureEvidence?.takeIf { it.first == captureGeneration }?.second?.drainRequestedMs = drainMs

        val deadline = System.currentTimeMillis() + drainMs
        while(drainMs > 0L && System.currentTimeMillis() < deadline && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            yield()
            val nRead = recorder.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
            if(nRead > 0) {
                if(appendSamples(scratch, nRead, captureGeneration) != AppendResult.Accepted) {
                    return
                }
                captureEvidence?.takeIf { it.first == captureGeneration }?.second?.let { it.drainSamples += nRead }
            } else {
                delay(10L)
            }
        }
    }

    @Synchronized
    private fun appendFinalSilence(captureGeneration: Long) {
        if (!RecordingSessionPolicy.shouldAcceptSamples(stopReason, captureGeneration, recognitionGeneration)) return

        val silenceSamples = (AUDIO_SAMPLE_RATE * FINAL_SILENCE_PAD_MS) / 1000
        if(floatSamples.remaining() < silenceSamples && !expandSpaceIfAllowed()) {
            return
        }

        repeat(silenceSamples) {
            floatSamples.put(0.0f)
        }
        (backend as? StreamingSpeechBackend)?.acceptAudio(FloatArray(silenceSamples))
    }

    private suspend fun runModel() {
        val runGeneration = recognitionGeneration
        val report = diagnostics
        val runLoadModelJob = synchronized(this) {
            if (runGeneration == recognitionGeneration) loadModelJob else null
        }
        try {
            runModelInner(runGeneration, runLoadModelJob)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error !is Exception && error !is OutOfMemoryError) throw error
            if (runGeneration != recognitionGeneration) {
                return
            }
            Log.e("AudioRecognizer", "Speech recognition failed (${DiagnosticFailure.from(error).kind})")
            report?.end(DiagnosticEvent.SESSION_FAILED, error)
            try {
                closeFailedBackend(runGeneration)
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            withContext(Dispatchers.Main) {
                failRecognition(error, runGeneration)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    releaseParakeetLease(runGeneration, keepWarm = false)
                } finally {
                    val capture = synchronized(this@RecordingSession) {
                        backup?.takeIf { it.first == runGeneration }?.second
                    }
                    withContext(Dispatchers.IO) { runCatching { capture?.close() } }
                    releaseModelSession(runGeneration)
                }
            }
        }
    }

    private suspend fun runModelInner(runGeneration: Long, runLoadModelJob: Job?) {
        val report = diagnostics
        val savedAudioId = synchronized(this) { backupId?.takeIf { it.first == runGeneration }?.second }
        if(runLoadModelJob != null && runLoadModelJob.isActive) {
            println("Model was not finished loading...")
            runLoadModelJob.join()
        }
        if (runGeneration != recognitionGeneration || stopReason == StopReason.Cancel) {
            throw CancellationException("Recognition ended before the model became ready")
        }

        var runBackend = backendForGeneration(runGeneration)
        if(runBackend == null) {
            if (runGeneration != recognitionGeneration) {
                throw CancellationException("Recognition was reset before the model became ready")
            }
            println("Model was null by the time runModel was called...")
            loadModel()
            val retryLoadJob = synchronized(this) { loadModelJob }
            retryLoadJob?.join()
            runBackend = backendForGeneration(runGeneration)
        }
        runBackend ?: throw IllegalStateException("Model did not load for this recognition")

        val finalStopReason = stopReason
        val backendType = effectiveRuntimeId().toSpeechBackendType()
        // Whisper keeps its forced language first; for other models the SK | EN choice wins over the old picker.
        // Parts spoken before an SK | EN switch were already transcribed by the model active then.
        val (earlierSegments, finalStart) = synchronized(this) { decodedSegments.toList() to segmentStart }
        val routedLanguage = if (earlierSegments.isEmpty()) routingLanguage
            else SwitchSegments.cleanupLanguage(earlierSegments.map { it.language }, routingLanguage)
        val cleanupLanguage = if (backendType == SpeechBackendType.WhisperGGML) forcedLanguage ?: routedLanguage
            else routedLanguage ?: forcedLanguage
        val vadEnabled = context.getSetting(IS_VAD_ENABLED) &&
            (backendType != SpeechBackendType.Parakeet || context.getSetting(PARAKEET_USE_VAD))
        val sampleCount = capturedSampleCount()
        val finalizeRecording: suspend () -> String = if (earlierSegments.isEmpty() || runBackend is StreamingSpeechBackend) {
            runBackend.prepareRecordingFinalization(floatSamples)
        } else {
            val rest = copyCapturedSamples(finalStart, sampleCount)
            val decodeRest: suspend () -> String = { if (rest.isEmpty()) "" else runBackend.transcribe(rest) }
            decodeRest
        }
        if (BuildConfig.DEBUG) {
            var sumSquares = 0.0
            var peak = 0.0f
            for (index in 0 until sampleCount) {
                val sample = floatSamples[index]
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
                sumSquares += (sample * sample).toDouble()
            }
            val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount).toFloat() else 0.0f
            Log.d(
                "AudioRecognizer",
                "capture samples=$sampleCount durationSec=${"%.2f".format(sampleCount.toFloat() / AUDIO_SAMPLE_RATE)} rms=${"%.5f".format(rms)} peak=${"%.5f".format(peak)} stopReason=$finalStopReason backend=$backendType vadEnabled=$vadEnabled"
            )
        }

        yield()
        var validEmpty = false
        var transcriptCapture: S1MiniTranscriptCapture? = null
        val text = run {
            val recognitionStarted = SystemClock.elapsedRealtime()
            report?.event(DiagnosticEvent.RECOGNITION_STARTED,
                mapOf(DiagnosticMetric.AUDIO_MS to sampleCount.toLong() * 1000 / AUDIO_SAMPLE_RATE))
            val rawText = SwitchSegments.join(earlierSegments.map { it.text } + finalizeRecording())
            liveBenchmarkSession?.raw(rawText)
            if (liveBenchmarkSession != null) return@run rawText
            report?.event(DiagnosticEvent.RECOGNITION_FINISHED, mapOf(
                DiagnosticMetric.DURATION_MS to SystemClock.elapsedRealtime() - recognitionStarted,
                DiagnosticMetric.CHARACTERS to rawText.length.toLong()),
                configuration = DiagnosticConfiguration(language = runBackend.detectedLanguage))
            saveBackupTranscript(savedAudioId, rawText)
            val finalized = TranscriptFinalizer.clean(context, rawText, backendType,
                runBackend.detectedLanguage, cleanupLanguage, personalVocabulary, report,
                onCleaning = { withContext(Dispatchers.Main) { cleaning() } })
            validEmpty = finalized.validEmpty
            transcriptCapture = finalized.capture
            finalized.text
        }

        if (runGeneration != recognitionGeneration) {
            throw CancellationException("Recognition was reset while decoding")
        }

        saveBackupTranscript(savedAudioId, text)

        val keepWarmEnabled = context.getSetting(PARAKEET_KEEP_WARM)
        val timeoutMs = context.getSetting(PARAKEET_KEEP_WARM_TIMEOUT_MS)
        modelLifecycle.release(runBackend, lifecycleScope, keepWarmEnabled, timeoutMs)
        synchronized(this) {
            if (parakeetLease?.generation == runGeneration) parakeetLease = null
        }
        clearBackend(runGeneration, runBackend)

        var delivery = S1MiniTranscriptDelivery.Canceled
        try {
            withContext(Dispatchers.Main) {
                if (runGeneration != recognitionGeneration) return@withContext
                runBackend.detectedLanguage?.let(::languageDetected)
                if (text.isBlank() && !validEmpty) {
                    delivery = S1MiniTranscriptDelivery.Prepared
                    report?.event(DiagnosticEvent.NO_SPEECH)
                    failRecognition(NoSpeechRecognizedException(), runGeneration)
                } else {
                    report?.event(DiagnosticEvent.RESULT_READY,
                        mapOf(DiagnosticMetric.CHARACTERS to text.length.toLong()))
                    delivery = S1MiniTranscriptDelivery.Rejected
                    delivery = if (finished(text)) S1MiniTranscriptDelivery.Accepted else S1MiniTranscriptDelivery.Rejected
                }
            }
        } finally {
            // A successful IME commit may synchronously reset/cancel this coroutine.
            val captureToSave = transcriptCapture
            if (delivery != S1MiniTranscriptDelivery.Canceled && captureToSave != null) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (context.getSetting(S1_MINI_TRANSCRIPT_DIAGNOSTICS)) {
                                S1MiniDiagnostics.recordTranscript(context, captureToSave.withDelivery(delivery))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun closeFailedBackend(generation: Long) {
        val backendToClose = backendForGeneration(generation)
        if (!releaseParakeetLease(generation, keepWarm = false)) {
            backendToClose?.let { modelLifecycle.release(it, lifecycleScope) }
        }
        clearBackend(generation, backendToClose)
    }

    private suspend fun releaseParakeetLease(
        generation: Long,
        keepWarm: Boolean,
        timeoutMs: Long = 0L
    ): Boolean {
        val ownedLease = synchronized(this) {
            parakeetLease?.takeIf { it.generation == generation }?.also {
                parakeetLease = null
            }
        } ?: return false
        modelLifecycle.release(ownedLease.lease, lifecycleScope, keepWarm, timeoutMs)
        return true
    }

    private fun backendForGeneration(generation: Long): SpeechBackend? = synchronized(this) {
        backend.takeIf { backendGeneration == generation }
    }

    private fun clearBackend(generation: Long, expectedBackend: SpeechBackend?) {
        synchronized(this) {
            if (backendGeneration == generation && backend === expectedBackend) {
                backend = null
                backendGeneration = -1L
            }
        }
    }

    private fun onFinishRecording() {
        if(!isRecording) {
            throw IllegalStateException("Should not call onFinishRecording when not recording")
        }

        isRecording = false

        unfocusAudio()
        if(stopReason == null) {
            stopReason = StopReason.Manual
        }
        diagnostics?.event(DiagnosticEvent.RECORDING_STOPPED, mapOf(
            DiagnosticMetric.STOP_REASON to (stopReason?.ordinal ?: -1).toLong()))
        liveBenchmarkSession?.milestone("captureStopped")
        if (stopReason == StopReason.Manual) liveBenchmarkSession?.milestone("stopPressed")

        processing()

        modelJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                recorderJob?.join()
                runModel()
            }
        }
    }
}

abstract class AudioRecognizer : RecordingSession()
