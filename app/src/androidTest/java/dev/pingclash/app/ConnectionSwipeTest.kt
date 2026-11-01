// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Synthetic rows and deliberate gesture distances.

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionSwipeTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val row = ConnectionRow(42, "tcp", "DIRECT", "public.fixture", 1, 0, 0, "MATCH")

    @Test
    fun shortSwipeRevealsOnlyDeleteAndTapClosesOnceUsingLatestRow() {
        val current = mutableStateOf(row)
        val closed = mutableListOf<String>()
        fixture { ConnectionListItem(current.value) { closed.add(current.value.destination) } }
        compose.onNodeWithText(tr(R.string.close_connection)).assertDoesNotExist()
        shortSwipe()
        compose.runOnIdle {
            assertTrue(closed.isEmpty())
            current.value = row.copy(destination = "updated.fixture", seconds = 2)
        }
        compose.onNodeWithTag("connection-delete-42").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("updated.fixture"), closed) }
    }

    @Test
    fun fullLeftSwipeClosesOnceAndFoldsTheRow() {
        var closes = 0
        fixture { ConnectionListItem(row) { closes++ } }
        compose.onNodeWithTag("connection-42").performTouchInput {
            swipe(Offset(width * .98f, centerY), Offset(width * .02f, centerY), 600)
        }
        compose.runOnIdle { assertEquals(1, closes) }
        val node = compose.onNodeWithTag("connection-42").fetchSemanticsNode()
        assertEquals(0f, node.boundsInRoot.height)
        compose.runOnIdle {
            node.config[SemanticsActions.CustomActions].single().action()
            assertEquals(1, closes)
        }
    }

    @Test
    fun cancelledFullGestureAndRightSwipeNeverCloseConnections() {
        var closes = 0
        fixture { ConnectionListItem(row) { closes++ } }
        compose.onNodeWithTag("connection-42").performTouchInput {
            down(Offset(width * .98f, centerY))
            moveTo(Offset(width * .02f, centerY), 600)
            cancel()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("connection-42").performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .9f, centerY), 600)
        }
        compose.runOnIdle { assertEquals(0, closes) }
        shortSwipe()
        compose.onNodeWithTag("connection-delete-42").assertIsDisplayed()
    }

    @Test
    fun rightSwipeClosesAnOpenDrawerWithoutDeleting() {
        var closes = 0
        fixture { ConnectionListItem(row) { closes++ } }
        shortSwipe()
        compose.onNodeWithTag("connection-42").performTouchInput {
            swipe(Offset(width * .1f, centerY), Offset(width * .8f, centerY), 500)
        }
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("connection-delete-42").assertDoesNotExist()
    }

    @Test
    fun physicalLeftSwipeAlsoWorksInRtlAndReducedMotion() {
        var closes = 0
        fixture(motion = false, rtl = true) { ConnectionListItem(row) { closes++ } }
        shortSwipe()
        compose.onNodeWithTag("connection-delete-42").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun leavingDuringACommittedFoldStillClosesExactlyOnce() {
        val shown = mutableStateOf(true)
        var closes = 0
        fixture { if (shown.value) ConnectionListItem(row) { closes++ } }
        shortSwipe()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("connection-delete-42").performClick()
            compose.runOnIdle { shown.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(1, closes) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun verticalListScrollDoesNotExposeOrDeleteRows() {
        var closes = 0
        fixture {
            LazyColumn(Modifier.fillMaxWidth()) {
                items((1L..30L).toList(), key = { it }) { id ->
                    ConnectionListItem(row.copy(id = id)) { closes++ }
                }
            }
        }
        compose.onNodeWithTag("connection-3").performTouchInput {
            swipe(Offset(centerX, height * .9f), Offset(centerX, -height * 2f), 600)
        }
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("connection-delete-3").assertDoesNotExist()
    }

    private fun shortSwipe() {
        compose.onNodeWithTag("connection-42").performTouchInput {
            swipe(Offset(width * .95f, centerY), Offset(width * .65f, centerY), 500)
        }
        compose.waitForIdle()
    }

    private fun fixture(
        motion: Boolean = true,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(
                        LocalMotionAllowed provides motion,
                        LocalLayoutDirection provides
                            if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Column(Modifier.width(340.dp).padding(10.dp)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
