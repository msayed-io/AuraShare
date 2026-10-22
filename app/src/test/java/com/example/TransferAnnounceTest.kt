package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.network.StreamingTransferManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TransferAnnounceTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun item(name: String, content: ByteArray, mime: String): MediaItem {
        val file = File(app.cacheDir, "announce-${UUID.randomUUID()}").apply { writeBytes(content) }
        return MediaItem(
            id = 1L,
            uri = Uri.fromFile(file),
            name = name,
            sizeBytes = content.size.toLong(),
            mimeType = mime,
            dateModified = 0L,
            mediaType = if (mime.startsWith("image/")) AuraMediaType.IMAGE else AuraMediaType.FILE
        )
    }

    private fun completed(body: ByteArray): MockResponse {
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) }
        return MockResponse().setResponseCode(200).setBody(
            "{\"status\":\"completed\",\"received\":${body.size},\"sha256\":\"$hash\"}"
        )
    }

    @Test
    fun `announces all directions with matching UUID and preserves upload request`() = runBlocking {
        val payload = "بيانات PDF ✓".toByteArray(Charsets.UTF_8)
        val name = "تقرير ✓.pdf"
        val token = "announce-token"

        listOf("top", "left", "right").forEach { direction ->
            val file = item(name, payload, "application/pdf")
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(completed(payload))

            var success = false
            StreamingTransferManager(app).sendFile(
                item = file,
                host = server.hostName,
                port = server.port,
                token = token,
                direction = direction
            ) { ok, _ -> success = ok }

            val announcement = server.takeRequest()
            assertEquals("/api/transfer/announce", announcement.path)
            assertEquals(token, announcement.getHeader("X-AuraShare-Token"))
            val json = JSONObject(announcement.body.readUtf8())
            assertTrue(UUID.fromString(json.getString("id")) != null)
            assertEquals(direction, json.getString("direction"))
            assertEquals(name, json.getString("name"))
            assertEquals(payload.size.toLong(), json.getLong("size"))
            assertEquals("application/pdf", json.getString("mime"))
            assertEquals("other", json.getString("kind"))
            assertTrue("PDF must not receive a thumbnail", json.isNull("thumb"))

            val upload = server.takeRequest()
            assertEquals("/upload", upload.path)
            assertEquals(json.getString("id"), upload.getHeader("X-Transfer-Id"))
            assertEquals(direction, upload.getHeader("X-Direction"))
            assertEquals(token, upload.getHeader("X-AuraShare-Token"))
            assertEquals(name, java.net.URLDecoder.decode(upload.getHeader("X-File-Name"), "UTF-8"))
            assertEquals(payload.size.toString(), upload.getHeader("X-File-Size"))
            assertEquals(payload.toList(), upload.body.readByteArray().toList())
            assertTrue(success)
        }
    }

    @Test
    fun `announcement 404 500 and timeout never prevent upload`() = runBlocking {
        val failures = listOf(
            MockResponse().setResponseCode(404),
            MockResponse().setResponseCode(500),
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        )
        failures.forEach { announceResponse ->
            val payload = "still transferred".toByteArray()
            server.enqueue(announceResponse)
            server.enqueue(completed(payload))
            val started = System.nanoTime()
            var success = false
            StreamingTransferManager(app).sendFile(
                item = item("fallback.bin", payload, "application/octet-stream"),
                host = server.hostName,
                port = server.port
            ) { ok, _ -> success = ok }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println("Announcement fallback ${announceResponse.status}: total announce+upload ${elapsedMs}ms")
            assertTrue("failure must not block transfer excessively: ${elapsedMs}ms", elapsedMs < 3500)
            assertEquals("/api/transfer/announce", server.takeRequest().path)
            assertEquals("/upload", server.takeRequest().path)
            assertTrue(success)
        }
    }

    @Test
    fun `real image preview is JSON base64 while upload retains original bytes`() = runBlocking {
        val payload = checkNotNull(javaClass.getResourceAsStream("/thumbnail-exif-rotate90.jpg")).use { it.readBytes() }
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(completed(payload))
        var success = false
        StreamingTransferManager(app).sendFile(item("صورة.jpg", payload, "image/jpeg"), server.hostName, server.port, direction = "left") { ok, _ -> success = ok }
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("image", json.getString("kind"))
        val thumbnail = android.util.Base64.decode(json.getString("thumb"), android.util.Base64.NO_WRAP)
        assertTrue(thumbnail.size <= 40 * 1024)
        assertEquals(0xff.toByte(), thumbnail[0])
        assertEquals(0xd8.toByte(), thumbnail[1])
        val preview = android.graphics.BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
        try {
            assertTrue(maxOf(preview.width, preview.height) <= 320)
        } finally { preview.recycle() }
        assertEquals(payload.toList(), server.takeRequest().body.readByteArray().toList())
        assertTrue(success)
    }

    @Test
    fun `failed video preview still announces video and transfers original bytes`() = runBlocking {
        val payload = "unreadable media metadata but readable file".toByteArray()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(completed(payload))
        var success = false
        StreamingTransferManager(app).sendFile(item("فيديو.mp4", payload, "video/mp4"), server.hostName, server.port) { ok, _ -> success = ok }
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("video", json.getString("kind"))
        assertTrue(json.isNull("thumb"))
        assertEquals(payload.toList(), server.takeRequest().body.readByteArray().toList())
        assertTrue(success)
    }

    @Test
    fun `invalid direction defaults to top while unknown size keeps chunked upload`() = runBlocking {
        val payload = "unknown length still streams".toByteArray()
        val source = item("unknown.bin", payload, "").copy(sizeBytes = -1)
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(completed(payload))
        var success = false
        StreamingTransferManager(app).sendFile(source, server.hostName, server.port, direction = "bottom") { ok, _ -> success = ok }
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("top", json.getString("direction"))
        assertEquals(-1L, json.getLong("size"))
        assertEquals("application/octet-stream", json.getString("mime"))
        val upload = server.takeRequest()
        assertEquals("top", upload.getHeader("X-Direction"))
        org.junit.Assert.assertNull(upload.getHeader("X-File-Size"))
        assertEquals("chunked", upload.getHeader("Transfer-Encoding"))
        assertEquals(payload.toList(), upload.body.readByteArray().toList())
        assertTrue(success)
    }

    @Test
    fun `announce redirect does not follow or leak token and upload remains intact`() = runBlocking {
        val redirect = MockWebServer().also { it.start() }
        try {
            val payload = "redirect-safe".toByteArray()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirect.url("/api/transfer/announce")))
            server.enqueue(completed(payload))
            var success = false
            StreamingTransferManager(app).sendFile(
                item = item("redirect.bin", payload, "application/octet-stream"),
                host = server.hostName,
                port = server.port,
                token = "must-not-leak"
            ) { ok, _ -> success = ok }

            val announcement = server.takeRequest()
            assertEquals("must-not-leak", announcement.getHeader("X-AuraShare-Token"))
            assertEquals("/upload", server.takeRequest().path)
            assertEquals(0, redirect.requestCount)
            assertTrue(success)
        } finally {
            redirect.shutdown()
        }
    }
}
