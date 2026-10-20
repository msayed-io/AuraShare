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

        val distinctItems = items.distinctBy { it.id }.toMutableList()
        // If local device has 0 media (e.g. freshly booted emulator), provide pristine initial local items
        if (distinctItems.isEmpty()) {
            distinctItems.addAll(getInitialImages())
        }
        distinctItems
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

        val distinctItems = items.distinctBy { it.id }.toMutableList()
        if (distinctItems.isEmpty()) {
            distinctItems.addAll(getInitialVideos())
        }
        distinctItems
    }

    suspend fun loadFiles(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
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

                if (idCol >= 0) {
                    var count = 0
                    while (cursor.moveToNext() && count < 200) {
                        count++
                        val id = cursor.getLong(idCol)
                        val name = if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else "ملف_$id"
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else "application/octet-stream"
                        val date = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                        val contentUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                name = name,
                                sizeBytes = size,
                                mimeType = mime,
                                dateModified = date,
                                bucketName = "المستندات",
                                mediaType = AuraMediaType.FILE
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error loading files from MediaStore", t)
        }

        val distinctItems = items.distinctBy { it.id }.toMutableList()
        if (distinctItems.isEmpty()) {
            distinctItems.addAll(getInitialFiles())
        }
        distinctItems
    }

    private fun getInitialImages(): List<MediaItem> = listOf(
        MediaItem(
            id = 101,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Aura_Architectural_Plan.jpg",
            sizeBytes = 4_850_000,
            mimeType = "image/jpeg",
            dateModified = System.currentTimeMillis() / 1000,
            bucketName = "الكاميرا",
            mediaType = AuraMediaType.IMAGE
        ),
        MediaItem(
            id = 102,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Deep_Space_Obsidian.png",
            sizeBytes = 8_320_000,
            mimeType = "image/png",
            dateModified = System.currentTimeMillis() / 1000 - 3600,
            bucketName = "التنزيلات",
            mediaType = AuraMediaType.IMAGE
        ),
        MediaItem(
            id = 103,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Liquid_Mercury_Drop.jpg",
            sizeBytes = 12_450_000,
            mimeType = "image/jpeg",
            dateModified = System.currentTimeMillis() / 1000 - 7200,
            bucketName = "الكاميرا",
            mediaType = AuraMediaType.IMAGE
        ),
        MediaItem(
            id = 104,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Monochrome_Engine_Schematic.jpg",
            sizeBytes = 6_180_000,
            mimeType = "image/jpeg",
            dateModified = System.currentTimeMillis() / 1000 - 14400,
            bucketName = "لقطات الشاشة",
            mediaType = AuraMediaType.IMAGE
        ),
        MediaItem(
            id = 105,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Minimalist_Dashboard_Dark.png",
            sizeBytes = 3_920_000,
            mimeType = "image/png",
            dateModified = System.currentTimeMillis() / 1000 - 28800,
            bucketName = "لقطات الشاشة",
            mediaType = AuraMediaType.IMAGE
        ),
        MediaItem(
            id = 106,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Satellite_Telemetry_Capture.jpg",
            sizeBytes = 15_600_000,
            mimeType = "image/jpeg",
            dateModified = System.currentTimeMillis() / 1000 - 86400,
            bucketName = "الكاميرا",
            mediaType = AuraMediaType.IMAGE
        )
    )

    private fun getInitialVideos(): List<MediaItem> = listOf(
        MediaItem(
            id = 201,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Fluid_Metaballs_Physics_4K.mp4",
            sizeBytes = 142_500_000,
            mimeType = "video/mp4",
            dateModified = System.currentTimeMillis() / 1000,
            durationMs = 184_000,
            bucketName = "الكاميرا",
            mediaType = AuraMediaType.VIDEO
        ),
        MediaItem(
            id = 202,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "High_Speed_Beam_Stream.mov",
            sizeBytes = 88_200_000,
            mimeType = "video/quicktime",
            dateModified = System.currentTimeMillis() / 1000 - 5400,
            durationMs = 96_000,
            bucketName = "التنزيلات",
            mediaType = AuraMediaType.VIDEO
        ),
        MediaItem(
            id = 203,
            uri = Uri.parse("android.resource://${context.packageName}/drawable/aurashare_icon_1792527142052"),
            name = "Quantum_Oscillation_Simulation.mp4",
            sizeBytes = 320_000_000,
            mimeType = "video/mp4",
            dateModified = System.currentTimeMillis() / 1000 - 18000,
            durationMs = 412_000,
            bucketName = "الكاميرا",
            mediaType = AuraMediaType.VIDEO
        )
    )

    private fun getInitialFiles(): List<MediaItem> {
        val sampleDir = java.io.File(context.filesDir, "sample_media").apply { if (!exists()) mkdirs() }
        
        fun createRealSampleFile(name: String, content: String): Pair<Uri, Long> {
            val file = java.io.File(sampleDir, name)
            if (!file.exists()) {
                file.writeText(content, Charsets.UTF_8)
            }
            return Pair(Uri.fromFile(file), file.length())
        }

        val file1 = createRealSampleFile("AuraShare_Network_Protocol_v2.pdf", "%PDF-1.4\n1 0 obj\n<< /Title (AuraShare Protocol) >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF")
        val file2 = createRealSampleFile("Core_Engine_Source_Release.zip", "PK\u0003\u0004AuraShareCoreReleaseArchiveData")
        val file3 = createRealSampleFile("Desktop_Handshake_Token.key", "AURASHARE-TOKEN-LOCAL-TEST-PAIRING-KEY-2026")
        val file4 = createRealSampleFile("Server_Configuration_Production.json", "{\"service\":\"AuraShare\",\"mode\":\"production\",\"port\":54321}")

        return listOf(
            MediaItem(
                id = 301,
                uri = file1.first,
                name = "AuraShare_Network_Protocol_v2.pdf",
                sizeBytes = file1.second,
                mimeType = "application/pdf",
                dateModified = System.currentTimeMillis() / 1000,
                bucketName = "المستندات",
                mediaType = AuraMediaType.FILE
            ),
            MediaItem(
                id = 302,
                uri = file2.first,
                name = "Core_Engine_Source_Release.zip",
                sizeBytes = file2.second,
                mimeType = "application/zip",
                dateModified = System.currentTimeMillis() / 1000 - 8000,
                bucketName = "التنزيلات",
                mediaType = AuraMediaType.FILE
            ),
            MediaItem(
                id = 303,
                uri = file3.first,
                name = "Desktop_Handshake_Token.key",
                sizeBytes = file3.second,
                mimeType = "text/plain",
                dateModified = System.currentTimeMillis() / 1000 - 24000,
                bucketName = "المستندات",
                mediaType = AuraMediaType.FILE
            ),
            MediaItem(
                id = 304,
                uri = file4.first,
                name = "Server_Configuration_Production.json",
                sizeBytes = file4.second,
                mimeType = "application/json",
                dateModified = System.currentTimeMillis() / 1000 - 45000,
                bucketName = "التنزيلات",
                mediaType = AuraMediaType.FILE
            )
        )
    }
}
