package org.futo.voiceinput

import org.futo.voiceinput.commands.DictationFormatter
import org.futo.voiceinput.commands.FieldProfile
import org.futo.voiceinput.commands.LastDictationMemory
import org.futo.voiceinput.commands.VoiceCommandOptions

/** The editor boundary. Snapshots are transient and must never be logged or persisted. */
internal interface InsertionEditor {
    fun snapshot(): InsertionSnapshot?
    fun replace(text: String, composing: Boolean): Boolean
    fun finishComposition(): Boolean
    /** Up to [max] UTF-16 units before the cursor, or null when the editor cannot say. */
    fun textBeforeCursor(max: Int): String?
    /** Deletes [deleteBefore] units before the cursor, then commits [insert] over the selection, as one edit. */
    fun edit(deleteBefore: Int, insert: String): Boolean
    /** Replaces absolute [start] until [end] with [text], then puts the cursor at absolute [cursor], as one edit. */
    fun replaceRange(start: Int, end: Int, text: String, cursor: Int): Boolean
}

internal class InsertionSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val offset: Int = 0
)

/** [imeOptions] is part of the key so a restart that turns a field private starts a new session. */
internal data class ImeEditorKey(val packageName: String?, val fieldId: Int, val inputType: Int, val imeOptions: Int = 0)

/**
 * Voice commands and field formatting for one field. [options] is read on each delivery because
 * settings load asynchronously. [memory] holds the last dictation for "delete that" under [key].
 */
internal class InsertionFormatting(
    val field: FieldProfile,
    val options: () -> VoiceCommandOptions,
    val memory: LastDictationMemory,
    val key: ImeEditorKey
)

/** Owns dictation across Android input callbacks, including view-only recreation. */
internal class ImeInputSessionLifecycle(
    /** See [ImeInsertionSession.freezeLiveText]. */
    private val freezeLiveText: () -> Boolean = { false },
    private val resetRecognizer: () -> Unit
) {
    var insertion: ImeInsertionSession? = null
        private set
    private var editorKey: ImeEditorKey? = null
    private var connection: Any? = null
    private var needsInitialization = true

    fun startInput(key: ImeEditorKey, connection: Any?, restarting: Boolean) {
        if (!restarting || editorKey != key || this.connection !== connection) cancel()
        editorKey = key
        this.connection = connection
    }

    fun startView(connection: Any?, editor: InsertionEditor?, automaticSpacing: Boolean,
        formatting: InsertionFormatting? = null): Boolean {
        if (this.connection !== connection) cancel()
        this.connection = connection
        if (!needsInitialization) return false
        insertion = editor?.let { ImeInsertionSession(it, automaticSpacing, formatting, freezeLiveText) }
        needsInitialization = false
        return true
    }

    fun finishView(recreating: Boolean) {
        if (!recreating) cancel()
    }

    fun finishInput() {
        cancel()
        editorKey = null
    }

    fun cancel() {
        insertion?.cancel()
        insertion = null
        connection = null
        needsInitialization = true
        resetRecognizer()
    }
}

