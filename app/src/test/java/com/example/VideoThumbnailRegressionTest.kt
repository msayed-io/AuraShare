package com.example

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.SuccessResult
import com.example.ui.components.videoThumbnailRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

/**
 * Regression coverage for the same request configuration used by VideoGridItem.
 * Robolectric supplies the frame; this does not exercise a device's native codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class VideoThumbnailRegressionTest {
    @Test(timeout = 60_000)
    fun `video frame request uses VideoFrameDecoder and returns shadowed bitmap`() = runBlocking {
        // Coil returns results through Main.immediate; Robolectric's paused looper
        // cannot drain that callback while this test is inside runBlocking.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val video = File.createTempFile("aura-thumbnail-", ".mp4")
        val loader = ImageLoader(context)
        val expected = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        expected.eraseColor(0xff35a7d6.toInt())
        try {
            // Minimal ISO-BMFF/MP4 header bytes make this a real .mp4 fixture file.
            video.writeBytes(byteArrayOf(
                0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0, 0, 2, 0, 0, 0, 0, 0, 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
            ))
            ShadowMediaMetadataRetriever.addFrame(video.absolutePath, 0L, expected)

            val request = videoThumbnailRequest(context, Uri.fromFile(video)).newBuilder()
                .size(2, 3).allowHardware(false)
                .build()
            val result = withTimeout(30_000) { loader.execute(request) }

            assertTrue("VideoFrameDecoder should produce a successful result", result is SuccessResult)
            val bitmap = (result as SuccessResult).drawable
                .let { (it as android.graphics.drawable.BitmapDrawable).bitmap }
            assertEquals(expected.width, bitmap.width)
            assertEquals(expected.height, bitmap.height)
            assertEquals(expected.getPixel(0, 0), bitmap.getPixel(0, 0))
        } finally {
            loader.shutdown()
            expected.recycle()
            video.delete()
            ShadowMediaMetadataRetriever.reset()
            Dispatchers.resetMain()
        }
    }
}
