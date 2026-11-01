package dev.pingclash.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyPageTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun disjointGroupsKeepNestedCardsAndRootRouteWhileTabsSwipe() {
        val app = compose.activity.application as PingClashApplication
        val before = app.repository.data.value
        val names =
            listOf(
                "🇭🇰 香港 01 · 专线",
                "🇭🇰 香港 02 · 高速",
                "🇯🇵 东京 01 · 标准",
                "🇯🇵 东京 02 · 专线",
                "🇸🇬 新加坡 · BGP",
                "🇺🇸 洛杉矶 · 标准",
                "🇩🇪 法兰克福 · 高速",
                "🇹🇼 台北 · 专线",
            )
        val nodes =
            JSONArray(
                names.map { name ->
                    JSONObject()
                        .put("name", name)
                        .put("type", "trojan")
                        .put("server", "example.invalid")
                        .put("port", 443)
                        .put("password", "proxy-page-fixture-secret")
                }
            )
        fun group(name: String, type: String, members: List<String>) =
            JSONObject().put("name", name).put("type", type).put("proxies", JSONArray(members))
        val raw =
            JSONObject()
                .put("proxies", nodes)
                .put(
                    "proxy-groups",
                    JSONArray()
                        .put(
                            group(
                                "✨ 主代理",
                                "select",
                                listOf("🚀 高速优选", "🇭🇰 香港", "🇯🇵 日本") + names.drop(4),
                            )
                        )
                        .put(group("🇭🇰 香港", "select", names.take(2)))
                        .put(group("🇯🇵 日本", "select", names.subList(2, 4)))
                        .put(group("🚀 高速优选", "url-test", names))
                        .put(group("隐藏辅助组", "select", listOf(names[0])).put("hidden", true)),
                )
                .toString()
        val p = app.repository.importProfile("代理页分组回归", raw)
        try {
            app.repository.activate(p.id)
            assertEquals(
                listOf("✨ 主代理", "🇭🇰 香港", "🇯🇵 日本", "🚀 高速优选"),
                p.displayGroups().map { it.name },
            )
            assertEquals(names.take(2), p.displayGroups()[1].proxies)
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("添加订阅").assertDoesNotExist()
            compose.onNodeWithTag("group-choice-✨ 主代理-🇭🇰 香港").assertIsDisplayed()
            compose.onNodeWithTag("group-choice-✨ 主代理-${names[0]}").assertDoesNotExist()
            compose.onNodeWithTag("group-tab-隐藏辅助组").assertDoesNotExist()
            // Invoke the whole-card selection, independently of its nested-group arrow.
            compose.onNodeWithTag("group-choice-✨ 主代理-🇭🇰 香港").performSemanticsAction(
                SemanticsActions.OnClick
            ) {
                it()
            }
            try {
                compose.waitUntil(10000) {
                    app.repository.data.value.activeProfile?.groups?.first()?.selected == "🇭🇰 香港"
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                throw AssertionError(
                    "Synthetic group selection: " +
                        "selected=${app.repository.data.value.activeProfile?.groups?.first()?.selected}; " +
                        "busy=${model.ui.value.busy}; message=${model.ui.value.message}",
                    failure,
                )
            }
            assertEquals("✨ 主代理", app.repository.data.value.selected?.name)
            compose.onNodeWithTag("strategy-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithTag("group-choice-🇭🇰 香港-${names[0]}").assertIsDisplayed()
            compose.onNodeWithTag("group-choice-🇭🇰 香港-${names[2]}").assertDoesNotExist()
            compose.onNodeWithTag("group-choice-🇭🇰 香港-${names[1]}").performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.activeProfile?.groups?.get(1)?.selected == names[1]
            }
            assertEquals("✨ 主代理", app.repository.data.value.selected?.name)
            assertEquals("🇭🇰 香港", app.repository.data.value.activeProfile!!.groups[0].selected)
            assertEquals(
                names[1],
                ProfileRepository(app).data.value.activeProfile!!.groups[1].selected,
            )
            compose.onNodeWithTag("group-choice-🇭🇰 香港-${names[1]}").assertIsSelected()
            compose.onNodeWithTag("nav-1").assertIsDisplayed()
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "proxy-group-region.png",
            )
            compose.onNodeWithTag("strategy-pager").performTouchInput { swipeRight() }
            compose.waitForIdle()
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "proxy-grid-light.png",
            )
            compose.onNodeWithContentDescription("搜索代理").performClick()
            compose.onNodeWithTag("proxy-search").performTextInput("法兰克福")
            compose.onNodeWithTag("group-choice-✨ 主代理-${names[6]}").assertIsDisplayed()
            compose.onNodeWithTag("group-choice-✨ 主代理-${names[4]}").assertDoesNotExist()
            compose.onNodeWithText("关闭").performClick()
            val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            val beforeFile = java.io.File(app.filesDir, "profiles.enc").readBytes()
            model.testProxies(app.repository.data.value.activeProfile!!, listOf(names[0], names[1]))
            compose.waitUntil(10000) { !model.proxyTests.value.running }
            assertEquals(2, model.proxyTests.value.values.size)
            assertFalse(model.proxyTests.value.values.values.any { it.state == "passed" })
            assertArrayEquals(beforeFile, java.io.File(app.filesDir, "profiles.enc").readBytes())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            try {
                device.executeShellCommand("cmd uimode night yes")
                compose.waitUntil(10000) {
                    compose.activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                compose.onNodeWithTag("nav-1").performClick()
                captureSyntheticFixture(compose, device, "proxy-grid-dark.png")
                device.executeShellCommand("settings put system font_scale 1.8")
                compose.waitUntil(10000) {
                    compose.activity.resources.configuration.fontScale >= 1.7f
                }
                compose.onNodeWithTag("nav-1").performClick()
                compose.onNodeWithTag("group-members-✨ 主代理").performScrollToIndex(0)
                compose.onNodeWithTag("group-choice-✨ 主代理-🚀 高速优选").assertIsDisplayed()
                captureSyntheticFixture(compose, device, "proxy-grid-large.png")
            } finally {
                device.executeShellCommand("settings put system font_scale 1.0")
                device.executeShellCommand("cmd uimode night no")
            }
        } finally {
            ViewModelProvider(compose.activity)[AppViewModel::class.java].cancelProxyTests()
            app.repository.delete(p.id)
            before.selectedId
                ?.takeIf {
                    AppData(profiles = app.repository.data.value.profiles, selectedId = it)
                        .selected != null
                }
                ?.let(app.repository::select)
        }
    }
}
