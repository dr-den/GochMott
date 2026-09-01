package com.bilto.gochmott.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.R
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.model.Usage
import com.bilto.gochmott.model.UsageEntry
import com.bilto.gochmott.viewmodel.UsagesState
import com.bilto.gochmott.viewmodel.UsagesViewModel

/**
 * Где встречается чеченское слово, у которого нет своей статьи.
 *
 * Это намеренно НЕ карточка статьи: статьи не существует, и делать вид, что она
 * есть, было бы враньём. Показываем то, что в базе действительно лежит, — русские
 * статьи, в переводах которых слово стоит:
 *
 *  * «Как перевод» (`src` 0 и 3) — слово и есть перевод русской статьи целиком
 *    или её часть: `да́нные` = `хаамаш`;
 *  * «В словосочетаниях» (`src` 1 и 2) — слово внутри примера: «координатийн куп»
 *    — «координатная окрестность».
 *
 * Первое сильнее второго, поэтому разделы идут в этом порядке. Любая строка ведёт
 * в ту русскую статью, откуда она взята: там уже настоящая карточка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsagesScreen(
    onBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    viewModel: UsagesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val entry = (state as? UsagesState.Success)?.entry
                    Text(
                        text = entry?.let { Marks.forLang(Lang.CE, it.word).orEmpty() }
                            ?: stringResource(R.string.usages_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
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
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UsagesState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                is UsagesState.Empty -> Text(
                    text = stringResource(R.string.no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )

                is UsagesState.Success -> UsagesContent(s.entry, onNavigateToDetail)
            }
        }
    }
}

@Composable
private fun UsagesContent(entry: UsageEntry, onNavigateToDetail: (Long) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.usages_no_entry),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val asGloss = entry.asGloss
        if (asGloss.isNotEmpty()) {
            item { UsagesSectionLabel(stringResource(R.string.usages_as_gloss)) }
            items(asGloss, key = { "g_${it.src}_${it.lemmaId}_${it.gloss}" }) { usage ->
                GlossUsageRow(usage, onNavigateToDetail)
            }
        }

        val inPhrases = entry.inPhrases
        if (inPhrases.isNotEmpty()) {
            item { UsagesSectionLabel(stringResource(R.string.usages_in_phrases)) }
            items(inPhrases, key = { "p_${it.lemmaId}_${it.phraseCe}_${it.phraseRu}" }) { usage ->
                PhraseUsageRow(usage, onNavigateToDetail)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun UsagesSectionLabel(text: String) {
    Column {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Слово стоит переводом русской статьи: `да́нные` -> `хаамаш`. */
@Composable
private fun GlossUsageRow(usage: Usage, onNavigateToDetail: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetail(usage.lemmaId) }
            .padding(vertical = 4.dp)
    ) {
        Row {
            Text(
                modifier = Modifier.alignByBaseline(),
                text = Marks.forLang(Lang.RU, usage.ruHeadword).orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            DictBadgeChip(usage.dictBook, usage.dictYear, Modifier.alignByBaseline())
        }
        usage.gloss?.let {
            Text(
                text = Marks.forLang(Lang.CE, it).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.copyOnLongPress(it)
            )
        }
    }
}

/** Слово внутри словосочетания: «координатийн куп» — «координатная окрестность». */
@Composable
private fun PhraseUsageRow(usage: Usage, onNavigateToDetail: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetail(usage.lemmaId) }
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = Marks.forLang(Lang.CE, usage.phraseCe).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.copyOnLongPress(usage.phraseCe.orEmpty())
        )
        if (!usage.phraseRu.isNullOrBlank()) {
            Text(
                text = Marks.forLang(Lang.RU, usage.phraseRu).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
        Spacer(Modifier.height(2.dp))
        Row {
            // Из какой статьи фраза — по ней и открывается карточка.
            Text(
                modifier = Modifier.alignByBaseline(),
                text = Marks.forLang(Lang.RU, usage.ruHeadword).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            DictBadgeChip(usage.dictBook, usage.dictYear, Modifier.alignByBaseline())
        }
    }
}
