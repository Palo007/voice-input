package org.futo.voiceinput

/** An edit the user applies from the dictation screen to the text before the cursor. */
enum class DictationEdit(val repeats: Boolean) {
    DeleteCharacter(repeats = true),
    DeleteWord(repeats = true),
    QuestionMark(repeats = false),
    Space(repeats = true)
}

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

    /** Plan for an empty selection. A non-empty selection is replaced by [selectionReplacement]. */
    fun plan(window: String, edit: DictationEdit): DictationEditPlan {
        // A window read from the editor may start with the second half of a surrogate pair.
        val before = if (window.isNotEmpty() && window[0].isLowSurrogate()) window.substring(1) else window
        return planWhole(before, edit)
    }

    private fun planWhole(before: String, edit: DictationEdit): DictationEditPlan = when (edit) {
        DictationEdit.DeleteCharacter -> DictationEditPlan(before.length - previousCharacterStart(before, before.length), "")
        DictationEdit.DeleteWord -> DictationEditPlan(before.length - previousWordStart(before), "")
        DictationEdit.QuestionMark -> DictationEditPlan(before.length - questionMarkStart(before), "?")
        DictationEdit.Space -> DictationEditPlan(0, " ")
    }

    /** Like a keyboard: deleting removes the selection, inserting replaces it. */
    fun selectionReplacement(edit: DictationEdit): String = when (edit) {
        DictationEdit.DeleteCharacter, DictationEdit.DeleteWord -> ""
        DictationEdit.QuestionMark -> "?"
        DictationEdit.Space -> " "
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
