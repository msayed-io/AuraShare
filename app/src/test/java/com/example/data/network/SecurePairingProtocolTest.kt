package com.example.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.TlsVersion
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Protocol-level tests; camera/QR capture is deliberately outside this suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SecurePairingProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var cert: HeldCertificate
    private lateinit var storage: MemoryStorage
    private var now: Long = 0L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        now = System.currentTimeMillis()
        cert = HeldCertificate.Builder().commonName("aurashare.local")
            .addSubjectAlternativeName("aurashare.local")
            .validityInterval(now - 60_000, now + 600_000).build()
        server = tlsServer(cert)
        storage = MemoryStorage()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    @Test fun approvedPairPersistsOnlyAfterApprovalAndUsesTls13() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
        val manager = manager()
        try {
            manager.pairQr(qr())
            assertEquals(1, manager.state.value.devices.size)
            assertEquals(id(), manager.state.value.devices.single().id)
            assertNull(manager.state.value.error)
            assertNotNull(manager.state.value.connected)
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals(TlsVersion.TLS_1_3, request!!.handshake!!.tlsVersion)
        } finally { manager.close() }
    }

    @Test fun pendingThenApprovedDoesNotPersistPendingCredential() = runBlocking {
        val pendingSeen = CountDownLatch(1)
        val releasePending = CountDownLatch(1)
        var statusCalls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/secure/handshake" -> json(200, "{\"server\":\"AuraShare\",\"protocol\":3,\"server_id\":\"${id()}\"}")
                "/api/secure/pair/request" -> json(202, "{\"request_id\":\"r1\",\"request_secret\":\"${HEX_A}\"}")
                "/api/secure/pair/status/r1" -> if (statusCalls++ == 0) {
                    pendingSeen.countDown(); releasePending.await(5, TimeUnit.SECONDS)
                    json(200, "{\"status\":\"pending\"}")
                } else json(200, "{\"status\":\"approved\",\"server_id\":\"${id()}\",\"device_name\":\"Test Desktop\",\"credential\":\"${HEX_C}\"}")
                "/api/secure/session/connect" -> json(200, "{\"server_id\":\"${id()}\",\"token\":\"${HEX_T}\"}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val manager = manager()
        try {
            val before = storage.bytes!!.clone()
            val job = launch(Dispatchers.IO) { manager.pairQr(qr()) }
            assertTrue(pendingSeen.await(5, TimeUnit.SECONDS))
            assertTrue(before.contentEquals(storage.bytes!!))
            releasePending.countDown()
            job.join()
            assertEquals(1, manager.state.value.devices.size)
        } finally { manager.close(); releasePending.countDown() }
    }

    @Test fun rejectedPairLeavesTrustEmptyAndReportsStateError() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); server.enqueue(json(200, "{\"status\":\"rejected\"}"))
        val manager = manager()
        try { manager.pairQr(qr()); assertTrue(manager.state.value.devices.isEmpty()); assertNotNull(manager.state.value.error) }
        finally { manager.close() }
    }

    @Test fun expiredMalformedAndUnmatchedQrAreRejectedWithoutNetwork() = runBlocking {
        val manager = manager()
        try {
            manager.pairQr(qr(expires = now - 1)); assertNotNull(manager.state.value.error)
            val count = server.requestCount
            manager.pairQr("not-json"); assertNotNull(manager.state.value.error)
            manager.pairQr(qr(serverId = "0".repeat(64))); assertNotNull(manager.state.value.error)
            assertEquals(count, server.requestCount)
        } finally { manager.close() }
    }

    @Test fun wrongSanCertificateFailsDuringTlsAndChangedIdentityFailsBeforeNetwork() = runBlocking {
        val wrongSan = HeldCertificate.Builder().commonName("aurashare.local")
            .addSubjectAlternativeName("other.local").validityInterval(now - 60_000, now + 600_000).build()
        val badServer = tlsServer(wrongSan)
        try {
            val badManager = SecurePairingManager(context, storage, { now }, { true }, CoroutineScope(Dispatchers.Unconfined), AesGcmCipher())
            try {
                badManager.pairQr(qrFor(badServer, wrongSan))
                assertNotNull(badManager.state.value.error)
                assertEquals(0, badServer.requestCount)
            } finally { badManager.close() }

            enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
            val manager = manager()
            try {
                manager.pairQr(qr())
                val before = server.requestCount
                manager.pairQr(qr(serverId = spki(wrongSan), certificate = wrongSan.certificate.encoded))
                assertNotNull(manager.state.value.error)
                assertEquals(before, server.requestCount)
            } finally { manager.close() }
        } finally { runCatching { badServer.shutdown() } }
    }

    @Test fun unknownTrustedDeviceAndBadHintsFailClosed() = runBlocking {
        val manager = manager()
        try { manager.connect(HEX_F, "10.0.0.2", 443); assertNotNull(manager.state.value.error); assertTrue(manager.state.value.devices.isEmpty()) }
        finally { manager.close() }
    }

    @Test fun revoked401ClearsCredentialButRetainsTrustedProfile() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect(401)
        val manager = manager()
        try {
            manager.pairQr(qr()); assertEquals(1, manager.state.value.devices.size); assertNotNull(manager.state.value.error)
        } finally { manager.close() }
        val reloaded = manager()
        try { assertEquals(1, reloaded.state.value.devices.size); reloaded.connect(id()); assertNotNull(reloaded.state.value.error); assertEquals(1, reloaded.state.value.devices.size) }
        finally { reloaded.close() }
    }

    @Test fun recreatedManagerReloadsEncryptedCredentialAndReconnects() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
        val first = manager()
        try { first.pairQr(qr()) } finally { first.close() }
        enqueueConnect()
        val second = manager()
        try { assertEquals(1, second.state.value.devices.size); second.connect(id()); assertNotNull(second.state.value.connected) }
        finally { second.close() }
    }

    @Test fun savedDeviceCanMoveToAResolvedRouteOnlyWhenThePinnedTlsIdentityMatches() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
        val relocated = tlsServer(cert)
        val manager = manager()
        try {
            manager.pairQr(qr())
            server.enqueue(json(200, "{\"status\":\"disconnected\"}"))
            enqueueConnectFor(relocated)
            manager.connect(id(), "127.0.0.1", relocated.port)
            assertNotNull(manager.state.value.connected)
            assertEquals(relocated.port, manager.state.value.connected!!.device.port)
            assertEquals("/api/secure/session/connect", relocated.takeRequest(5, TimeUnit.SECONDS)!!.path)
        } finally { manager.close(); relocated.shutdown() }
    }

    @Test fun forgetRemovesLocalTrustAndSendsSecureRemoteRevocation() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
        server.enqueue(json(200, "{\"status\":\"disconnected\"}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val manager = manager()
        try {
            manager.pairQr(qr())
            manager.forget(id())
            assertTrue(manager.state.value.devices.isEmpty())
            val paths = (0 until server.requestCount).map { server.takeRequest(5, TimeUnit.SECONDS)!!.path }
            assertTrue(paths.contains("/api/secure/device/revoke"))
        } finally { manager.close() }
    }

    @Test fun corruptStorageIsFailClosedAndNeverOverwritten() {
        storage.bytes = byteArrayOf(1, 2, 3, 4)
        val manager = manager()
        try { assertTrue(manager.state.value.devices.isEmpty()); assertNotNull(manager.state.value.error); assertTrue(storage.bytes!!.contentEquals(byteArrayOf(1, 2, 3, 4))) }
        finally { manager.close() }
    }

    @Test fun disconnectClearsConnectionButRetainsTrustWhenServerIsOffline() = runBlocking {
        enqueueHandshake(); enqueuePairStart(); enqueueApproved(); enqueueConnect()
        val manager = manager()
        try { manager.pairQr(qr()); server.shutdown(); manager.disconnect(); assertNull(manager.state.value.connected); assertEquals(1, manager.state.value.devices.size) }
        finally { manager.close() }
    }

    @Test fun plaintextAndTls12DowngradeAreNotAccepted() = runBlocking {
        val plain = MockWebServer().also { it.start() }
        val tls12 = tlsServer(cert, Tls12OnlyFactory(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory()))
        try {
            val plainManager = SecurePairingManager(context, storage, { now }, { true }, CoroutineScope(Dispatchers.Unconfined), AesGcmCipher())
            try { plainManager.pairQr(qrFor(plain, cert)); assertNotNull(plainManager.state.value.error); assertEquals(0, plain.requestCount) }
            finally { plainManager.close() }
            val tlsManager = SecurePairingManager(context, storage, { now }, { true }, CoroutineScope(Dispatchers.Unconfined), AesGcmCipher())
            try { tlsManager.pairQr(qrFor(tls12, cert)); assertNotNull(tlsManager.state.value.error); assertEquals(0, tls12.requestCount) }
            finally { tlsManager.close() }
        } finally { runCatching { plain.shutdown() }; runCatching { tls12.shutdown() } }
    }

    private fun manager() = SecurePairingManager(context, storage, { now }, { true }, CoroutineScope(Dispatchers.Unconfined), AesGcmCipher())
    private fun qr(expires: Long = now + 120_000, serverId: String = id(), certificate: ByteArray = cert.certificate.encoded) = qrFor(server, cert, expires, serverId, certificate)
    private fun qrFor(s: MockWebServer, c: HeldCertificate, expires: Long = now + 120_000, serverId: String = spki(c), certificate: ByteArray = c.certificate.encoded) = org.json.JSONObject().put("type", "aurashare-secure-pair").put("version", 3).put("host", "127.0.0.1").put("port", s.port).put("certificate", android.util.Base64.encodeToString(certificate, android.util.Base64.NO_WRAP)).put("server_id", serverId).put("bootstrap", HEX_B).put("expires_at", expires).toString()
    private fun id() = spki(cert)
    private fun spki(c: HeldCertificate) = MessageDigest.getInstance("SHA-256").digest(c.certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
    private fun tlsServer(c: HeldCertificate, factory: SSLSocketFactory? = null) = MockWebServer().apply { useHttps(factory ?: HandshakeCertificates.Builder().heldCertificate(c).build().sslSocketFactory(), false); start() }
    private fun enqueueHandshake() = server.enqueue(json(200, "{\"server\":\"AuraShare\",\"protocol\":3,\"server_id\":\"${id()}\"}"))
    private fun enqueuePairStart() = server.enqueue(json(202, "{\"request_id\":\"r1\",\"request_secret\":\"$HEX_A\"}"))
    private fun enqueueApproved() = server.enqueue(json(200, "{\"status\":\"approved\",\"server_id\":\"${id()}\",\"device_name\":\"Test Desktop\",\"credential\":\"$HEX_C\"}"))
    private fun enqueueConnect(code: Int = 200) = server.enqueue(if (code == 200) json(200, "{\"server_id\":\"${id()}\",\"token\":\"$HEX_T\"}") else MockResponse().setResponseCode(code))
    private fun enqueueConnectFor(target: MockWebServer) = target.enqueue(json(200, "{\"server_id\":\"${id()}\",\"token\":\"$HEX_T\"}"))
    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private class Tls12OnlyFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        private fun restrict(s: Socket): Socket { (s as SSLSocket).enabledProtocols = arrayOf("TLSv1.2"); return s }
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean) = restrict(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String, port: Int) = restrict(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = restrict(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress, port: Int) = restrict(delegate.createSocket(host, port))
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int) = restrict(delegate.createSocket(host, port, localHost, localPort))
        override fun getDefaultCipherSuites() = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites() = delegate.supportedCipherSuites
    }
    private class MemoryStorage : SecureStorage { var bytes: ByteArray? = null; override fun read() = bytes?.clone(); override fun writeAtomically(bytes: ByteArray) { this.bytes = bytes.clone() } }
    private class AesGcmCipher : SecureStoreCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun encrypt(plain: ByteArray): ByteArray { val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }; val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); return iv + c.doFinal(plain) }
        override fun decrypt(blob: ByteArray): ByteArray { require(blob.size > 12); val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))); return c.doFinal(blob.copyOfRange(12, blob.size)) }
    }
    companion object {
        private val HEX_A = "a".repeat(64)
        private val HEX_B = "b".repeat(64)
        private val HEX_C = "c".repeat(64)
        private val HEX_F = "f".repeat(64)
        private val HEX_T = "d".repeat(64)
    }
}
