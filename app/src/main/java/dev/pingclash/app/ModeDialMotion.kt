// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val ModeDetents = listOf(-68f, 0f, 68f)

/** One animation owner. Reconciliation never requests a routing change. */
@Stable
internal class ModeDialMotion(initial: Int, private val scope: CoroutineScope) {
    val angle = Animatable(ModeDetents[initial])
    var dragging by mutableStateOf(false)
        private set

    var revision by mutableIntStateOf(0)
        private set

    var settlements by mutableIntStateOf(0)
        private set

    private var animation: Job? = null
    private var destination: Float? = null

    fun drag(value: Float) {
        dragging = true
        destination = null
        animation?.cancel()
        animation = scope.launch { angle.snapTo(value) }
    }

    fun release() {
        dragging = false
        revision++
    }

    fun reconcile(actual: Int, motion: Boolean) {
        val target = ModeDetents[actual]
        if (motion && destination == target && animation?.isActive == true) return
        if (angle.value == target && !angle.isRunning) return
        val velocity = angle.velocity
        animation?.cancel()
        destination = target
        settlements++
        animation =
            scope.launch {
                if (motion) angle.animateTo(target, spring(.5f, 420f), initialVelocity = velocity)
                else angle.snapTo(target)
            }
    }
}

internal fun modeFeedback(view: android.view.View) {
    view.performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.view.HapticFeedbackConstants.CONFIRM
        else android.view.HapticFeedbackConstants.VIRTUAL_KEY
    )
}
