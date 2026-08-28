package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.BookLang
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
 * Вводная часть словаря и выбранный язык её текста.
 *
 * Язык хранится в настройках, а не в состоянии экрана: читатель, открывший
 * предисловие по-чеченски, ожидает увидеть по-чеченски и раздел о построении
 * словаря. Каждый экран берёт свою копию ViewModel, но все они смотрят в один
 * и тот же поток настройки и остаются согласованными.
 */
@HiltViewModel
class BookViewModel @Inject constructor(
    private val repository: BookRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _book = MutableStateFlow<DictionaryBook?>(null)
    val book: StateFlow<DictionaryBook?> = _book.asStateFlow()

    val language: StateFlow<BookLang> = settings.get(SettingKeys.bookLanguage)
        .map { if (it == BookLang.CE.name) BookLang.CE else BookLang.RU }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BookLang.RU)

    init {
        viewModelScope.launch { _book.value = repository.load() }
    }

    fun setLanguage(lang: BookLang) {
        settings.set(SettingKeys.bookLanguage, lang.name)
    }
}
