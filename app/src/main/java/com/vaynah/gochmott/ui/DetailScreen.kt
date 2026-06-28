package com.vaynah.gochmott.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vaynah.gochmott.model.EntryDetail
import com.vaynah.gochmott.model.Example
import com.vaynah.gochmott.model.Form
import com.vaynah.gochmott.model.Ref
import com.vaynah.gochmott.model.Sense
import com.vaynah.gochmott.viewmodel.DetailState
import com.vaynah.gochmott.viewmodel.DetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    lemmaId: Long,
    viewModel: DetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onSearchQuery: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state is DetailState.Success) {
                        val detail = (state as DetailState.Success).detail
                        Text(
                            text = buildAnnotatedString {
                                append(detail.lemma.headword)
                                if (detail.lemma.homographN > 1) {
                                    withStyle(SpanStyle(fontSize = 12.sp, baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript)) {
                                        append("${detail.lemma.homographN}")
                                    }
                                }
                            },
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text("Статья")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val s = state) {
                is DetailState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is DetailState.Error -> {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }

                is DetailState.Success -> {
                    DetailContent(
                        detail = s.detail,
                        onNavigateToDetail = onNavigateToDetail,
                        onSearchQuery = onSearchQuery
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailContent(
    detail: EntryDetail,
    onNavigateToDetail: (Long) -> Unit,
    onSearchQuery: (String) -> Unit
) {
    val lemma = detail.lemma

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header: headword + pos + flags + gram note
        item {
            Column {
                Row {
                    Text(
                        modifier = Modifier.alignByBaseline(),
                        text = lemma.headword,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (lemma.pos != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            modifier = Modifier.alignByBaseline(),
                            text = lemma.pos,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    val flags = buildList {
                        if (lemma.pluraliaTantum) add("мн.")
                        if (lemma.indeclinable) add("нескл.")
                    }
                    if (flags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modifier = Modifier.alignByBaseline(),
                            text = flags.joinToString(" "),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                // Gram classes
                if (lemma.classes.isNotEmpty()) {
                    val sg = lemma.classes.filter { it.number == "sg" }.map { it.marker }
                    val pl = lemma.classes.filter { it.number == "pl" }.map { it.marker }
                    val classStr = buildString {
                        if (sg.isNotEmpty()) append("ед[${sg.joinToString(",")}]")
                        if (sg.isNotEmpty() && pl.isNotEmpty()) append("  ")
                        if (pl.isNotEmpty()) append("мн[${pl.joinToString(",")}]")
                    }
                    Text(
                        text = classStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!lemma.gramNote.isNullOrBlank()) {
                    Text(
                        text = lemma.gramNote,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Senses
        if (detail.senses.isNotEmpty()) {
            item {
                SectionLabel("Значения")
            }
            items(detail.senses) { sense ->
                SenseRow(sense)
            }
        }

        // Word forms table
        if (detail.forms.isNotEmpty()) {
            item {
                SectionLabel("Формы слова")
                Spacer(Modifier.height(4.dp))
                FormsTable(detail.forms)
            }
        }

        // Examples and idioms
        val examples = detail.examples.filter { it.kind == "example" || it.kind == "collocation" }
        val idioms = detail.examples.filter { it.kind == "idiom" || it.kind == "proverb" || it.kind == "saying" }

        if (examples.isNotEmpty()) {
            item { SectionLabel("Примеры") }
            items(examples) { ex -> ExampleRow(ex) }
        }

        if (idioms.isNotEmpty()) {
            item { SectionLabel("◊ Идиомы и пословицы") }
            items(idioms) { ex -> ExampleRow(ex) }
        }

        // Cross-references
        if (detail.refs.isNotEmpty()) {
            item { SectionLabel("Ссылки") }
            items(detail.refs) { ref ->
                RefRow(
                    ref = ref,
                    onNavigate = { id -> onNavigateToDetail(id) },
                    onSearch = { q -> onSearchQuery(q) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
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

@Composable
private fun SenseRow(sense: Sense) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${sense.senseNo}.",
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(24.dp)
        )
        Column {
            Text(
                text = sense.glossRu,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!sense.domain.isNullOrBlank()) {
                Text(
                    text = sense.domain,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FormsTable(forms: List<Form>) {
    // Group non-headword forms; show headword at top
    val headword = forms.firstOrNull { it.isHeadword }
    val rest = forms.filter { !it.isHeadword }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Group by number (sg/pl) then TAM or case
        val grouped = rest.groupBy { it.number ?: "" }
        grouped.forEach { (number, groupForms) ->
            if (number.isNotEmpty()) {
                Text(
                    text = if (number == "sg") "Единственное число" else "Множественное число",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            groupForms.forEach { form ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val label = when {
                        form.tam != null -> form.tam
                        form.caseAbbr != null -> "${form.caseAbbr}."
                        else -> "—"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = form.form,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExampleRow(example: Example) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = example.ceText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        if (!example.ruText.isNullOrBlank()) {
            Text(
                text = example.ruText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
private fun RefRow(
    ref: Ref,
    onNavigate: (Long) -> Unit,
    onSearch: (String) -> Unit
) {
    val relLabel = when (ref.relType) {
        "see" -> "см."
        "compare" -> "ср."
        "same_as" -> "то же что"
        "variant" -> "вариант"
        "plural_of" -> "мн. от"
        "aspect_pair" -> "вид. пара"
        else -> ref.relType
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (ref.toLemmaId != null) onNavigate(ref.toLemmaId)
                else onSearch(ref.toHeadwordRaw)
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$relLabel ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ref.toHeadwordRaw,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (ref.toLemmaId != null) TextDecoration.Underline else TextDecoration.None
                ),
                color = if (ref.toLemmaId != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
        if (ref.toLemmaId != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.Companion.then(Modifier.padding(start = 4.dp))
            )
        }
    }
}

