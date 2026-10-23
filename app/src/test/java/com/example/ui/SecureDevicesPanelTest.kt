package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.network.SecureState
import com.example.data.network.TrustedDevice
import com.example.ui.components.SecureDevicesPanel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SecureDevicesPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedDeviceIsPresentedAsLinkedAndConnectsByItsSavedIdentity() {
        val device = TrustedDevice("a".repeat(64), "حاسوب المكتب", "192.168.1.20", 54433, "public-certificate")
        var connectedId: String? = null
        var scanned = false
        compose.setContent {
            MyApplicationTheme {
                SecureDevicesPanel(
                    state = SecureState(devices = listOf(device)),
                    onScanQr = { scanned = true },
                    onConnect = { connectedId = it },
                    onDisconnect = {},
                    onForget = {},
                )
            }
        }
        compose.onNodeWithText("الأجهزة المرتبطة").assertIsDisplayed()
        compose.onNodeWithText("حاسوب المكتب").assertIsDisplayed()
        compose.onNodeWithText("مرتبط — غير متصل").assertIsDisplayed()
        compose.onNodeWithText("اتصال").performClick()
        compose.onNodeWithText("مسح QR لربط جهاز").performClick()
        compose.runOnIdle {
            assertEquals(device.id, connectedId)
            org.junit.Assert.assertEquals(true, scanned)
        }
        compose.onNodeWithText("الأجهزة القريبة").assertDoesNotExist()
        compose.onNodeWithText("اقتران").assertDoesNotExist()
    }

    @Test fun emptyStateIsConciseAndContainsNoComputerVersionRequirement() {
        compose.setContent {
            MyApplicationTheme {
                SecureDevicesPanel(
                    state = SecureState(),
                    onScanQr = {},
                    onConnect = {},
                    onDisconnect = {},
                    onForget = {},
                )
            }
        }
        compose.onNodeWithText("لا توجد أجهزة مرتبطة بعد").assertIsDisplayed()
        compose.onNodeWithText("الأجهزة القريبة").assertDoesNotExist()
        compose.onNodeWithText("نسخة الكمبيوتر").assertDoesNotExist()
    }
}
