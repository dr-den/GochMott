package com.bilto.gochmott.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.R
import com.bilto.gochmott.capitalizeFirst
import com.bilto.gochmott.model.DictStats
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.SearchDirection
import com.bilto.gochmott.model.UsageEntry
import com.bilto.gochmott.viewmodel.SearchIntent
import com.bilto.gochmott.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToUsages: (String) -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    val openDetail: (Long) -> Unit = { lemmaId ->
        viewModel.onIntent(SearchIntent.QuerySubmitted)
        onNavigateToDetail(lemmaId)
    }

    val openUsages: (String) -> Unit = { word ->
        viewModel.onIntent(SearchIntent.QuerySubmitted)
        onNavigateToUsages(word)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.menu_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
                    label = { Text(stringResource(R.string.lang_ce_to_ru), fontSize = 13.sp) },
                    colors = FilterChipDefaults.elevatedFilterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )

                IconButton(onClick = { viewModel.onIntent(SearchIntent.SwapDirection) }) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = stringResource(R.string.swap_direction_content_description),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                ElevatedFilterChip(
                    selected = state.direction == SearchDirection.RU_TO_CE,
                    onClick = {
                        if (state.direction != SearchDirection.RU_TO_CE)
                            viewModel.onIntent(SearchIntent.SwapDirection)
                    },
                    label = { Text(stringResource(R.string.lang_ru_to_ce), fontSize = 13.sp) },
                    colors = FilterChipDefaults.elevatedFilterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // Search field
            // Пока словарь ставится из assets, поле видно, но не принимает ввод:
            // искать всё равно нечем, а живое поле молча съедало бы набранное.
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onIntent(SearchIntent.QueryChanged(it)) },
                enabled = state.dbReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        if (state.direction == SearchDirection.CE_TO_RU)
                            stringResource(R.string.search_hint_ce)
                        else
                            stringResource(R.string.search_hint_ru)
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onIntent(SearchIntent.ClearQuery) }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.clear_content_description)
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.onIntent(SearchIntent.QuerySubmitted)
                        focusManager.clearFocus()
                    }
                ),
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
                                stringResource(R.string.error_prefix, state.dbError ?: ""),
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
                            // Доля известна только пока идёт копирование из assets.
                            // До и после него (открытие БД, проверка версии) крутилка
                            // неопределённая — там считать нечего.
                            val progress = state.dbProgress
                            if (progress == null) {
                                CircularProgressIndicator()
                            } else {
                                CircularProgressIndicator(progress = { progress })
                            }
                            Text(stringResource(R.string.loading_dictionary))
                            if (progress != null) {
                                Text(
                                    text = stringResource(
                                        R.string.loading_dictionary_percent,
                                        (progress * 100).toInt()
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    state.query.isEmpty() && state.history.isNotEmpty() -> {
                        SearchHistoryList(
                            entries = state.history,
                            onSelect = { viewModel.onIntent(SearchIntent.HistorySelected(it)) },
                            onRemove = { viewModel.onIntent(SearchIntent.HistoryRemoved(it)) },
                            onClearAll = { viewModel.onIntent(SearchIntent.ClearHistory) }
                        )
                    }

                    state.query.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.search_hint_empty_state),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Считает база, а не ресурсы: захардкоженная цифра
                            // разойдётся с ней при первой же пересборке. Слова —
                            // той стороны, которую сейчас ищут: в ЧЕ→РУ спрашивают
                            // чеченское слово, в РУ→ЧЕ русское.
                            val stats = state.stats
                            if (stats == null) {
                                // Первый запуск после смены словаря: цифры ещё
                                // считаются на IO. Держим место, а не дёргаем
                                // вёрстку скачком, когда строка появится.
                                if (!state.statsSettled) StatsPlaceholder()
                            } else {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.stats_line,
                                        pluralStringResource(
                                            R.plurals.stats_books, stats.books, stats.books
                                        ),
                                        wordsLabel(stats, state.direction)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    state.hasNoResults -> {
                        Box(modifier = Modifier.align(Alignment.Center)) {
                            Text(
                                stringResource(R.string.no_results),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 1. Точные совпадения — всегда сверху
                            if (state.hasExact) {
                                items(state.exactResults, key = { "exact_${it.id}" }) { hit ->
                                    HitCard(hit = hit, onClick = { openDetail(hit.id) })
                                }
                            }

                            // 1б. ЧЕ→РУ: слово без своей статьи, но живущее в переводах
                            // книг рус→чеч. Заголовок чеченский — как его и искали.
                            state.usage?.let { usage ->
                                item(key = "usage_${usage.key}") {
                                    UsageCard(usage = usage, onClick = { openUsages(usage.key) })
                                }
                            }

                            // 2а. РУ→ЧЕ: похожие русские слова вместо статей — сначала
                            if (state.suggestions.isNotEmpty()) {
                                item(key = "suggestions") {
                                    SuggestionsBlock(
                                        query = state.query,
                                        words = state.suggestions,
                                        onWordSelected = {
                                            viewModel.onIntent(SearchIntent.SuggestionSelected(it))
                                        }
                                    )
                                }
                            }

                            // 2б. ЧЕ→РУ: похожие статьи отдельным блоком под точными
                            if (state.fuzzyResults.isNotEmpty()) {
                                item(key = "fuzzy_header") {
                                    FuzzyHeader(query = state.query, hasExact = state.hasExact)
                                }
                                items(state.fuzzyResults, key = { "fuzzy_${it.id}" }) { hit ->
                                    HitCard(hit = hit, onClick = { openDetail(hit.id) })
                                }
                            } else if (state.suggestions.isEmpty() && state.isFuzzyLoading) {
                                item(key = "fuzzy_loading") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .height(16.dp)
                                                .width(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.searching_similar_words),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    entries: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "history_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.recent_searches),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text(stringResource(R.string.clear_history))
                }
            }
        }

        items(entries, key = { "history_$it" }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemove(entry) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove_from_history),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Заголовок блока примерных совпадений.
 */
@Composable
private fun FuzzyHeader(query: String, hasExact: Boolean) {
    Column(modifier = Modifier.padding(top = if (hasExact) 12.dp else 4.dp, bottom = 2.dp)) {
        if (hasExact) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.similar_words),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            NotFoundText(query)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
internal fun NotFoundText(query: String) {
    Text(
        text = buildAnnotatedString {
            append(stringResource(R.string.words).capitalizeFirst())
            append(" «")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(query.trim()) }
            append("» ")
            append(stringResource(R.string.word_not_found))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * РУ→ЧЕ: похожие русские слова.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsBlock(
    query: String,
    words: List<String>,
    onWordSelected: (String) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        NotFoundText(query)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            words.forEach { word ->
                SuggestionChip(
                    onClick = { onWordSelected(word) },
                    label = { Text(word) },
                    shape = MaterialTheme.shapes.small
                )
            }
        }
    }
}

/**
 * Слово, у которого нет своей статьи.
 *
 * Заголовок чеченский — иначе выдача «чеченский → русский» превращается в список
 * русских слов. Но карточка намеренно не похожа на статью: у неё рамка вместо
 * заливки и подпись «встречается в переводах», потому что за ней не статья из
 * книги, а список мест, где слово стоит.
 */
/**
 * Место под подпись, пока цифры считаются.
 *
 * Появляется только при первом запуске после смены словаря — дальше цифры
 * лежат в `common.db` и приходят сразу. Пульсирующий прямоугольник, а не пустота:
 * иначе строка возникает рывком и сдвигает вёрстку под уже читающим глазом.
 */
@Composable
private fun StatsPlaceholder() {
    val pulse = rememberInfiniteTransition(label = "stats")
    val alpha by pulse.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "statsAlpha"
    )
    Box(
        modifier = Modifier
            .padding(top = 10.dp)
            .width(190.dp)
            .height(14.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                RoundedCornerShape(4.dp)
            )
    )
}

/** «20 653 чеченских слова» либо «26 224 русских слова» — смотря что сейчас ищут. */
@Composable
private fun wordsLabel(stats: DictStats, direction: SearchDirection): String {
    val count = stats.wordsFor(direction)
    val plural = if (direction == SearchDirection.CE_TO_RU) {
        R.plurals.stats_words_ce
    } else {
        R.plurals.stats_words_ru
    }
    return pluralStringResource(plural, count, count)
}

@Composable
private fun UsageCard(usage: UsageEntry, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(usage.word, onClick),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = Marks.forLang(Lang.CE, usage.word).orEmpty(),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = stringResource(R.string.usage_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(4.dp))
            // Превью: перевод сильнее словосочетания, поэтому глоссы идут первыми.
            val preview = (usage.asGloss + usage.inPhrases).take(USAGE_PREVIEW)
            preview.forEach { u ->
                Text(
                    text = if (u.isGloss) {
                        Marks.forLang(Lang.RU, u.ruHeadword).orEmpty()
                    } else {
                        Marks.forLang(Lang.RU, u.phraseRu).orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (usage.usages.size > preview.size) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.usage_more, usage.usages.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val USAGE_PREVIEW = 2

@Composable
private fun HitCard(hit: LemmaHit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(hit.headword, onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Headword row. Знак надстрочный выбирается по языку ЗАГОЛОВКА: у статьи
            // `ада́птер` из словаря рус→чеч он русский, а не чеченский.
            Row {
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = Marks.forLang(hit.lang, hit.headword).orEmpty(),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (hit.homographN > 0) {
                    Text(
                        text = "${hit.homographN}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .alignByBaseline()
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
                    if (hit.pluraliaTantum) add(stringResource(R.string.pluralia_tantum))
                    if (hit.indeclinable) add(stringResource(R.string.indeclinable))
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
                // Из какой книги статья. Один запрос отдаёт статьи из нескольких
                // словарей сразу, и без подписи выдача выглядит как список повторов.
                Spacer(Modifier.weight(1f))
                DictBadgeChip(
                    hit.dictBook, hit.dictYear, Modifier.alignByBaseline(), hit.alsoIn.size
                )
            }

            // Gram classes
            if (hit.classes.isNotEmpty()) {
                val sg = hit.classes.filter { it.number == "sg" }.map { it.marker }
                val pl = hit.classes.filter { it.number == "pl" }.map { it.marker }
                val classStr = buildString {
                    if (sg.isNotEmpty()) append(stringResource(R.string.sg_forms, ClassMarker.list(sg)))
                    if (sg.isNotEmpty() && pl.isNotEmpty()) append(" ")
                    if (pl.isNotEmpty()) append(stringResource(R.string.pl_forms, ClassMarker.list(pl)))
                }
                if (classStr.isNotEmpty()) {
                    Text(
                        text = classStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Совпавший перевод (только для поиска рус->чеч)
            if (hit.matchedGloss != null &&
                hit.firstSenses.none { it.startsWith(hit.matchedGloss) }
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Marks.forLang(hit.glossLang, hit.matchedGloss).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
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
                            append(Marks.forLang(hit.glossLang, gloss).orEmpty())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

