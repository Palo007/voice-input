package org.futo.voiceinput.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.voiceinput.R
import org.futo.voiceinput.commands.VoiceCommand
import org.futo.voiceinput.commands.VoiceCommandLanguage
import org.futo.voiceinput.commands.VoiceCommandPhrases
import org.futo.voiceinput.settings.ScreenTitle
import org.futo.voiceinput.settings.ScrollableList
import org.futo.voiceinput.settings.SettingToggleDataStore
import org.futo.voiceinput.settings.Tip
import org.futo.voiceinput.settings.VOICE_COMMANDS_DESTRUCTIVE
import org.futo.voiceinput.settings.VOICE_COMMANDS_ENABLED
import org.futo.voiceinput.settings.useDataStore

@Composable
fun VoiceCommandsScreen(navController: NavHostController = rememberNavController()) {
    val enabled = useDataStore(VOICE_COMMANDS_ENABLED)
    ScrollableList {
        ScreenTitle(stringResource(R.string.voice_commands_title), showBack = true, navController = navController)
        Tip(stringResource(R.string.voice_commands_tip))
        SettingToggleDataStore(
            stringResource(R.string.voice_commands_enable),
            VOICE_COMMANDS_ENABLED,
            subtitle = stringResource(R.string.voice_commands_enable_description)
        )
        SettingToggleDataStore(
            stringResource(R.string.voice_commands_destructive),
            VOICE_COMMANDS_DESTRUCTIVE,
            subtitle = stringResource(R.string.voice_commands_destructive_description),
            disabled = !enabled.value
        )
        Spacer(Modifier.height(16.dp))
        VoiceCommandPhrases.all.forEach { PhraseList(it) }
        HelpText(stringResource(R.string.voice_commands_literal_help))
        HelpText(stringResource(R.string.voice_commands_fields_help))
        HelpText(stringResource(R.string.voice_commands_private_help))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PhraseList(language: VoiceCommandLanguage) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(if (language.tag == "sk") R.string.voice_commands_slovak else R.string.voice_commands_english),
            style = MaterialTheme.typography.titleSmall)
        VoiceCommand.values().forEach { command ->
            val phrases = language.phrases[command].orEmpty().joinToString(" · ") { "“$it”" }
            Text("$phrases → ${stringResource(effect(command))}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.voice_commands_literal_word, language.literal),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpText(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun effect(command: VoiceCommand): Int = when (command) {
    VoiceCommand.NewLine -> R.string.voice_command_new_line
    VoiceCommand.NewParagraph -> R.string.voice_command_new_paragraph
    VoiceCommand.Comma -> R.string.voice_command_comma
    VoiceCommand.Period -> R.string.voice_command_period
    VoiceCommand.QuestionMark -> R.string.voice_command_question_mark
    VoiceCommand.ExclamationMark -> R.string.voice_command_exclamation_mark
    VoiceCommand.Colon -> R.string.voice_command_colon
    VoiceCommand.DeleteThat -> R.string.voice_command_delete_that
    VoiceCommand.UndoDictation -> R.string.voice_command_undo_dictation
}
