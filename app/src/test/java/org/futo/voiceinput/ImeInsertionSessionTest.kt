package org.futo.voiceinput

import org.junit.Assert.*
import org.junit.Test

class ImeInsertionSessionTest {
    private open class Editor(var text: String = "", var start: Int = text.length, var end: Int = start) : InsertionEditor {
        var composingStart = -1
        var composingEnd = -1
        var accepts = true
        var writes = 0
        var available = true
        var extracted = true
        override fun snapshot() = if (available) InsertionSnapshot(text, start, end, if (extracted) 0 else -1) else null
        override fun replace(text: String, composing: Boolean): Boolean {
            writes++
            if (!accepts) return false
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
        override fun textBeforeCursor(max: Int): String? =
            if (available) text.substring(maxOf(0, minOf(start, end) - max), minOf(start, end)) else null
        override fun edit(deleteBefore: Int, insert: String): Boolean {
            writes++
            if (!accepts) return false
            val from = minOf(start, end) - deleteBefore
            text = text.replaceRange(from, maxOf(start, end), insert)
            start = from + insert.length
            end = start
            return true
        }
    }

    @Test fun repeatedPartialsReplaceSelectionAndFinalCommitsOnce() {
        val editor = Editor("hello old world", 6, 9)
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("new"))
        assertTrue(session.partial("new"))
        assertEquals(1, editor.writes)
        assertTrue(session.finish("better", "en"))
        assertFalse(session.finish("better", "en"))
        assertEquals("hello better world", editor.text)
        assertEquals(2, editor.writes)
    }

