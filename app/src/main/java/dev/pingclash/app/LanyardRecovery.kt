// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

/**
 * Retry lost host messages twice, then report a fallback. The host shows its native card on a
 * fallback and may re-arm to keep offering frames; nothing here reloads the page.
 */
internal class LanyardRecovery {
    enum class Action {
        NONE,
        RETRY,
        FALLBACK,
    }

    private var deadline = 0L
    private var remaining = 0

    fun arm() {
        deadline = android.os.SystemClock.uptimeMillis() + ACK_TIMEOUT_MS
        remaining = 2
    }

    fun clear() {
        deadline = 0L
    }

    fun next(active: Boolean, ready: Boolean): Action {
        if (!active || ready || deadline == 0L) return Action.NONE
        val now = android.os.SystemClock.uptimeMillis()
        return when {
            now < deadline -> Action.NONE
            remaining == 0 -> Action.FALLBACK
            else -> {
                remaining--
                deadline = now + ACK_TIMEOUT_MS
                Action.RETRY
            }
        }
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 2000L
    }
}
