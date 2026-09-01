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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
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
import com.bilto.gochmott.R
import com.bilto.gochmott.model.DictSource
import com.bilto.gochmott.model.EntryDetail
import com.bilto.gochmott.model.Example
import com.bilto.gochmott.model.Gloss
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.model.LinkedEntry
import com.bilto.gochmott.model.Form
import com.bilto.gochmott.model.Ref
import com.bilto.gochmott.model.Sense
import com.bilto.gochmott.viewmodel.DetailState
import com.bilto.gochmott.viewmodel.DetailViewModel

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
                                append(Marks.forLang(detail.lemma.lang, detail.lemma.headword).orEmpty())
                                if (detail.lemma.homographN > 0) {
                                    withStyle(SpanStyle(fontSize = 12.sp, baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript)) {
                                        append("${detail.lemma.homographN}")
                                    }
                                }
                            },
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(stringResource(R.string.article))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(
                            R.string.back
                        ))
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
                        modifier = Modifier
                            .alignByBaseline()
                            .copyOnLongPress(lemma.headword),
                        text = Marks.forLang(lemma.lang, lemma.headword).orEmpty(),
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
                        if (sg.isNotEmpty()) append(stringResource(R.string.sg_forms, sg.joinToString(",")))
                        if (sg.isNotEmpty() && pl.isNotEmpty()) append("  ")
                        if (pl.isNotEmpty()) append(stringResource(R.string.pl_forms, pl.joinToString(",")))
                    }
                    Text(
                        text = classStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // «объект в ед.» / «субъект во мн.» — у Мациева это отдельные
                // пометы переходности, в БД они лежат полями obj_num / subj_num.
                val govNotes = buildList {
                    lemma.objNum?.let { add(if (it == "sg") "объект в ед." else "объект во мн.") }
                    lemma.subjNum?.let { add(if (it == "sg") "субъект в ед." else "субъект во мн.") }
                    if (lemma.isClassAgreeing) add("изменяется по классам")
                }
                if (govNotes.isNotEmpty()) {
                    Text(
                        text = govNotes.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (lemma.labels.isNotEmpty()) {
                    Text(
                        text = lemma.labels.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Значения. Примеры теперь висят на значении, а не на статье, и
        // показываются прямо под своим переводом — как в книге.
        if (detail.senses.isNotEmpty()) {
            item {
                SectionLabel(stringResource(R.string.meanings))
            }
            itemsIndexed(detail.senses) { i, sense ->
                val previous = detail.senses.getOrNull(i - 1)
                SenseBlock(
                    sense = sense,
                    startsBlock = sense.blockN != null && sense.blockN != previous?.blockN,
                    lang = lemma.lang
                )
            }
        }

        // Word forms table
        if (detail.forms.isNotEmpty()) {
            item {
                SectionLabel(stringResource(R.string.word_forms))
                Spacer(Modifier.height(4.dp))
                FormsTable(detail.forms, lemma.lang)
            }
        }

        // Идиомы за ромбом «◊» относятся к статье целиком, а не к значению.
        if (detail.idioms.isNotEmpty()) {
            item { SectionLabel("◊ " + stringResource(R.string.idioms_and_proverbs)) }
            items(detail.idioms) { ex -> ExampleRow(ex, lemma.lang) }
        }

        // Cross-references
        if (detail.refs.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.refs)) }
            items(detail.refs) { ref ->
                RefRow(
                    ref = ref,
                    lang = lemma.lang,
                    onNavigate = { id -> onNavigateToDetail(id) },
                    onSearch = { q -> onSearchQuery(q) }
                )
            }
        }

        // Это же слово в других книгах (`lemma_links`). Пока словарь один — пусто.
        if (detail.related.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.other_books)) }
            items(detail.related) { link ->
                LinkedEntryRow(link = link, onNavigate = onNavigateToDetail)
            }
        }

        // Откуда статья. Готовую строку даёт паспорт словаря (`dicts.citation`) —
        // склеивать её в UI не нужно.
        detail.source?.let { source ->
            item { SourceLine(source) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Та же лемма в другой книге. Поля книг не сливаются: если `conflict` непуст,
 * книги расходятся в классе или части речи — это разночтение источников, а не
 * ошибка, поэтому обе статьи показываются рядом, без «победителя».
 *
 * `reviewed=1` значит, что пару уже смотрел человек (`tools/reviewed.tsv`) и
 * подтвердил: расхождение настоящее. Тогда тревожная плашка «книги расходятся»
 * не нужна — вместо неё показываем его же объяснение из `note`. Сам класс всё
 * равно приходит от обеих книг, подтверждение не выбирает победителя.
 */
@Composable
private fun LinkedEntryRow(link: LinkedEntry, onNavigate: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(link.lemmaId) }
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(Marks.forLang(link.lang, link.headword).orEmpty())
                }
                if (link.homographN > 0) {
                    withStyle(
                        SpanStyle(
                            fontSize = 10.sp,
                            baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
                            color = MaterialTheme.colorScheme.primary
                        )
                    ) { append("${link.homographN}") }
                }
                append("  ")
                withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { append(link.dictTitle) }
            },
            style = MaterialTheme.typography.bodyMedium
        )
        val explanation = when {
            link.reviewed && !link.note.isNullOrBlank() -> link.note
            link.reviewed -> stringResource(R.string.link_reviewed)
            link.conflict.isNotEmpty() ->
                stringResource(R.string.link_conflict, link.conflict.joinToString(", "))
            else -> null
        }
        if (explanation != null) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = if (link.reviewed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/** Подпись под статьёй: из какой книги она взята. */
@Composable
private fun SourceLine(source: DictSource) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )
        Text(
            text = source.citation.ifBlank {
                listOfNotNull(source.authors, source.title, source.year?.toString())
                    .joinToString(". ")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 6.dp)
                .copyOnLongPress(source.citation)
        )
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
private fun SenseBlock(sense: Sense, startsBlock: Boolean, lang: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Блок `1.` / `2.` — это части речи внутри одной статьи
        // (хе̃наза: 1. прил. преждевременный, 2. нареч. преждевременно).
        if (startsBlock) {
            Text(
                text = listOfNotNull(sense.blockN?.let { "$it." }, sense.pos)
                    .joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sense.senseNo?.let { "$it." }.orEmpty(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sense.labels.isNotEmpty()) {
                    Text(
                        text = sense.labels.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Text(
                    text = glossesText(sense.glosses),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.copyOnLongPress(plainGlosses(sense.glosses))
                )
                sense.examples.forEach { ExampleRow(it, lang) }
            }
        }
    }
}

/**
 * Переводы одного значения одной строкой.
 *
 * Разделитель берётся из книги: `,` между синонимами, `;` перед более далёким
 * переводом. Уточнение (`кисть` у «рука́») и управление (`чем-л.`) идут курсивом,
 * чтобы их не читали как часть перевода.
 */
/**
 * Те же переводы, но голым текстом — для буфера обмена.
 *
 * Курсивные уточнение и управление отброшены: «рука́», а не «рука́ (кисть)».
 * В скобках стоит пояснение к переводу, и вставлять его вместе с переводом
 * пользователь не просил.
 */
private fun plainGlosses(glosses: List<Gloss>): String = buildString {
    glosses.forEachIndexed { i, gloss ->
        if (i > 0) append((gloss.sep ?: ",") + " ")
        append(gloss.text)
    }
}

/** Классы чеченского перевода, как их печатает книга 2017: `адаптер (й, й)`. */
private fun clsSuffix(cls: List<String>): String =
    if (cls.isEmpty()) "" else cls.joinToString(", ", prefix = " (", postfix = ")")

@Composable
private fun glossesText(glosses: List<Gloss>) = buildAnnotatedString {
    val aside = SpanStyle(
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    glosses.forEachIndexed { i, gloss ->
        if (i > 0) append((gloss.sep ?: ",") + " ")
        if (gloss.labels.isNotEmpty()) {
            withStyle(aside) { append(gloss.labels.joinToString(" ") + " ") }
        }
        append(Marks.forLang(gloss.lang, gloss.text).orEmpty())
        // Классный показатель перевода: у словарей рус->чеч он стоит при чеченском
        // слове -- `адаптер (й, й)`, -- и в базе лежит в `glosses.gram.cls`.
        if (gloss.cls.isNotEmpty()) withStyle(aside) { append(clsSuffix(gloss.cls)) }
        gloss.gov?.let { withStyle(aside) { append(" $it") } }
        gloss.note?.let { withStyle(aside) { append(" (${Marks.forLang(gloss.lang, it)})") } }
    }
}

@Composable
private fun FormsTable(forms: List<Form>, lang: String) {
    // Заголовочную форму репозиторий не отдаёт — она в шапке карточки
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
                    text = if (number == "sg") stringResource(R.string.sg) else stringResource(R.string.pl),
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
                        // abbr_ru приходит уже с точкой («им.», «род.») — как в книге
                        form.caseAbbr != null -> form.caseAbbr
                        else -> "—"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = Marks.forLang(lang, form.form).orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(0.6f)
                            .copyOnLongPress(form.form)
                    )
                }
            }
        }
    }
}

