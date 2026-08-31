package com.bilto.gochmott.search

import java.text.Normalizer

/**
 * ТОЧНЫЙ порт `normalize_ru` из `tools/build_app_db_v4.py`. Этим же кодом собраны
 * русские ключи в базе: `glosses.text_norm`, `trans_index.word` (для `lang='ru'`),
 * `lemmas.headword_norm` у словарей рус→чеч.
 *
 * Отдельный нормализатор нужен потому, что [ChechenNormalizer] превращает `1`, `i`,
 * `l`, `|` в палочку — это способ набрать `Ӏ` с обычной раскладки. Прогнать через
 * него русский ввод значит получить из «1-й ряд» — «Ӏ-й ряд».
 *
 * Порядок операций менять нельзя: `ё` заменяется ДО снятия комбинирующих знаков,
 * потому что в NFC это предсоставленный кодпойнт U+0451, а не «е» + диакритика.
 */
object RuNormalizer {

    private val WS = Regex("\\s+")

    /** NFC → lower → ё=е → снять комбинирующие знаки → схлопнуть пробелы. */
    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val s = Normalizer.normalize(input, Normalizer.Form.NFC)
            .lowercase()
            .replace('ё', 'е')
            .filterNot(::isCombiningMark)
        return WS.replace(s, " ").trim()
    }

    private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Самопроверка (вызывается в unit-тесте). */
    fun selfTest(): Boolean =
        normalize("ка́ждый  раз") == "каждый раз" &&
        normalize("1-й") == "1-й" &&              // цифра НЕ палочка — в отличие от ce
        normalize("Ёлка") == "елка"
}
