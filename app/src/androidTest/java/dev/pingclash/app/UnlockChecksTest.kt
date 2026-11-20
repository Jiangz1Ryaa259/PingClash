// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each service's own answers become an indication of access and the region it sees. */
@RunWith(AndroidJUnit4::class)
class UnlockChecksTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun netflixIsJudgedByItsLicensedTitles() {
        assertEquals("JP", netflixRegion("https://www.netflix.com/jp/title/81280792"))
        assertEquals("DE", netflixRegion("https://www.netflix.com/de-en/title/70143836"))
        assertEquals("", netflixRegion(null))
        assertEquals(
            UnlockResult(UnlockStatus.AVAILABLE, "JP"),
            netflixVerdict(listOf(404, 200), "JP"),
        )
        assertEquals(
            UnlockResult(UnlockStatus.ORIGINALS_ONLY, "SG"),
            netflixVerdict(listOf(404, 404), "SG"),
        )
        assertEquals(UnlockResult(UnlockStatus.UNAVAILABLE), netflixVerdict(listOf(403, 403), ""))
        assertEquals(UnlockResult(UnlockStatus.UNKNOWN), netflixVerdict(listOf(500), ""))
    }

    @Test
    fun youtubeAndTheAiServicesReadTheirPages() {
        assertEquals(
            UnlockResult(UnlockStatus.UNAVAILABLE, "HK"),
            youtubeVerdict(
                WebsiteReply(
                    200,
                    null,
                    "\"GL\":\"HK\" … YouTube Premium is not available in your country",
                )
            ),
        )
        assertEquals(
            UnlockResult(UnlockStatus.AVAILABLE, "JP"),
            youtubeVerdict(WebsiteReply(200, null, "{\"countryCode\":\"JP\"} ad-free")),
        )
        assertEquals("JP", traceRegion("fl=1\nip=192.0.2.1\nloc=JP\ntls=TLSv1.3\n"))
        assertEquals("", traceRegion("loc=\n"))
        assertEquals(
            UnlockResult(UnlockStatus.UNAVAILABLE, "HK"),
            regionPageVerdict(
                "claude",
                WebsiteReply(302, "https://claude.ai/app-unavailable-in-region"),
                "HK",
            ),
        )
        assertEquals(
            UnlockResult(UnlockStatus.AVAILABLE, "US"),
            regionPageVerdict("chatgpt", WebsiteReply(403, null, "Just a moment..."), "US"),
        )
        assertEquals(
            UnlockResult(UnlockStatus.UNKNOWN),
            regionPageVerdict("chatgpt", WebsiteReply(0), ""),
        )
    }

    @Test
    fun theRowSaysWhatItFoundAndChecksAgainOnTap() {
        var checks = 0
        val result = UnlockResult(UnlockStatus.AVAILABLE, "JP")
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    RouteAccessLine("netflix", RouteAccess(result) { checks++ })
                }
            }
        }
        val line = compose.onNodeWithTag("route-netflix-access")
        line.assertExists()
        assertEquals(
            tr(R.string.unlock_available) + " · " + exitCountryLabel("JP", "en"),
            unlockText(result, "en"),
        )
        line.performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        assertEquals(tr(R.string.unlock_check), unlockText(null, "en"))
        assertEquals(
            tr(R.string.unlock_needs_vpn),
            unlockText(UnlockResult(UnlockStatus.NEEDS_VPN, "JP"), "en"),
        )
    }
}
