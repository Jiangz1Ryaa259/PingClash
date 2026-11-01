package dev.pingclash.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pingclash.diagnostics.Models
import dev.pingclash.diagnostics.ReportFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanaryPrivacyTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun automaticMessagesReportsAndEncryptedStorageDoNotExposeCanaryCredentials() {
        val app = compose.activity.application as PingClashApplication
        val model = AppViewModel(app)
        val source = "https://example.invalid/?token=CANARY-SUB-TOKEN-1234"
        val text =
            """
            proxies:
              - name: fixture
                type: anytls
                server: example.invalid
                port: 443
                password: CANARY-PASSWORD-1234
              - name: fixture-vless
                type: vless
                server: example.invalid
                port: 443
                tls: true
                uuid: 00000000-0000-4000-8000-000000000001
            """
                .trimIndent()
        val profile =
            app.repository.importProfile(
                "Synthetic privacy",
                text,
                source,
                userAgent = "CANARY-UA-1234",
            )
        try {
            val failures =
                listOf(
                    Exception(source),
                    java.net.UnknownHostException(source),
                    java.net.SocketTimeoutException("CANARY-PASSWORD-1234"),
                    javax.net.ssl.SSLException("CANARY-PASSWORD-1234"),
                    IllegalArgumentException("00000000-0000-4000-8000-000000000001"),
                    providerFailure(
                        org.json
                            .JSONObject()
                            .put("index", 1)
                            .put("kind", source)
                            .put("code", source)
                    ),
                )
            failures.forEach { assertNoSecrets(model.publicFailure(it)) }
            assertNoSecrets(ProviderFailure(1, "proxy", "provider_network").publicMessage())
            val report =
                Models.Report(
                    "CANARY-ID-1234",
                    "CANARY-TIME-1234",
                    source,
                    listOf(
                        Models.Step(
                            Models.Stage.PROXY,
                            Models.State.FAILED,
                            Models.Code.CORE_UNAVAILABLE,
                            1,
                            source,
                            0,
                        )
                    ),
                    1,
                )
            assertNoSecrets(ReportFormatter.markdown(report))
            assertNoSecrets(importFieldSummary(profile.fieldIssues))
            val disk =
                java.io.File(app.filesDir, "profiles.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertNoSecrets(disk)
            val exported = configurationExport(profile)
            assertFalse(exported.contains("CANARY-SUB-TOKEN"))
            assertFalse(exported.contains("CANARY-UA"))
            // Full configuration export is deliberately credential-bearing and
            // gated by the UI warning. Never mistake it for a diagnostic report.
            assertTrue(exported.contains("CANARY-PASSWORD-1234"))
            assertTrue(exported.contains("00000000-0000-4000-8000-000000000001"))
            val process =
                ProcessBuilder("logcat", "-d", "-t", "500", "--uid=" + android.os.Process.myUid())
                    .start()
            val logs = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertNoSecrets(logs)
        } finally {
            app.repository.delete(profile.id)
        }
    }

    private fun assertNoSecrets(output: String) {
        assertFalse("Synthetic credential escaped an automatic output", output.contains("CANARY-"))
        assertFalse(
            "Synthetic UUID escaped an automatic output",
            output.contains("00000000-0000-4000-8000-000000000001"),
        )
    }
}
