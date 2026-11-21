// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app publishes state from worker threads (the VPN service, the repository) and its effects
 * wait on background dispatchers. On a phone, effects and composition still run on the main thread.
 * Compose's test rule resumes them on whichever thread woke them unless its dispatcher is confined,
 * and a frame composed on a worker thread raced the main one on CI ("pending composition has not
 * been applied", `CalledFromWrongThreadException`). [mainThreadComposeRule] keeps tests as the
 * phone runs.
 */
@RunWith(AndroidJUnit4::class)
class EffectThreadTest {
    @get:Rule val compose = mainThreadComposeRule<MainActivity>()

    private val main: Thread = Looper.getMainLooper().thread

    @Test
    fun anEffectResumedFromABackgroundDispatcherContinuesOnTheMainThread() {
        val threads = CopyOnWriteArrayList<String>()
        val done = AtomicBoolean(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { Thread.sleep(50) }
                    threads += Thread.currentThread().name
                    done.set(true)
                }
            }
        }
        compose.waitUntil(5000) { done.get() }
        assertEquals(listOf(main.name), threads)
    }

    @Test
    fun stateSetOnAWorkerThreadIsComposedOnTheMainThread() {
        val flow = MutableStateFlow(0)
        val composed = CopyOnWriteArrayList<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                val value by flow.collectAsState()
                Text("value $value")
                SideEffect { composed += Thread.currentThread().name }
            }
        }
        compose.onNodeWithText("value 0").assertExists()
        repeat(UPDATES) { i -> thread(name = "publisher-$i") { flow.value = i + 1 }.join() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("value $UPDATES").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(setOf(main.name), composed.toSet())
    }

    private companion object {
        const val UPDATES = 20
    }
}
