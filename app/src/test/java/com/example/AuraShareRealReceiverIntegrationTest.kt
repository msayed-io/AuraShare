package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.network.SecurePairingManager
import com.example.data.network.SecureStorage
import com.example.data.network.SecureStoreCipher
import com.example.data.network.StreamingTransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Real Android production networking against the pinned desktop TLS receiver built by CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuraShareRealReceiverIntegrationTest {
    @Test(timeout = 180_000)
    fun realSecurePairingTransferReconnectAndRevoke() = runBlocking {
        val binary = System.getenv("AURASHARE_CORE_TEST_BINARY")
        assertTrue(
            "AURASHARE_CORE_TEST_BINARY must point to the real secure receiver; skipping is forbidden",
            !binary.isNullOrBlank() && File(binary).canExecute(),
        )
        val process = ProcessBuilder(binary!!).redirectError(ProcessBuilder.Redirect.INHERIT).apply {
            environment()["AURASHARE_INTEGRATION_TEST"] = "1"
        }.start()
        val lines = process.inputStream.bufferedReader()
        val fixture = JSONObject(requireNotNull(lines.readLine()) { "secure server did not emit startup JSON" })
        val securePort = fixture.getInt("secure_port")
        val downloadDir = File(fixture.getString("download_dir"))
        val qr = JSONObject(fixture.getString("qr")).put("host", "127.0.0.1").toString()
        val control = OutputStreamWriter(process.outputStream, Charsets.UTF_8).buffered()
        val events = JsonEventReader(lines)
        try {
            val storage = MemoryStorage()
            val manager = secureManager(storage)
            try {
                val pairing = async(Dispatchers.IO) { manager.pairQr(qr) }
                assertTrue("desktop did not report an actual pending secure pairing", events.awaitEvent("pending", 20_000))
                control.write("approve\n"); control.flush()
                pairing.await()

                val connection = requireNotNull(manager.state.value.connected) { "secure manager did not establish TLS" }
                val trustedId = connection.device.id
                assertTrue("connected device identity must be saved as trusted", manager.state.value.devices.any { it.id == trustedId })
                assertEquals("https", connection.baseUrl.substringBefore(":").lowercase())

                val unauthenticated = connection.client.newCall(
                    Request.Builder()
                        .url("${connection.baseUrl}/upload")
                        .post(ByteArray(0).toRequestBody())
                        .build(),
                ).execute().use { it.code }
                assertTrue("secure upload without session credential was accepted", unauthenticated == 401 || unauthenticated == 403)

                sendAndAssert(connection, downloadDir, "صورة-✓.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00, 1, 2, 3))
                sendAndAssert(connection, downloadDir, "فيديو-✓.mp4", "video/mp4", byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 1, 2, 3, 4))
                sendAndAssert(connection, downloadDir, "مستند-✓.pdf", "application/pdf", "%PDF-1.7\nبيانات عربية\n".toByteArray(Charsets.UTF_8))
                sendAndAssert(connection, downloadDir, "ملف-عشوائي.bin", "application/octet-stream", Random.nextBytes(257))

                manager.disconnect()
                manager.close()

                val recreated = secureManager(storage)
                try {
                    recreated.connect(trustedId, "127.0.0.1", securePort)
                    val reconnected = requireNotNull(recreated.state.value.connected) { "saved device did not reconnect through TLS" }
                    assertEquals(trustedId, reconnected.device.id)
                    assertEquals(securePort, reconnected.device.port)
                    recreated.forget(trustedId)
                    assertTrue("forget must remove the stored device", recreated.state.value.devices.none { it.id == trustedId })
                    control.write("quit\n"); control.flush()
                } finally {
                    recreated.close()
                }
                assertTrue("secure receiving core did not exit", process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            } finally {
                runCatching { manager.close() }
            }
        } finally {
            runCatching { control.write("quit\n"); control.flush(); control.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun sendAndAssert(
        connection: com.example.data.network.SecureConnection,
        dir: File,
        name: String,
        mime: String,
        bytes: ByteArray,
    ) {
        val source = File.createTempFile("aurashare-secure-ci-", ".bin").apply { writeBytes(bytes) }
        try {
            val item = MediaItem(
                System.nanoTime(), Uri.fromFile(source), name, bytes.size.toLong(), mime,
                System.currentTimeMillis(), bucketName = "integration", mediaType = AuraMediaType.FILE,
            )
            val done = CountDownLatch(1)
            var ok = false
            var error: String? = null
            StreamingTransferManager(ApplicationProvider.getApplicationContext()).sendFile(item, connection) { success, message ->
                ok = success
                error = message
                done.countDown()
            }
            assertTrue("secure transfer callback timed out: $error", done.await(30, TimeUnit.SECONDS))
            assertTrue("secure transfer failed: $error", ok)
            val received = File(dir, name)
            assertTrue("missing received file $name", received.isFile)
            assertEquals(bytes.toList(), received.readBytes().toList())
            assertEquals(sha256(bytes), sha256(received.readBytes()))
        } finally {
            source.delete()
        }
    }

    private fun secureManager(storage: SecureStorage) = SecurePairingManager(
        ApplicationProvider.getApplicationContext(),
        storage,
        addressAllowed = { it == "127.0.0.1" || SecurePairingManager.isAllowedLanIpv4(it) },
        storeCipher = AesGcmCipher(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryStorage : SecureStorage {
        private var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun writeAtomically(bytes: ByteArray) { value = bytes.clone() }
    }

    private class AesGcmCipher : SecureStoreCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }
        override fun decrypt(blob: ByteArray): ByteArray {
            require(blob.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            return cipher.doFinal(blob.copyOfRange(12, blob.size))
        }
    }

    private class JsonEventReader(private val reader: java.io.BufferedReader) {
        private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        init {
            Thread {
                try { while (true) lines.put(reader.readLine() ?: break) } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()
        }
        fun awaitEvent(name: String, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                val line = lines.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (runCatching { JSONObject(line).optString("event") == name }.getOrDefault(false)) return true
            }
            return false
        }
    }
}
