package com.bilto.gochmott.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bilto.gochmott.R
import com.bilto.gochmott.viewmodel.DisplayPrefsViewModel

@Composable
fun AppDrawerContent(
    onBookClick: () -> Unit,
    onAboutClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    prefs: DisplayPrefsViewModel = hiltViewModel()
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.ch_ru_dict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Знаки читаются прямо из Marks — это Compose-состояние, поэтому статья
        // под открытым меню перерисовывается сразу, без повторного запроса к БД.
        DrawerSectionLabel(stringResource(R.string.display_section))

        DrawerSwitch(
            title = stringResource(R.string.show_ce_length),
            subtitle = stringResource(R.string.show_ce_length_hint),
            checked = Marks.showLength,
            onCheckedChange = prefs::setChechenLength
        )
        DrawerSwitch(
            title = stringResource(R.string.show_ru_stress),
            subtitle = stringResource(R.string.show_ru_stress_hint),
            checked = Marks.showStress,
            onCheckedChange = prefs::setRussianStress
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // «О словаре» стоит выше «О приложении»: это содержимое книги, ради
        // которой приложение и существует, а не сведения о самой программе.
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.book_title)) },
            icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
            selected = false,
            onClick = onBookClick,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        NavigationDrawerItem(
            label = { Text(stringResource(R.string.about_app)) },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            selected = false,
            onClick = onAboutClick,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        NavigationDrawerItem(
            label = { Text(stringResource(R.string.privacy_policy)) },
            icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
            selected = false,
            onClick = onPrivacyPolicyClick,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
    )
}

/**
 * Строка-переключатель. Подпись показывает пример: словами объяснять, что такое
 * «чёрточка долготы», дольше, чем показать `ха̃дадала` рядом с `хададала`.
 */
@Composable
private fun DrawerSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
