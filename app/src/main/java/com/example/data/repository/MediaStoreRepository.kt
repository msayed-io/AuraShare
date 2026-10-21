package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.AuraMediaType
import com.example.data.model.MediaItem
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fresh queries only: MediaStore visibility plus trees explicitly granted by the user. */
class MediaStoreRepository(private val context: Context) {
    private val tag = "MediaStoreRepository"

    suspend fun loadImages(): List<MediaItem> = withContext(Dispatchers.IO) {
        queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, AuraMediaType.IMAGE)
    }

    suspend fun loadVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, AuraMediaType.VIDEO)
    }

    suspend fun loadFiles(): List<MediaItem> = withContext(Dispatchers.IO) {
        val files = mutableListOf<MediaItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        ).apply { if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Files.FileColumns.RELATIVE_PATH) }
        try {
            context.contentResolver.query(
                uri, projection.toTypedArray(),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}",
                null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val id = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val name = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val size = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val mime = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val modified = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val relative = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (id >= 0 && c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val mediaId = c.getLong(id)
                    val itemUri = ContentUris.withAppendedId(uri, mediaId)
                    // A stale MediaStore row must not become a selectable phantom file.
                    if (!isReadable(itemUri)) continue
                    val mimeType = c.stringOr(mime, "application/octet-stream")
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    files += MediaItem(
                        mediaId, itemUri, c.stringOr(name, "ملف_$mediaId"), c.longOr(size),
                        mimeType, c.longOr(modified),
                        bucketName = c.stringOr(relative, "التخزين المشترك").trimEnd('/').ifBlank { "التخزين المشترك" },
                        mediaType = AuraMediaType.FILE
                    )
                }
            }
        } catch (e: SecurityException) {
            files.clear()
            Log.w(tag, "Shared files permission unavailable", e)
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Shared files provider unavailable", e)
        }
        // Tree grants are the source of truth for folders and unindexed documents.
        val saf = loadPersistedSafEntries()
        val safMediaUris = saf.asSequence().filterNot { it.isDirectory }.mapNotNull { item ->
            if (Build.VERSION.SDK_INT >= 29) {
                try { MediaStore.getMediaUri(context, item.uri)?.toString() }
                catch (_: Exception) { null }
            } else null
        }.toSet()
        (saf + files.filterNot { it.uri.toString() in safMediaUris }).distinctBy { it.uri }
    }

    private suspend fun queryMedia(uri: Uri, type: AuraMediaType): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val bucketColumn = if (type == AuraMediaType.VIDEO) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED, bucketColumn
        ).apply { if (type == AuraMediaType.VIDEO) add(MediaStore.Video.Media.DURATION) }
        try {
            context.contentResolver.query(uri, projection.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                val id = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val bucket = c.getColumnIndex(bucketColumn)
                val duration = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                while (id >= 0 && c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val mediaId = c.getLong(id)
                    val itemUri = ContentUris.withAppendedId(uri, mediaId)
                    if (!isReadable(itemUri)) continue
                    out += MediaItem(
                        mediaId, itemUri, c.stringOr(name, "وسيط_$mediaId"), c.longOr(size),
                        c.stringOr(mime, if (type == AuraMediaType.VIDEO) "video/mp4" else "image/jpeg"),
                        c.longOr(modified), if (type == AuraMediaType.VIDEO) c.longOr(duration) else null,
                        c.stringOr(bucket, "التخزين المشترك"), type
                    )
                }
            }
        } catch (e: SecurityException) {
            out.clear()
            Log.w(tag, "Media permission unavailable for $type", e)
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Media provider unavailable for $type", e)
        }
        return out.distinctBy { it.uri }
    }

    private fun isReadable(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) { false }
      catch (_: SecurityException) { false }
      catch (_: IllegalArgumentException) { false }

    private suspend fun loadPersistedSafEntries(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val rootPaths = mutableSetOf<String>()
        val visitedUris = mutableSetOf<String>()
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
        for (grant in grants) {
            currentCoroutineContext().ensureActive()
            val tree = mutableListOf<MediaItem>()
            try {
                val rootId = DocumentsContract.getTreeDocumentId(grant.uri)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, rootId)
                val rootName = context.contentResolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.stringOr(c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME), rootId.substringAfterLast(':')) else null
                } ?: continue // Deleted or revoked root: do not display a stale folder.
                var rootPath = rootName.ifBlank { rootId }
                if (rootPath in rootPaths) rootPath = "$rootPath (${grant.uri.authority}:$rootId)"
                rootPaths += rootPath
                tree += MediaItem(stableId(rootUri), rootUri, rootName, 0, DocumentsContract.Document.MIME_TYPE_DIR, 0,
                    bucketName = "التخزين المسموح", mediaType = AuraMediaType.FILE, directoryPath = rootPath)
                val pending = ArrayDeque<Pair<String, String>>()
                val visitedIds = mutableSetOf<String>()
                pending.add(rootId to rootPath)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (documentId, parentPath) = pending.removeFirst()
                    if (!visitedIds.add(documentId)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(grant.uri, documentId)
                    context.contentResolver.query(children, DOCUMENT_COLUMNS, null, null, null)?.use { c ->
                        val id = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val name = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modified = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        while (id >= 0 && c.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val childId = c.stringOr(id, "")
                            if (childId.isBlank() || childId in visitedIds) continue
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, childId)
                            if (!visitedUris.add(childUri.toString())) continue
                            val childName = c.stringOr(name, childId)
                            val childMime = c.stringOr(mime, "application/octet-stream")
                            val directory = childMime == DocumentsContract.Document.MIME_TYPE_DIR
                            // Ignore deleted/unreadable entries rather than displaying phantom documents.
                            if (!directory && !isReadable(childUri)) continue
                            val path = "$parentPath/$childName"
                            tree += MediaItem(stableId(childUri), childUri, childName, c.longOr(size), childMime,
                                c.longOr(modified) / 1000, bucketName = parentPath, mediaType = AuraMediaType.FILE,
                                directoryPath = if (directory) path else null)
                            if (directory) pending.add(childId to path)
                        }
                    }
                }
                // Do not publish a half-enumerated tree after a revoked grant.
                result += tree
            } catch (e: SecurityException) {
                Log.w(tag, "Document tree access revoked", e)
            } catch (e: IllegalArgumentException) {
                Log.w(tag, "Document tree no longer available", e)
            }
        }
        return result.distinctBy { it.uri }
    }

    private fun stableId(uri: Uri): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        var value = 0L
        repeat(8) { value = (value shl 8) or (digest[it].toLong() and 0xff) }
        return value or Long.MIN_VALUE // SAF and positive MediaStore IDs never collide.
    }

    private fun Cursor.stringOr(index: Int, fallback: String): String = if (index >= 0 && !isNull(index)) getString(index) else fallback
    private fun Cursor.longOr(index: Int): Long = if (index >= 0 && !isNull(index)) getLong(index) else 0L

    companion object {
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
