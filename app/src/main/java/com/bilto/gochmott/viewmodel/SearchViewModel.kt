package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.db.DatabaseHelper
import com.bilto.gochmott.model.DictStats
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.SearchDirection
import com.bilto.gochmott.model.UsageEntry
import com.bilto.gochmott.repository.DictRepository
import com.bilto.gochmott.repository.SearchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val direction: SearchDirection = SearchDirection.CE_TO_RU,
    val isLoading: Boolean = false,
    /** Точные совпадения — всегда идут первыми. */
    val exactResults: List<LemmaHit> = emptyList(),
    /** ЧЕ→РУ: похожие статьи отдельным блоком под точными. */
    val fuzzyResults: List<LemmaHit> = emptyList(),
    /** РУ→ЧЕ: похожие русские слова. Только когда точного слова в словаре нет. */
    val suggestions: List<String> = emptyList(),
    /**
     * ЧЕ→РУ: слово без своей статьи, встречающееся внутри переводов книг рус→чеч.
     * Показывается заголовком, открывается списком употреблений, а не карточкой.
     */
    val usage: UsageEntry? = null,
    val isFuzzyLoading: Boolean = false,
    val history: List<String> = emptyList(),
    val dbReady: Boolean = false,
    val dbError: String? = null,
    /** Что лежит в базе — подпись на пустом экране. null, пока не посчитано. */
    val stats: DictStats? = null
) {
    val hasExact: Boolean get() = exactResults.isNotEmpty()
    val hasNoResults: Boolean
        get() = exactResults.isEmpty() && fuzzyResults.isEmpty() &&
                suggestions.isEmpty() && usage == null && !isFuzzyLoading
}

sealed class SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent()
    data class SuggestionSelected(val word: String) : SearchIntent()
    object SwapDirection : SearchIntent()
    object ClearQuery : SearchIntent()

    object QuerySubmitted : SearchIntent()
    data class HistorySelected(val query: String) : SearchIntent()
    data class HistoryRemoved(val query: String) : SearchIntent()
    object ClearHistory : SearchIntent()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: DictRepository,
    private val dbHelper: DatabaseHelper,
    private val historyRepository: SearchHistoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var userFixedDirection: Boolean = false

    init {
        initDatabase()
        observeHistory()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHistory() {
        viewModelScope.launch {
            _state.map { it.direction }
                .distinctUntilChanged()
                .flatMapLatest { direction -> historyRepository.history(direction) }
                .collect { entries -> _state.update { it.copy(history = entries) } }
        }
    }

    private fun initDatabase() {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dbHelper.database // triggers lazy copy + open
                }
                _state.update { it.copy(dbReady = true) }
            } catch (e: Exception) {
                _state.update { it.copy(dbError = e.message ?: "Ошибка базы данных") }
                return@launch
            }
            // Подпись пустого экрана: цифры из базы, а не из ресурсов. Считаются
            // после dbReady, поэтому пару мгновений экран стоит без них — это
            // лучше, чем показать число, которое разойдётся с базой.
            try {
                val stats = repository.stats()
                _state.update { it.copy(stats = stats) }
            } catch (_: Exception) { }

            // индекс примерного поиска строится заметное время — греем его сразу, чтобы
            // первый же запрос отдал «похожие слова» без паузы. Сбой тут поиск не ломает.
            try {
                repository.warmUpFuzzyIndex()
            } catch (_: Exception) { }
        }
    }

    fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> onQueryChanged(intent.query)
            is SearchIntent.SuggestionSelected -> onSuggestionSelected(intent.word)
            is SearchIntent.SwapDirection -> onSwapDirection()
            is SearchIntent.ClearQuery -> onClearQuery()
            is SearchIntent.QuerySubmitted -> rememberQuery(_state.value.query)
            is SearchIntent.HistorySelected -> onHistorySelected(intent.query)
            is SearchIntent.HistoryRemoved -> viewModelScope.launch {
                historyRepository.remove(_state.value.direction, intent.query)
            }
            is SearchIntent.ClearHistory -> viewModelScope.launch {
                historyRepository.clear(_state.value.direction)
            }
        }
    }

    private fun rememberQuery(query: String) {
        if (query.isBlank()) return
        val direction = _state.value.direction
        viewModelScope.launch { historyRepository.record(direction, query) }
    }


    private fun onHistorySelected(query: String) {
        _state.update { it.copy(query = query, suggestions = emptyList()) }
        scheduleSearch(query, _state.value.direction)
        rememberQuery(query)
    }

    private fun onQueryChanged(query: String) {
         _state.update { it.copy(query = query) }
        scheduleSearch(query, _state.value.direction)
    }


    private fun onSuggestionSelected(word: String) {
        _state.update { it.copy(query = word, suggestions = emptyList()) }
        scheduleSearch(word, _state.value.direction)
        rememberQuery(word)
    }

    private fun onSwapDirection() {
        userFixedDirection = true
        val newDir = _state.value.direction.opposite()
        _state.update { it.copy(direction = newDir) }
        scheduleSearch(_state.value.query, newDir)
    }

    fun searchFor(query: String, direction: SearchDirection? = null) {
        userFixedDirection = false
        if (direction != null) _state.update { it.copy(direction = direction) }
        onQueryChanged(query)
    }

    private fun onClearQuery() {
        searchJob?.cancel()
        _state.update { it.copy(query = "").cleared() }
    }

    private fun SearchState.cleared() = copy(
        exactResults = emptyList(),
        fuzzyResults = emptyList(),
        suggestions = emptyList(),
        usage = null,
        isLoading = false,
        isFuzzyLoading = false
    )

    private fun scheduleSearch(query: String, direction: SearchDirection) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.cleared() }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250) // debounce
            if (!_state.value.dbReady) return@launch
            _state.update {
                it.copy(
                    isLoading = true, isFuzzyLoading = true,
                    fuzzyResults = emptyList(), suggestions = emptyList(), usage = null
                )
            }

            val exact = repository.enrichHits(
                when (direction) {
                    SearchDirection.CE_TO_RU -> repository.searchChechen(query)
                    SearchDirection.RU_TO_CE -> repository.searchRussian(query)
                }
            )
            ensureActive()
            _state.update { it.copy(isLoading = false, exactResults = exact) }

            when {
                direction == SearchDirection.CE_TO_RU -> {
                    // Своей статьи у слова может не быть, а внутри переводов книг
                    // рус→чеч оно стоять может. Спрашиваем только на промахе: когда
                    // статья нашлась, она и есть ответ, а употребления — шум.
                    if (exact.isEmpty()) {
                        val usage = repository.chechenUsages(query)
                        ensureActive()
                        _state.update { it.copy(usage = usage) }
                    }
                    val fuzzy = repository.enrichHits(
                        repository.searchChechenFuzzy(query, exact.mapTo(HashSet()) { it.id })
                    )
                    ensureActive()
                    _state.update { it.copy(fuzzyResults = fuzzy, isFuzzyLoading = false) }
                }

                exact.isEmpty() -> {
                    val words = repository.suggestRussianWords(query)
                    ensureActive()
                    _state.update { it.copy(suggestions = words, isFuzzyLoading = false) }
                }

                else -> _state.update { it.copy(isFuzzyLoading = false) }
            }
        }
    }

}
