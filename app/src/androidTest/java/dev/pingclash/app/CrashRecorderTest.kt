// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashRecorderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previousRunReportsBecomeTheLastCrashOnceAndCanBeDeleted() {
        val dir = CrashRecorder.folder(context)
        val backup = CrashRecorder.last(context)
        try {
            CrashRecorder.clear(context)
            File(dir, "app-live.txt")
                .writeText("Thread: main\njava.lang.IllegalStateException: fixture")
            File(dir, "core-live.txt").writeText("panic: fixture-core\n\ngoroutine 7 [running]:")
            CrashRecorder.collect(context, "fixture-version")
            val report = requireNotNull(CrashRecorder.last(context)).text
            assertTrue(report.startsWith("PingClash fixture-version"))
            assertTrue(report.contains("[App]") && report.contains("IllegalStateException"))
            assertTrue(report.contains("[Core]") && report.contains("panic: [message omitted]"))
            assertFalse(File(dir, "app-live.txt").exists() || File(dir, "core-live.txt").exists())
            assertEquals(CrashKind.CLOSED, CrashRecorder.takeUnseen(context))
            assertNull("the launch notice shows once", CrashRecorder.takeUnseen(context))
            // A clean run (nothing new) keeps the last report and raises no notice.
            CrashRecorder.collect(context, "fixture-version")
            assertEquals(report, CrashRecorder.last(context)?.text)
            assertNull(CrashRecorder.takeUnseen(context))
            // A run that only contained a core panic kept going: a gentler notice.
            File(dir, "core-recovered.txt").writeText("recovered panic in tcp: fixture\ncount 3")
            CrashRecorder.collect(context, "fixture-version")
            val contained = requireNotNull(CrashRecorder.last(context)).text
            assertTrue(contained.contains("[Core, contained]") && contained.contains("count 3"))
            assertFalse(File(dir, "core-recovered.txt").exists())
            assertEquals(CrashKind.CONTAINED, CrashRecorder.takeUnseen(context))
            CrashRecorder.clear(context)
            assertNull(CrashRecorder.last(context))
        } finally {
            CrashRecorder.clear(context)
            backup?.let { File(dir, "last.txt").writeText(it.text) }
        }
    }

    @Test
    fun crashMessagesAndLegacyReportsNeverExportCanaryCredentials() {
        val dir = CrashRecorder.folder(context)
        val backup = CrashRecorder.last(context)
        val secret = "CANARY-SUB-TOKEN-CRASH"
        try {
            val error =
                IllegalStateException(
                    "https://example.invalid/sub?token=$secret",
                    RuntimeException("password=$secret"),
                )
            val safe = CrashText.throwable(error)
            assertFalse(safe.contains(secret))
            assertTrue(safe.contains("IllegalStateException"))
            File(dir, "app-live.txt")
                .writeText(
                    "java.lang.IllegalStateException: $secret\n" +
                        "at dev.pingclash.app.CrashRecorderTest.test(CrashRecorderTest.kt:42)\n"
                )
            File(dir, "core-live.txt")
                .writeText(
                    "panic: $secret\ngithub.com/metacubex/example.Func(\"$secret\")\n" +
                        "private/path/example.go:123 +0xff\n"
                )
            CrashRecorder.collect(context, "fixture-version")
            val saved = File(dir, "last.txt").readText()
            assertFalse(saved.contains(secret))
            assertTrue(saved.contains("example.Func(...)"))
            assertTrue(saved.contains("example.go:123"))
            File(dir, "last.txt").appendText("\njava.lang.RuntimeException: $secret\n")
            val exported = requireNotNull(CrashRecorder.last(context)).text
            assertFalse(exported.contains(secret))
            assertEquals(exported, CrashRecorder.last(context)?.text)
            assertTrue(
                CrashText.sanitize("panic: buffer overflow: capacity 32768,start 0, need 2")
                    .contains("capacity 32768,start 0, need 2")
            )
            assertEquals("", CrashText.sanitize(""))
            // Shrunk release builds name most frames "Unknown Source:N"; they locate the fault.
            val shrunk =
                CrashText.sanitize(
                    "java.lang.IllegalArgumentException: CANARY-MESSAGE\n" +
                        "at dev.pingclash.app.BackgroundFrame.a(Unknown Source:83)\n" +
                        "at V.c.invoke(Unknown Source)\n" +
                        "at android.view.View.draw(View.java:26719)"
                )
            assertTrue(shrunk.contains("at dev.pingclash.app.BackgroundFrame.a(Unknown Source:83)"))
            assertTrue(shrunk.contains("at V.c.invoke(Unknown Source)"))
            assertFalse(shrunk.contains("CANARY-MESSAGE"))
        } finally {
            CrashRecorder.clear(context)
            backup?.let { File(dir, "last.txt").writeText(it.text) }
        }
    }
}
