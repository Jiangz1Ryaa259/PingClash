// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Chooses a way to add a profile in the open Add subscription sheet: a group of several opens its
 * menu first, a group of one is the method itself.
 */
internal fun AndroidComposeTestRule<*, *>.chooseImport(method: String) {
    val group = ImportGroup.entries.first { g -> g.methods.any { it.name.lowercase() == method } }
    waitUntil(5_000) {
        onAllNodes(androidx.compose.ui.test.hasTestTag("import-sheet"))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
    if (group.methods.size > 1)
        onNodeWithTag("import-group-${group.name.lowercase()}").performClick()
    waitForIdle()
    onNodeWithTag("import-method-$method").performClick()
}
