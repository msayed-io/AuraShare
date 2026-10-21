package com.example

import android.app.Activity
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ImmersiveStatusBarTest {
    @Suppress("DEPRECATION")
    @Test @Config(sdk = [29])
    fun android10UsesAllRequiredImmersiveFlagsAndRestoresThem() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = controller.get().window
            val expected = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            enforceImmersiveStatusBar(window)
            assertEquals(expected, window.decorView.systemUiVisibility)
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
            window.decorView.systemUiVisibility = 0
            enforceImmersiveStatusBar(window)
            assertEquals(expected, window.decorView.systemUiVisibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [31])
    fun modernAndroidAppliesFullscreenPolicyWithoutLegacyApiCrash() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = controller.get().window
            enforceImmersiveStatusBar(window)
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
        } finally { controller.pause().stop().destroy() }
    }
}