/** One utterance in one input connection, independent of the input view's lifetime. */
internal class ImeInsertionSession(
    private val editor: InsertionEditor,
    private val automaticSpacing: Boolean = true,
    private val formatting: InsertionFormatting? = null,
    /**
     * Makes the live text sent so far permanent, so later live and final text holds only newer
     * speech. True when the recognizer supports that for this dictation; dictation then goes on at
     * the cursor after the user moves it or edits the field. Main thread.
     */
    private val freezeLiveText: () -> Boolean = { false }
) {
    private var active = true
    private var started = false
    private var composingStart = -1
    private var composingText: String? = null
    /** Selection updates our own writes will cause; editors may report them after a newer write. */
    private data class Ack(val selectionStart: Int, val selectionEnd: Int, val candidatesStart: Int, val candidatesEnd: Int) {
        constructor(cursor: Int, candidatesStart: Int, candidatesEnd: Int) : this(cursor, cursor, candidatesStart, candidatesEnd)
    }
    private val pendingAcks = mutableListOf<Ack>()
    /** Absolute cursor after the last edit while no live text composes; null when unknown. */
    private var editCursor: Int? = null

    /** [language] selects the voice-command phrases; see [InsertionFormatting]. */
    fun partial(text: String, language: String? = null): Boolean = deliver(text, composing = true, language)

    fun finish(text: String, language: String?): Boolean = deliver(text, composing = false, language)

    fun cancel() {
        // Keep already accepted text. Removing it could undo a selection replacement or user edit.
        // Finishing composition does not change text or selection, even after ownership was lost.
        if (composingText != null) editor.finishComposition()
        active = false
        composingText = null
        editCursor = null
        pendingAcks.clear()
    }

    /**
     * Applies [edit] at the cursor. Live text that is still composing is first committed as it
     * stands, then [committed] runs, so the caller can stop sending that text again: later partial
     * and final results then insert only newer text at the cursor, after the edit.
     * Without ownership (the user moved the cursor or edited the field) the edit still applies at
     * the cursor, and dictation goes on from there.
     */
    fun edit(edit: DictationEdit, committed: () -> Unit): Boolean {
        val previous = composingText
        val owned = previous != null && editor.snapshot()?.let(::ownsComposition) == true
        // Also clears a stale composing span from an earlier IME, which some editors skip when deleting.
        if (!editor.finishComposition()) return false
        started = true
        // The field changed by hand; "delete that" must not remove an earlier dictation now.
        formatting?.memory?.forget()
        if (previous != null) {
            if (active && owned) addAck(Ack(composingStart + previous.length, -1, -1))
            composingText = null
        }
        committed()
        editCursor = null
        val snapshot = editor.snapshot() ?: return false
        val start = minOf(snapshot.selectionStart, snapshot.selectionEnd)
        // Live text is committed even when the edit then changes nothing, so a cursor move by the
        // user must still end ownership from here on.
        if (snapshot.offset >= 0 && start == maxOf(snapshot.selectionStart, snapshot.selectionEnd)) {
            editCursor = snapshot.offset + start
        }
        if (edit == DictationEdit.Lowercase) return lowercase(snapshot, start)
        val plan = if (start != maxOf(snapshot.selectionStart, snapshot.selectionEnd)) {
            // Even an empty replacement removes the selection.
            DictationEditPlan(0, DictationEditing.selectionReplacement(edit))
        } else {
            DictationEditing.plan(editor.textBeforeCursor(DictationEditing.contextChars(edit)) ?: return false, edit)
                .also { if (it.isNoop) return true }
        }
        if (!editor.edit(plan.deleteBefore, plan.insert)) return false
        if (snapshot.offset >= 0) {
            val cursor = snapshot.offset + start - plan.deleteBefore + plan.insert.length
            addAck(Ack(cursor, -1, -1))
            editCursor = cursor
        }
        return true
    }

    /** Changes only the one letter where offsets are known, so links or mentions in between survive. */
    private fun lowercase(snapshot: InsertionSnapshot, start: Int): Boolean {
        // A selection is left alone: there is no obvious letter to change.
        if (start != maxOf(snapshot.selectionStart, snapshot.selectionEnd)) return true
        val before = editor.textBeforeCursor(DictationEditing.LOWERCASE_CONTEXT_CHARS) ?: return false
        if (snapshot.offset < 0) {
            // Without offsets the text from the letter is rewritten, which drops links and
            // formatting in it, so only nearby letters are changed.
            val plan = DictationEditing.plan(before, DictationEdit.Lowercase)
            if (plan.isNoop || plan.deleteBefore > DictationEditing.CONTEXT_CHARS) return true
            return editor.edit(plan.deleteBefore, plan.insert)
        }
        val target = DictationEditing.lowercaseTarget(before) ?: return true
        val cursor = snapshot.offset + start
        val from = cursor - target.distance
        val after = cursor + target.text.length - target.length
        if (!editor.replaceRange(from, from + target.length, target.text, after)) return false
        // Editors may report each step of the batch.
        addAck(Ack(from, from + target.length, -1, -1))
        addAck(Ack(from + target.text.length, -1, -1))
        addAck(Ack(after, -1, -1))
        editCursor = after
        return true
    }

    private fun addAck(ack: Ack) {
        if (pendingAcks.lastOrNull() == ack) return
        pendingAcks.add(ack)
        // Editors that batch notifications never report some writes; keep only recent ones.
        if (pendingAcks.size > MAX_PENDING_ACKS) pendingAcks.removeAt(0)
    }

    fun selectionChanged(start: Int, end: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (!active) return
        val previous = composingText
        if (previous == null) {
            // After an edit the next live text goes in at the cursor, so a cursor move by the user
            // ends ownership just as it does while live text composes.
            val cursor = editCursor ?: return
            val pending = pendingAcks.indexOf(Ack(start, end, candidatesStart, candidatesEnd))
            if (pending >= 0) pendingAcks.subList(0, pending + 1).clear()
            else if (start != cursor || end != cursor || candidatesStart != -1 || candidatesEnd != -1) userMoved()
            return
        }
        val pending = pendingAcks.indexOf(Ack(start, end, candidatesStart, candidatesEnd))
        val current = start == end && candidatesStart == composingStart && candidatesEnd == end &&
            end == composingStart + previous.length
        if (current || pending >= 0) {
            // Editor notifications may acknowledge earlier successful writes (including edits that
            // committed earlier live text) after a newer one.
            if (pending >= 0) pendingAcks.subList(0, pending + 1).clear()
        } else {
            userMoved()
        }
    }

    /**
     * The user moved the cursor or edited the field. Live text is committed where it stands and,
     * when the recognizer can stop resending it, dictation goes on at the new cursor. Otherwise
     * this session ends, as later results would repeat the committed text.
     */
    private fun userMoved(): Boolean {
        if (composingText != null && !editor.finishComposition()) {
            active = false
            return false
        }
        composingText = null
        editCursor = null
        pendingAcks.clear()
        if (!freezeLiveText()) {
            active = false
            return false
        }
        // The field changed by hand; "delete that" must not remove an earlier dictation now.
        formatting?.memory?.forget()
        return true
    }

    private fun deliver(text: String, composing: Boolean, language: String?): Boolean {
        if (!active) return false
        if (!started) {
            // An earlier IME/connection may have left a composing span around unrelated text.
            if (!editor.finishComposition()) return false
            started = true
        }
        val snapshot = editor.snapshot() ?: return false
        val previous = composingText
        // A blank interim hypothesis must not erase the user's selection or the last partial.
        if (composing && text.isBlank()) return true
        // Without absolute selection offsets we cannot safely replace a later partial.
        if (composing && snapshot.offset < 0) return false
        val from = if (previous == null) minOf(snapshot.selectionStart, snapshot.selectionEnd)
            else composingStart - snapshot.offset
        val to = if (previous == null) maxOf(snapshot.selectionStart, snapshot.selectionEnd)
            else from + previous.length
        if (from !in 0..snapshot.text.length || to !in from..snapshot.text.length) return false
        if (previous != null && !ownsComposition(snapshot)) {
            // Live text not yet accepted is sent again after the freeze, at the new cursor. A final
            // result already holds the committed text, so it cannot go on.
            if (!composing || !userMoved()) active = false
            return false
        }
        // Formatting sees only the field text before the insertion point; it never leaves this method.
        val formatted = formatting?.let {
            DictationFormatter.format(text, snapshot.text.substring(0, from), it.field, it.options(), language,
                final = !composing,
                previousDictation = if (composing || it.field.isPrivate) null else it.memory.recall(it.key))
        }
        val output = formatted?.text ?: text
        // Final only, and within the text read above. A selection is replaced as usual, without
        // also deleting text before it.
        val deleteBefore = if (previous == null && from != to) 0 else (formatted?.deleteBefore ?: 0).coerceIn(0, from)
        val start = from - deleteBefore
        if (output.isEmpty() && previous == null && deleteBefore == 0) {
            // Live text that formats to nothing (only a command so far) leaves the field alone.
            if (!composing) active = false
            return true
        }
        val spaced = if (output.isNotEmpty() && automaticSpacing &&
            language?.substringBefore('-') !in setOf("zh", "ja", "th", "lo", "km", "my")) {
            val before = if (start > 0) snapshot.text.codePointBefore(start) else -1
            val after = if (to < snapshot.text.length) snapshot.text.codePointAt(to) else -1
            (if (needsSpace(before, output.codePointAt(0))) " " else "") + output +
                (if (needsSpace(output.codePointBefore(output.length), after)) " " else "")
        } else output
        if (composing && spaced == previous) return true
        // An empty composing span is not kept by editors, which would look like a lost composition.
        // Earlier live text stays until the final result replaces it.
        if (composing && spaced.isEmpty()) return true
        val written = when {
            deleteBefore == 0 -> editor.replace(spaced, composing)
            snapshot.offset >= 0 -> {
                // commitText would replace a composing region instead of the range, so end it first.
                (previous == null || editor.finishComposition()) && editor.replaceRange(
                    snapshot.offset + start, snapshot.offset + to, spaced, snapshot.offset + start + spaced.length)
            }
            // Without offsets nothing composes; the text read before the cursor excludes a selection.
            else -> editor.edit(deleteBefore, spaced)
        }
        if (!written) return false
        if (!composing) formatting?.let {
            if (it.field.isPrivate) it.memory.forget() else it.memory.remember(it.key, spaced)
        }
        if (composing) {
            composingStart = snapshot.offset + from
            composingText = spaced
            editCursor = null
            addAck(Ack(composingStart + spaced.length, composingStart, composingStart + spaced.length))
        } else {
            active = false
            composingText = null
        }
        return true
    }

    private fun ownsComposition(snapshot: InsertionSnapshot): Boolean {
        val previous = composingText ?: return false
        if (snapshot.offset < 0) return false
        val from = composingStart - snapshot.offset
        val to = from + previous.length
        return from >= 0 && to <= snapshot.text.length && snapshot.selectionStart == to &&
            snapshot.selectionEnd == to && snapshot.text.substring(from, to) == previous
    }

    private companion object {
        const val MAX_PENDING_ACKS = 256
    }

    private fun needsSpace(left: Int, right: Int): Boolean {
        if (left < 0 || right < 0) return false
        val unspaced = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR)
        if (Character.UnicodeScript.of(left) in unspaced || Character.UnicodeScript.of(right) in unspaced) return false
        val leftWord = Character.isLetterOrDigit(left) || left.toChar() in ".,!?;:" ||
            Character.getType(left) in setOf(Character.END_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt())
        val rightWord = Character.isLetterOrDigit(right) ||
            Character.getType(right) in setOf(Character.START_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt())
        return leftWord && rightWord
    }
}
