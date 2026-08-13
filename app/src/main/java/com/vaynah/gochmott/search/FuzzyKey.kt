package com.vaynah.gochmott.search

/**
 * Ключ ПРИМЕРНОГО поиска («скелет» слова). В отличие от [ChechenNormalizer] этот ключ
 * НЕ хранится в БД и НЕ участвует в точном поиске — он считается на лету и нужен только
 * чтобы свести к одной строке варианты написания, которые пользователь путает:
 *
 *   куьг / куг / кюг      -> куг      (ь выброшен, ю→у)
 *   мостагӀ / мостаг      -> мостаг   (палочка выброшена)
 *   хӀума / хума          -> хума
 *   аьрзу / арзу / эрзу   -> арзу     (ь выброшен, э→е … аь→а)
 *   тӀулг / ттулг         -> тулг     (дубли схлопнуты)
 *
 * Правила намеренно «грубые»: коллизии здесь не страшны, потому что результат всё равно
 * ранжируется расстоянием Левенштейна и показывается отдельным блоком «похожие слова».
 * На словаре Мациева (20 423 статьи) скелет даёт 18 466 уникальных ключей — т.е. слипаются
 * единицы процентов статей.
 */
object FuzzyKey {

    private const val PAL_UPPER = 'Ӏ' // Ӏ — как в ChechenNormalizer
    private const val PAL_LOWER = 'ӏ' // ӏ

    /** Буквы, которые пользователь ставит вместо чеченских диграфов/палочки. */
    private val CE_FOLD: Map<Char, Char> = mapOf(
        'ю' to 'у', 'я' to 'а', 'э' to 'е', 'ё' to 'е'
    )

    private val RU_FOLD: Map<Char, Char> = mapOf('ё' to 'е')

    /** Скелет чеченского слова. Вход прогоняется через штатный [ChechenNormalizer]. */
    fun chechen(input: String?): String = fold(ChechenNormalizer.normalize(input), CE_FOLD)

    /**
     * То же для строк, уже прошедших [ChechenNormalizer] (колонки `*_norm` в БД):
     * пропускает повторную нормализацию — важно при построении индекса по 20 тыс. статей.
     */
    fun chechenFromNormalized(norm: String?): String =
        if (norm.isNullOrEmpty()) "" else fold(norm, CE_FOLD)

    /** Скелет русского слова (для опечаток в направлении рус→чеч). */
    fun russian(input: String?): String =
        if (input.isNullOrEmpty()) "" else fold(input.lowercase(), RU_FOLD)

    private fun fold(source: String, map: Map<Char, Char>): String {
        val sb = StringBuilder(source.length)
        for (raw in source) {
            if (raw == 'ь' || raw == 'ъ' || raw == PAL_UPPER || raw == PAL_LOWER) continue
            if (!raw.isLetter()) continue
            val ch = map[raw] ?: raw
            if (sb.isNotEmpty() && sb.last() == ch) continue // схлопываем дубли: тт→т, аа→а
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Сколько правок прощаем запросу такой длины. */
    fun maxEdits(queryLength: Int): Int = when {
        queryLength <= 4 -> 1
        queryLength <= 8 -> 2
        else -> 3
    }

    /** Худший ранг, который ещё показываем. */
    fun maxRank(queryLength: Int): Int = SUBSTRING + maxEdits(queryLength)

    /**
     * Насколько кандидат близок запросу; меньше — лучше.
     * 0 — скелеты совпали, 1 — кандидат начинается с запроса, 2 — содержит запрос,
     * дальше 2 + расстояние Левенштейна. Значение больше [maxRank] показывать не стоит.
     */
    fun rank(candidate: String, query: String, maxEdits: Int = maxEdits(query.length)): Int = when {
        candidate == query -> 0
        candidate.startsWith(query) -> PREFIX
        candidate.contains(query) -> SUBSTRING
        else -> SUBSTRING + Levenshtein.atMost(candidate, query, maxEdits)
    }

    private const val PREFIX = 1
    private const val SUBSTRING = 2
}