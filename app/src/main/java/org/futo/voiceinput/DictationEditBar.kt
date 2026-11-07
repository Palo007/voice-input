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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.voiceinput.theme.Typography

/** Like a keyboard's backspace: the first repeat after a short hold, then steady repeats. */
private const val EDIT_REPEAT_DELAY_MS = 400L
private const val EDIT_REPEAT_INTERVAL_MS = 75L

/** Width of the small edit buttons; matches the stacked SK/EN language buttons beside them. */
internal val DICTATION_SMALL_BUTTON_WIDTH = 56.dp

/**
 * Editing buttons for the IME dictation screen. A narrow column ("?" over delete character) the
 * width of the language buttons, a wide column (space bar over "Aa→a"), and a tall, highlighted
 * delete-word button at the edge for the thumb.
 */
@Composable
fun DictationEditBar(onEdit: (DictationEdit) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min).testTag("dictation-edit-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(DICTATION_SMALL_BUTTON_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DictationEditButton(DictationEdit.QuestionMark, stringResource(R.string.dictation_edit_question_mark),
                onEdit, Modifier.fillMaxWidth()) { EditLabel("?") }
            DictationEditButton(DictationEdit.DeleteCharacter, stringResource(R.string.dictation_edit_delete_character),
                onEdit, Modifier.fillMaxWidth()) { BackspaceIcon(18) }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DictationEditButton(DictationEdit.Space, stringResource(R.string.dictation_edit_space),
                onEdit, Modifier.fillMaxWidth()) { EditLabel(stringResource(R.string.dictation_edit_space_short)) }
            DictationEditButton(DictationEdit.Lowercase, stringResource(R.string.dictation_edit_lowercase),
                onEdit, Modifier.fillMaxWidth()) { EditLabel(stringResource(R.string.dictation_edit_lowercase_short)) }
        }
        Spacer(Modifier.width(6.dp))
        DictationEditButton(DictationEdit.DeleteWord, stringResource(R.string.dictation_edit_delete_word),
            onEdit, Modifier.width(80.dp).fillMaxHeight(), prominent = true) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BackspaceIcon(24)
                EditLabel(stringResource(R.string.dictation_edit_word_short))
            }
        }
    }
}

@Composable
private fun BackspaceIcon(sizeDp: Int) {
    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, modifier = Modifier.size(sizeDp.dp))
}

@Composable
private fun EditLabel(text: String) {
    Text(text, style = Typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DictationEditButton(
    edit: DictationEdit, description: String, onEdit: (DictationEdit) -> Unit,
    modifier: Modifier, prominent: Boolean = false, content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnEdit by rememberUpdatedState(onEdit)
    val shape = RoundedCornerShape(if (prominent) 20.dp else 24.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .indication(interactionSource, ripple())
            // Consumes the touch, so it never reaches the tap-to-stop surface or pauses VAD.
            .pointerInput(edit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    currentOnEdit(edit)
                    var wait = EDIT_REPEAT_DELAY_MS
                    while (true) {
                        // Null only on timeout: the finger is still down, so repeat.
                        val ended = withTimeoutOrNull(wait) { waitForUpOrCancellation()?.consume(); true }
                        if (ended != null || !edit.repeats) {
                            if (ended == null) waitForUpOrCancellation()?.consume()
                            break
                        }
                        currentOnEdit(edit)
                        wait = EDIT_REPEAT_INTERVAL_MS
                    }
                    interactionSource.tryEmit(PressInteraction.Release(press))
                }
            }
            // Its own merge boundary: otherwise TalkBack merges it into the tap-to-stop panel.
            .semantics(mergeDescendants = true) {}
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                onClick { currentOnEdit(edit); true }
            },
        shape = shape,
        color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) { content() }
    }
}

@Preview(widthDp = 232)
@Composable
private fun DictationEditBarPreview() {
    DictationEditBar(onEdit = {})
}
