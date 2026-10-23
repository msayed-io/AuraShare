package com.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.network.SecureState
import com.example.ui.components.AppleFolderDropdown
import com.example.ui.components.DesktopCapsuleHeader
import com.example.ui.components.FloatingBackdropHost
import com.example.ui.components.FloatingBottomNavigation
import com.example.ui.components.FloatingTopDissolve
import com.example.ui.components.FolderOption
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FloatingChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topGapPassesTouchesToTheUnderlyingContent() {
        var taps = 0
        compose.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("chrome_test_root")) {
                        FloatingBackdropHost(
                            content = { Box(Modifier.fillMaxSize().clickable { taps++ }) },
                            overlay = {
                                FloatingTopDissolve()
                                DesktopCapsuleHeader(null, {}, {}, isBeingAttracted = false)
                            }
                        )
                    }
                }
            }
        }
        // The center of a narrow screen is intentionally not a click target in the ghost header.
        compose.onNodeWithTag("chrome_test_root").performTouchInput {
            click(Offset(width / 2f, 30.dp.toPx()))
        }
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun selectingTabUpdatesNativeSelectedSemantics() {
        compose.setContent {
            MyApplicationTheme {
                var tab by remember { mutableStateOf("images") }
                Box(Modifier.size(360.dp, 640.dp)) {
                    FloatingBottomNavigation(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.onNodeWithTag("tab_images_button").assertIsSelected()
        compose.onNodeWithTag("tab_files_button").performClick().assertIsSelected()
        compose.onNodeWithText("الملفات").assertIsDisplayed()
    }

    @Test fun selectingFolderInvokesRealCallbackAndClosesMenu() {
        var folder = "الكل"
        compose.setContent {
            MyApplicationTheme(darkTheme = false) {
                AppleFolderDropdown(listOf(FolderOption("الكل", 2), FolderOption("الكاميرا", 1)), "الكل", { folder = it })
            }
        }
        compose.onNodeWithTag("folder_dropdown_button").performClick()
        compose.onNodeWithTag("folder_item_الكاميرا").performClick()
        compose.runOnIdle { assertEquals("الكاميرا", folder) }
        compose.onNodeWithTag("folder_item_الكاميرا").assertDoesNotExist()
    }

    @Test fun linkedDevicesAndHistoryRemainAvailableWithoutDiscoveryControls() {
        var history = 0
        compose.setContent {
            MyApplicationTheme {
                DesktopCapsuleHeader(null, { history++ }, {}, isBeingAttracted = false, secureState = SecureState())
            }
        }
        compose.onNodeWithTag("desktop_capsule_status").performClick()
        compose.onNodeWithText("لا توجد أجهزة مرتبطة بعد").assertIsDisplayed()
        compose.onNodeWithTag("transfer_history_button").performClick()
        compose.runOnIdle { assertEquals(1, history) }
    }
}
