// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.os.Looper
import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Turns a hang into a failure that says where it waits: after [limitMillis] it records the test
 * thread's and the main thread's stacks, logs them, and interrupts the test so the failure carries
 * them instead of the whole class running into the runner's 15-minute limit.
 */
class HangReport(private val limitMillis: Long) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val test = Thread.currentThread()
                val report = java.util.concurrent.atomic.AtomicReference<String?>()
                val watchdog =
                    Thread(
                            {
                                try {
                                    Thread.sleep(limitMillis)
                                } catch (_: InterruptedException) {
                                    return@Thread
                                }
                                report.set(stacks(description, test).also { Log.e(TAG, it) })
                                test.interrupt()
                            },
                            "hang-report",
                        )
                        .apply {
                            isDaemon = true
                            start()
                        }
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    report.get()?.let { throw AssertionError(it, failure) }
                    throw failure
                } finally {
                    watchdog.interrupt()
                }
                report.get()?.let { throw AssertionError(it) }
            }
        }

    private fun stacks(description: Description, test: Thread): String = buildString {
        appendLine("${description.methodName} still running after ${limitMillis / 1000} s")
        listOf("test" to test, "main" to Looper.getMainLooper().thread).forEach { (name, thread) ->
            appendLine("--- $name thread (${thread.state})")
            thread.stackTrace.take(MAX_FRAMES).forEach { appendLine("    at $it") }
        }
    }

    private companion object {
        const val TAG = "HangReport"
        const val MAX_FRAMES = 40
    }
}
