// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView

/** Programmatic views do not inherit the focus flags from Termux's XML layout. */
internal class TerminalInputView(context: Context) : TerminalView(context, null) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        // Password variations can activate OEM secure keyboards and their
        // screenshot restrictions. This is an ordinary multiline text editor;
        // retain the PTY connection and suppress command autocorrection.
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_NORMAL or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions =
            EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }

    fun showKeyboard() {
        requestFocus()
        post {
            if (isAttachedToWindow && hasWindowFocus()) {
                context
                    .getSystemService(InputMethodManager::class.java)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    windowInsetsController?.show(android.view.WindowInsets.Type.ime())
            }
        }
    }
}
