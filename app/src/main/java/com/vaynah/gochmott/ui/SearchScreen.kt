package com.vaynah.gochmott.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaynah.gochmott.model.LemmaHit
import com.vaynah.gochmott.model.SearchDirection
import com.vaynah.gochmott.viewmodel.SearchIntent
import com.vaynah.gochmott.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ГочМотт", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Direction chips + swap
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ElevatedFilterChip(
                    selected = state.direction == SearchDirection.CE_TO_RU,
                    onClick = {
                        if (state.direction != SearchDirection.CE_TO_RU)
                            viewModel.onIntent(SearchIntent.SwapDirection)
                    },
                    label = { Text("ЧЕ → РУ", fontSize = 13.sp) },
                    colors = FilterChipDefaults.elevatedFilterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )

                IconButton(onClick = { viewModel.onIntent(SearchIntent.SwapDirection) }) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Поменять направление",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                ElevatedFilterChip(
                    selected = state.direction == SearchDirection.RU_TO_CE,
                    onClick = {
                        if (state.direction != SearchDirection.RU_TO_CE)
                            viewModel.onIntent(SearchIntent.SwapDirection)
                    },
                    label = { Text("РУ → ЧЕ", fontSize = 13.sp) },
                    colors = FilterChipDefaults.elevatedFilterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // Search field
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onIntent(SearchIntent.QueryChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        if (state.direction == SearchDirection.CE_TO_RU)
                            "Чеченское слово…" else "Русское слово…"
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onIntent(SearchIntent.ClearQuery) }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.medium
            )

            // Body
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when {
                    state.dbError != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Ошибка: ${state.dbError}",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    !state.dbReady -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Загрузка словаря…")
                        }
                    }

                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    state.query.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Введите слово для поиска",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Чеченско-русский словарь Мациева\n≈20 400 статей",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    state.hasNoResults -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Ничего не найдено",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (state.suggestFuzzy) {
                                TextButton(
                                    onClick = { viewModel.onIntent(SearchIntent.RetryFuzzy) }
                                ) {
                                    Text("Поиск по части слова / опечатки")
                                }
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.results, key = { it.id }) { hit ->
                                HitCard(hit = hit, onClick = { onNavigateToDetail(hit.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: LemmaHit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Headword row
            Row {
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = hit.headword,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (hit.homographN > 1) {
                    Text(
                        text = "${hit.homographN}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp).alignByBaseline()
                    )
                }
                if (hit.pos != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        modifier = Modifier.alignByBaseline(),
                        text = hit.pos,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                // Flags
                val flags = buildList {
                    if (hit.pluraliaTantum) add("мн.")
                    if (hit.indeclinable) add("нескл.")
                }
                if (flags.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        modifier = Modifier.alignByBaseline(),
                        text = flags.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Gram classes
            if (hit.classes.isNotEmpty()) {
                val sg = hit.classes.filter { it.number == "sg" }.map { it.marker }
                val pl = hit.classes.filter { it.number == "pl" }.map { it.marker }
                val classStr = buildString {
                    if (sg.isNotEmpty()) append("ед[${sg.joinToString(",")}]")
                    if (sg.isNotEmpty() && pl.isNotEmpty()) append(" ")
                    if (pl.isNotEmpty()) append("мн[${pl.joinToString(",")}]")
                }
                if (classStr.isNotEmpty()) {
                    Text(
                        text = classStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // First senses
            if (hit.firstSenses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                hit.firstSenses.forEachIndexed { i, gloss ->
                    Text(
                        text = buildAnnotatedString {
                            if (hit.firstSenses.size > 1) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                    append("${i + 1}. ")
                                }
                            }
                            append(gloss)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

