package org.futo.voiceinput

import org.futo.voiceinput.diagnostics.DiagnosticFailure

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION
import android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import org.futo.voiceinput.ml.RunState
import org.futo.voiceinput.recognition.RecognitionModel
import org.futo.voiceinput.settings.ENABLE_SOUND
import org.futo.voiceinput.settings.DICTATION_LANGUAGE
import org.futo.voiceinput.settings.ROUTE_BY_LANGUAGE
import org.futo.voiceinput.settings.setSetting
import org.futo.voiceinput.diagnostics.DiagnosticLanguageProbe
import org.futo.voiceinput.diagnostics.KeyboardLanguageProbe
import org.futo.voiceinput.recognition.DictationLanguage
import org.futo.voiceinput.recognition.LanguageRouting
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import org.futo.voiceinput.settings.LANGUAGE_TOGGLES
import org.futo.voiceinput.settings.MANUALLY_SELECT_LANGUAGE
import org.futo.voiceinput.settings.SETTINGS_DESTINATION_EXTRA
import org.futo.voiceinput.settings.SettingsActivity
import org.futo.voiceinput.settings.VERBOSE_PROGRESS
import org.futo.voiceinput.settings.getSetting
import org.futo.voiceinput.theme.Typography

fun Modifier.recognizerSurfaceClickable(disabled: Boolean, onPauseVAD: (Boolean) -> Unit, onFinish: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val ripple = ripple(bounded = false)

    this.pointerInput(disabled) {
            detectTapGestures(onPress = { offset ->
                if(!disabled) {
                    val press = PressInteraction.Press(offset)

                    interactionSource.emit(press)
                    onPauseVAD(true)
                    val didRelease = this.tryAwaitRelease()
                    onPauseVAD(false)

                    if (didRelease) {
                        interactionSource.emit(PressInteraction.Release(press))
                        onFinish()
                    } else {
                        interactionSource.emit(PressInteraction.Cancel(press))
                    }
                }
            })
        }.indication(interactionSource, ripple).semantics(mergeDescendants = true) {
            if (!disabled) {
                role = Role.Button
                onClick {
                    onFinish()
                    true
                }
            }
        }
}

