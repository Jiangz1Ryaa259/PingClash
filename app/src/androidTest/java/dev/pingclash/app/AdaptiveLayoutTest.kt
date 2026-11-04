// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theSidebarListsPagesAsRowsSwitchesByTapAndDragAndCollapsesToItsIcons() {
        val selected = mutableIntStateOf(0)
        var collapsed by mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides false) {
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize()) { drawRect(Color(0xFF79D6CE)) }
                            GlassSidebar(
                                selected.intValue,
                                if (collapsed) SIDEBAR_COLLAPSED else SIDEBAR_EXPANDED,
                                { collapsed = !collapsed },
                                Modifier.align(Alignment.CenterStart),
                            ) {
                                selected.intValue = it
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val rows = (0..3).map { compose.onNodeWithTag("nav-$it").fetchSemanticsNode().boundsInRoot }
        // Full-width rows, top to bottom.
        assertTrue(rows.zipWithNext().all { (a, b) -> b.top >= a.bottom - 1f })
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(rows[0].width > 200 * density)
        compose.onNodeWithTag("sidebar-status").assertExists()
        compose.onNodeWithTag("nav-2").performClick()
        compose.waitForIdle()
        assertEquals(2, selected.intValue)
        assertSelectorOn(2)
        // Dragging the lens down the rows switches the way a swipe along the bar does.
        val area = compose.onNodeWithTag("nav-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nav-0").performTouchInput {
            swipeDown(startY = center.y, endY = rows[3].center.y - area.top)
        }
        compose.waitForIdle()
        assertEquals(3, selected.intValue)
        assertSelectorOn(3)
        // Collapsing keeps the icons where they were and narrows the sidebar to them.
        val icon = compose.onNodeWithTag("nav-1").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("sidebar-toggle").performClick()
        compose.waitForIdle()
        assertTrue(collapsed)
        val narrow = compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(SIDEBAR_COLLAPSED.value * density, narrow.width, 2f)
        assertEquals(
            icon.top,
            compose.onNodeWithTag("nav-1").fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        assertSelectorOn(3)
    }

    @Test
    fun widePagesCentreAReadableColumnAndPhonesKeepTheirMargins() {
        assertEquals(20.dp, readableSidePadding(360.dp))
        assertEquals(20.dp, readableSidePadding(740.dp))
        assertEquals(140.dp, readableSidePadding(1000.dp))
    }

    private fun assertSelectorOn(index: Int) {
        val selector = compose.onNodeWithTag("glass-selector").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("nav-$index").fetchSemanticsNode().boundsInRoot
        assertEquals("lens is not on row $index", row.center.y, selector.center.y, 2f)
        assertEquals(row.center.x, selector.center.x, 2f)
    }
}
