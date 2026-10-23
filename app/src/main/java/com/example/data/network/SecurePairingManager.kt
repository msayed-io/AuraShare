package com.example.data.network

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.conscrypt.Conscrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Non-secret identity view. Discovery hints are NOT authentication. */
data class TrustedDevice(val id: String, val name: String, val host: String, val port: Int, val certificate: String)
class SecureConnection(val device: TrustedDevice, val client: OkHttpClient, val baseUrl: String, val token: String)
data class SecureState(
    val devices: List<TrustedDevice> = emptyList(),
    val connected: SecureConnection? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val trustAvailable: Boolean = true,
)
interface SecureStorage { fun read(): ByteArray?; fun writeAtomically(bytes: ByteArray) }
interface SecureStoreCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }

/** Private app directory, temporary file + fsync + same-filesystem atomic rename. */
class AtomicNoBackupStorage(private val file: File) : SecureStorage {
    override fun read(): ByteArray? {
        if (!file.exists()) return null
        require(file.length() in 1..1_048_576) { "invalid secure store size" }
        return file.readBytes()
    }
    override fun writeAtomically(bytes: ByteArray) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            check(tmp.renameTo(file)) { "secure store atomic rename failed" }
        } finally { if (tmp.exists()) tmp.delete() }
    }
}

/** Uses standard AES-GCM and a non-exportable Android Keystore key, not a custom primitive. */
class AndroidKeystoreStoreCipher : SecureStoreCipher {
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 29 && blob[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob.copyOfRange(13, blob.size))
    }
    companion object {
        private val KEY_LOCK = Any()
        private const val ALIAS = "aurashare-secure-trust-v3"
        private val AAD = "AuraShare/trust/v3".toByteArray(Charsets.UTF_8)
    }
}

