package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.components.FloatingBackdropHost
import com.example.ui.components.FloatingBottomNavigation
import com.example.ui.components.FloatingGlassSurface
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolidCapsuleSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capsuleIsOpaqueButSurroundingPixelsRemainTheScrollingBackground() {
        assertSolidSurface(bottomNavigation = false)
    }

    @Test
    fun bottomNavigationIsAnOpaqueCapsuleWithoutAFullWidthSlab() {
        assertSolidSurface(bottomNavigation = true)
    }

    private fun assertSolidSurface(bottomNavigation: Boolean) {
        val background = mutableStateOf(Color.Red)
        compose.setContent {
            MyApplicationTheme(darkTheme = true) {
                Box(Modifier.size(360.dp, 160.dp)) {
                    FloatingBackdropHost(
                        modifier = Modifier.testTag("opacity_root"),
                        content = { Box(Modifier.fillMaxSize().background(background.value)) },
                        overlay = {
                            if (bottomNavigation) {
                                FloatingBottomNavigation("images", {}, Modifier.align(Alignment.Center))
                            } else {
                                FloatingGlassSurface(Modifier.width(160.dp).align(Alignment.Center)) {}
                            }
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
        val first = compose.onNodeWithTag("opacity_root").captureToImage().toPixelMap()
        compose.runOnIdle { background.value = Color.Blue }
        compose.waitForIdle()
        val second = compose.onNodeWithTag("opacity_root").captureToImage().toPixelMap()
        // Sample inside the surface, away from nav text/icons and the selected-tab indicator.
        val x = first.width / 2
        val y = first.height / 2 - (if (bottomNavigation) 21 else 0) * (first.width / 360f).toInt().coerceAtLeast(1)
        assertEquals("Surface alpha must remain 1 over changing content", first[x, y], second[x, y])
        assertEquals(1f, second[x, y].alpha, 0f)
        // At the same height, outside the rounded capsule, the content is still visible.
        val outsideX = (first.width * .03f).toInt()
        val outsideY = first.height / 2
        assertTrue(first[outsideX, outsideY].red > .95f)
        assertTrue(second[outsideX, outsideY].blue > .95f)
    }
}
