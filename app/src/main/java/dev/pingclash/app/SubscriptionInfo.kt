// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import org.json.JSONObject

internal const val DEFAULT_SUBSCRIPTION_UA = "Clash.Meta/PingClash-0.1.0"

internal fun validSubscriptionUa(value: String) =
    value.length in 1..256 && value.all { it.code in 32..126 }

data class SubscriptionQuota(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
) {
    val used: Long?
        get() =
            if (upload == null || download == null) null
            else upload + download.coerceAtMost(Long.MAX_VALUE - upload)

    val remaining: Long?
        get() =
            total?.takeIf { it > 0 }?.let { limit -> used?.let { (limit - it).coerceAtLeast(0) } }

    fun json() =
        JSONObject()
            .put("upload", upload)
            .put("download", download)
            .put("total", total)
            .put("expire", expire)

    companion object {
        fun parse(header: String?): SubscriptionQuota? {
            if (header == null || header.length > 4096) return null
            val fields =
                header.split(';').mapNotNull { part ->
                    val pair = part.trim().split('=', limit = 2)
                    if (
                        pair.size != 2 || pair[0] !in setOf("upload", "download", "total", "expire")
                    )
                        null
                    else
                        pair[0] to
                            pair[1]
                                .trim()
                                .takeIf { it.matches(Regex("[0-9]{1,19}")) }
                                ?.toLongOrNull()
                }
            if (fields.groupBy { it.first }.any { it.value.size > 1 }) return null
            val values = fields.toMap()
            return SubscriptionQuota(
                    values["upload"],
                    values["download"],
                    values["total"],
                    values["expire"]?.takeIf { it in 1..253402300799L },
                )
                .takeIf {
                    it.upload != null ||
                        it.download != null ||
                        it.total != null ||
                        it.expire != null
                }
        }

        fun read(value: JSONObject?): SubscriptionQuota? =
            value?.let {
                parse(
                    listOf("upload", "download", "total", "expire")
                        .filter { key -> !it.isNull(key) }
                        .joinToString(";") { key -> "$key=${it.opt(key)}" }
                )
            }
    }
}

data class SubscriptionResponse(
    val content: String,
    val quota: SubscriptionQuota?,
    val suggestedName: String? = null,
) {
    override fun toString() = "SubscriptionResponse(redacted)"
}
