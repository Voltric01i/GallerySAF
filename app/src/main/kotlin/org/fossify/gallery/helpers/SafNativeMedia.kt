package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.fossify.gallery.models.Medium
import java.util.ArrayDeque

/** File:// is deliberately never inferred from a third-party document URI. */
object SafNativeMedia {
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl")
    private const val MAX_MEDIA = 20000

    fun scan(context: Context, tree: Uri): ArrayList<Medium> {
        val grant = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!grant) throw SecurityException("SAF read grant is missing")
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IllegalStateException("Cannot open SAF tree")
        if (!root.isDirectory) throw IllegalStateException("SAF tree is not a directory")
        val queue = ArrayDeque<DocumentFile>()
        val visited = HashSet<String>()
        val found = ArrayList<Medium>()
        queue.add(root)
        while (queue.isNotEmpty() && found.size < MAX_MEDIA && !Thread.currentThread().isInterrupted) {
            val folder = queue.removeFirst()
            if (!visited.add(folder.uri.toString())) continue
            for (child in folder.listFiles()) {
                if (found.size >= MAX_MEDIA) break
                val name = child.name ?: continue
                if (name.startsWith('.')) continue
                if (child.isDirectory) {
                    queue.add(child)
                } else if (child.isFile && isImage(name, child.type)) {
                    val timestamp = child.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                    found.add(Medium().apply {
                        this.name = name
                        path = child.uri.toString()
                        parentPath = folder.uri.toString()
                        modified = timestamp
                        taken = timestamp
                        size = child.length().coerceAtLeast(0)
                        type = when (name.substringAfterLast('.', "").lowercase()) {
                            "gif" -> TYPE_GIFS
                            "jxl" -> TYPE_IMAGES
                            else -> TYPE_IMAGES
                        }
                        mediaStoreId = 0L
                    })
                }
            }
        }
        found.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return found
    }

    fun canWrite(context: Context, tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    fun isImage(name: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true || name.substringAfterLast('.', "").lowercase() in extensions
}

/** An in-process viewer session avoids sending thousands of URIs through Binder. */
object SafNativeSession {
    var items: List<Medium> = emptyList()
}
