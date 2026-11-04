// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pingclash.diagnostics.RunControl
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** TLS/key material is synthetic, loopback-only and absent from the production APK. */
internal class WebsiteTlsFixture : Closeable {
    val requests = AtomicInteger()
    val accepted = AtomicInteger()
    val handshakes = AtomicInteger()
    @Volatile var status = 204
    @Volatile var firstDelay = 0L
    @Volatile var responseDelay = 0L
    @Volatile var body = ""
    @Volatile var location: String? = null
    @Volatile var headStatus: Int? = null
    val methods = CopyOnWriteArrayList<String>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val workers = Executors.newCachedThreadPool()
    private val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
    private val server: java.net.ServerSocket
    val port
        get() = server.localPort

    fun target() = ServiceTarget("fixture", "Fixture", "https://127.0.0.1:$port/")

    init {
        val encoded =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("website-fixture.base64")
                .use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val key =
            java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(
                    java.security.spec.PKCS8EncodedKeySpec(
                        Base64.decode(encoded.getString("fixturePrivateDer"), Base64.DEFAULT)
                    )
                )
        val certificate =
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    Base64.decode(encoded.getString("certificate"), Base64.DEFAULT).inputStream()
                )
        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(
                    "fixture",
                    key,
                    "public-test-password".toCharArray(),
                    arrayOf(certificate),
                )
            }
        val keys =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "public-test-password".toCharArray())
            }
        val trust =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(store)
            }
        val tls =
            SSLContext.getInstance("TLS").apply {
                init(keys.keyManagers, trust.trustManagers, null)
            }
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        server =
            tls.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        workers.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                clients.add(client)
                workers.execute {
                    runCatching {
                        client.use {
                            it.soTimeout = 10000
                            Thread.sleep(responseDelay)
                            (it as javax.net.ssl.SSLSocket).startHandshake()
                            handshakes.incrementAndGet()
                            val reader = it.getInputStream().bufferedReader()
                            while (true) {
                                val first = reader.readLine() ?: break
                                val method = first.substringBefore(' ')
                                require(method in setOf("HEAD", "GET"))
                                methods.add(method)
                                var bytes = first.length
                                while (true) {
                                    val line = reader.readLine() ?: error("test_eof")
                                    bytes += line.length
                                    require(bytes < 16384)
                                    if (line.isEmpty()) break
                                }
                                val count = requests.incrementAndGet()
                                Thread.sleep(if (count == 1) firstDelay else responseDelay)
                                val replyStatus =
                                    if (method == "HEAD") headStatus ?: status else status
                                val bytesBody =
                                    if (replyStatus == 204) byteArrayOf() else body.toByteArray()
                                val redirect =
                                    location?.let { value -> "Location: $value\r\n" }.orEmpty()
                                it.getOutputStream()
                                    .write(
                                        ("HTTP/1.1 $replyStatus Fixture\r\n" +
                                                redirect +
                                                "Content-Length: ${bytesBody.size}\r\nConnection: keep-alive\r\n\r\n")
                                            .toByteArray()
                                    )
                                if (method == "GET") it.getOutputStream().write(bytesBody)
                                it.getOutputStream().flush()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        HttpsURLConnection.setDefaultSSLSocketFactory(previous)
        server.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}

@RunWith(AndroidJUnit4::class)
class WebsiteRoutingTest {
    @get:Rule(order = 0) val locale = ResourceLocaleRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app
        get() = compose.activity.application as PingClashApplication

    @Test
    fun anyHttpAnswerOverVerifiedTlsMeansThisNetworkReachesTheSite() {
        assertFalse(VpnRuntime.state.value.active)
        // A bot wall, a rate limit or a server error is still an answer from the site.
        for (status in listOf(403, 429, 503)) {
            WebsiteTlsFixture().use { fixture ->
                fixture.status = status
                fixture.headStatus = 204
                RunControl(3000).use { control ->
                    val network =
                        app.getSystemService(ConnectivityManager::class.java).activeNetwork!!
                    val result = WebsiteProbe.run(fixture.target(), 2000, control, network)
                    assertTrue("$status: $result", result is Latency.Measured)
                    assertTrue(fixture.methods.contains("GET"))
                }
            }
        }
    }

    @Test
    fun normalGetWorksWhenHeadIsUnsupportedAndWarmupDoesNotBecomeLatency() {
        WebsiteTlsFixture().use { fixture ->
            fixture.headStatus = 405
            fixture.status = 200
            fixture.body = "Public website response"
            RunControl(3000).use { control ->
                val network = app.getSystemService(ConnectivityManager::class.java).activeNetwork!!
                val target = fixture.target().copy(id = "bilibili")
                assertTrue(WebsiteProbe.run(target, 2000, control, network) is Latency.Measured)
                assertTrue(fixture.methods.contains("GET"))
            }
        }
    }

    @Test
    fun onlyAnExplicitRegionRefusalIsNotReachable() {
        val google = serviceTargets.first { it.id == "google" }
        assertEquals("www.google.com", java.net.URL(google.url).host)
        for (status in listOf(200, 204, 302, 403, 429, 500)) {
            assertEquals(null, websiteResponseFailure(google, WebsiteReply(status)))
        }
        val claude = serviceTargets.first { it.id == "claude" }
        // A browser passes a challenge page, so the way to the site is open.
        assertEquals(
            null,
            websiteResponseFailure(
                claude,
                WebsiteReply(403, preview = "<title>Just a moment...</title>"),
            ),
        )
        // A redirect to sign-in or another domain is an answer, not a failure.
        assertEquals(
            null,
            websiteResponseFailure(claude, WebsiteReply(302, "https://login.example.invalid/")),
        )
        assertEquals(
            WebsiteFailure.RESTRICTED,
            websiteResponseFailure(
                claude,
                WebsiteReply(302, "https://www.anthropic.com/app-unavailable-in-region"),
            ),
        )
        assertEquals(
            WebsiteFailure.RESTRICTED,
            websiteResponseFailure(
                claude,
                WebsiteReply(403, preview = "Claude is only available in certain regions"),
            ),
        )
        assertEquals(WebsiteFailure.RESTRICTED, websiteResponseFailure(google, WebsiteReply(451)))
        // Region wording on an ordinary site means nothing.
        val bilibili = serviceTargets.first { it.id == "bilibili" }
        assertEquals(
            null,
            websiteResponseFailure(bilibili, WebsiteReply(200, preview = "unsupported country")),
        )
        assertFalse(
            WebsiteReply(403, "CANARY-LOCATION", "CANARY-BODY").toString().contains("CANARY")
        )
        val input = java.io.ByteArrayInputStream(ByteArray(10000))
        assertEquals(4096, readWebsitePreview(input).size)
        assertEquals(5904, input.available())
    }

    @Test
    fun aRedirectIsTheAnswerAndIsNeverFollowed() {
        WebsiteTlsFixture().use { fixture ->
            fixture.status = 302
            fixture.location = "http://127.0.0.1:${fixture.port}/elsewhere"
            RunControl(3000).use { control ->
                val network = app.getSystemService(ConnectivityManager::class.java).activeNetwork!!
                val result = WebsiteProbe.run(fixture.target(), 2000, control, network)
                assertTrue(result.toString(), result is Latency.Measured)
                // The warm-up and the measured request; the cleartext target is never fetched.
                assertEquals(2, fixture.requests.get())
            }
        }
    }

    @Test
    fun aFailedWebsiteCanRetryWhileAnotherWebsiteIsStillRunning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WebsiteTlsFixture().use { fast ->
            WebsiteTlsFixture().use { slow ->
                fast.headStatus = 204
                // A refusal of this location: still a failure under the reachability verdict.
                fast.status = 451
                slow.firstDelay = 1400
                val targets =
                    listOf(fast.target().copy(id = "fast"), slow.target().copy(id = "slow"))
                val checks = ServiceChecks(scope, app)
                try {
                    checks.start(4000, listOf("fast", "slow"), targets)
                    compose.waitUntil(5000) { checks.state.value.values["fast"] is Latency.Failed }
                    assertTrue(checks.state.value.running)
                    fast.status = 204
                    checks.start(4000, listOf("fast"), targets)
                    compose.waitUntil(5000) {
                        checks.state.value.values["fast"] is Latency.Measured
                    }
                    assertTrue(
                        "Retry waited for the other site",
                        checks.state.value.values["slow"] == Latency.Running,
                    )
                } finally {
                    checks.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun anUnresponsiveSiteCostsOneTimeoutAndARefusedOneEndsAtWarmUp() {
        val network = app.getSystemService(ConnectivityManager::class.java).activeNetwork!!
        WebsiteTlsFixture().use { fixture ->
            fixture.responseDelay = 10000
            RunControl(8000).use { control ->
                val begun = System.nanoTime()
                val result = WebsiteProbe.run(fixture.target(), 3000, control, network)
                val waited = (System.nanoTime() - begun) / 1_000_000
                assertEquals(WebsiteFailure.TIMEOUT, (result as Latency.Failed).reason)
                // Warm-up (1 s) plus a full second timeout used to take about 4 s.
                assertTrue("waited $waited ms", waited < 3600)
            }
        }
        val closed =
            WebsiteTlsFixture().let { fixture -> fixture.target().also { fixture.close() } }
        RunControl(8000).use { control ->
            val result = WebsiteProbe.run(closed, 3000, control, network)
            assertEquals(WebsiteFailure.REFUSED, (result as Latency.Failed).reason)
        }
    }

    @Test
    fun untrustedTlsStillFailsAndFailureKindsDoNotInventBlockingOrLeakErrors() {
        val untrusted = HttpsURLConnection.getDefaultSSLSocketFactory()
        WebsiteTlsFixture().use { fixture ->
            HttpsURLConnection.setDefaultSSLSocketFactory(untrusted)
            RunControl(3000).use { control ->
                val network = app.getSystemService(ConnectivityManager::class.java).activeNetwork!!
                val result = WebsiteProbe.run(fixture.target(), 1000, control, network)
                assertEquals(WebsiteFailure.CERTIFICATE, (result as Latency.Failed).reason)
                assertEquals(0, fixture.requests.get())
            }
        }
        assertEquals(
            WebsiteFailure.RESET,
            classifyWebsiteFailure(SocketException("Connection reset CANARY-SUB-TOKEN-1234")),
        )
        assertEquals(
            WebsiteFailure.TIMEOUT,
            classifyWebsiteFailure(SocketTimeoutException("CANARY-PASSWORD")),
        )
        assertEquals(
            WebsiteFailure.DNS,
            classifyWebsiteFailure(java.net.UnknownHostException("CANARY-UUID")),
        )
        assertEquals(
            WebsiteFailure.CERTIFICATE,
            classifyWebsiteFailure(
                javax.net.ssl.SSLHandshakeException("tls").apply {
                    initCause(java.security.cert.CertificateException("CANARY-PASSWORD"))
                }
            ),
        )
        assertFalse(websiteFailureText(Latency.Failed(WebsiteFailure.RESET)).contains("阻断"))
        assertFalse(websiteFailureText(Latency.Failed(WebsiteFailure.RESET)).contains("CANARY"))
    }

    @Test
    fun cancelClearsResultsAndLateHttpsCannotResurrectThem() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WebsiteTlsFixture().use { fixture ->
            val checks = ServiceChecks(scope, app)
            try {
                fixture.responseDelay = 1200
                checks.start(2000, listOf("fixture"), listOf(fixture.target()))
                compose.waitUntil(5000) { fixture.accepted.get() >= 1 }
                checks.cancel()
                assertEquals(ServiceCheckState(), checks.state.value)
                Thread.sleep(1400)
                assertEquals(ServiceCheckState(), checks.state.value)
                // A fresh request after cancellation must remain independent of old cancellation.
                fixture.responseDelay = 0
                checks.start(2000, listOf("fixture"), listOf(fixture.target()))
                compose.waitUntil(5000) { !checks.state.value.running }
                assertTrue(
                    checks.state.value.toString(),
                    checks.state.value.values["fixture"] is Latency.Measured,
                )
            } finally {
                checks.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun aRouteChangeBeforeItsCallbackNeverLeavesATileTesting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork)
        // The route changes, but no network callback follows to reset the batch.
        var route = WebsiteRoute(network, false)
        WebsiteTlsFixture().use { fixture ->
            val checks = ServiceChecks(scope, app, routeOverride = { route })
            try {
                fixture.responseDelay = 1200
                checks.start(4000, listOf("fixture"), listOf(fixture.target()))
                compose.waitUntil(5000) { fixture.accepted.get() >= 1 }
                route = WebsiteRoute(network, true)
                compose.waitUntil(6000) { !checks.state.value.running }
                assertTrue(
                    checks.state.value.toString(),
                    checks.state.value.values["fixture"] != Latency.Running,
                )
                // The tile is idle again, so tapping it starts a fresh check.
                fixture.responseDelay = 0
                checks.start(4000, listOf("fixture"), listOf(fixture.target()))
                compose.waitUntil(5000) { !checks.state.value.running }
                assertTrue(checks.state.value.values["fixture"] is Latency.Measured)
            } finally {
                checks.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun websiteCannotBorrowNodeBeforeVpnButAppHttpsTraversesTunAfterConnect() {
        val repo = app.repository
        val before = repo.data.value
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        WebsiteTlsFixture().use { tls ->
            ControlledServers(mapOf(("192.0.2.123" to 443) to tls.port)).use { proxy ->
                val profile =
                    repo.importProfile(
                        "Website route fixture",
                        JSONObject()
                            .put("name", "website-route")
                            .put("type", "socks5")
                            .put("server", "127.0.0.1")
                            .put("port", proxy.socks.localPort)
                            .put("username", "fixture-user")
                            .put("password", "fixture-private-password")
                            .toString(),
                    )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val checks = ServiceChecks(scope, app)
                val target = ServiceTarget("fixture", "Fixture", "https://${proxy.host}/")
                try {
                    repo.activate(profile.id)
                    repo.clientSettings(
                        before.client.copy(appMode = "all", mode = "global"),
                        "127.0.0.1:${proxy.dns.localPort}",
                        before.probeUrl,
                    )
                    assertFalse(VpnRuntime.state.value.active)
                    checks.start(1000, listOf("fixture"), listOf(target))
                    compose.waitUntil(7000) { !checks.state.value.running }
                    assertTrue(
                        "Website borrowed selected node while VPN was off",
                        checks.state.value.values["fixture"] is Latency.Failed,
                    )
                    assertEquals(0, proxy.intercepted.get())
                    authorizeVpn(device)
                    VpnRuntime.connect(app)
                    compose.waitUntil(15000) {
                        VpnRuntime.state.value.phase in setOf("established", "error")
                    }
                    assertEquals(
                        VpnRuntime.state.value.message,
                        "established",
                        VpnRuntime.state.value.phase,
                    )
                    compose.waitUntil(10000) {
                        manager
                            .getNetworkCapabilities(manager.activeNetwork)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    }
                    compose.waitUntil(5000) { checks.state.value.values.isEmpty() }
                    checks.start(3000, listOf("fixture"), listOf(target))
                    compose.waitUntil(10000) { !checks.state.value.running }
                    assertTrue(
                        "${checks.state.value}; dns=${proxy.dnsRequests.get()}; " +
                            "relay=${proxy.intercepted.get()}; TLS=${tls.handshakes.get()}; " +
                            "HTTP=${tls.methods}",
                        checks.state.value.values["fixture"] is Latency.Measured,
                    )
                    assertTrue(
                        "App UID HTTPS did not reach SOCKS relay",
                        proxy.intercepted.get() >= 1,
                    )
                    assertTrue(
                        "Website DNS did not follow VPN resolver",
                        proxy.dnsRequests.get() >= 1,
                    )
                    assertTrue(tls.requests.get() >= 2)
                    compose.waitUntil(3000) { tls.handshakes.get() >= 1 }
                    VpnRuntime.disconnect(app)
                    compose.waitUntil(10000) {
                        !VpnRuntime.state.value.active && checks.state.value.values.isEmpty()
                    }
                } finally {
                    checks.close()
                    scope.cancel()
                    VpnRuntime.disconnect(app)
                    compose.waitUntil(10000) { !VpnRuntime.state.value.active }
                    repo.clientSettings(before.client, before.dnsAddress, before.probeUrl)
                    repo.delete(profile.id)
                }
            }
        }
    }

    private fun authorizeVpn(device: UiDevice) {
        val authorization = VpnService.prepare(app) ?: return
        compose.runOnUiThread { compose.activity.startActivityForResult(authorization, 12038) }
        approveSyntheticVpn(device, compose)
    }

    @Test
    fun independentTcpOpensThreeSocketsAndUdpRemainsNotApplicable() {
        val server = java.net.ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val count = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            while (!server.isClosed) runCatching { server.accept().use { count.incrementAndGet() } }
        }
        try {
            val node =
                LocalNode(
                    "tcp",
                    "TCP",
                    "socks5",
                    JSONObject().put("server", "127.0.0.1").put("port", server.localPort).toString(),
                )
            RunControl(3000).use { control ->
                assertEquals("passed", TcpLatencyProbe.run(app, node, 1000, control).state)
            }
            compose.waitUntil(3000) { count.get() == 3 }
            RunControl(3000).use { control ->
                assertEquals(
                    "skipped",
                    TcpLatencyProbe.run(app, node.copy(type = "hysteria2"), 1000, control).state,
                )
            }
            assertEquals(3, count.get())
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }
}
