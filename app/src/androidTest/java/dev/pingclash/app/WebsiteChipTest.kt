// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber") // Animation sampling and responsive fixture.

package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebsiteChipTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun collapsedTilesStayUnchangedAndExpandedFailuresCanRetryDuringABatch() {
        val called = mutableListOf<List<String>>()
        val state =
            DashboardState(
                AppData(),
                VpnUiState(),
                DiagnosticUiState(),
                ProxyTestState(),
                true,
                false,
                ServiceCheckState(
                    true,
                    mapOf(
                        "google" to Latency.Failed(WebsiteFailure.HTTP, 403),
                        "claude" to Latency.Running,
                    ),
                ),
            )
        fixture { HomeServiceChecks(state, { called.add(it) }) }
        compose.onNodeWithTag("service-target-google").assertIsDisplayed()
        compose.onNodeWithTag("service-detail-google").assertDoesNotExist()
        compose.onNodeWithTag("service-check-more").performClick()
        compose.onNodeWithTag("service-detail-google").assertIsDisplayed().performClick()
        assertEquals(listOf(listOf("google")), called)
        compose
            .onNode(
                hasText("HTTP 403") and hasAnyAncestor(hasTestTag("service-detail-google")),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun chipTransitionsFromRunningToMeasuredAndHttpFailureWithoutInventingSuccess() {
        val result = mutableStateOf<Latency?>(null)
        var retries = 0
        fixture { WebsiteCheckChip(serviceTargets.first(), result.value, true) { retries++ } }
        compose.runOnIdle { result.value = Latency.Running }
        compose.onNodeWithTag("service-detail-google").assertIsNotEnabled()
        compose.runOnIdle { result.value = Latency.Measured(84) }
        compose.onNodeWithText("84 ms").assertIsDisplayed()
        compose.runOnIdle { result.value = Latency.Failed(WebsiteFailure.HTTP, 451, 123) }
        compose.onNodeWithText("HTTP 451").assertIsDisplayed()
        compose.onNodeWithTag("service-detail-google").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun reducedMotionStillShowsFinalStatusAndErrorRetry() {
        var retries = 0
        fixture(false) {
            WebsiteCheckChip(
                serviceTargets.first(),
                Latency.Failed(WebsiteFailure.RESTRICTED),
                true,
            ) {
                retries++
            }
        }
        compose.onNodeWithText(tr(R.string.website_restricted)).assertIsDisplayed()
        compose.onNodeWithTag("service-detail-google").performClick()
        assertEquals(1, retries)
    }

    private fun fixture(motion: Boolean = true, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    CompositionLocalProvider(LocalMotionAllowed provides motion) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
