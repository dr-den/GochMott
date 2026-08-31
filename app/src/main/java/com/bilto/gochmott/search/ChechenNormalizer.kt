package com.bilto.gochmott.search

import java.text.Normalizer
import kotlin.text.iterator

/**
 * ТОЧНЫЙ порт `normalize_ce` из сборщика. Этим же кодом нормализованы ключи *_norm в dict.db,
 * поэтому пользовательский чеченский ввод ПЕРЕД запросом `WHERE form_norm = ?`
 * нужно прогонять ровно через ChechenNormalizer.normalize(). Русский ввод — через
 * [RuNormalizer]: здесь `1`, `i`, `l`, `|` становятся палочкой. Любое расхождение с
 * БД ломает прямой поиск чеч→рус, поэтому НЕ меняйте логику без пересборки БД.
 *
 * Что делает: NFC → lower → карта(палочка+латинские гомоглифы) → срез комбинирующих
 * знаков (ударения) → схлопывание пробелов → NFC. NFD НЕ применяется специально:
 * «й» и «ё» должны оставаться целыми буквами (й — классный показатель).
 */
object ChechenNormalizer {

    private const val PAL = '\u04C0' // Ӏ — канонический вид палочки

    // Карта применяется ПОСЛЕ lower(), поэтому ключи строчные. Совпадает с _CHAR_MAP.
    private val MAP: Map<Char, Char> = mapOf(
        // палочка во всех обличьях -> Ӏ
        '\u04CF' to PAL, '\u04C0' to PAL, 'i' to PAL, 'l' to PAL, '1' to PAL,
        '|' to PAL, '\u0406' to PAL, '\u0456' to PAL,
        // латиница, неотличимая от кириллицы -> кириллица
        'a' to '\u0430', 'c' to '\u0441', 'e' to '\u0435', 'o' to '\u043E',
        'p' to '\u0440', 'x' to '\u0445', 'y' to '\u0443', 'k' to '\u043A',
        '\u0455' to '\u0437' // ѕ -> з
    )

    fun normalize(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var s = Normalizer.normalize(input, Normalizer.Form.NFC)
        s = s.lowercase() // для кириллицы/латиницы эквивалентно casefold
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(MAP[ch] ?: ch)
        // срезаем ТОЛЬКО комбинирующие знаки (ударение U+0301 и пр.);
        // прекомпозированные й/ё — одиночные кодпойнты, не затрагиваются
        val noMarks = buildString(sb.length) {
            for (ch in sb) if (!isCombiningMark(ch)) append(ch)
        }
        s = noMarks.trim().replace(Regex("\\s+"), " ")
        return Normalizer.normalize(s, Normalizer.Form.NFC)
    }

    private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    // Самопроверка (вызвать в unit-тесте). Все «грязные» двойники -> один ключ.
    fun selfTest(): Boolean {
        val key = normalize("мостаг\u04C0е")               // мостагӀе
        val variants = listOf(
            "м\u006F\u0441\u0442\u0430г\u0049е",           // o,c,a,I — латиница
            "мостаг1е",                                     // палочка как «1»
            "МOCТAГIЕ"                                      // КАПС + латиница + I
        )
        val ok = variants.all { normalize(it) == key }
        val classOk = normalize("йаха") != normalize("иаха") // й ≠ и не сливаются
        return ok && classOk
    }
}
