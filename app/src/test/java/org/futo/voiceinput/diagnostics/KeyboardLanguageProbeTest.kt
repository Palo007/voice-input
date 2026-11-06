package org.futo.voiceinput.diagnostics

import org.futo.voiceinput.recognition.DictationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLanguageProbeTest {
    @Test
    fun futoKeyboardSubtypeWithoutLocaleLeavesChoiceUnchanged() {
        val probe = DiagnosticLanguageProbe.forInputMethod(
            "org.futo.inputmethod.latin/.LatinIME", "", "keyboard", listOf(""), emptyList())
        assertNull(probe.resolved)
        assertNull(probe.resolvedLanguage())
        assertEquals("", probe.lastSubtypeLocale)
        assertTrue(probe.describe().contains("lastSubtype=(no locale)/keyboard"))
    }

    @Test
    fun inputMethodSubtypeLocaleWinsOverHintLocales() {
        val probe = DiagnosticLanguageProbe.forInputMethod("a.b/.Ime", "sk_SK", "keyboard", listOf("sk_SK", "en_US"), listOf("en-US"))
        assertEquals(DictationLanguage.Slovak, probe.resolvedLanguage())
        assertEquals("sk-SK", probe.lastSubtypeLocale)
    }

    @Test
    fun hintLocalesUsedOnlyWhenSubtypeNamesNoLanguage() {
        // A Czech keyboard decides (unchanged button); a lower-priority English hint must not override it.
        assertNull(DiagnosticLanguageProbe.forInputMethod(null, "cs", "keyboard", emptyList(), listOf("en-GB")).resolved)
        assertEquals("en", DiagnosticLanguageProbe.forInputMethod(null, "", "keyboard", emptyList(), listOf("en-GB", "sk")).resolved)
        assertNull(DiagnosticLanguageProbe.forInputMethod(null, null, null, emptyList(), listOf("de", "en-GB")).resolved)
        // A non-keyboard subtype (for example another voice IME) is not the keyboard language.
        assertEquals("en", DiagnosticLanguageProbe.forInputMethod(null, "sk", "voice", emptyList(), listOf("en")).resolved)
        assertNull(DiagnosticLanguageProbe.forInputMethod(null, "cs-CZ", "keyboard", emptyList(), listOf("pl")).resolved)
    }

    @Test
    fun activityExtrasInPriorityOrder() {
        assertEquals("sk", DiagnosticLanguageProbe.forActivity("other", "sk-SK", "en-US").resolved)
        assertNull(DiagnosticLanguageProbe.forActivity(null, "cs", "en_US").resolved)
        assertEquals("en", DiagnosticLanguageProbe.forActivity(null, null, "en_US").resolved)
        assertNull(DiagnosticLanguageProbe.forActivity(null, null, null).resolved)
    }

    @Test
    fun sanitizingDropsAnythingButTagsAndIds() {
        val probe = DiagnosticLanguageProbe("ime", caller = "hello world!", lastSubtypeLocale = "not a tag",
            lastSubtypeMode = "Keyboard!", hintLocales = List(20) { "en" } + "some text", extraLanguage = "x".repeat(50),
            resolved = "cs").sanitized()
        assertNull(probe.caller)
        assertNull(probe.lastSubtypeLocale)
        assertNull(probe.lastSubtypeMode)
        assertEquals(8, probe.hintLocales.size)
        assertNull(probe.extraLanguage)
        assertNull(probe.resolved)
        assertEquals("unknown", DiagnosticLanguageProbe("receiver").sanitized().source)
        assertEquals("other", DiagnosticLanguageProbe("activity", caller = "other").sanitized().caller)
    }

    @Test
    fun probeSurvivesRecordSerialization() {
        val probe = DiagnosticLanguageProbe.forInputMethod("org.futo.inputmethod.latin/.LatinIME", "", "keyboard",
            emptyList(), listOf("sk"))
        val record = DiagnosticRecord(timestampMs = 1L, event = DiagnosticEvent.LANGUAGE_PROBE, languageProbe = probe)
        val decoded = DiagnosticStore.json.decodeFromString(DiagnosticRecord.serializer(),
            DiagnosticStore.json.encodeToString(DiagnosticRecord.serializer(), record))
        assertEquals(probe, decoded.languageProbe)
        assertFalse(decoded.languageProbe!!.describe().contains("EXTRA_LANGUAGE"))
    }
}
