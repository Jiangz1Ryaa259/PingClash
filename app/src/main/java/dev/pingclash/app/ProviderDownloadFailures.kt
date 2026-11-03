// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

internal val providerCodes =
    setOf(
        "provider_invalid",
        "provider_count_limit",
        "provider_document_syntax",
        "provider_document_limit",
        "provider_dns_document",
        "provider_group_keys",
        "provider_group_filter",
        "provider_group_use",
        "provider_group_reference",
        "provider_rule_reference",
        "provider_rule_entry",
        "provider_bridge_invalid",
        "provider_internal",
        "provider_payload",
        "provider_network",
        "provider_dns",
        "provider_timeout",
        "provider_tls",
        "provider_connect",
        "provider_redirect",
        "provider_size_limit",
        "provider_total_limit",
        "provider_config_limit",
        "provider_rule_limit",
        "provider_rule_document",
        "provider_rule_syntax",
        "provider_rule_type",
        "provider_html",
        "provider_declared_size_limit",
        "provider_cache_limit",
        "provider_proxy_unavailable",
        "provider_local_file_required",
    )

internal fun safeProviderCode(code: String) =
    code.takeIf { it in providerCodes || it.matches(Regex("provider_http_[1-5][0-9]{2}")) }
        ?: "provider_payload"

internal class ProviderFailure(index: Int, kind: String, code: String) :
    Exception("provider_failed") {
    val index = index.coerceIn(0, MAX_PROVIDER_COUNT)
    val kind = kind.takeIf { it in setOf("proxy", "rule") }.orEmpty()
    val code = safeProviderCode(code)

    fun publicMessage() =
        if (index > 0 && kind.isNotBlank())
            tr(R.string.provider_failure, index, kind, downloadFailureText(code))
        else tr(R.string.provider_configuration_failure, downloadFailureText(code))
}

internal class SubscriptionTransferFailure(val code: String) :
    java.io.IOException("subscription_transfer_failed") {
    fun publicMessage() = downloadFailureText(safeProviderCode(code))
}

internal fun downloadFailureText(code: String): String {
    val status = code.removePrefix("provider_http_").toIntOrNull()
    if (code.startsWith("provider_http_") && status in MIN_HTTP_STATUS..MAX_HTTP_STATUS)
        return tr(R.string.provider_http, status)
    return tr(providerFailureResources[code] ?: R.string.provider_network)
}

private val providerFailureResources =
    mapOf(
        "provider_dns" to R.string.provider_dns,
        "provider_timeout" to R.string.provider_timeout,
        "provider_tls" to R.string.provider_tls,
        "provider_connect" to R.string.provider_connect,
        "provider_redirect" to R.string.provider_redirect,
        "provider_invalid" to R.string.provider_invalid,
        "provider_count_limit" to R.string.provider_count_limit,
        "provider_document_syntax" to R.string.provider_document_syntax,
        "provider_document_limit" to R.string.provider_document_limit,
        "provider_dns_document" to R.string.provider_dns_document,
        "provider_group_keys" to R.string.provider_group_keys,
        "provider_group_filter" to R.string.provider_group_filter,
        "provider_group_use" to R.string.provider_group_use,
        "provider_group_reference" to R.string.provider_group_reference,
        "provider_rule_reference" to R.string.provider_rule_reference,
        "provider_rule_entry" to R.string.provider_rule_entry,
        "provider_bridge_invalid" to R.string.provider_bridge_invalid,
        "provider_internal" to R.string.provider_internal,
        "provider_payload" to R.string.provider_payload,
        "provider_size_limit" to R.string.provider_size_limit,
        "provider_total_limit" to R.string.provider_total_limit,
        "provider_config_limit" to R.string.provider_config_limit,
        "provider_rule_limit" to R.string.provider_rule_limit,
        "provider_rule_document" to R.string.provider_rule_document,
        "provider_rule_syntax" to R.string.provider_rule_syntax,
        "provider_rule_type" to R.string.provider_rule_type,
        "provider_html" to R.string.provider_html,
        "provider_declared_size_limit" to R.string.provider_declared_size_limit,
        "provider_cache_limit" to R.string.provider_cache_limit,
        "provider_proxy_unavailable" to R.string.provider_proxy_unavailable,
        "provider_local_file_required" to R.string.provider_local_file_required,
    )

internal fun providerDownloadFailure(
    index: Int,
    kind: String,
    failure: Exception,
): ProviderFailure {
    if (failure is kotlinx.coroutines.CancellationException) throw failure
    val chain = generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val code =
        when {
            failure is SubscriptionTransferFailure -> failure.code
            failure is SubscriptionHttpFailure -> "provider_http_${failure.status}"
            chain.any { it is java.net.UnknownHostException } -> "provider_dns"
            chain.any { it is java.net.SocketTimeoutException } -> "provider_timeout"
            chain.any {
                it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException
            } -> "provider_tls"
            chain.any { it is java.net.SocketException } -> "provider_connect"
            else -> "provider_network"
        }
    return ProviderFailure(index, kind, code)
}

private const val MAX_CAUSE_DEPTH = 8

internal fun providerErrorRecord(value: String): String {
    val parts = value.split(':')
    val index = parts.getOrNull(0)?.toIntOrNull()
    if (parts.size != ERROR_RECORD_PARTS) return ""
    return if (
        index in 0..MAX_PROVIDER_COUNT &&
            parts[1] in setOf("proxy", "rule") &&
            safeProviderCode(parts[2]) == parts[2]
    )
        value
    else ""
}

private const val MAX_PROVIDER_COUNT = MAX_REMOTE_PROVIDERS
private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599
private const val ERROR_RECORD_PARTS = 3
