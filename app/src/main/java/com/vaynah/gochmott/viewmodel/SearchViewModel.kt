package com.vaynah.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaynah.gochmott.db.DatabaseHelper
import com.vaynah.gochmott.model.LemmaHit
import com.vaynah.gochmott.model.SearchDirection
import com.vaynah.gochmott.repository.DictRepository
import com.vaynah.gochmott.search.ChechenNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val direction: SearchDirection = SearchDirection.CE_TO_RU,
    val isLoading: Boolean = false,
    /** Точные совпадения — всегда идут первыми. */
    val exactResults: List<LemmaHit> = emptyList(),
    /** Примерные совпадения — отдельным блоком под точными. */
    val fuzzyResults: List<LemmaHit> = emptyList(),
    /** Примерный поиск ещё идёт: рано говорить «ничего не найдено». */
    val isFuzzyLoading: Boolean = false,
    val dbReady: Boolean = false,
    val dbError: String? = null
) {
    val hasExact: Boolean get() = exactResults.isNotEmpty()
    val hasNoResults: Boolean
        get() = exactResults.isEmpty() && fuzzyResults.isEmpty() && !isFuzzyLoading
}

sealed class SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent()
    object SwapDirection : SearchIntent()
    object ClearQuery : SearchIntent()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: DictRepository,
    private val dbHelper: DatabaseHelper
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var userFixedDirection: Boolean = false

    init {
        initDatabase()
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
            is SearchIntent.SwapDirection -> onSwapDirection()
            is SearchIntent.ClearQuery -> onClearQuery()
        }
    }

    private fun onQueryChanged(query: String) {
         _state.update { it.copy(query = query) }
        scheduleSearch(query, _state.value.direction)
    }

    private fun onSwapDirection() {
        userFixedDirection = true
        val newDir = if (_state.value.direction == SearchDirection.CE_TO_RU)
            SearchDirection.RU_TO_CE else SearchDirection.CE_TO_RU
        _state.update { it.copy(direction = newDir) }
        scheduleSearch(_state.value.query, newDir)
    }

    fun searchFor(query: String) {
        userFixedDirection = false
        onQueryChanged(query)
    }

    private fun onClearQuery() {
        searchJob?.cancel()
        _state.update { it.copy(query = "").cleared() }
    }

    private fun SearchState.cleared() = copy(
        exactResults = emptyList(),
        fuzzyResults = emptyList(),
        isLoading = false,
        isFuzzyLoading = false
    )

    /**
     * Точный и примерный поиск идут всегда — двумя шагами. Точные совпадения показываем
     * сразу, примерные догружаются следом (первый прогон ещё строит индекс скелетов) и
     * попадают в отдельный блок под точными.
     */
    private fun scheduleSearch(query: String, direction: SearchDirection) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.cleared() }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250) // debounce
            if (!_state.value.dbReady) return@launch
            _state.update { it.copy(isLoading = true, isFuzzyLoading = true, fuzzyResults = emptyList()) }

            val exact = repository.enrichHits(
                when (direction) {
                    SearchDirection.CE_TO_RU -> repository.searchChechen(query)
                    SearchDirection.RU_TO_CE -> repository.searchRussian(query)
                }
            )
            ensureActive()
            _state.update { it.copy(isLoading = false, exactResults = exact) }

            val fuzzy = repository.enrichHits(
                when (direction) {
                    SearchDirection.CE_TO_RU -> repository.searchChechenFuzzy(query, exact.mapTo(HashSet()) { it.id })
                    SearchDirection.RU_TO_CE -> repository.searchRussianFuzzy(query, exact.mapTo(HashSet()) { it.id })
                }
            )
            ensureActive()
            _state.update { it.copy(fuzzyResults = fuzzy, isFuzzyLoading = false) }
        }
    }

    private fun autoDetectDirection(query: String): SearchDirection {
        if (query.isBlank()) return SearchDirection.CE_TO_RU
        val normalized = ChechenNormalizer.normalize(query)
        // After normalization, Chechen-specific markers remain: палочка (Ӏ), digraphs аь/оь/уь
        if (normalized.contains('Ӏ')) return SearchDirection.CE_TO_RU
        if (normalized.contains("аь") || normalized.contains("оь") || normalized.contains("уь"))
            return SearchDirection.CE_TO_RU
        // If original had Latin chars (homoglyphs), normalizer converted them → still CE
        val hadLatin = query.any { it.code in 0x41..0x7A }
        if (hadLatin && normalized != query.lowercase()) return SearchDirection.CE_TO_RU
        // Pure Cyrillic without Chechen markers → Russian
        return if (query.all { it.isLetter() && it.code > 127 || it.isWhitespace() })
            SearchDirection.RU_TO_CE
        else
            SearchDirection.CE_TO_RU
    }
}
