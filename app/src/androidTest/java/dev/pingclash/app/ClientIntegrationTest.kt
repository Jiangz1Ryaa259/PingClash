package dev.pingclash.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.pingclash.diagnostics.ReportFormatter
import dev.pingclash.mobile.Mobile
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientIntegrationTest {
    @get:org.junit.Rule(order = 0) val resourceLocale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun tcpAcceptors(): Set<String> {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val tables = device.executeShellCommand("cat /proc/net/tcp /proc/net/tcp6")
        assertTrue("Test shell cannot inspect socket table", tables.contains("local_address"))
        return tables
            .lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter {
                it.size > 7 && it[3] == "0A" && it[7] == android.os.Process.myUid().toString()
            }
            .map { it[1] }
            .toSet()
    }

    private fun verifyLiveOwner(app: PingClashApplication) {
        val ownerLoopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, ownerLoopback).use { ownerServer ->
            Socket().use { owned ->
                owned.bind(java.net.InetSocketAddress(ownerLoopback, 0))
                assertTrue(requireNotNull(PingVpnService.instance).protect(owned))
                owned.connect(java.net.InetSocketAddress(ownerLoopback, ownerServer.localPort))
                ownerServer.accept().use {
                    val owner =
                        AndroidCorePlatform(app)
                            .connectionOwner(
                                android.system.OsConstants.IPPROTO_TCP.toLong(),
                                "127.0.0.1:${owned.localPort}",
                                "127.0.0.1:${ownerServer.localPort}",
                            )
                            .split('\n')
                    assertEquals(android.os.Process.myUid().toString(), owner.first())
                    assertEquals(app.packageName, owner.getOrNull(1))
                }
            }
        }
    }

    @Test
    fun importEncryptProbeEstablishVPNForwardSeparateUIDAndStop() {
        val app = compose.activity.application as PingClashApplication
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("Native Android library did not load", app.nativeReady)
        assertTrue(
            "Ordinary page blocks screenshots",
            compose.activity.window.attributes.flags and
                android.view.WindowManager.LayoutParams.FLAG_SECURE == 0,
        )
        assertTrue(JSONObject(Mobile.info()).getBoolean("vpnAvailable"))
        ControlledServers().use { fixture ->
            val baselineAcceptors = tcpAcceptors()
            assertTrue("Socket inspection did not find the fixture", baselineAcceptors.isNotEmpty())
            assertTrue(
                "A test fixture bound outside loopback",
                baselineAcceptors.all {
                    it.substringBefore(":") in
                        setOf(
                            "0100007F",
                            "00000000000000000000000001000000",
                            "0000000000000000FFFF00000100007F",
                        )
                },
            )
            val name = "本地端到端测试"
            val config =
                JSONObject()
                    .put("type", "socks5")
                    .put("name", "本地 SOCKS5")
                    .put("server", "127.0.0.1")
                    .put("port", fixture.socks.localPort)
                    .put("username", "fixture-user")
                    .put("password", "fixture-private-password")
                    .toString()
            compose.onNodeWithTag("nav-2").performClick()
            compose.onNodeWithTag("profile-add").performClick()
            compose.onNodeWithTag("import-method-url").performClick()
            compose.onNodeWithText("节点配置").performClick()
            compose.onNodeWithText("名称（可选）").performTextInput(name)
            compose.onNodeWithText("节点 JSON / Clash YAML").performTextInput(config)
            hideFixtureKeyboard() // Dismiss only IME before scrolling on small displays.
            compose.onNodeWithText("下一步").performScrollTo().performClick()
            compose.onNodeWithText("导入", useUnmergedTree = false).performScrollTo().performClick()
            compose.waitUntil(10_000) { app.repository.data.value.nodes.isNotEmpty() }
            // Repository commit may precede the import Activity closing and the
            // MainActivity Compose root becoming available again.
            compose.waitUntil(5000) {
                var focused = false
                compose.activityRule.scenario.onActivity { focused = it.hasWindowFocus() }
                runCatching {
                        focused &&
                            compose.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty()
                    }
                    .getOrDefault(false)
            }
            compose.onNodeWithTag("issue-popup").assertDoesNotExist()
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("本地 SOCKS5").assertIsDisplayed()
            captureSyntheticFixture(compose, device, "nodes-light.png")
            val encrypted =
                File(app.filesDir, "profiles.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(
                "Plaintext credential reached disk",
                encrypted.contains("fixture-private-password"),
            )
            assertEquals(
                app.repository.data.value.selected?.config,
                ProfileRepository(app).data.value.selected?.config,
            )
            app.repository.settings(
                "127.0.0.1:${fixture.dns.localPort}",
                "https://${fixture.host}/",
            )

            val directProbe =
                JSONObject(
                    Mobile.probe(
                        config,
                        "http://127.0.0.1:${fixture.http.localPort}/",
                        UUID.randomUUID().toString(),
                        4000,
                    )
                )
            assertEquals("proxy_request_verified", directProbe.getString("code"))
            assertEquals(204, directProbe.getInt("httpStatus"))

            device.executeShellCommand(
                "pm grant dev.pingclash.app android.permission.POST_NOTIFICATIONS"
            )
            compose.onNodeWithTag("nav-0").performClick()
            compose.onNodeWithTag("vpn-power").performClick()
            approveSyntheticVpn(device, compose)
            compose.waitUntil(20_000) {
                VpnRuntime.state.value.phase in listOf("established", "error")
            }
            assertEquals(
                VpnRuntime.state.value.message,
                "established",
                VpnRuntime.state.value.phase,
            )
            assertEquals(
                "Starting VPN introduced a TCP listener",
                baselineAcceptors,
                tcpAcceptors(),
            )
            assertTrue(
                "VPN foreground notification missing",
                app.getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications
                    .any {
                        it.id == 1 &&
                            it.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT !=
                                0
                    },
            )
            compose.waitForIdle()
            compose
                .onNodeWithTag("vpn-power")
                .assert(
                    SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                        vpnControlDescription("established"),
                    )
                )
            assertEquals(
                180f,
                compose
                    .onNodeWithTag("lanyard-stage")
                    .fetchSemanticsNode()
                    .config[LanyardFaceAngle],
                .1f,
            )
            compose.onNodeWithTag("vpn-power").performTouchInput {
                down(center)
                advanceEventTime(800)
                moveBy(androidx.compose.ui.geometry.Offset(60f, 0f), 160)
                cancel()
            }
            assertTrue("Dragging the connected card stopped VPN", VpnRuntime.state.value.active)
            captureSyntheticFixture(compose, device, "connection-light.png")
            device.waitForIdle(2000)

            val completed = CountDownLatch(1)
            var peerResult: Bundle? = null
            val receiver =
                object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        peerResult = resultData
                        completed.countDown()
                    }
                }
            app.startActivity(
                Intent()
                    .setComponent(
                        ComponentName("dev.pingclash.app.test", "dev.pingclash.test.PeerActivity")
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("host", fixture.host)
                    .putExtra("receiver", receiver)
            )
            assertTrue("Separate-UID peer did not finish", completed.await(25, TimeUnit.SECONDS))
            assertTrue(
                "Separate-UID DNS/TCP VPN path failed at ${peerResult?.getString("stage")}",
                peerResult?.getBoolean("success") == true,
            )
            assertTrue(
                "Another UID could control the VPN service",
                peerResult?.getBoolean("serviceDenied") == true,
            )
            assertTrue(
                "Another UID could read private profiles",
                peerResult?.getBoolean("privateFileDenied") == true,
            )
            assertTrue("Peer did not traverse selected node", fixture.intercepted.get() > 0)
            assertTrue("DNS did not traverse selected node", fixture.dnsRequests.get() > 0)
            compose.waitUntil(5000) { JSONObject(Mobile.vpnStatus()).optLong("downloaded") > 0 }

            verifyLiveOwner(app)
            // The peer Activity runs in another task/UID. Returning to the app is
            // explicit, just as a user returns from a browser; its task need not be
            // foreground merely because the peer delivered its result.
            compose.runOnUiThread {
                compose.activity.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
            }
            compose.waitUntil(5000) { compose.activity.hasWindowFocus() }

            val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            model.diagnose()
            compose.waitUntil(20_000) { model.diagnosis.value.report != null }
            val report = requireNotNull(model.diagnosis.value.report)
            val markdown = ReportFormatter.markdown(report)
            assertFalse(markdown.contains("fixture-private-password"))
            assertFalse(markdown.contains("127.0.0.1"))
            assertFalse(markdown.contains(fixture.host))
            assertTrue(
                report.steps.any { it.stage == dev.pingclash.diagnostics.Models.Stage.PROXY }
            )
            compose.onNodeWithTag("nav-3").performClick()
            visibleSettingsControl(compose, "tool-diagnosis").performClick()
            val previewControl = compose.onNodeWithText("预览脱敏报告").performScrollTo()
            val navTop =
                compose.onNodeWithTag("glass-navigation").fetchSemanticsNode().boundsInRoot.top
            val distance =
                previewControl.fetchSemanticsNode().boundsInRoot.bottom - navTop +
                    16 * compose.activity.resources.displayMetrics.density
            if (distance > 0)
                compose
                    .onNode(hasScrollAction() and hasAnyDescendant(hasText("预览脱敏报告")))
                    .performSemanticsAction(
                        androidx.compose.ui.semantics.SemanticsActions.ScrollBy
                    ) {
                        it(0f, distance)
                    }
            compose.waitForIdle()
            assertTrue(
                "Report preview control remains behind navigation",
                previewControl.fetchSemanticsNode().boundsInRoot.bottom <= navTop,
            )
            previewControl.performClick()
            compose.onNodeWithText("脱敏报告预览").assertIsDisplayed()
            device.waitForIdle(2000)
            compose.onNodeWithText("保存 .md").performClick()
            device.setOrientationLeft()
            device.waitForIdle(2000)
            val saveButton =
                device.wait(
                    Until.findObject(By.res("com.android.documentsui", "button_save")),
                    5000,
                )
                    ?: device.wait(
                        Until.findObject(
                            By.text(java.util.regex.Pattern.compile("(?i)^save$|^保存$"))
                        ),
                        3000,
                    )
            assertNotNull("System document save button missing", saveButton)
            saveButton!!.click()
            assertTrue(
                "System file picker did not complete saving",
                device.wait(Until.gone(By.pkg("com.android.documentsui")), 8000),
            )
            device.setOrientationNatural()
            device.waitForIdle(2000)
            val filename = "PingClash-${report.runId.take(8)}.md"
            compose.waitUntil(5000) {
                device.executeShellCommand("cat /sdcard/Download/$filename").contains("# PingClash")
            }
            val exported = device.executeShellCommand("cat /sdcard/Download/$filename")
            assertFalse("Export leaked a credential", exported.contains("fixture-private-password"))
            device.pressBack()

            compose.onNodeWithTag("nav-0").performClick()
            compose.onNodeWithTag("vpn-power").performClick()
            compose.waitUntil(10_000) { !VpnRuntime.state.value.active }
            compose.waitForIdle()
            assertEquals(
                0f,
                compose
                    .onNodeWithTag("lanyard-stage")
                    .fetchSemanticsNode()
                    .config[LanyardFaceAngle],
                .1f,
            )
            assertFalse(JSONObject(Mobile.vpnStatus()).getBoolean("running"))
            Mobile.stopVPN()
            Mobile.stopVPN()
            VpnRuntime.connect(app)
            compose.waitUntil(10_000) {
                VpnRuntime.state.value.phase in listOf("established", "error")
            }
            assertEquals("established", VpnRuntime.state.value.phase)
            PingVpnService.instance?.onRevoke()
            compose.waitUntil(10_000) { !VpnRuntime.state.value.active }
            assertFalse(
                "Revocation did not clean up native TUN",
                JSONObject(Mobile.vpnStatus()).getBoolean("running"),
            )
            assertTrue(VpnRuntime.state.value.message.contains("撤销"))
            val revoked = VpnRuntime.state.value.message
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("vpn-error-badge").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("vpn-error-badge").assertIsDisplayed().performClick()
            compose.onNodeWithText(revoked).assertIsDisplayed()
            compose.onNodeWithText(tr(R.string.close)).performClick()
            // Revocation is intentionally a sticky warning now. End this
            // synthetic scenario before another Activity/test starts.
            VpnRuntime.publish(VpnUiState())
            device.unfreezeRotation()
        }
    }
}

