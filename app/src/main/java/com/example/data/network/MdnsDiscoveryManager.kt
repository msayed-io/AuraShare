package com.example.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data class Searching(val message: String = "جاري رصد حاسوب AuraShare عبر mDNS...") : DiscoveryState()
    data class Nearby(val peers: List<NearbyPeer>) : DiscoveryState()
    data class Discovered(val serviceName: String, val host: String, val port: Int) : DiscoveryState()
    data class Connected(val serviceName: String, val host: String, val port: Int, val deviceName: String, val requiresPairing: Boolean = true, val protocolVersion: Int = 2) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}
data class NearbyPeer(val serviceName: String, val host: String, val port: Int, val deviceName: String? = null, val status: PeerStatus = PeerStatus.NEARBY, val serviceType: String = "_aurashare._tcp")
enum class PeerStatus { NEARBY, PAIRING, CONNECTED, DISCONNECTED }

/** Small seam for deterministic lifecycle tests; production delegates to Android's NsdManager. */
interface NsdDiscoveryClient {
    fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener)
    fun stop(listener: NsdManager.DiscoveryListener)
    fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener)
}
private class AndroidNsdDiscoveryClient(private val nsd: NsdManager?) : NsdDiscoveryClient {
    override fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) { nsd?.discoverServices(type, protocol, listener) }
    override fun stop(listener: NsdManager.DiscoveryListener) { nsd?.stopServiceDiscovery(listener) }
    override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { nsd?.resolveService(info, listener) }
}

class MdnsDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nsdClient: NsdDiscoveryClient = AndroidNsdDiscoveryClient(context.getSystemService(Context.NSD_SERVICE) as? NsdManager)
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val client = PairingClient()
    private val peers = ConcurrentHashMap<String, NearbyPeer>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val tokens = ConcurrentHashMap<String, String>()
    private val resolveQueue = ArrayDeque<ResolveItem>()
    private val serviceEpoch = ConcurrentHashMap<String, Long>()
    private val generation = AtomicLong(0)
    private val stateLock = Any()
    private var activeResolve: ResolveItem? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var requested = false
    @Volatile private var discovering = false
    @Volatile private var activeRun: Run? = null
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private data class ResolveItem(val key: String, val info: NsdServiceInfo, val run: Long, val epoch: Long)
    private class Run(val generation: Long) : NsdManager.DiscoveryListener {
        lateinit var owner: MdnsDiscoveryManager
        override fun onDiscoveryStarted(regType: String?) = owner.started(this)
        override fun onDiscoveryStopped(serviceType: String?) = owner.stopped(this)
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = owner.startFailed(this)
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = owner.stopFailed(this)
        override fun onServiceFound(info: NsdServiceInfo?) { owner.found(this, info) }
        override fun onServiceLost(info: NsdServiceInfo?) { owner.lost(this, info) }
    }

    private fun publish() { _state.value = if (peers.isEmpty()) DiscoveryState.Searching() else DiscoveryState.Nearby(peers.values.sortedBy { it.deviceName ?: "AuraShare (${it.host})" }) }
    private fun current(run: Run) = activeRun === run && requested && generation.get() == run.generation
    private fun type(value: String?) = value.orEmpty().trimEnd('.')
    private fun key(name: String?, serviceType: String?) = "${name.orEmpty()}|${type(serviceType)}"

    fun startDiscovery() {
        synchronized(stateLock) { if (requested) return; requested = true }
        val run = Run(generation.incrementAndGet()).also { it.owner = this }
        activeRun = run; discovering = true // mark before the async callback: duplicate starts are rejected
        jobs.values.forEach { it.cancel() }; jobs.clear(); tokens.clear(); peers.clear()
        synchronized(stateLock) { resolveQueue.clear(); activeResolve = null; serviceEpoch.clear() }
        publish(); acquireLock()
        try { nsdClient.discover("_aurashare._tcp", NsdManager.PROTOCOL_DNS_SD, run) }
        catch (_: Throwable) { if (current(run)) { requested = false; discovering = false; releaseLock(); _state.value = DiscoveryState.Error("فشل بدء اكتشاف الشبكة") } }
    }

    private fun started(run: Run) { if (current(run)) { discovering = true; publish() } }
    private fun stopped(run: Run) {
        if (!current(run)) return
        requested = false; discovering = false
        synchronized(stateLock) { resolveQueue.clear(); activeResolve = null }
        releaseLock(); publish()
    }
    private fun startFailed(run: Run) {
        if (!current(run)) return
        requested = false; discovering = false
        synchronized(stateLock) { resolveQueue.clear(); activeResolve = null }
        releaseLock(); _state.value = DiscoveryState.Error("تعذر تشغيل اكتشاف الشبكة"); scheduleRetry(run.generation)
    }
    private fun stopFailed(run: Run) { if (current(run)) { requested = false; discovering = false; releaseLock() } }
    private fun scheduleRetry(ticket: Long) { scope.launch { delay(1_000); if (ticket == generation.get() && !requested) startDiscovery() } }

    private fun found(run: Run, info: NsdServiceInfo?) { if (current(run) && info?.serviceName != null) enqueueResolve(run, info) }
    private fun lost(run: Run, info: NsdServiceInfo?) {
        if (!current(run) || info == null) return
        val k = key(info.serviceName, info.serviceType); serviceEpoch[k] = (serviceEpoch[k] ?: 0L) + 1L
        val oldToken = tokens.remove(k); jobs.remove(k)?.cancel(); peers.remove(k)
        // Android permits one native resolve at a time. A lost service invalidates
        // its result, but does not cancel the underlying native operation.
        synchronized(stateLock) { resolveQueue.removeAll { it.key == k } }
        if (oldToken != null) scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(info.host?.hostAddress ?: return@launch, info.port), oldToken) } }
        publish(); resolveNext()
    }

    private fun enqueueResolve(run: Run, info: NsdServiceInfo) {
        val k = key(info.serviceName, info.serviceType)
        serviceEpoch.putIfAbsent(k, 0L)
        val epoch = serviceEpoch[k] ?: 0L
        synchronized(stateLock) {
            if (!current(run) || peers.containsKey(k) || resolveQueue.any { it.key == k } || activeResolve?.key == k) return
            resolveQueue.addLast(ResolveItem(k, info, run.generation, epoch))
        }
        resolveNext()
    }
    private fun resolveNext() {
        val item = synchronized(stateLock) { if (activeResolve != null || resolveQueue.isEmpty()) null else resolveQueue.removeFirst().also { activeResolve = it } } ?: return
        val run = activeRun; if (run == null || run.generation != item.run || !current(run)) { finishResolve(item); return }
        try { nsdClient.resolve(item.info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { finishResolve(item); resolveNext() }
            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                if (!valid(item)) { finishResolve(item); resolveNext(); return }
                val host = resolved?.host; val port = resolved?.port ?: -1
                if (host is Inet4Address && port in 1..65535 && host.hostAddress != null) {
                    val peer = NearbyPeer(resolved?.serviceName ?: item.info.serviceName ?: "AuraShare", host.hostAddress!!, port, serviceType = type(resolved?.serviceType ?: item.info.serviceType))
                    peers[item.key] = peer; publish()
                    scope.launch(Dispatchers.IO) { val friendly = runCatching { client.handshake(PairingClient.Endpoint(peer.host, peer.port)).hostname }.getOrNull(); if (requested && generation.get() == item.run && serviceEpoch[item.key] == item.epoch) { peers.computeIfPresent(item.key) { _, p -> p.copy(deviceName = friendly ?: p.deviceName) }; publish() } }
                }
                finishResolve(item); resolveNext()
            }
        }) } catch (e: Throwable) { if (valid(item)) { finishResolve(item); if (e is CancellationException) throw e; resolveNext() } }
    }
    private fun valid(item: ResolveItem): Boolean = requested && generation.get() == item.run && serviceEpoch[item.key] == item.epoch && synchronized(stateLock) { activeResolve === item }
    private fun finishResolve(item: ResolveItem) { synchronized(stateLock) { if (activeResolve === item) activeResolve = null } }

    fun stopDiscovery() {
        val oldRun = activeRun; generation.incrementAndGet(); requested = false; discovering = false
        jobs.values.forEach { it.cancel() }; jobs.clear(); synchronized(stateLock) { resolveQueue.clear(); activeResolve = null }
        val oldTokens = tokens.entries.toList(); tokens.clear(); oldTokens.forEach { (k, token) -> peers[k]?.let { peer -> scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(peer.host, peer.port), token) } } } }
        if (oldRun != null) runCatching { nsdClient.stop(oldRun) }
        activeRun = null; releaseLock(); peers.clear(); _state.value = DiscoveryState.Idle
    }

    fun pair(peer: NearbyPeer, onResult: (Boolean, String?) -> Unit) {
        val k = key(peer.serviceName, peer.serviceType)
        // There is one target: cancel pending pairing too, not only already-connected peers.
        peers.entries.filter { it.key != k && it.value.status == PeerStatus.PAIRING }.forEach { jobs.remove(it.key)?.cancel(); peers[it.key] = it.value.copy(status = PeerStatus.NEARBY) }
        peers.entries.filter { it.key != k && it.value.status == PeerStatus.CONNECTED }.forEach { disconnect(it.value) }
        jobs[k]?.cancel(); peers[k] = peer.copy(serviceType = type(peer.serviceType), status = PeerStatus.PAIRING); publish()
        val ticket = generation.get()
        jobs[k] = scope.launch(Dispatchers.IO) {
            try {
                val endpoint = PairingClient.Endpoint(peer.host, peer.port); val info = client.handshake(endpoint)
                val request = client.requestPair(endpoint, PairingClient.defaultClientId(context.getSharedPreferences("aurashare_prefs", Context.MODE_PRIVATE)), PairingClient.deviceName())
                val status = client.awaitApproval(endpoint, request); ensureActive(); if (ticket != generation.get()) return@launch
                if (status.status == "approved" && !status.token.isNullOrBlank() && status.serverId == info.serverId) {
                    check(client.heartbeat(endpoint, status.token)) { "heartbeat failed" }; tokens[k] = status.token
                    peers[k] = peer.copy(deviceName = status.deviceName ?: info.hostname, status = PeerStatus.CONNECTED); publish(); withContext(Dispatchers.Main) { onResult(true, null) }
                    while (isActive && ticket == generation.get() && client.heartbeat(endpoint, status.token)) delay(10_000)
                    tokens.remove(k); peers.computeIfPresent(k) { _, p -> p.copy(status = PeerStatus.DISCONNECTED) }; publish()
                } else { peers[k] = peer.copy(status = PeerStatus.NEARBY); publish(); withContext(Dispatchers.Main) { onResult(false, status.status) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { tokens.remove(k); if (ticket == generation.get()) { peers[k] = peer.copy(status = PeerStatus.NEARBY); publish(); withContext(Dispatchers.Main) { onResult(false, "تعذر الاقتران") } } }
        }
    }
    fun tokenFor(peer: NearbyPeer) = tokens[key(peer.serviceName, peer.serviceType)]
    fun disconnect(peer: NearbyPeer) { val k = key(peer.serviceName, peer.serviceType); val token = tokens.remove(k); jobs.remove(k)?.cancel(); peers[k] = peer.copy(status = PeerStatus.NEARBY); publish(); if (token != null) scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(peer.host, peer.port), token) } } }
    fun nearbyPeers(): List<NearbyPeer> = peers.values.toList()
    private fun acquireLock() { runCatching { if (lock == null) lock = wifi?.createMulticastLock("AuraShareMdns")?.apply { setReferenceCounted(false); acquire() } } }
    private fun releaseLock() { runCatching { lock?.takeIf { it.isHeld }?.release() }; lock = null }
}
