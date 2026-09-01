package com.bilto.gochmott.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.BookLang
import com.bilto.gochmott.model.BookRef
import com.bilto.gochmott.model.DictionaryBook
import com.bilto.gochmott.repository.BookRepository
import com.bilto.gochmott.settingsrepo.SettingKeys
import com.bilto.gochmott.settingsrepo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Вводная часть ОДНОЙ книги и выбранный язык её текста.
 *
 * Код книги приходит из маршрута навигации: экраны разделов лежат под
 * `book/{bookCode}/…`, и каждая копия ViewModel знает, чью вводную часть грузить.
 *
 * Язык хранится в настройках, а не в состоянии экрана: читатель, открывший
 * предисловие по-чеченски, ожидает увидеть по-чеченски и раздел о построении
 * словаря — и в другой книге тоже. Каждый экран берёт свою копию ViewModel, но
 * все они смотрят в один и тот же поток настройки и остаются согласованными.
 */
@HiltViewModel
class BookViewModel @Inject constructor(
    private val repository: BookRepository,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** null на экране списка книг: там ничьей вводной части не показывают. */
    private val bookCode: String? = savedStateHandle[BOOK_CODE_ARG]

    private val _book = MutableStateFlow<DictionaryBook?>(null)
    val book: StateFlow<DictionaryBook?> = _book.asStateFlow()

    private val _books = MutableStateFlow<List<BookRef>>(emptyList())

    /** Список книг для экрана «О словарях». */
    val books: StateFlow<List<BookRef>> = _books.asStateFlow()

    val language: StateFlow<BookLang> = settings.get(SettingKeys.bookLanguage)
        .map { if (it == BookLang.CE.name) BookLang.CE else BookLang.RU }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BookLang.RU)

    init {
        viewModelScope.launch { _books.value = repository.index() }
        // Без кода книги не читаем ничего: у Мациева вводная часть на 100 КБ,
        // и тянуть её ради списка из трёх строк незачем.
        bookCode?.let { code -> viewModelScope.launch { _book.value = repository.load(code) } }
    }

    fun setLanguage(lang: BookLang) {
        settings.set(SettingKeys.bookLanguage, lang.name)
    }

    companion object {
        /** Имя аргумента маршрута; см. `Screen.Book`. */
        const val BOOK_CODE_ARG = "bookCode"
    }
}
