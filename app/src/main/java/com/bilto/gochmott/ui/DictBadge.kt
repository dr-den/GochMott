package com.bilto.gochmott.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bilto.gochmott.R

/**
 * Короткая подпись книги для строки выдачи.
 *
 * В базе пять словарей и один запрос отдаёт статьи из нескольких сразу: `маркер`
 * есть и у Мациева, и в компьютерной лексике 2017, и переводом в математическом
 * 1997. Без подписи выдача выглядит как список повторов.
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
        "math1997" -> stringResource(R.string.dict_book_math1997)
        "comp2017" -> stringResource(R.string.dict_book_comp2017)
        else -> year?.toString().orEmpty()
    }
}

/** Плашка книги. Пустой код (нет данных) не рисуется вовсе. */
@Composable
fun DictBadgeChip(bookCode: String, year: Int?, modifier: Modifier = Modifier) {
    val text = DictBadge.label(bookCode, year)
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
