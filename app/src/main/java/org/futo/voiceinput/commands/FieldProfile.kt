package org.futo.voiceinput.commands

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class FieldKind { Text, Email, Url, Number, Phone, DateTime, Password }

enum class FieldCapitalization { None, Sentences, Words, Characters }

/** What the target editor declares through [EditorInfo]; decides commands and formatting. */
data class FieldProfile(
    val kind: FieldKind,
    val multiline: Boolean = false,
    val capitalization: FieldCapitalization = FieldCapitalization.None,
    val noPersonalizedLearning: Boolean = false,
    val signed: Boolean = false,
    val decimal: Boolean = false
) {
    /**
     * Password variations and fields asking for no personalized learning: no personal vocabulary,
     * no audio or transcript history, no destructive commands, nothing remembered for "delete that".
     */
    val isPrivate: Boolean get() = kind == FieldKind.Password || noPersonalizedLearning

    /** Voice commands only apply to text fields; structured fields get their own formatting. */
    val acceptsCommands: Boolean get() = kind == FieldKind.Text

    companion object {
        fun from(inputType: Int, imeOptions: Int): FieldProfile {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS
            val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER -> FieldProfile(
                    if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldKind.Password else FieldKind.Number,
                    noPersonalizedLearning = noLearning,
                    signed = flags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                    decimal = flags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0)
                InputType.TYPE_CLASS_PHONE -> FieldProfile(FieldKind.Phone, noPersonalizedLearning = noLearning)
                InputType.TYPE_CLASS_DATETIME -> FieldProfile(FieldKind.DateTime, noPersonalizedLearning = noLearning)
                else -> FieldProfile(
                    kind = when (variation) {
                        InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.Password
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.Email
                        InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.Url
                        else -> FieldKind.Text
                    },
                    // IME_MULTI_LINE only lets a fullscreen IME wrap; the editor itself stays single-line.
                    multiline = flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
                    capitalization = when {
                        flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> FieldCapitalization.Characters
                        flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> FieldCapitalization.Words
                        flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> FieldCapitalization.Sentences
                        else -> FieldCapitalization.None
                    },
                    noPersonalizedLearning = noLearning)
            }
        }
    }
}
