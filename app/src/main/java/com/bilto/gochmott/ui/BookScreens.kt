package com.bilto.gochmott.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.R
import com.bilto.gochmott.model.Abbreviation
import com.bilto.gochmott.model.AlphabetLetter
import com.bilto.gochmott.model.BookLang
import com.bilto.gochmott.model.BookParagraph
import com.bilto.gochmott.model.BookRef
import com.bilto.gochmott.model.DictionaryBook
import com.bilto.gochmott.viewmodel.BookViewModel

/**
 * Экраны вводных частей словарей.
 *
 * Три уровня: список книг («О словарях») -> разделы одной книги -> текст раздела.
 * Средний и нижний устроены одинаково: шапка с названием, под ней переключатель
 * языка текста, дальше содержимое. Переключатель повторяется на каждом экране
 * (а не стоит один раз в меню), потому что решение «читать это по-чеченски»
 * принимается при чтении конкретного раздела, а не заранее.
 *
 * Заголовки и тексты берутся через `heading()`/`body()`: у словаря 1997 года
 * предисловие напечатано только по-чеченски, а «Построение словаря» только
 * по-русски, и подставить существующую сторону лучше, чем показать пустой экран.
 */

// --------------------------------------------------------------------- каркас

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookScaffold(
    title: String,
    language: BookLang,
    onLanguage: (BookLang) -> Unit,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LanguageToggle(language, onLanguage)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            content(PaddingValues(0.dp))
        }
    }
}

