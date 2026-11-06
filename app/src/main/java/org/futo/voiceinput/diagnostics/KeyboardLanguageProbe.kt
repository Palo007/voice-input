package org.futo.voiceinput.diagnostics

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import kotlinx.serialization.Serializable
import org.futo.voiceinput.recognition.DictationLanguage
import org.futo.voiceinput.recognition.LanguageRouting

/**
 * Language signals visible to the app when dictation starts. Holds only locale tags,
 * an input method id or an input-method package, never text, audio or receiving-app names.
 */
@Serializable
data class DiagnosticLanguageProbe(
    /** "ime" (VoiceInputMethodService) or "activity" (RecognizeActivity). */
    val source: String,
    /** Previous input method id (IME path) or the calling package if it is an input method; otherwise "other". */
    val caller: String? = null,
    val lastSubtypeLocale: String? = null,
    val lastSubtypeMode: String? = null,
    /** Enabled subtype locales of the previous input method. */
    val callerSubtypeLocales: List<String> = emptyList(),
    val hintLocales: List<String> = emptyList(),
    val extraLanguage: String? = null,
    val extraLanguagePreference: String? = null,
    /** "sk" or "en" when a signal named one of them; null means the remembered choice is used. */
    val resolved: String? = null
) {
    fun sanitized(): DiagnosticLanguageProbe = copy(
        source = source.takeIf { it == "ime" || it == "activity" } ?: "unknown",
        caller = caller?.takeIf { it == "other" || idPattern.matches(it) },
        lastSubtypeLocale = lastSubtypeLocale?.let(::sanitizeTag),
        lastSubtypeMode = lastSubtypeMode?.takeIf { modePattern.matches(it) },
        callerSubtypeLocales = callerSubtypeLocales.mapNotNull(::sanitizeTag).take(MAX_TAGS),
        hintLocales = hintLocales.mapNotNull(::sanitizeTag).take(MAX_TAGS),
        extraLanguage = extraLanguage?.let(::sanitizeTag),
        extraLanguagePreference = extraLanguagePreference?.let(::sanitizeTag),
        resolved = resolved?.takeIf { it == "sk" || it == "en" }
    )

    /** One line with locale tags and ids only, for Logcat and the Diagnostics screen. */
    fun describe(): String = buildList {
        add("source=$source")
        add("caller=${caller ?: "none"}")
        add("lastSubtype=${lastSubtypeLocale?.ifEmpty { "(no locale)" } ?: "none"}" +
            (lastSubtypeMode?.let { "/$it" } ?: ""))
        if (source == "ime") add("callerSubtypes=${callerSubtypeLocales.ifEmpty { listOf("none") }.joinToString(",")}")
        if (source == "ime") add("hintLocales=${hintLocales.ifEmpty { listOf("none") }.joinToString(",")}")
        if (source == "activity") add("EXTRA_LANGUAGE=${extraLanguage ?: "none"}")
        if (source == "activity") add("EXTRA_LANGUAGE_PREFERENCE=${extraLanguagePreference ?: "none"}")
        add("resolved=${resolved ?: "none (remembered choice)"}")
    }.joinToString(" ")

    fun resolvedLanguage(): DictationLanguage? = LanguageRouting.parseLanguage(resolved)

    companion object {
        private const val MAX_TAGS = 8
        private val tagPattern = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,4}")
        private val idPattern = Regex("[A-Za-z0-9_.]{1,100}(/[A-Za-z0-9_.$]{1,150})?")
        private val modePattern = Regex("[a-z]{1,20}")

        /** Empty string stays empty: a subtype with no locale is itself a useful observation. */
        internal fun sanitizeTag(tag: String): String? {
            val normalized = tag.trim().replace('_', '-')
            if (normalized.isEmpty()) return ""
            return normalized.takeIf { it.length <= 35 && tagPattern.matches(it) }
        }

        /**
         * The first signal that names a language decides, even if it is neither Slovak nor English:
         * a Czech keyboard must not be overridden by a lower-priority English hint.
         */
        private fun firstNamedLanguage(tags: List<String?>): String? = tags.firstOrNull { !it.isNullOrBlank() }

        /** Pure resolution for the IME path: the previous keyboard's subtype first, then the editor's hint. */
        fun forInputMethod(
            caller: String?,
            lastSubtypeLocale: String?,
            lastSubtypeMode: String?,
            callerSubtypeLocales: List<String>,
            hintLocales: List<String>
        ): DiagnosticLanguageProbe {
            val subtypeLocale = lastSubtypeLocale.takeIf { lastSubtypeMode == null || lastSubtypeMode == "keyboard" }
            val resolved = LanguageRouting.parseLanguage(firstNamedLanguage(listOf(subtypeLocale) + hintLocales))
            return DiagnosticLanguageProbe("ime", caller, lastSubtypeLocale, lastSubtypeMode,
                callerSubtypeLocales, hintLocales, resolved = resolved?.tag).sanitized()
        }

        /** Pure resolution for the activity path: EXTRA_LANGUAGE, then EXTRA_LANGUAGE_PREFERENCE. */
        fun forActivity(caller: String?, extraLanguage: String?, extraLanguagePreference: String?): DiagnosticLanguageProbe {
            val resolved = LanguageRouting.parseLanguage(firstNamedLanguage(listOf(extraLanguage, extraLanguagePreference)))
            return DiagnosticLanguageProbe("activity", caller, extraLanguage = extraLanguage,
                extraLanguagePreference = extraLanguagePreference, resolved = resolved?.tag).sanitized()
        }
    }
}

/** Reads the language signals on the phone. Every call is best effort and never throws. */
object KeyboardLanguageProbe {
    private const val TAG = "LanguageProbe"

    private fun InputMethodSubtype.tag(): String = languageTag.ifEmpty {
        @Suppress("DEPRECATION") locale
    }

    fun fromInputMethod(context: Context, editorInfo: EditorInfo?): DiagnosticLanguageProbe? = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val last = imm.lastInputMethodSubtype
        // There is no public "previous input method" API; find the input method that owns the last subtype.
        val owner = last?.let { subtype ->
            imm.enabledInputMethodList.firstOrNull { info ->
                info.packageName != context.packageName &&
                    (0 until info.subtypeCount).any { info.getSubtypeAt(it) == subtype }
            }
        }
        val ownerLocales = owner?.let { imm.getEnabledInputMethodSubtypeList(it, true).map { s -> s.tag() } }.orEmpty()
        val hints = editorInfo?.hintLocales?.let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }.orEmpty()
        DiagnosticLanguageProbe.forInputMethod(owner?.id, last?.tag(), last?.mode, ownerLocales, hints)
    }.getOrNull()

    fun fromActivity(context: Context, intent: Intent?, callingPackage: String?): DiagnosticLanguageProbe? = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Receiving-app names stay out of diagnostics; only input methods (keyboards) are named.
        val caller = callingPackage?.let { pkg ->
            if (imm.inputMethodList.any { it.packageName == pkg }) pkg else "other"
        }
        DiagnosticLanguageProbe.forActivity(caller,
            intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
            intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
    }.getOrNull()

    /** Keeps the latest probe for the Diagnostics screen and records it when diagnostics are on. */
    fun report(probe: DiagnosticLanguageProbe?) {
        probe ?: return
        AppDiagnostics.languageProbe(probe)
        if (AppDiagnostics.collectionEnabled()) runCatching { Log.i(TAG, probe.describe()) }
    }
}
