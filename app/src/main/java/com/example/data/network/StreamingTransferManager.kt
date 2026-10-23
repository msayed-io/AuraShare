package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.model.MediaItem
import com.example.data.model.TransferRecord
import com.example.data.model.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class StreamingTransferManager(private val context: Context) {
    private val tag = "StreamingTransfer"

    val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // A separate whole-call deadline includes connection, request and response headers.
    // Redirects/retries remain disabled so the session token never leaves this peer.
    private val announceClient = httpClient.newBuilder()
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()
    private val thumbnails = MediaThumbnailGenerator(context)

    private val _activeTransfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val activeTransfers: StateFlow<List<TransferRecord>> = _activeTransfers.asStateFlow()

    private val _currentTransfer = MutableStateFlow<TransferRecord?>(null)
    val currentTransfer: StateFlow<TransferRecord?> = _currentTransfer.asStateFlow()

    companion object {
        /**
         * Encodes file name for the v2 /upload endpoint:
         * Standard RFC 3986 percent-encoding where space is %20 and plus sign is %2B.
         */
        fun encodeModernFileName(name: String): String {
            return try {
                URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            } catch (_: Exception) {
                name
            }
        }

    }

    suspend fun sendFile(
        item: MediaItem,
        host: String,
        port: Int,
        token: String? = null,
        @Suppress("UNUSED_PARAMETER") useLegacyEndpoint: Boolean = false,
        direction: String = "top",
        secureClient: OkHttpClient? = null,
        secureBaseUrl: String? = null,
        onComplete: (Boolean, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val transferId = UUID.randomUUID().toString()
        val edge = when (direction) {
            "top", "left", "right" -> direction
            else -> {
                Log.w(tag, "Invalid transfer direction; using top")
                "top"
            }
        }
        val secureSession = secureClient != null || secureBaseUrl != null
        val baseUrl = if (secureSession) secureBaseUrl?.toHttpUrlOrNull() else null
        if (secureSession && (secureClient == null || baseUrl == null ||
                    baseUrl.scheme != "https" || !baseUrl.host.equals("aurashare.local", ignoreCase = true) ||
                    baseUrl.encodedPath != "" && baseUrl.encodedPath != "/" ||
                    baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty() ||
                    baseUrl.query != null || baseUrl.fragment != null)) {
            val errorMsg = "جلسة HTTPS الآمنة تتطلب secureClient و secureBaseUrl صالحاً (HTTPS aurashare.local بلا مسار أو بيانات اعتماد)"
            onComplete(false, errorMsg)
            return@withContext
        }
        // These are immutable per-transfer references; secure traffic never falls back to HTTP.
        val transferClient = secureClient ?: httpClient
        val transferAnnounceClient = if (secureSession) {
            transferClient.newBuilder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
        } else announceClient

        val initialRecord = TransferRecord(
            id = transferId,
            item = item,
            status = TransferStatus.STREAMING,
            progress = 0f,
            speedBytesPerSec = 0L
        )

        _currentTransfer.value = initialRecord
        _activeTransfers.update { listOf(initialRecord) + it }

        // Preliminary validation: Verify that the URI is accessible and readable
        try {
            val testStream = context.contentResolver.openInputStream(item.uri)
            if (testStream == null) {
                val errorMsg = "تعذر قراءة محتوى الملف (${item.name}): المصدر غير متوفر أو غير قابل للوصول"
                Log.e(tag, errorMsg)
                markFailed(transferId, errorMsg)
                onComplete(false, errorMsg)
                return@withContext
            }
            testStream.close()
        } catch (e: Exception) {
            val errorMsg = "تعذر فتح مصدر الملف (${item.name}): ${e.localizedMessage ?: "الملف مفقود"}"
            Log.e(tag, errorMsg, e)
            markFailed(transferId, errorMsg)
            onComplete(false, errorMsg)
            return@withContext
        }

        try {
            announce(item, host, port, token, transferId, edge, baseUrl, transferAnnounceClient)
        } catch (cancelled: CancellationException) {
            markFailed(transferId, "تم إلغاء عملية النقل")
            onComplete(false, "تم الإلغاء")
            throw cancelled
        } catch (_: Exception) {
            val message = "تعذر تأكيد بدء النقل الآمن؛ لم يُرسل الملف ولم يُستخدم HTTP."
            markFailed(transferId, message)
            onComplete(false, message)
            return@withContext
        }

        val startTime = System.currentTimeMillis()
        var lastReportTime = startTime
        var lastBytesWritten = 0L
        var totalBytesWritten = 0L

        // Empty file has size 0, not 1. Unknown size has size -1.
        val isSizeKnown = item.sizeBytes >= 0
        val totalSize = if (isSizeKnown) item.sizeBytes else -1L

        val digest = MessageDigest.getInstance("SHA-256")

        val requestBody = object : RequestBody() {
            override fun contentType() = (item.mimeType.ifEmpty { "application/octet-stream" }).toMediaTypeOrNull()

            override fun contentLength(): Long = totalSize

            override fun writeTo(sink: BufferedSink) {
                val inputStream = context.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("تعذر فتح دفق القراءة للملف ${item.name}")

                inputStream.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int

                    while (stream.read(buffer).also { read = it } != -1) {
                        if (read <= 0) continue

                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        totalBytesWritten += read

                        val now = System.currentTimeMillis()
                        val interval = now - lastReportTime
                        if (interval >= 100 || (totalSize > 0 && totalBytesWritten >= totalSize)) {
                            val bytesInInterval = totalBytesWritten - lastBytesWritten
                            val speed = if (interval > 0) (bytesInInterval * 1000) / interval else 0L
                            val progress = if (totalSize > 0) {
                                (totalBytesWritten.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                            } else 0.5f

                            lastReportTime = now
                            lastBytesWritten = totalBytesWritten

                            updateProgress(transferId, progress, speed)
                        }
                    }
                    sink.flush()
                }

                // If file is 0 bytes (empty file)
                if (totalSize == 0L) {
                    updateProgress(transferId, 1f, 0L)
                }
            }
        }

        val endpoint = "/upload"
        val encodedFileName = encodeModernFileName(item.name)

        val requestBuilder = Request.Builder()
            .url(if (secureSession) baseUrl!!.newBuilder().addPathSegment("upload").build().toString()
                else "http://$host:$port$endpoint")
            .header("User-Agent", "AuraShare-Android-Client/1.0")
            .header("X-Device-Name", encodeModernFileName(PairingClient.deviceName()))
            .header("X-File-Name", encodedFileName)
            .header("X-File-Type", item.mimeType.ifEmpty { "application/octet-stream" })
            .header("X-Transfer-Id", transferId)
            .header("X-Direction", edge)

        if (isSizeKnown) {
            requestBuilder.header("X-File-Size", totalSize.toString())
        }

        if (!token.isNullOrBlank()) {
            requestBuilder.header("X-AuraShare-Token", token)
        }

        val request = requestBuilder.post(requestBody).build()
        val call = transferClient.newCall(request)

        try {
            val response: Response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    Log.d(tag, "Coroutine cancelled, cancelling OkHttp call for transfer $transferId")
                    call.cancel()
                }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, _, _ ->
                            response.close()
                        }
                    }
                })
            }

            processServerResponse(
                response = response,
                transferId = transferId,
                totalBytesWritten = totalBytesWritten,
                digest = digest,
                secure = secureSession,
                expectedSize = item.sizeBytes,
                onComplete = onComplete
            )

        } catch (c: CancellationException) {
            Log.w(tag, "Transfer $transferId was cancelled")
            call.cancel()
            markFailed(transferId, "تم إلغاء عملية النقل")
            onComplete(false, "تم الإلغاء")
            throw c // Rethrow CancellationException to properly cooperate with coroutine lifecycle
        } catch (ioe: IOException) {
            val errorMsg = if (call.isCanceled()) {
                "تم إلغاء عملية النقل"
            } else {
                "تعذر الاتصال بالخادم على $host:$port: ${ioe.localizedMessage ?: "انقطع الاتصال أو الخادم غير متاح"}"
            }
            Log.e(tag, errorMsg, ioe)
            markFailed(transferId, errorMsg)
            onComplete(false, errorMsg)
        } catch (e: Exception) {
            val errorMsg = "خطأ أثناء تدفق البيانات: ${e.localizedMessage ?: "فشل غير متوقع"}"
            Log.e(tag, errorMsg, e)
            markFailed(transferId, errorMsg)
            onComplete(false, errorMsg)
        }
    }

    private suspend fun announce(
        item: MediaItem,
        host: String,
        port: Int,
        token: String?,
        transferId: String,
        direction: String,
        secureBaseUrl: HttpUrl?,
        announceClientForTransfer: OkHttpClient
    ) {
        try {
            val mime = item.mimeType.ifEmpty { "application/octet-stream" }
            val kind = when {
                mime.startsWith("image/", ignoreCase = true) -> "image"
                mime.startsWith("video/", ignoreCase = true) -> "video"
                else -> "other"
            }
            val payload = JSONObject()
                .put("id", transferId)
                .put("direction", direction)
                .put("name", item.name)
                .put("size", item.sizeBytes)
                .put("mime", mime)
                .put("kind", kind)
                .put("thumb", thumbnails.generate(item) ?: JSONObject.NULL)
            val builder = Request.Builder()
                .url(if (secureBaseUrl != null) secureBaseUrl.newBuilder()
                    .addPathSegment("api").addPathSegment("transfer").addPathSegment("announce").build().toString()
                else "http://$host:$port/api/transfer/announce")
                .header("User-Agent", "AuraShare-Android-Client/1.0")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            if (!token.isNullOrBlank()) builder.header("X-AuraShare-Token", token)
            val call = announceClientForTransfer.newCall(builder.build())
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        if (secureBaseUrl != null) continuation.resumeWithException(e)
                        else {
                            Log.w(tag, "Announcement unavailable; continuing legacy file transfer", e)
                            continuation.resumeWith(Result.success(Unit))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        var failure: IOException? = null
                        response.use {
                            if (!it.isSuccessful && secureBaseUrl != null) failure = IOException("secure announce rejected (${it.code})")
                            else if (!it.isSuccessful) Log.w(tag, "Announcement HTTP ${it.code}; continuing legacy file transfer")
                        }
                        if (!continuation.isCancelled) {
                            if (failure != null) continuation.resumeWithException(failure!!)
                            else continuation.resumeWith(Result.success(Unit))
                        }
                    }
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (secureBaseUrl != null) throw e
            Log.w(tag, "Announcement failed; continuing file transfer", e)
        }
    }

    private fun processServerResponse(
        response: Response,
        transferId: String,
        totalBytesWritten: Long,
        digest: MessageDigest,
        secure: Boolean,
        expectedSize: Long,
        onComplete: (Boolean, String?) -> Unit
    ) {
        response.use { resp ->
            val code = resp.code
            val bodyString = resp.body?.string().orEmpty()

            if (code == 401) {
                val err = "رمز الاقتران غير صالح أو منتهي الصلاحية (HTTP 401). يرجى إعادة الاقتران بالحاسوب."
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            if (code == 423) {
                val err = "الخادم مشغول حالياً بتنفيذ الحد الأقصى من عمليات النقل المتزامنة (HTTP 423)"
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            if (code == 429) {
                val err = "تم تجاوز معدل الطلبات المسموح به في الخادم (HTTP 429)"
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            if (!resp.isSuccessful) {
                val serverMsg = try {
                    val errJson = JSONObject(bodyString)
                    errJson.optString("error", "").ifEmpty { errJson.optString("message", "") }
                } catch (_: Exception) {
                    ""
                }
                val detail = if (serverMsg.isNotEmpty()) ": $serverMsg" else ""
                val err = "رفض الخادم استلام الملف (رمز الخطأ HTTP $code$detail)"
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            // Verify 2xx response JSON
            if (bodyString.isBlank()) {
                val err = "استجابة الخادم فارغة ولا تحتوي على تأكيد الاستلام"
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            val json = try {
                JSONObject(bodyString)
            } catch (e: Exception) {
                val err = "استجابة الخادم تالفة وليست بصيغة JSON معتمدة"
                Log.e(tag, err, e)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            // 1. Verify status is 'completed'
            val serverStatus = json.optString("status", "")
            if (!serverStatus.equals("completed", ignoreCase = true)) {
                val err = "لم يكتمل النقل على الخادم (حالة الخادم: '$serverStatus'، المتوقع: 'completed')"
                Log.e(tag, err)
                markFailed(transferId, err)
                onComplete(false, err)
                return
            }

            // Secure sessions require exact server accounting; legacy keeps optional fields permissive.
            val received = json.optLong("received", -1L)
            if ((secure && (!json.has("received") || received != totalBytesWritten)) ||
                (!secure && json.has("received") && received >= 0 && received != totalBytesWritten)) {
                val err = "عدم تطابق حجم البيانات: الخادم استلم $received بايت بينما تم إرسال $totalBytesWritten بايت"
                markFailed(transferId, err); onComplete(false, err); return
            }
            if (secure && expectedSize >= 0 && totalBytesWritten != expectedSize) {
                val err = "حجم الملف المرسل لا يطابق الحجم المعروف: أرسل $totalBytesWritten من $expectedSize بايت"
                markFailed(transferId, err); onComplete(false, err); return
            }

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val serverSha256 = json.optString("sha256", "").trim()
            if ((secure && !Regex("^[0-9a-fA-F]{64}$").matches(serverSha256)) ||
                (serverSha256.isNotEmpty() && !serverSha256.equals(computedSha256, ignoreCase = true))) {
                val err = "فشل التحقق الأمني: بصمة SHA-256 مفقودة أو غير متطابقة"
                markFailed(transferId, err); onComplete(false, err); return
            }

            // All checks passed!
            Log.i(tag, "Transfer $transferId completed and verified successfully! SHA-256=$computedSha256, bytes=$totalBytesWritten")
            markCompleted(transferId)
            onComplete(true, null)
        }
    }

    private fun updateProgress(transferId: String, progress: Float, speedBytesPerSec: Long) {
        _currentTransfer.update { current ->
            if (current?.id == transferId) {
                current.copy(
                    progress = progress,
                    speedBytesPerSec = speedBytesPerSec,
                    status = TransferStatus.STREAMING
                )
            } else current
        }

        _activeTransfers.update { list ->
            list.map {
                if (it.id == transferId) {
                    it.copy(progress = progress, speedBytesPerSec = speedBytesPerSec, status = TransferStatus.STREAMING)
                } else it
            }
        }
    }

    private fun markCompleted(transferId: String) {
        _currentTransfer.update { current ->
            if (current?.id == transferId) {
                current.copy(
                    progress = 1f,
                    status = TransferStatus.COMPLETED
                )
            } else current
        }

        _activeTransfers.update { list ->
            list.map {
                if (it.id == transferId) {
                    it.copy(progress = 1f, status = TransferStatus.COMPLETED)
                } else it
            }
        }
    }

    private fun markFailed(transferId: String, error: String) {
        _currentTransfer.update { current ->
            if (current?.id == transferId) {
                current.copy(
                    status = TransferStatus.FAILED,
                    error = error
                )
            } else current
        }

        _activeTransfers.update { list ->
            list.map {
                if (it.id == transferId) {
                    it.copy(status = TransferStatus.FAILED, error = error)
                } else it
            }
        }
    }

    fun clearActiveTransfer() {
        _currentTransfer.value = null
    }
}
