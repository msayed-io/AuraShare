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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Inet4Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrustedDeviceRouteDiscoveryTest {
    private val trustedId = "a".repeat(64)
    private lateinit var fake: FakeNsd
    private lateinit var manager: TrustedDeviceRouteDiscoveryManager
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        fake = FakeNsd()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = TrustedDeviceRouteDiscoveryManager(ApplicationProvider.getApplicationContext(), scope, fake)
    }

    @After fun tearDown() { manager.stopDiscovery(); scope.cancel() }

    @Test fun `only secure service is searched and only a stored identity becomes a route hint`() {
        manager.setTrustedDeviceIds(setOf(trustedId))
        val listener = fake.discoveryListeners.single()
        assertEquals(SECURE_DISCOVERY_SERVICE_TYPE, fake.requestedType)
        assertEquals(NsdManager.PROTOCOL_DNS_SD, fake.requestedProtocol)

        val oldService = service("Desk", "_aurashare._tcp.", trustedId)
        listener.onServiceFound(oldService)
        assertNull("old discovery services must not be resolved", fake.resolveListener)

        val unknown = service("Unknown", "_aurashare-secure._tcp.", "f".repeat(64))
        listener.onServiceFound(unknown)
        fake.resolveListener!!.onServiceResolved(resolved(unknown, "192.168.1.30", 54433))
        assertTrue("unknown servers must never become pairing candidates or saved routes", manager.hints.value.isEmpty())

        val known = service("Saved desktop", "_aurashare-secure._tcp.", trustedId)
        listener.onServiceFound(known)
        fake.resolveListener!!.onServiceResolved(resolved(known, "192.168.1.99", 54433))
        assertEquals(TrustedRouteHint(trustedId, "192.168.1.99", 54433), manager.hintFor(trustedId))
        assertEquals(setOf(trustedId), manager.hints.value.keys)
    }

    @Test fun `lost secure record removes the route and forgetting all devices stops discovery`() {
        manager.setTrustedDeviceIds(setOf(trustedId))
        val listener = fake.discoveryListeners.single()
        val known = service("Saved desktop", "_aurashare-secure._tcp.", trustedId)
        listener.onServiceFound(known)
        fake.resolveListener!!.onServiceResolved(resolved(known, "192.168.1.99", 54433))
        assertTrue(manager.hints.value.containsKey(trustedId))

        listener.onServiceLost(known)
        assertTrue(manager.hints.value.isEmpty())
        manager.setTrustedDeviceIds(emptySet())
        assertTrue(fake.stoppedListeners.contains(listener))
        assertTrue(manager.hints.value.isEmpty())
    }

    @Test fun `late resolve from an old discovery run cannot overwrite the current route`() {
        manager.setTrustedDeviceIds(setOf(trustedId))
        val oldListener = fake.discoveryListeners.single()
        val oldInfo = service("Saved desktop", "_aurashare-secure._tcp.", trustedId)
        oldListener.onServiceFound(oldInfo)
        val oldResolve = fake.resolveListeners.single()

        manager.stopDiscovery()
        manager.startDiscovery()
        val currentListener = fake.discoveryListeners.last()
        val currentInfo = service("Saved desktop", "_aurashare-secure._tcp.", trustedId)
        currentListener.onServiceFound(currentInfo)
        val currentResolve = fake.resolveListeners.last()

        oldResolve.onServiceResolved(resolved(oldInfo, "192.168.1.30", 54433))
        assertTrue(manager.hints.value.isEmpty())
        currentResolve.onServiceResolved(resolved(currentInfo, "192.168.1.99", 54434))
        assertEquals(TrustedRouteHint(trustedId, "192.168.1.99", 54434), manager.hintFor(trustedId))
    }

    @Test fun `secure route validation rejects malformed identity public host and invalid port`() {
        val address = InetAddress.getByName("192.168.1.20") as Inet4Address
        assertNull(trustedSecureRouteHint("A".repeat(64), address, 54433, setOf(trustedId)))
        assertNull(trustedSecureRouteHint("f".repeat(64), address, 54433, setOf(trustedId)))
        assertNull(trustedSecureRouteHint(trustedId, InetAddress.getByName("8.8.8.8") as Inet4Address, 54433, setOf(trustedId)))
        assertNull(trustedSecureRouteHint(trustedId, address, 0, setOf(trustedId)))
        assertEquals(TrustedRouteHint(trustedId, "192.168.1.20", 54433), trustedSecureRouteHint(trustedId, address, 54433, setOf(trustedId)))
    }

    private fun service(name: String, type: String, id: String) = NsdServiceInfo().apply {
        serviceName = name
        serviceType = type
        @Suppress("DEPRECATION")
        setAttribute(SECURE_DISCOVERY_SERVER_ID_ATTRIBUTE, id)
    }

    private fun resolved(info: NsdServiceInfo, host: String, port: Int) = NsdServiceInfo().apply {
        serviceName = info.serviceName
        serviceType = info.serviceType
        @Suppress("DEPRECATION")
        setAttribute(
            SECURE_DISCOVERY_SERVER_ID_ATTRIBUTE,
            requireNotNull(info.attributes[SECURE_DISCOVERY_SERVER_ID_ATTRIBUTE]).toString(Charsets.UTF_8),
        )
        this.host = InetAddress.getByName(host)
        this.port = port
    }

    private class FakeNsd : NsdDiscoveryClient {
        val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
        val stoppedListeners = mutableListOf<NsdManager.DiscoveryListener>()
        val resolveListeners = mutableListOf<NsdManager.ResolveListener>()
        var requestedType: String? = null
        var requestedProtocol: Int? = null
        var resolveListener: NsdManager.ResolveListener? = null
        override fun discover(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
            requestedType = type
            requestedProtocol = protocol
            discoveryListeners += listener
        }
        override fun stop(listener: NsdManager.DiscoveryListener) { stoppedListeners += listener }
        override fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
            resolveListener = listener
            resolveListeners += listener
        }
    }
}
