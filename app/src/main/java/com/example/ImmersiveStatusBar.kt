package com.example

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Enforces true edge-to-edge fullscreen immersive experience across Android 10 (API 29)
 * and modern Android releases (API 30+), hiding system status bars and navigation bars
 * with transient swipe behavior and display cutout support.
 */
@Suppress("DEPRECATION")
internal fun enforceImmersiveStatusBar(window: Window) {
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

    // API 28+ layout in display cutout short edges to avoid black bars
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val params = window.attributes
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = params
    }

    val flags = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
    )

    // Set systemUiVisibility flags (critical for Android 10 / API 29)
    window.decorView.systemUiVisibility = flags

    // OnSystemUiVisibilityChangeListener to immediately re-hide if accidentally revealed
    window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
        if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
            window.decorView.systemUiVisibility = flags
        }
    }

    // Modern API 30+ path protected by SDK_INT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        Api30ImmersiveStatusBar.hide(window)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object Api30ImmersiveStatusBar {
    fun hide(window: Window) {
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }
}
