package org.futo.voiceinput.commands

import org.futo.voiceinput.DictationEditing
import java.util.Locale

data class VoiceCommandOptions(val enabled: Boolean = false, val destructive: Boolean = false)

/** Remove [deleteBefore] UTF-16 units before the insertion point, then insert [text]. */
data class FormattedInsertion(val text: String, val deleteBefore: Int = 0)

/**
 * Turns one recognized utterance into the text to insert, from the field type and the text before
 * the insertion point ([before]). Pure: the same utterance always gives the same result, so live
 * text can be rendered on every update without running a command twice. Only a [final] result
 * deletes text outside the utterance. Inputs are dictated or field content: never log or persist them.
 */
object DictationFormatter {
    private const val RECOGNIZER_PUNCTUATION = ".,;:!?"
    private const val SENTENCE_END = ".?!"

    fun format(
        text: String, before: String, field: FieldProfile, options: VoiceCommandOptions,
        language: String?, final: Boolean, previousDictation: String? = null
    ): FormattedInsertion = when (field.kind) {
        FieldKind.Text -> prose(text, before, field, options, language, final, previousDictation)
        FieldKind.Email -> FormattedInsertion(address(text.lowercase(Locale.ROOT), language, email = true))
        FieldKind.Url -> FormattedInsertion(address(text, language, email = false).let(::lowercaseFirst))
        FieldKind.Number -> FormattedInsertion(number(text, language, field))
        FieldKind.Phone -> FormattedInsertion(phone(text, language))
        FieldKind.DateTime, FieldKind.Password -> FormattedInsertion(text)
    }

    private enum class Attach { None, Punctuation, Break }
    private enum class Case { Keep, Upper, Lower }

    private fun prose(
        text: String, before: String, field: FieldProfile, options: VoiceCommandOptions,
        language: String?, final: Boolean, previousDictation: String?
    ): FormattedInsertion {
        val destructive = options.destructive && !field.isPrivate
        val pieces = if (options.enabled && field.acceptsCommands) {
            VoiceCommandParser.parse(text, VoiceCommandPhrases.forLanguage(language), destructive)
        } else listOf(DictationPiece.Text(text.trim()))

        val out = StringBuilder()
        var attach = Attach.None
        var nextCase = Case.Keep
        var afterCommand = false
        var deletePrevious = false
        for (piece in pieces) {
            when (piece) {
                is DictationPiece.Text -> {
                    // Punctuation the recognizer put after a command word belongs to the command.
                    var chunk = if (afterCommand) piece.text.trimStart { it in RECOGNIZER_PUNCTUATION || it.isWhitespace() }
                        else piece.text
                    chunk = chunk.trim()
                    afterCommand = false
                    if (chunk.isEmpty()) continue
                    chunk = applyCase(chunk, nextCase)
                    nextCase = Case.Keep
                    if (out.isNotEmpty() && out.last() != '\n' && !out.last().isWhitespace()) out.append(' ')
                    out.append(chunk)
                }
                is DictationPiece.Command -> {
                    afterCommand = true
                    when (val command = piece.command) {
                        VoiceCommand.Comma, VoiceCommand.Period, VoiceCommand.QuestionMark,
                        VoiceCommand.ExclamationMark, VoiceCommand.Colon -> {
                            val mark = punctuation(command)
                            trimTrailingSpaces(out)
                            if (out.isEmpty()) {
                                if (attach == Attach.None) attach = Attach.Punctuation
                            } else if (out.last() in RECOGNIZER_PUNCTUATION) {
                                out.setLength(out.length - 1)
                            }
                            out.append(mark)
                            nextCase = if (mark in SENTENCE_END) Case.Upper else Case.Lower
                        }
                        VoiceCommand.NewLine, VoiceCommand.NewParagraph -> {
                            // A single-line field has no line breaks; the editor action is not sent.
                            if (!field.multiline) continue
                            trimTrailingSpaces(out)
                            if (out.isEmpty() && attach == Attach.None) attach = Attach.Break
                            out.append(if (command == VoiceCommand.NewLine) "\n" else "\n\n")
                            nextCase = Case.Upper
                        }
                        VoiceCommand.DeleteThat -> {
                            if (out.isBlank()) deletePrevious = true else removeLastSentence(out)
                            nextCase = Case.Keep
                        }
                        VoiceCommand.UndoDictation -> {
                            if (out.isBlank()) deletePrevious = true
                            out.clear()
                            attach = Attach.None
                            nextCase = Case.Keep
                        }
                    }
                }
            }
        }

        var deleteBefore = 0
        var context = before
        if (deletePrevious && final && destructive && !previousDictation.isNullOrEmpty() &&
            before.endsWith(previousDictation)) {
            deleteBefore = previousDictation.length
            context = before.substring(0, before.length - deleteBefore)
        }
        if (final && attach == Attach.Punctuation) {
            // Like the "?" editing button: replaces spaces and one trailing "." or ",".
            val start = DictationEditing.questionMarkStart(context)
            deleteBefore += context.length - start
            context = context.substring(0, start)
        } else if (final && attach == Attach.Break) {
            val start = context.trimEnd { it == ' ' || it == '\t' }.length
            deleteBefore += context.length - start
            context = context.substring(0, start)
        }

        var result = out.toString()
        if (attach == Attach.None && result.isNotEmpty()) {
            val previous = context.trimEnd { it.isWhitespace() && it != '\n' }
            val sentenceStart = previous.isEmpty() || previous.last() in SENTENCE_END || previous.last() == '\n'
            // A capital after text that continues a sentence is kept: it may be a name.
            if (sentenceStart && field.capitalization == FieldCapitalization.Sentences) result = applyCase(result, Case.Upper)
        }
        result = when (field.capitalization) {
            FieldCapitalization.Characters -> result.uppercase(Locale.ROOT)
            FieldCapitalization.Words -> capitalizeWords(result)
            else -> result
        }
        return FormattedInsertion(result, deleteBefore)
    }

