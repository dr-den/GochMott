package com.bilto.gochmott.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.EntryDetail
import com.bilto.gochmott.repository.DictRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DetailState {
    object Loading : DetailState()
    data class Success(val detail: EntryDetail) : DetailState()
    data class Error(val message: String) : DetailState()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: DictRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        val lemmaId = savedStateHandle.get<Long>("lemmaId") ?: -1L
        if (lemmaId >= 0) load(lemmaId)
    }

    fun load(lemmaId: Long) {
        viewModelScope.launch {
            _state.value = DetailState.Loading
            try {
                val detail = repository.getEntryDetail(lemmaId)
                _state.value = DetailState.Success(detail)
            } catch (e: Exception) {
                _state.value = DetailState.Error(e.message ?: "Ошибка загрузки статьи")
            }
        }
    }
}
