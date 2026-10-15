package org.fossify.gallery.helpers

import android.content.Context
import android.net.Uri
import org.fossify.gallery.models.Directory

/**
 * SAF albums are transient Directory objects for presentation only.
 * They must never be persisted to the directory DB or scanned with File/MediaStore.
 * Actual media remains owned by the DocumentsProvider.
 */
object SafAlbumStore {
    private const val PREFS = "saf_gallery"
    private const val KEY_ROOTS = "saf_tree_uris"
    private const val NAME_PREFIX = "saf_album_name_"
    private const val COVER_PREFIX = "saf_album_cover_"
    private const val COUNT_PREFIX = "saf_album_count_"
    private const val PATH_PREFIX = "saf-tree-album:"

    /** Persisted SAF trees shared by the gallery, included-folder screen and move picker. */
    fun registeredRoots(context: Context): List<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ROOTS, emptySet())?.toList()?.sorted() ?: emptyList()
    }

    fun registerRoot(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ROOTS, registeredRoots(context).toSet() + uri.toString()).apply()
    }

    fun unregisterRoot(context: Context, root: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_ROOTS, registeredRoots(context).toSet() - root).apply()
        deleteMetadata(context, root)
        val uri = Uri.parse(root)
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (grant != null) {
            val flags = (if (grant.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags != 0) {
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            }
        }
    }

    fun isSafAlbumPath(path: String) = path.startsWith(PATH_PREFIX)

    fun albumPath(treeUri: Uri) = PATH_PREFIX + Uri.encode(treeUri.toString())

    fun treeUriFromAlbumPath(path: String): Uri? {
        if (!isSafAlbumPath(path)) return null
        return try { Uri.parse(Uri.decode(path.removePrefix(PATH_PREFIX))) } catch (_: Exception) { null }
    }

    fun albumLabel(context: Context, root: String): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = preferences.getString(NAME_PREFIX + root, null)
        return name?.takeIf { it.isNotBlank() }
            ?: Uri.decode(Uri.parse(root).lastPathSegment ?: "SAF folder")
    }

    fun albums(context: Context): ArrayList<Directory> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val roots = preferences.getStringSet(KEY_ROOTS, emptySet())?.toList()?.sorted() ?: emptyList()
        return ArrayList(roots.mapNotNull { root ->
            try {
                val uri = Uri.parse(root)
                if (uri.scheme != "content") return@mapNotNull null
                val count = preferences.getInt(COUNT_PREFIX + root, 0)
                val fallback = Uri.decode(uri.lastPathSegment ?: "SAF folder")
                val albumName = preferences.getString(NAME_PREFIX + root, null)
                    ?.takeIf { it.isNotBlank() } ?: fallback
                Directory().apply {
                    path = albumPath(uri)
                    name = "SAF · $albumName"
                    tmb = preferences.getString(COVER_PREFIX + root, "") ?: ""
                    mediaCnt = count
                    subfoldersMediaCount = count
                    containsMediaFilesDirectly = true
                    subfoldersCount = 1
                }
            } catch (_: Exception) {
                null
            }
        })
    }

    /** Called on the UI thread after the worker finishes scanning a tree. */
    fun updateAlbum(context: Context, root: String, name: String?, cover: Uri?, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (!name.isNullOrBlank()) putString(NAME_PREFIX + root, name)
            if (cover != null) putString(COVER_PREFIX + root, cover.toString())
            else remove(COVER_PREFIX + root)
            putInt(COUNT_PREFIX + root, count)
        }.apply()
    }

    fun deleteMetadata(context: Context, root: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(NAME_PREFIX + root)
            .remove(COVER_PREFIX + root)
            .remove(COUNT_PREFIX + root)
            .apply()
    }
}
