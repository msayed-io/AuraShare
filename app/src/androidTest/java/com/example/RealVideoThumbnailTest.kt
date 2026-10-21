package com.example

import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.SuccessResult
import com.example.ui.components.videoThumbnailRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an Android emulator: real MP4, MediaStore content URI, real platform video decoder. */
@RunWith(AndroidJUnit4::class)
class RealVideoThumbnailTest {
    @Test
    fun realVideoContentUriProducesTheVideoFrameInsteadOfAnEmptyCover() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "aurashare-thumbnail-regression.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AuraShareTest/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        val loader = ImageLoader(context)
        try {
            instrumentation.context.assets.open("thumbnail-red.mp4").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val request = videoThumbnailRequest(context, uri).newBuilder()
                .size(160, 96).allowHardware(false).build()
            val result = withContext(Dispatchers.IO) { loader.execute(request) }
            assertTrue("Video URI must decode successfully, not show an empty cover: $result", result is SuccessResult)
            val bitmap = ((result as SuccessResult).drawable as BitmapDrawable).bitmap
            assertEquals(160, bitmap.width)
            assertEquals(96, bitmap.height)
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("The decoded cover must be the red video frame", Color.red(pixel) > 200 && Color.green(pixel) < 50 && Color.blue(pixel) < 50)
        } finally {
            loader.shutdown()
            resolver.delete(uri, null, null)
        }
    }
}
