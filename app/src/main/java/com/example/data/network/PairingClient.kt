package com.example.data.network

import android.os.Build
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** HTTP-only implementation of aurashare-pairing-contract.md. No credentials are logged. */
class PairingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
) {
    data class PeerInfo(
        val hostname: String,
        val protocol: Int,
        val requiresPairing: Boolean,
        val serverId: String,
        val secureServerId: String? = null,
        val securePort: Int? = null
    )
    data class PairRequest(val requestId: String, val secret: String)
    data class PairStatus(val status: String, val token: String?, val deviceName: String?, val serverId: String?)
    data class Endpoint(val host: String, val port: Int) {
        init {
            require(port in 1..65535) { "invalid port" }
            require(isIpv4(host)) { "AuraShare desktop endpoints must use IPv4" }
        }
        companion object {
            private fun isIpv4(value: String): Boolean = value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) &&
                runCatching { InetAddress.getByName(value) is Inet4Address && value.split('.').all { it.toInt() in 0..255 } }.getOrDefault(false)
        }
    }

    fun handshake(endpoint: Endpoint): PeerInfo {
        execute(Request.Builder().url(url(endpoint, "/api/handshake")).get().build()).use { response ->
            require(response.code == 200) { "handshake rejected (${response.code})" }
            val json = jsonBody(response)
            require(json.optString("server").equals("AuraShare", true)) { "not an AuraShare server" }
            require(json.optInt("protocol", -1) == 2) { "unsupported protocol" }
            require(json.optBoolean("requires_pairing", false)) { "pairing is required" }
            require(json.optString("pairing_method") == "approval") { "unsupported pairing method" }
            val serverId = json.optString("server_id"); require(serverId.isNotBlank()) { "missing server_id" }
            val hostname = json.optString("hostname").ifEmpty { json.optString("server") }
            require(hostname.isNotBlank()) { "missing hostname" }
            val candidateSecureServerId = json.optString("secure_server_id")
            val candidateSecurePort = json.optInt("secure_port", -1)
            val securePairValid = candidateSecureServerId.matches(SECURE_SERVER_ID) && candidateSecurePort in 1..65535
            return PeerInfo(
                hostname,
                2,
                true,
                serverId,
                candidateSecureServerId.takeIf { securePairValid },
                candidateSecurePort.takeIf { securePairValid }
            )
        }
    }

    fun requestPair(endpoint: Endpoint, clientId: String, deviceName: String): PairRequest {
        require(clientId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "invalid client id" }
        require(deviceName.isNotBlank() && deviceName.length <= 80 && deviceName.none { it.isISOControl() || it.category == CharCategory.FORMAT }) { "invalid device name" }
        val secret = randomSecret()
        val body = JSONObject().put("client_id", clientId).put("device_name", deviceName).put("client_secret", secret).toString().toRequestBody(JSON)
        execute(Request.Builder().url(url(endpoint, "/api/pair/request")).post(body).build()).use { response ->
            require(response.code == 202) { "pair request rejected (${response.code})" }
            val json = jsonBody(response); require(json.optString("status") == "pending") { "invalid pair response" }
            val id = json.optString("request_id"); require(id.isNotBlank()) { "missing request_id" }
            return PairRequest(id, secret)
        }
    }

    suspend fun awaitApproval(endpoint: Endpoint, request: PairRequest, timeoutMs: Long = 120_000L): PairStatus {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeoutMs) {
            execute(Request.Builder().url(url(endpoint, "/api/pair/status/${request.requestId}")).header("X-AuraShare-Pair-Secret", request.secret).get().build()).use { response ->
                if (response.code == 401 || response.code == 404) error("pair status unauthorized")
                if (response.code != 200 && response.code != 410) error("pair status rejected (${response.code})")
                val json = jsonBody(response); val status = json.optString("status")
                require(status in setOf("pending", "approved", "rejected", "expired")) { "invalid pair status" }
                val serverId = json.optString("server_id").ifEmpty { null }
                val result = PairStatus(status, json.optString("token").ifEmpty { null }, json.optString("device_name").ifEmpty { null }, serverId)
                if (status == "approved") require(!result.token.isNullOrBlank() && !serverId.isNullOrBlank()) { "approved response missing token/server_id" }
                if (status != "pending") return result
            }
            delay(1_000)
        }
        return PairStatus("expired", null, null, null)
    }

    fun heartbeat(endpoint: Endpoint, token: String): Boolean = execute(Request.Builder().url(url(endpoint, "/api/session/heartbeat")).header("X-AuraShare-Token", token).post(EMPTY).build()).use { response ->
        response.code == 200 && runCatching { jsonBody(response).optString("status") == "connected" }.getOrDefault(false)
    }

    fun disconnect(endpoint: Endpoint, token: String): Boolean = execute(Request.Builder().url(url(endpoint, "/api/session/disconnect")).header("X-AuraShare-Token", token).post(EMPTY).build()).use { response ->
        response.code == 200 && runCatching { jsonBody(response).optString("status") == "disconnected" }.getOrDefault(false)
    }

    private fun execute(request: Request) = client.newCall(request).execute().also { require(it.request.header("Origin") == null) }
    private fun jsonBody(response: okhttp3.Response): JSONObject = JSONObject(response.body?.string().orEmpty())
    private fun url(endpoint: Endpoint, path: String) = "http://${endpoint.host}:${endpoint.port}$path"
    companion object {
        private val SECURE_SERVER_ID = Regex("[0-9a-f]{64}")
        private val JSON = "application/json; charset=utf-8".toMediaType(); private val EMPTY = ByteArray(0).toRequestBody(null)
        fun randomSecret(): String { val bytes = ByteArray(32); SecureRandom().nextBytes(bytes); return bytes.joinToString("") { "%02x".format(it) } }
        fun defaultClientId(prefs: android.content.SharedPreferences): String = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client_id", it).apply() }
        fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim().take(80)
    }
}
