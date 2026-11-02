package dev.pingclash.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.mobile.Mobile
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkReconnectionTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun networkLossKeepsTunAndForegroundThenResumesWithoutNewPermission() {
        val app = compose.activity.application as PingClashApplication
        val repo = app.repository
        val before = repo.data.value
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        assumeTrue(
            "This AVD has no controllable Wi-Fi/cellular transport",
            capabilities?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } == true,
        )
        val wifi = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        val mobile = device.executeShellCommand("settings get global mobile_data").trim() != "0"
        ControlledServers().use { fixture ->
            val config =
                JSONObject()
                    .put("type", "socks5")
                    .put("name", "network-fixture")
                    .put("server", "127.0.0.1")
                    .put("port", fixture.socks.localPort)
                    .put("username", "fixture-user")
                    .put("password", "fixture-private-password")
                    .toString()
            val profile = repo.importProfile("Synthetic network test", config)
            try {
                repo.settings("127.0.0.1:${fixture.dns.localPort}", "https://${fixture.host}/")
                // The app asks for this before connecting on Android 13+; without it the
                // system keeps the foreground-service notification out of the active list.
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    device.executeShellCommand(
                        "pm grant dev.pingclash.app android.permission.POST_NOTIFICATIONS"
                    )
                val permission = VpnService.prepare(app)
                if (permission != null) {
                    compose.runOnUiThread { compose.activity.startActivity(permission) }
                    approveSyntheticVpn(device, compose)
                }
                VpnRuntime.connect(app)
                compose.waitUntil(20000) {
                    VpnRuntime.state.value.phase in listOf("established", "error")
                }
                assertEquals(
                    VpnRuntime.state.value.message,
                    "established",
                    VpnRuntime.state.value.phase,
                )
                val sessionStart = VpnRuntime.state.value.sessionStartedElapsed
                assertTrue(sessionStart >= 0)
                val service = requireNotNull(PingVpnService.instance)
                val descriptor =
                    PingVpnService::class.java.getDeclaredField("descriptor").apply {
                        isAccessible = true
                    }
                val tun = descriptor.get(service) as ParcelFileDescriptor
                val activityInfo =
                    app.packageManager.getActivityInfo(compose.activity.componentName, 0)
                assertEquals(
                    "Dashboard excluded from recents",
                    0,
                    activityInfo.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS,
                )
                device.pressHome()
                val dozeEnabled =
                    device.executeShellCommand("dumpsys deviceidle enabled").trim() == "1"
                try {
                    device.executeShellCommand("dumpsys deviceidle enable")
                    device.executeShellCommand("dumpsys battery unplug")
                    device.executeShellCommand("dumpsys deviceidle force-idle")
                    assertTrue(
                        "Doze was not entered",
                        device.executeShellCommand("dumpsys deviceidle get deep").contains("IDLE"),
                    )
                    assertTrue("Doze closed TUN", tun.fileDescriptor.valid())
                    assertTrue(
                        "Doze removed foreground notification",
                        app.getSystemService(android.app.NotificationManager::class.java)
                            .activeNotifications
                            .any { it.id == 1 },
                    )
                } finally {
                    device.executeShellCommand("dumpsys deviceidle unforce")
                    device.executeShellCommand("dumpsys battery reset")
                    if (!dozeEnabled) device.executeShellCommand("dumpsys deviceidle disable")
                    device.executeShellCommand("input keyevent 224")
                    device.executeShellCommand("input keyevent 82")
                }
                device.executeShellCommand("svc wifi disable")
                device.executeShellCommand("svc data disable")
                compose.waitUntil(25000) {
                    service.boundNetwork == null && VpnRuntime.state.value.phase == "connecting"
                }
                assertSame("TUN was replaced during outage", tun, descriptor.get(service))
                assertTrue("TUN was closed during outage", tun.fileDescriptor.valid())
                assertFalse(JSONObject(Mobile.vpnStatus()).optBoolean("running"))
                assertTrue(
                    app.getSystemService(android.app.NotificationManager::class.java)
                        .activeNotifications
                        .any { it.id == 1 }
                )
                if (mobile) device.executeShellCommand("svc data enable")
                if (wifi) device.executeShellCommand("svc wifi enable")
                compose.waitUntil(30000) {
                    service.boundNetwork != null && VpnRuntime.state.value.phase == "established"
                }
                assertSame("Handoff closed the original TUN", tun, descriptor.get(service))
                assertEquals(
                    "Handoff reset the session timer",
                    sessionStart,
                    VpnRuntime.state.value.sessionStartedElapsed,
                )
                assertTrue(VpnRuntime.state.value.uptime > 0)
                assertNull("Handoff required new VPN authorization", VpnService.prepare(app))
                val reply =
                    JSONObject(
                        Mobile.probe(
                            config,
                            "http://127.0.0.1:${fixture.http.localPort}/",
                            UUID.randomUUID().toString(),
                            4000,
                        )
                    )
                assertEquals("proxy_request_verified", reply.optString("code"))
            } finally {
                device.executeShellCommand("svc wifi " + if (wifi) "enable" else "disable")
                device.executeShellCommand("svc data " + if (mobile) "enable" else "disable")
                VpnRuntime.disconnect(app)
                compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                repo.delete(profile.id)
                repo.settings(before.dnsAddress, before.probeUrl)
                app.startActivity(
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
