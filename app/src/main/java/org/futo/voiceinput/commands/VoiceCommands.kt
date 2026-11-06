package org.futo.voiceinput.commands

import java.util.Locale

/** A spoken command. Destructive commands remove text and need their own setting. */
enum class VoiceCommand(val destructive: Boolean = false) {
    NewLine, NewParagraph, Comma, Period, QuestionMark, ExclamationMark, Colon,
    DeleteThat(destructive = true), UndoDictation(destructive = true)
}

/**
 * The phrases of one language. Phrases are lowercase words separated by single spaces.
 * [literal] before a phrase makes it plain text.
 */
class VoiceCommandLanguage(val tag: String, val phrases: Map<VoiceCommand, List<String>>, val literal: String)

/** Phrase tables per language. Add a language here; the parser and settings help list follow. */
object VoiceCommandPhrases {
    val English = VoiceCommandLanguage("en", mapOf(
        VoiceCommand.NewLine to listOf("new line"),
        VoiceCommand.NewParagraph to listOf("new paragraph"),
        VoiceCommand.Comma to listOf("comma"),
        VoiceCommand.Period to listOf("period", "full stop"),
        VoiceCommand.QuestionMark to listOf("question mark"),
        VoiceCommand.ExclamationMark to listOf("exclamation mark", "exclamation point"),
        VoiceCommand.Colon to listOf("colon"),
        VoiceCommand.DeleteThat to listOf("delete that"),
        VoiceCommand.UndoDictation to listOf("undo dictation"),
    ), literal = "literal")

    val Slovak = VoiceCommandLanguage("sk", mapOf(
        VoiceCommand.NewLine to listOf("nový riadok"),
        VoiceCommand.NewParagraph to listOf("nový odsek"),
        VoiceCommand.Comma to listOf("čiarka"),
        VoiceCommand.Period to listOf("bodka"),
        VoiceCommand.QuestionMark to listOf("otáznik"),
        VoiceCommand.ExclamationMark to listOf("výkričník"),
        VoiceCommand.Colon to listOf("dvojbodka"),
        VoiceCommand.DeleteThat to listOf("vymaž to"),
        VoiceCommand.UndoDictation to listOf("zruš diktovanie"),
    ), literal = "doslova")

    val all = listOf(English, Slovak)

    /** "sk" (any region) selects Slovak; every other or unknown language uses English. */
    fun forLanguage(tag: String?): VoiceCommandLanguage {
        val base = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
        return all.firstOrNull { it.tag == base } ?: English
    }
}

sealed interface DictationPiece {
    data class Text(val text: String) : DictationPiece
    data class Command(val command: VoiceCommand) : DictationPiece
}

/**
 * Splits recognized text into plain text and commands. Case and punctuation attached to the words
 * are ignored; a multi-word phrase must not contain punctuation between its words.
 * Destructive commands match only as a sentence of their own. Text here is dictated content:
 * never log or persist it.
 */
object VoiceCommandParser {
    private class Token(val start: Int, val end: Int, val core: String, val leading: Boolean, val trailing: String)

    private const val SENTENCE_END = ".?!"

    fun parse(text: String, language: VoiceCommandLanguage, destructive: Boolean): List<DictationPiece> {
        val tokens = tokenize(text)
        val phrases = language.phrases.entries
            .filter { destructive || !it.key.destructive }
            .flatMap { (command, list) -> list.map { it.split(' ') to command } }
            .sortedByDescending { it.first.size }
        val pieces = mutableListOf<DictationPiece>()
        val pending = StringBuilder()
        var copied = 0
        // Index of the first token after the last command; a destructive command may start there.
        var afterCommand = -1
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.core == language.literal && !token.leading && token.trailing.isEmpty()) {
                val literal = match(phrases, tokens, i + 1)
                if (literal != null) {
                    pending.append(text, copied, token.start)
                    val last = tokens[i + literal.first]
                    pending.append(text, tokens[i + 1].start, last.end)
                    copied = last.end
                    i += 1 + literal.first
                    continue
                }
            }
            val found = match(phrases, tokens, i)
            val accepted = found?.takeIf { (size, command) ->
                !command.destructive || (
                    (i == 0 || i == afterCommand || tokens[i - 1].trailing.any { it in SENTENCE_END }) &&
                    (i + size == tokens.size || tokens[i + size - 1].trailing.any { it in SENTENCE_END }))
            }
            if (accepted == null) { i++; continue }
            pending.append(text, copied, token.start)
            pending.toString().trim().takeIf { it.isNotEmpty() }?.let { pieces += DictationPiece.Text(it) }
            pending.clear()
            pieces += DictationPiece.Command(accepted.second)
            i += accepted.first
            copied = tokens[i - 1].end
            afterCommand = i
        }
        pending.append(text, copied, text.length)
        pending.toString().trim().takeIf { it.isNotEmpty() }?.let { pieces += DictationPiece.Text(it) }
        return pieces
    }

    private fun match(phrases: List<Pair<List<String>, VoiceCommand>>, tokens: List<Token>, index: Int): Pair<Int, VoiceCommand>? {
        for ((words, command) in phrases) {
            if (index + words.size > tokens.size) continue
            if (words.indices.all { k ->
                    val token = tokens[index + k]
                    token.core == words[k] && !token.leading && (k == words.size - 1 || token.trailing.isEmpty())
                }) return words.size to command
        }
        return null
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val raw = text.substring(start, i)
            var coreStart = 0
            while (coreStart < raw.length && !raw[coreStart].isLetterOrDigit()) coreStart++
            var coreEnd = raw.length
            while (coreEnd > coreStart && !raw[coreEnd - 1].isLetterOrDigit()) coreEnd--
            tokens += Token(start, i, raw.substring(coreStart, coreEnd).lowercase(Locale.ROOT),
                leading = coreStart > 0, trailing = raw.substring(coreEnd))
        }
        return tokens
    }
}
