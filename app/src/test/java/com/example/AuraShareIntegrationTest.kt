package com.example

import com.example.data.network.PairingClient
import com.example.data.network.StreamingTransferManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuraShareIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PairingClient
    @Before fun setUp() { server = MockWebServer(); server.start(); client = PairingClient() }
    @After fun tearDown() { server.shutdown() }
    private fun endpoint() = PairingClient.Endpoint("127.0.0.1", server.port)

    @Test fun handshake_requires_v2_approval_and_server_id() {
        server.enqueue(MockResponse().setBody("""{"server":"AuraShare","hostname":"Desk","protocol":2,"requires_pairing":true,"pairing_method":"approval","server_id":"boot-1"}"""))
        val info = client.handshake(endpoint())
        assertEquals(2, info.protocol); assertTrue(info.requiresPairing); assertEquals("boot-1", info.serverId)
        assertNull(server.takeRequest().getHeader("Origin"))
    }
    @Test fun request_and_approval_keep_secret_out_of_url() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"request_id":"req-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","token":"session-token","device_name":"Desk","server_id":"boot-1"}"""))
        val request = client.requestPair(endpoint(), "client-1", "Pixel")
        val approved = client.awaitApproval(endpoint(), request)
        assertEquals("req-1", request.requestId); assertEquals(64, request.secret.length); assertEquals("approved", approved.status)
        assertFalse(server.takeRequest().path!!.contains(request.secret))
    }
    @Test fun heartbeat_and_disconnect_use_session_token() {
        server.enqueue(MockResponse().setBody("""{"status":"connected"}""")); assertTrue(client.heartbeat(endpoint(), "session-token"))
        server.enqueue(MockResponse().setBody("""{"status":"disconnected"}""")); assertTrue(client.disconnect(endpoint(), "session-token"))
        val heartbeat = server.takeRequest(); assertEquals("/api/session/heartbeat", heartbeat.path)
        val request = server.takeRequest(); assertEquals("/api/session/disconnect", request.path); assertEquals("session-token", request.getHeader("X-AuraShare-Token"))
    }
    @Test(expected = IllegalArgumentException::class) fun handshake_rejects_legacy_protocol() {
        server.enqueue(MockResponse().setBody("""{"server":"AuraShare","protocol":1}""")); client.handshake(endpoint())
    }
    @Test(expected = IllegalArgumentException::class) fun endpoint_rejects_ipv6_or_hostname() {
        PairingClient.Endpoint("localhost", server.port)
    }
    @Test(expected = IllegalArgumentException::class) fun handshake_requires_server_id() {
        server.enqueue(MockResponse().setBody("""{"server":"AuraShare","hostname":"Desk","protocol":2,"requires_pairing":true,"pairing_method":"approval"}""")); client.handshake(endpoint())
    }

    @Test fun upload_redirect_does_not_forward_session_token() {
        val redirect = MockWebServer(); redirect.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirect.url("/upload")))
            val manager = StreamingTransferManager(ApplicationProvider.getApplicationContext())
            manager.httpClient.newCall(Request.Builder().url(server.url("/upload"))
                .header("X-AuraShare-Token", "must-not-leak").post("bytes".toRequestBody()).build()).execute().use { response ->
                assertEquals(302, response.code)
            }
            assertEquals(0, redirect.requestCount)
        } finally { redirect.shutdown() }
    }
}
