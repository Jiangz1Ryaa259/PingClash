// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.edit

@Composable
internal fun rememberProxyListLayout(): MutableState<Boolean> {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("proxy_ui", 0) }
    val layout = remember { mutableStateOf(preferences.getBoolean("single_column", false)) }
    LaunchedEffect(layout.value) { preferences.edit { putBoolean("single_column", layout.value) } }
    return layout
}

@Composable
internal fun ProxyLayoutOptions(singleColumn: Boolean, select: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(tr(R.string.proxy_layout_grid)) },
        onClick = { select(false) },
        leadingIcon = { RadioButton(selected = !singleColumn, onClick = null) },
        modifier = Modifier.testTag("proxy-layout-grid"),
    )
    DropdownMenuItem(
        text = { Text(tr(R.string.proxy_layout_list)) },
        onClick = { select(true) },
        leadingIcon = { RadioButton(selected = singleColumn, onClick = null) },
        modifier = Modifier.testTag("proxy-layout-list"),
    )
    HorizontalDivider()
}
