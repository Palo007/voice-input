package org.futo.voiceinput.recognition

import java.util.Locale

/**
 * Multilingual Parakeet V3 models (Ultra, Slovak) choose the language themselves. With accented English
 * they sometimes write a few words phonetically in Cyrillic ("It стопс стриминг" for "It stops streaming").
 * Neither dictation language (EN, SK) uses Cyrillic, so such letters are spelled back in Latin letters.
 */
object LatinScript {
    enum class Scheme { English, Slovak }

    private val common = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'з' to "z",
        'и' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'ы' to "y", 'э' to "e",
        'ъ' to "", 'ь' to "", 'і' to "i", 'ґ' to "g", 'ў' to "w", 'ј' to "j", 'ђ' to "dj",
        'љ' to "lj", 'њ' to "nj", 'џ' to "dz", 'ѕ' to "dz", 'ѓ' to "gj", 'ќ' to "kj"
    )

    private val english = common + mapOf(
        'ё' to "yo", 'ж' to "zh", 'й' to "y", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
        'щ' to "shch", 'ю' to "yu", 'я' to "ya", 'є' to "ye", 'ї' to "yi", 'ћ' to "c"
    )

    private val slovak = common + mapOf(
        'ё' to "jo", 'ж' to "ž", 'й' to "j", 'х' to "ch", 'ц' to "c", 'ч' to "č", 'ш' to "š",
        'щ' to "šč", 'ю' to "ju", 'я' to "ja", 'є' to "je", 'ї' to "ji", 'ћ' to "ć"
    )

    private fun isCyrillic(c: Char) = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC

    fun containsCyrillic(text: String): Boolean = text.any(::isCyrillic)

    /** Returns [text] with Cyrillic letters spelled in Latin letters; other text is unchanged. */
    fun toLatin(text: String, scheme: Scheme = Scheme.English): String {
        if (!containsCyrillic(text)) return text
        val table = if (scheme == Scheme.Slovak) slovak else english
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!isCyrillic(c)) { out.append(c); i++; continue }
            val lower = c.lowercaseChar()
            val latin = table[lower] ?: ""
            if (c == lower || latin.isEmpty()) {
                out.append(latin)
            } else {
                // An all-caps word ("ШОП") stays all caps; otherwise only the first letter is capital.
                val neighbour = text.getOrNull(i + 1) ?: text.getOrNull(i - 1)
                val allCaps = neighbour != null && neighbour.isLetter() && neighbour.isUpperCase()
                out.append(if (allCaps) latin.uppercase(Locale.ROOT)
                    else latin.replaceFirstChar { it.uppercaseChar() })
            }
            i++
        }
        return out.toString()
    }
}
