package com.bilto.gochmott.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.R
import com.bilto.gochmott.viewmodel.DictFilterViewModel

/**
 * Кнопка фильтра словарей для верхней панели.
 *
 * Меню выпадает прямо из панели: пара галочек — не повод открывать экран.
 * Точка на значке — какие-то книги отключены; без неё легко забыть, что выдача
 * урезана, и решить, что слова в словаре нет.
 *
 * Последнюю включённую книгу выключить нельзя — пустой фильтр ничего не найдёт,
 * и понять причину будет не по чему.
 */
@Composable
fun DictFilterButton(viewModel: DictFilterViewModel = hiltViewModel()) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val disabled by viewModel.disabled.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val narrowed = books.any { it.book in disabled }

    Box {
        IconButton(onClick = { open = true }, enabled = books.isNotEmpty()) {
            BadgedBox(badge = { if (narrowed) Badge() }) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.dict_filter_title)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = stringResource(R.string.dict_filter_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            val enabledCount = books.count { it.book !in disabled }
            books.forEach { info ->
                val checked = info.book !in disabled
                val locked = checked && enabledCount == 1
                DropdownMenuItem(
                    enabled = !locked,
                    onClick = { viewModel.setEnabled(info.book, !checked) },
                    leadingIcon = {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !locked)
                    },
                    text = {
                        Column(
                            modifier = Modifier.widthIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            DictBadgeLabel(info)
                            Text(
                                text = info.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                )
            }
            if (narrowed) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dict_filter_all)) },
                    onClick = { viewModel.enableAll() }
                )
            }
        }
    }
}
