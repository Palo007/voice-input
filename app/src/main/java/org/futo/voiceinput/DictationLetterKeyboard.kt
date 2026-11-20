package org.futo.voiceinput

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Shift on the letter keyboard: each tap moves to the next state, Locked back to Off. */
enum class LetterShift {
    Off, Once, Locked;

    fun next(): LetterShift = when (this) { Off -> Once; Once -> Locked; Locked -> Off }

    /** The text a key types in this state. */
    fun apply(text: String): String = if (this == Off) text else text.uppercase()

    /** The state after a letter is typed: a one-letter shift ends, caps lock stays. */
    fun afterLetter(): LetterShift = if (this == Once) Off else this
}

/** Pure layout data for [DictationLetterKeyboard], kept apart so it can be unit tested. */
internal object LetterKeyboardLayout {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** Letters offered on a long press: Slovak first, then the Czech-only ones. */
    private val accents = mapOf(
        'a' to "áä", 'c' to "č", 'd' to "ď", 'e' to "éě", 'i' to "í", 'l' to "ľĺ", 'n' to "ň",
        'o' to "óô", 'r' to "ŕř", 's' to "š", 't' to "ť", 'u' to "úů", 'y' to "ý", 'z' to "ž"
    )

    /** Digits on the top row, like a phone keyboard: q is 1 through p is 0. */
    private val digits = rows[0].zip("1234567890").toMap()

    fun accentsFor(letter: Char): List<String> = accents[letter]?.map { it.toString() }.orEmpty()

    /** Long-press options: the top row's digit first (selected when the row opens), then the accents. */
    fun alternativesFor(letter: Char): List<String> = listOfNotNull(digits[letter]?.toString()) + accentsFor(letter)

    /**
     * Which of [count] long-press options is under the finger at [x] (key-local), when each option
     * is [keyWidth] wide and the first one sits over the key. With [leftward] the options extend to
     * the left of the key, otherwise to the right; positions beyond either end pick the nearest end.
     */
    fun accentIndex(x: Float, keyWidth: Float, count: Int, leftward: Boolean): Int {
        if (count <= 0 || keyWidth <= 0f) return 0
        val steps = if (leftward) (keyWidth - x) / keyWidth else x / keyWidth
        return floor(steps).toInt().coerceIn(0, count - 1)
    }
}

/** The long-press option row shown over the keyboard; [anchor] is the key, in keyboard coordinates. */
@Stable
private class AccentPopup(val anchor: Rect, val options: List<String>, val leftward: Boolean) {
    var selected by mutableIntStateOf(0)
}

/** Height of one key row, including the gap around each key. */
private val ROW_HEIGHT = 48.dp
/** Gap between a key and its long-press options, in key heights; the finger covers less than this. */
private const val ACCENT_ROW_LIFT = 1f
/** Inset of the visible key inside its touch area, so neighboring keys leave no dead gaps. */
private val KEY_INSET = 2.dp

/**
 * Letter keyboard on the dictation screen, for fixing a few letters without leaving dictation.
 * Keys type at the cursor through [onEdit] like the other editing buttons, so the live text so far
 * is kept and later speech goes in after the typed letters. [onClose] returns to the editing buttons.
 * Long-pressing a letter offers its top-row digit, then its Slovak and Czech accented forms; slide to
 * one and release.
 */
