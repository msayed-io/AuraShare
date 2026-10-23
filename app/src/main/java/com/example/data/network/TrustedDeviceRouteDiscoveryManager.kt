package com.example.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The secure desktop listener advertises its TLS port on this DNS-SD service. */
const val SECURE_DISCOVERY_SERVICE_TYPE = "_aurashare-secure._tcp"
const val SECURE_DISCOVERY_SERVER_ID_ATTRIBUTE = "server_id"

/** Routing data only; SecurePairingManager still authenticates the pinned certificate. */
data class TrustedRouteHint(val serverId: String, val host: String, val port: Int)

internal fun trustedSecureRouteHint(
    serverId: String?,
    address: Inet4Address?,
    port: Int,
    trustedIds: Set<String>,
): TrustedRouteHint? {
    val id = serverId?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
    val host = address?.hostAddress ?: return null
    if (id !in trustedIds || port !in 1..65535 || !SecurePairingManager.isAllowedLanIpv4(host)) return null
    return TrustedRouteHint(id, host, port)
}

/** Small seam for deterministic discovery lifecycle tests. */
interface NsdDiscoveryClient {
    fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener)
    fun stop(listener: NsdManager.DiscoveryListener)
    fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener)
}

private class AndroidNsdDiscoveryClient(private val nsd: NsdManager?) : NsdDiscoveryClient {
    override fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
        nsd?.discoverServices(type, protocol, listener)
    }

    override fun stop(listener: NsdManager.DiscoveryListener) {
        nsd?.stopServiceDiscovery(listener)
    }

    override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        nsd?.resolveService(info, listener)
    }
}

/**
 * Discovers only secure TLS service records and retains hints only for already-linked server IDs.
 * It never probes a peer over the network; every hint is re-authenticated by the pinned TLS key.
 */
