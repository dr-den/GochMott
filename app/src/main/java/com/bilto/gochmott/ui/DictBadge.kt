package com.bilto.gochmott.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bilto.gochmott.R
import com.bilto.gochmott.model.BookInfo
import com.bilto.gochmott.model.MergedRef
import com.bilto.gochmott.model.SourceAuthority
import com.bilto.gochmott.model.SourceQuality

/**
 * Короткая подпись книги для строки выдачи.
 *
 * В базе шесть словарных направлений, и один запрос отдаёт статьи из нескольких
 * сразу: `маркер` есть и у Мациева, и в компьютерной лексике 2017, и переводом
 * в математическом 1997. Без подписи выдача выглядит как список повторов.
 *
 * Подпись берётся по `dicts.book` — по КНИГЕ, а не по направлению: у двуязычной
 * книги `math1997_ce` и `math1997_ru` это один и тот же источник. Названия лежат
 * в ресурсах, а не в базе: в `dicts` хранится полное библиографическое имя, а на
 * плашку нужно два слова, и это решение интерфейса, а не данных. Незнакомый код
 * (новая книга до правки ресурсов) показывается годом — тоже различает.
 */
object DictBadge {

    @Composable
    fun label(bookCode: String, year: Int?): String = when (bookCode) {
        "maciev1961" -> stringResource(R.string.dict_book_maciev1961)
        "karasaev1978" -> stringResource(R.string.dict_book_karasaev1978)
        "math1997" -> stringResource(R.string.dict_book_math1997)
        "comp2017" -> stringResource(R.string.dict_book_comp2017)
        "aslakhanov2012" -> stringResource(R.string.dict_book_aslakhanov2012)
        else -> year?.toString().orEmpty()
    }
}

/**
 * Паспорта книг по коду `dicts.book`. Кладутся в корень активити (см.
 * `setThemedContent`), чтобы плашке не тащить оценку книги через каждую модель
 * выдачи. Пока база не прочитана, карта пустая — плашки нейтральные.
 */
@Immutable
data class BookCatalog(val byCode: Map<String, BookInfo> = emptyMap()) {
    operator fun get(book: String): BookInfo? = byCode[book]
}

val LocalBookCatalog = compositionLocalOf { BookCatalog() }

/**
 * Цвета плашки по `dicts.quality`.
 *
 * Сдержанно, в той же силе, что нейтральная подложка: плашка — подпись, а не
 * тревога. `rough` — песочный, `raw` — розовато-красный. Оттенки свои, а не из
 * схемы: жёлтого в Material-схеме нет, а `errorContainer` на обоях с
 * динамическими цветами бывает каким угодно. Тёмная тема определяется по самой
 * поверхности — так сходится и с выбранной в меню темой, и с системной.
 */
@Immutable
private data class BadgeColors(val container: Color, val content: Color)

@Composable
private fun badgeColors(quality: SourceQuality?): BadgeColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (quality) {
        SourceQuality.ROUGH ->
            if (dark) BadgeColors(Color(0xFF4A3F17), Color(0xFFEAD48C))
            else BadgeColors(Color(0xFFF8EDC4), Color(0xFF5F4C00))
        SourceQuality.RAW ->
            if (dark) BadgeColors(Color(0xFF55292A), Color(0xFFF2B8B3))
            else BadgeColors(Color(0xFFF8DEDB), Color(0xFF7F2A25))
        else -> BadgeColors(scheme.surfaceVariant.copy(alpha = 0.6f), scheme.onSurfaceVariant)
    }
}

private val BadgeShape = RoundedCornerShape(4.dp)

