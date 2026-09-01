package com.bilto.gochmott.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.UsageEntry
import com.bilto.gochmott.repository.DictRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UsagesState {
    object Loading : UsagesState()
    data class Success(val entry: UsageEntry) : UsagesState()

    /** Слово перестало находиться — например, база обновилась под открытым экраном. */
    object Empty : UsagesState()
}

/**
 * Употребления чеченского слова, у которого нет своей статьи.
 *
 * Слово приходит маршрутом уже нормализованным (`trans_index.word`), поэтому
 * повторно ничего не приводим: ключ и так тот, по которому построен индекс.
 */
@HiltViewModel
class UsagesViewModel @Inject constructor(
    private val repository: DictRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val word: String = savedStateHandle[WORD_ARG] ?: ""

    private val _state = MutableStateFlow<UsagesState>(UsagesState.Loading)
    val state: StateFlow<UsagesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = repository.chechenUsages(word)
            _state.value = if (entry == null) UsagesState.Empty else UsagesState.Success(entry)
        }
    }

    companion object {
        const val WORD_ARG = "word"
    }
}
