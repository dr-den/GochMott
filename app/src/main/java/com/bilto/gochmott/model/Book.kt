package com.bilto.gochmott.model

/**
 * Вводная часть книги: предисловие, правила построения словаря, а у Мациева ещё
 * слово редактора, список сокращений и алфавит.
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
    /**
     * Текст раздела. Если своей стороны у раздела нет — подставляем другую.
     *
     * Это не запасной вариант «на всякий случай», а обычное состояние: словарь
     * 1997 года напечатал предисловие только по-чеченски, а «Построение словаря»
     * только по-русски. Пустой экран вместо существующего текста был бы хуже,
     * чем текст на другом языке — так же поступает и список сокращений.
     */
    fun body(lang: BookLang): List<BookParagraph> =
        (if (lang == BookLang.RU) ru else ce).ifEmpty {
            if (lang == BookLang.RU) ce else ru
        }

    fun heading(lang: BookLang): String = title[lang].ifBlank { title[lang.other()] }
}

/**
 * Строка списка «О словарях»: книга, а не направление. У двуязычной книги
 * (1997, 2017) оба направления показываются одной записью — читателю важна
 * книга, а `ce->ru` и `ru->ce` это её половины.
 *
 * Собирается `tools/build_books_json.py` из `dicts`, поэтому [entries] не может
 * разойтись с тем, что лежит в базе.
 */
data class BookRef(
    val code: String,
    val title: Bilingual,
    val authors: String,
    val year: Int?,
    /** Сколько статей у книги в базе — сумма по обоим направлениям. */
    val entries: Int,
    /** Файл в `assets` с вводной частью этой книги. */
    val asset: String
) {
    fun heading(lang: BookLang): String = title[lang].ifBlank { title[lang.other()] }
}

/** Статья списка сокращений: `перен.` — «переносное значение» / «кечдина маьӀна». */
data class Abbreviation(val short: String, val expansion: Bilingual)

/** Строка таблицы алфавита: начертание буквы и её название. */
data class AlphabetLetter(val letter: String, val name: String)

/**
 * Вводная часть одной книги. Списка сокращений и алфавита у словарей 1997 и 2017
 * нет вовсе, поэтому их разделы пустые — экраны просто не предлагаются.
 */
data class DictionaryBook(
    val code: String,
    val title: Bilingual,
    val source: String,
    val sections: List<BookSection>,
    val abbreviationsTitle: Bilingual,
    val abbreviations: List<Abbreviation>,
    val alphabetTitle: Bilingual,
    val alphabet: List<AlphabetLetter>
) {
    fun heading(lang: BookLang): String = title[lang].ifBlank { title[lang.other()] }
}
