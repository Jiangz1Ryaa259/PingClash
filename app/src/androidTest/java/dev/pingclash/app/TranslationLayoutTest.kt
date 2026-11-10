// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A translation can be right and still not fit. Opens the pages in every language and fails on any
 * text that does not fit the place it was given, so a new language shows its problems here.
 */
@RunWith(AndroidJUnit4::class)
class TranslationLayoutTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val problems = mutableListOf<String>()

    /**
     * Compose's own overflow flags also fire on text sized to its content, so measure. Editable
     * fields scroll, and an ellipsis is how names from a subscription are meant to give way.
     */
    private fun cutOff(layout: TextLayoutResult, node: SemanticsNode): Boolean {
        val input = layout.layoutInput
        val needed = layout.multiParagraph.intrinsics.maxIntrinsicWidth
        val oneLine = input.maxLines == 1 || !input.softWrap
        val parent = node.parent?.size?.width ?: Int.MAX_VALUE
        val cut =
            (oneLine && needed > node.size.width + 1) ||
                (!oneLine && layout.multiParagraph.didExceedMaxLines) ||
                node.size.width > parent + 1
        return cut &&
            !node.config.contains(SemanticsActions.SetText) &&
            input.overflow != TextOverflow.Ellipsis
    }

    private fun scan(language: String, page: String) {
        compose.waitForIdle()
        val hasLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        compose
            .onAllNodes(hasLayout, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts
                    .filter { cutOff(it, node) }
                    .forEach { problems += "$language/$page: ${it.layoutInput.text.text}" }
            }
    }

    private fun open(tag: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tab(index: Int) {
        compose.onNodeWithTag("nav-$index").performClick()
        compose.waitForIdle()
    }

    private fun walkTheGuide(language: String) {
        open("settings-setup")
        repeat(GUIDE_STEPS) { step ->
            scan(language, "guide $step")
            open("setup-next")
        }
        scan(language, "guide ready")
        open("setup-home")
    }

    private fun walk(language: String) {
        tab(0)
        scan(language, "home")
        compose.onNodeWithContentDescription(tr(R.string.ui_more)).performClick()
        scan(language, "home details")
        compose.onNodeWithContentDescription(tr(R.string.ui_more)).performClick()
        tab(1)
        scan(language, "proxies")
        tab(2)
        scan(language, "profiles")
        tab(3)
        scan(language, "settings")
        SETTINGS_PAGES.forEach { tag ->
            open(tag)
            scan(language, tag)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
        walkTheGuide(language)
    }

    @Test
    fun noTextIsCutOffInAnyLanguage() {
        val app = compose.activity.application as PingClashApplication
        runBlocking { app.repository.importProfile("Layout", FIXTURE) }
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        UI_LANGUAGES.forEach { language ->
            model.appearance(language.code, app.repository.data.value.client.theme)
            compose.waitUntil(WAIT) { UiStrings.preference() == language.code }
            compose.waitUntil(WAIT) {
                compose.onAllNodesWithTag("glass-navigation").fetchSemanticsNodes().isNotEmpty()
            }
            walk(language.code)
        }
        assertTrue(problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun aLongWheelLabelStaysOnTheCard() {
        // Short labels stay exactly on the wheel; a long one moves in just enough to fit.
        assertEquals(149f, drumLabelX(149f, halfExtent = 20f, width = 374f, margin = 6f), 0f)
        assertEquals(336f, drumLabelX(340f, halfExtent = 32f, width = 374f, margin = 6f), 0f)
        assertEquals(38f, drumLabelX(34f, halfExtent = 32f, width = 374f, margin = 6f), 0f)
    }

    private companion object {
        const val WAIT = 10_000L
        const val GUIDE_STEPS = 4
        val SETTINGS_PAGES =
            listOf(
                "settings-network",
                "settings-dns",
                "settings-rules",
                "settings-routing",
                "settings-apps",
                "settings-connections",
                "tool-diagnosis",
                "settings-appearance",
                "settings-control",
                "settings-automation",
                "settings-privacy",
                "settings-about",
            )
        val FIXTURE =
            """
            proxies:
              - {name: node-a, type: ss, server: 198.51.100.1, port: 8388, cipher: aes-256-gcm, password: fixture}
              - {name: node-b, type: ss, server: 198.51.100.2, port: 8388, cipher: aes-256-gcm, password: fixture}
            proxy-groups:
              - {name: Pick, type: select, proxies: [node-a, node-b]}
            rules:
              - MATCH,Pick
            """
                .trimIndent()
    }
}
