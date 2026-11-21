// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Compose's test rule for every UI test here, with effects and recomposition kept on the main
 * thread as on a phone.
 *
 * By default the rule runs composition effects on an unconfined test dispatcher: a coroutine woken
 * by a worker thread (a StateFlow set by the VPN service, the end of a `withContext(IO)`) goes on
 * on that thread, and the rule then sends snapshot notifications and composes a frame there, racing
 * the main thread. With a standard test dispatcher the woken coroutine is only queued; the test
 * clock and [QueuedEffects] run the queue on the main thread. See [EffectThreadTest].
 */
@OptIn(ExperimentalTestApi::class)
internal inline fun <reified A : ComponentActivity> mainThreadComposeRule():
    AndroidComposeTestRule<ActivityScenarioRule<A>, A> {
    val dispatcher = StandardTestDispatcher()
    return createAndroidComposeRule<A>(dispatcher).also {
        it.registerIdlingResource(QueuedEffects(dispatcher.scheduler))
    }
}

/** [mainThreadComposeRule] for tests that start their own activity. */
@OptIn(ExperimentalTestApi::class)
internal fun mainThreadEmptyComposeRule(): ComposeTestRule {
    val dispatcher = StandardTestDispatcher()
    return createEmptyComposeRule(dispatcher).also {
        it.registerIdlingResource(QueuedEffects(dispatcher.scheduler))
    }
}

/**
 * Runs the coroutines that are due now on the main thread, as the main looper runs posted work on a
 * phone: whenever the test waits for the UI to settle, whenever the main looper goes idle, and on a
 * slow heartbeat that wakes it for tests that stop the clock and poll a value. The test clock alone
 * only runs the queue when it moves a frame, and a resumed effect does not ask for one. Delays
 * still wait for the test clock, so a test that stops the clock keeps control of time.
 */
internal class QueuedEffects(private val scheduler: TestCoroutineScheduler) : IdlingResource {
    init {
        // JUnit makes a rule per test, so the newest scheduler is the running test's.
        current = scheduler
        if (started.compareAndSet(false, true)) {
            Looper.getMainLooper().queue.addIdleHandler {
                current?.runCurrent()
                true
            }
            main.postDelayed(Heartbeat, HEARTBEAT_MILLIS)
        }
    }

    override val isIdleNow: Boolean
        get() {
            // Espresso asks on the main thread; anywhere else, leave the queue to the looper.
            if (Looper.myLooper() == Looper.getMainLooper()) scheduler.runCurrent()
            return true
        }

    /** Far enough apart that Espresso still sees the main looper idle between beats. */
    private object Heartbeat : Runnable {
        override fun run() {
            current?.runCurrent()
            main.postDelayed(this, HEARTBEAT_MILLIS)
        }
    }

    private companion object {
        const val HEARTBEAT_MILLIS = 50L
        val main = Handler(Looper.getMainLooper())
        val started = AtomicBoolean(false)
        @Volatile var current: TestCoroutineScheduler? = null
    }
}
