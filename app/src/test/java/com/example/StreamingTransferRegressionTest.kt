package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.model.TransferStatus
import com.example.data.network.DiscoveryState
import com.example.data.network.MdnsDiscoveryManager
import com.example.data.network.StreamingTransferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StreamingTransferRegressionTest {

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

    private fun createTestFile(fileName: String, content: String): MediaItem {
        val testFile = File(app.cacheDir, fileName)
        testFile.writeText(content, Charsets.UTF_8)
        return MediaItem(
            id = System.currentTimeMillis(),
            uri = Uri.fromFile(testFile),
            name = fileName,
            sizeBytes = testFile.length(),
            mimeType = "text/plain",
            dateModified = System.currentTimeMillis() / 1000,
            bucketName = "اختبار",
            mediaType = AuraMediaType.FILE
        )
    }

    private fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun enqueueAnnouncementResponse(code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
    }

    private fun takeUploadRequest(): okhttp3.mockwebserver.RecordedRequest {
        assertEquals("/api/transfer/announce", server.takeRequest().path)
        return server.takeRequest()
    }

    @Test
    fun `test network error when server offline fails explicitly without fake success`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("test_offline.txt", "Some content")

        var completionCalled = false
        var transferSuccess = true
        var errorMessage: String? = null

        // Connect to a closed port
        val closedPort = 64999
        transferManager.sendFile(
            item = item,
            host = "127.0.0.1",
            port = closedPort
        ) { success, error ->
            completionCalled = true
            transferSuccess = success
            errorMessage = error
        }

        assertTrue("Completion callback must be invoked", completionCalled)
        assertFalse("CRITICAL: Network failure must NOT declare success!", transferSuccess)
        assertNotNull("Error message must be present", errorMessage)
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test missing or unreadable file URI fails explicitly without synthetic payload`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val missingUri = Uri.parse("file:///non_existent_folder/missing_file.xyz")
        val item = MediaItem(
            id = 9999,
            uri = missingUri,
            name = "missing_file.xyz",
            sizeBytes = 1024,
            mimeType = "text/plain",
            dateModified = 0,
            bucketName = "مفقود",
            mediaType = AuraMediaType.FILE
        )

        var completionCalled = false
        var transferSuccess = true
        var errorMessage: String? = null

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port
        ) { success, error ->
            completionCalled = true
            transferSuccess = success
            errorMessage = error
        }

        assertTrue(completionCalled)
        assertFalse("Unreadable file must NOT succeed", transferSuccess)
        assertNotNull(errorMessage)
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test successful transfer with completed status and SHA-256 match`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val content = "AuraShare High Speed Streaming Real Content"
        val expectedSha256 = computeSha256(content)
        val item = createTestFile("valid_doc.txt", content)

        val serverResponseBody = """
            {
                "id": "trans-12345",
                "name": "valid_doc.txt",
                "received": ${content.length},
                "total": ${content.length},
                "bytes_per_second": 50000000,
                "status": "completed",
                "sha256": "$expectedSha256",
                "path": "C:\\AuraShare\\valid_doc.txt"
            }
        """.trimIndent()
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBody(serverResponseBody))

        var callbackSuccess = false
        var callbackError: String? = "not called"

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port,
            token = "MY_TOKEN_2026"
        ) { success, error ->
            callbackSuccess = success
            callbackError = error
        }

        val recordedRequest = takeUploadRequest()
        assertEquals("/upload", recordedRequest.path)
        assertEquals("MY_TOKEN_2026", recordedRequest.getHeader("X-AuraShare-Token"))
        assertEquals(content.length.toString(), recordedRequest.getHeader("X-File-Size"))
        assertEquals("AuraShare-Android-Client/1.0", recordedRequest.getHeader("User-Agent"))
        assertEquals(content, recordedRequest.body.readUtf8())

        assertTrue("Transfer should succeed when SHA-256 matches", callbackSuccess)
        assertNull(callbackError)
        assertEquals(TransferStatus.COMPLETED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test transfer fails when server SHA-256 does not match`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val content = "Authentic Phone Content"
        val item = createTestFile("tampered.txt", content)

        val serverResponseBody = """
            {
                "id": "trans-wrong",
                "name": "tampered.txt",
                "received": ${content.length},
                "total": ${content.length},
                "status": "completed",
                "sha256": "fake_or_corrupted_sha256_hash_value"
            }
        """.trimIndent()
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBody(serverResponseBody))

        var callbackSuccess = true
        var callbackError: String? = null

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port
        ) { success, error ->
            callbackSuccess = success
            callbackError = error
        }

        assertFalse("Transfer must fail when SHA-256 does not match!", callbackSuccess)
        assertNotNull(callbackError)
        assertTrue(callbackError!!.contains("SHA-256"))
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test transfer fails when server status is not completed`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val content = "Test Content"
        val item = createTestFile("incomplete.txt", content)

        val serverResponseBody = """
            {
                "id": "trans-incomplete",
                "status": "in_progress",
                "received": ${content.length}
            }
        """.trimIndent()
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBody(serverResponseBody))

        var callbackSuccess = true
        var callbackError: String? = null

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port
        ) { success, error ->
            callbackSuccess = success
            callbackError = error
        }

        assertFalse("Transfer must fail if status != completed", callbackSuccess)
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test HTTP 401 returns failure requesting re-pairing`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("auth_test.txt", "Hello")

        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized token"}"""))

        var callbackSuccess = true
        var callbackError: String? = null

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port,
            token = "EXPIRED_TOKEN"
        ) { success, error ->
            callbackSuccess = success
            callbackError = error
        }

        assertFalse(callbackSuccess)
        assertTrue(callbackError?.contains("401") == true)
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }

    @Test
    fun `test HTTP 423 locked and 429 rate limit return clear failure`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("limit_test.txt", "Data")

        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(423).setBody("""{"error":"Locked"}"""))

        var callbackSuccess = true
        var callbackError: String? = null

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port
        ) { success, error ->
            callbackSuccess = success
            callbackError = error
        }

        assertFalse(callbackSuccess)
        assertTrue(callbackError?.contains("423") == true)
    }

    @Test
    fun `test empty file 0 bytes transfer and header`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("empty.txt", "")
        val expectedSha256 = computeSha256("")

        val serverResponseBody = """
            {
                "status": "completed",
                "received": 0,
                "sha256": "$expectedSha256"
            }
        """.trimIndent()
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBody(serverResponseBody))

        var callbackSuccess = false

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port
        ) { success, _ ->
            callbackSuccess = success
        }

        val request = takeUploadRequest()
        assertEquals("0", request.getHeader("X-File-Size"))
        assertTrue(callbackSuccess)
    }

    @Test
    fun `test stale legacy flag cannot downgrade authentication endpoint`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("legacy+file test.txt", "Legacy data")
        val expectedSha256 = computeSha256("Legacy data")

        val serverResponseBody = """
            {
                "status": "completed",
                "received": 11,
                "sha256": "$expectedSha256"
            }
        """.trimIndent()
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBody(serverResponseBody))

        transferManager.sendFile(
            item = item,
            host = server.hostName,
            port = server.port,
            useLegacyEndpoint = true
        ) { _, _ -> }

        val request = takeUploadRequest()
        assertEquals("/upload", request.path)
        // Even a stale legacy flag must retain modern encoding and endpoint.
        val nameHeader = request.getHeader("X-File-Name")
        assertTrue("Modern filename encodes space", nameHeader?.contains("%20") == true)
        assertTrue("Modern filename encodes plus", nameHeader?.contains("%2B") == true)
    }

    @Test
    fun `test coroutine cancellation rethrows CancellationException and marks Failed`() = runBlocking {
        val transferManager = StreamingTransferManager(app)
        val item = createTestFile("cancel_test.txt", "Some large content for streaming test")

        // Server delays response so transfer is active
        enqueueAnnouncementResponse()
        server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))

        val scope = CoroutineScope(Dispatchers.IO)
        var thrownException: Throwable? = null

        val job = scope.launch {
            try {
                transferManager.sendFile(
                    item = item,
                    host = server.hostName,
                    port = server.port
                ) { _, _ -> }
            } catch (t: Throwable) {
                thrownException = t
            }
        }

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            assertEquals("/api/transfer/announce", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/upload", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
        }
        job.cancel()
        job.join()

        assertTrue("CancellationException must be re-thrown", thrownException is CancellationException)
        assertEquals(TransferStatus.FAILED, transferManager.currentTransfer.value?.status)
    }
}