class SecurePairingManager(
    context: Context,
    private val storage: SecureStorage = AtomicNoBackupStorage(File(context.noBackupFilesDir, "aurashare-secure.blob")),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val addressAllowed: (String) -> Boolean = ::isAllowedLanIpv4,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val storeCipher: SecureStoreCipher = AndroidKeystoreStoreCipher(),
) : AutoCloseable {
    private val _state = MutableStateFlow(SecureState(trustAvailable = false))
    val state: StateFlow<SecureState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val records = linkedMapOf<String, Record>()
    private var clientId = UUID.randomUUID().toString()
    private var heartbeatJob: Job? = null
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private var storeCorrupt = false

    init {
        try {
            val blob = storage.read()
            if (blob != null) {
                val j = JSONObject(String(storeCipher.decrypt(blob), Charsets.UTF_8))
                require(j.getInt("version") == 3)
                clientId = j.getString("client_id").also { require(it.matches(CLIENT_ID)) }
                val array = j.getJSONArray("devices")
                require(array.length() <= 128)
                for (i in 0 until array.length()) {
                    val r = Record.from(array.getJSONObject(i))
                    require(r.id.matches(HEX64) && validIpv4(r.host) && addressAllowed(r.host) && r.port in 1..65535)
                    require(r.credential == null || r.credential.matches(HEX64))
                    require(records.put(r.id, r) == null)
                    // Identity continuity is checked even when certificate validity has since expired.
                    require(spkiHex(decodeCertificate(r.certificate)) == r.id)
                }
            } else persist(records)
            publish()
        } catch (_: Exception) {
            records.clear(); storeCorrupt = true
            _state.value = SecureState(trustAvailable = false, error = "تعذر فتح سجل الثقة المحمي. أُوقف الاقتران والاتصال القديم لمنع فقد الهوية؛ لم تُحذف البيانات.")
        }
    }

    suspend fun pairQr(raw: String) = operation {
        val qr = QrPayload.parse(raw, nowMs(), addressAllowed)
        require(records[qr.serverId]?.credential == null) { "already trusted" }
        require(records.values.none { it.host == qr.host && it.port == qr.port && it.id != qr.serverId }) { "identity changed" }
        val http = SecureHttpClientFactory().create(qr)
        request(http, qr.baseUrl, "/api/secure/handshake").use {
            require(it.code == 200)
            val j = json(it)
            require(j.getString("server") == "AuraShare" && j.getInt("protocol") == 3 && j.getString("server_id") == qr.serverId)
        }
        val body = JSONObject().put("client_id", clientId).put("device_name", PairingClient.deviceName()).put("bootstrap", qr.bootstrap)
        val start = request(http, qr.baseUrl, "/api/secure/pair/request", body = body).use {
            require(it.code == 202)
            val j = json(it)
            PairStart(j.getString("request_id"), j.getString("request_secret")).also { p ->
                require(p.id.matches(Regex("[A-Za-z0-9-]{1,100}")) && p.secret.matches(HEX64))
            }
        }
        var approved: JSONObject? = null
        val deadline = nowMs() + 120_000
        var attempts = 0
        while (approved == null && nowMs() <= deadline && attempts++ < 120) {
            currentCoroutineContext().ensureActive()
            request(http, qr.baseUrl, "/api/secure/pair/status/${start.id}", headers = mapOf("X-AuraShare-Pair-Secret" to start.secret)).use {
                require(it.code == 200 || it.code == 410)
                val j = json(it)
                when (j.getString("status")) {
                    "pending" -> Unit
                    "approved" -> { require(it.code == 200); approved = j }
                    else -> error("pairing denied or expired")
                }
            }
            if (approved == null) delay(1_000)
        }
        val result = requireNotNull(approved)
        require(result.getString("server_id") == qr.serverId)
        val credential = result.getString("credential").also { require(it.matches(HEX64)) }
        val name = result.optString("device_name", "AuraShare Desktop").replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(80).ifBlank { "AuraShare Desktop" }
        val record = Record(qr.serverId, name, qr.host, qr.port, qr.certificate, credential)
        commit(records.toMutableMap().apply { put(record.id, record) })
        disconnectInternal()
        connectInternal(record.id, null, null)
    }

    suspend fun connect(id: String, hostHint: String? = null, portHint: Int? = null) = operation {
        disconnectInternal()
        connectInternal(id, hostHint, portHint)
    }

    private suspend fun connectInternal(id: String, hostHint: String?, portHint: Int?) {
        val record = requireNotNull(records[id])
        val credential = requireNotNull(record.credential)
        val host = hostHint ?: record.host
        val port = portHint ?: record.port
        require(validIpv4(host) && addressAllowed(host) && port in 1..65535)
        val updated = record.copy(host = host, port = port)
        val payload = updated.payload()
        val http = SecureHttpClientFactory().create(payload)
        val token = request(http, payload.baseUrl, "/api/secure/session/connect",
            body = JSONObject().put("client_id", clientId), headers = mapOf("X-AuraShare-Device-Credential" to credential)).use {
            if (it.code == 401) {
                commit(records.toMutableMap().apply { put(id, record.copy(credential = null)) })
                error("credential revoked")
            }
            require(it.code == 200)
            val j = json(it); require(j.getString("server_id") == id)
            j.getString("token").also { t -> require(t.matches(HEX64)) }
        }
        currentCoroutineContext().ensureActive(); check(!closed)
        if (updated != record) commit(records.toMutableMap().apply { put(id, updated) })
        val connection = SecureConnection(updated.public(), http, payload.baseUrl, token)
        _state.update { it.copy(connected = connection) }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !closed) {
                delay(10_000)
                if (_state.value.connected !== connection) break
                val alive = try {
                    request(http, payload.baseUrl, "/api/secure/session/heartbeat", body = JSONObject(), headers = mapOf("X-AuraShare-Token" to token)).use {
                        it.code == 200 && json(it).optString("status") == "connected"
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (!alive) {
                    _state.update { if (it.connected === connection) it.copy(connected = null, error = "انقطع الاتصال الآمن؛ يمكنك إعادة الاتصال بالجهاز المحفوظ.") else it }
                    break
                }
            }
        }
    }

    suspend fun disconnect() = operation { disconnectInternal() }
    private fun disconnectInternal() {
        heartbeatJob?.cancel(); heartbeatJob = null
        val connection = _state.value.connected
        _state.update { it.copy(connected = null) }
        if (connection != null) runCatching {
            request(connection.client, connection.baseUrl, "/api/secure/session/disconnect", body = JSONObject(), headers = mapOf("X-AuraShare-Token" to connection.token)).close()
        }
    }

    suspend fun forget(id: String) = operation {
        val record = records[id] ?: return@operation
        if (_state.value.connected?.device?.id == id) disconnectInternal()
        // Local revocation is durable even if the peer is offline. Never claim remote success without its reply.
        commit(records.toMutableMap().apply { remove(id) })
        val revoked = record.credential?.let { credential -> runCatching {
            val p = record.payload()
            request(SecureHttpClientFactory().create(p), p.baseUrl, "/api/secure/device/revoke", body = JSONObject(),
                headers = mapOf("X-AuraShare-Device-Credential" to credential)).use { it.code == 200 || it.code == 204 }
        }.getOrDefault(false) } ?: true
        if (!revoked) _state.update { it.copy(error = "أُلغيت الثقة على الهاتف. تعذر تأكيد إلغائها على الكمبيوتر؛ احذف اعتماد الهاتف من الكمبيوتر عندما يتاح.") }
    }

    private suspend fun operation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            if (storeCorrupt) { _state.update { it.copy(trustAvailable = false) }; return@withLock }
            _state.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _state.update { it.copy(error = "تعذر الاتصال الآمن أو التحقق من الهوية/الموافقة. لم يُستخدم HTTP. تحقق من الجهاز وصلاحية QR؛ إذا تغيرت الهوية فألغِ الثقة القديمة ثم أعد الاقتران.") }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    override fun close() {
        closed = true; activeCalls.forEach { it.cancel() }; heartbeatJob?.cancel(); scope.cancel()
        _state.update { it.copy(connected = null, busy = false) }
    }
    private fun request(client: OkHttpClient, base: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): Response {
        check(!closed)
        val request = Request.Builder().url(base + path).apply {
            if (body == null) get() else post(body.toString().toRequestBody(JSON))
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val call = client.newCall(request)
        activeCalls.add(call)
        return try { call.execute() } finally { activeCalls.remove(call) }
    }
    private fun json(response: Response): JSONObject {
        val body = response.body ?: error("missing response")
        require(body.contentLength() <= 16_384)
        val source = body.source(); require(!source.request(16_385)) { "response too large" }
        return JSONObject(source.readUtf8())
    }
    private fun persist(next: Map<String, Record>) {
        val j = JSONObject().put("version", 3).put("client_id", clientId).put("devices", JSONArray(next.values.map { it.json() }))
        storage.writeAtomically(storeCipher.encrypt(j.toString().toByteArray(Charsets.UTF_8)))
    }
    private fun commit(next: Map<String, Record>) {
        check(!closed); require(next.size <= 128)
        persist(next); records.clear(); records.putAll(next); publish()
    }
    private fun publish() { _state.update { it.copy(devices = records.values.map(Record::public), trustAvailable = !storeCorrupt) } }
    private data class PairStart(val id: String, val secret: String)
    private data class Record(val id: String, val name: String, val host: String, val port: Int, val certificate: String, val credential: String?) {
        fun public() = TrustedDevice(id, name, host, port, certificate)
        fun payload() = QrPayload(id, host, port, certificate, "", Long.MAX_VALUE)
        fun json() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("certificate", certificate).put("credential", credential ?: JSONObject.NULL)
        companion object {
            fun from(j: JSONObject) = Record(j.getString("id"), j.getString("name"), j.getString("host"), j.getInt("port"), j.getString("certificate"),
                if (j.isNull("credential")) null else j.getString("credential"))
        }
    }
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val CLIENT_ID = Regex("[A-Za-z0-9-]{1,80}")
        internal val HEX64 = Regex("[0-9a-f]{64}")
        internal fun validIpv4(host: String): Boolean = host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) &&
            host.split('.').all { it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) }
        fun isAllowedLanIpv4(host: String): Boolean {
            if (!validIpv4(host)) return false
            val b = host.split('.').map { it.toInt() }
            return b[0] == 10 || b[0] == 172 && b[1] in 16..31 || b[0] == 192 && b[1] == 168 || b[0] == 169 && b[1] == 254
        }
    }
}

