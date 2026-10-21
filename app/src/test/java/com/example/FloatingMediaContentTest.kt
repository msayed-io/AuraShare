package com.example

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToIndex
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import com.example.ui.components.MediaGridView
import com.example.ui.components.rememberFluidDragController
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class FloatingMediaContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: Long, type: AuraMediaType, name: String = "item-$id") =
        MediaItem(
            id = id,
            uri = Uri.parse("file:///sdcard/$name"),
            name = name,
            sizeBytes = 1024,
            mimeType = when (type) {
                AuraMediaType.IMAGE -> "image/jpeg"
                AuraMediaType.VIDEO -> "video/mp4"
                AuraMediaType.FILE -> "text/plain"
            },
            dateModified = id,
            durationMs = if (type == AuraMediaType.VIDEO) 1_000L else null,
            mediaType = type
        )

    @Test
    fun fileHeaderIsPartOfTheScrollContentAndFinalItemIsReachable() {
        val items = (1L..40L).map { item(it, AuraMediaType.FILE, "document-$it.txt") }
        compose.setContent {
            MyApplicationTheme {
                MediaGridView(
                    mediaType = AuraMediaType.FILE,
                    items = items,
                    dragController = rememberFluidDragController(onSendItem = {}),
                    onQuickSend = {},
                    header = { Text("FILE_HEADER") }
                )
            }
        }

        compose.onNodeWithText("FILE_HEADER").assertIsDisplayed()
        compose.onNodeWithTag("files_list").performScrollToIndex(items.size)
        compose.onNodeWithText("document-40.txt").assertIsDisplayed()
        compose.onNodeWithText("FILE_HEADER").assertDoesNotExist()
    }

    @Test
    fun emptyFileListStillShowsHeaderInsideScrollableContent() {
        compose.setContent {
            MyApplicationTheme {
                MediaGridView(
                    mediaType = AuraMediaType.FILE,
                    items = emptyList(),
                    dragController = rememberFluidDragController(onSendItem = {}),
                    onQuickSend = {},
                    header = { Text("EMPTY_HEADER") }
                )
            }
        }

        compose.onNodeWithTag("empty_media_list").assertIsDisplayed()
        compose.onNodeWithText("EMPTY_HEADER").assertIsDisplayed()
    }

    @Test
    fun imageCustomAccessibilitySendActionInvokesCallback() {
        val image = item(11, AuraMediaType.IMAGE, "photo.jpg")
        var sent: MediaItem? = null
        compose.setContent {
            MyApplicationTheme {
                MediaGridView(
                    mediaType = AuraMediaType.IMAGE,
                    items = listOf(image),
                    dragController = rememberFluidDragController(onSendItem = {}),
                    onQuickSend = { sent = it }
                )
            }
        }

        val actions = compose.onNodeWithTag("media_item_11").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { check(actions.single { it.label == "إرسال إلى الحاسوب" }.action()) }
        check(sent == image)
    }

    @Test
    fun videoCustomAccessibilitySendActionInvokesCallback() {
        val video = item(12, AuraMediaType.VIDEO, "clip.mp4")
        var sent: MediaItem? = null
        compose.setContent {
            MyApplicationTheme {
                MediaGridView(
                    mediaType = AuraMediaType.VIDEO,
                    items = listOf(video),
                    dragController = rememberFluidDragController(onSendItem = {}),
                    onQuickSend = { sent = it }
                )
            }
        }

        val actions = compose.onNodeWithTag("video_item_12").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { check(actions.single { it.label == "إرسال إلى الحاسوب" }.action()) }
        check(sent == video)
    }
}
