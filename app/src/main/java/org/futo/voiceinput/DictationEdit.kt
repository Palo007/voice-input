package org.futo.voiceinput

/** An edit the user applies from the dictation screen to the text before the cursor. */
enum class DictationEdit(val repeats: Boolean) {
    DeleteCharacter(repeats = true),
    DeleteWord(repeats = true),
    QuestionMark(repeats = false),
    Space(repeats = true),
    /** Makes the nearest capitalized word before the cursor start with a small letter. */
    Lowercase(repeats = false)
}

/** A letter [distance] UTF-16 units before the cursor, [length] units long, becomes [text]. */
internal data class LowercaseTarget(val distance: Int, val length: Int, val text: String)

/** Remove [deleteBefore] UTF-16 units before the cursor, then insert [insert]. */
internal data class DictationEditPlan(val deleteBefore: Int, val insert: String) {
    val isNoop: Boolean get() = deleteBefore == 0 && insert.isEmpty()
}

/**
 * Pure planning for [DictationEdit]. Works on the text before the cursor only.
 * Text passed here is editor content: never log or persist it.
 */
internal object DictationEditing {
    /** How much text before the cursor is read to plan an edit. A longer word takes another tap. */
    const val CONTEXT_CHARS = 256
    /** Lowercasing looks back any distance, within what the editor returns. */
    const val LOWERCASE_CONTEXT_CHARS = 4096

    /** Plan for an empty selection. A non-empty selection is replaced by [selectionReplacement]. */
    fun plan(window: String, edit: DictationEdit): DictationEditPlan {
        // A window read from the editor may start with the second half of a surrogate pair.
        val before = if (window.isNotEmpty() && window[0].isLowSurrogate()) window.substring(1) else window
        return planWhole(before, edit)
    }

    /** Text read for [edit]; see [CONTEXT_CHARS] and [LOWERCASE_CONTEXT_CHARS]. */
    fun contextChars(edit: DictationEdit): Int =
        if (edit == DictationEdit.Lowercase) LOWERCASE_CONTEXT_CHARS else CONTEXT_CHARS

    /**
     * The capital letter to make small: the first letter of the nearest word before the cursor
     * that starts with a capital and has no other capitals. Skips all-caps and mixed words such as
     * "USA" or "iPhone", and the pronoun "I" (also in "I'm"). Each tap moves one word further back,
     * because the word changed last no longer starts with a capital.
     */
    fun lowercaseTarget(window: String): LowercaseTarget? {
        // A full window may start inside a word ("Phone" of "iPhone"); such a word is not judged.
        val truncated = window.length >= LOWERCASE_CONTEXT_CHARS
        val skipped = if (window.isNotEmpty() && window[0].isLowSurrogate()) 1 else 0
        val text = window.substring(skipped)
        var i = text.length
        while (i > 0) {
            if (!isWordPart(text.codePointBefore(i))) { i -= Character.charCount(text.codePointBefore(i)); continue }
            val end = i
            while (i > 0 && isWordPart(text.codePointBefore(i))) i -= Character.charCount(text.codePointBefore(i))
            if (i == 0 && truncated) return null
            val word = text.substring(i, end)
            val first = word.codePointAt(0)
            val capitals = word.codePoints().filter { Character.isUpperCase(it) || Character.isTitleCase(it) }.count()
            if (word != "I" && capitals == 1L && (Character.isUpperCase(first) || Character.isTitleCase(first))) {
                val lower = Character.toLowerCase(first)
                if (lower != first) {
                    return LowercaseTarget(text.length - i, Character.charCount(first), String(Character.toChars(lower)))
                }
            }
        }
        return null
    }

    private fun isWordPart(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    private fun planWhole(before: String, edit: DictationEdit): DictationEditPlan = when (edit) {
        DictationEdit.DeleteCharacter -> DictationEditPlan(before.length - previousCharacterStart(before, before.length), "")
        DictationEdit.DeleteWord -> DictationEditPlan(before.length - previousWordStart(before), "")
        DictationEdit.QuestionMark -> DictationEditPlan(before.length - questionMarkStart(before), "?")
        DictationEdit.Space -> DictationEditPlan(0, " ")
        // Rewrites the text from the letter to the cursor; used only without absolute offsets.
        DictationEdit.Lowercase -> lowercaseTarget(before)?.let {
            DictationEditPlan(it.distance, it.text + before.substring(before.length - it.distance + it.length))
        } ?: DictationEditPlan(0, "")
    }

    /** Like a keyboard: deleting removes the selection, inserting replaces it. */
    fun selectionReplacement(edit: DictationEdit): String = when (edit) {
        DictationEdit.DeleteCharacter, DictationEdit.DeleteWord -> ""
        DictationEdit.QuestionMark -> "?"
        DictationEdit.Space -> " "
        DictationEdit.Lowercase -> error("Lowercase leaves a selection unchanged")
    }

    /**
     * Start of the user-perceived character ending at [end]: a code point plus combining marks,
     * variation selectors, emoji modifiers, emoji tags, ZWJ sequences, a flag pair, or CR LF.
     * An approximation of extended grapheme clusters that behaves the same on every JVM and Android.
     */
    fun previousCharacterStart(text: String, end: Int): Int {
        if (end <= 0) return 0
        val last = text.codePointBefore(end)
        if (isRegionalIndicator(last)) {
            var count = 0
            var i = end
            while (i > 0 && isRegionalIndicator(text.codePointBefore(i))) { i -= 2; count++ }
            // Indicators pair up from the start of the run; an odd last one stands alone.
            return end - if (count % 2 == 0) 4 else 2
        }
        if (last == '\n'.code && end >= 2 && text[end - 2] == '\r') return end - 2
        var i = end
        while (i > 0) {
            val cp = text.codePointBefore(i)
            i -= Character.charCount(cp)
            if (isExtender(cp)) continue
            // cp is a base character. A preceding ZWJ joins it to the emoji before.
            if (i > 0 && text[i - 1] == ZWJ && isPictographic(cp)) {
                i -= 1
                continue
            }
            break
        }
        return i
    }

    /**
     * Start of the deletion for "delete one word": trailing spaces, then the run of non-space
     * characters (a word with its punctuation), then the spaces before it. A line break directly
     * before the cursor (after any trailing spaces) is removed on its own.
     */
    fun previousWordStart(text: String): Int {
        var i = text.length
        while (i > 0 && isHorizontalSpace(text[i - 1])) i--
        if (i > 0 && isLineBreak(text[i - 1])) {
            return if (text[i - 1] == '\n' && i >= 2 && text[i - 2] == '\r') i - 2 else i - 1
        }
        while (i > 0 && !isHorizontalSpace(text[i - 1]) && !isLineBreak(text[i - 1])) i--
        while (i > 0 && isHorizontalSpace(text[i - 1])) i--
        return i
    }

    /** "?" replaces spaces before the cursor and one trailing "." or "," (models often end with "."). */
    fun questionMarkStart(text: String): Int {
        var i = text.length
        while (i > 0 && isHorizontalSpace(text[i - 1])) i--
        if (i > 0 && (text[i - 1] == '.' || text[i - 1] == ',')) i--
        return i
    }

    private const val ZWJ = '‍'

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtender(cp: Int): Boolean {
        if (cp == ZWJ.code || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }
    }

    private fun isPictographic(cp: Int): Boolean =
        cp >= 0x2000 && Character.getType(cp) == Character.OTHER_SYMBOL.toInt()

    private fun isLineBreak(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '

    private fun isHorizontalSpace(c: Char) = !isLineBreak(c) && (Character.isWhitespace(c) || Character.isSpaceChar(c))
}
