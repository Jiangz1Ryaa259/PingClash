// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The agreement text's version; raise it when the text changes so users read it again. */
internal const val AGREEMENT_VERSION = 3

internal const val SETUP_VIEW_GUIDE = "guide"
internal const val SETUP_VIEW_AGREEMENT = "agreement"
/** Settings > Setup guide: the guide again, from the step after the agreement. */
internal const val SETUP_VIEW_RERUN = "rerun"

/**
 * Whether the guide is on screen, for the whole process rather than one activity: a recreated
 * activity (a language change on Android 13+) reads the same value instead of restoring an old
 * copy, and Settings opens the guide in whichever activity is current. [run] numbers each opening,
 * so one run's saved step never carries into the next.
 */
internal object SetupScreen {
    private val state = MutableStateFlow<String?>(null)
    val view: StateFlow<String?> = state.asStateFlow()
    @Volatile
    var run = 0
        private set

    /** The first activity of the process decides from the stored progress; later ones keep it. */
    fun start(setup: SetupState, hasProfiles: Boolean): String {
        state.compareAndSet(null, setup.view(hasProfiles))
        return state.value.orEmpty()
    }

    fun show(view: String) {
        run++
        state.value = view
    }

    fun close() {
        state.value = ""
    }

    /** Forgets the decision, as a new process would; for tests that reset the stored progress. */
    fun reset() {
        state.value = null
    }
}

/** What the first-run flow still has to show. */
internal enum class SetupNeed {
    NONE,
    /** A new install: the whole guide, from the agreement. */
    GUIDE,
    /** A changed agreement, or an install from before the guide existed. */
    AGREEMENT,
}

/**
 * First-run progress, kept apart from the encrypted app data: a profile reset does not bring the
 * guide back, and backups never carry it, since the agreement is accepted per install.
 */
internal class SetupState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)

    val agreement
        get() = prefs.getInt(AGREEMENT, 0)

    val guideDone
        get() = prefs.getBoolean(GUIDE, false)

    /**
     * An install that already has profiles predates the guide: it reads the agreement only and then
     * hears about service routing once.
     */
    fun need(hasProfiles: Boolean): SetupNeed =
        when {
            agreement >= AGREEMENT_VERSION && guideDone -> SetupNeed.NONE
            agreement >= AGREEMENT_VERSION -> SetupNeed.GUIDE
            guideDone || hasProfiles -> SetupNeed.AGREEMENT
            else -> SetupNeed.GUIDE
        }

    /** The screen to open on: "" for the app, or one of the SETUP_VIEW_ values. */
    fun view(hasProfiles: Boolean): String =
        when (need(hasProfiles)) {
            SetupNeed.NONE -> ""
            SetupNeed.GUIDE -> SETUP_VIEW_GUIDE
            SetupNeed.AGREEMENT -> SETUP_VIEW_AGREEMENT
        }

    fun agree(upgrade: Boolean) {
        val editor = prefs.edit().putInt(AGREEMENT, AGREEMENT_VERSION)
        if (upgrade && !guideDone) editor.putBoolean(GUIDE, true).putBoolean(FEATURE, true)
        editor.apply()
    }

    /** The guide ended, finished or skipped; [skipped] queues the one-time hint. */
    fun finish(skipped: Boolean) {
        prefs.edit().putBoolean(GUIDE, true).putBoolean(HINT, skipped).apply()
    }

    /** Returns true once after a skipped guide. */
    fun takeHint(): Boolean = take(HINT)

    /** Returns true while the one-time "new: service routing" card should show. */
    val featurePending
        get() = prefs.getBoolean(FEATURE, false)

    fun dismissFeature() {
        prefs.edit().putBoolean(FEATURE, false).apply()
    }

    private fun take(key: String): Boolean {
        if (!prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, false).apply()
        return true
    }

    companion object {
        const val PREFS = "setup"
        private const val AGREEMENT = "agreement"
        private const val GUIDE = "guide_done"
        private const val HINT = "skip_hint"
        private const val FEATURE = "routing_card"

        /** Marks setup complete, as an instrumentation run does before the app starts. */
        fun complete(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS, 0)
                .edit()
                .putInt(AGREEMENT, AGREEMENT_VERSION)
                .putBoolean(GUIDE, true)
                .commit()
        }
    }
}
