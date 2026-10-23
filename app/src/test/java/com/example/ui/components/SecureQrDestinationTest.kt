package com.example.ui.components

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ui.AuraShareScreen
import com.example.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SecureQrDestinationTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    private fun viewModel() = MainViewModel(ApplicationProvider.getApplicationContext()).also {
        store.put("qr-test", it)
    }

    @After fun clearViewModel() { store.clear() }

    @Test
    @Config(sdk = [24])
    fun openingQrScanner_unmountsPreviousPermissionTree_andCloseRestoresIt() {
        val viewModel = viewModel()
        compose.setContent { AuraShareScreen(viewModel) }
        compose.onNodeWithTag("permission_gate_card").assertIsDisplayed()
        compose.runOnIdle { viewModel.showQrScanner(true) }
        compose.onNodeWithTag("qr_scanner_root").assertIsDisplayed()
        compose.onNodeWithTag("permission_gate_card").assertDoesNotExist()
        compose.onNodeWithTag("desktop_capsule_status").assertDoesNotExist()
        compose.onNodeWithContentDescription("إغلاق ماسح QR").performClick()
        compose.onNodeWithTag("qr_scanner_root").assertDoesNotExist()
        compose.onNodeWithTag("permission_gate_card").assertIsDisplayed()
        assertEquals(false, viewModel.uiState.value.showQrScanner)
        // Reopening creates a fresh scanner session instead of reusing a dismissed gate.
        compose.runOnIdle { viewModel.showQrScanner(true) }
        compose.onNodeWithTag("qr_scanner_root").assertIsDisplayed()
        compose.onNodeWithContentDescription("إغلاق ماسح QR").performClick()
        compose.onNodeWithTag("qr_scanner_root").assertDoesNotExist()
    }

    @Test
    fun systemBack_closesStandaloneScannerAndRestoresPreviousScreen() {
        val viewModel = viewModel()
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            AuraShareScreen(viewModel)
        }
        compose.runOnIdle { viewModel.showQrScanner(true) }
        compose.onNodeWithTag("qr_scanner_root").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("qr_scanner_root").assertDoesNotExist()
        compose.onNodeWithTag("permission_gate_card").assertIsDisplayed()
        assertEquals(false, viewModel.uiState.value.showQrScanner)
    }

    @Test
    fun torchIcon_exposesConfirmedArabicState_andDisabledSemantics() {
        val enabled = mutableStateOf(true)
        val checked = mutableStateOf(false)
        var requested: Boolean? = null
        compose.setContent {
            SecureQrTorchButton(enabled.value, checked.value, onCheckedChange = { requested = it })
        }
        compose.onNodeWithContentDescription("تشغيل المصباح").assertIsEnabled().performClick()
        assertEquals(true, requested)
        // No optimistic state: the icon stays off until the camera result updates its caller.
        assertEquals("المصباح معطّل", compose.onNodeWithTag("qr_torch_button")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.runOnIdle { checked.value = true }
        compose.onNodeWithContentDescription("إطفاء المصباح").assertIsEnabled()
        assertEquals("المصباح مفعّل", compose.onNodeWithTag("qr_torch_button")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("إطفاء المصباح").assertIsNotEnabled()
    }
}