internal data class QrPayload(val serverId: String, val host: String, val port: Int, val certificate: String, val bootstrap: String, val expiresAt: Long) {
    val baseUrl get() = "https://aurashare.local:$port"
    companion object {
        fun parse(raw: String, now: Long, addressAllowed: (String) -> Boolean): QrPayload {
            require(raw.toByteArray(Charsets.UTF_8).size in 1..8192)
            val j = JSONObject(raw)
            require(j.getString("type") == "aurashare-secure-pair" && j.getInt("version") == 3)
            val host = j.getString("host"); require(SecurePairingManager.validIpv4(host) && addressAllowed(host))
            val port = j.getInt("port"); require(port in 1..65535)
            val cert = j.getString("certificate"); val x509 = decodeCertificate(cert); x509.checkValidity(java.util.Date(now))
            val id = j.getString("server_id"); require(id.matches(SecurePairingManager.HEX64) && spkiHex(x509) == id)
            val bootstrap = j.getString("bootstrap"); require(bootstrap.matches(SecurePairingManager.HEX64))
            val expiry = j.getLong("expires_at"); require(expiry > now && expiry - now <= 300_000)
            return QrPayload(id, host, port, cert, bootstrap, expiry)
        }
    }
}
internal fun decodeCertificate(value: String): X509Certificate {
    require(value.length in 1..7000)
    val bytes = Base64.decode(value, Base64.NO_WRAP)
    val input = bytes.inputStream()
    val cert = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
    require(input.available() == 0)
    require(!cert.sigAlgName.contains("SHA1", true) && !cert.sigAlgName.contains("MD5", true))
    when (val key = cert.publicKey) {
        is ECPublicKey -> require(key.params.curve.field.fieldSize >= 256)
        is RSAPublicKey -> require(key.modulus.bitLength() >= 2048)
        else -> error("unsupported identity key")
    }
    return cert
}
internal fun spkiHex(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

internal class SecureHttpClientFactory {
    fun create(qr: QrPayload): OkHttpClient {
        val cert = decodeCertificate(qr.certificate)
        cert.checkValidity(); require(spkiHex(cert) == qr.serverId)
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("qr-approved-peer", cert) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        // Explicit provider only for this connection. Never change global TLS defaults or trust.
        val vm = System.getProperty("java.vm.name").orEmpty()
        val ssl = if (vm.contains("Dalvik", true) || vm.contains("ART", true)) SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()) else SSLContext.getInstance("TLSv1.3")
        ssl.init(null, arrayOf(tm), null)
        val pin = "sha256/" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .certificatePinner(CertificatePinner.Builder().add("aurashare.local", pin).build())
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != "aurashare.local") throw UnknownHostException("unexpected secure hostname")
                    return listOf(InetAddress.getByName(qr.host))
                }
            })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                require(chain.request().url.scheme == "https" && chain.request().url.host == "aurashare.local" && chain.request().url.port == qr.port)
                cert.checkValidity()
                chain.proceed(chain.request())
            }.build()
    }
}