@Composable
private fun LanguageToggle(language: BookLang, onLanguage: (BookLang) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Подписи намеренно на своих языках: «Нохчийн» узнаётся тем, кто будет
        // его читать, а перевод «чеченский» в этой кнопке ничего не добавляет.
        BookLang.entries.forEach { lang ->
            ElevatedFilterChip(
                selected = language == lang,
                onClick = { if (language != lang) onLanguage(lang) },
                label = {
                    Text(
                        text = stringResource(
                            if (lang == BookLang.RU) R.string.book_lang_ru else R.string.book_lang_ce
                        ),
                        fontSize = 13.sp
                    )
                },
                colors = FilterChipDefaults.elevatedFilterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun BookContent(
    viewModel: BookViewModel,
    onBack: () -> Unit,
    title: (DictionaryBook, BookLang) -> String,
    body: @Composable (DictionaryBook, BookLang) -> Unit
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val loaded = book

    BookScaffold(
        title = if (loaded == null) stringResource(R.string.books_title) else title(loaded, language),
        language = language,
        onLanguage = viewModel::setLanguage,
        onBack = onBack
    ) {
        if (loaded == null) {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            body(loaded, language)
        }
    }
}

// ------------------------------------------------------- список книг

/**
 * «О словарях» — верхний уровень. Строка на КНИГУ, а не на направление: у 1997
 * и 2017 оба направления это две половины одной книги, и разводить их по разным
 * строкам значило бы предлагать читателю выбор, которого он не делал.
 */
@Composable
fun BooksListScreen(
    onOpenBook: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BookViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()

    BookScaffold(
        title = stringResource(R.string.books_title),
        language = language,
        onLanguage = viewModel::setLanguage,
        onBack = onBack
    ) {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(books) { book ->
                BookRefRow(book, language, onClick = { onOpenBook(book.code) })
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BookRefRow(book: BookRef, lang: BookLang, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(book.heading(lang), onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.heading(lang),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(book.authors.ifBlank { null }, book.year?.toString())
                    .joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (book.entries > 0) {
                Text(
                    text = stringResource(R.string.book_entries, book.entries),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

// ------------------------------------------------------------------ содержание

@Composable
fun BookHubScreen(
    onOpenSection: (String) -> Unit,
    onOpenAbbreviations: () -> Unit,
    onOpenAlphabet: () -> Unit,
    onBack: () -> Unit,
    viewModel: BookViewModel = hiltViewModel()
) {
    BookContent(viewModel, onBack, title = { book, lang -> book.heading(lang) }) { book, lang ->
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(book.sections) { section ->
                BookRow(
                    title = section.heading(lang),
                    // Читателю лучше знать до тапа, что раздел откроется
                    // на другом языке, — иначе смена языка выглядит поломкой.
                    subtitle = if (section.isFallback(lang)) fallbackLabel(lang) else null,
                    onClick = { onOpenSection(section.id) }
                )
            }
            // Список сокращений и алфавит напечатал только Мациев; у словарей
            // 1997 и 2017 их нет, и строки-пустышки предлагать нечего.
            if (book.abbreviations.isNotEmpty()) {
                item { BookRow(book.abbreviationsTitle[lang], onClick = onOpenAbbreviations) }
            }
            if (book.alphabet.isNotEmpty()) {
                item { BookRow(book.alphabetTitle[lang], onClick = onOpenAlphabet) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = book.source,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

/** Короткая помета «только по-русски» / «только по-чеченски» для нужной стороны. */
@Composable
private fun fallbackLabel(lang: BookLang): String = stringResource(
    if (lang == BookLang.CE) R.string.section_only_ru else R.string.section_only_ce
)

@Composable
private fun BookRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(title, onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

// -------------------------------------------------------------- текстовый раздел

@Composable
fun BookSectionScreen(
    sectionId: String,
    onBack: () -> Unit,
    viewModel: BookViewModel = hiltViewModel()
) {
    BookContent(
        viewModel, onBack,
        title = { book, lang ->
            book.sections.firstOrNull { it.id == sectionId }?.heading(lang).orEmpty()
        }
    ) { book, lang ->
        val section = book.sections.firstOrNull { it.id == sectionId }
        val paragraphs = section?.body(lang).orEmpty()
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Своей стороны у раздела может не быть: словарь 1997 года напечатал
            // предисловие только по-чеченски, а «Построение словаря» только
            // по-русски. Показываем то, что есть, но говорим почему.
            if (section?.isFallback(lang) == true) {
                item {
                    Text(
                        text = stringResource(
                            if (lang == BookLang.CE) R.string.section_fallback_ru
                            else R.string.section_fallback_ce
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(paragraphs) { paragraph -> BookParagraphRow(paragraph) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Абзац раздела.
 *
 * Примеры словарных статей в книге набраны с заглавного слова жирным — по этому
 * признаку они и отличаются от обычного текста. Показываем их плашкой, как
 * примеры в карточке статьи: иначе правило и иллюстрация к нему сливаются
 * в сплошной текст.
 */
@Composable
private fun BookParagraphRow(paragraph: BookParagraph) {
    val isExample = paragraph.firstOrNull()?.bold == true
    val text = buildAnnotatedString {
        paragraph.forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                baselineShift = if (run.superscript) BaselineShift.Superscript else null,
                fontSize = if (run.superscript) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified
            )
            withStyle(style) { append(run.text) }
        }
    }
    if (isExample) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(6.dp)
                )
                .copyOnLongPress(text.text)
                .padding(8.dp)
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.copyOnLongPress(text.text)
        )
    }
}

// ------------------------------------------------------------------ сокращения

@Composable
fun AbbreviationsScreen(onBack: () -> Unit, viewModel: BookViewModel = hiltViewModel()) {
    BookContent(viewModel, onBack, title = { book, lang -> book.abbreviationsTitle[lang] }) { book, lang ->
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            itemsIndexed(book.abbreviations) { i, item ->
                AbbreviationRow(item, lang)
                if (i < book.abbreviations.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AbbreviationRow(item: Abbreviation, lang: BookLang) {
    // У 23 сокращений чеченской расшифровки в книге нет — там показываем русскую,
    // потому что пустая строка рядом с пометой хуже, чем строка на другом языке.
    val expansion = item.expansion[lang].ifBlank { item.expansion[lang.other()] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress("${item.short} — $expansion")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = item.short,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = expansion,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

// -------------------------------------------------------------------- алфавит

@Composable
fun AlphabetScreen(onBack: () -> Unit, viewModel: BookViewModel = hiltViewModel()) {
    BookContent(viewModel, onBack, title = { book, lang -> book.alphabetTitle[lang] }) { book, _ ->
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            itemsIndexed(book.alphabet) { i, letter ->
                AlphabetRow(letter, i + 1)
                if (i < book.alphabet.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AlphabetRow(letter: AlphabetLetter, number: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .copyOnLongPress(letter.letter)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Text(
            text = letter.letter,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = letter.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
