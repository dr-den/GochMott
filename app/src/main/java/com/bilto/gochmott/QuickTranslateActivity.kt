package com.bilto.gochmott

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilto.gochmott.ui.QuickTranslateCard
import com.bilto.gochmott.ui.theme.GochMottTheme
import com.bilto.gochmott.viewmodel.QuickTranslateViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Быстрый перевод выделенного текста: всплывает поверх чужого приложения, когда пользователь
 * выбирает «Перевести» в меню выделения (ACTION_PROCESS_TEXT) или отправляет текст в
 * приложение через «Поделиться» (ACTION_SEND).
 *
 * Прозрачное окно с одной карточкой — своего экрана у активити нет: он бы уводил из того
 * приложения, где читают текст. Для подробностей карточка открывает [MainActivity].
 */
@AndroidEntryPoint
class QuickTranslateActivity : ComponentActivity() {

    private val viewModel: QuickTranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.translateSelection(selectedText(intent))

        setContent {
            GochMottTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                QuickTranslateCard(
                    state = state,
                    onSwapDirection = viewModel::onSwapDirection,
                    onEntryClick = { lemmaId ->
                        openInApp(MainActivity.entryIntent(this, lemmaId))
                    },
                    onSuggestionClick = viewModel::onSuggestionSelected,
                    onOpenInApp = {
                        openInApp(MainActivity.searchIntent(this, state.word, state.direction))
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.translateSelection(selectedText(intent))
    }

    private fun selectedText(intent: Intent): String? = when (intent.action) {
        // Текст меню выделения приходит как CharSequence: у него может быть разметка.
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }

    /** Уходим в приложение и закрываем карточку: возвращаться к ней уже незачем. */
    private fun openInApp(intent: Intent) {
        startActivity(intent)
        finish()
    }
}
