// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** The caller must mask/remove private pixels before releasing this lease. */
@Composable
internal fun VisiblePrivateContent(visible: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(window, visible) {
        val owner = Any()
        if (visible && window != null) SecurePage.acquire(window, owner)
        onDispose { if (visible && window != null) SecurePage.release(window, owner) }
    }
}
