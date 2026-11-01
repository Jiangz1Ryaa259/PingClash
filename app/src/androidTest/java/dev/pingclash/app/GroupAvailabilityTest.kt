// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAvailabilityTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun node(name: String) =
        JSONObject()
            .put("name", name)
            .put("type", "anytls")
            .put("server", "example.invalid")
            .put("port", 443)
            .put("password", "availability-android-fixture")

    @Test
    fun mixed101And11SubscriptionKeepsSelectableGroupsAndLocalReparse() {
        val app = compose.activity.application as PingClashApplication
        val nodes = JSONArray()
        val names = (0 until 112).map { "节点$it" }
        names.forEachIndexed { i, name ->
            nodes.put(node(name).apply { if (i >= 101) put("udp", "invalid-flag") })
        }
        fun group(name: String, type: String, members: List<String>) =
            JSONObject().put("name", name).put("type", type).put("proxies", JSONArray(members))
        val raw =
            JSONObject()
                .put("proxies", nodes)
                .put(
                    "proxy-groups",
                    JSONArray()
                        .put(group("主代理", "select", listOf("自动选择", "故障转移", "DIRECT") + names))
                        .put(group("自动选择", "url-test", names))
                        .put(group("故障转移", "fallback", names)),
                )
                .toString()
        val profile = app.repository.importProfile("异常成员隔离回归", raw)
        try {
            app.repository.activate(profile.id)
            assertEquals(101, profile.nodes.size)
            assertEquals(11, profile.unsupported)
            assertEquals(0, profile.groupsUnsupported)
            assertEquals(11, profile.groups[1].unavailable)
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithTag("group-choice-主代理-节点0").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.activeProfile?.groups?.first()?.selected == "节点0"
            }
            compose.onNodeWithTag("group-tab-自动选择").performClick()
            compose.onNodeWithTag("group-choice-自动选择-节点1").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.activeProfile?.groups?.get(1)?.selected == "节点1"
            }
            assertEquals("主代理", app.repository.data.value.selected?.name)
            captureSyntheticFixture(
                compose,
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                "group-availability.png",
            )
            compose.onNodeWithTag("nav-2").performClick()
            compose.onNodeWithTag("profile-menu-${profile.id}").performClick()
            compose.onNodeWithTag("profile-reparse-${profile.id}").performScrollTo().performClick()
            val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            compose.waitUntil(10000) { !model.ui.value.busy }
            model.dismissMessage()
            val reread = ProfileRepository(app).data.value.activeProfile!!
            assertEquals(listOf("主代理", "自动选择", "故障转移"), reread.groups.map { it.name })
            assertEquals("节点0", reread.groups[0].selected)
            assertEquals("节点1", reread.groups[1].selected)
            assertEquals(names, reread.groups[1].proxies)
            assertEquals(11, reread.groups[1].unavailable)
            assertFalse(
                File(app.filesDir, "profiles.enc")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("availability-android-fixture")
            )
        } finally {
            app.repository.delete(profile.id)
        }
    }

    @Test
    fun brokenManualDefaultCanBeChangedWithoutCommittingAnotherInvalidChoice() {
        val app = compose.activity.application as PingClashApplication
        val raw =
            JSONObject()
                .put("proxies", JSONArray().put(node("有效节点")))
                .put(
                    "proxy-groups",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "手动选择")
                                .put("type", "select")
                                .put("proxies", JSONArray(listOf("缺失成员", "有效节点")))
                        ),
                )
                .toString()
        val p = app.repository.importProfile("手动恢复回归", raw)
        try {
            app.repository.activate(p.id)
            assertFalse(p.groups[0].supported)
            assertTrue(p.groups[0].selectionEditable)
            val previous = File(app.filesDir, "profiles.enc").readBytes()
            assertTrue(runCatching { app.repository.selectGroup(p.id, "手动选择", "缺失成员") }.isFailure)
            assertArrayEquals(previous, File(app.filesDir, "profiles.enc").readBytes())
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithTag("group-choice-手动选择-有效节点").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                app.repository.data.value.activeProfile?.groups?.first()?.supported == true
            }
            assertEquals(
                "有效节点",
                ProfileRepository(app).data.value.activeProfile?.groups?.first()?.selected,
            )
            assertEquals("手动选择", app.repository.data.value.selected?.name)
        } finally {
            app.repository.delete(p.id)
        }
    }
}
