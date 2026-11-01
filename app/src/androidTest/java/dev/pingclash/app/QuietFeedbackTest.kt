// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuietFeedbackTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun heldControlsHaveNoDarkWashWhileClicksAndLongPressStillWork() {
        var latency = 0
        var website = 0
        var select = 0
        var details = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides false) {
                        Column(
                            Modifier.fillMaxSize().background(Color.White).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ProxyMemberLatency("Fixture", ProxyDelay("passed", 42)) { latency++ }
                            ProxyMemberCard(
                                ProxyMemberPresentation(
                                    "Card",
                                    "AnyTLS",
                                    false,
                                    true,
                                    ProxyDelay("passed", 42),
                                    false,
                                ),
                                ProxyMemberActions({ select++ }, { details++ }, null, null),
                                Modifier.testTag("quiet-card"),
                            )
                            HomeServiceChecks(
                                DashboardState(
                                    AppData(),
                                    VpnUiState(),
                                    DiagnosticUiState(),
                                    ProxyTestState(),
                                    true,
                                    false,
                                ),
                                { website++ },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            assertNoPressedWash("proxy-latency-Fixture")
            // Proxy cards and website tiles sink under the finger by design (no wash).
            for (tag in listOf("quiet-card", "service-target-google")) assertFluidPress(tag)
            assertEquals(0, latency + website + select + details)
            compose.onNodeWithTag("proxy-latency-Fixture").performClick()
            compose.onNodeWithTag("service-target-google").performClick()
            compose.onNodeWithTag("quiet-card").performClick()
            compose.onNodeWithTag("quiet-card").performTouchInput { longClick() }
            assertEquals(1, latency)
            assertEquals(1, website)
            assertEquals(1, select)
            assertEquals(1, details)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun lightAndDarkThemesRemoveInheritedFoundationMaterialAndPopupWash() {
        val mode = mutableStateOf("light")
        val menu = mutableStateOf(false)
        val dialog = mutableStateOf(false)
        val calls = IntArray(4)
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme(mode.value) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(20.dp)
                    ) {
                        TextButton({ calls[0]++ }, Modifier.testTag("quiet-m3")) {
                            Text("Fixture button")
                        }
                        Box(
                            Modifier.testTag("quiet-foundation")
                                .clickable { calls[1]++ }
                                .padding(20.dp)
                        ) {
                            Text("Fixture row")
                        }
                        TextButton({ menu.value = true }, Modifier.testTag("quiet-open-menu")) {
                            Text("Fixture menu")
                        }
                        TextButton({ dialog.value = true }, Modifier.testTag("quiet-open-dialog")) {
                            Text("Fixture dialog")
                        }
                        DropdownMenu(menu.value, { menu.value = false }) {
                            DropdownMenuItem(
                                { Text("Fixture item") },
                                {
                                    calls[2]++
                                    menu.value = false
                                },
                                modifier = Modifier.testTag("quiet-menu-item"),
                            )
                        }
                    }
                    if (dialog.value)
                        AlertDialog(
                            onDismissRequest = { dialog.value = false },
                            title = { Text("Fixture dialog") },
                            confirmButton = {
                                TextButton(
                                    {
                                        calls[3]++
                                        dialog.value = false
                                    },
                                    Modifier.testTag("quiet-dialog-confirm"),
                                ) {
                                    Text("Fixture confirm")
                                }
                            },
                        )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            for (theme in listOf("light", "dark")) {
                compose.runOnUiThread { mode.value = theme }
                compose.mainClock.advanceTimeBy(500)
                for (tag in listOf("quiet-m3", "quiet-foundation")) {
                    assertNoPressedWash(tag)
                    compose.onNodeWithTag(tag).performClick()
                }
                compose.onNodeWithTag("quiet-open-menu").performClick()
                compose.mainClock.advanceTimeBy(500)
                assertNoPressedWash("quiet-menu-item")
                compose.onNodeWithTag("quiet-menu-item").performClick()
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithTag("quiet-open-dialog").performClick()
                compose.mainClock.advanceTimeBy(500)
                assertNoPressedWash("quiet-dialog-confirm")
                compose.onNodeWithTag("quiet-dialog-confirm").performClick()
                compose.mainClock.advanceTimeBy(500)
            }
            assertTrue(calls.all { it == 2 })
            val highlight = android.util.TypedValue()
            assertTrue(
                compose.activity.theme.resolveAttribute(
                    android.R.attr.colorControlHighlight,
                    highlight,
                    true,
                )
            )
            assertEquals(android.graphics.Color.TRANSPARENT, highlight.data)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    // A pressed control may shrink a little but must not darken, and returns exactly.
    private fun assertFluidPress(tag: String) {
        val node = compose.onNodeWithTag(tag)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val before = node.captureToImage()
        node.performTouchInput { down(center) }
        try {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            val pressed = node.captureToImage()
            assertTrue("$tag does not respond to a press", pressed.width < before.width)
        } finally {
            node.performTouchInput { cancel() }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue(
            "$tag leaves a cancelled-press residue",
            difference(before, node.captureToImage()) < .0035,
        )
    }

    private fun assertNoPressedWash(tag: String) {
        val node = compose.onNodeWithTag(tag)
        // A popup starts its entry animation on its first attached/drawn frame.
        // Compare a settled surface, not the initial 0.8-scale menu against its final size.
        assertTrue(node.captureToImage().width > 0)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val before = node.captureToImage()
        node.performTouchInput { down(center) }
        try {
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
            val change = difference(before, node.captureToImage())
            assertTrue("$tag adds a pressed rectangle: $change", change < .0035)
        } finally {
            node.performTouchInput { cancel() }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertTrue(
            "$tag leaves a cancelled-press residue",
            difference(before, node.captureToImage()) < .0035,
        )
    }

    private fun difference(a: ImageBitmap, b: ImageBitmap): Double {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val left = a.toPixelMap()
        val right = b.toPixelMap()
        var sum = 0.0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = left[x, y]
            val q = right[x, y]
            sum += abs(p.red - q.red) + abs(p.green - q.green) + abs(p.blue - q.blue)
        }
        return sum / (a.width * a.height * 3)
    }
}
