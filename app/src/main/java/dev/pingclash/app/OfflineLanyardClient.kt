// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

internal const val LANYARD_ORIGIN = "https://appassets.androidplatform.net"
internal const val LANYARD_PAGE = "$LANYARD_ORIGIN/lanyard/index.html"
private const val HTTP_OK = 200
private const val HTTP_FORBIDDEN = 403

/** Only these bundled files can be loaded; never opens files, content URIs or remote resources. */
internal class OfflineLanyardClient(
    private val assets: AssetManager,
    private val loaded: () -> Unit,
    private val failed: () -> Unit,
    private val gone: (WebView) -> Unit = {},
) : WebViewClient() {
    private var terminated = false
    private val paths =
        mapOf(
            "/lanyard/index.html" to "text/html",
            "/lanyard/renderer.js" to "text/javascript",
            "/lanyard/renderer.css" to "text/css",
        )

    internal fun permits(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == "appassets.androidplatform.net" &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            uri.encodedPath in paths

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        if (!permits(request.url)) return denied()
        val path = requireNotNull(request.url.encodedPath)
        return WebResourceResponse(
            paths[path],
            "UTF-8",
            HTTP_OK,
            "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            assets.open(path.removePrefix("/")),
        )
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (terminated) return
        if (url == LANYARD_PAGE) loaded() else failed()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: android.webkit.WebResourceError,
    ) {
        if (!terminated && request.isForMainFrame) failed()
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail,
    ): Boolean {
        terminated = true
        gone(view)
        failed()
        if (view is LanyardThreeView) view.releaseRenderer(gone = true)
        else {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        return true
    }

    private fun denied() =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            HTTP_FORBIDDEN,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
}
