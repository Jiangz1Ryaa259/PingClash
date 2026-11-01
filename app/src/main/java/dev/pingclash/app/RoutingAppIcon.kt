// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun RoutingAppIcon(packageName: String, loader: AppIconLoader) {
    val pixels = with(LocalDensity.current) { 36.dp.roundToPx() }
    Box(
        Modifier.padding(8.dp).size(36.dp).testTag("app-icon-$packageName"),
        contentAlignment = Alignment.Center,
    ) {
        // A new identity starts empty immediately, rather than showing the
        // previous app's bitmap until produceState's effect gets its next turn.
        key(loader, packageName, pixels) {
            val initial = remember { loader.peek(packageName, pixels) }
            val bitmap by
                produceState<Bitmap?>(initial) { value = loader.load(packageName, pixels) }
            val image = remember(bitmap) { bitmap?.asImageBitmap() }
            if (image != null)
                Image(
                    image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().testTag("app-icon-image-$packageName"),
                )
            else
                AppGlyph(
                    PcIcon.Apps,
                    Modifier.fillMaxSize().testTag("app-icon-placeholder-$packageName"),
                    Muted,
                )
        }
    }
}
