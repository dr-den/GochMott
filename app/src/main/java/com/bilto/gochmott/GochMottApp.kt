package com.bilto.gochmott

import android.app.Application
import com.bilto.gochmott.settingsrepo.DisplayPrefs
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GochMottApp : Application() {

    /**
     * Внедряется только ради создания: в конструкторе [DisplayPrefs] подписывается
     * на сохранённые настройки показа знаков и применяет их к `Marks`. Без этого
     * поля Hilt создал бы объект лишь при первом открытии бокового меню, и до
     * этого статьи рисовались бы со значениями по умолчанию.
     */
    @Inject lateinit var displayPrefs: DisplayPrefs
}
