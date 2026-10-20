package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.network.PairingClient
import com.example.data.network.StreamingTransferManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Optional cross-application test. CI supplies AURASHARE_CORE_TEST_BINARY; local JVM runs skip.
 * The fixture's stdin is deliberately used as the desktop's local approval authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuraShareRealReceiverIntegrationTest {
    @Test(timeout = 90_000)
    fun realPairingTransferDisconnectAndRejectRetry() = runBlocking {
        val binary = System.getenv("AURASHARE_CORE_TEST_BINARY")
        assumeTrue("AURASHARE_CORE_TEST_BINARY is not set", !binary.isNullOrBlank() && File(binary).canExecute())
        val process = ProcessBuilder(binary!!).redirectError(ProcessBuilder.Redirect.INHERIT).apply {
            environment()["AURASHARE_INTEGRATION_TEST"] = "1"
        }.start()
        try {
            val fixture = JSONObject(process.inputStream.bufferedReader().readLine())
            val endpoint = PairingClient.Endpoint("127.0.0.1", fixture.getInt("port"))
            val downloadDir = File(fixture.getString("download_dir"))
            val client = PairingClient()
            val stdin = process.outputStream.bufferedWriter()
            val info = client.handshake(endpoint)
            assertEquals(2, info.protocol)

            val rejected = client.requestPair(endpoint, "android-ci-reject", "Android CI")
            stdin.write("reject\n"); stdin.flush()
            assertEquals("rejected", client.awaitApproval(endpoint, rejected, 15_000).status)

            val request = client.requestPair(endpoint, "android-ci-approve", "Android CI")
            stdin.write("approve\n"); stdin.flush()
            val approval = client.awaitApproval(endpoint, request, 15_000)
            assertEquals("approved", approval.status)
            val token = requireNotNull(approval.token)
            assertEquals(info.serverId, approval.serverId)
            assertTrue(client.heartbeat(endpoint, token))

            val bytes = "AuraShare Arabic filename ✓\n".toByteArray(Charsets.UTF_8)
            val source = File.createTempFile("aurashare-ci-", ".bin").apply { writeBytes(bytes) }
            try {
                val item = MediaItem(1L, Uri.fromFile(source), "اختبار-✓.bin", bytes.size.toLong(), "application/octet-stream", System.currentTimeMillis(), mediaType = AuraMediaType.FILE)
                val manager = StreamingTransferManager(ApplicationProvider.getApplicationContext())
                val done = CountDownLatch(1)
                var result = false
                var error: String? = null
                manager.sendFile(item, "127.0.0.1", endpoint.port, token, useLegacyEndpoint = false) { ok, message -> result = ok; error = message; done.countDown() }
                assertTrue("transfer callback timed out: $error", done.await(20, TimeUnit.SECONDS))
                assertTrue("transfer failed: $error", result)
                val received = downloadDir.walkTopDown().firstOrNull { it.isFile && it.name == item.name }
                assertTrue("Arabic filename was not written", received != null)
                assertEquals(bytes.toList(), received!!.readBytes().toList())
                assertEquals(sha256(bytes), sha256(received.readBytes()))
            } finally { source.delete() }

            stdin.write("disconnect\n"); stdin.flush()
            // The fixture revokes immediately; a subsequent heartbeat must fail.
            eventually(5_000) { !client.heartbeat(endpoint, token) }
        } finally {
            process.outputStream.bufferedWriter().use { it.write("quit\n"); it.flush() }
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun eventually(timeoutMs: Long, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) if (check()) return else Thread.sleep(100)
        assertTrue(check())
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
