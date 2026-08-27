package com.bilto.gochmott.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import com.bilto.gochmott.R
import com.bilto.gochmott.search.Diacritics

/**
 * Копирование слова или фразы в буфер обмена по долгому тапу.
 *
 * **В буфер не уходят ни чёрточки долготы, ни ударения** — независимо от того,
 * что включено в боковом меню. На экране они нужны: это чтение словаря. В буфере
 * мешают: скопированное вставляют в поиск, в переписку, в документ, а там
 * `ха̃дадала` и `ка́ждый раз` с комбинирующими знаками не совпадут ни с чем и
 * выглядят поломанными. Так что копируется чистое слово, всегда.
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val context = LocalContext.current
    val toast = stringResource(R.string.copied_to_clipboard)
    return remember(context, toast) { { text -> context.copyToClipboard(text, toast) } }
}

private fun Context.copyToClipboard(text: String, message: String) {
    val value = Diacritics.plain(text).trim()
    if (value.isEmpty()) return
    val manager = getSystemService<ClipboardManager>() ?: return
    manager.setPrimaryClip(ClipData.newPlainText(value, value))
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * Делает текст копируемым по долгому тапу.
 *
 * [onClick] — обычное нажатие, если у элемента оно уже было (карточка выдачи,
 * отсылка). Для просто текста его нет, и короткий тап ничего не делает.
 *
 * Через `combinedClickable`, а не `detectTapGestures`, ради доступности:
 * `onLongClickLabel` попадает в TalkBack отдельным действием, и до копирования
 * можно добраться без долгого удержания.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.copyOnLongPress(
    text: String,
    onClick: (() -> Unit)? = null
): Modifier {
    val copy = rememberCopyToClipboard()
    val label = stringResource(R.string.copy_action)
    return this.combinedClickable(
        onClick = { onClick?.invoke() },
        onLongClick = { copy(text) },
        onLongClickLabel = label
    )
}