/** Only whitelisted loopback services are reachable; no real subscription is used. */
internal class ControlledServers(
    private val extraMappings: Map<Pair<String, Int>, Int> = emptyMap(),
    private val firstHandshakeDelay: Long = 0,
) : Closeable {
    val handshakes = AtomicInteger()
    private val loopback = InetAddress.getByName("127.0.0.1")
    val http = ServerSocket(0, 16, loopback)
    val dns = ServerSocket(0, 16, loopback)
    val socks = ServerSocket(0, 16, loopback)
    val host = "peer-${UUID.randomUUID()}.fixture.test"
    val intercepted = AtomicInteger()
    val dnsRequests = AtomicInteger()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val workers =
        Executors.newCachedThreadPool { task ->
            Thread(task, "controlled-test-server").apply { isDaemon = true }
        }
    @Volatile private var closed = false

    init {
        accept(http) { socket ->
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            while (!input.readLine().isNullOrEmpty()) {
                /* Consume bounded test headers. */
            }
            socket
                .getOutputStream()
                .write(
                    "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
        }
        accept(dns) { socket ->
            val input = DataInputStream(socket.getInputStream())
            val query = ByteArray(input.readUnsignedShort())
            input.readFully(query)
            var end = 12
            val labels = mutableListOf<String>()
            while (end < query.size && query[end].toInt() != 0) {
                val length = query[end].toInt() and 255
                require(length in 1..63 && end + length + 1 < query.size)
                labels += query.copyOfRange(end + 1, end + 1 + length).toString(Charsets.US_ASCII)
                end += 1 + length
            }
            end += 5
            require(end <= query.size)
            val accepted = labels.joinToString(".") == host
            val isA = query[end - 4].toInt() == 0 && query[end - 3].toInt() == 1
            if (accepted) dnsRequests.incrementAndGet()
            val answer = java.io.ByteArrayOutputStream()
            val out = java.io.DataOutputStream(answer)
            out.write(query, 0, 2)
            out.writeShort(if (accepted) 0x8180 else 0x8183)
            out.writeShort(1)
            out.writeShort(if (accepted && isA) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.write(query, 12, end - 12)
            if (accepted && isA) {
                out.writeShort(0xc00c)
                out.writeShort(1)
                out.writeShort(1)
                out.writeInt(60)
                out.writeShort(4)
                out.write(byteArrayOf(192.toByte(), 0, 2, 123))
            }
            val payload = answer.toByteArray()
            java.io.DataOutputStream(socket.getOutputStream()).apply {
                writeShort(payload.size)
                write(payload)
                flush()
            }
        }
        accept(socks) { socket ->
            if (handshakes.incrementAndGet() == 1 && firstHandshakeDelay > 0)
                Thread.sleep(firstHandshakeDelay)
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            require(input.readUnsignedByte() == 5)
            val methods = ByteArray(input.readUnsignedByte())
            input.readFully(methods)
            require(methods.contains(2.toByte()))
            output.write(byteArrayOf(5, 2))
            output.flush()
            require(input.readUnsignedByte() == 1)
            val user = ByteArray(input.readUnsignedByte())
            input.readFully(user)
            val password = ByteArray(input.readUnsignedByte())
            input.readFully(password)
            require(
                user.toString(Charsets.UTF_8) == "fixture-user" &&
                    password.toString(Charsets.UTF_8) == "fixture-private-password"
            )
            output.write(byteArrayOf(1, 0))
            output.flush()
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
            input.readUnsignedByte()
            val address =
                when (input.readUnsignedByte()) {
                    1 ->
                        ByteArray(4).also(input::readFully).let {
                            InetAddress.getByAddress(it).hostAddress
                        }
                    4 ->
                        ByteArray(16).also(input::readFully).let {
                            InetAddress.getByAddress(it).hostAddress
                        }
                    3 ->
                        ByteArray(input.readUnsignedByte())
                            .also(input::readFully)
                            .toString(Charsets.US_ASCII)
                    else -> error("address_invalid")
                }
            val port = input.readUnsignedShort()
            val mapped =
                when {
                    address == "127.0.0.1" && port in listOf(http.localPort, dns.localPort) -> port
                    address in listOf("192.0.2.123", host) && port == 8080 -> {
                        intercepted.incrementAndGet()
                        http.localPort
                    }
                    address to port in extraMappings -> {
                        intercepted.incrementAndGet()
                        extraMappings.getValue(address to port)
                    }
                    else -> {
                        output.write(byteArrayOf(5, 2, 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        return@accept
                    }
                }
            Socket(loopback, mapped).use { remote ->
                sockets += remote
                remote.soTimeout = 12_000
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                output.flush()
                val copied = CountDownLatch(1)
                workers.execute {
                    try {
                        input.copyTo(remote.getOutputStream())
                    } catch (_: Exception) {} finally {
                        copied.countDown()
                    }
                }
                remote.getInputStream().copyTo(output)
                socket.close()
                copied.await(2, TimeUnit.SECONDS)
                sockets.remove(remote)
            }
        }
    }

    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) {
        workers.execute {
            while (!closed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        return@execute
                    }
                sockets += socket
                workers.execute {
                    try {
                        socket.use {
                            it.soTimeout = 12_000
                            handler(it)
                        }
                    } catch (_: Exception) {} finally {
                        sockets.remove(socket)
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        if (VpnRuntime.state.value.active) PingVpnService.instance?.onRevoke()
        listOf(http, dns, socks).forEach { runCatching { it.close() } }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
