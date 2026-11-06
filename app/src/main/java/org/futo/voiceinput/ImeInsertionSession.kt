package org.futo.voiceinput

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

internal data class ImeEditorKey(val packageName: String?, val fieldId: Int, val inputType: Int)

/** Owns dictation across Android input callbacks, including view-only recreation. */
internal class ImeInputSessionLifecycle(private val resetRecognizer: () -> Unit) {
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

    fun startView(connection: Any?, editor: InsertionEditor?, automaticSpacing: Boolean): Boolean {
        if (this.connection !== connection) cancel()
        this.connection = connection
        if (!needsInitialization) return false
        insertion = editor?.let { ImeInsertionSession(it, automaticSpacing) }
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
    private val automaticSpacing: Boolean = true
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

    fun partial(text: String): Boolean = deliver(text, composing = true, language = null)

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
     * Without ownership (the user moved the cursor or edited the field) the edit still applies to
     * the field, but this session stays inactive, as before.
     */
    fun edit(edit: DictationEdit, committed: () -> Unit): Boolean {
        val previous = composingText
        if (previous != null && active && editor.snapshot()?.let(::ownsComposition) != true) active = false
        // Also clears a stale composing span from an earlier IME, which some editors skip when deleting.
        if (!editor.finishComposition()) return false
        started = true
        if (previous != null) {
            if (active) addAck(Ack(composingStart + previous.length, -1, -1))
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
            else if (start != cursor || end != cursor || candidatesStart != -1 || candidatesEnd != -1) active = false
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
            active = false
        }
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
            active = false
            return false
        }
        if (text.isEmpty() && previous == null) {
            active = false
            return true
        }
        val spaced = if (text.isNotEmpty() && automaticSpacing &&
            language?.substringBefore('-') !in setOf("zh", "ja", "th", "lo", "km", "my")) {
            val before = if (from > 0) snapshot.text.codePointBefore(from) else -1
            val after = if (to < snapshot.text.length) snapshot.text.codePointAt(to) else -1
            (if (needsSpace(before, text.codePointAt(0))) " " else "") + text +
                (if (needsSpace(text.codePointBefore(text.length), after)) " " else "")
        } else text
        if (composing && spaced == previous) return true
        if (!editor.replace(spaced, composing)) return false
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
