package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.settingsrepo.DisplayPrefs
import com.bilto.gochmott.settingsrepo.ThemeMode
import com.bilto.gochmott.settingsrepo.ThemePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Переключатели оформления для бокового меню.
 *
 * Знаки своего состояния не держат: текущие значения читаются прямо из
 * [com.bilto.gochmott.ui.Marks], а это Compose-состояние — меню перерисуется
 * само, вместе с открытой под ним статьёй. Тему применяют активити, меню её
 * только показывает и меняет.
 */
@HiltViewModel
class DisplayPrefsViewModel @Inject constructor(
    private val prefs: DisplayPrefs
) : ViewModel() {

    val theme: StateFlow<ThemePrefs?> =
        prefs.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setChechenLength(value: Boolean) = prefs.setChechenLength(value)

    fun setRussianStress(value: Boolean) = prefs.setRussianStress(value)

    fun setThemeMode(value: ThemeMode) = prefs.setThemeMode(value)

    fun setDynamicColor(value: Boolean) = prefs.setDynamicColor(value)
}
