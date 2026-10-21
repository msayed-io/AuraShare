package com.example

import android.Manifest
import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun runPermissionCheck(
    permissions: Array<String>,
    granted: Array<String>,
    expected: Boolean
) {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val shadow = shadowOf(application)
    shadow.denyPermissions(*permissions)
    if (granted.isNotEmpty()) shadow.grantPermissions(*granted)

    val dispatcher = StandardTestDispatcher()
    Dispatchers.setMain(dispatcher)
    val viewModel = MainViewModel(application)
    val store = ViewModelStore().apply { put("permission-test", viewModel) }
    try {
        viewModel.checkPermissions()
        if (expected) assertTrue(viewModel.uiState.value.isMediaPermissionGranted)
        else assertFalse(viewModel.uiState.value.isMediaPermissionGranted)
    } finally {
        // checkPermissions starts discovery; always stop it even when an assertion fails.
        viewModel.discoveryManager.stopDiscovery()
        store.clear()
        Dispatchers.resetMain()
        shadow.denyPermissions(*permissions)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaPermissionApi29RegressionTest {
    @Test
    fun `api 29 requires READ_EXTERNAL_STORAGE`() {
        runPermissionCheck(
            permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            granted = emptyArray(),
            expected = false
        )
        runPermissionCheck(
            permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            granted = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            expected = true
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaPermissionApi33RegressionTest {
    @Test
    fun `api 33 accepts image-only or video-only and denies neither`() {
        val media = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        runPermissionCheck(media, emptyArray(), expected = false)
        runPermissionCheck(media, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), expected = true)
        runPermissionCheck(media, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), expected = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPermissionApi34RegressionTest {
    @Test
    fun `api 34 accepts image-only video-only or visual-user-selected`() {
        val media = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        runPermissionCheck(media, emptyArray(), expected = false)
        runPermissionCheck(media, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), expected = true)
        runPermissionCheck(media, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), expected = true)
        runPermissionCheck(media, arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), expected = true)
    }
}
