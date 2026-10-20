package com.example

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.network.DiscoveryState
import com.example.data.network.NearbyPeer
import com.example.data.network.PeerStatus
import com.example.ui.components.DesktopCapsuleHeader
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NearbyHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nearbyPeerPairsWithoutManualFields() {
        val peer = NearbyPeer("desktop-service", "192.168.1.20", 54321, "Mohamed PC")
        var selected: NearbyPeer? = null
        compose.setContent {
            MyApplicationTheme {
                DesktopCapsuleHeader(DiscoveryState.Nearby(listOf(peer)), null,
                    onRefreshDiscovery = {}, onPair = { selected = it }, onDisconnect = {},
                    onOpenHistory = {}, onCapsuleBoundsChanged = {}, isBeingAttracted = false)
            }
        }
        compose.onNodeWithText("Mohamed PC").assertIsDisplayed()
        compose.onNodeWithText("اقتران").performClick()
        assertEquals(peer, selected)
        compose.onNodeWithText("إعدادات الربط اليدوي").assertDoesNotExist()
        compose.onNodeWithText("رمز الاقتران").assertDoesNotExist()
    }

    @Test fun connectedPeerRemainsVisibleAndCanDisconnect() {
        val peer = NearbyPeer("desktop-service", "192.168.1.20", 54321, "Mohamed PC", PeerStatus.CONNECTED)
        var selected: NearbyPeer? = null
        compose.setContent {
            MyApplicationTheme {
                DesktopCapsuleHeader(DiscoveryState.Nearby(listOf(peer)), null,
                    onRefreshDiscovery = {}, onPair = {}, onDisconnect = { selected = it },
                    onOpenHistory = {}, onCapsuleBoundsChanged = {}, isBeingAttracted = false)
            }
        }
        compose.onNodeWithText("مُتصل بالحاسوب").assertIsDisplayed()
        compose.onNodeWithText("Mohamed PC").assertIsDisplayed()
        compose.onNodeWithText("قطع الاتصال").performClick()
        assertEquals(peer, selected)
    }
}
