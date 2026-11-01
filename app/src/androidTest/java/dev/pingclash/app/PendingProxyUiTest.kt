// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingProxyUiTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pendingStatesUseSpinnersWithoutQueueTextOrTopProgressAndFinishInPlace() {
        val nodes =
            listOf(
                LocalNode("one", "Queued fixture", "socks5", "{}"),
                LocalNode("two", "Running fixture", "socks5", "{}"),
            )
        val groups =
            listOf(
                LocalGroup(
                    "Main",
                    "select",
                    nodes.map { it.name } + "Auto",
                    "Queued fixture",
                    true,
                ),
                LocalGroup("Auto", "url-test", nodes.map { it.name }, "Queued fixture", true),
            )
        val profile =
            LocalProfile("pending-fixture", "Fixture", null, nodes, 0, false, groups = groups)
        fun key(name: String) = delayKey(profile.id, name, "Main")
        val tests =
            mutableStateOf(
                ProxyTestState(
                    running = true,
                    total = 2,
                    values =
                        mapOf(
                            key("Queued fixture") to ProxyDelay("queued"),
                            key("Running fixture") to ProxyDelay("running"),
                            key("Auto") to ProxyDelay("members_running", total = 2),
                        ),
                )
            )
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light") {
                    CompositionLocalProvider(LocalMotionAllowed provides false) {
                        ProxyScreen(
                            AppData(listOf(profile), selectedId = "one"),
                            true,
                            model,
                            tests.value,
                            {},
                            {},
                        )
                    }
                }
            }
        }
        for (name in listOf("Queued fixture", "Running fixture", "Auto")) {
            compose.onNodeWithTag("proxy-pending-$name").assertIsDisplayed()
        }
        compose.onNodeWithText("排队中").assertDoesNotExist()
        compose.onNodeWithText("测速 0 / 2").assertDoesNotExist()
        compose.runOnUiThread {
            tests.value =
                tests.value.copy(
                    running = false,
                    completed = 2,
                    values =
                        mapOf(
                            key("Queued fixture") to ProxyDelay("passed", 42),
                            key("Running fixture") to ProxyDelay("timeout"),
                            key("Auto") to
                                ProxyDelay("members", completed = 2, total = 2, passed = 1),
                        ),
                )
        }
        for (name in listOf("Queued fixture", "Running fixture", "Auto")) {
            compose.onNodeWithTag("proxy-pending-$name").assertDoesNotExist()
        }
        compose.onNodeWithText("42 ms").assertIsDisplayed()
    }
}