@Composable
fun DictationLetterKeyboard(onEdit: (DictationEdit) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var shift by remember { mutableStateOf(LetterShift.Off) }
    var popup by remember { mutableStateOf<AccentPopup?>(null) }
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val view = LocalView.current
    val currentOnEdit by rememberUpdatedState(onEdit)

    fun tap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun type(text: String) {
        currentOnEdit(DictationEdit.Type(shift.apply(text)))
        // A digit leaves a one-letter shift for the next letter.
        if (text.any { it.isLetter() }) shift = shift.afterLetter()
    }
    fun edit(edit: DictationEdit) { tap(); currentOnEdit(edit) }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { root[0] = it }
            // A touch between keys must not reach the tap-to-stop surface behind the keyboard.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .testTag("dictation-letter-keyboard")
    ) {
        @Composable
        fun RowScope.Letters(index: Int) {
            LetterKeyboardLayout.rows[index].forEach { letter ->
                LetterKey(letter, shift, onPressDown = ::tap, onType = ::type,
                    showAccents = { coords, options ->
                        val rootCoords = root[0]
                        if (rootCoords == null || !rootCoords.isAttached || !coords.isAttached) null
                        else {
                            val anchor = rootCoords.localBoundingBoxOf(coords)
                            AccentPopup(anchor, options.map(shift::apply),
                                leftward = anchor.center.x > rootCoords.size.width / 2f).also { popup = it }
                        }
                    },
                    hideAccents = { popup = null })
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) { Letters(0) }
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                Spacer(Modifier.weight(0.5f))
                Letters(1)
                Spacer(Modifier.weight(0.5f))
            }
            // The last two rows share a tall key on the right that returns to the editing buttons.
            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT * 2)) {
                Column(Modifier.weight(8.5f)) {
                    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                        ActionKey("shift", stringResource(R.string.dictation_letters_shift), weight = 1.5f,
                            highlighted = shift != LetterShift.Off, onPress = { tap(); shift = shift.next() },
                            state = stringResource(when (shift) {
                                LetterShift.Off -> R.string.dictation_letters_shift_off
                                LetterShift.Once -> R.string.dictation_letters_shift_once
                                LetterShift.Locked -> R.string.dictation_letters_shift_locked
                            })) {
                            Icon(painterResource(when (shift) {
                                LetterShift.Off -> R.drawable.ic_shift
                                LetterShift.Once -> R.drawable.ic_shift_once
                                LetterShift.Locked -> R.drawable.ic_shift_locked
                            }), contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        Letters(2)
                    }
                    // Space at the left edge, the erase keys beside it, then the cursor keys together.
                    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                        ActionKey("space", stringResource(R.string.dictation_edit_space), weight = 3f, repeats = true,
                            onPress = { edit(DictationEdit.Space) }) { EditLabel(stringResource(R.string.dictation_edit_space_short)) }
                        ActionKey("delete", stringResource(R.string.dictation_edit_delete_character), weight = 1.25f,
                            repeats = true, onPress = { edit(DictationEdit.DeleteCharacter) }) { BackspaceIcon(20) }
                        ActionKey("word", stringResource(R.string.dictation_edit_delete_word), weight = 1.25f,
                            prominent = true, repeats = true, onPress = { edit(DictationEdit.DeleteWord) }) { BackspaceIcon(22) }
                        ActionKey("left", stringResource(R.string.dictation_letters_cursor_left), repeats = true,
                            onPress = { edit(DictationEdit.MoveCursor(forward = false)) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                        }
                        ActionKey("right", stringResource(R.string.dictation_letters_cursor_right), repeats = true,
                            onPress = { edit(DictationEdit.MoveCursor(forward = true)) }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                        ActionKey("period", stringResource(R.string.dictation_edit_period),
                            onPress = { edit(DictationEdit.Period) }) { EditLabel(".") }
                    }
                }
                ActionKey("close", stringResource(R.string.dictation_letters_close), weight = 1.5f, highlighted = true,
                    onPress = { tap(); onClose() }) {
                    Icon(painterResource(R.drawable.mic_2_), contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        }
        popup?.let { AccentRow(it) }
    }
}

/** A non-letter key filling [weight] of its row; see [DictationKey]. */
@Composable
private fun RowScope.ActionKey(
    key: String, description: String, weight: Float = 1f, repeats: Boolean = false,
    prominent: Boolean = false, highlighted: Boolean = false, state: String? = null, onPress: () -> Unit,
    content: @Composable () -> Unit
) {
    DictationKey(key, description, repeats, onPress,
        Modifier.weight(weight).fillMaxSize().padding(KEY_INSET),
        prominent = prominent, highlighted = highlighted, minHeight = 0.dp, cornerRadius = 8.dp,
        stateDescription = state, content = content)
}

/**
 * One letter: types on release, or after a long press offers [LetterKeyboardLayout.alternativesFor] through
 * [showAccents]; sliding picks one and releasing types it. The whole cell is the touch area.
 */
@Composable
private fun RowScope.LetterKey(
    letter: Char, shift: LetterShift, onPressDown: () -> Unit, onType: (String) -> Unit,
    showAccents: (LayoutCoordinates, List<String>) -> AccentPopup?, hideAccents: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val accents = remember(letter) { LetterKeyboardLayout.alternativesFor(letter) }
    val view = LocalView.current
    val currentOnType by rememberUpdatedState(onType)
    val currentOnPressDown by rememberUpdatedState(onPressDown)
    val currentShowAccents by rememberUpdatedState(showAccents)
    val currentHideAccents by rememberUpdatedState(hideAccents)
    val label = shift.apply(letter.toString())
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(letter) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    currentOnPressDown()
                    // 0: still down after the long-press timeout, 1: released, 2: cancelled.
                    var outcome = 0
                    val timeout = if (accents.isEmpty()) Long.MAX_VALUE else viewConfiguration.longPressTimeoutMillis
                    withTimeoutOrNull(timeout) { outcome = if (awaitRelease(down.id)) 1 else 2 }
                    if (outcome == 1) currentOnType(letter.toString())
                    val popup = if (outcome == 0) coords[0]?.let { currentShowAccents(it, accents) } else null
                    if (popup != null) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                // Consumed elsewhere: the gesture was cancelled (a system gesture), type nothing.
                                if (change.isConsumed) break
                                change.consume()
                                popup.selected = LetterKeyboardLayout.accentIndex(change.position.x,
                                    size.width.toFloat(), popup.options.size, popup.leftward)
                                if (!change.pressed) {
                                    currentOnType(accents[popup.selected])
                                    break
                                }
                            }
                        } finally {
                            currentHideAccents()
                        }
                    } else if (outcome == 0) {
                        awaitRelease(down.id)
                    }
                    interactionSource.tryEmit(PressInteraction.Release(press))
                }
            }
            .semantics(mergeDescendants = true) {}
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick { currentOnType(letter.toString()); true }
                if (accents.isNotEmpty()) customActions = accents.map { accent ->
                    CustomAccessibilityAction(shift.apply(accent)) { currentOnType(accent); true }
                }
            }
            .padding(KEY_INSET)
    ) {
        Surface(
            Modifier.fillMaxSize().clip(shape).indication(interactionSource, ripple()),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, fontSize = 20.sp, maxLines = 1, softWrap = false)
                if (accents.isNotEmpty()) {
                    // A hint that a long press offers more: the digit, or else the first accented letter.
                    Text(shift.apply(accents.first()), fontSize = 9.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp))
                }
            }
        }
    }
}

