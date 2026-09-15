package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.settingsrepo.SettingKeys
import com.bilto.gochmott.settingsrepo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    /** Показывать ли вводный показ при запуске; null — настройка ещё не прочитана. */
    val showOnStart: StateFlow<Boolean?> = settings.get(SettingKeys.onboardingDone)
        .map { done -> !done }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish() {
        settings.set(SettingKeys.onboardingDone, true)
    }
}
