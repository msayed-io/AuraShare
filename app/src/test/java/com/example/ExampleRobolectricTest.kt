package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.data.network.MdnsDiscoveryManager
import com.example.ui.AuraShareScreen
import com.example.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // Android 10 (EMUI 10/11/12 on Huawei)
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `test app name matches resource`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AuraShare", appName)
    }

    @Test
    fun `test app renders cleanly on android 10 emui`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(app)
        composeTestRule.setContent {
            AuraShareScreen(viewModel = viewModel)
        }
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun `test mDNS service discovery initialization and safe state`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = MdnsDiscoveryManager(app, CoroutineScope(Dispatchers.Main))
        manager.startDiscovery()
        assertNotNull(manager.state.value)
        manager.stopDiscovery()
    }
}
