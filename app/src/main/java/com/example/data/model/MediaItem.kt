package com.example.data.model

import android.net.Uri
import java.util.Locale

enum class AuraMediaType {
    IMAGE,
    VIDEO,
    FILE
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateModified: Long,
    val durationMs: Long? = null,
    val bucketName: String = "الكل",
    val mediaType: AuraMediaType,
    val directoryPath: String? = null
) {
    val isDirectory: Boolean
        get() = mimeType == "vnd.android.document/directory"
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 بايت"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f جيجابايت", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f ميجابايت", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.0f كيلوبايت", kb)
                else -> "$sizeBytes بايت"
            }
        }

    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs ?: 0L) / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
}

data class TransferRecord(
    val id: String,
    val item: MediaItem,
    val status: TransferStatus,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedSpeed: String
        get() {
            val mb = speedBytesPerSec / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f ميجابايت/ث", mb)
        }
}

enum class TransferStatus {
    QUEUED,
    STREAMING,
    COMPLETED,
    FAILED
}
