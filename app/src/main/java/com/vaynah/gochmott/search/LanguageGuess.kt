package com.vaynah.gochmott.search

import com.vaynah.gochmott.model.SearchDirection

/**
 * Предположение о языке слова. Нужно быстрому переводу выделенного текста: направление там
 * никто не выбирает, а оба языка записаны кириллицей.
 *
 * Смотрим на приметы чеченского — палочку и диграфы, которых в русском не бывает (ь после
 * а/о/у/х, ъ перед к). Ответ приблизительный и задаёт лишь ПОРЯДОК проверки: направление
 * всё равно подтверждается словарём (QuickTranslateViewModel), а в карточке есть кнопка
 * «поменять».
 */
object LanguageGuess {

    private const val PAL = 'Ӏ' // канонический вид палочки, как в ChechenNormalizer

    /**
     * Сочетания, невозможные в русском слове. «тх»/«пх» сюда не берём: они встречаются на
     * стыке приставки и корня («отходы»), а ложное «чеченское» направление стоит дороже —
     * русское слово тогда не найдётся вообще.
     */
    private val CE_MARKERS = listOf("аь", "оь", "уь", "юь", "хь", "къ", "кх")

    fun directionFor(word: String): SearchDirection {
        val key = ChechenNormalizer.normalize(word)
        val looksChechen = key.contains(PAL) || CE_MARKERS.any { key.contains(it) }
        return if (looksChechen) SearchDirection.CE_TO_RU else SearchDirection.RU_TO_CE
    }
}
