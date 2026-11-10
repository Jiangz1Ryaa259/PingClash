// SPDX-License-Identifier: GPL-3.0-only
package dev.pingclash.app

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/**
 * Every run starts from cleared app data, which would open on the first-run guide. Tests of the app
 * itself start past it; [SetupGuideTest] clears it again to test the guide.
 */
class PingClashTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        SetupState.complete(app)
        super.callApplicationOnCreate(app)
    }
}
