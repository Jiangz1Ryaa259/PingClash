// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceRoutingTest {
    @get:Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val app
        get() = compose.activity.application as PingClashApplication

    /** Category names in the bundled GeoSite.dat: GeoSiteList{GeoSite entry=1{code=1}}. */
    private fun bundledCategories(): Set<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open("ruledata/GeoSite.dat").use { it.readBytes() }
        val codes = HashSet<String>()
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (i < bytes.size) {
            varint()
            val size = varint().toInt()
            val end = i + size
            if (varint() == 10L) {
                val length = varint().toInt()
                codes += String(bytes, i, length, Charsets.UTF_8).uppercase()
            }
            i = end
        }
        return codes
    }

    @Test
    fun everyShippedRowIsCheckedAndPointsAtBundledData() {
        val categories = bundledCategories()
        assertTrue(categories.size > 1000)
        assertEquals(SERVICE_CATALOGUE.size, SERVICE_IDS.size)
        for (service in SERVICE_CATALOGUE) {
            assertTrue(service.id, service.geosite.isNotEmpty() || service.suffixes.isNotEmpty())
            assertTrue(service.id, service.geosite.all { it in categories })
            assertTrue(service.id, service.checked.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        }
        // A tab ships only with enough verified, useful rows.
        for (region in ServiceRegion.entries) assertTrue(
            region.name,
            SERVICE_CATALOGUE.count { it.region == region } >= 3,
        )
        for (region in listOf(null) + ServiceRegion.entries) guideRows(region).forEach { row ->
            assertTrue(row.id, row.members.isNotEmpty() && row.members.all { it in SERVICE_IDS })
        }
        assertTrue(
            listOf("CATEGORY-ADS-ALL", "CATEGORY-ADS-AND-TELEMETRY").all { it in categories }
        )
    }

    @Test
    fun rulesPutBlockingFirstAndNarrowServicesBeforeTheirParents() {
        assertEquals(emptyList<String>(), serviceRouteRules(emptyMap(), BLOCKING_OFF))
        val rules =
            serviceRouteRules(
                mapOf("google" to ROUTE_PROXY, "youtube" to ROUTE_DIRECT, "itvx" to ROUTE_PROXY),
                BLOCKING_TELEMETRY,
            )
        assertEquals("GEOSITE,CATEGORY-ADS-ALL,REJECT", rules[0])
        assertEquals("GEOSITE,CATEGORY-ADS-AND-TELEMETRY,REJECT", rules[1])
        assertTrue(rules.indexOf("GEOSITE,YOUTUBE,DIRECT") < rules.indexOf("GEOSITE,GOOGLE,PROXY"))
        assertTrue("DOMAIN-SUFFIX,itv.com,PROXY" in rules)
        assertTrue("DOMAIN,itvpnpmobile-a.akamaihd.net,PROXY" in rules)
        assertEquals(1, serviceRouteRules(emptyMap(), BLOCKING_ADS).size)
        // The user's own rules stay first, then services, then the subscription.
        val data =
            AppData(
                client =
                    ClientSettings(
                        customRules = "DOMAIN,example.invalid,DIRECT",
                        serviceRoutes = mapOf("netflix" to ROUTE_PROXY),
                    )
            )
        val runtime = JSONObject(data.runtimeOptions()).getJSONArray("rules")
        assertEquals("DOMAIN,example.invalid,DIRECT", runtime.getString(0))
        assertEquals("GEOSITE,NETFLIX,PROXY", runtime.getString(1))
    }

    @Test
    fun theCoreAcceptsEveryRuleTheCatalogueCanProduce() {
        val repo = app.repository
        val before = repo.data.value
        try {
            for (route in ROUTE_VALUES) {
                val every = SERVICE_IDS.associateWith { route }
                val value =
                    before.client.copy(
                        mode = "rule",
                        serviceRoutes = every,
                        blocking = BLOCKING_TELEMETRY,
                    )
                // clientSettings validates the complete rule table with the native core.
                repo.clientSettings(value, before.dnsAddress, before.probeUrl)
                assertEquals(every, repo.data.value.client.serviceRoutes)
            }
        } finally {
            repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
        }
    }

    @Test
    fun storedChoicesDropUnknownServicesAndSerialiseStably() {
        val stored =
            ClientSettings(serviceRoutes = mapOf("youtube" to ROUTE_PROXY, "bbc" to ROUTE_DIRECT))
                .json()
                .apply {
                    getJSONObject("serviceRoutes")
                        .put("retired-service", ROUTE_PROXY)
                        .put("netflix", "sideways")
                    put("blocking", "everything")
                }
        val read = ClientSettings.read(stored)
        assertEquals(mapOf("bbc" to ROUTE_DIRECT, "youtube" to ROUTE_PROXY), read.serviceRoutes)
        assertEquals(BLOCKING_OFF, read.blocking)
        validateClientSettings(read)
        assertEquals(
            ClientSettings(serviceRoutes = mapOf("youtube" to ROUTE_PROXY, "bbc" to ROUTE_DIRECT))
                .json()
                .toString(),
            ClientSettings(serviceRoutes = mapOf("bbc" to ROUTE_DIRECT, "youtube" to ROUTE_PROXY))
                .json()
                .toString(),
        )
        assertFalse(
            runCatching {
                    validateClientSettings(
                        ClientSettings(serviceRoutes = mapOf("nope" to ROUTE_PROXY))
                    )
                }
                .isSuccess
        )
        // Backups carry the choices.
        val backup =
            SettingsBackup.export(AppData(client = read.copy(blocking = BLOCKING_ADS)), false)
        val restored = SettingsBackup.read(backup).settings
        assertEquals(read.serviceRoutes, restored.serviceRoutes)
        assertEquals(BLOCKING_ADS, restored.blocking)
    }

    @Test
    fun settingsPageSavesAtOnceAndOnlyTheServiceListScrolls() {
        val repo = app.repository
        val before = repo.data.value
        try {
            repo.serviceRouting(emptyMap(), BLOCKING_OFF)
            compose.onNodeWithTag("nav-3").performClick()
            compose.onNodeWithTag("settings-routing").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("routing-clear-all").assertIsNotEnabled()
            compose.onNodeWithText(tr(R.string.routing_regions)).assertIsDisplayed()
            // The page itself does not scroll; the service list does.
            assertFalse(
                compose
                    .onNodeWithTag("settings-page")
                    .fetchSemanticsNode()
                    .config
                    .contains(
                        androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
                    )
            )
            compose.onNodeWithTag("routing-list").assertIsDisplayed()
            compose.onNodeWithTag("choice-${tr(R.string.ad_blocking)}-$BLOCKING_ADS").performClick()
            compose.waitUntil(5000) { repo.data.value.client.blocking == BLOCKING_ADS }
            compose.onNodeWithTag("routing-region-UK").performScrollTo().performClick()
            compose.onNodeWithTag("route-bbc-direct").performClick()
            compose.waitUntil(5000) { repo.data.value.client.serviceRoutes["bbc"] == ROUTE_DIRECT }
            compose.onNodeWithTag("routing-region-AI").performScrollTo().performClick()
            compose.onNodeWithTag("route-claude-proxy").performClick()
            compose.waitUntil(5000) {
                repo.data.value.client.serviceRoutes["claude"] == ROUTE_PROXY
            }
            compose.onNodeWithTag("routing-clear-all").performClick()
            compose.onNodeWithTag("routing-clear-confirm").performClick()
            compose.waitUntil(5000) { repo.data.value.client.serviceRoutes.isEmpty() }
            // Clear all leaves ad blocking alone.
            assertEquals(BLOCKING_ADS, repo.data.value.client.blocking)
        } finally {
            repo.serviceRouting(before.client.serviceRoutes, before.client.blocking)
        }
    }

    @Test
    fun aServiceCanUseAProfileGroupAndFallsBackWithoutIt() {
        // A group the profile lacks falls back, and only a real group name reaches a rule.
        assertEquals(
            listOf("GEOSITE,NETFLIX,PROXY"),
            serviceRouteRules(mapOf("netflix" to groupRoute("Gone")), BLOCKING_OFF),
        )
        assertEquals(
            listOf("GEOSITE,NETFLIX,Japan"),
            serviceRouteRules(mapOf("netflix" to groupRoute("Japan")), BLOCKING_OFF, setOf("Japan")),
        )
        // A hand-edited backup cannot smuggle rule text through a group name.
        val crafted =
            ClientSettings().json().apply {
                put(
                    "serviceRoutes",
                    org.json
                        .JSONObject()
                        .put("netflix", "group:Japan,no-resolve")
                        .put("bbc", groupRoute("Japan")),
                )
            }
        assertEquals(
            mapOf("bbc" to groupRoute("Japan")),
            ClientSettings.read(crafted).serviceRoutes,
        )
    }

    @Test
    fun theServiceListChoosesAGroupAndTheCoreAcceptsIt() {
        val repo = app.repository
        val before = repo.data.value
        val group = "Japan 日本"
        val profile = repo.importProfile("Group fixture", groupFixture(group))
        try {
            repo.select(profile.nodes.single().id)
            assertTrue(group in repo.data.value.activeProfile!!.routingGroups())
            repo.serviceRouting(mapOf("netflix" to ROUTE_PROXY), BLOCKING_OFF)
            compose.onNodeWithTag("nav-3").performClick()
            compose.onNodeWithTag("settings-routing").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("routing-region-US").performScrollTo().performClick()
            compose.onNodeWithTag("route-netflix-group").performClick()
            val device =
                androidx.test.uiautomator.UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()
                )
            captureSyntheticFixture(compose, device, "routing-group-picker.png")
            compose.onNodeWithTag("route-group-$group").performClick()
            compose.waitUntil(5000) {
                repo.data.value.client.serviceRoutes["netflix"] == groupRoute(group)
            }
            // The segmented choice still reads Proxy, and the core accepts the rule.
            compose.onNodeWithTag("route-netflix-group").assertIsDisplayed()
            captureSyntheticFixture(compose, device, "routing-group-row.png")
            val client = repo.data.value.client
            repo.clientSettings(client, before.dnsAddress, before.probeUrl)
            val rules =
                org.json
                    .JSONObject(repo.data.value.runtimeOptions())
                    .getJSONArray("rules")
                    .toString()
            assertTrue(rules, rules.contains("GEOSITE,NETFLIX,$group"))
            // Back to the main proxy from the same list.
            compose.onNodeWithTag("route-netflix-group").performClick()
            compose.onNodeWithTag("route-group-${tr(R.string.routing_main_proxy)}").performClick()
            compose.waitUntil(5000) {
                repo.data.value.client.serviceRoutes["netflix"] == ROUTE_PROXY
            }
        } finally {
            repo.delete(profile.id)
            repo.serviceRouting(before.client.serviceRoutes, before.client.blocking)
        }
    }

    private fun groupFixture(group: String) =
        """
        {"proxies":[{"type":"socks5","name":"Group fixture node","server":"127.0.0.1","port":1080}],
         "proxy-groups":[{"name":"$group","type":"select",
                          "proxies":["Group fixture node","REJECT"]}]}
        """
            .trimIndent()

    @Test
    fun countriesAndRegionsUseTheChinaNamingRule() {
        val china = setOf(ServiceRegion.MAINLAND, ServiceRegion.HONG_KONG, ServiceRegion.TAIWAN)
        china.forEach { assertEquals("🇨🇳", it.flag) }
        ServiceRegion.entries
            .filter { it.flag.isNotEmpty() && it !in china }
            .forEach { assertFalse(it.flag == "🇨🇳") }
        assertEquals(ServiceRegion.HONG_KONG, homeRegion("MO", "zh"))
        assertEquals(ServiceRegion.TAIWAN, homeRegion("TW", "zh"))
        assertEquals(ServiceRegion.MAINLAND, regionOrder(homeRegion("CN", "zh")).first())
        assertEquals(ServiceRegion.US, regionOrder(homeRegion("DE", "de")).first())
    }
}
