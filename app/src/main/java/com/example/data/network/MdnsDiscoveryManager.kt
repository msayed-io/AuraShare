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
data class NearbyPeer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceName: String? = null,
    val status: PeerStatus = PeerStatus.NEARBY,
    val serviceType: String = "_aurashare._tcp",
    val secureServerId: String? = null,
    val securePort: Int? = null
)
enum class PeerStatus { NEARBY, PAIRING, CONNECTED, DISCONNECTED }

private val secureServerIdPattern = Regex("[0-9a-f]{64}")
private fun validSecureServerId(value: String?) = value?.takeIf { it.matches(secureServerIdPattern) }
private fun endpointKey(peer: NearbyPeer) = "${peer.host}:${peer.port}"

/** UI projection only: mDNS names are aliases, while host:port is the endpoint identity. */
internal fun consolidateNearbyPeers(peers: Collection<NearbyPeer>): List<NearbyPeer> = peers
    .groupBy(::endpointKey)
    .values
    .map { aliases ->
        val selected = aliases.sortedWith(
            compareByDescending<NearbyPeer> { it.status.priority() }
                .thenByDescending { validSecureServerId(it.secureServerId) != null }
                .thenBy { it.deviceName ?: "" }
                .thenBy { it.serviceName }
        ).first()
        // Discovery hints are an inseparable pair; never mix aliases.
        val secureHints = aliases.firstOrNull { validSecureServerId(it.secureServerId) != null && it.securePort in 1..65535 }
        val secureId = validSecureServerId(secureHints?.secureServerId)
        val securePort = secureHints?.securePort
        selected.copy(secureServerId = secureId, securePort = securePort)
    }
    .sortedBy { it.deviceName ?: "AuraShare (${it.host})" }

