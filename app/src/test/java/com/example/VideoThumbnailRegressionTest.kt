package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.decode.VideoFrameDecoder
import com.example.ui.components.videoThumbnailRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verify wiring only. Real codec execution belongs to RealVideoThumbnailTest
 * on an Android emulator, not a shadowed retriever/paused Robolectric main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VideoThumbnailRegressionTest {
    @Test
    fun videoContentUriIsRoutedToTheVideoFrameDecoder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://media/external/video/media/42")
        val request = videoThumbnailRequest(context, uri)
        assertEquals(uri, request.data)
        assertTrue("Video requests must not use a still-image decoder", request.decoderFactory is VideoFrameDecoder.Factory)
    }
}
