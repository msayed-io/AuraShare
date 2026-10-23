package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.model.TransferStatus
import com.example.data.network.StreamingTransferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SecureStreamingTransferTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("aurashare.local").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) })
            .callTimeout(2, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun item(bytes: ByteArray, name: String, mime: String): MediaItem {
        val file = File(app.cacheDir, "secure-${name.hashCode()}-${System.nanoTime()}").apply { writeBytes(bytes) }
        return MediaItem(
            id = System.nanoTime(), uri = Uri.fromFile(file), name = name,
            sizeBytes = bytes.size.toLong(), mimeType = mime,
            dateModified = System.currentTimeMillis() / 1000,
            bucketName = "secure",
            mediaType = if (mime.startsWith("image/")) AuraMediaType.IMAGE else AuraMediaType.FILE
        )
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun completed(bytes: ByteArray) =
        """{"status":"completed","received":${bytes.size},"sha256":"${sha(bytes)}"}"""

    private fun runTransfer(bytes: ByteArray, response: String, name: String = "clip.bin",
                            mime: String = "application/octet-stream"): Pair<Boolean, String?> = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))
        var result = false
        var error: String? = null
        StreamingTransferManager(app).sendFile(item(bytes, name, mime), "ignored", 1,
            secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { ok, message ->
                result = ok; error = message
            }
        result to error
    }

    @Test fun `secure JPEG and MP4 fixtures preserve filename bytes SHA and size`() = runBlocking {
        val jpeg = javaClass.getResourceAsStream("/thumbnail-exif-rotate90.jpg")!!.use { it.readBytes() }
        val video = byteArrayOf(
            0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 0, 1,
            0, 0, 0, 0, 'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
            0, 0, 0, 12, 'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 7, 6, 5, 4
        )
        listOf(
            Triple("thumbnail-exif-rotate90.jpg", jpeg, "image/jpeg"),
            Triple("fixture-video.mp4", video, "video/mp4")
        ).forEach { (name, bytes, mime) ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody(completed(bytes)))
            var ok = false
            StreamingTransferManager(app).sendFile(item(bytes, name, mime), "ignored", 1,
                secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { success, _ -> ok = success }
            server.takeRequest()
            val upload = server.takeRequest()
            assertTrue(ok)
            assertEquals(name, java.net.URLDecoder.decode(upload.getHeader("X-File-Name"), "UTF-8"))
            assertEquals(bytes.size.toString(), upload.getHeader("X-File-Size"))
            val received = upload.body.readByteArray()
            assertEquals(bytes.toList(), received.toList())
            assertEquals(sha(bytes), sha(received))
        }
    }

    @Test fun `secure transfer rejects missing received separately`() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        val result = runTransfer(bytes, """{"status":"completed","sha256":"${sha(bytes)}"}""")
        assertFalse(result.first)
    }

    @Test fun `secure transfer rejects wrong size or hash`() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        assertFalse(runTransfer(bytes, """{"status":"completed","received":${bytes.size + 1},"sha256":"${sha(bytes)}"}""").first)
        assertFalse(runTransfer(bytes, """{"status":"completed","received":${bytes.size},"sha256":"${"0".repeat(64)}"}""").first)
    }

    @Test fun `secure unknown size and empty files keep correct framing and digest`() {
        val unknownBytes = byteArrayOf(8, 9, 10)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(completed(unknownBytes)))
        var unknownOk = false
        StreamingTransferManager(app).sendFile(item(unknownBytes, "unknown.bin", "application/octet-stream").copy(sizeBytes = -1), "ignored", 1,
            secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { ok, _ -> unknownOk = ok }
        server.takeRequest(); val unknownUpload = server.takeRequest()
        assertTrue(unknownOk)
        assertTrue(unknownUpload.getHeader("X-File-Size") == null)
        assertEquals(unknownBytes.toList(), unknownUpload.body.readByteArray().toList())

        val empty = ByteArray(0)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(completed(empty)))
        var emptyOk = false
        StreamingTransferManager(app).sendFile(item(empty, "empty.jpg", "image/jpeg"), "ignored", 1,
            secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { ok, _ -> emptyOk = ok }
        server.takeRequest(); val emptyUpload = server.takeRequest()
        assertTrue(emptyOk)
        assertEquals("0", emptyUpload.getHeader("X-File-Size"))
        assertEquals(0, emptyUpload.body.size)
    }

    @Test fun `wrong certificate and wrong SAN fail HTTPS without plaintext fallback`() = runBlocking {
        val bytes = byteArrayOf(4, 5, 6)
        val wrongCertServer = MockWebServer()
        val wrong = HeldCertificate.Builder().addSubjectAlternativeName("aurashare.local").build()
        wrongCertServer.useHttps(HandshakeCertificates.Builder().heldCertificate(wrong).build().sslSocketFactory(), false)
        wrongCertServer.start()
        try {
            wrongCertServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            var ok = true
            StreamingTransferManager(app).sendFile(item(bytes, "wrong-cert.bin", "application/octet-stream"), "ignored", 1,
                secureClient = client, secureBaseUrl = "https://aurashare.local:${wrongCertServer.port}") { success, _ -> ok = success }
            assertFalse(ok)
            assertEquals(0, wrongCertServer.requestCount)
        } finally { wrongCertServer.shutdown() }

        val wrongSan = HeldCertificate.Builder().addSubjectAlternativeName("not-aurashare.local").build()
        val wrongSanServer = MockWebServer()
        wrongSanServer.useHttps(HandshakeCertificates.Builder().heldCertificate(wrongSan).build().sslSocketFactory(), false)
        wrongSanServer.start()
        val wrongSanTrusted = HandshakeCertificates.Builder().addTrustedCertificate(wrongSan.certificate).build()
        val wrongSanClient = client.newBuilder().sslSocketFactory(wrongSanTrusted.sslSocketFactory(), wrongSanTrusted.trustManager).build()
        try {
            wrongSanServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            var ok = true
            StreamingTransferManager(app).sendFile(item(bytes, "wrong-san.bin", "application/octet-stream"), "ignored", 1,
                secureClient = wrongSanClient, secureBaseUrl = "https://aurashare.local:${wrongSanServer.port}") { success, _ -> ok = success }
            assertFalse(ok)
            assertEquals(0, wrongSanServer.requestCount)
        } finally { wrongSanServer.shutdown() }
    }

    @Test fun `plain HTTP and secure network drop fail cleanly`() = runBlocking {
        val plain = MockWebServer().also { it.start() }
        try {
            plain.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            var ok = true
            StreamingTransferManager(app).sendFile(item(byteArrayOf(4, 5, 6), "plain.bin", "application/octet-stream"), "ignored", 1,
                secureClient = client, secureBaseUrl = "https://aurashare.local:${plain.port}") { success, _ -> ok = success }
            assertFalse(ok)
            assertEquals(0, plain.requestCount)
        } finally { plain.shutdown() }

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        var dropped = true
        val manager = StreamingTransferManager(app)
        manager.sendFile(item(byteArrayOf(1, 2, 3), "drop.bin", "application/octet-stream"), "ignored", 1,
            secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { success, _ -> dropped = success }
        assertFalse(dropped)
        assertEquals(TransferStatus.FAILED, manager.currentTransfer.value?.status)
    }

    @Test fun `secure cancellation is reported as clean failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(5, TimeUnit.SECONDS).setBody("{}"))
        val manager = StreamingTransferManager(app)
        var thrown: Throwable? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                manager.sendFile(item(ByteArray(1024) { 1 }, "cancel.bin", "application/octet-stream"), "ignored", 1,
                    secureClient = client, secureBaseUrl = "https://aurashare.local:${server.port}") { ok, _ -> assertFalse(ok) }
            } catch (t: Throwable) { thrown = t }
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(thrown is CancellationException)
        assertEquals(TransferStatus.FAILED, manager.currentTransfer.value?.status)
    }
}
