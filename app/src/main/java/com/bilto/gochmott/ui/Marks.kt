package com.bilto.gochmott.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.search.Diacritics

/**
 * Показывать ли надстрочные знаки — состояние экрана, не данных.
 *
 * Из БД текст приходит размеченным всегда; снимает знаки только UI, прямо при
 * отрисовке. Поэтому оба флага — Compose-состояние: переключение в боковом меню
 * перерисовывает уже открытую статью, без повторного запроса к словарю.
 *
 * Долгота и ударение независимы: ударение помогает читать русский перевод,
 * чёрточка долготы передаёт чеченскую фонетику, которую современное письмо не
 * записывает, а словарь Мациева записывает. Значения хранит
 * [com.bilto.gochmott.settingsrepo.DisplayPrefs].
 *
 * В буфер обмена не уходит ни то, ни другое — см. `ClipboardCopy.kt`.
 *
 * С появлением словарей 1997 и 2017 сторону больше нельзя угадывать по месту в
 * карточке: у статьи `ада́птер` (рус→чеч) ударение стоит в ЗАГОЛОВКЕ, а долгота —
 * в переводе `чIа̃на`. Поэтому текст всегда снабжается своим `lang`, и знак
 * выбирает [forLang], а не вызывающий код.
 */
object Marks {
    var showLength by mutableStateOf(true)
    var showStress by mutableStateOf(true)

    /** Чеченский текст к показу. */
    fun ce(text: String?): String =
        if (showLength) text.orEmpty() else Diacritics.withoutLength(text)

    /** Русский текст к показу; null проходит насквозь — поля перевода необязательны. */
    fun ru(text: String?): String? =
        if (text == null || showStress) text else Diacritics.withoutStress(text)

    /**
     * Текст к показу по коду языка из базы (`lemmas.lang`, `glosses.lang`, …).
     * Чеченскому снимается долгота, русскому — ударение.
     */
    fun forLang(lang: String?, text: String?): String? =
        if (lang == Lang.RU) ru(text) else if (text == null) null else ce(text)
}
