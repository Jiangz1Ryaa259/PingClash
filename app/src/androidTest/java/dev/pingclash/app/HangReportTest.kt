// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** A test stuck past its limit fails with the stacks instead of hanging the class. */
@RunWith(AndroidJUnit4::class)
class HangReportTest {
    @Test
    fun aStuckTestFailsWithWhereItWaits() {
        val stuck =
            object : Statement() {
                override fun evaluate() = Thread.sleep(30_000)
            }
        val started = System.currentTimeMillis()
        try {
            HangReport(1000)
                .apply(stuck, Description.createTestDescription("Fixture", "stuck"))
                .evaluate()
            fail("A stuck test passed")
        } catch (report: AssertionError) {
            assertTrue(report.message.orEmpty().contains("stuck still running after 1 s"))
            assertTrue(report.message.orEmpty().contains("--- main thread"))
            assertTrue(report.message.orEmpty().contains("Thread.sleep"))
        }
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }
}
