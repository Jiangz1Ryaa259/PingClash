package dev.pingclash.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionGroupsPriorityTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()

    private fun fixture(
        default: String = "B",
        members: List<String> = listOf("C", "A", "B"),
    ): String {
        val nodes = JSONArray()
        listOf("A", "B", "C").forEach { name ->
            nodes.put(
                JSONObject()
                    .put("name", name)
                    .put("type", "trojan")
                    .put("server", "example.invalid")
                    .put("port", 443)
                    .put("password", "synthetic-priority-fixture")
            )
        }
        return JSONObject()
            .put("proxies", nodes)
            .put(
                "proxy-groups",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("name", "服务商主组")
                            .put("type", "select")
                            .put("proxies", JSONArray(listOf("服务商区域", "服务商自动")))
                    )
                    .put(
                        JSONObject()
                            .put("name", "服务商区域")
                            .put("type", "select")
                            .put("proxies", JSONArray(members))
                            .put("default-selected", default)
                    )
                    .put(
                        JSONObject()
                            .put("name", "服务商自动")
                            .put("type", "url-test")
                            .put("proxies", JSONArray(listOf("B", "A")))
                            .put("url", "https://example.invalid/")
                    ),
            )
            .toString()
    }

    @Test
    fun providerOrderDefaultsAndMembershipSurviveRefreshOnlyManualChoicesOverride() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PingClashApplication
        val repo = app.repository
        val ids = mutableListOf<String>()
        val before = repo.data.value.selectedId
        try {
            val initial = repo.importProfile("原订阅优先回归", fixture()).also { ids.add(it.id) }
            repo.activate(initial.id)
            assertEquals(listOf("服务商主组", "服务商区域", "服务商自动"), initial.displayGroups().map { it.name })
            assertEquals(listOf("服务商区域", "服务商自动"), initial.groups[0].proxies)
            assertEquals(listOf("C", "A", "B"), initial.groups[1].proxies)
            assertEquals("B", initial.groups[1].selected)
            assertTrue(initial.userSelections.isEmpty())
            assertEquals(initial.groupId("服务商主组"), repo.data.value.selectedId)

            val refreshed = repo.importProfile(initial.name, fixture("A"), replaceId = initial.id)
            assertEquals(
                "A",
                refreshed.groups[1].selected,
            ) // Old automatic default must not override the provider.
            repo.selectGroup(initial.id, "服务商区域", "C")
            val manual =
                repo.importProfile(
                    initial.name,
                    fixture("B", listOf("B", "C", "A")),
                    replaceId = initial.id,
                )
            assertEquals("C", manual.groups[1].selected)
            assertEquals(listOf("B", "C", "A"), manual.groups[1].proxies)
            assertEquals(
                mapOf("服务商区域" to "C"),
                ProfileRepository(app).data.value.activeProfile!!.userSelections,
            )

            val removed =
                repo.importProfile(
                    initial.name,
                    fixture("B", listOf("B", "A")),
                    replaceId = initial.id,
                )
            assertEquals("B", removed.groups[1].selected)
            assertTrue(removed.userSelections.isEmpty())
            repo.selectGroup(initial.id, "服务商自动", "A")
            repo.selectGroup(initial.id, "服务商自动", "")
            assertEquals(
                "",
                repo
                    .importProfile(initial.name, fixture(), replaceId = initial.id)
                    .groups[2]
                    .selected,
            )
            val other = repo.importProfile("另一份同名分组订阅", fixture("A")).also { ids.add(it.id) }
            assertEquals("A", other.groups[1].selected)
            assertTrue(other.userSelections.isEmpty())
            assertEquals(initial.id, repo.data.value.activeProfile?.id)
        } finally {
            ids.reversed().forEach(repo::delete)
            before
                ?.takeIf { id -> repo.data.value.profiles.any { it.containsTarget(id) } }
                ?.let(repo::select)
        }
    }

    @Test
    fun declaredUnavailableGroupsNeverBecomeAllNodesOrSilentlySelectAnotherGroup() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PingClashApplication
        val repo = app.repository
        val ids = mutableListOf<String>()
        val before = repo.data.value.selectedId
        try {
            val nodeOnly = JSONObject(fixture()).apply { remove("proxy-groups") }.toString()
            val pool = repo.importProfile("仅节点回归", nodeOnly).also { ids.add(it.id) }
            assertFalse(pool.hasSubscriptionGroups)
            assertEquals(listOf("全部节点"), pool.displayGroups().map { it.name })
            repo.activate(pool.id)
            val declared = repo.importProfile(pool.name, fixture(), replaceId = pool.id)
            assertEquals(declared.groupId("服务商主组"), repo.data.value.selectedId)
            for (raw in
                listOf(
                    JSONArray(),
                    JSONObject().put("invalid", true),
                    JSONArray().apply {
                        repeat(65) {
                            put(
                                JSONObject()
                                    .put("name", "组$it")
                                    .put("type", "select")
                                    .put("proxies", JSONArray(listOf("A")))
                            )
                        }
                    },
                )) {
                val input = JSONObject(fixture()).put("proxy-groups", raw).toString()
                val profile = repo.importProfile("不可用分组回归", input).also { ids.add(it.id) }
                assertTrue(profile.hasSubscriptionGroups)
                assertTrue(profile.displayGroups().isEmpty())
                assertNull(profile.defaultTargetId)
                repo.activate(profile.id)
                assertNull(repo.data.value.selected)
                assertEquals(profile.id, ProfileRepository(app).data.value.activeProfile?.id)
            }
            val invalidFirst =
                JSONObject(fixture())
                    .apply { getJSONArray("proxy-groups").getJSONObject(0).put("type", "relay") }
                    .toString()
            val unsupported = repo.importProfile("原主组不可用回归", invalidFirst).also { ids.add(it.id) }
            assertFalse(unsupported.groups[0].supported)
            assertTrue(unsupported.groups[1].supported)
            assertNull(unsupported.defaultTargetId)
            repo.activate(unsupported.id)
            assertNull(repo.data.value.selected)
            repo.delete(pool.id)
            assertEquals(unsupported.id, repo.data.value.activeProfile?.id)
            assertNull(repo.data.value.selected)
            ids.remove(pool.id)
        } finally {
            ids.reversed().forEach(repo::delete)
            before
                ?.takeIf { id -> repo.data.value.profiles.any { it.containsTarget(id) } }
                ?.let(repo::select)
        }
    }
}
