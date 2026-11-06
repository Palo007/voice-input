package org.futo.voiceinput

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.futo.voiceinput.commands.FieldProfile
import org.futo.voiceinput.commands.LastDictationMemory
import org.futo.voiceinput.commands.VoiceCommandOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice commands and field formatting applied through [ImeInsertionSession] to a fake editor. */
class VoiceCommandInsertionTest {
    private class Editor(var text: String = "", var extracted: Boolean = true) : InsertionEditor {
        var start = text.length
        var end = start
        var composingStart = -1
        var composingEnd = -1
        var writes = 0
        override fun snapshot() = InsertionSnapshot(text, start, end, if (extracted) 0 else -1)
        override fun replace(text: String, composing: Boolean): Boolean {
            writes++
            val from = if (composingStart >= 0) composingStart else minOf(start, end)
            val to = if (composingStart >= 0) composingEnd else maxOf(start, end)
            this.text = this.text.replaceRange(from, to, text)
            start = from + text.length
            end = start
            composingStart = if (composing) from else -1
            composingEnd = if (composing) start else -1
            return true
        }
        override fun finishComposition(): Boolean { composingStart = -1; composingEnd = -1; return true }
        override fun textBeforeCursor(max: Int): String = text.substring(maxOf(0, minOf(start, end) - max), minOf(start, end))
        override fun replaceRange(start: Int, end: Int, text: String, cursor: Int): Boolean {
            writes++
            check(composingStart < 0) { "a composing region would swallow the commit" }
            this.text = this.text.replaceRange(start, end, text)
            this.start = cursor
            this.end = cursor
            return true
        }
        override fun edit(deleteBefore: Int, insert: String): Boolean {
            writes++
            check(composingStart < 0)
            val from = minOf(start, end) - deleteBefore
            text = text.replaceRange(from, maxOf(start, end), insert)
            start = from + insert.length
            end = start
            return true
        }
    }

    private val multiline = FieldProfile.from(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
    private val on = VoiceCommandOptions(enabled = true, destructive = true)
    private val key = ImeEditorKey("app", 1, 1)

    private fun session(editor: Editor, field: FieldProfile = multiline, memory: LastDictationMemory = LastDictationMemory(),
        options: VoiceCommandOptions = on) =
        ImeInsertionSession(editor, automaticSpacing = true, formatting = InsertionFormatting(field, { options }, memory, key))

    @Test fun partialsRenderCommandsAndFinalCommitsOnce() {
        val editor = Editor()
        val s = session(editor)
        assertTrue(s.partial("Hello comma", "en"))
        assertEquals("Hello,", editor.text)
        assertTrue(s.partial("Hello comma world new line", "en"))
        assertEquals("Hello, world\n", editor.text)
        assertTrue(s.finish("Hello comma world new line next.", "en"))
        assertEquals("Hello, world\nNext.", editor.text)
        assertFalse(s.finish("Hello comma world new line next.", "en"))
        assertEquals("Hello, world\nNext.", editor.text)
    }

    @Test fun punctuationAttachesToFieldTextAfterLiveText() {
        val editor = Editor("Hello. ")
        val s = session(editor)
        assertTrue(s.partial("comma and", "en"))
        assertEquals("Hello. , and", editor.text)
        assertTrue(s.finish("Comma and more.", "en"))
        assertEquals("Hello, and more.", editor.text)
        assertEquals(editor.text.length, editor.start)
    }

    @Test fun punctuationAttachesWithoutOffsets() {
        val editor = Editor("Hello.", extracted = false)
        assertTrue(session(editor).finish("Question mark.", "en"))
        assertEquals("Hello?", editor.text)
    }

    @Test fun deleteThatRemovesThePreviousDictation() {
        val memory = LastDictationMemory()
        val editor = Editor("Hi.")
        assertTrue(session(editor, memory = memory).finish("Good morning.", "en"))
        assertEquals("Hi. Good morning.", editor.text)
        assertTrue(session(editor, memory = memory).finish("Delete that.", "en"))
        assertEquals("Hi.", editor.text)
        // Only one level: a second "delete that" has nothing left to remove.
        assertTrue(session(editor, memory = memory).finish("Delete that.", "en"))
        assertEquals("Hi.", editor.text)
    }

    @Test fun undoDictationAfterLiveTextReplacesIt() {
        val memory = LastDictationMemory()
        val editor = Editor("Hi.")
        assertTrue(session(editor, memory = memory).finish("Good morning.", "en"))
        val s = session(editor, memory = memory)
        assertTrue(s.partial("Undo dictation", "en"))
        assertEquals("Hi. Good morning.", editor.text)
        assertTrue(s.finish("Undo dictation. Bye.", "en"))
        assertEquals("Hi. Bye.", editor.text)
    }

    @Test fun userEditsProtectThePreviousDictation() {
        val memory = LastDictationMemory()
        val editor = Editor("Hi.")
        assertTrue(session(editor, memory = memory).finish("Good morning.", "en"))
        editor.text += "!"
        editor.start = editor.text.length
        editor.end = editor.start
        assertTrue(session(editor, memory = memory).finish("Delete that.", "en"))
        assertEquals("Hi. Good morning.!", editor.text)
    }

    @Test fun memoryExpiresAndBelongsToOneField() {
        var now = 0L
        val memory = LastDictationMemory(clock = { now })
        memory.remember(key, "text")
        assertNull(memory.recall(ImeEditorKey("app", 2, 1)))
        assertEquals("text", memory.recall(key))
        now += LastDictationMemory.TTL_MS + 1
        assertNull(memory.recall(key))
    }

    @Test fun privateFieldsAreNotRemembered() {
        val memory = LastDictationMemory()
        val field = FieldProfile.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val editor = Editor()
        assertTrue(session(editor, field, memory).finish("Secret words.", "en"))
        assertNull(memory.recall(key))
        assertTrue(session(editor, field, memory).finish("Delete that.", "en"))
        assertEquals("Secret words. Delete that.", editor.text)
    }

    @Test fun emailFieldGetsAddressFormatting() {
        val field = FieldProfile.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0)
        val editor = Editor()
        val s = ImeInsertionSession(editor, automaticSpacing = false,
            formatting = InsertionFormatting(field, { on }, LastDictationMemory(), key))
        assertTrue(s.finish("Anna at example dot com.", "en"))
        assertEquals("anna@example.com", editor.text)
    }

    @Test fun liveTextOfACommandStaysUntilTheFinalRemovesIt() {
        val memory = LastDictationMemory()
        val editor = Editor("Hi.")
        assertTrue(session(editor, memory = memory).finish("Good morning.", "en"))
        val s = session(editor, memory = memory)
        assertTrue(s.partial("Delete", "en"))
        assertEquals("Hi. Good morning. Delete", editor.text)
        val writes = editor.writes
        // Formats to nothing: no empty composing span is written.
        assertTrue(s.partial("Delete that", "en"))
        assertEquals(writes, editor.writes)
        assertTrue(s.finish("Delete that.", "en"))
        assertEquals("Hi.", editor.text)
    }

    @Test fun aSelectionIsReplacedWithoutDeletingTextBeforeIt() {
        val memory = LastDictationMemory()
        val editor = Editor("Hi.")
        assertTrue(session(editor, memory = memory).finish("Good morning.", "en"))
        editor.text += " old"
        editor.start = editor.text.length - 3
        editor.end = editor.text.length
        assertTrue(session(editor, memory = memory).finish("Comma new.", "en"))
        assertEquals("Hi. Good morning. , new.", editor.text)
    }
}
