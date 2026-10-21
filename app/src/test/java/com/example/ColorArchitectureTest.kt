package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.ui.components.AppleFolderDropdown
import com.example.ui.components.FolderOption
import com.example.ui.theme.AuraDarkTokens
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ColorArchitectureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `default theme exposes the required dark material scheme`() {
        var scheme: androidx.compose.material3.ColorScheme? = null
        composeTestRule.setContent {
            MyApplicationTheme {
                scheme = MaterialTheme.colorScheme
            }
        }
        composeTestRule.runOnIdle {
            val actual = requireNotNull(scheme)
            assertEquals(AuraDarkTokens.Background, actual.background)
            assertEquals(AuraDarkTokens.Surface, actual.surface)
            assertEquals(AuraDarkTokens.Interactive, actual.surfaceVariant)
            assertEquals(AuraDarkTokens.Primary, actual.primary)
            assertEquals(AuraDarkTokens.OnPrimary, actual.onPrimary)
            assertEquals(AuraDarkTokens.Secondary, actual.secondary)
            assertEquals(AuraDarkTokens.Primary, actual.onBackground)
            assertEquals(AuraDarkTokens.Primary, actual.onSurface)
            assertEquals(AuraDarkTokens.Secondary, actual.onSurfaceVariant)
            assertEquals(AuraDarkTokens.FocusedBorder, actual.outline)
            assertEquals(AuraDarkTokens.Border, actual.outlineVariant)
        }
    }

    @Test
    fun `dark tokens use the prescribed layered colors and alpha values`() {
        assertEquals(Color(0xFF000000), AuraDarkTokens.Background)
        assertEquals(Color(0xFF1C1C1E), AuraDarkTokens.Surface)
        assertEquals(Color(0xFF2C2C2E), AuraDarkTokens.Interactive)
        assertEquals(Color(0xFFF5F5F5), AuraDarkTokens.Primary)
        assertEquals(Color(0xFF000000), AuraDarkTokens.OnPrimary)
        assertEquals(Color(0xFF8E8E93), AuraDarkTokens.Secondary)
        // sRGB Color stores alpha in an 8-bit channel: permit only its rounding error.
        val alphaTolerance = 0.5f / 255f
        assertEquals(0.08f, AuraDarkTokens.Border.alpha, alphaTolerance)
        assertEquals(0.12f, AuraDarkTokens.FocusedBorder.alpha, alphaTolerance)
        assertEquals(0.12f, AuraDarkTokens.Selected.alpha, alphaTolerance)
        assertEquals(0.08f, AuraDarkTokens.FocusRing.alpha, alphaTolerance)
        assertEquals(0.28f, AuraDarkTokens.FocusBorder.alpha, alphaTolerance)
        assertEquals(0.10f, AuraDarkTokens.Divider.alpha, alphaTolerance)
        assertEquals(0.05f, AuraDarkTokens.Hover.alpha, alphaTolerance)
    }

    @Test
    fun `primary button colors meet high contrast requirement`() {
        val ratio = contrastRatio(AuraDarkTokens.Primary, AuraDarkTokens.OnPrimary)
        assertTrue("primary button contrast was $ratio", ratio >= 7.0)
    }

    @Test
    fun `explicit light theme remains available`() {
        var background = Color.Unspecified
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                background = MaterialTheme.colorScheme.background
            }
        }
        composeTestRule.runOnIdle {
            assertEquals(Color(0xFFEAE6D2), background)
            assertTrue(background != AuraDarkTokens.Background)
        }
    }

    @Test
    fun `folder menu exposes its selected option to accessibility services`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AppleFolderDropdown(listOf(FolderOption("الكل", 2), FolderOption("الكاميرا", 1)), "الكل", {})
            }
        }
        composeTestRule.onNodeWithTag("folder_dropdown_button").performClick()
        composeTestRule.onNodeWithTag("folder_item_الكل").assertIsSelected()
        composeTestRule.onNodeWithTag("folder_item_الكاميرا").assertIsNotSelected()
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05) / (darker + 0.05)
    }
}