private fun PeerStatus.priority() = when (this) {
    PeerStatus.CONNECTED -> 4
    PeerStatus.PAIRING -> 3
    PeerStatus.NEARBY -> 2
    PeerStatus.DISCONNECTED -> 1
}

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
    private val attempts = ConcurrentHashMap<String, AtomicLong>()
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

    private fun publish() { _state.value = if (peers.isEmpty()) DiscoveryState.Searching() else DiscoveryState.Nearby(consolidateNearbyPeers(peers.values)) }
    private fun current(run: Run) = activeRun === run && requested && generation.get() == run.generation
    private fun type(value: String?) = value.orEmpty().trimEnd('.')
    private fun key(name: String?, serviceType: String?) = "${name.orEmpty()}|${type(serviceType)}"
    private fun nextAttempt(k: String) = attempts.computeIfAbsent(k) { AtomicLong() }.incrementAndGet()
    private suspend fun validAttempt(k: String, ticket: Long): Boolean = attempts[k]?.get() == ticket && jobs[k] === currentCoroutineContext()[Job] && requested && generation.get() == (activeRun?.generation ?: -1L)

    fun startDiscovery() {
        synchronized(stateLock) { if (requested) return; requested = true }
        val run = Run(generation.incrementAndGet()).also { it.owner = this }
        activeRun = run; discovering = true // mark before the async callback: duplicate starts are rejected
        jobs.values.forEach { it.cancel() }; jobs.clear(); tokens.clear(); peers.keys.forEach(::nextAttempt); peers.clear()
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
        val k = key(info.serviceName, info.serviceType); serviceEpoch[k] = (serviceEpoch[k] ?: 0L) + 1L; nextAttempt(k)
        val oldPeer = peers.remove(k)
        val aliases = oldPeer?.let { old -> peers.values.filter { endpointKey(it) == endpointKey(old) } }.orEmpty()
        val oldToken = tokens.remove(k)
        jobs.remove(k)?.cancel()
        if (oldToken != null && aliases.any { it.status == PeerStatus.CONNECTED }) aliases.first { it.status == PeerStatus.CONNECTED }.let { tokens[key(it.serviceName, it.serviceType)] = oldToken }
        // Android permits one native resolve at a time. A lost service invalidates
        // its result, but does not cancel the underlying native operation.
        synchronized(stateLock) { resolveQueue.removeAll { it.key == k } }
        if (oldToken != null && aliases.none { it.status == PeerStatus.CONNECTED || it.status == PeerStatus.PAIRING }) scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(info.host?.hostAddress ?: return@launch, info.port), oldToken) } }
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
                if (host is Inet4Address && port in 1..65535 && host.hostAddress != null && SecurePairingManager.isAllowedLanIpv4(host.hostAddress!!)) {
                    val peer = NearbyPeer(resolved?.serviceName ?: item.info.serviceName ?: "AuraShare", host.hostAddress!!, port, serviceType = type(resolved?.serviceType ?: item.info.serviceType))
                    peers[item.key] = peer; publish()
                    scope.launch(Dispatchers.IO) {
                        val info = runCatching { client.handshake(PairingClient.Endpoint(peer.host, peer.port)) }.getOrNull()
                        if (requested && generation.get() == item.run && serviceEpoch[item.key] == item.epoch) {
                            peers.computeIfPresent(item.key) { _, p -> p.copy(deviceName = info?.hostname ?: p.deviceName, secureServerId = info?.secureServerId, securePort = info?.securePort) }
                            publish()
                        }
                    }
                }
                finishResolve(item); resolveNext()
            }
        }) } catch (e: Throwable) { if (valid(item)) { finishResolve(item); if (e is CancellationException) throw e; resolveNext() } }
    }
    private fun valid(item: ResolveItem): Boolean = requested && generation.get() == item.run && serviceEpoch[item.key] == item.epoch && synchronized(stateLock) { activeResolve === item }
    private fun finishResolve(item: ResolveItem) { synchronized(stateLock) { if (activeResolve === item) activeResolve = null } }

    fun stopDiscovery() {
        val oldRun = activeRun; generation.incrementAndGet(); requested = false; discovering = false
        attempts.keys.forEach(::nextAttempt)
        jobs.values.forEach { it.cancel() }; jobs.clear(); synchronized(stateLock) { resolveQueue.clear(); activeResolve = null }
        val oldTokens = tokens.entries.toList(); tokens.clear(); oldTokens.forEach { (k, token) -> peers[k]?.let { peer -> scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(peer.host, peer.port), token) } } } }
        if (oldRun != null) runCatching { nsdClient.stop(oldRun) }
        activeRun = null; releaseLock(); peers.clear(); _state.value = DiscoveryState.Idle
    }

    fun pair(peer: NearbyPeer, onResult: (Boolean, String?) -> Unit) {
        val k = key(peer.serviceName, peer.serviceType)
        if (!SecurePairingManager.isAllowedLanIpv4(peer.host)) return
        if (peers.any { it.key != k && endpointKey(it.value) == endpointKey(peer) && it.value.status in setOf(PeerStatus.CONNECTED, PeerStatus.PAIRING) } || peers[k]?.status in setOf(PeerStatus.CONNECTED, PeerStatus.PAIRING)) return
        peers.entries.filter { it.key != k && it.value.status == PeerStatus.PAIRING }.forEach { nextAttempt(it.key); jobs.remove(it.key)?.cancel(); peers[it.key] = it.value.copy(status = PeerStatus.NEARBY) }
        peers.entries.filter { it.key != k && it.value.status == PeerStatus.CONNECTED }.forEach { disconnect(it.value) }
        jobs[k]?.cancel(); val attempt = nextAttempt(k)
        peers[k] = peer.copy(serviceType = type(peer.serviceType), status = PeerStatus.PAIRING); publish()
        val ticket = generation.get()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (!validAttempt(k, attempt)) return@launch
                val endpoint = PairingClient.Endpoint(peer.host, peer.port); val info = client.handshake(endpoint)
                val request = client.requestPair(endpoint, PairingClient.defaultClientId(context.getSharedPreferences("aurashare_prefs", Context.MODE_PRIVATE)), PairingClient.deviceName())
                val status = client.awaitApproval(endpoint, request); ensureActive(); if (!validAttempt(k, attempt) || ticket != generation.get()) return@launch
                if (status.status == "approved" && !status.token.isNullOrBlank() && status.serverId == info.serverId) {
                    check(client.heartbeat(endpoint, status.token)) { "heartbeat failed" }; ensureActive(); if (!validAttempt(k, attempt)) return@launch
                    tokens[k] = status.token; ensureActive(); if (!validAttempt(k, attempt)) return@launch
                    peers[k] = peer.copy(deviceName = status.deviceName ?: info.hostname, status = PeerStatus.CONNECTED, secureServerId = info.secureServerId, securePort = info.securePort); publish()
                    withContext(Dispatchers.Main) { ensureActive(); if (validAttempt(k, attempt)) onResult(true, null) }
                    while (isActive && validAttempt(k, attempt) && ticket == generation.get() && client.heartbeat(endpoint, status.token)) { ensureActive(); delay(10_000) }
                    if (attempts[k]?.get() == attempt && tokens[k] == status.token) { tokens.remove(k, status.token); peers.computeIfPresent(k) { _, p -> if (p.status == PeerStatus.CONNECTED) p.copy(status = PeerStatus.DISCONNECTED) else p }; publish() }
                } else if (validAttempt(k, attempt)) { peers[k] = peer.copy(status = PeerStatus.NEARBY); publish(); withContext(Dispatchers.Main) { ensureActive(); if (validAttempt(k, attempt)) onResult(false, status.status) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (attempts[k]?.get() == attempt) { tokens.remove(k); if (validAttempt(k, attempt)) { peers[k] = peer.copy(status = PeerStatus.NEARBY); publish(); withContext(Dispatchers.Main) { ensureActive(); if (validAttempt(k, attempt)) onResult(false, "تعذر الاقتران") } } } }
        }
        jobs[k] = job; job.start()
    }
    fun tokenFor(peer: NearbyPeer) = tokens[key(peer.serviceName, peer.serviceType)]
        ?: peers.entries.firstOrNull { endpointKey(it.value) == endpointKey(peer) && it.value.status == PeerStatus.CONNECTED }?.let { tokens[it.key] }
    fun disconnect(peer: NearbyPeer) {
        val matching = peers.entries.filter { endpointKey(it.value) == endpointKey(peer) }
        matching.forEach { nextAttempt(it.key) }
        val token = matching.asSequence().mapNotNull { tokens.remove(it.key) }.firstOrNull()
        matching.forEach { (alias, value) -> jobs.remove(alias)?.cancel(); peers[alias] = value.copy(status = PeerStatus.NEARBY) }
        publish(); if (token != null) scope.launch(Dispatchers.IO) { runCatching { client.disconnect(PairingClient.Endpoint(peer.host, peer.port), token) } }
    }
    fun nearbyPeers(): List<NearbyPeer> = consolidateNearbyPeers(peers.values)
    private fun acquireLock() { runCatching { if (lock == null) lock = wifi?.createMulticastLock("AuraShareMdns")?.apply { setReferenceCounted(false); acquire() } } }
    private fun releaseLock() { runCatching { lock?.takeIf { it.isHeld }?.release() }; lock = null }
}
