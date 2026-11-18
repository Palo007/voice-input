package org.futo.voiceinput.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinScriptTest {
    @Test
    fun spellsPhoneticEnglishInLatinLetters() {
        assertEquals("It stops striming. The changes", LatinScript.toLatin("It стопс стриминг. The changes"))
        assertEquals("Starts striming. begins streaming", LatinScript.toLatin("Стартс стриминг. begins streaming"))
        assertEquals("Or Puts.", LatinScript.toLatin("Or Пуц."))
        assertEquals("shop SHOP Shop", LatinScript.toLatin("шоп ШОП Шоп"))
    }

    @Test
    fun slovakSchemeUsesSlovakLetters() {
        assertEquals("čaj šach žaba jablko", LatinScript.toLatin("чай шах жаба йаблко", LatinScript.Scheme.Slovak))
        assertEquals("Čo", LatinScript.toLatin("Чо", LatinScript.Scheme.Slovak))
    }

    @Test
    fun latinTextIsReturnedUnchanged() {
        val text = "Výborne čakám ťa, zober si všetko."
        assertFalse(LatinScript.containsCyrillic(text))
        assertSame(text, LatinScript.toLatin(text))
    }

    @Test
    fun replacesCyrillicLookalikesInsideLatinWords() {
        // Cyrillic "с" and "о" inside an otherwise Latin word.
        val mixed = "сat dоg"
        assertTrue(LatinScript.containsCyrillic(mixed))
        assertEquals("sat dog", LatinScript.toLatin(mixed))
    }

    @Test
    fun softAndHardSignsAreDropped() {
        assertEquals("obekt", LatinScript.toLatin("объект"))
    }
}
