// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class ImportFormState(initial: ImportDraft) {
    var content by mutableStateOf(initial.content)
    var subscription by mutableStateOf(initial.subscription)
    var name by mutableStateOf("")
    var nameEdited = false
    var review by mutableStateOf(false)
    var visible by mutableStateOf(false)
    var agent by mutableStateOf(DEFAULT_SUBSCRIPTION_UA)
    var error by mutableStateOf<String?>(null)

    fun edit(value: String) {
        if (value.length > IMPORT_TEXT_LIMIT || value.toByteArray().size > IMPORT_TEXT_LIMIT) {
            error = tr(R.string.ui_import_too_large)
        } else {
            content = value
            error = null
        }
    }

    fun next(onSubmit: (String, String, Boolean) -> Unit) {
        error =
            when {
                content.isBlank() -> tr(R.string.ui_input_required)
                subscription && subscriptionInput(content) == null ->
                    tr(R.string.enter_a_complete_https_subscription_link_w)
                else -> null
            }
        if (error == null) {
            if (review) onSubmit(name.trim(), content, subscription) else review = true
        }
    }
}
