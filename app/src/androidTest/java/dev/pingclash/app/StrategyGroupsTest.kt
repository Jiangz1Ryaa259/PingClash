package dev.pingclash.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrategyGroupsTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun import234NodesKeepFifteenDialerDependenciesAndPersistManualChoice() {
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value
        val nodes = JSONArray()
        repeat(234) { index ->
            val kind = if (index in 228..233) "trojan" else "hysteria2"
            nodes.put(
                JSONObject()
                    .put("name", "合成节点$index")
                    .put("type", kind)
                    .put("server", "example.invalid")
                    .put("port", 443)
                    .put("password", "private-group-fixture")
                    .put("skip-cert-verify", true)
                    .apply {
                        if (index >= 219)
                            put("dialer-proxy", if (index % 2 == 0) "DIRECT" else "拨号选择")
                    }
            )
        }
        val group =
            JSONObject()
                .put("name", "拨号选择")
                .put("type", "select")
                .put("proxies", JSONArray(listOf("DIRECT", "合成节点0")))
        val auto =
            JSONObject()
                .put("name", "自动测试")
                .put("type", "url-test")
                .put("proxies", JSONArray(listOf("合成节点0", "DIRECT")))
                .put("url", "https://www.gstatic.com/generate_204")
                .put("tolerance", 50)
        val input =
            JSONObject()
                .put("proxies", nodes)
                .put("proxy-groups", JSONArray().put(group).put(auto))
                .toString()
        val parsed = JSONObject(Mobile.importProfile(input))
        assertEquals(
            "Native import code: ${parsed.optString("code")}",
            "passed",
            parsed.optString("state"),
        )
        val profile = app.repository.importProfile("策略组回归", input)
        try {
            app.repository.select(profile.nodes.last().id)
            if (!app.repository.data.value.certificateNoticeAcknowledged) {
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText("节点证书校验提示").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("知道了，不再提醒").performClick()
                compose.waitUntil(5000) { app.repository.data.value.certificateNoticeAcknowledged }
            }
            assertEquals(234, profile.nodes.size)
            assertEquals(0, profile.unsupported)
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithTag("strategy-pager").assertIsDisplayed()
            compose.onNodeWithTag("group-choice-拨号选择-合成节点0").performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.profiles
                    .first { it.id == profile.id }
                    .groups
                    .first()
                    .selected == "合成节点0"
            }
            val selected =
                app.repository.data.value.profiles.first { it.id == profile.id }.nodes.last()
            assertTrue(JSONObject(selected.config).has("dialer-chain"))
            assertEquals(
                "passed",
                JSONObject(Mobile.validateNode(selected.config)).getString("state"),
            )
            val reread = ProfileRepository(app).data.value
            assertEquals(
                "合成节点0",
                reread.profiles.first { it.id == profile.id }.groups.first().selected,
            )
            assertTrue(
                JSONObject(reread.profiles.first { it.id == profile.id }.nodes.last().config)
                    .has("dialer-chain")
            )
            compose.onNodeWithContentDescription("代理选项").performClick()
            compose.onNodeWithTag("group-main-拨号选择").performClick()
            compose.waitUntil(10000) { app.repository.data.value.selected?.type == "group" }
            assertEquals(
                "passed",
                JSONObject(Mobile.validateNode(app.repository.data.value.selectedRuntimeConfig!!))
                    .getString("state"),
            )
            assertEquals("拨号选择", ProfileRepository(app).data.value.selected?.name)
            compose.onNodeWithTag("strategy-pager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("group-tab-自动测试").assertIsSelected()
            compose.onNodeWithTag("group-choice-自动测试-合成节点0").performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.profiles
                    .first { it.id == profile.id }
                    .groups[1]
                    .selected == "合成节点0"
            }
            compose.waitUntil(10000) {
                compose
                    .onAllNodes(
                        hasTestTag("group-choice-自动测试-合成节点0") and isSelected() and isEnabled()
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("group-choice-自动测试-合成节点0")
                .assertIsSelected()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.profiles
                    .first { it.id == profile.id }
                    .groups[1]
                    .selected
                    .isEmpty()
            }
            compose.onNodeWithTag("strategy-pager").performTouchInput { swipeRight() }
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "strategy-groups.png",
            )
            assertFalse(
                java.io
                    .File(app.filesDir, "profiles.enc")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("private-group-fixture")
            )
            app.repository.importProfile(profile.name, profile.definition, replaceId = profile.id)
            assertEquals(
                "合成节点0",
                app.repository.data.value.profiles
                    .first { it.id == profile.id }
                    .groups
                    .first()
                    .selected,
            )
            assertEquals("拨号选择", app.repository.data.value.selected?.name)
        } finally {
            app.repository.delete(profile.id)
            before.selectedId
                ?.takeIf { id -> app.repository.data.value.nodes.any { it.id == id } }
                ?.let(app.repository::select)
        }
    }
}
