package org.futo.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterKeyboardLayoutTest {
    @Test fun rowsHoldEachLatinLetterOnce() {
        val letters = LetterKeyboardLayout.rows.joinToString("")
        assertEquals(26, letters.length)
        assertEquals(('a'..'z').toSet(), letters.toSet())
    }

    @Test fun longPressOffersSlovakLetters() {
        val offered = LetterKeyboardLayout.rows.joinToString("").flatMap { LetterKeyboardLayout.accentsFor(it) }.toSet()
        "áäčďéíĺľňóôŕšťúýž".forEach { assertTrue("$it", it.toString() in offered) }
        assertEquals(listOf("á", "ä"), LetterKeyboardLayout.accentsFor('a'))
        assertTrue(LetterKeyboardLayout.accentsFor('q').isEmpty())
    }

    @Test fun topRowOffersDigitsBeforeAccents() {
        assertEquals("1234567890", LetterKeyboardLayout.rows[0].map { LetterKeyboardLayout.alternativesFor(it).first() }.joinToString(""))
        assertEquals(listOf("3", "é", "ě"), LetterKeyboardLayout.alternativesFor('e'))
        assertEquals(listOf("1"), LetterKeyboardLayout.alternativesFor('q'))
        assertEquals(listOf("á", "ä"), LetterKeyboardLayout.alternativesFor('a'))
        assertTrue(LetterKeyboardLayout.alternativesFor('m').isEmpty())
    }

    @Test fun shiftCapitalizesOneLetterOrLocks() {
        assertEquals("a", LetterShift.Off.apply("a"))
        assertEquals("Ô", LetterShift.Once.apply("ô"))
        assertEquals(LetterShift.Off, LetterShift.Once.afterLetter())
        assertEquals(LetterShift.Locked, LetterShift.Locked.afterLetter())
        assertEquals(LetterShift.Once, LetterShift.Off.next())
        assertEquals(LetterShift.Locked, LetterShift.Once.next())
        assertEquals(LetterShift.Off, LetterShift.Locked.next())
    }

    @Test fun slidingPicksTheOptionUnderTheFinger() {
        // Rightward: the first option is over the key, the next one to its right.
        assertEquals(0, LetterKeyboardLayout.accentIndex(10f, 40f, 2, leftward = false))
        assertEquals(1, LetterKeyboardLayout.accentIndex(50f, 40f, 2, leftward = false))
        assertEquals(1, LetterKeyboardLayout.accentIndex(500f, 40f, 2, leftward = false))
        assertEquals(0, LetterKeyboardLayout.accentIndex(-30f, 40f, 2, leftward = false))
        // Leftward: the next option is to the left of the key.
        assertEquals(0, LetterKeyboardLayout.accentIndex(10f, 40f, 2, leftward = true))
        assertEquals(1, LetterKeyboardLayout.accentIndex(-10f, 40f, 2, leftward = true))
        assertEquals(0, LetterKeyboardLayout.accentIndex(80f, 40f, 2, leftward = true))
    }
}
