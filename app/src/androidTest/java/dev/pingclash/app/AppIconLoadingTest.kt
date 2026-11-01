// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIconLoadingTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun packageReadsAndRasterizationRunOffMainWithBoundedImmutablePixels() {
        val context = compose.activity.applicationContext
        val probed =
            AppIconLoader(context) { _, edge ->
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                iconBitmap(edge)
            }
        runBlocking { assertNotNull(probed.load("fixture.app", 32)) }
        val actual = runBlocking { AppIconLoader(context).load(context.packageName, 2048) }
        assertNotNull(actual)
        assertEquals(192, actual!!.width)
        assertEquals(192, actual.height)
        assertFalse(actual.isMutable)
        val wide =
            Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val fitted = rasterizeRoutingIcon(BitmapDrawable(context.resources, wide), 32)
        assertEquals(Color.TRANSPARENT, fitted.getPixel(16, 0))
        assertEquals(Color.RED, fitted.getPixel(16, 16))
    }

    @Test
    fun cacheReusesEntriesEvictsOldOnesAndCachesMissingResources() = runBlocking {
        val reads = AtomicInteger()
        val loader =
            AppIconLoader(compose.activity.applicationContext, cacheEntries = 2) { pkg, edge ->
                reads.incrementAndGet()
                if (pkg == "missing") throw PackageManager.NameNotFoundException()
                require(pkg != "broken") { "Synthetic icon error" }
                iconBitmap(edge)
            }
        assertNull(loader.peek("one", 32))
        assertEquals(0, reads.get())
        val first = loader.load("one", 32)
        assertSame(first, loader.peek("one", 32))
        loader.load("two", 32)
        assertSame(first, loader.load("one", 32))
        assertEquals(2, reads.get())
        loader.load("three", 32)
        loader.load("two", 32)
        assertEquals(4, reads.get())
        assertNull(loader.load("missing", 32))
        assertNull(loader.load("missing", 32))
        assertEquals(5, reads.get())
        assertNull(loader.load("broken", 32))
        assertFalse(first!!.isRecycled)
    }

    @Test
    fun parallelScrollingNeverDecodesMoreThanFourIconsAtOnce() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val loader =
            AppIconLoader(compose.activity.applicationContext) { _, edge ->
                val running = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, running) }
                try {
                    Thread.sleep(30)
                    iconBitmap(edge)
                } finally {
                    active.decrementAndGet()
                }
            }
        (1..16)
            .map { index -> async(Dispatchers.Default) { loader.load("fixture.$index", 24) } }
            .awaitAll()
        assertTrue(peak.get() in 1..4)
        assertEquals(0, active.get())
    }

    @Test
    fun cancelledDecodeCannotCacheLatePixelsAndCancellationIsNotAFailedIcon() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val loader =
            AppIconLoader(compose.activity.applicationContext) { pkg, edge ->
                if (pkg == "cancel") throw CancellationException("Synthetic cancellation")
                reads.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                iconBitmap(edge)
            }
        try {
            val pending = async(Dispatchers.Default) { loader.load("late", 24) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            pending.cancel()
            release.countDown()
            pending.join()
            assertNotNull(loader.load("late", 24))
            assertEquals(2, reads.get())
            val failure = runCatching { loader.load("cancel", 24) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertNotNull(loader.load("after-cancel", 24))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun placeholderKeepsGeometryAndAReusedRowNeverShowsThePreviousIcon() {
        val packageName = mutableStateOf("fixture.first")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader =
            AppIconLoader(compose.activity.applicationContext) { pkg, edge ->
                if (pkg == "fixture.second") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                iconBitmap(edge, if (pkg == "fixture.first") Color.RED else Color.BLUE)
            }
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    PingClashTheme("light") {
                        Row {
                            RoutingAppIcon(packageName.value, loader)
                            Text("Synthetic app", Modifier.testTag("icon-label"))
                        }
                    }
                }
            }
            awaitImage("fixture.first")
            val label = compose.onNodeWithTag("icon-label").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { packageName.value = "fixture.second" }
            compose.waitForIdle()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            compose.onNodeWithTag("app-icon-placeholder-fixture.second").assertExists()
            compose.onNodeWithTag("app-icon-image-fixture.second").assertDoesNotExist()
            compose.onNodeWithTag("app-icon-image-fixture.first").assertDoesNotExist()
            assertEquals(
                label,
                compose.onNodeWithTag("icon-label").fetchSemanticsNode().boundsInRoot,
            )
            compose.runOnIdle { packageName.value = "fixture.third" }
            awaitImage("fixture.third")
            release.countDown()
            Thread.sleep(100)
            compose.onNodeWithTag("app-icon-image-fixture.third").assertExists()
            compose.onNodeWithTag("app-icon-image-fixture.second").assertDoesNotExist()
            assertEquals(
                label,
                compose.onNodeWithTag("icon-label").fetchSemanticsNode().boundsInRoot,
            )
        } finally {
            release.countDown()
        }
    }

    private fun awaitImage(packageName: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("app-icon-image-$packageName").fetchSemanticsNodes().size == 1
        }
    }
}

private fun iconBitmap(edge: Int, color: Int = Color.GREEN) =
    Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
