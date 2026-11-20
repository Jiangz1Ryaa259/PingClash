// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Node and group choices from Proxies. A choice shows at once (the page reads [choices]); saving
 * the profile and updating a running VPN happen one at a time in the background, and when several
 * taps land on one group before its turn, only the last is applied. So quick or repeated taps never
 * race each other and never wait on the screen.
 */
internal class SelectionQueue(
    scope: CoroutineScope,
    private val apply: (Choice) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    /** A choice in a group of a profile; an empty [group] is the main proxy, [value] its id. */
    data class Choice(val profileId: String, val group: String, val value: String) {
        val key
            get() = profileId + KEY_SEPARATOR + group
    }

    private val pending = MutableStateFlow<Map<String, Choice>>(emptyMap())

    /** Choices made but not yet saved, by [Choice.key]. */
    val choices = pending.asStateFlow()

    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { for (ignored in signal) drain() }
    }

    /** Returns once every choice made so far is saved, so an operation never overtakes one. */
    suspend fun awaitIdle() {
        pending.first { it.isEmpty() }
    }

    fun choose(choice: Choice) {
        pending.update { it + (choice.key to choice) }
        signal.trySend(Unit)
    }

    // Whatever a save throws is reported; the queue goes on with the next choice.
    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        while (true) {
            val next = pending.value.values.firstOrNull() ?: return
            try {
                apply(next)
            } catch (failure: Exception) {
                failed(failure)
            }
            // A newer tap on the same group stays queued and is applied next.
            pending.update { if (it[next.key] == next) it - next.key else it }
        }
    }

    private companion object {
        const val KEY_SEPARATOR = "\u0000"
    }
}

/** The profile as the page should show it: saved choices with the not-yet-saved ones on top. */
internal fun LocalProfile.withChoices(choices: Map<String, SelectionQueue.Choice>): LocalProfile {
    val mine = choices.values.filter { it.profileId == id && it.group.isNotEmpty() }
    if (mine.isEmpty()) return this
    val byGroup = mine.associate { it.group to it.value }
    return copy(groups = groups.map { g -> byGroup[g.name]?.let { g.copy(selected = it) } ?: g })
}

/**
 * The app data as the page should show it, with a not-yet-saved main proxy and choices. A main
 * proxy chosen on another profile's page is not shown: it is dropped when its turn comes.
 */
internal fun AppData.withChoices(choices: Map<String, SelectionQueue.Choice>): AppData {
    if (choices.isEmpty()) return this
    val main = choices.values.lastOrNull { it.group.isEmpty() && it.profileId == activeProfileId }
    return copy(
        selectedId = main?.value ?: selectedId,
        profiles = profiles.map { it.withChoices(choices) },
    )
}