/** Сама плашка без поведения — общая для строки выдачи, списка «ещё» и подсказки. */
@Composable
private fun BadgeText(text: String, quality: SourceQuality?, modifier: Modifier = Modifier) {
    val colors = badgeColors(quality)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.content,
        maxLines = 1,
        modifier = Modifier
            .clip(BadgeShape)
            .then(modifier)
            .background(colors.container, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * Плашка книги. Пустой код (нет данных) не рисуется вовсе.
 *
 * [alsoIn] — книги, повторяющие эту статью слово в слово. Строка выдачи у них
 * общая, и плашка говорит «Мациев, 1961 +2», а не перечисляет все три: подписана
 * ведущая книга. По тапу — паспорт книги, а у сводной плашки сперва список книг.
 * Всё в выпадающем окне у самой плашки: уводить читателя с выдачи ради справки
 * незачем.
 */
@Composable
fun DictBadgeChip(
    bookCode: String,
    year: Int?,
    modifier: Modifier = Modifier,
    alsoIn: List<MergedRef> = emptyList()
) {
    val label = DictBadge.label(bookCode, year)
    if (label.isEmpty()) return
    val catalog = LocalBookCatalog.current
    val text = if (alsoIn.isNotEmpty()) "$label +${alsoIn.size}" else label
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        BadgeText(
            text = text,
            quality = catalog[bookCode]?.quality,
            modifier = Modifier.clickable { open = true }
        )
        val books = remember(bookCode, year, alsoIn) {
            (listOf(bookCode to year) + alsoIn.map { it.dictBook to it.dictYear })
                .distinctBy { it.first }
        }
        BookInfoMenu(expanded = open, books = books, onDismiss = { open = false })
    }
}

/**
 * Плашки книг одного значения: первые [DICT_BADGE_ROW_LIMIT] и «ещё N».
 *
 * Значение порой подтверждают три-четыре книги, и полный ряд плашек выдавливает
 * сам перевод в узкую колонку. «Ещё N» раскрывает остальные списком там же.
 */
@Composable
fun DictBadgeRow(books: List<MergedRef>, modifier: Modifier = Modifier) {
    if (books.isEmpty()) return
    val shown = if (books.size > DICT_BADGE_ROW_LIMIT) books.take(DICT_BADGE_ROW_LIMIT) else books
    val hidden = books.drop(shown.size)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        shown.forEach { DictBadgeChip(it.dictBook, it.dictYear) }
        if (hidden.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = stringResource(R.string.dict_badges_more, hidden.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(BadgeShape)
                        .clickable { open = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                BookInfoMenu(
                    expanded = open,
                    books = hidden.map { it.dictBook to it.dictYear }.distinctBy { it.first },
                    onDismiss = { open = false }
                )
            }
        }
    }
}

/** Плашек в ряду, прежде чем остальные свернутся в «ещё N». */
private const val DICT_BADGE_ROW_LIMIT = 2

/**
 * Выпадающее окно у плашки: у одной книги — сразу её паспорт, у нескольких —
 * список плашек, тап по которой открывает паспорт на том же месте.
 */
@Composable
private fun BookInfoMenu(
    expanded: Boolean,
    books: List<Pair<String, Int?>>,
    onDismiss: () -> Unit
) {
    var selected by remember(books) { mutableStateOf(books.singleOrNull()?.first) }
    val close = {
        onDismiss()
        selected = books.singleOrNull()?.first
    }
    val catalog = LocalBookCatalog.current
    DropdownMenu(expanded = expanded, onDismissRequest = close) {
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp, max = 300.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val current = selected
            if (current == null) {
                books.forEach { (code, year) ->
                    BadgeText(
                        text = DictBadge.label(code, year),
                        quality = catalog[code]?.quality,
                        modifier = Modifier.clickable { selected = code }
                    )
                }
            } else {
                val year = books.firstOrNull { it.first == current }?.second
                BookInfoContent(current, year, catalog[current])
            }
        }
    }
}

/** Паспорт книги: название, кто составил, в каком виде текст и чем он плох. */
@Composable
private fun BookInfoContent(code: String, year: Int?, info: BookInfo?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BadgeText(DictBadge.label(code, year), info?.quality)
        if (info == null) return@Column
        if (info.title.isNotBlank()) {
            Text(
                text = listOfNotNull(info.title, info.year?.toString()).joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        InfoLine(stringResource(R.string.dict_authority), authorityText(info.authority))
        InfoLine(stringResource(R.string.dict_quality), qualityText(info.quality))
        info.caveat?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoLine(name: String, value: String) {
    Text(
        text = "$name: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun authorityText(authority: SourceAuthority): String = stringResource(
    when (authority) {
        SourceAuthority.ACADEMIC -> R.string.dict_authority_academic
        SourceAuthority.SPECIALIZED -> R.string.dict_authority_specialized
        SourceAuthority.COMMUNITY -> R.string.dict_authority_community
    }
)

@Composable
fun qualityText(quality: SourceQuality): String = stringResource(
    when (quality) {
        SourceQuality.CLEAN -> R.string.dict_quality_clean
        SourceQuality.ROUGH -> R.string.dict_quality_rough
        SourceQuality.RAW -> R.string.dict_quality_raw
    }
)

/** Плашка книги в фильтре — тех же цветов, что в выдаче, но без поведения. */
@Composable
fun DictBadgeLabel(info: BookInfo, modifier: Modifier = Modifier) {
    BadgeText(DictBadge.label(info.book, info.year), info.quality, modifier)
}
