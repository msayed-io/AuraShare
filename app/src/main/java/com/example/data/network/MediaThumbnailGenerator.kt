package com.example.data.network

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import com.example.data.model.MediaItem
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

/** Creates the small, in-memory preview sent with a transfer announcement. */
class MediaThumbnailGenerator private constructor(private val source: ThumbnailSource) {
    constructor(context: Context) : this(AndroidThumbnailSource(context.applicationContext))

    internal constructor(context: Context, source: ThumbnailSource) : this(source) {
        // This constructor is intentionally package-private: it makes source ordering testable.
    }

    fun generate(item: MediaItem): String? {
        return try {
            val bitmap = when {
                item.mimeType.startsWith("image/", ignoreCase = true) ->
                    attempt("image") { source.image(item.uri) }
                item.mimeType.startsWith("video/", ignoreCase = true) -> {
                    attempt("embedded") { source.embedded(item.uri) }
                        ?: attempt("system") { source.system(item.uri) }
                        ?: attempt("frame") { source.frame(item.uri) }
                }
                else -> null
            } ?: return null
            encode(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create thumbnail for ${item.uri}", e)
            null
        }
    }

    private inline fun attempt(name: String, block: () -> Bitmap?): Bitmap? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Thumbnail $name attempt failed", e)
        null
    }

    private fun encode(input: Bitmap): String? {
        var bitmap = input
        val owned = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Bitmap, Boolean>()
        )
        owned += input
        try {
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            if (maxOf(bitmap.width, bitmap.height) > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) { bitmap = scaled; owned += scaled }
            }
            var quality = 80
            var current = bitmap
            while (true) {
                val bytes = ByteArrayOutputStream(16 * 1024).use { out ->
                    check(current.compress(Bitmap.CompressFormat.JPEG, quality, out))
                    out.toByteArray()
                }
                if (bytes.size <= MAX_BYTES) return Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (quality > 35) {
                    quality -= 10
                    continue
                }
                if (maxOf(current.width, current.height) <= MIN_EDGE) return null
                val scale = 0.8f
                val smaller = Bitmap.createScaledBitmap(current,
                    (current.width * scale).toInt().coerceAtLeast(1),
                    (current.height * scale).toInt().coerceAtLeast(1), true)
                current = smaller
                owned += smaller
                quality = 80
            }
        } finally {
            owned.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    internal interface ThumbnailSource {
        fun image(uri: Uri): Bitmap?
        fun embedded(uri: Uri): Bitmap?
        fun system(uri: Uri): Bitmap?
        fun frame(uri: Uri): Bitmap?
    }

    private class AndroidThumbnailSource(private val context: Context) : ThumbnailSource {
        private val resolver: ContentResolver = context.contentResolver

        override fun image(uri: Uri): Bitmap? {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    // ContentResolver.loadThumbnail applies the provider's EXIF correction.
                    resolver.loadThumbnail(uri, Size(MAX_EDGE, MAX_EDGE), null)?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Some providers do not implement loadThumbnail; use sampled decoding below.
                }
            }
            return decodeSampled(uri)?.let { corrected(uri, it) }
        }

        override fun embedded(uri: Uri): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes -> decodeBytes(bytes) }
            } finally {
                retriever.release()
            }
        }

        override fun system(uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            try { resolver.loadThumbnail(uri, Size(MAX_EDGE, MAX_EDGE), null) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } else null

        override fun frame(uri: Uri): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                if (Build.VERSION.SDK_INT >= 27) {
                    val rawWidth = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: MAX_EDGE
                    val rawHeight = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: MAX_EDGE
                    val rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val width = if (rotation % 180 != 0) rawHeight else rawWidth
                    val height = if (rotation % 180 != 0) rawWidth else rawHeight
                    val scale = MAX_EDGE.toFloat() / maxOf(width, height).coerceAtLeast(1)
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1))
                } else {
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                retriever.release()
            }
        }

        private fun decodeSampled(uri: Uri): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }

        private fun corrected(uri: Uri, bitmap: Bitmap): Bitmap {
            val orientation = try {
                resolver.openInputStream(uri)?.use { input ->
                    android.media.ExifInterface(input).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A broken/missing EXIF block must not discard an otherwise valid bitmap.
                android.media.ExifInterface.ORIENTATION_NORMAL
            }
            val corrected = applyOrientation(bitmap, orientation)
            if (corrected !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            return corrected
        }

        private fun decodeBytes(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
        }
    }

    companion object {
        private const val TAG = "MediaThumbnailGenerator"
        private const val MAX_EDGE = 320
        private const val MIN_EDGE = 32
        private const val MAX_BYTES = 40 * 1024

        private fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (maxOf(width / (sample * 2), height / (sample * 2)) >= MAX_EDGE) sample *= 2
            return sample
        }

        private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                6 -> matrix.setRotate(90f)
                7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                8 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
