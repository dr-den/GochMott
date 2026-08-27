package com.bilto.gochmott.search


object TextSelection {

    /**
     * Небуквенные символы, которые всё же считаем частью слова: палочка в «народных»
     * написаниях (1, |, — их сводит к Ӏ [ChechenNormalizer]), дефис и апострофы внутри слова.
     */
    private const val WORD_EXTRAS = "1|-'’"

    /** Обрамление, к слову не относящееся: (куьг), слово-, «слово». */
    private const val TRIM_CHARS = "-'’"


    private const val MAX_WORD_LENGTH = 48

    data class Parsed(
        val word: String,
        /** В выделении было больше одного слова — перевели только первое. */
        val hadMoreWords: Boolean
    )


    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrEmpty()) return null
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && !isWordChar(raw[i])) i++
            val start = i
            while (i < raw.length && isWordChar(raw[i])) i++
            if (start == i) break
            val word = clean(raw.substring(start, i)) ?: continue
            return Parsed(word = word, hadMoreWords = hasLetterFrom(raw, i))
        }
        return null
    }

    private fun isWordChar(ch: Char): Boolean = ch.isLetter() || ch in WORD_EXTRAS

    /** Обрезает обрамление и отбраковывает токены без букв: «1)», «—», «|». */
    private fun clean(token: String): String? {
        val trimmed = token.trim { it in TRIM_CHARS }
        if (trimmed.none(Char::isLetter)) return null
        return trimmed.take(MAX_WORD_LENGTH)
    }


    private fun hasLetterFrom(raw: String, from: Int): Boolean {
        for (i in from until raw.length) if (raw[i].isLetter()) return true
        return false
    }
}