@Composable
fun InnerRecognize(
    bars: List<Pair<Float, Float>> = emptyList(),
    state: MagnitudeState = MagnitudeState.MIC_MAY_BE_BLOCKED,
    compact: Boolean = false,
    onDrawn: () -> Unit = {}
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recognition-waveform")
            .height(if (compact) 48.dp else 120.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val middle = size.height / 2f
        val count = (size.width / 8.dp.toPx()).toInt().coerceIn(1, 48)
        val step = size.width / count
        recordingBarAmplitudes(bars, count).forEachIndexed { index, amplitude ->
            val x = (index + 0.5f) * step
            // Fixed gain keeps quiet noise small; zero PCM leaves a row of short bars.
            val halfHeight = (amplitude * 4f).coerceAtMost(1f) * (middle - 2.dp.toPx()).coerceAtLeast(0f)
            drawLine(
                color,
                Offset(x, middle - halfHeight),
                Offset(x, middle + halfHeight),
                3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        onDrawn()
    }

    val text = when (state) {
        MagnitudeState.NOT_TALKED_YET -> stringResource(R.string.try_saying_something)
        MagnitudeState.MIC_MAY_BE_BLOCKED -> stringResource(R.string.no_audio_detected_is_your_microphone_blocked)
        MagnitudeState.TALKING -> stringResource(R.string.listening)
        MagnitudeState.ENDING_SOON_30S -> stringResource(R.string.ending_soon_30s)
        MagnitudeState.ENDING_SOON_VAD -> stringResource(R.string.ending_soon_vad)
    }

    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun RecognitionModelCaption(modelName: String?) {
    if (!modelName.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.recognizer_model_caption, modelName),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = TextAlign.Center,
            style = Typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * SK | EN choice for language routing. Each option is a radio button for TalkBack; [enabled] false only dims it.
 * [vertical] stacks the options in a narrow column, beside the editing buttons.
 */
@Composable
fun DictationLanguageSwitch(selected: DictationLanguage, enabled: Boolean, onSelected: (DictationLanguage) -> Unit,
    vertical: Boolean = false, locked: Boolean = true) {
    if (vertical) {
        Column(
            modifier = Modifier.width(DICTATION_SMALL_BUTTON_WIDTH).selectableGroup(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
        ) { DictationLanguageOptions(selected, enabled, onSelected, Modifier.fillMaxWidth(), locked) }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .selectableGroup(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) { DictationLanguageOptions(selected, enabled, onSelected, Modifier.padding(horizontal = 4.dp).defaultMinSize(minWidth = 64.dp), locked) }
    }
}

@Composable
private fun DictationLanguageOptions(selected: DictationLanguage, enabled: Boolean,
    onSelected: (DictationLanguage) -> Unit, optionModifier: Modifier, locked: Boolean) {
    val lockedLabel = stringResource(R.string.dictation_language_locked)
    DictationLanguage.values().forEach { language ->
        val isSelected = language == selected
        val label = stringResource(if (language == DictationLanguage.Slovak) R.string.dictation_language_sk_short
            else R.string.dictation_language_en_short)
        val description = stringResource(if (language == DictationLanguage.Slovak) R.string.dictation_language_sk_description
            else R.string.dictation_language_en_description)
        Surface(
            modifier = optionModifier
                .defaultMinSize(minHeight = 48.dp)
                // Always enabled so the tap is consumed here and never reaches the tap-to-stop surface.
                // A locked choice is refused by the caller.
                .selectable(selected = isSelected, role = Role.RadioButton,
                    onClick = { if (enabled) onSelected(language) })
                .semantics {
                    contentDescription = description
                    // Locked while recording; after Stop it is simply unavailable.
                    if (!enabled) { if (locked) stateDescription = lockedLabel else disabled() }
                },
            shape = RoundedCornerShape(24.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = Typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun SelectLanguage(languages: Set<String>, onSelected: (String) -> Unit) {
    val languageItems = LANGUAGE_LIST.filter { languages.contains(it.id) }

    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 140.dp), modifier = Modifier.fillMaxWidth()) {
        items(languageItems.size) {
            Button(onClick = {
                onSelected(languageItems[it].id)
            }, modifier = Modifier
                .fillMaxSize()
                .padding(4.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )) {
                Text(languageItems[it].name,  modifier = Modifier.padding(0.dp, 16.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun ColumnScope.RecognizeLoadingCircle(text: String = "Initializing...") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
fun ColumnScope.PartialDecodingResult(text: String = "I am speaking [...]") {
    val provisionalLabel = stringResource(R.string.recognizer_provisional_transcript)
    Text(provisionalLabel, style = Typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            modifier = Modifier
                .align(Alignment.Start)
                .heightIn(max = 144.dp)
                .verticalScroll(rememberScrollState())
                .semantics { stateDescription = provisionalLabel }
                .padding(8.dp)
                .defaultMinSize(0.dp, 64.dp),
            textAlign = TextAlign.Start,
            style = Typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun RecognitionContent(state: RecognitionUiState, compact: Boolean = false,
    onFinish: () -> Unit = {},
    dictationLanguage: DictationLanguage? = null, onDictationLanguage: (DictationLanguage) -> Unit = {},
    dictationLanguageLocked: Boolean = false, onEdit: ((DictationEdit) -> Unit)? = null,
    onWaveformDrawn: () -> Unit = {}) {
    if (state.phase == RecognitionUiPhase.Inactive) return
    // With the editing buttons, the content is widened under the window's close button; only the
    // editing row uses that area, everything else keeps clear of the button.
    val inset = if (onEdit != null) LocalDictationTrailingInset.current else 0.dp
    Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val extra = inset.roundToPx()
        if (extra == 0 || !constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val placeable = measurable.measure(constraints.copy(
            minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(placeable.width - extra, placeable.height) { placeable.place(0, 0) }
    }.verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(end = inset)) {
            if (state.isRecording) {
                if (compact) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            InnerRecognize(state.bars, state.magnitude, compact = true, onDrawn = onWaveformDrawn)
                        }
                        IconButton(onClick = onFinish, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.finish_recording))
                        }
                    }
                } else {
                    InnerRecognize(state.bars, state.magnitude, onDrawn = onWaveformDrawn)
                }
                state.statusText?.let {
                    Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            } else {
                RecognizeLoadingCircle(state.statusText ?: stringResource(R.string.initializing))
            }
        }
        val languageSwitchable = state.phase == RecognitionUiPhase.Loading || state.phase == RecognitionUiPhase.Recording
        // Also while loading and after Stop: the dictation screen is still open then.
        if (onEdit != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dictationLanguage != null) {
                    // Stays after Stop, dimmed, so the editing buttons do not move under the finger.
                    DictationLanguageSwitch(dictationLanguage,
                        enabled = languageSwitchable && !(state.isRecording && dictationLanguageLocked),
                        onSelected = onDictationLanguage, vertical = true, locked = languageSwitchable)
                    Spacer(Modifier.width(4.dp))
                }
                DictationEditBar(onEdit, Modifier.weight(1f), trailingExtra = inset)
            }
        }
        Column(Modifier.fillMaxWidth().padding(end = inset)) {
            RecognitionModelCaption(state.modelName)
            if (onEdit == null && dictationLanguage != null && languageSwitchable) {
                DictationLanguageSwitch(dictationLanguage, enabled = !(state.isRecording && dictationLanguageLocked),
                    onSelected = onDictationLanguage)
            }
            if (!compact && state.partialText.isNotBlank()) PartialDecodingResult(state.partialText)
        }
    }
}

@Composable
fun ColumnScope.RecognizeMicError(openSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.grant_microphone_permission_to_use_voice_input),
            modifier = Modifier
                .padding(8.dp, 2.dp)
                .align(Alignment.CenterHorizontally),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(
            onClick = { openSettings() },
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.CenterHorizontally)
                .size(64.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.open_voice_input_settings),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ColumnScope.RecognizeFailure(message: String, onModelOptions: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            message,
            modifier = Modifier
                .padding(12.dp, 8.dp)
                .align(Alignment.CenterHorizontally),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        onModelOptions?.let {
            Button(
                onClick = it,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
            ) {
                Text(stringResource(R.string.model_options))
            }
        }
    }
}

@Composable
fun ColumnScope.RecognizeModelDownloadRequired(body: String, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            body,
            modifier = Modifier
                .padding(12.dp, 2.dp)
                .align(Alignment.CenterHorizontally),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onDownload,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(8.dp)
        ) {
            Text(stringResource(R.string.download_model))
        }
    }
}

abstract class RecognizerView {
    protected open val liveBenchmarkSession: org.futo.voiceinput.benchmark.LiveBenchmarkSession? get() = null
    protected open val compact: Boolean = false
    private var shouldPlaySounds = ENABLE_SOUND.default
    private var shouldBeVerbose = VERBOSE_PROGRESS.default
    private var shouldRequestLanguage = MANUALLY_SELECT_LANGUAGE.default
    private var languages = LANGUAGE_TOGGLES.default

    suspend fun loadSettings() {
        shouldPlaySounds = context.getSetting(ENABLE_SOUND)
        shouldBeVerbose = context.getSetting(VERBOSE_PROGRESS)
        shouldRequestLanguage = context.getSetting(MANUALLY_SELECT_LANGUAGE)
        languages = context.getSetting(LANGUAGE_TOGGLES)
    }

    private val soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(CONTENT_TYPE_SONIFICATION)
            .build()
    ).build()

    private var startSoundId: Int = -1
    private var cancelSoundId: Int = -1
    private var firstWaveformFrame = true

    protected abstract val context: Context
    protected abstract val lifecycleScope: LifecycleCoroutineScope

    abstract fun setContent(content: @Composable () -> Unit)

    abstract fun onCancel()
    abstract fun sendResult(result: String, detectedLanguage: String?): Boolean
    abstract fun sendPartialResult(result: String): Boolean
    abstract fun requestPermission()
    abstract fun requestParakeetModelDownload()
    abstract fun requestRecognitionModelDownload(model: RecognitionModel)
    abstract fun requestMoonshineModelDownload()
    abstract fun requestWhisperModelDownload(models: List<ModelData>)

    abstract fun decodingStarted()

    @Composable
    abstract fun Window(onClose: () -> Unit, allowClick: Boolean, onPauseVAD: (Boolean) -> Unit, onFinish: () -> Unit, content: @Composable ColumnScope.() -> Unit)

    private var detectedLanguage: String? = null
    private var presentation by mutableStateOf(RecognitionUiState())
    /** Current SK | EN choice; null hides the button (setting off, Slovak model missing, or comparison). */
    private var dictationLanguage by mutableStateOf<DictationLanguage?>(null)
    /** True while recording with a live-text model on either side; the button is then disabled. */
    private var dictationLanguageLocked by mutableStateOf(false)

    /** The target field is private (password or no personalized learning); see AudioRecognizer.privateTarget. */
    protected open val privateTarget: Boolean get() = false
    /** Language for voice-command phrases: the SK | EN or forced language, else the reported one. */
    protected val commandLanguage: String? get() = recognizer.dictationLanguageTag ?: detectedLanguage
    /** True when this host has a target field for the editing buttons (the IME). */
    protected open val supportsDictationEditing: Boolean = false
    /**
     * Applies [edit] in the target field. [committed] must run once composing live text has been
     * committed as it stands, before the edit itself.
     */
    protected open fun applyDictationEdit(edit: DictationEdit, committed: () -> Unit): Boolean = false
    /** The editing buttons are shown for this dictation (EDIT_BUTTONS_WHILE_DICTATING and a supported model). */
    private var dictationEditing by mutableStateOf(false)

    private fun dictationEdit(edit: DictationEdit) {
        // Committing live text is only safe while the recognizer will stop resending it.
        if (!dictationEditing || !recognizer.isDictationEditingActive) return
        applyDictationEdit(edit) { recognizer.freezeLiveText() }
    }

    /**
     * The user moved the cursor or edited the target field during dictation: the live text shown
     * so far becomes permanent, so dictation can go on at the new cursor. False when this
     * dictation cannot (the editing buttons are off for it).
     */
    fun freezeLiveTextForUserEdit(): Boolean =
        dictationEditing && recognizer.isDictationEditingActive && recognizer.freezeLiveText()

    /** Language signals from the keyboard or the calling app; locale tags only. */
    protected open fun probeKeyboardLanguage(): DiagnosticLanguageProbe? = null

    private fun selectDictationLanguage(language: DictationLanguage) {
        if (dictationLanguage == null || language == dictationLanguage) return
        if (!recognizer.setDictationLanguage(language)) return
        dictationLanguage = language
        // A manual tap is remembered for the next dictation.
        lifecycleScope.launch { context.setSetting(DICTATION_LANGUAGE, language.tag) }
    }

    /** Probes the keyboard language and decides the button's state before the recognizer starts. */
    private suspend fun prepareDictationLanguage() {
        val probe = probeKeyboardLanguage()
        KeyboardLanguageProbe.report(probe)
        val visible = liveBenchmarkSession == null &&
            LanguageRouting.isActive(context.getSetting(ROUTE_BY_LANGUAGE), recognizer.slovakModelInstalled())
        val language = if (visible) LanguageRouting.initialLanguage(probe?.resolvedLanguage(),
            DictationLanguage.fromSetting(context.getSetting(DICTATION_LANGUAGE))) else null
        dictationLanguage = language
        recognizer.setDictationLanguage(language)
    }
    private val microphoneState = mutableStateOf(MicrophoneRouteState())

    @Composable
    private fun RoutingWindow(onClose: () -> Unit, allowClick: Boolean,
        onPauseVAD: (Boolean) -> Unit, onFinish: () -> Unit,
        content: @Composable ColumnScope.() -> Unit) {
        Window(onClose, allowClick, onPauseVAD, onFinish) {
            content()
            MicrophoneRouteControl(microphoneState.value, recognizer::selectMicrophone)
        }
    }

    private fun showRecognition() {
        setContent {
            val state = presentation
            RoutingWindow(
                onClose = { recognizer.cancelRecognizer() },
                onFinish = { finishRecognizerIfRecording() },
                onPauseVAD = { if (state.isRecording) recognizer.pauseVAD(it) },
                allowClick = state.isRecording
            ) {
                RecognitionContent(state, compact = compact, onFinish = { finishRecognizerIfRecording() },
                    dictationLanguage = dictationLanguage, onDictationLanguage = ::selectDictationLanguage,
                    dictationLanguageLocked = dictationLanguageLocked,
                    onEdit = if (dictationEditing) ::dictationEdit else null) {
                    if (firstWaveformFrame && state.bars.isNotEmpty()) {
                        firstWaveformFrame = false
                        recognizer.waveformDrawn()
                    }
                }
            }
        }
    }

    private val recognizer = object : AudioRecognizer() {
        override val context: Context
            get() = this@RecognizerView.context
        override val lifecycleScope: LifecycleCoroutineScope
            get() = this@RecognizerView.lifecycleScope

        override fun routedModelChanged(modelName: String?) {
            presentation = presentation.copy(modelName = modelName)
        }

        override fun microphoneRouteChanged(state: MicrophoneRouteState) {
            microphoneState.value = state
        }

        override fun recordingInterrupted() {
            Toast.makeText(context, R.string.microphone_interrupted, Toast.LENGTH_LONG).show()
        }

        // Tries to play a sound. If it's not yet ready, plays it when it's ready
        private fun playSound(id: Int) {
            lifecycleScope.launch {
                if(context.getSetting(ENABLE_SOUND)) {
                    if (soundPool.play(id, 1.0f, 1.0f, 0, 0, 1.0f) == 0) {
                        soundPool.setOnLoadCompleteListener { soundPool, sampleId, status ->
                            if ((sampleId == id) && (status == 0)) {
                                soundPool.play(id, 1.0f, 1.0f, 0, 0, 1.0f)
                            }
                        }
                    }
                }
            }
        }

        override fun cancelled() {
            presentation = presentation.end()
            playSound(cancelSoundId)
            onCancel()
        }

        override fun finished(result: String): Boolean {
            presentation = presentation.end()
            val report = diagnostics
            runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if(manager.isEnabled) {
                val event = AccessibilityEvent.obtain();

                event.setPackageName(RecognizerView::class.java.`package`.name)
                event.setClassName(RecognizerView::class.java.name)
                event.setEventTime(SystemClock.uptimeMillis())
                event.setEnabled(true)
                event.text.add(result)

                event.setEventType(AccessibilityEvent.TYPE_ANNOUNCEMENT)

                manager.sendAccessibilityEvent(event)

            }
            }
            val benchmarkSession = liveBenchmarkSession
            val accepted = try {
                sendResult(result, detectedLanguage)
            } catch (error: Exception) {
                report?.event(org.futo.voiceinput.diagnostics.DiagnosticEvent.DELIVERY_REJECTED, error = error)
                throw error
            }
            runCatching { benchmarkSession?.delivered(result, accepted) }
            // sendResult may synchronously reset the recognizer while returning to the previous IME.
            report?.end(if (accepted) org.futo.voiceinput.diagnostics.DiagnosticEvent.DELIVERY_ACCEPTED
                else org.futo.voiceinput.diagnostics.DiagnosticEvent.DELIVERY_REJECTED)
            if (!accepted) {
                failed(IllegalStateException("The active input connection is unavailable"))
            }
            return accepted
        }

        override fun failed(error: Throwable) {
            liveBenchmarkSession?.failure("Recognition or delivery failed (${error.javaClass.simpleName})")
            presentation = presentation.end()
            diagnostics?.end(org.futo.voiceinput.diagnostics.DiagnosticEvent.SESSION_FAILED, error)
            Log.e("RecognizerView", "Recognition did not produce a result (${DiagnosticFailure.from(error).kind})")
            val message = if (error is NoSpeechRecognizedException) {
                context.getString(R.string.no_speech_recognized)
            } else {
                context.getString(R.string.recognition_failed)
            }
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { cancelRecognizer() },
                    onPauseVAD = { },
                    allowClick = true
                ) {
                    RecognizeFailure(
                        message,
                        if (error is NoSpeechRecognizedException) null else {
                            {
                                context.startActivity(
                                    Intent(context, SettingsActivity::class.java).apply {
                                        putExtra(SETTINGS_DESTINATION_EXTRA, "models")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }

        override fun languageDetected(result: String) {
            detectedLanguage = result
        }

        override fun partialResult(result: String) {
            sendPartialResult(result)
            presentation = presentation.partial(result)
        }

        override fun livePartialResult(result: String): Boolean {
            val accepted = sendPartialResult(result)
            presentation = presentation.partial(result)
            return accepted
        }

        override fun dictationEditingChanged(enabled: Boolean) {
            dictationEditing = enabled
        }

        override fun decodingStatus(status: RunState) {
            val text = if (shouldBeVerbose) {
                when (status) {
                    RunState.ExtractingFeatures -> context.getString(R.string.extracting_features)
                    RunState.ProcessingEncoder -> context.getString(R.string.running_encoder)
                    RunState.StartedDecoding -> context.getString(R.string.decoding_started)
                    RunState.Streaming -> context.getString(R.string.listening)
                    RunState.CatchingUp -> context.getString(R.string.catching_up)
                    RunState.SwitchingModel -> context.getString(R.string.switching_to_english_model)
                    RunState.OOMError -> context.getString(R.string.out_of_memory_error)
                }
            } else {
                when (status) {
                    RunState.ExtractingFeatures -> context.getString(R.string.processing)
                    RunState.ProcessingEncoder -> context.getString(R.string.processing)
                    RunState.StartedDecoding -> context.getString(R.string.processing)
                    RunState.Streaming -> context.getString(R.string.listening)
                    RunState.CatchingUp -> context.getString(R.string.catching_up)
                    RunState.SwitchingModel -> context.getString(R.string.switching_to_english_model)
                    RunState.OOMError -> context.getString(R.string.out_of_memory_error)
                }
            }

            if(status == RunState.StartedDecoding) {
                this@RecognizerView.decodingStarted()
            }

            presentation = presentation.status(
                if (status == RunState.Streaming) null else text,
                streaming = status == RunState.Streaming || status == RunState.CatchingUp
            )
        }

        override fun loading() {
            presentation = presentation.start(selectedModelName)
            showRecognition()
        }

        override fun needParakeetModelDownload() {
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { requestParakeetModelDownload() },
                    onPauseVAD = { },
                    allowClick = false
                ) {
                    RecognizeModelDownloadRequired(
                        body = context.getString(R.string.parakeet_download_required_body),
                        onDownload = { requestParakeetModelDownload() }
                    )
                }
            }
        }

        override fun needRecognitionModelDownload(model: RecognitionModel) {
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { requestRecognitionModelDownload(model) },
                    onPauseVAD = { },
                    allowClick = false
                ) {
                    RecognizeModelDownloadRequired(
                        body = context.getString(R.string.download_required_body),
                        onDownload = { requestRecognitionModelDownload(model) }
                    )
                }
            }
        }

        override fun needMoonshineModelDownload() {
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { requestMoonshineModelDownload() },
                    onPauseVAD = { },
                    allowClick = false
                ) {
                    RecognizeModelDownloadRequired(
                        body = context.getString(R.string.moonshine_download_required_body),
                        onDownload = { requestMoonshineModelDownload() }
                    )
                }
            }
        }

        override fun needWhisperModelDownload(models: List<ModelData>) {
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { requestWhisperModelDownload(models) },
                    onPauseVAD = { },
                    allowClick = false
                ) {
                    RecognizeModelDownloadRequired(
                        body = context.getString(R.string.whisper_download_required_body),
                        onDownload = { requestWhisperModelDownload(models) }
                    )
                }
            }
        }

        override fun needPermission() {
            requestPermission()
        }

        override fun permissionRejected() {
            setContent {
                this@RecognizerView.RoutingWindow(
                    onClose = { cancelRecognizer() },
                    onFinish = { openPermissionSettings() },
                    onPauseVAD = { },
                    allowClick = true
                ) {
                    RecognizeMicError(openSettings = { openPermissionSettings() })
                }
            }
        }

        override fun recordingStarted() {
            firstWaveformFrame = true
            dictationLanguageLocked = !dictationLanguageSwitchableWhileRecording()
            presentation = presentation.copy(modelName = selectedModelName).recording()

            playSound(startSoundId)
        }

        override fun updateWaveform(bars: List<Pair<Float, Float>>, state: MagnitudeState) {
            presentation = presentation.waveform(bars, state)
        }

        fun waveformDrawn() {
            diagnostics?.event(org.futo.voiceinput.diagnostics.DiagnosticEvent.WAVEFORM_FIRST_FRAME)
            if (BuildConfig.DEBUG) Log.d("WaveformTiming", "first_frame t=${SystemClock.elapsedRealtime()}")
        }

        override fun processing() {
            presentation = presentation.processing(context.getString(R.string.processing))
        }

        override fun cleaning() {
            presentation = presentation.cleaning(context.getString(R.string.s1_cleaning_transcript))
        }
    }

    fun isRecording(): Boolean {
        return recognizer.isCurrentlyRecording()
    }

    fun finishRecognizerIfRecording() {
        recognizer.finishRecognizerIfRecording()
    }

    fun reset() {
        presentation = presentation.end()
        dictationEditing = false
        detectedLanguage = null
        recognizer.reset()
    }

    fun init() {
        presentation = presentation.end()
        startSoundId = soundPool.load(this.context, R.raw.start, 0)
        cancelSoundId = soundPool.load(this.context, R.raw.cancel, 0)

        recognizer.dictationEditingHost = supportsDictationEditing
        recognizer.privateTarget = privateTarget
        lifecycleScope.launch {
            recognizer.liveBenchmarkSession = liveBenchmarkSession
            loadSettings()
            if (liveBenchmarkSession != null) { shouldRequestLanguage = false; languages = setOf("en") }
            prepareDictationLanguage()

            if(shouldRequestLanguage && (languages.size > 1)) {
                setContent {
                    this@RecognizerView.RoutingWindow(
                        onClose = { recognizer.cancelRecognizer() },
                        onFinish = { },
                        onPauseVAD = { },
                        allowClick = false
                    ) {
                        SelectLanguage(languages = languages, onSelected = {
                            recognizer.forceLanguage(it)

                            if(!recognizer.isCurrentlyRecording()) {
                                recognizer.create()
                            } else {
                                // NOTE: If forceLanguage was set to "en" and English-only model was loaded
                                // then we'll be stuck with English
                                recognizer.forceLanguage(null)
                            }
                        })
                    }
                }
            } else {
                recognizer.forceLanguage(null)
                recognizer.create()
            }
        }
    }

    fun permissionResultGranted() {
        recognizer.permissionResultGranted()
    }

    fun permissionResultRejected() {
        recognizer.permissionResultRejected()
    }
}
