package org.futo.voiceinput.theme.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.voiceinput.R
import org.futo.voiceinput.settings.CUSTOM_THEME_COLOR
import org.futo.voiceinput.settings.CUSTOM_THEME_MODE
import org.futo.voiceinput.settings.THEME_KEY
import org.futo.voiceinput.settings.useDataStore
import org.futo.voiceinput.theme.ThemeOption
import org.futo.voiceinput.theme.ThemeOptionKeys
import org.futo.voiceinput.theme.ThemeOptions
import org.futo.voiceinput.theme.Typography
import org.futo.voiceinput.theme.UixThemeWrapper
import org.futo.voiceinput.theme.presets.CustomTheme
import org.futo.voiceinput.theme.presets.CustomThemeMode
import org.futo.voiceinput.theme.presets.CustomThemeSwatches
import org.futo.voiceinput.theme.rememberThemeColors
import org.futo.voiceinput.theme.presets.AMOLEDDarkPurple
import org.futo.voiceinput.theme.presets.ClassicMaterialDark
import org.futo.voiceinput.theme.presets.VoiceInputTheme

// TODO: For Dynamic System we need to show the user that it switches between light/dark
@Composable
fun ThemePreview(theme: ThemeOption, isSelected: Boolean = false, onClick: () -> Unit = { }) {
    val colors = rememberThemeColors(theme)

    val currColors = MaterialTheme.colorScheme

    val borderWidth = if (isSelected) {
        2.dp
    } else {
        0.dp
    }

    val borderColor = if (isSelected) {
        currColors.inversePrimary
    } else {
        Color.Transparent
    }

    val textColor = colors.onBackground

    val voiceCircleColor = colors.primaryContainer
    val micIconColor = colors.onPrimaryContainer

    val keyboardShape = RoundedCornerShape(8.dp)

    Surface(
        modifier = Modifier
            .padding(12.dp)
            .width(172.dp)
            .height(128.dp)
            .border(borderWidth, borderColor, keyboardShape)
            .clickable { onClick() },
        color = colors.surface,
        shape = keyboardShape
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Theme name and action bar
            Text(
                text = stringResource(theme.name),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(colors.background)
                    .fillMaxWidth()
                    .padding(4.dp),
                color = textColor,
                style = Typography.labelSmall
            )

            // Keyboard contents
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp, 32.dp, 8.dp, 8.dp)
            ) {
                val icon = painterResource(R.drawable.mic_2_)
                // Button
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    color = voiceCircleColor,
                    shape = CircleShape
                ) {
                    /*Canvas(modifier = Modifier.fillMaxSize()) {
                        with(icon) {
                            translate(size.width / 4.0f, size.height / 4.0f) {
                                draw(size / 2.0f, colorFilter = ColorFilter.tint(micIconColor))
                            }
                        }
                    }*/
                }
            }
        }
    }
}

@Composable
fun ThemePicker(onSelected: (ThemeOption) -> Unit) {
    val context = LocalContext.current

    val currentTheme = useDataStore(THEME_KEY.key, "").value
    var editingCustom by remember { mutableStateOf(false) }

    val isInspecting = LocalInspectionMode.current
    val availableThemeOptions = remember {
        ThemeOptionKeys.mapNotNull { key ->
            ThemeOptions[key]?.let { Pair(key, it) }
        }.filter {
            it.second.available(context)
        }.filter {
            when (isInspecting) {
                true -> !it.second.dynamic
                else -> true
            }
        }
    }

    LazyVerticalGrid(
        modifier = Modifier.fillMaxWidth(),
        columns = GridCells.Adaptive(minSize = 172.dp)
    ) {
        items(availableThemeOptions.count()) {
            val themeOption = availableThemeOptions[it].second

            ThemePreview(themeOption, isSelected = themeOption.key == currentTheme) {
                onSelected(themeOption)
                if (themeOption.key == CustomTheme.key) editingCustom = true
            }
        }
    }

    if (editingCustom) CustomThemeDialog(onDismiss = { editingCustom = false })
}

/** Accent color and brightness of the custom theme. Changes apply at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomThemeDialog(onDismiss: () -> Unit) {
    val (color, setColor) = useDataStore(CUSTOM_THEME_COLOR)
    val (mode, setMode) = useDataStore(CUSTOM_THEME_MODE)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_theme_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.custom_theme_accent), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomThemeSwatches.forEach { swatch ->
                        val selected = swatch == color
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { setColor(swatch) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null,
                                tint = if (ColorUtils.calculateLuminance(swatch) > 0.5) Color.Black else Color.White)
                        }
                    }
                }
                Text(stringResource(R.string.custom_theme_brightness), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomThemeMode.all.forEach { option ->
                        FilterChip(selected = option == mode, onClick = { setMode(option) },
                            label = { Text(stringResource(when (option) {
                                CustomThemeMode.DARK -> R.string.custom_theme_dark
                                CustomThemeMode.LIGHT -> R.string.custom_theme_light
                                CustomThemeMode.BLACK -> R.string.custom_theme_black
                                else -> R.string.custom_theme_system
                            })) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_theme_done)) } }
    )
}


@Preview
@Composable
private fun ThemePickerPreview() {
    Column {
        UixThemeWrapper(VoiceInputTheme.obtainColors(LocalContext.current)) {
            Surface(
                color = MaterialTheme.colorScheme.background
            ) {
                ThemePicker {}
            }
        }
        UixThemeWrapper(ClassicMaterialDark.obtainColors(LocalContext.current)) {
            Surface(
                color = MaterialTheme.colorScheme.background
            ) {
                ThemePicker {}
            }
        }
        UixThemeWrapper(AMOLEDDarkPurple.obtainColors(LocalContext.current)) {
            Surface(
                color = MaterialTheme.colorScheme.background
            ) {
                ThemePicker {}
            }
        }
    }
}