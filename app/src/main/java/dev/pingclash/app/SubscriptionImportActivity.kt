// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An external link can request confirmation, never activate a profile or start VPN. */
class SubscriptionImportActivity : ComponentActivity() {
    private val model: SubscriptionImportModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent, savedInstanceState == null)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val data by
                (application as PingClashApplication).repository.data.collectAsStateWithLifecycle()
            PingClashTheme(data.client.effectiveTheme, data.client.dynamicColor) {
                AlertDialog(
                    onDismissRequest = { if (state.phase != "importing") finish() },
                    title = { Text(tr(R.string.deep_link_title)) },
                    text = {
                        Text(
                            when (state.phase) {
                                "confirm" ->
                                    tr(R.string.deep_link_confirm, state.request?.host.orEmpty())
                                "importing" -> tr(R.string.deep_link_importing)
                                "saved" -> tr(R.string.deep_link_saved)
                                else -> state.failure ?: tr(R.string.deep_link_failed)
                            }
                        )
                    },
                    confirmButton = {
                        if (state.phase == "confirm") {
                            TextButton(model::confirm, Modifier.testTag("deep-link-confirm")) {
                                Text(tr(R.string.deep_link_import))
                            }
                        } else if (state.phase != "importing") {
                            TextButton({ finish() }, Modifier.testTag("deep-link-close")) {
                                Text(tr(R.string.deep_link_close))
                            }
                        }
                    },
                    dismissButton = {
                        if (state.phase == "confirm")
                            TextButton({ finish() }, Modifier.testTag("deep-link-cancel")) {
                                Text(tr(R.string.deep_link_cancel))
                            }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Do not replace a URL while a user is reviewing its host or importing it.
        consume(intent, false)
    }

    private fun consume(incoming: Intent, accept: Boolean) {
        val raw = if (incoming.action == Intent.ACTION_VIEW && accept) incoming.dataString else null
        incoming.data = null
        incoming.replaceExtras(null as Bundle?)
        if (accept) model.offer(raw)
    }
}

internal data class SubscriptionImportState(
    val phase: String = "failed",
    val request: SubscriptionDeepLink? = null,
    val failure: String? = null,
)

internal class SubscriptionImportModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SubscriptionImportState())
    val state = mutableState.asStateFlow()

    fun offer(raw: String?) {
        val request = SubscriptionDeepLink.parse(raw)
        mutableState.value =
            SubscriptionImportState(if (request == null) "failed" else "confirm", request)
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Untrusted download/parse errors must cross a fixed public vocabulary.
    fun confirm() {
        val current = mutableState.value
        if (current.phase != "confirm") return
        val request = current.request ?: return
        mutableState.value = SubscriptionImportState("importing")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<PingClashApplication>()
                check(app.nativeReady && !app.repository.data.value.loadFailed)
                val response = SubscriptionDownloader.fetchResponse(request.source)
                val prepared =
                    RemoteProfiles.loadForImport(response.content, DEFAULT_SUBSCRIPTION_UA)
                ensureActive()
                app.repository.importProfile(
                    SubscriptionNames.choose(
                        request.suggestedName,
                        response.suggestedName,
                        request.name,
                    ),
                    prepared.content,
                    request.source,
                    userAgent = DEFAULT_SUBSCRIPTION_UA,
                    subscriptionResponse = response,
                    remote = prepared.remote,
                    requireActivation = true,
                )
                mutableState.value = SubscriptionImportState("saved")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Never stringify an exception carrying the download URL or response body.
                mutableState.value =
                    SubscriptionImportState("failed", failure = safeImportFailure(failure))
            }
        }
    }
}

internal fun safeImportFailure(failure: Exception): String =
    when (failure) {
        is ProviderFailure -> failure.publicMessage()
        is SubscriptionTransferFailure -> failure.publicMessage()
        is ProfileImportFailure -> failure.publicMessage
        is SubscriptionHttpFailure ->
            tr(R.string.subscription_server_returned_http_check_th, failure.status)
        is java.net.UnknownHostException -> tr(R.string.the_subscription_hostname_could_not_resolv)
        is java.net.SocketTimeoutException ->
            tr(R.string.subscription_download_timed_out_try_anothe)
        is javax.net.ssl.SSLException -> tr(R.string.subscription_tls_handshake_failed_check_th)
        else -> tr(R.string.deep_link_failed)
    }
