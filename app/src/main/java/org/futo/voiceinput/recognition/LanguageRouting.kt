package org.futo.voiceinput.recognition

import java.util.Locale

/** The two dictation languages offered by the SK | EN button. */
enum class DictationLanguage(val tag: String) {
    Slovak("sk"), English("en");

    companion object {
        fun fromSetting(value: String?): DictationLanguage =
            values().firstOrNull { it.tag == value } ?: English
    }
}

/**
 * Chooses the recognition model per recording from the dictation language.
 * The saved model choice (SPEECH_BACKEND) is never changed here.
 */
object LanguageRouting {
    const val SLOVAK_RUNTIME = "parakeet_sk"

    /** Routing applies only when the setting is on and the Slovak model is installed. */
    fun isActive(settingOn: Boolean, slovakInstalled: Boolean): Boolean = settingOn && slovakInstalled

    /** Slovak goes to the Slovak model; everything else keeps the user's selected model. */
    fun routeRuntime(
        selectedRuntime: String,
        settingOn: Boolean,
        slovakInstalled: Boolean,
        language: DictationLanguage?
    ): String = if (isActive(settingOn, slovakInstalled) && language == DictationLanguage.Slovak) {
        SLOVAK_RUNTIME
    } else {
        selectedRuntime
    }

    /** Language given to the English cleanup gates (Harper, S1); null when routing is inactive. */
    fun cleanupLanguage(settingOn: Boolean, slovakInstalled: Boolean, language: DictationLanguage?): String? =
        language?.tag?.takeIf { isActive(settingOn, slovakInstalled) }

    /** Parses a locale tag such as "sk", "sk-SK", "sk_SK" or "en-US". Other languages give null. */
    fun parseLanguage(tag: String?): DictationLanguage? {
        val language = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
        return when (language) {
            "sk", "slk", "slo" -> DictationLanguage.Slovak
            "en", "eng" -> DictationLanguage.English
            else -> null
        }
    }

    /** First Slovak or English tag in priority order wins; unknown tags are skipped. */
    fun firstKnownLanguage(tags: List<String?>): DictationLanguage? = tags.firstNotNullOfOrNull(::parseLanguage)

    /**
     * Language for a new dictation: the keyboard's language when it is Slovak or English,
     * otherwise the remembered choice.
     */
    fun initialLanguage(probed: DictationLanguage?, remembered: DictationLanguage): DictationLanguage =
        probed ?: remembered
}
