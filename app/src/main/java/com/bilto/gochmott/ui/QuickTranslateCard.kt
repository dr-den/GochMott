package com.bilto.gochmott.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilto.gochmott.R
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.SearchDirection
import com.bilto.gochmott.viewmodel.QuickTranslateState


@Composable
fun QuickTranslateCard(
    state: QuickTranslateState,
    onSwapDirection: () -> Unit,
    onEntryClick: (Long) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onOpenInApp: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                DirectionHeader(
                    direction = state.direction,
                    swapEnabled = state.word.isNotEmpty(),
                    onSwapDirection = onSwapDirection,
                    onDismiss = onDismiss
                )

                Column(modifier = Modifier.padding(end = 8.dp)) {
                    if (state.word.isNotEmpty()) {
                        Text(
                            text = state.word,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (state.hadMoreWords) {
                            Text(
                                text = stringResource(R.string.quick_first_word_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        QuickTranslateBody(
                            state = state,
                            onEntryClick = onEntryClick,
                            onSuggestionClick = onSuggestionClick
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(end = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                TextButton(
                    onClick = onOpenInApp,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.quick_open_in_app))
                }
            }
        }
    }
}

@Composable
private fun DirectionHeader(
    direction: SearchDirection,
    swapEnabled: Boolean,
    onSwapDirection: () -> Unit,
    onDismiss: () -> Unit
) {
    val source = if (direction == SearchDirection.CE_TO_RU)
        R.string.lang_chechen else R.string.lang_russian
    val target = if (direction == SearchDirection.CE_TO_RU)
        R.string.lang_russian else R.string.lang_chechen

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSwapDirection, enabled = swapEnabled) {
            Text(stringResource(source), style = MaterialTheme.typography.labelLarge)
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.swap_direction_content_description),
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Text(stringResource(target), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickTranslateBody(
    state: QuickTranslateState,
    onEntryClick: (Long) -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    when {
        state.noWord -> InfoText(stringResource(R.string.quick_no_word))

        state.error != null -> InfoText(
            text = stringResource(R.string.error_prefix, state.error),
            color = MaterialTheme.colorScheme.error
        )

        state.isLoading -> ProgressRow(stringResource(R.string.loading_dictionary))

        state.entries.isNotEmpty() -> {
            state.entries.forEach { hit ->
                QuickEntry(hit = hit, onClick = { onEntryClick(hit.id) })
            }
            if (state.hiddenEntries > 0) {
                Text(
                    text = stringResource(R.string.quick_more_entries, state.hiddenEntries),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.isSimilarLoading -> ProgressRow(stringResource(R.string.searching_similar_words))

        state.similarEntries.isNotEmpty() -> {
            NotFoundText(state.word)
            Spacer(Modifier.height(6.dp))
            state.similarEntries.forEach { hit ->
                QuickEntry(hit = hit, onClick = { onEntryClick(hit.id) })
            }
        }

        state.suggestions.isNotEmpty() -> {
            NotFoundText(state.word)
            Spacer(Modifier.height(8.dp))
            SuggestionsRow(words = state.suggestions, onWordClick = onSuggestionClick)
        }

        else -> InfoText(stringResource(R.string.no_results))
    }
}

@Composable
private fun QuickEntry(hit: LemmaHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Row {
            Text(
                modifier = Modifier.alignByBaseline(),
                text = hit.headword,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hit.homographN > 1) {
                Text(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .alignByBaseline(),
                    text = "${hit.homographN}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (hit.pos != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    modifier = Modifier.alignByBaseline(),
                    text = hit.pos,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        hit.firstSenses.forEachIndexed { i, gloss ->
            Text(
                text = buildAnnotatedString {
                    if (hit.firstSenses.size > 1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("${i + 1}. ") }
                    }
                    append(gloss)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsRow(words: List<String>, onWordClick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        words.forEach { word ->
            SuggestionChip(
                onClick = { onWordClick(word) },
                label = { Text(word) },
                shape = MaterialTheme.shapes.small
            )
        }
    }
}

@Composable
private fun InfoText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}
