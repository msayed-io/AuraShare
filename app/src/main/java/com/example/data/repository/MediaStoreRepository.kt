package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreRepository(private val context: Context) {
    private val tag = "MediaStoreRepository"

    suspend fun loadImages(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media._ID
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                } else -1

                if (idCol >= 0) {
                    var count = 0
                    while (cursor.moveToNext() && count < 300) {
                        count++
                        val id = cursor.getLong(idCol)
                        val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else "صورة_$id.jpg"
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else "image/jpeg"
                        val date = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                        val bucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol) ?: "الكاميرا" else "الكاميرا"
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                name = name,
                                sizeBytes = size,
                                mimeType = mime,
                                dateModified = date,
                                bucketName = bucket,
                                mediaType = AuraMediaType.IMAGE
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error loading images from MediaStore", t)
        }

        items.distinctBy { it.id }
    }

    suspend fun loadVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Video.Media._ID
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                } else -1

                if (idCol >= 0) {
                    var count = 0
                    while (cursor.moveToNext() && count < 200) {
                        count++
                        val id = cursor.getLong(idCol)
                        val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else "فيديو_$id.mp4"
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else "video/mp4"
                        val date = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                        val duration = if (durationCol >= 0 && !cursor.isNull(durationCol)) cursor.getLong(durationCol) else 0L
                        val bucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol) ?: "الكاميرا" else "الكاميرا"
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                name = name,
                                sizeBytes = size,
                                mimeType = mime,
                                dateModified = date,
                                durationMs = duration,
                                bucketName = bucket,
                                mediaType = AuraMediaType.VIDEO
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error loading videos from MediaStore", t)
        }

        items.distinctBy { it.id }
    }

    suspend fun loadFiles(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Files.FileColumns.RELATIVE_PATH
            else MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}"
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1

                if (idCol >= 0) {
                    var count = 0
                    while (cursor.moveToNext() && count < 200) {
                        count++
                        val id = cursor.getLong(idCol)
                        val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else "ملف_$id"
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else "application/octet-stream"
                        val date = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                        val bucket = if (relativePathCol >= 0 && !cursor.isNull(relativePathCol))
                            cursor.getString(relativePathCol).trimEnd('/').substringAfterLast('/').ifBlank { "التخزين المشترك" }
                        else "التخزين المشترك"
                        val contentUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                name = name,
                                sizeBytes = size,
                                mimeType = mime,
                                dateModified = date,
                                bucketName = bucket,
                                mediaType = AuraMediaType.FILE
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error loading files from MediaStore", t)
        }

        items.distinctBy { it.id }
    }

}
