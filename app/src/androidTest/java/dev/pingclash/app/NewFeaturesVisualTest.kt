// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pingclash.diagnostics.Models
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draws the 0.2.5 additions in both themes and saves screenshots to the app's external files
 * (`visual-*.png`) for a look on Android 13+; the assertions only check that each part is shown.
 */
@RunWith(AndroidJUnit4::class)
class NewFeaturesVisualTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = mainThreadComposeRule<MainActivity>()

    private val day = 24 * 60 * 60L
    private val gib = 1L shl 30

    @Test
    fun additionsInLightAndDark() {
        listOf("light", "dark").forEach { theme ->
            show(theme) { Parts() }
            compose
                .onAllNodesWithTag("connection-reason", useUnmergedTree = true)
                .assertCountEquals(2)
            compose.onNodeWithTag("proxy-stable-香港 01", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("route-netflix-access", useUnmergedTree = true).assertExists()
            shot("parts-$theme")
            show(theme) { Doctor() }
            compose.onNodeWithTag("doctor-cause").assertExists()
            shot("doctor-$theme")
            show(theme, scroll = false) { Profiles() }
            shot("profiles-$theme")
        }
    }

    private fun show(
        theme: String,
        scroll: Boolean = true,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme(theme, false) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .then(
                                if (scroll) Modifier.verticalScroll(rememberScrollState())
                                else Modifier
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Parts() {
        ConnectionParts()
        ProxyParts()
        RouteParts()
    }

    @androidx.compose.runtime.Composable
    private fun ConnectionParts() {
        ConnectionListItem(
            ConnectionRow(
                1,
                "tcp",
                "Japan",
                "www.youtube.com:443",
                12,
                2048,
                81920,
                "GEOSITE",
                "youtube",
            ),
            tr(R.string.connection_reason_service, "YouTube"),
        ) {}
        ConnectionListItem(
            ConnectionRow(
                2,
                "tcp",
                "DIRECT",
                "example.com:443",
                5,
                512,
                1024,
                "DOMAIN-SUFFIX",
                "example.com",
            ),
            tr(R.string.connection_reason_custom, 2),
        ) {}
    }

    @androidx.compose.runtime.Composable
    private fun ProxyParts() {
        val profile = routeProfile(mainPick = ROUTE_MANUAL)
        val state =
            routeState(
                profile,
                ProxyTestState(
                    values =
                        mapOf(
                            delayKey(profile.id, "香港 01", ROUTE_MANUAL) to
                                ProxyDelay("passed", 142),
                            delayKey(profile.id, "香港 02", ROUTE_MANUAL) to ProxyDelay("passed", 412),
                        ),
                    history =
                        mapOf(
                            delayKey(profile.id, "香港 01", ROUTE_MANUAL) to listOf(150, 155, 148),
                            delayKey(profile.id, "香港 02", ROUTE_MANUAL) to
                                listOf(90, FAILED_CHECK, 400),
                        ),
                ),
            )
        RouteCard(state, proxyRoute(state.data, profile, state.live, null), routeActions())
        MemberRow(state, state.groups.getValue(ROUTE_MANUAL), "香港 01", routeActions())
        MemberRow(state, state.groups.getValue(ROUTE_MANUAL), "香港 02", routeActions())
    }

    @androidx.compose.runtime.Composable
    private fun RouteParts() {
        Tile {
            RouteRow(
                RouteLabel(
                    "Netflix",
                    "netflix",
                    access = RouteAccess(UnlockResult(UnlockStatus.ORIGINALS_ONLY, "SG")) {},
                ),
                ROUTE_PROXY,
                true,
            ) {}
            RouteRow(
                RouteLabel(
                    "ChatGPT",
                    "chatgpt",
                    access = RouteAccess(UnlockResult(UnlockStatus.AVAILABLE, "JP")) {},
                ),
                ROUTE_PROXY,
                true,
            ) {}
            RouteRow(
                RouteLabel("Claude", "claude", access = RouteAccess(null) {}),
                ROUTE_DEFAULT,
                true,
            ) {}
        }
    }

    @androidx.compose.runtime.Composable
    private fun Doctor() {
        val steps =
            listOf(
                Models.Step(
                    Models.Stage.NETWORK,
                    Models.State.PASSED,
                    Models.Code.NETWORK_READY,
                    0,
                    "",
                    0,
                ),
                Models.Step(Models.Stage.DNS, Models.State.PASSED, Models.Code.DNS_OK, 12, "", 0),
                Models.Step(
                    Models.Stage.TCP,
                    Models.State.FAILED,
                    Models.Code.TCP_TIMEOUT,
                    5000,
                    "",
                    0,
                ),
            )
        ConnectionDoctorTile(
            DoctorState(
                evidence =
                    DoctorEvidence(
                        true,
                        true,
                        "rule",
                        steps,
                        listOf(Latency.Failed(WebsiteFailure.TIMEOUT), null, null, null),
                        false,
                    )
            ),
            true,
            {},
            {},
        )
        ConnectionDoctorTile(
            DoctorState(running = true, stage = DoctorStage.WEBSITES),
            true,
            {},
            {},
        )
    }

    @androidx.compose.runtime.Composable
    private fun Profiles() {
        val now = java.time.Instant.now().epochSecond
        val profile =
            LocalProfile(
                "p1",
                "Example subscription",
                "https://example.invalid/sub",
                emptyList(),
                0,
                false,
                quota = SubscriptionQuota(0, 80 * gib, 100 * gib, now + 30 * day),
                usage = listOf(UsageSample(now - 4 * day, 40 * gib), UsageSample(now, 80 * gib)),
            )
        ProfilesOverview(
            AppData(profiles = listOf(profile), activeProfileId = "p1"),
            false,
            AppUiState(),
            ProfileActions({}, { _, _ -> }, {}),
        )
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "visual-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