class TrustedDeviceRouteDiscoveryManager(
    context: Context,
    private val scope: CoroutineScope,
    private val nsdClient: NsdDiscoveryClient = AndroidNsdDiscoveryClient(
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager,
    ),
) {
    private val lock = Any()
    private val generation = AtomicLong(0)
    private val routesByService = ConcurrentHashMap<String, TrustedRouteHint>()
    private val resolveQueue = ArrayDeque<ResolveItem>()
    private val queuedKeys = mutableSetOf<String>()
    private val latestInfoByService = mutableMapOf<String, NsdServiceInfo>()
    private val serviceEpochs = mutableMapOf<String, Long>()
    private val trustedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _hints = MutableStateFlow<Map<String, TrustedRouteHint>>(emptyMap())
    val hints: StateFlow<Map<String, TrustedRouteHint>> = _hints.asStateFlow()

    @Volatile private var requested = false
    @Volatile private var activeRun: Run? = null
    private var activeResolve: ResolveItem? = null
    private var retryJob: Job? = null

    private data class ResolveItem(val key: String, val info: NsdServiceInfo, val generation: Long, val serviceEpoch: Long)

    private class Run(val generation: Long) : NsdManager.DiscoveryListener {
        lateinit var owner: TrustedDeviceRouteDiscoveryManager
        override fun onDiscoveryStarted(regType: String?) = owner.discoveryStarted(this)
        override fun onDiscoveryStopped(serviceType: String?) = owner.discoveryStopped(this)
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = owner.discoveryFailed(this)
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = owner.discoveryFailed(this)
        override fun onServiceFound(info: NsdServiceInfo?) = owner.serviceFound(this, info)
        override fun onServiceLost(info: NsdServiceInfo?) = owner.serviceLost(this, info)
    }

    fun setTrustedDeviceIds(ids: Set<String>) {
        val safeIds = ids.filterTo(linkedSetOf()) { it.matches(Regex("[0-9a-f]{64}")) }
        val changed = safeIds != trustedIds.value
        trustedIds.value = safeIds
        routesByService.entries.removeIf { it.value.serverId !in safeIds }
        publishRoutes()
        if (safeIds.isEmpty()) stopDiscovery() else {
            if (changed && requested) stopDiscovery()
            startDiscovery()
        }
    }

    private fun key(info: NsdServiceInfo) = "${info.serviceName.orEmpty()}|${info.serviceType.orEmpty().trimEnd('.')}"
    private fun current(run: Run) = activeRun === run && requested && generation.get() == run.generation

    fun startDiscovery() {
        if (trustedIds.value.isEmpty()) return
        synchronized(lock) {
            if (requested) return
            requested = true
        }
        val run = Run(generation.incrementAndGet()).also { it.owner = this }
        activeRun = run
        try {
            nsdClient.discover(SECURE_DISCOVERY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, run)
        } catch (_: Throwable) {
            if (current(run)) failRun(run)
        }
    }

    fun stopDiscovery() {
        val run = activeRun
        generation.incrementAndGet()
        requested = false
        retryJob?.cancel()
        retryJob = null
        synchronized(lock) {
            resolveQueue.clear()
            queuedKeys.clear()
            latestInfoByService.clear()
            serviceEpochs.clear()
            activeResolve = null
        }
        routesByService.clear()
        publishRoutes()
        activeRun = null
        run?.let { runCatching { nsdClient.stop(it) } }
    }

    fun hintFor(serverId: String): TrustedRouteHint? = _hints.value[serverId]

    private fun discoveryStarted(run: Run) {
        if (!current(run)) return
    }

    private fun discoveryStopped(run: Run) {
        if (!current(run)) return
        failRun(run)
        scheduleRetry(run.generation)
    }

    private fun discoveryFailed(run: Run) {
        if (!current(run)) return
        failRun(run)
        scheduleRetry(run.generation)
    }

    private fun failRun(run: Run) {
        if (activeRun !== run) return
        requested = false
        activeRun = null
    }

    private fun scheduleRetry(ticket: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000)
            if (ticket == generation.get() && trustedIds.value.isNotEmpty() && !requested) startDiscovery()
        }
    }

    private fun serviceFound(run: Run, info: NsdServiceInfo?) {
        if (!current(run) || info == null || normalizeType(info.serviceType) != SECURE_DISCOVERY_SERVICE_TYPE) return
        val serviceKey = key(info)
        synchronized(lock) {
            if (!current(run)) return
            latestInfoByService[serviceKey] = info
            if (routesByService.containsKey(serviceKey) || !queuedKeys.add(serviceKey)) return
            resolveQueue.addLast(ResolveItem(serviceKey, info, run.generation, serviceEpochs[serviceKey] ?: 0L))
        }
        resolveNext()
    }

    private fun serviceLost(run: Run, info: NsdServiceInfo?) {
        if (!current(run) || info == null || normalizeType(info.serviceType) != SECURE_DISCOVERY_SERVICE_TYPE) return
        val serviceKey = key(info)
        synchronized(lock) {
            serviceEpochs[serviceKey] = (serviceEpochs[serviceKey] ?: 0L) + 1L
            latestInfoByService.remove(serviceKey)
            resolveQueue.removeAll { it.key == serviceKey }
            if (activeResolve?.key != serviceKey) queuedKeys.remove(serviceKey)
        }
        routesByService.remove(serviceKey)
        publishRoutes()
        resolveNext()
    }

    private fun resolveNext() {
        val item = synchronized(lock) {
            if (activeResolve != null || resolveQueue.isEmpty()) null
            else resolveQueue.removeFirst().also { activeResolve = it }
        } ?: return
        val run = activeRun
        if (run == null || run.generation != item.generation || !current(run)) {
            finishResolve(item)
            return
        }
        try {
            nsdClient.resolve(item.info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    finishResolve(item)
                    resolveNext()
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    val stillAdvertised = synchronized(lock) {
                        latestInfoByService.containsKey(item.key) && (serviceEpochs[item.key] ?: 0L) == item.serviceEpoch
                    }
                    if (current(run) && stillAdvertised && resolved != null && normalizeType(resolved.serviceType) == SECURE_DISCOVERY_SERVICE_TYPE) {
                        val encodedId = resolved.attributes[SECURE_DISCOVERY_SERVER_ID_ATTRIBUTE]
                        val advertisedId = encodedId?.toString(Charsets.UTF_8)
                        val address = resolved.host as? Inet4Address
                        val hint = trustedSecureRouteHint(advertisedId, address, resolved.port, trustedIds.value)
                        if (hint != null) {
                            routesByService[item.key] = hint
                            publishRoutes()
                        }
                    }
                    finishResolve(item)
                    resolveNext()
                }
            })
        } catch (_: Throwable) {
            finishResolve(item)
            resolveNext()
        }
    }

    private fun finishResolve(item: ResolveItem) {
        synchronized(lock) {
            if (activeRun?.generation != item.generation || generation.get() != item.generation) return
            if (activeResolve === item) activeResolve = null
            val latest = latestInfoByService[item.key]
            val epoch = serviceEpochs[item.key] ?: 0L
            if (latest != null && epoch != item.serviceEpoch && activeRun?.generation == item.generation && queuedKeys.contains(item.key)) {
                resolveQueue.addLast(ResolveItem(item.key, latest, item.generation, epoch))
            } else {
                queuedKeys.remove(item.key)
            }
        }
    }

    private fun publishRoutes() {
        val allowed = trustedIds.value
        _hints.value = routesByService.values
            .filter { it.serverId in allowed }
            .groupBy(TrustedRouteHint::serverId)
            .mapValues { (_, routes) -> routes.first() }
    }

    private fun normalizeType(value: String?) = value.orEmpty().trimEnd('.')

}