    private fun punctuation(command: VoiceCommand): Char = when (command) {
        VoiceCommand.Comma -> ','
        VoiceCommand.Period -> '.'
        VoiceCommand.QuestionMark -> '?'
        VoiceCommand.ExclamationMark -> '!'
        VoiceCommand.Colon -> ':'
        else -> error("not punctuation")
    }

    private fun trimTrailingSpaces(out: StringBuilder) {
        while (out.isNotEmpty() && out.last().isWhitespace() && out.last() != '\n') out.setLength(out.length - 1)
    }

    /** Removes the last sentence; the end of the sentence before it stays. */
    private fun removeLastSentence(out: StringBuilder) {
        trimTrailingSpaces(out)
        var end = out.length
        while (end > 0 && out[end - 1] in SENTENCE_END) end--
        var cut = end
        while (cut > 0 && out[cut - 1] !in SENTENCE_END && out[cut - 1] != '\n') cut--
        out.setLength(cut)
        trimTrailingSpaces(out)
    }

    /**
     * Upper: capitalize a first word that has no capitals ("iPhone" is kept).
     * Lower: lowercase a plainly capitalized first word ("I", "USA", "O'Brien" are kept).
     */
    private fun applyCase(text: String, case: Case): String {
        if (case == Case.Keep) return text
        val first = text.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0) return text
        var end = first
        while (end < text.length && (text[end].isLetterOrDigit() ||
                (text[end] in WORD_JOINERS && end + 1 < text.length && text[end + 1].isLetter()))) end++
        val word = text.substring(first, end)
        val change = if (case == Case.Upper) word.none { it.isUpperCase() || it.isTitleCase() }
            else DictationEditing.isPlainCapitalized(word)
        if (!change) return text
        val letter = text.substring(first, first + 1)
        return text.substring(0, first) +
            (if (case == Case.Upper) letter.uppercase(Locale.ROOT) else letter.lowercase(Locale.ROOT)) +
            text.substring(first + 1)
    }

    private const val WORD_JOINERS = "'\u2019\u02BC-"

    private fun capitalizeWords(text: String): String {
        val out = StringBuilder(text.length)
        var start = true
        for (c in text) {
            out.append(if (start && c.isLetter()) c.titlecaseChar() else c)
            start = c.isWhitespace()
        }
        return out.toString()
    }

    private fun lowercaseFirst(text: String): String =
        if (text.isNotEmpty() && text[0].isUpperCase()) text[0].lowercaseChar() + text.substring(1) else text

    private val ADDRESS_WORDS = mapOf(
        "en" to mapOf("at" to "@", "dot" to ".", "underscore" to "_", "dash" to "-", "hyphen" to "-",
            "slash" to "/", "colon" to ":"),
        "sk" to mapOf("zavináč" to "@", "bodka" to ".", "podčiarkovník" to "_", "pomlčka" to "-",
            "lomka" to "/", "dvojbodka" to ":"))

    private val NUMBER_WORDS = mapOf(
        "en" to mapOf("zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
            "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "minus" to "-",
            "plus" to "+", "star" to "*", "hash" to "#", "pound" to "#"),
        "sk" to mapOf("nula" to "0", "jeden" to "1", "jedna" to "1", "jedno" to "1", "dva" to "2", "dve" to "2",
            "tri" to "3", "štyri" to "4", "päť" to "5", "šesť" to "6", "sedem" to "7", "osem" to "8",
            "deväť" to "9", "mínus" to "-", "plus" to "+", "hviezdička" to "*", "mriežka" to "#"))

    private fun words(language: String?, table: Map<String, Map<String, String>>): Map<String, String> =
        table[VoiceCommandPhrases.forLanguage(language).tag] ?: emptyMap()

    /** Replaces whole spoken words from [map], keeping other tokens, and joins without spaces. */
    private fun spokenSymbols(text: String, map: Map<String, String>): String =
        text.trim().split(Regex("\\s+")).joinToString("") { token ->
            val core = token.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            map[core] ?: token
        }

    /** Email or URL: spoken symbols, no spaces, no commas (email), no trailing period. */
    private fun address(text: String, language: String?, email: Boolean): String {
        var result = spokenSymbols(text, words(language, ADDRESS_WORDS).let { words ->
            if (email) words.filterValues { it != "/" && it != ":" } else words.filterValues { it != "@" && it != "_" && it != "-" }
        })
        if (email) result = result.replace(",", "")
        return result.trimEnd('.', ',')
    }

    /** Number words this formatter cannot combine; such text is passed on for the field to filter. */
    private val COMPOUND_NUMBER_WORDS = setOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
        "thousand", "million", "billion", "point", "half",
        "desať", "jedenásť", "dvanásť", "trinásť", "dvadsať", "tridsať", "štyridsať", "päťdesiat", "sto",
        "dvesto", "tisíc", "milión", "celá", "celých", "pol")

    private fun number(text: String, language: String?, field: FieldProfile): String {
        // "one hundred" must not become "1": such numbers are left as recognized.
        val compound = text.trim().split(Regex("\\s+")).any {
            it.trim { c -> !c.isLetterOrDigit() }.lowercase(Locale.ROOT) in COMPOUND_NUMBER_WORDS
        }
        if (compound) return text.trim().trimEnd('.')
        val spoken = spokenSymbols(text, words(language, NUMBER_WORDS))
        val out = StringBuilder()
        var separator = -1
        for ((i, c) in spoken.withIndex()) {
            when {
                c.isDigit() -> out.append(c)
                c == '-' && field.signed && out.isEmpty() -> out.append(c)
                (c == '.' || c == ',') && field.decimal && out.lastOrNull()?.isDigit() == true &&
                    spoken.getOrNull(i + 1)?.isDigit() == true -> {
                    // Only one separator: an earlier one was a thousands separator.
                    if (separator >= 0) out.deleteCharAt(separator)
                    separator = out.length
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    private fun phone(text: String, language: String?): String =
        spokenSymbols(text, words(language, NUMBER_WORDS)).filter { it.isDigit() || it in "+*#" }
}
