// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/** This view only paints. Native Compose retains input, accessibility and VPN actions. */
@SuppressLint(
    "ClickableViewAccessibility"
) // Noninteractive renderer; the native button owns accessible actions.
internal class LanyardThreeView(context: Context) : WebView(context) {
    private var released = false

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    internal fun releaseRenderer(gone: Boolean = false) {
        if (released) return
        released = true
        (parent as? android.view.ViewGroup)?.removeView(this)
        if (!gone) stopLoading()
        destroy()
    }
}

@SuppressLint(
    "SetJavaScriptEnabled"
) // Pinned offline bundle, strict request allowlist, no Java/JS interface.
internal fun createLanyardView(context: Context, state: LanyardThreeState): LanyardThreeView {
    WebView.setWebContentsDebuggingEnabled(false)
    return LanyardThreeView(context).apply {
        tag = "lanyard-offline-webview"
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        importantForAccessibility =
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        isClickable = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        state.renderer = this
        webViewClient =
            OfflineLanyardClient(
                context.assets,
                { if (state.renderer === this) state.pageLoaded() },
                { if (state.renderer === this) state.failed() },
                { state.failed(it) },
            )
        loadUrl(LANYARD_PAGE)
    }
}

@Composable
internal fun rememberLanyardThreeState(): LanyardThreeState? {
    val allow = LocalAllowLanyardThree.current
    // Keep a dead renderer out of this Activity/session, including saved-state recreation.
    // This is not the user's reduce-effects preference and does not affect the VPN.
    var rendererLost by rememberSaveable { mutableStateOf(false) }
    val state =
        remember(allow, rendererLost) {
            if (allow && !rendererLost) LanyardThreeState { rendererLost = true } else null
        }
    // The host owns control/geometry/phase; releasing one AndroidView must not
    // clear a control that remains composed or a replacement view attached to it.
    DisposableEffect(state) { onDispose { state?.dispose() } }
    return state
}

@Composable
internal fun LanyardSceneLayers(
    state: LanyardThreeState?,
    cable: MutableState<Offset?>,
    selected: Int,
    secure: Boolean,
) {
    val foreground = rememberForeground()
    // Home must create its renderer independently of a control attachment effect.
    // After recreation the layout/control commit can precede this subscription.
    if (state != null && (selected == 0 || state.started))
        LanyardThreeLayer(state, selected == 0 && !secure && foreground, selected == 0 && !secure)
    if (selected == 0 && state == null) WindowLanyardCable(cable)
}

@Composable
internal fun LanyardThreeLayer(state: LanyardThreeState, active: Boolean, show: Boolean = active) {
    SideEffect { state.visible(active) }
    AndroidView(
        factory = { createLanyardView(it, state) },
        onRelease = {
            if (state.renderer === it) state.renderer = null
            it.releaseRenderer()
        },
        modifier =
            Modifier.fillMaxSize()
                .zIndex(if (state.dragging) 1f else 0f)
                .onSizeChanged { state.viewport(it.width, it.height) }
                .graphicsLayer { alpha = if (state.displayReady && show) 1f else 0f },
    )
}
