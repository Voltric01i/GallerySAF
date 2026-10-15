package org.fossify.gallery.helpers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import org.fossify.gallery.models.Medium
import java.util.ArrayDeque

/**
 * SAF media discovery using a single metadata Cursor per directory.
 *
 * Never infer a filesystem path from Document IDs: identifiers are opaque and
 * must be used through DocumentsContract with the original granted tree URI.
 */
object SafNativeMedia {
    private const val TAG = "SafNativeMedia"
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl")

    private val childProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    /**
     * A full scan is performed only when invoked by the activity (initial open,
     * explicit refresh, or a successful mutation). No arbitrary image-count cap.
     * Cancellation is propagated into ContentResolver.query().
     *
     * @throws SecurityException when the tree grant has been revoked.
     * @throws IllegalStateException when a directory cannot be listed. A failed
     *   query is NOT silently interpreted as an empty directory.
     */
    fun scan(
        context: Context,
        tree: Uri,
        cancellation: CancellationSignal = CancellationSignal(),
    ): ArrayList<Medium> {
        val resolver = context.contentResolver
        val grant = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!grant) throw SecurityException("SAF read grant is missing")

        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        val found = ArrayList<Medium>()
        var directoryQueries = 0
        var scannedDocuments = 0L
        var queryMs = 0L
        var cursorMs = 0L
        val startMs = SystemClock.elapsedRealtime()

        queue.add(rootId)
        while (queue.isNotEmpty()) {
            cancellation.throwIfCanceled()
            val parentId = queue.removeFirst()
            if (!visited.add(parentId)) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId).toString()

            val queryStart = SystemClock.elapsedRealtime()
            val cursor = resolver.query(childrenUri, childProjection, null, null, null, cancellation)
                ?: throw IllegalStateException("SAF directory query returned null")
            queryMs += SystemClock.elapsedRealtime() - queryStart
            directoryQueries++

            cursor.use { rows ->
                val idColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idColumn < 0) throw IllegalStateException("Provider omitted document IDs")
                val nameColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val timeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeColumn = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                val cursorStart = SystemClock.elapsedRealtime()
                while (rows.moveToNext()) {
                    cancellation.throwIfCanceled()
                    scannedDocuments++
                    val documentId = rows.getString(idColumn) ?: continue
                    val name = rows.optionalString(nameColumn) ?: continue
                    if (name.startsWith('.')) continue
                    val mime = rows.optionalString(mimeColumn)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (documentId !in visited) queue.add(documentId)
                        continue
                    }
                    if (!isImage(name, mime)) continue

                    val timestamp = rows.optionalLong(timeColumn).coerceAtLeast(0L)
                    val length = rows.optionalLong(sizeColumn).coerceAtLeast(0L)
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                    found.add(Medium().apply {
                        this.name = name
                        path = documentUri.toString()
                        parentPath = parentUri
                        modified = timestamp
                        taken = timestamp
                        size = length
                        type = if (name.substringAfterLast('.', "").lowercase() == "gif") TYPE_GIFS else TYPE_IMAGES
                        mediaStoreId = 0L
                    })
                }
                cursorMs += SystemClock.elapsedRealtime() - cursorStart
            }
        }

        cancellation.throwIfCanceled()
        found.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        Log.i(
            TAG,
            "scan completed: folders=$directoryQueries documents=$scannedDocuments " +
                "images=${found.size} queryMs=$queryMs cursorMs=$cursorMs " +
                "totalMs=${SystemClock.elapsedRealtime() - startMs}",
        )
        return found
    }

    private fun Cursor.optionalString(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    private fun Cursor.optionalLong(index: Int): Long =
        if (index < 0 || isNull(index)) 0L else getLong(index)

    fun canWrite(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    fun isImage(name: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true || name.substringAfterLast('.', "").lowercase() in extensions
}

/** An in-process viewer session avoids sending thousands of URIs through Binder. */
object SafNativeSession {
    var items: List<Medium> = emptyList()
}
