package dev.pingclash.app

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** UI fixtures have a declared locale; language-changing tests cannot leak it. */
class ResourceLocaleRule : TestRule {
    private fun reset() {
        val app =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as PingClashApplication
        // API 31 Haze intentionally invalidates on pre-draw. General Espresso
        // tests use the opaque preference; FrameGlassProbe independently tests
        // real source sampling, toggles and protected windows without Espresso.
        app.repository.appearance(
            "zh",
            app.repository.data.value.client.theme,
            scenery =
                ScenePreferenceChange(reduceTransparency = android.os.Build.VERSION.SDK_INT == 31),
        )
    }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                reset()
                try {
                    base.evaluate()
                } finally {
                    reset()
                }
            }
        }
}

/** Close input windows without sending Back to the sheet under test. */
@android.annotation.TargetApi(29)
internal fun hideFixtureKeyboard() {
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
            val input =
                view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle(1000)
}
