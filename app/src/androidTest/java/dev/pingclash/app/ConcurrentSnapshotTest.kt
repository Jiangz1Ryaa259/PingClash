// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Cancelling proxy tests copies sets that finishing tests are still changing on other threads. */
@RunWith(AndroidJUnit4::class)
class ConcurrentSnapshotTest {
    @Test
    fun aSetChangingWhileItIsCopiedNeverThrows() {
        val runs = ConcurrentHashMap.newKeySet<Int>()
        val stop = AtomicBoolean(false)
        // One run starts and finishes over and over, so the set keeps moving between 0 and 1.
        val worker = thread {
            while (!stop.get()) {
                runs.add(1)
                runs.remove(1)
            }
        }
        var failure: Throwable? = null
        try {
            repeat(ROUNDS) { runCatching { runs.snapshot() }.onFailure { failure = it } }
            // The same race with Kotlin's toList(), logged to show the test can catch it.
            var plain = 0
            repeat(ROUNDS) { if (runCatching { runs.toList() }.isFailure) plain++ }
            android.util.Log.i("ConcurrentSnapshotTest", "toList() threw $plain of $ROUNDS times")
        } finally {
            stop.set(true)
            worker.join()
        }
        assertNull("a copy threw: $failure", failure)
    }

    /** A routing change asked of a VPN that is not running must not leave checks waiting. */
    @Test
    fun aRoutingChangeWithoutAVpnStillSettles() {
        val app =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext
        VpnRuntime.reconfigure(app)
        val started = System.nanoTime()
        kotlinx.coroutines.runBlocking { VpnRuntime.awaitRouting(10_000) }
        org.junit.Assert.assertTrue(VpnRuntime.routingSettled)
        org.junit.Assert.assertTrue(
            "waited for nothing",
            System.nanoTime() - started < 4_000_000_000L,
        )
    }

    private companion object {
        const val ROUNDS = 200_000
    }
}
