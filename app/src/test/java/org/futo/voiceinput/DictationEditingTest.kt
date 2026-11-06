package org.futo.voiceinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationEditingTest {
    private fun apply(before: String, edit: DictationEdit): String {
        val plan = DictationEditing.plan(before, edit)
        return before.substring(0, before.length - plan.deleteBefore) + plan.insert
    }

    @Test fun deleteCharacterRemovesOneCharacter() {
        assertEquals("hell", apply("hello", DictationEdit.DeleteCharacter))
        assertEquals("hello", apply("hello ", DictationEdit.DeleteCharacter))
        assertEquals("a\r\n".dropLast(2), apply("a\r\n", DictationEdit.DeleteCharacter))
        assertEquals("a\n", apply("a\n\n", DictationEdit.DeleteCharacter))
    }

    @Test fun deleteCharacterKeepsSurrogatePairsAndEmojiWhole() {
        val cases = listOf(
            "x𝕏" to "x",                           // mathematical X, one surrogate pair
            "a😀" to "a",                                      // plain emoji
            "a👍🏽" to "a",                                     // skin tone modifier
            "a👨‍👩‍👧" to "a",                                     // ZWJ family
            "a❤️‍🔥" to "a",                                     // ZWJ with variation selector
            "a❤️" to "a",                                      // variation selector
            "a1️⃣" to "a",                                     // keycap
            "a🇸🇰" to "a",                                     // flag: two regional indicators
            "🇸🇰🇬🇧" to "🇸🇰",                                  // only the last flag
            "a🇸🇰🇬" to "a🇸🇰",                       // an unpaired indicator alone
            "a🏴󠁧󠁢󠁳󠁣󠁴󠁿" to "a", // tag sequence flag
            "cafe\u0301" to "caf",                     // e with a combining accent is one character
            "zle\u030C" to "zl"                        // e with a combining caron
        )
        cases.forEach { (before, expected) ->
            assertEquals(before, expected, apply(before, DictationEdit.DeleteCharacter))
        }
    }

    @Test fun deleteWordRemovesTheWordAndTheSpacesBeforeIt() {
        assertEquals("hello", apply("hello world", DictationEdit.DeleteWord))
        assertEquals("hello", apply("hello world   ", DictationEdit.DeleteWord))
        assertEquals("", apply("hello", DictationEdit.DeleteWord))
        assertEquals("", apply("   ", DictationEdit.DeleteWord))
        assertEquals("one two", apply("one two three", DictationEdit.DeleteWord))
    }

    @Test fun deleteWordTakesPunctuationWithTheWord() {
        assertEquals("Hello,", apply("Hello, world.", DictationEdit.DeleteWord))
        assertEquals("", apply("Hello,", DictationEdit.DeleteWord))
        assertEquals("Wait", apply("Wait ...", DictationEdit.DeleteWord))
        assertEquals("It's", apply("It's (really) ", DictationEdit.DeleteWord))
        assertEquals("ok", apply("ok 👍🏽", DictationEdit.DeleteWord))
    }

    @Test fun deleteWordStopsAtLineBreaks() {
        assertEquals("line one\n", apply("line one\nsecond", DictationEdit.DeleteWord))
        assertEquals("line one", apply("line one\n", DictationEdit.DeleteWord))
        assertEquals("line one", apply("line one\n  ", DictationEdit.DeleteWord))
        assertEquals("line one", apply("line one\r\n", DictationEdit.DeleteWord))
    }

    @Test fun emptyFieldPlansNothing() {
        assertTrue(DictationEditing.plan("", DictationEdit.DeleteCharacter).isNoop)
        assertTrue(DictationEditing.plan("", DictationEdit.DeleteWord).isNoop)
        assertEquals("?", apply("", DictationEdit.QuestionMark))
        assertEquals(" ", apply("", DictationEdit.Space))
    }

    @Test fun questionMarkReplacesAFinalPeriodOrCommaAndSpaces() {
        assertEquals("How are you?", apply("How are you.", DictationEdit.QuestionMark))
        assertEquals("How are you?", apply("How are you. ", DictationEdit.QuestionMark))
        assertEquals("Really?", apply("Really,", DictationEdit.QuestionMark))
        assertEquals("Why?", apply("Why", DictationEdit.QuestionMark))
        assertEquals("Wait..?", apply("Wait...", DictationEdit.QuestionMark))
        assertEquals("What!?", apply("What!", DictationEdit.QuestionMark))
        assertEquals("Line\n?", apply("Line\n", DictationEdit.QuestionMark))
    }

    @Test fun spaceAppendsOneSpace() {
        assertEquals("word ", apply("word", DictationEdit.Space))
        assertEquals("word  ", apply("word ", DictationEdit.Space))
    }

    @Test fun selectionsAreReplacedLikeAKeyboard() {
        assertEquals("", DictationEditing.selectionReplacement(DictationEdit.DeleteCharacter))
        assertEquals("", DictationEditing.selectionReplacement(DictationEdit.DeleteWord))
        assertEquals("?", DictationEditing.selectionReplacement(DictationEdit.QuestionMark))
        assertEquals(" ", DictationEditing.selectionReplacement(DictationEdit.Space))
    }
}
