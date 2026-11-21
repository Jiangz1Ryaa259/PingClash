// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Proxies page with a subscription like the ones people import, in light and dark, saved as
 * `route-*.png` in the app's external files for a look on Android 13+.
 */
@RunWith(AndroidJUnit4::class)
class ProxyRouteLookTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = mainThreadComposeRule<MainActivity>()

    private val nodes =
        listOf(
            "🇭🇰 香港 01" to "ss",
            "🇭🇰 香港 02 IPLC" to "trojan",
            "🇯🇵 日本 东京 01" to "vmess",
            "🇯🇵 日本 大阪 02" to "hysteria2",
            "🇸🇬 新加坡 01" to "trojan",
            "🇺🇸 美国 洛杉矶 01" to "ss",
        )

    private fun profile(): LocalProfile {
        val names = nodes.map { it.first }
        return LocalProfile(
            "look",
            "示例机场",
            null,
            nodes.map { (name, type) -> LocalNode("n-$name", name, type, "{}") },
            0,
            false,
            groups =
                listOf(
                    LocalGroup("节点选择", "select", listOf("自动选择", "香港节点") + names, "香港节点", true),
                    LocalGroup("自动选择", "url-test", names, "", true),
                    LocalGroup("香港节点", "select", names.take(2), names[1], true),
                    LocalGroup("YouTube", "select", listOf("节点选择", "香港节点", "DIRECT"), "节点选择", true),
                    LocalGroup(
                        "Netflix",
                        "select",
                        listOf("香港节点", names[4], "DIRECT"),
                        names[4],
                        true,
                    ),
                    LocalGroup("故障转移", "fallback", names, "", true),
                    LocalGroup("广告拦截", "select", listOf("REJECT", "DIRECT"), "REJECT", true),
                ),
        )
    }

    private fun tests(p: LocalProfile, exitFails: Boolean): ProxyTestState {
        val ms = listOf(142L, 48L, 86L, 0L, 210L, 388L)
        val values =
            p.groups.flatMap { g ->
                nodes.mapIndexed { i, (name, _) ->
                    val delay =
                        when {
                            exitFails && i == 1 -> ProxyDelay("timeout")
                            ms[i] == 0L -> ProxyDelay("timeout")
                            else -> ProxyDelay("passed", ms[i])
                        }
                    delayKey(p.id, name, g.name) to delay
                }
            }
        val history =
            mapOf(
                delayKey(p.id, nodes[0].first, "香港节点") to listOf<Long>(150, 140, 146),
                delayKey(p.id, nodes[1].first, "香港节点") to listOf<Long>(50, FAILED_CHECK, 400),
            )
        return ProxyTestState(values = values.toMap(), history = history)
    }

    private fun show(theme: String, exitFails: Boolean = false) {
        val p = profile()
        val data = AppData(listOf(p), selectedId = p.groupId("节点选择"), activeProfileId = p.id)
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme(theme, false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ProxyScreen(data, false, model, tests(p, exitFails), {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun routeListAndSheetInBothThemes() {
        listOf("light", "dark").forEach { theme ->
            show(theme)
            shot("page-$theme")
            compose.onNodeWithTag("route-stop-节点选择").performClick()
            compose.waitForIdle()
            Thread.sleep(600)
            shot("sheet-$theme")
            compose.closeProxyGroup()
            compose.onNodeWithTag("proxy-groups").performScrollToKey("自动选择")
            compose.onNodeWithTag("group-tab-自动选择").performClick()
            compose.waitForIdle()
            Thread.sleep(600)
            shot("auto-$theme")
            compose.closeProxyGroup()
        }
        show("light", exitFails = true)
        compose.onNodeWithTag("route-help").assertExists()
        shot("trouble-light")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "route-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
