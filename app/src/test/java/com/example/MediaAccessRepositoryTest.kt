package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.MediaStoreRepository
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MediaAccessRepositoryTest {
    private lateinit var provider: FakeDocumentsProvider
    private lateinit var tree: Uri
    private lateinit var repository: MediaStoreRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
        tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        repository = MediaStoreRepository(context)
    }

    @Test
    fun persistedReadGrantsAreTraversedButWriteOnlyGrantsAreNot() = runBlocking {
        provider.add(ROOT_ID, Node("read.txt", "read", "text/plain"))
        provider.add("writeRoot", Node("write.txt", "write", "text/plain"))
        persist(tree)
        persist(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "writeRoot"), read = false, write = true)
        val files = repository.loadFiles()
        assertTrue(files.any { it.name == "read.txt" })
        assertFalse(files.any { it.name == "write.txt" })
    }

    @Test
    fun traversalReachesDeepDescendantsAndPreservesFullParentPath() = runBlocking {
        provider.add(ROOT_ID, Node("Level 1", "l1", DIR))
        provider.add("l1", Node("Level 2", "l2", DIR))
        provider.add("l2", Node("Level 3", "l3", DIR))
        provider.add("l3", Node("payload.bin", "payload", "application/octet-stream", 42))
        persist(tree)
        val files = repository.loadFiles()
        val payload = files.single { it.name == "payload.bin" }
        assertEquals("Root/Level 1/Level 2/Level 3", payload.bucketName)
        assertEquals(42L, payload.sizeBytes)
        assertTrue(files.any { it.directoryPath == "Root/Level 1/Level 2/Level 3" })
    }

    @Test
    fun emptyRootRemainsBrowsableAndRevokedRootDisappears() = runBlocking {
        persist(tree)
        val empty = repository.loadFiles().filter { it.uri.authority == AUTHORITY }
        assertEquals(1, empty.size)
        assertTrue(empty.single().isDirectory)
        assertEquals("Root", empty.single().directoryPath)
        provider.deniedId = ROOT_ID
        assertTrue(repository.loadFiles().none { it.uri.authority == AUTHORITY })
    }

    @Test
    fun revokedGrantDuringTraversalDoesNotPublishPartialResults() = runBlocking {
        provider.add(ROOT_ID, Node("visible-before-revoke.txt", "first", "text/plain"))
        provider.add(ROOT_ID, Node("Private", "revoked", DIR))
        provider.deniedId = "revoked"
        persist(tree)
        assertTrue(repository.loadFiles().none { it.uri.authority == AUTHORITY })
    }

    @Test
    fun refreshRemovesDeletedProviderDocuments() = runBlocking {
        provider.add(ROOT_ID, Node("old.txt", "old", "text/plain"))
        persist(tree)
        assertTrue(repository.loadFiles().any { it.name == "old.txt" })
        provider.remove("old")
        assertFalse(repository.loadFiles().any { it.name == "old.txt" })
    }

    @Test
    fun equalDisplayNamesHaveDistinctStableIdsAndFullPaths() = runBlocking {
        provider.add(ROOT_ID, Node("A", "a", DIR))
        provider.add(ROOT_ID, Node("B", "b", DIR))
        provider.add("a", Node("same.txt", "a-same", "text/plain"))
        provider.add("b", Node("same.txt", "b-same", "text/plain"))
        persist(tree)
        val same = repository.loadFiles().filter { it.name == "same.txt" }
        assertEquals(2, same.size)
        assertNotEquals(same[0].id, same[1].id)
        assertTrue(same.all { it.id < 0 })
        assertEquals(setOf("Root/A", "Root/B"), same.map { it.bucketName }.toSet())
        assertEquals(same.map { it.id }.toSet(), repository.loadFiles().filter { it.name == "same.txt" }.map { it.id }.toSet())
    }

    @Test
    fun missingPermissionOrEmptyMediaStoreNeverFabricatesItems() = runBlocking {
        assertTrue(repository.loadImages().isEmpty())
        assertTrue(repository.loadVideos().isEmpty())
        assertTrue(repository.loadFiles().isEmpty())
    }

    private fun persist(uri: Uri, read: Boolean = true, write: Boolean = false) {
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        ApplicationProvider.getApplicationContext<Context>().contentResolver.takePersistableUriPermission(uri, flags)
    }

    data class Node(val name: String, val id: String, val mime: String, val size: Long = 1L)

    class FakeDocumentsProvider : ContentProvider() {
        private val children = linkedMapOf<String, MutableList<Node>>()
        var deniedId: String? = null
        fun add(parent: String, node: Node) { children.getOrPut(parent) { mutableListOf() }.add(node) }
        fun remove(id: String) { children.values.forEach { it.removeAll { child -> child.id == id } } }
        override fun onCreate() = true
        override fun getType(uri: Uri) = DIR
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException(uri.toString())
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            val id = DocumentsContract.getDocumentId(uri)
            if (id == deniedId) throw SecurityException("revoked")
            if (children.values.none { list -> list.any { it.id == id } }) throw FileNotFoundException(uri.toString())
            val file = File(requireNotNull(context).cacheDir, "provider-${id.hashCode()}").apply { writeText("real file bytes") }
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            if (id == deniedId) throw SecurityException("revoked")
            val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val cursor = MatrixCursor(columns)
            val nodes = if (uri.lastPathSegment == "children") children[id].orEmpty()
                else listOf(Node(if (id == ROOT_ID) "Root" else "WriteRoot", id, DIR))
            nodes.forEach { node ->
                cursor.addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime
                        DocumentsContract.Document.COLUMN_SIZE -> node.size
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1000L
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        }
    }

    companion object {
        private const val AUTHORITY = "com.example.test.documents"
        private const val ROOT_ID = "primary:root"
        private const val DIR = DocumentsContract.Document.MIME_TYPE_DIR
    }
}
