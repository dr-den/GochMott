package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.BookInfo
import com.bilto.gochmott.repository.DictSources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Фильтр словарей в верхней панели — один на поиск и карточку.
 *
 * Состояния своего не держит: и список книг, и отключённые лежат в [DictSources],
 * а экраны сами пересобирают выдачу, когда набор меняется.
 */
@HiltViewModel
class DictFilterViewModel @Inject constructor(
    private val sources: DictSources
) : ViewModel() {

    val books: StateFlow<List<BookInfo>> = sources.books

    val disabled: StateFlow<Set<String>> =
        sources.disabledBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        // Экран поиска может открыть меню раньше первого запроса — список книг
        // должен быть к этому времени. Базу это откроет, если она ещё закрыта,
        // но ставит её всё равно поиск: копирование одно, под блокировкой lazy.
        viewModelScope.launch {
            try {
                sources.load()
            } catch (_: Exception) {
                // Без базы фильтровать нечего; ошибку покажет сам экран.
            }
        }
    }

    fun setEnabled(book: String, enabled: Boolean) = sources.setEnabled(book, enabled)

    fun enableAll() = sources.enableAll()
}
