package com.bilto.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import com.bilto.gochmott.settingsrepo.DisplayPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Переключатели показа надстрочных знаков для бокового меню.
 *
 * Своего состояния не держит: текущие значения читаются прямо из
 * [com.bilto.gochmott.ui.Marks], а это Compose-состояние — меню перерисуется
 * само, вместе с открытой под ним статьёй. ViewModel нужна только чтобы достать
 * [DisplayPrefs] и сохранить выбор.
 */
@HiltViewModel
class DisplayPrefsViewModel @Inject constructor(
    private val prefs: DisplayPrefs
) : ViewModel() {

    fun setChechenLength(value: Boolean) = prefs.setChechenLength(value)

    fun setRussianStress(value: Boolean) = prefs.setRussianStress(value)
}
