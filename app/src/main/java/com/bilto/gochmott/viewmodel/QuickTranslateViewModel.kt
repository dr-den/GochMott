package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.SearchDirection
import com.bilto.gochmott.repository.DictRepository
import com.bilto.gochmott.search.LanguageGuess
import com.bilto.gochmott.search.TextSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickTranslateState(
    val word: String = "",
    val hadMoreWords: Boolean = false,
    val direction: SearchDirection = SearchDirection.CE_TO_RU,
    val isLoading: Boolean = true,
    val entries: List<LemmaHit> = emptyList(),
    val hiddenEntries: Int = 0,
    /** ЧЕ→РУ: похожие статьи, когда точного совпадения нет. */
    val similarEntries: List<LemmaHit> = emptyList(),
    /** РУ→ЧЕ: похожие русские слова, когда точного совпадения нет. */
    val suggestions: List<String> = emptyList(),
    val isSimilarLoading: Boolean = false,
    val noWord: Boolean = false,
    val error: String? = null
)


@HiltViewModel
class QuickTranslateViewModel @Inject constructor(
    private val repository: DictRepository
) : ViewModel() {

    private companion object {
        const val CARD_ENTRIES = 3
        const val CARD_SIMILAR = 5
        const val CARD_SUGGESTIONS = 6
    }

    private val _state = MutableStateFlow(QuickTranslateState())
    val state: StateFlow<QuickTranslateState> = _state.asStateFlow()

    private var searchJob: Job? = null


    fun translateSelection(raw: String?) {
        val parsed = TextSelection.parse(raw)
        if (parsed == null) {
            _state.update { it.copy(isLoading = false, noWord = true) }
            return
        }
        _state.update { it.copy(hadMoreWords = parsed.hadMoreWords) }
        search(parsed.word, direction = null)
    }

    fun onSwapDirection() {
        val current = _state.value
        if (current.word.isEmpty()) return
        search(current.word, current.direction.opposite())
    }


    fun onSuggestionSelected(word: String) {

        _state.update { it.copy(hadMoreWords = false) }
        search(word, _state.value.direction)
    }

    private fun search(word: String, direction: SearchDirection?) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    word = word,
                    isLoading = true,
                    noWord = false,
                    error = null,
                    entries = emptyList(),
                    hiddenEntries = 0,
                    similarEntries = emptyList(),
                    suggestions = emptyList(),
                    isSimilarLoading = false
                )
            }
            try {
                val (resolved, hits) = resolveDirection(word, direction)
                val shown = repository.enrichHits(hits.take(CARD_ENTRIES))
                ensureActive()
                _state.update {
                    it.copy(
                        direction = resolved,
                        isLoading = false,
                        entries = shown,
                        hiddenEntries = hits.size - shown.size,
                        isSimilarLoading = hits.isEmpty()
                    )
                }
                if (hits.isEmpty()) loadSimilar(word, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isSimilarLoading = false,
                        error = e.message.orEmpty()
                    )
                }
            }
        }
    }


    private suspend fun resolveDirection(
        word: String,
        fixed: SearchDirection?
    ): Pair<SearchDirection, List<LemmaHit>> {
        if (fixed != null) return fixed to exactHits(word, fixed)

        val guess = LanguageGuess.directionFor(word)
        val primary = exactHits(word, guess)
        if (primary.isNotEmpty()) return guess to primary

        val fallback = guess.opposite()
        val secondary = exactHits(word, fallback)
        return if (secondary.isNotEmpty()) fallback to secondary else guess to emptyList()
    }

    private suspend fun exactHits(word: String, direction: SearchDirection): List<LemmaHit> =
        when (direction) {
            SearchDirection.CE_TO_RU -> repository.searchChechen(word)
            SearchDirection.RU_TO_CE -> repository.searchRussian(word)
        }

    private suspend fun loadSimilar(word: String, direction: SearchDirection) {
        when (direction) {
            SearchDirection.CE_TO_RU -> {
                val similar = repository.enrichHits(
                    repository.searchChechenFuzzy(word).take(CARD_SIMILAR)
                )
                _state.update { it.copy(similarEntries = similar, isSimilarLoading = false) }
            }

            SearchDirection.RU_TO_CE -> {
                val words = repository.suggestRussianWords(word, CARD_SUGGESTIONS)
                _state.update { it.copy(suggestions = words, isSimilarLoading = false) }
            }
        }
    }
}
