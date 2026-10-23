package com.example.ui

import com.example.data.network.NearbyPeer
import com.example.data.network.PeerStatus
import com.example.data.network.TrustedDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDeviceFilteringTest {
    private val identity = "a".repeat(64)
    private val device = TrustedDevice(identity, "الحاسوب", "192.168.1.20", 54433, "public certificate")

    @Test fun `saved device is not a legacy candidate even when disconnected`() {
        val peer = NearbyPeer("alias", device.host, 54321)
        assertFalse(legacyCandidateAllowed(peer, listOf(device)))
    }

    @Test fun `routing identity suppresses candidate after address changes but grants no trust`() {
        val hint = NearbyPeer("new-name", "192.168.1.99", 54321, secureServerId = identity, securePort = 54433)
        assertFalse(legacyCandidateAllowed(hint, listOf(device)))
    }

    @Test fun `connected alias cannot become a legacy candidate for saved device`() {
        val peer = NearbyPeer("alias2", device.host, 54321, status = PeerStatus.CONNECTED)
        assertFalse(legacyCandidateAllowed(peer, listOf(device)))
    }

    @Test fun `unknown peer remains eligible only for explicit legacy consent flow`() {
        val unknown = NearbyPeer("unknown", "192.168.1.44", 54321)
        assertTrue(legacyCandidateAllowed(unknown, listOf(device)))
    }

    @Test fun `forgetting trust allows a new explicit pairing choice`() {
        val peer = NearbyPeer("desktop", device.host, 54321)
        assertTrue(legacyCandidateAllowed(peer, emptyList()))
    }
}
