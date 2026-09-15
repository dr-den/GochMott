package com.bilto.gochmott.settingsrepo

import com.bilto.gochmott.ui.Marks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Выбранное оформление: тема и цвета из обоев. */
data class ThemePrefs(val mode: ThemeMode, val dynamicColor: Boolean)

/**
 * Настройки показа надстрочных знаков: связывает сохранённые значения с [Marks].
 *
 * Собирается при старте приложения (поле в `GochMottApp`), поэтому выбор
 * пользователя применяется до первой отрисовки, а не после открытия меню.
 * Сбор идёт на главном потоке: [Marks] — Compose-состояние, и запись из него
 * должна попадать в тот же кадр, что и перерисовка.
 *
 * Тему, в отличие от знаков, активити ждут перед первым кадром (см. [theme]):
 * мигнуть светлым экраном перед тёмным заметнее, чем чёрточкой над буквой.
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

    val theme: Flow<ThemePrefs> = combine(
        settings.get(SettingKeys.themeMode),
        settings.get(SettingKeys.dynamicColor)
    ) { mode, dynamic ->
        ThemePrefs(
            mode = ThemeMode.entries.firstOrNull { it.name == mode } ?: ThemeMode.SYSTEM,
            dynamicColor = dynamic
        )
    }

    fun setChechenLength(value: Boolean) {
        settings.set(SettingKeys.showCeLength, value)
    }

    fun setRussianStress(value: Boolean) {
        settings.set(SettingKeys.showRuStress, value)
    }

    fun setThemeMode(value: ThemeMode) {
        settings.set(SettingKeys.themeMode, value.name)
    }

    fun setDynamicColor(value: Boolean) {
        settings.set(SettingKeys.dynamicColor, value)
    }
}