/**
 * Пример или идиома.
 *
 * `examples.ce` / `examples.ru` названы по ЯЗЫКУ, а не по роли, поэтому порядок
 * сторон на экране задаёт язык ЗАГОЛОВКА статьи: у `ада́птер` из словаря рус->чеч
 * сверху должно стоять русское сочетание «адаптер не найден», а переводом под
 * ним — «адаптер ца карийна», а не наоборот.
 */
@Composable
private fun ExampleRow(example: Example, lang: String) {
    val headSide = if (lang == Lang.RU) example.ruText.orEmpty() else example.ceText
    val transSide = if (lang == Lang.RU) example.ceText else example.ruText
    val transLang = Lang.other(lang)
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
            text = buildAnnotatedString {
                append(Marks.forLang(lang, headSide).orEmpty())
                // «посл.» / «погов.» — это разряд примера, а не часть текста
                if (example.kind != null && example.kind != "phrase") {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(" ${example.kind}")
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.copyOnLongPress(headSide)
        )
        // У части примеров перевод разбит на подпункты: «куьг таӀо — а) …, б) …»
        if (example.subs.isNotEmpty()) {
            example.subs.forEach { sub ->
                Text(
                    text = listOfNotNull(
                        sub.letter?.let { "$it)" },
                        Marks.forLang(sub.lang, sub.text),
                        sub.gov
                    ).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.copyOnLongPress(sub.text)
                )
            }
        } else if (!transSide.isNullOrBlank()) {
            Text(
                text = Marks.forLang(transLang, transSide).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.copyOnLongPress(transSide)
            )
        }
        // Буквальный перевод идиомы: (букв. все, кто мо́жет владе́ть па́лкой)
        if (!example.note.isNullOrBlank()) {
            Text(
                text = "(" + listOfNotNull(example.noteKind, Marks.forLang(transLang, example.note))
                    .joinToString(" ") + ")",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun RefRow(
    ref: Ref,
    lang: String,
    onNavigate: (Long) -> Unit,
    onSearch: (String) -> Unit
) {
    // Раньше здесь был перевод кодов `see`/`compare` в текст. Теперь отношение
    // приходит из словаря как есть — «понуд. от», «потенц. от», «прил. к»,
    // «мн. от»: у Мациева это готовые пометы, и их 15 видов вместо двух.
    val relLabel = ref.rel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(ref.toHeadword) {
                if (ref.toLemmaId != null) onNavigate(ref.toLemmaId)
                else onSearch(ref.toHeadword)
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
                text = Marks.forLang(lang, ref.toHeadword).orEmpty(),
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

