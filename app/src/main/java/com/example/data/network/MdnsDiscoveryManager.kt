package com.example.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

sealed class DiscoveryState {
    object Idle : DiscoveryState()
    data class Searching(val message: String = "جاري رصد حاسوب AuraShare عبر الشبكة...") : DiscoveryState()
    data class Discovered(val serviceName: String, val host: String, val port: Int) : DiscoveryState()
    data class Connected(
        val serviceName: String,
        val host: String,
        val port: Int,
        val deviceName: String,
        val requiresPairing: Boolean = false,
        val protocolVersion: Int = 1
    ) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}

class MdnsDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val tag = "MdnsDiscoveryManager"
    private val serviceType = "_aurashare._tcp"

    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscovering = false
    private var handshakeJob: Job? = null

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    var activeToken: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(tag, "Start discovery failed: $errorCode")
            _state.value = DiscoveryState.Error("تعذر تشغيل مستكشف الشبكة المحلية (رمز: $errorCode)")
            isDiscovering = false
            try {
                nsdManager?.stopServiceDiscovery(this)
            } catch (t: Throwable) {
                Log.w(tag, "Failed to stop discovery on start failure: ${t.message}")
            } finally {
                releaseMulticastLock()
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(tag, "Stop discovery failed: $errorCode")
            isDiscovering = false
            releaseMulticastLock()
        }

        override fun onDiscoveryStarted(regType: String?) {
            Log.d(tag, "Service discovery started: $regType")
            _state.value = DiscoveryState.Searching("جاري رصد حاسوب AuraShare عبر mDNS...")
            isDiscovering = true
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            Log.d(tag, "Discovery stopped: $serviceType")
            isDiscovering = false
            releaseMulticastLock()
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            Log.d(tag, "Service found: ${serviceInfo?.serviceName}")
            val name = serviceInfo?.serviceName ?: return
            resolveService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            Log.d(tag, "Service lost: ${serviceInfo?.serviceName}")
            if (_state.value is DiscoveryState.Connected || _state.value is DiscoveryState.Discovered) {
                _state.value = DiscoveryState.Searching("فُقد الاتصال بالحاسوب، جاري إعادة الرصد...")
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return

        try {
            acquireMulticastLock()
            _state.value = DiscoveryState.Searching("جاري البحث عن الحاسوب عبر الشبكة المحلية...")
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
        } catch (t: Throwable) {
            Log.e(tag, "Error starting mDNS discovery", t)
            _state.value = DiscoveryState.Error("فشل بدء الرصد اللاسلكي: ${t.localizedMessage}")
            isDiscovering = false
            releaseMulticastLock()
        }
    }

    fun stopDiscovery() {
        try {
            if (isDiscovering) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error stopping discovery", t)
        } finally {
            isDiscovering = false
            releaseMulticastLock()
        }
    }

    private var isResolving = false

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (isResolving) return
        isResolving = true
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(tag, "Resolve failed: $errorCode")
                    isResolving = false
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                    isResolving = false
                    val host: InetAddress? = resolvedInfo?.host
                    val port = resolvedInfo?.port ?: 0
                    val hostAddress = host?.hostAddress ?: return
                    val serviceName = resolvedInfo.serviceName ?: "AuraShare Server"

                    if (port <= 0) {
                        Log.w(tag, "Resolved service with invalid port: $port")
                        return
                    }

                    Log.i(tag, "Resolved service: $serviceName at $hostAddress:$port")
                    _state.value = DiscoveryState.Discovered(serviceName, hostAddress, port)
                    performHandshake(hostAddress, port, serviceName, activeToken)
                }
            })
        } catch (e: Throwable) {
            isResolving = false
            Log.e(tag, "Exception resolving service", e)
        }
    }

    fun connectManually(host: String, port: Int, token: String? = null) {
        activeToken = token
        _state.value = DiscoveryState.Searching("جاري التحقق من هوية الخادم على $host:$port...")
        performHandshake(host, port, "AuraShare Desktop", token)
    }

    fun performHandshake(host: String, port: Int, serviceName: String, token: String? = activeToken) {
        handshakeJob?.cancel()
        handshakeJob = scope.launch(Dispatchers.IO) {
            val handshakeUrl = "http://$host:$port/api/handshake"
            val healthUrl = "http://$host:$port/health"
            val pingUrl = "http://$host:$port/ping"

            var verified = false
            var deviceLabel = serviceName
            var requiresPairing = false
            var protocolVersion = 1
            var failureReason: String? = null

            var shouldAttemptFallback = false

            // Primary attempt: GET /api/handshake
            try {
                val reqBuilder = Request.Builder()
                    .url(handshakeUrl)
                    .header("User-Agent", "AuraShare-Android-Client/1.0")
                    .get()

                if (!token.isNullOrBlank()) {
                    reqBuilder.header("X-AuraShare-Token", token)
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        try {
                            val json = JSONObject(body)
                            val server = json.optString("server", "")
                            if (server.equals("AuraShare", ignoreCase = true)) {
                                verified = true
                                deviceLabel = json.optString("hostname", serviceName).ifEmpty { serviceName }
                                protocolVersion = json.optInt("protocol", 1)
                                requiresPairing = json.optBoolean("requires_pairing", false)
                            } else {
                                failureReason = "الخادم المستجيب ليس خادم AuraShare الرسمي (server='$server')"
                            }
                        } catch (e: Exception) {
                            failureReason = "بيانات المصافحة ليست بتنسيق JSON معتمد من AuraShare"
                        }
                    } else if (response.code == 404) {
                        shouldAttemptFallback = true
                    } else {
                        failureReason = "رفض الخادم المصافحة (HTTP ${response.code})"
                    }
                }
            } catch (e: IOException) {
                Log.w(tag, "Handshake failed on /api/handshake: ${e.message}")
                failureReason = "تعذر الوصول إلى $handshakeUrl: ${e.localizedMessage ?: "فشل الاتصال"}"
                shouldAttemptFallback = true
            }

            // Fallback 1: GET /health if /api/handshake failed with 404
            if (!verified && shouldAttemptFallback) {
                try {
                    val req = Request.Builder()
                        .url(healthUrl)
                        .header("User-Agent", "AuraShare-Android-Client/1.0")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            try {
                                val json = JSONObject(body)
                                val server = json.optString("server", "")
                                if (server.equals("AuraShare", ignoreCase = true)) {
                                    verified = true
                                    deviceLabel = json.optString("hostname", serviceName).ifEmpty { serviceName }
                                    protocolVersion = json.optInt("protocol", 1)
                                    requiresPairing = json.optBoolean("requires_pairing", false)
                                    failureReason = null
                                }
                            } catch (_: Exception) {
                                // Body may be plain text 'ok' or similar
                                if (body.contains("AuraShare", ignoreCase = true)) {
                                    verified = true
                                    failureReason = null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Health fallback failed: ${e.message}")
                }
            }

            // Fallback 2: GET /ping
            if (!verified && shouldAttemptFallback) {
                try {
                    val req = Request.Builder()
                        .url(pingUrl)
                        .header("User-Agent", "AuraShare-Android-Client/1.0")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            if (body.contains("AuraShare", ignoreCase = true) || body.equals("pong", ignoreCase = true)) {
                                verified = true
                                failureReason = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Ping fallback failed: ${e.message}")
                }
            }

            if (verified) {
                Log.i(tag, "Successfully connected and verified AuraShare server at $host:$port ($deviceLabel)")
                _state.value = DiscoveryState.Connected(
                    serviceName = serviceName,
                    host = host,
                    port = port,
                    deviceName = deviceLabel,
                    requiresPairing = requiresPairing,
                    protocolVersion = protocolVersion
                )
            } else {
                Log.e(tag, "Server at $host:$port failed AuraShare verification: $failureReason")
                // CRITICAL: NEVER set DiscoveryState.Connected if handshake failed or unverified!
                _state.value = DiscoveryState.Error(
                    failureReason ?: "تعذر التحقق من خادم AuraShare على $host:$port (استجابة غير معتمدة أو الخادم متوقف)"
                )
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("AuraShareMdnsLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to acquire multicast lock: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to release multicast lock: ${t.message}")
        } finally {
            multicastLock = null
        }
    }
}
