package com.example.data.network

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MdnsDiscoveryManagerTest {
    private lateinit var fake: FakeNsd
    private lateinit var manager: MdnsDiscoveryManager
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        fake = FakeNsd()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = MdnsDiscoveryManager(ApplicationProvider.getApplicationContext(), scope, fake)
    }

    @After fun tearDown() { manager.stopDiscovery(); scope.cancel() }

    @Test fun `start is idempotent before started callback and stale stop cannot stop new run`() {
        manager.startDiscovery(); val first = fake.discoveryListeners.single()
        manager.startDiscovery()
        assertEquals(1, fake.discoveryListeners.size)
        first.onDiscoveryStarted("_aurashare._tcp")
        manager.stopDiscovery(); manager.startDiscovery(); val second = fake.discoveryListeners.last()
        first.onDiscoveryStopped("_aurashare._tcp")
        assertTrue(manager.state.value is DiscoveryState.Searching)
        assertEquals(2, fake.discoveryListeners.size)
        second.onDiscoveryStarted("_aurashare._tcp")
    }

    @Test fun `lost service tombstones pending resolution and stale callback cannot resurrect peer`() {
        manager.startDiscovery(); val listener = fake.discoveryListeners.single()
        val info = service("Desk", "_aurashare._tcp.")
        listener.onServiceFound(info)
        listener.onServiceLost(info)
        fake.resolveListener?.onServiceResolved(resolved("Desk", "192.0.2.10", 45700, "_aurashare._tcp."))
        assertTrue(manager.nearbyPeers().isEmpty())
    }

    @Test fun `resolved discovery remains nearby until explicit pair`() {
        manager.startDiscovery(); val listener = fake.discoveryListeners.single()
        val info = service("Desk", "_aurashare._tcp.")
        listener.onServiceFound(info)
        fake.resolveListener?.onServiceResolved(resolved("Desk", "192.0.2.10", 45700, "_aurashare._tcp."))
        val peer = manager.nearbyPeers().single()
        assertEquals(PeerStatus.NEARBY, peer.status)
        assertEquals("_aurashare._tcp", peer.serviceType)
        assertTrue(manager.state.value is DiscoveryState.Nearby)
    }

    private fun service(name: String, type: String) = NsdServiceInfo().apply { serviceName = name; serviceType = type }
    private fun resolved(name: String, host: String, port: Int, type: String) = service(name, type).apply {
        this.host = java.net.InetAddress.getByName(host); this.port = port
    }

    private class FakeNsd : NsdDiscoveryClient {
        val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
        var resolveListener: NsdManager.ResolveListener? = null
        override fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) { discoveryListeners += listener }
        override fun stop(listener: NsdManager.DiscoveryListener) { }
        override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { resolveListener = listener }
    }
}
