package org.futo.voiceinput

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.futo.voiceinput.theme.Typography

/** Like a keyboard's backspace: the first repeat after a short hold, then steady repeats. */
private const val EDIT_REPEAT_DELAY_MS = 400L
private const val EDIT_REPEAT_INTERVAL_MS = 75L

/** Width of the small edit buttons; matches the stacked SK/EN language buttons beside them. */
internal val DICTATION_SMALL_BUTTON_WIDTH = 56.dp

/**
 * Width of the host window's close button beside the dictation content. The editing row reaches
 * under that column, so the right-hand buttons extend to the right edge.
 */
internal val LocalDictationTrailingInset = compositionLocalOf { 0.dp }

/** Width of the delete-word button, and of the slim letter-keyboard button at the right edge. */
private val DELETE_WORD_WIDTH = 80.dp
private val LETTERS_BUTTON_WIDTH = 44.dp

/**
 * Editing buttons for the IME dictation screen. Two equal columns ("Aa→a" over space,
 * delete character over "."), a tall, highlighted delete-word button, and at the right edge a slim,
 * tall button that opens the letter keyboard ([onOpenLetters]). The two tall buttons together are
 * as wide as delete-word was alone, so the other buttons keep their places.
 */
@Composable
fun DictationEditBar(onEdit: (DictationEdit) -> Unit, modifier: Modifier = Modifier, onOpenLetters: (() -> Unit)? = null) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min).testTag("dictation-edit-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DictationEditButton(DictationEdit.Lowercase, stringResource(R.string.dictation_edit_lowercase),
                onEdit, Modifier.fillMaxWidth()) { EditLabel(stringResource(R.string.dictation_edit_lowercase_short)) }
            DictationEditButton(DictationEdit.Space, stringResource(R.string.dictation_edit_space),
                onEdit, Modifier.fillMaxWidth()) { EditLabel(stringResource(R.string.dictation_edit_space_short)) }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DictationEditButton(DictationEdit.DeleteCharacter, stringResource(R.string.dictation_edit_delete_character),
                onEdit, Modifier.fillMaxWidth()) { BackspaceIcon(18) }
            DictationEditButton(DictationEdit.Period, stringResource(R.string.dictation_edit_period),
                onEdit, Modifier.fillMaxWidth()) { EditLabel(".") }
        }
        Spacer(Modifier.width(6.dp))
        DictationEditButton(DictationEdit.DeleteWord, stringResource(R.string.dictation_edit_delete_word),
            onEdit, Modifier.width(DELETE_WORD_WIDTH).fillMaxHeight(), prominent = true) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BackspaceIcon(24)
                EditLabel(stringResource(R.string.dictation_edit_word_short))
            }
        }
        if (onOpenLetters != null) {
            Spacer(Modifier.width(4.dp))
            DictationKey("letters", stringResource(R.string.dictation_letters_open), repeats = false,
                onPress = onOpenLetters, modifier = Modifier.width(LETTERS_BUTTON_WIDTH).fillMaxHeight()) {
                EditLabel(stringResource(R.string.dictation_letters_open_short))
            }
        }
    }
}

@Composable
internal fun BackspaceIcon(sizeDp: Int) {
    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, modifier = Modifier.size(sizeDp.dp))
}

@Composable
internal fun EditLabel(text: String) {
    Text(text, style = Typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DictationEditButton(
    edit: DictationEdit, description: String, onEdit: (DictationEdit) -> Unit,
    modifier: Modifier, prominent: Boolean = false, content: @Composable () -> Unit
) {
    val currentOnEdit by rememberUpdatedState(onEdit)
    DictationKey(edit, description, edit.repeats, { currentOnEdit(edit) }, modifier, prominent, content = content)
}

/**
 * Handles one touch on a dictation key: [onPress] runs on touch down and, when [repeats], again
 * while the finger stays down. The touch is consumed, so it never reaches the tap-to-stop surface
 * or pauses VAD. [key] restarts the gesture handling when the key changes.
 */
internal fun Modifier.dictationKeyPress(key: Any, interactionSource: MutableInteractionSource, repeats: Boolean,
    onPress: () -> Unit): Modifier = pointerInput(key, repeats) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val press = PressInteraction.Press(down.position)
        interactionSource.tryEmit(press)
        onPress()
        var wait = EDIT_REPEAT_DELAY_MS
        while (true) {
            // Null only on timeout: the finger is still down, so repeat.
            val ended = withTimeoutOrNull(wait) { waitForUpOrCancellation()?.consume(); true }
            if (ended != null || !repeats) {
                if (ended == null) waitForUpOrCancellation()?.consume()
                break
            }
            onPress()
            wait = EDIT_REPEAT_INTERVAL_MS
        }
        interactionSource.tryEmit(PressInteraction.Release(press))
    }
}

/** A rounded dictation key; see [dictationKeyPress]. [highlighted] uses the secondary colors. */
@Composable
internal fun DictationKey(
    key: Any, description: String, repeats: Boolean, onPress: () -> Unit,
    modifier: Modifier, prominent: Boolean = false, highlighted: Boolean = false,
    minHeight: Dp = 48.dp, cornerRadius: Dp = if (prominent) 20.dp else 24.dp, stateDescription: String? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnPress by rememberUpdatedState(onPress)
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .indication(interactionSource, ripple())
            .dictationKeyPress(key, interactionSource, repeats) { currentOnPress() }
            // Its own merge boundary: otherwise TalkBack merges it into the tap-to-stop panel.
            .semantics(mergeDescendants = true) {}
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                stateDescription?.let { this.stateDescription = it }
                onClick { currentOnPress(); true }
            },
        shape = shape,
        color = when {
            prominent -> MaterialTheme.colorScheme.primary
            highlighted -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            prominent -> MaterialTheme.colorScheme.onPrimary
            highlighted -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.heightIn(min = minHeight).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

@Preview(widthDp = 320)
@Composable
private fun DictationEditBarPreview() {
    DictationEditBar(onEdit = {}, onOpenLetters = {})
}
