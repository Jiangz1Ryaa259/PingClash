// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Repeated taps show at once and settle on the last one without racing. */
@RunWith(AndroidJUnit4::class)
class ProxyTapTest {
    @Test
    fun quickTapsShowAtOnceAndOnlyTheLastIsSaved() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        try {
            val queue =
                SelectionQueue(
                    scope,
                    apply = { choice ->
                        Thread.sleep(300) // A slow encrypted save.
                        applied += "${choice.group}=${choice.value}"
                    },
                    failed = {},
                )
            fun tap(group: String, value: String) =
                queue.choose(SelectionQueue.Choice("p", group, value))
            tap("Main", "A")
            Thread.sleep(50)
            tap("Main", "B")
            tap("Main", "C")
            tap("Other", "X")
            // Shown at once: the page reads the newest choice before anything is saved.
            assertEquals("C", queue.choices.value.values.first { it.group == "Main" }.value)
            val deadline = System.currentTimeMillis() + 5000
            while (queue.choices.value.isNotEmpty() && System.currentTimeMillis() < deadline) Thread
                .sleep(20)
            assertTrue(queue.choices.value.isEmpty())
            // A was already being saved; B was replaced by C before its turn. Never at once.
            assertEquals(listOf("Main=A", "Main=C", "Other=X"), applied.toList())
            val data =
                routeData()
                    .withChoices(
                        mapOf("k" to SelectionQueue.Choice("route-fixture", ROUTE_MANUAL, "香港 02"))
                    )
            assertEquals(
                "香港 02",
                data.profiles.single().groups.first { it.name == ROUTE_MANUAL }.selected,
            )
        } finally {
            scope.cancel()
        }
    }
}
