package org.futo.voiceinput.recognition

import org.futo.voiceinput.harper.HarperEnglishGate
import org.futo.voiceinput.s1.S1MiniEnglishGate
import org.futo.voiceinput.settings.DICTATION_LANGUAGE
import org.futo.voiceinput.settings.ROUTE_BY_LANGUAGE
import org.futo.voiceinput.settings.SpeechBackendType
import org.futo.voiceinput.settings.toSpeechBackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRoutingTest {
    private val selected = "parakeet_ultra"

    @Test
    fun settingOffKeepsSelectedModel() {
        DictationLanguage.values().forEach {
            assertEquals(selected, LanguageRouting.routeRuntime(selected, settingOn = false, slovakInstalled = true, it))
        }
        assertNull(LanguageRouting.cleanupLanguage(settingOn = false, slovakInstalled = true, DictationLanguage.Slovak))
    }

    @Test
    fun slovakModelMissingMakesSlovakChoiceHaveNoEffect() {
        assertEquals(selected, LanguageRouting.routeRuntime(selected, settingOn = true, slovakInstalled = false,
            DictationLanguage.Slovak))
        assertFalse(LanguageRouting.isActive(settingOn = true, slovakInstalled = false))
        assertNull(LanguageRouting.cleanupLanguage(settingOn = true, slovakInstalled = false, DictationLanguage.Slovak))
    }

    @Test
    fun slovakRoutesToSlovakModel() {
        assertEquals("parakeet_sk", LanguageRouting.routeRuntime(selected, true, true, DictationLanguage.Slovak))
        assertEquals("parakeet_sk", LanguageRouting.routeRuntime("asr4all", true, true, DictationLanguage.Slovak))
        assertEquals(SpeechBackendType.ParakeetSlovak, LanguageRouting.SLOVAK_RUNTIME.toSpeechBackendType())
    }

    @Test
    fun englishRoutesToSelectedModel() {
        assertEquals(selected, LanguageRouting.routeRuntime(selected, true, true, DictationLanguage.English))
        assertEquals("whisper_ggml", LanguageRouting.routeRuntime("whisper_ggml", true, true, DictationLanguage.English))
    }

    @Test
    fun noOrOtherLanguageKeepsSelectedModel() {
        assertEquals(selected, LanguageRouting.routeRuntime(selected, true, true, null))
        assertEquals(selected, LanguageRouting.routeRuntime(selected, true, true, LanguageRouting.parseLanguage("cs")))
    }

    @Test
    fun parsesLocaleTags() {
        listOf("sk", "sk-SK", "sk_SK", "SK", " sk-sk ").forEach {
            assertEquals(it, DictationLanguage.Slovak, LanguageRouting.parseLanguage(it))
        }
        listOf("en", "en-US", "en_GB", "EN-us").forEach {
            assertEquals(it, DictationLanguage.English, LanguageRouting.parseLanguage(it))
        }
        listOf("cs", "cs-CZ", "pl", "de-DE", "", null, "skx", "eng-x").forEach {
            if (it == "eng-x") assertEquals(DictationLanguage.English, LanguageRouting.parseLanguage(it))
            else assertNull(it.toString(), LanguageRouting.parseLanguage(it))
        }
    }

    @Test
    fun firstKnownLanguageSkipsUnknownTags() {
        assertEquals(DictationLanguage.Slovak, LanguageRouting.firstKnownLanguage(listOf(null, "cs", "sk-SK", "en")))
        assertNull(LanguageRouting.firstKnownLanguage(listOf("cs", "", null)))
    }

    @Test
    fun keyboardLanguageWinsOtherwiseRememberedChoice() {
        val remembered = DictationLanguage.fromSetting("sk")
        assertEquals(DictationLanguage.Slovak, remembered)
        assertEquals(DictationLanguage.English, LanguageRouting.initialLanguage(DictationLanguage.English, remembered))
        assertEquals(DictationLanguage.Slovak, LanguageRouting.initialLanguage(null, remembered))
        // Unknown or missing saved values fall back to English, the setting's default.
        assertEquals(DictationLanguage.English, DictationLanguage.fromSetting(null))
        assertEquals(DictationLanguage.English, DictationLanguage.fromSetting("cs"))
        assertEquals("en", DICTATION_LANGUAGE.default)
        assertFalse(ROUTE_BY_LANGUAGE.default)
    }

    private fun harper(backend: SpeechBackendType, forced: String?) = HarperEnglishGate.isEstablishedEnglish(
        backend, detectedLanguage = null, forcedLanguage = forced, nemotronProfile = "balanced",
        nemotronLanguage = "en", enabledWhisperLanguages = setOf("en"), explicitlyEnglish = false)

    private fun s1(backend: SpeechBackendType, forced: String?) = S1MiniEnglishGate.isEstablishedEnglish(
        backend, null, forced, "balanced", "en", setOf("en"))

    @Test
    fun cleanupLanguageOpensEnglishGatesOnlyForEnglish() {
        val sk = LanguageRouting.cleanupLanguage(true, true, DictationLanguage.Slovak)
        val en = LanguageRouting.cleanupLanguage(true, true, DictationLanguage.English)
        assertEquals("sk", sk)
        assertEquals("en", en)
        // SK routes to the Slovak model: both gates stay closed.
        assertFalse(harper(SpeechBackendType.ParakeetSlovak, sk))
        assertFalse(s1(SpeechBackendType.ParakeetSlovak, sk))
        // EN routes to the selected model: both gates open, even without the "explicitly English" declaration.
        assertTrue(harper(SpeechBackendType.ParakeetUltra, en))
        assertTrue(s1(SpeechBackendType.ParakeetUltra, en))
        // The user selected the Slovak model itself and chose EN: English is forced, so cleanup runs.
        assertTrue(harper(SpeechBackendType.ParakeetSlovak, en))
        assertTrue(s1(SpeechBackendType.ParakeetSlovak, en))
        // A Slovak choice also blocks cleanup on a model that would otherwise be assumed English.
        assertFalse(s1(SpeechBackendType.ParakeetUltra, sk))
    }

    @Test
    fun routingOffLeavesGatesToTheirBackendDefaults() {
        val none = LanguageRouting.cleanupLanguage(false, true, DictationLanguage.English)
        assertNull(none)
        assertTrue(s1(SpeechBackendType.ParakeetUltra, none))
        assertFalse(harper(SpeechBackendType.ParakeetUltra, none))
    }
}
