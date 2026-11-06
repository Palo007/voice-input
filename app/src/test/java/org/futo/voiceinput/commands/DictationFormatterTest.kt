package org.futo.voiceinput.commands

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationFormatterTest {
    private companion object {
        val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val MULTILINE = TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val EMAIL = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val URL = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val NUMBER = InputType.TYPE_CLASS_NUMBER
        val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val PHONE = InputType.TYPE_CLASS_PHONE
        val PASSWORD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val CAPS = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        val WORDS = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS

        val ON = VoiceCommandOptions(enabled = true, destructive = true)
        val NO_DESTRUCTIVE = VoiceCommandOptions(enabled = true, destructive = false)
        val OFF = VoiceCommandOptions(enabled = false, destructive = false)
    }

    private data class Case(
        val name: String,
        val spoken: String,
        val expected: String,
        val inputType: Int = MULTILINE,
        val before: String = "",
        val deleteBefore: Int = 0,
        val options: VoiceCommandOptions = ON,
        val language: String? = "en",
        val final: Boolean = true,
        val previous: String? = null,
        val imeOptions: Int = 0
    )

    private val cases = listOf(
        // Plain text and punctuation inside one dictation.
        Case("comma attaches", "Hello comma world.", "Hello, world.", TEXT),
        Case("recognizer punctuation around a command", "Hello, comma, how are you?", "Hello, how are you?", TEXT),
        Case("period starts a sentence", "it works period it is fast", "It works. It is fast", TEXT),
        Case("full stop", "Done full stop.", "Done.", TEXT),
        Case("question mark replaces trailing period", "Are you there question mark.", "Are you there?", TEXT),
        Case("exclamation point", "Wow exclamation point", "Wow!", TEXT),
        Case("exclamation mark and colon", "Note colon. Buy milk exclamation mark", "Note: buy milk!", TEXT),
        Case("comma lowercases a sentence-break capital", "Hello. Comma. How are you?", "Hello, how are you?", TEXT),
        Case("comma keeps I", "Yes comma I agree.", "Yes, I agree.", TEXT),
        Case("commands off", "Hello comma world.", "Hello comma world.", TEXT, options = OFF),
        Case("literal escape", "A literal new line of products.", "A new line of products.", MULTILINE),
        Case("literal comma", "Say literal comma twice.", "Say comma twice.", TEXT),
        Case("multi-word phrase does not cross a sentence", "It is new. Line them up.", "It is new. Line them up.", MULTILINE),
        // Layout.
        Case("new line in multiline", "Dear Anna, new line. thanks for the call.", "Dear Anna,\nThanks for the call.", MULTILINE),
        Case("new paragraph", "First part. New paragraph. Second part.", "First part.\n\nSecond part.", MULTILINE),
        Case("new line dropped in single-line field", "Hello new line world.", "Hello world.", TEXT),
        Case("new line alone in single-line field", "New line.", "", TEXT),
        // Punctuation next to existing text (final only).
        Case("comma after field text with period", "Comma and more.", ", and more.", TEXT, before = "Hello.", deleteBefore = 1),
        Case("question mark after field space", "Question mark.", "?", TEXT, before = "Really. ", deleteBefore = 2),
        Case("new line removes spaces before", "New line. Next.", "\nNext.", MULTILINE, before = "Hello. ", deleteBefore = 1),
        Case("partial does not delete field text", "Comma and more", ", and more", TEXT, before = "Hello.", final = false),
        // Capitalization from the field.
        Case("continuing a sentence keeps a possible name", "John will come.", "John will come.", TEXT, before = "I think "),
        Case("sentence start keeps inner capitals", "iPhone is here.", "iPhone is here.", TEXT),
        Case("continuing keeps acronyms", "USA trip.", "USA trip.", TEXT, before = "the "),
        Case("sentence start capitalizes", "we should go.", "We should go.", TEXT, before = "Done. "),
        Case("empty field capitalizes", "we should go.", "We should go.", TEXT),
        Case("no capitalization flag keeps case", "we should go.", "we should go.", InputType.TYPE_CLASS_TEXT),
        Case("cap characters", "make it loud.", "MAKE IT LOUD.", CAPS),
        Case("cap words", "john smith", "John Smith", WORDS),
        // Email and URL.
        Case("email from words", "John dot Smith at example dot com.", "john.smith@example.com", EMAIL),
        Case("email already formatted", "Anna@Example.com.", "anna@example.com", EMAIL),
        Case("email ignores commands", "a comma b at x dot io", "acommab@x.io", EMAIL),
        Case("url", "Example dot com slash news.", "example.com/news", URL),
        // Number and phone.
        Case("number digits only", "It is 42.", "42", NUMBER),
        Case("number words", "five seven", "57", NUMBER),
        Case("signed decimal", "minus 3.5.", "-3.5", DECIMAL),
        Case("plain number drops sign and separator", "-3.5", "35", NUMBER),
        Case("compound number words are left for the field", "One hundred.", "One hundred", NUMBER),
        Case("tens are left for the field", "twenty", "twenty", NUMBER),
        Case("phone", "Plus 421 905, 123 456.", "+421905123456", PHONE),
        Case("slovak phone words", "nula deväť päť", "095", PHONE, language = "sk"),
        // Password: unchanged, no commands.
        Case("password unchanged", "Hunter comma two.", "Hunter comma two.", PASSWORD),
        // Delete that and undo dictation.
        Case("delete that removes last sentence", "Hello there. How are you? Delete that.", "Hello there.", TEXT),
        Case("delete that then more text", "One. Two. Delete that. Three.", "One. Three.", TEXT),
        Case("undo dictation clears the dictation", "Hello there. Undo dictation.", "", TEXT),
        Case("delete that inside a sentence is text", "Please delete that file.", "Please delete that file.", TEXT),
        Case("delete that before another command is text", "Delete that comma.", "Delete that,", TEXT,
            before = "Hi. Good morning.", previous = " Good morning."),
        Case("destructive off is text", "Hello. Delete that.", "Hello. Delete that.", TEXT, options = NO_DESTRUCTIVE),
        Case("delete that removes previous dictation", "Delete that.", "", TEXT,
            before = "Hi. Good morning.", previous = " Good morning.", deleteBefore = 14),
        Case("undo dictation removes previous dictation", "Undo dictation. Bye.", "Bye.", TEXT,
            before = "Hi. Good morning.", previous = " Good morning.", deleteBefore = 14),
        Case("previous dictation edited by user is kept", "Delete that.", "", TEXT,
            before = "Hi. Good morning!", previous = " Good morning."),
        Case("partial never deletes previous dictation", "Delete that.", "", TEXT,
            before = "Hi. Good morning.", previous = " Good morning.", final = false),
        Case("password blocks destructive commands", "Delete that.", "Delete that.", PASSWORD,
            before = "abc", previous = "abc"),
        Case("no personalized learning blocks destructive commands", "Delete that.", "Delete that.", TEXT,
            before = "Hi. Good morning.", previous = " Good morning.",
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        Case("no personalized learning keeps punctuation", "Hi comma there", "Hi, there", TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        // Slovak.
        Case("slovak comma and new line", "Ahoj čiarka ako sa máš otáznik nový riadok Dobre.",
            "Ahoj, ako sa máš?\nDobre.", MULTILINE, language = "sk"),
        Case("slovak delete", "Prvá veta. Druhá veta. Vymaž to.", "Prvá veta.", TEXT, language = "sk"),
        Case("english phrase in slovak dictation is text", "Ahoj comma svet.", "Ahoj comma svet.", TEXT, language = "sk"),
        Case("slovak literal", "Doslova bodka.", "Bodka.", TEXT, language = "sk"),
    )

    @Test fun formatsEveryCase() {
        val failures = cases.mapNotNull { case ->
            val field = FieldProfile.from(case.inputType, case.imeOptions)
            val result = DictationFormatter.format(case.spoken, case.before, field, case.options, case.language,
                final = case.final, previousDictation = case.previous)
            val expected = FormattedInsertion(case.expected, case.deleteBefore)
            if (result == expected) null else "${case.name}: expected $expected, got $result"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun partialAndFinalRenderTheSameUtterance() {
        val field = FieldProfile.from(MULTILINE, 0)
        val spoken = "Hello comma world new line next"
        val partial = DictationFormatter.format(spoken, "", field, ON, "en", final = false)
        val final = DictationFormatter.format(spoken, "", field, ON, "en", final = true)
        assertEquals("Hello, world\nNext", partial.text)
        assertEquals(partial, final)
    }

    @Test fun fieldProfilesFromEditorInfo() {
        assertTrue(FieldProfile.from(PASSWORD, 0).isPrivate)
        assertTrue(FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0).isPrivate)
        assertTrue(FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0).isPrivate)
        assertTrue(FieldProfile.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0).isPrivate)
        assertTrue(FieldProfile.from(TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).isPrivate)
        assertFalse(FieldProfile.from(TEXT, 0).isPrivate)
        assertFalse(FieldProfile.from(EMAIL, 0).isPrivate)
        assertTrue(FieldProfile.from(MULTILINE, 0).multiline)
        assertFalse(FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE, 0).multiline)
    }

    @Test fun parserFindsCommandsAndKeepsText() {
        val pieces = VoiceCommandParser.parse("Hello, comma, world. New line.", VoiceCommandPhrases.forLanguage("en"), destructive = false)
        assertEquals(listOf(
            DictationPiece.Text("Hello,"), DictationPiece.Command(VoiceCommand.Comma),
            DictationPiece.Text("world."), DictationPiece.Command(VoiceCommand.NewLine)
        ), pieces)
    }

    @Test fun everyCommandHasEnglishAndSlovakPhrases() {
        for (language in listOf("en", "sk")) {
            val phrases = VoiceCommandPhrases.forLanguage(language)
            for (command in VoiceCommand.values()) {
                assertTrue("$language $command", phrases.phrases[command].orEmpty().isNotEmpty())
            }
        }
    }
}
