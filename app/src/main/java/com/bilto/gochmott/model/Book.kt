package com.bilto.gochmott.model

/**
 * Вводная часть издания 1961 года: предисловие, слово редактора, правила
 * построения словаря, список сокращений и алфавит.
 *
 * В книге всё это напечатано дважды — по-русски и по-чеченски, параллельными
 * разделами. Поэтому [BookLang] переключает **язык самого текста Мациева**,
 * а не язык интерфейса: русскоязычный читатель должен уметь открыть чеченский
 * вариант и наоборот. По этой же причине тексты лежат в `assets/about.json`,
 * а не в строковых ресурсах — ресурсы выбирает система по локали устройства.
 */
enum class BookLang {
    RU, CE;

    fun other(): BookLang = if (this == RU) CE else RU
}

/** Пара «русский / чеченский» — заголовок, помета, расшифровка. */
data class Bilingual(val ru: String, val ce: String) {
    operator fun get(lang: BookLang): String = if (lang == BookLang.RU) ru else ce
}

/**
 * Отрезок абзаца с начертанием.
 *
 * Абзац хранится списком отрезков, а не строкой, потому что в оригинале
 * заглавное слово и помета стоят вплотную и различаются только шрифтом:
 * `доккха` жирным, сразу за ним `прил.` курсивом. Склеенные в одну строку они
 * превращаются в «доккхаприл.».
 */
data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val superscript: Boolean = false
)

typealias BookParagraph = List<TextRun>

data class BookSection(
    val id: String,
    val title: Bilingual,
    private val ru: List<BookParagraph>,
    private val ce: List<BookParagraph>
) {
    fun body(lang: BookLang): List<BookParagraph> = if (lang == BookLang.RU) ru else ce
}

/** Статья списка сокращений: `перен.` — «переносное значение» / «кечдина маьӀна». */
data class Abbreviation(val short: String, val expansion: Bilingual)

/** Строка таблицы алфавита: начертание буквы и её название. */
data class AlphabetLetter(val letter: String, val name: String)

data class DictionaryBook(
    val title: Bilingual,
    val source: String,
    val sections: List<BookSection>,
    val abbreviationsTitle: Bilingual,
    val abbreviations: List<Abbreviation>,
    val alphabetTitle: Bilingual,
    val alphabet: List<AlphabetLetter>
)
