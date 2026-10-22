package com.example.data.network

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MediaThumbnailGeneratorTest {
    private val uri = Uri.parse("content://test/item/1")

    @Test
    fun `other MIME types have no thumbnail`() {
        val source = FakeSource()
        val result = generator(source).generate(item("application/pdf"))
        assertNull(result)
        assertEquals(emptyList<String>(), source.calls)
    }

    @Test
    fun `image thumbnail is no-wrap JPEG within size and edge limits`() {
        val source = FakeSource(image = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888))
        val result = generator(source).generate(item("image/jpeg"))
        assertNotNull(result)
        val bytes = android.util.Base64.decode(result, android.util.Base64.NO_WRAP)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertTrue(bytes.size <= 40 * 1024)
        val decoded = BitmapFactoryCompat.decode(bytes)
        assertTrue(maxOf(decoded.width, decoded.height) <= 320)
        decoded.recycle()
    }

    @Test
    fun `video uses embedded then stops before system and frame`() {
        val source = FakeSource(embedded = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888))
        assertNotNull(generator(source).generate(item("video/mp4")))
        assertEquals(listOf("embedded"), source.calls)
    }

    @Test
    fun `video falls through failed embedded and system to first frame`() {
        val source = FakeSource(system = null, frame = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888))
        assertNotNull(generator(source).generate(item("video/mp4")))
        assertEquals(listOf("embedded", "system", "frame"), source.calls)
    }

    @Test
    fun `video system wins when embedded is absent`() {
        val source = FakeSource(system = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888))
        assertNotNull(generator(source).generate(item("video/mp4")))
        assertEquals(listOf("embedded", "system"), source.calls)
    }

    @Test
    fun `embedded exception still falls through to system`() {
        val source = FakeSource(
            throwEmbedded = true,
            system = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        )
        assertNotNull(generator(source).generate(item("video/mp4")))
        assertEquals(listOf("embedded", "system"), source.calls)
    }

    @Test
    fun `all failing sources return null`() {
        val source = FakeSource()
        assertNull(generator(source).generate(item("image/jpeg")))
        assertNull(generator(source).generate(item("video/mp4")))
        assertEquals(listOf("image", "embedded", "system", "frame"), source.calls)
    }

    @Test
    @Config(sdk = [28])
    fun `sampled real JPEG applies EXIF rotation without full image decoding`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = java.io.File(app.cacheDir, "exif-rotate90.jpg")
        try {
            checkNotNull(javaClass.getResourceAsStream("/thumbnail-exif-rotate90.jpg")).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val source = item("image/jpeg").copy(uri = Uri.fromFile(file), sizeBytes = file.length())
            val encoded = MediaThumbnailGenerator(app).generate(source)
            assertNotNull(encoded)
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val bitmap = BitmapFactoryCompat.decode(bytes)
            try {
                assertEquals(160, bitmap.width)
                assertEquals(320, bitmap.height)
                val top = bitmap.getPixel(80, 60)
                val bottom = bitmap.getPixel(80, 260)
                assertTrue(android.graphics.Color.red(top) > android.graphics.Color.blue(top))
                assertTrue(android.graphics.Color.blue(bottom) > android.graphics.Color.red(bottom))
            } finally { bitmap.recycle() }
        } finally { file.delete() }
    }

    @Test
    fun `unreadable image returns null without synthetic preview`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertNull(MediaThumbnailGenerator(app).generate(item("image/jpeg").copy(uri = Uri.parse("file:///missing/source.jpg"))))
    }

    private fun generator(source: FakeSource) = MediaThumbnailGenerator(
        ApplicationProvider.getApplicationContext(), source
    )

    private fun item(mime: String) = MediaItem(1, uri, "x", 1, mime, 0, mediaType =
        if (mime.startsWith("image")) AuraMediaType.IMAGE else if (mime.startsWith("video")) AuraMediaType.VIDEO else AuraMediaType.FILE)

    private class FakeSource(
        private val image: Bitmap? = null,
        private val embedded: Bitmap? = null,
        private val system: Bitmap? = null,
        private val frame: Bitmap? = null,
        private val throwEmbedded: Boolean = false
    ) : MediaThumbnailGenerator.ThumbnailSource {
        val calls = mutableListOf<String>()
        override fun image(uri: Uri): Bitmap? { calls += "image"; return image }
        override fun embedded(uri: Uri): Bitmap? {
            calls += "embedded"
            if (throwEmbedded) throw IllegalStateException("embedded failed")
            return embedded
        }
        override fun system(uri: Uri): Bitmap? { calls += "system"; return system }
        override fun frame(uri: Uri): Bitmap? { calls += "frame"; return frame }
    }

    private object BitmapFactoryCompat {
        fun decode(bytes: ByteArray): Bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
