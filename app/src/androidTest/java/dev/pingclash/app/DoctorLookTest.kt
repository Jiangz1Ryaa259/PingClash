// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pingclash.diagnostics.Models
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The doctor's result when every node fails, in every language on a 360 dp phone: each row and each
 * line of the error details stays on one line. Saved as `doctor-*-<language>.png` for a look.
 */
@RunWith(AndroidJUnit4::class)
class DoctorLookTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = mainThreadComposeRule<MainActivity>()

    @Test
    fun everyNodeFailingFitsInEveryLanguage() {
        val app = compose.activity.application as PingClashApplication
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val wrapped = mutableListOf<String>()
        UI_LANGUAGES.forEach { language ->
            model.appearance(language.code, app.repository.data.value.client.theme)
            compose.waitUntil(WAIT) { UiStrings.preference() == language.code }
            compose.waitForIdle()
            show(failing())
            shot("top-${language.code}")
            wrapped += wrappedLines(language.code)
            compose.onNodeWithTag("doctor-copy").performScrollTo()
            compose.waitForIdle()
            shot("details-${language.code}")
        }
        assertTrue(wrapped.joinToString("\n"), wrapped.isEmpty())
        compose.onNodeWithTag("doctor-copy").performClick()
        val evidence = failing()
        val text =
            detailsText(
                evidence,
                doctorLines(evidence, null),
                detailFacts(evidence, supportFacts(compose.activity, evidence, "rule")),
            )
        assertTrue(text.contains("Hong Kong 01"))
        assertTrue(text.contains("Japan 02"))
        assertTrue(text.contains("TCP_TIMEOUT"))
        assertFalse(text.contains("203.0.113.25"))
        // Without node steps there is no empty "Node check" heading.
        val unchecked = evidence.copy(steps = null)
        val bare =
            detailsText(
                unchecked,
                doctorLines(unchecked, null),
                detailFacts(unchecked, supportFacts(compose.activity, unchecked, "rule")),
            )
        assertFalse(bare.contains(tr(R.string.doctor_details_node_check)))
    }

    private fun show(evidence: DoctorEvidence) {
        compose.runOnUiThread {
            compose.activity.setContent {
                PingClashTheme("light", false) {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.width(PHONE_WIDTH.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            ConnectionDoctorTile(DoctorState(evidence = evidence), true, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Texts of the rows, the error details and the buttons that wrapped or were cut short. */
    private fun wrappedLines(language: String): List<String> {
        val hasLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        return compose
            .onAllNodes(hasLayout, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .filter(::inRowsOrDetails)
            .flatMap { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts
                    .filter { layout ->
                        layout.lineCount > 1 ||
                            (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
                    }
                    .map { "$language: ${it.layoutInput.text.text}" }
            }
    }

    private fun inRowsOrDetails(node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        while (current != null) {
            val tag = current.config.getOrNull(SemanticsProperties.TestTag)
            if (tag != null && (tag.startsWith("doctor-row-") || tag in WHOLE_LINES)) return true
            current = current.parent
        }
        return false
    }

    private fun failing(): DoctorEvidence {
        val now = System.currentTimeMillis() / 1000
        return DoctorEvidence(
            true,
            true,
            "rule",
            listOf(
                Models.Step(
                    Models.Stage.NETWORK,
                    Models.State.PASSED,
                    Models.Code.NETWORK_READY,
                    0,
                    "",
                    0,
                ),
                Models.Step(
                    Models.Stage.DNS,
                    Models.State.PASSED,
                    Models.Code.DNS_OK,
                    12,
                    "IPv4",
                    1,
                ),
                Models.Step(
                    Models.Stage.TCP,
                    Models.State.FAILED,
                    Models.Code.TCP_TIMEOUT,
                    8000,
                    "IPv4",
                    1,
                ),
            ),
            emptyList(),
            false,
            PlanCheck(82_400_000_000, now + 15 * 86_400, now),
            ClockCheck(2, true),
            PhoneCheck(
                LinkKind.CELLULAR,
                true,
                false,
                "opportunistic",
                false,
                true,
                false,
                true,
                35,
                "Google",
            ),
            listOf(
                PeerResult("Japan 02", false, 0, "proxy_timeout"),
                PeerResult("Singapore 01", false, 0, "proxy_timeout"),
                PeerResult("US 03", false, 0, "proxy_timeout"),
            ),
            DnsCheck(DnsVerdict.SAME, listOf("203.0.113.25"), listOf("203.0.113.25")),
            "hysteria2",
            "Hong Kong 01",
        )
    }

    private fun shot(name: String) {
        Thread.sleep(400)
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = compose.activity.getExternalFilesDir(null) ?: return
        File(dir, "doctor-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private companion object {
        const val WAIT = 10_000L
        const val PHONE_WIDTH = 360
        val WHOLE_LINES = setOf("doctor-details", "doctor-copy", "doctor-download")
    }
}