    @Test fun rejectedOperationsCanBeRetriedAndIdenticalLaterUtterancesAreDelivered() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        editor.accepts = false
        assertFalse(session.partial("hello"))
        editor.accepts = true
        assertTrue(session.partial("hello"))
        editor.accepts = false
        assertFalse(session.finish("hello", "en"))
        editor.accepts = true
        assertTrue(session.finish("hello", "en"))
        editor.text = ""
        editor.start = 0
        editor.end = 0
        assertTrue(ImeInsertionSession(editor).finish("hello", "en"))
        assertEquals("hello", editor.text)
    }

    @Test fun lostCompositionWithUnchangedTextNeverDuplicatesFinal() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello"))
        editor.finishComposition()
        session.selectionChanged(5, 5, -1, -1)
        assertFalse(session.finish("hello world", "en"))
        assertEquals("hello", editor.text)
    }

    @Test fun spacingUsesCurrentContextAroundTheWholeReplacement() {
        val editor = Editor("helloworld", 5)
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("new"))
        assertEquals("hello new world", editor.text)
        assertTrue(session.finish("better", "en"))
        assertEquals("hello better world", editor.text)
    }

    @Test fun punctuationWhitespaceAndUnspacedScriptsDoNotGainIndiscriminateSpaces() {
        val cases = listOf(
            Triple("hello|, world", "friend", "hello friend, world"),
            Triple("hello | world", "friend", "hello friend world"),
            Triple("hello|", ",", "hello,"),
            Triple("(|)", "word", "(word)"),
            Triple("hello.|Next", "Yes.", "hello. Yes. Next"),
            Triple("你|吗", "好", "你好吗"),
            Triple("ก|ข", "ค", "กคข"),
            Triple("hello|world", "", "helloworld")
        )
        for ((context, result, expected) in cases) {
            val cursor = context.indexOf('|')
            val editor = Editor(context.replace("|", ""), cursor)
            assertTrue(ImeInsertionSession(editor).finish(result, null))
            assertEquals(context, expected, editor.text)
        }
        val editor = Editor("abcxyz", 3)
        assertTrue(ImeInsertionSession(editor).finish("def", "ja"))
        assertEquals("abcdefxyz", editor.text)
    }

    @Test fun cursorMovementAndUserEditsArePreservedWithoutWaitingForNotifications() {
        for (moveCursor in listOf(true, false)) {
            val editor = Editor()
            val session = ImeInsertionSession(editor)
            assertTrue(session.partial("hello"))
            if (moveCursor) { editor.start = 0; editor.end = 0 } else editor.text = "other"
            assertFalse(session.finish("hello world", "en"))
            assertEquals(if (moveCursor) "hello" else "other", editor.text)
        }
    }

    @Test fun cancellationRetainsAcceptedPartialWithoutOverwritingSelection() {
        val editor = Editor("old", 0, 3)
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("new"))
        session.cancel()
        assertFalse(session.finish("later", "en"))
        assertEquals("new", editor.text)
        assertEquals(-1, editor.composingStart)
    }

    @Test fun cancellingAfterCursorMovementCannotLeaveACompositionForTheNextUtterance() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello"))
        editor.start = 0
        editor.end = 0
        assertFalse(session.finish("discarded", "en"))
        session.cancel()
        assertTrue(ImeInsertionSession(editor).finish("next", "en"))
        assertEquals("next hello", editor.text)
    }

    @Test fun emptyResultsPreserveSelectionsButAnEmptyFinalClearsOurOwnPartial() {
        val editor = Editor("old", 0, 3)
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial(""))
        assertEquals("old", editor.text)
        assertTrue(session.partial("new"))
        assertTrue(session.partial(""))
        assertEquals("new", editor.text)
        assertTrue(session.finish("", "en"))
        assertEquals("", editor.text)
        val untouched = Editor("old", 0, 3)
        assertTrue(ImeInsertionSession(untouched).finish("", "en"))
        assertEquals("old", untouched.text)
    }

    @Test fun missingContextUsesFinalOnlyAndMissingConnectionRejectsDelivery() {
        val editor = Editor("beforeafter", 6).apply { extracted = false }
        val session = ImeInsertionSession(editor)
        assertFalse(session.partial("word"))
        assertTrue(session.finish("word", "en"))
        assertEquals("before word after", editor.text)
        val missing = Editor().apply { available = false }
        assertFalse(ImeInsertionSession(missing).finish("word", "en"))
    }

    @Test fun delayedAcknowledgmentsOfOurEarlierPartialsDoNotCancelTheSession() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("one"))
        assertTrue(session.partial("one two"))
        session.selectionChanged(3, 3, 0, 3)
        session.selectionChanged(7, 7, 0, 7)
        assertTrue(session.finish("one two three", "en"))
        assertEquals("one two three", editor.text)
    }

    @Test fun spacingCanBeDisabledForStructuredFields() {
        val editor = Editor("a@c", 1)
        assertTrue(ImeInsertionSession(editor, automaticSpacing = false).finish("b", "en"))
        assertEquals("ab@c", editor.text)
    }

    @Test fun recreationRetainsTheUtteranceButDismissalAndEditorChangesEndIt() {
        val editor = Editor()
        val key = ImeEditorKey("editor", 1, 1)
        val lifecycle = ImeInputSessionLifecycle { }
        lifecycle.startInput(key, editor, restarting = false)
        assertTrue(lifecycle.startView(editor, editor, automaticSpacing = true))
        val original = checkNotNull(lifecycle.insertion)
        assertTrue(original.partial("hello"))
        lifecycle.finishView(recreating = true)
        lifecycle.startInput(key, editor, restarting = true)
        assertFalse(lifecycle.startView(editor, editor, automaticSpacing = true))
        assertTrue(checkNotNull(lifecycle.insertion).partial("hello"))
        assertEquals(1, editor.writes)
        assertTrue(checkNotNull(lifecycle.insertion).finish("hello world", "en"))
        assertEquals("hello world", editor.text)

        lifecycle.finishView(recreating = false)
        assertFalse(original.finish("stale", "en"))
        assertTrue(lifecycle.startView(editor, editor, automaticSpacing = true))
        val next = checkNotNull(lifecycle.insertion)
        assertTrue(next.partial("again"))
        val other = Editor()
        lifecycle.startInput(key.copy(fieldId = 2), other, restarting = false)
        assertTrue(lifecycle.startView(other, other, automaticSpacing = true))
        assertFalse(next.finish("stale", "en"))
        assertTrue(checkNotNull(lifecycle.insertion).finish("hello", "en"))
        assertEquals("hello", other.text)
        assertEquals("hello world again", editor.text)
    }

    // Dictation editing: live text is committed, then the edit applies before the cursor.

    @Test fun deleteWhileComposingCommitsLiveTextThenDeletesFromIt() {
        val editor = Editor("Note:", 5)
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello world."))
        assertEquals("Note: hello world.", editor.text)
        var commits = 0
        assertTrue(session.edit(DictationEdit.DeleteWord) { commits++ })
        assertEquals(1, commits)
        assertEquals(-1, editor.composingStart)
        assertEquals("Note: hello", editor.text)
        assertTrue(session.edit(DictationEdit.DeleteCharacter) { commits++ })
        assertEquals("Note: hell", editor.text)
        // The recognizer now sends only text after the committed part.
        assertTrue(session.partial("again"))
        assertEquals("Note: hell again", editor.text)
        assertTrue(session.finish("again later", "en"))
        assertEquals("Note: hell again later", editor.text)
    }

    @Test fun lateSelectionUpdatesFromEditsAndOldLiveTextKeepOwnership() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("one two"))
        assertTrue(session.edit(DictationEdit.DeleteWord) { })
        assertTrue(session.partial("three"))
        assertEquals("one three", editor.text)
        // Notifications for the first partial, the commit and the delete arrive late, in order.
        session.selectionChanged(7, 7, 0, 7)
        session.selectionChanged(7, 7, -1, -1)
        session.selectionChanged(3, 3, -1, -1)
        session.selectionChanged(9, 9, 3, 9)
        assertTrue(session.finish("three four", "en"))
        assertEquals("one three four", editor.text)
    }

    @Test fun editsOnTheSelectionReplaceItLikeAKeyboard() {
        val editor = Editor("keep this text", 5, 9)
        val session = ImeInsertionSession(editor)
        assertTrue(session.edit(DictationEdit.DeleteWord) { })
        assertEquals("keep  text", editor.text)
        editor.start = 4; editor.end = 5
        assertTrue(session.edit(DictationEdit.QuestionMark) { })
        assertEquals("keep? text", editor.text)
    }

    @Test fun editsInAnEmptyFieldChangeNothingAndDictationStillInserts() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.edit(DictationEdit.DeleteCharacter) { })
        assertTrue(session.edit(DictationEdit.DeleteWord) { })
        assertEquals(0, editor.writes)
        assertTrue(session.edit(DictationEdit.Space) { })
        assertEquals(" ", editor.text)
        assertTrue(session.finish("hi", "en"))
        assertEquals(" hi", editor.text)
    }

    @Test fun questionMarkReplacesTheFinalPeriodOfLiveText() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("How are you."))
        assertTrue(session.edit(DictationEdit.QuestionMark) { })
        assertEquals("How are you?", editor.text)
        assertTrue(session.finish("Fine.", "en"))
        assertEquals("How are you? Fine.", editor.text)
    }

    @Test fun failedCommitLeavesLiveTextComposingAndDoesNotFreeze() {
        val editor = object : Editor() {
            var finishing = true
            override fun finishComposition(): Boolean = finishing && super.finishComposition()
        }
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello"))
        editor.finishing = false
        var commits = 0
        assertFalse(session.edit(DictationEdit.DeleteCharacter) { commits++ })
        assertEquals(0, commits)
        assertEquals("hello", editor.text)
        assertTrue(session.partial("hello there"))
        assertEquals("hello there", editor.text)
    }

    @Test fun editWithoutOwnershipStillAppliesButDoesNotRevive() {
        val editor = Editor()
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello"))
        session.selectionChanged(2, 2, 0, 5)
        editor.start = 5; editor.end = 5
        assertTrue(session.edit(DictationEdit.DeleteCharacter) { })
        assertEquals("hell", editor.text)
        assertFalse(session.finish("hello world", "en"))
        assertEquals("hell", editor.text)
    }

    @Test fun cursorMovedByTheUserAfterAnEditEndsOwnership() {
        val editor = Editor("abc")
        val session = ImeInsertionSession(editor)
        assertTrue(session.partial("hello"))
        assertTrue(session.edit(DictationEdit.Space) { })
        assertEquals("abc hello ", editor.text)
        // Our own late notifications are fine; the user's tap elsewhere is not.
        session.selectionChanged(9, 9, -1, -1)
        session.selectionChanged(10, 10, -1, -1)
        session.selectionChanged(2, 2, -1, -1)
        assertFalse(session.partial("more"))
        assertEquals("abc hello ", editor.text)
    }

    @Test fun deleteWordReadsAWindowThatStartsInsideAnEmoji() {
        val plan = DictationEditing.plan("\uDE00" + "x".repeat(10), DictationEdit.DeleteWord)
        assertEquals(10, plan.deleteBefore)
    }
}