/**
 * Waits until pointer [id] is lifted, wherever it has moved: true when released, false when the
 * gesture was cancelled (the event is consumed elsewhere, as by a system gesture). A finger that
 * drifts off the key while typing fast still types the key it pressed.
 */
private suspend fun AwaitPointerEventScope.awaitRelease(id: PointerId): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (change.isConsumed) return false
        change.consume()
        if (!change.pressed) return true
    }
}

/**
 * The long-press options, each as wide as the key; the selected one is highlighted. They float a
 * full key height above their key, every row included, so the pressing finger never covers them. A
 * separate popup window lets them reach past the dictation screen's scrolling area, which clips.
 */
@Composable
private fun AccentRow(popup: AccentPopup) {
    val density = LocalDensity.current
    val keyWidth = popup.anchor.width
    val keyHeight = popup.anchor.height
    val count = popup.options.size
    val left = if (popup.leftward) popup.anchor.right - keyWidth * count else popup.anchor.left
    val top = popup.anchor.top - keyHeight - keyHeight * ACCENT_ROW_LIFT
    val shape = RoundedCornerShape(10.dp)
    Popup(offset = IntOffset(left.roundToInt(), top.roundToInt()),
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Row(
            Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clearAndSetSemantics { }
        ) {
            // Leftward rows list the first option at the right end, in line with the key.
            val order = if (popup.leftward) popup.options.indices.reversed() else popup.options.indices
            for (i in order) {
                val selected = i == popup.selected
                Box(
                    Modifier
                        .width(with(density) { keyWidth.toDp() })
                        .height(with(density) { popup.anchor.height.toDp() })
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(popup.options[i], fontSize = 22.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Preview(widthDp = 380)
@Composable
private fun DictationLetterKeyboardPreview() {
    DictationLetterKeyboard(onEdit = {}, onClose = {})
}
