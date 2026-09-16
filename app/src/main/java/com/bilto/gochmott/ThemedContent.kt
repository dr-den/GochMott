package com.bilto.gochmott

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.repository.DictSources
import com.bilto.gochmott.settingsrepo.DisplayPrefs
import com.bilto.gochmott.ui.BookCatalog
import com.bilto.gochmott.ui.LocalBookCatalog
import com.bilto.gochmott.ui.theme.GochMottTheme
import com.bilto.gochmott.ui.theme.isDark

/**
 * `setContent` с темой, которую выбрал пользователь.
 *
 * Пока настройка не прочитана, не рисуем ничего: иначе при тёмной теме в
 * светлой системе первый кадр мигнул бы светлым. Чтение — одна строка из
 * Room, ждать его незаметно.
 *
 * Значки строки состояния и навигации тоже следуют выбранной теме, а не
 * системной: `enableEdgeToEdge()` без параметров смотрит на систему и при
 * тёмной теме в светлой системе рисовал бы тёмные значки на тёмном фоне.
 *
 * Сюда же кладётся каталог книг ([LocalBookCatalog]): плашка словаря красится
 * по оценке книги и показывает её паспорт, а протаскивать это через каждую
 * модель выдачи незачем.
 */
fun ComponentActivity.setThemedContent(
    displayPrefs: DisplayPrefs,
    dictSources: DictSources,
    content: @Composable () -> Unit
) {
    enableEdgeToEdge()
    setContent {
        val prefs by displayPrefs.theme.collectAsStateWithLifecycle(initialValue = null)
        val books by dictSources.books.collectAsStateWithLifecycle()
        val catalog = remember(books) { BookCatalog(books.associateBy { it.book }) }
        prefs?.let { theme ->
            val dark = theme.isDark()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark }
                )
                onDispose {}
            }
            GochMottTheme(darkTheme = dark, dynamicColor = theme.dynamicColor) {
                CompositionLocalProvider(LocalBookCatalog provides catalog, content = content)
            }
        }
    }
}

// Те же подложки под кнопками навигации, что у enableEdgeToEdge() по умолчанию.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
