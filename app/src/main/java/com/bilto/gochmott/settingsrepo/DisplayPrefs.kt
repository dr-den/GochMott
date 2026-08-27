package com.bilto.gochmott.settingsrepo

import com.bilto.gochmott.ui.Marks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки показа надстрочных знаков: связывает сохранённые значения с [Marks].
 *
 * Собирается при старте приложения (поле в `GochMottApp`), поэтому выбор
 * пользователя применяется до первой отрисовки, а не после открытия меню.
 * Сбор идёт на главном потоке: [Marks] — Compose-состояние, и запись из него
 * должна попадать в тот же кадр, что и перерисовка.
 */
@Singleton
class DisplayPrefs @Inject constructor(
    private val settings: SettingsRepository
) {
    init {
        settings.launch(Dispatchers.Main) {
            settings.get(SettingKeys.showCeLength).collect { Marks.showLength = it }
        }
        settings.launch(Dispatchers.Main) {
            settings.get(SettingKeys.showRuStress).collect { Marks.showStress = it }
        }
    }

    fun setChechenLength(value: Boolean) {
        settings.set(SettingKeys.showCeLength, value)
    }

    fun setRussianStress(value: Boolean) {
        settings.set(SettingKeys.showRuStress, value)
    }
}
