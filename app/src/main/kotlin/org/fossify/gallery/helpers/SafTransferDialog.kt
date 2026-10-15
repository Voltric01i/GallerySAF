package org.fossify.gallery.helpers

import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.gallery.R
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Destination picker and stream-based copier for arbitrary SAF document providers.
 * This intentionally does not use Gallery's FilePickerDialog, OTGPath, or path conversion.
 * Gallery-to-SAF transfers are supported. Gallery's original FilePickerDialog remains
 * available for ordinary paths; SAF-to-Gallery is handled by SafGalleryActivity.
 */
class SafTransferDialog(private val activity: BaseSimpleActivity) {
    private val worker = Executors.newSingleThreadExecutor()

    fun show(
        sourcePaths: List<String>,
        copy: Boolean,
        onUseOriginalPicker: () -> Unit,
        onComplete: () -> Unit
    ) {
        val roots = SafAlbumStore.registeredRoots(activity)
        if (roots.isEmpty()) {
            onUseOriginalPicker()
            worker.shutdown()
            return
        }
        val entries = listOf(activity.getString(R.string.saf_v4_normal_storage)) +
            roots.map { activity.getString(R.string.saf_v4_provider_prefix, SafAlbumStore.albumLabel(activity, it)) }
        AlertDialog.Builder(activity)
            .setTitle(if (copy) R.string.saf_v4_copy_destination else R.string.saf_v4_move_destination)
            .setItems(entries.toTypedArray()) { _, which ->
                if (which == 0) {
                    onUseOriginalPicker()
                    worker.shutdown()
                } else {
                    val uri = Uri.parse(roots[which - 1])
                    val canWrite = activity.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isWritePermission
                    }
                    if (!canWrite) {
                        message(activity.getString(R.string.saf_v4_destination_read_only))
                        worker.shutdown()
                    } else {
                        val tree = DocumentFile.fromTreeUri(activity, uri)
                        if (tree == null || !tree.isDirectory) {
                            message(activity.getString(R.string.saf_v4_unavailable))
                            worker.shutdown()
                        } else {
                            browse(tree, sourcePaths, copy, onComplete)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> worker.shutdown() }
            .show()
    }

    private fun browse(
        folder: DocumentFile,
        sourcePaths: List<String>,
        copy: Boolean,
        onComplete: () -> Unit
    ) {
        val loading = AlertDialog.Builder(activity)
            .setMessage(R.string.saf_v4_loading)
            .setCancelable(false)
            .create()
        loading.show()
        worker.execute {
            val result = runCatching {
                folder.listFiles().filter { it.isDirectory }.sortedBy { it.name?.lowercase() ?: "" }
            }
            activity.runOnUiThread {
                loading.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.isFailure) {
                    message(activity.getString(R.string.saf_v4_unavailable))
                    worker.shutdown()
                    return@runOnUiThread
                }
                val children = result.getOrThrow()
                val titles = listOf(activity.getString(R.string.saf_v4_select_here)) +
                    children.map { "📁 ${it.name ?: "Folder"}" }
                AlertDialog.Builder(activity)
                    .setTitle(folder.name ?: activity.getString(R.string.saf_v4_provider_name))
                    .setItems(titles.toTypedArray()) { _, index ->
                        if (index == 0) {
                            confirmTransfer(folder, sourcePaths, copy, onComplete)
                        } else {
                            browse(children[index - 1], sourcePaths, copy, onComplete)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> worker.shutdown() }
                    .show()
            }
        }
    }

    private fun confirmTransfer(
        targetFolder: DocumentFile,
        paths: List<String>,
        copy: Boolean,
        onComplete: () -> Unit
    ) {
        val verb = if (copy) R.string.saf_v4_copy_destination else R.string.saf_v4_move_destination
        AlertDialog.Builder(activity)
            .setTitle(verb)
            .setMessage(activity.getString(R.string.saf_v4_confirm, paths.size, targetFolder.name ?: "SAF"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> transfer(targetFolder, paths, copy, onComplete) }
            .show()
    }

    private fun transfer(folder: DocumentFile, paths: List<String>, copy: Boolean, onComplete: () -> Unit) {
        val progress = AlertDialog.Builder(activity)
            .setMessage(R.string.saf_v4_transferring)
            .setCancelable(false)
            .create()
        progress.show()
        worker.execute {
            var completed = 0
            var copiedButNotDeleted = 0
            var failed = 0
            for (path in paths) {
                val source = File(path)
                if (!source.isFile || !source.canRead()) {
                    failed++
                    continue
                }
                var target: DocumentFile? = null
                try {
                    // Never overwrite an existing document, even when this provider maps names oddly.
                    if (folder.findFile(source.name) != null) {
                        failed++
                        continue
                    }
                    val ext = source.extension.lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: "application/octet-stream"
                    val created = folder.createFile(mime, source.name)
                        ?: throw IllegalStateException("createFile returned null")
                    target = created
                    // Some providers append an extension to the document's display name.
                    if (created.name != source.name) {
                        throw IllegalStateException("Provider changed the destination name")
                    }
                    // Stream the source while hashing, then read back and verify the
                    // destination before a move is permitted to delete the original.
                    val sourceHash = MessageDigest.getInstance("SHA-256")
                    var bytesWritten = 0L
                    FileInputStream(source).use { input ->
                        activity.contentResolver.openOutputStream(created.uri, "w").use { output ->
                            requireNotNull(output) { "Cannot open destination stream" }
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                sourceHash.update(buffer, 0, count)
                                bytesWritten += count
                            }
                            output.flush()
                        }
                    }
                    if (bytesWritten != source.length()) {
                        throw IllegalStateException("Source changed during transfer")
                    }
                    val targetHash = MessageDigest.getInstance("SHA-256")
                    var bytesRead = 0L
                    activity.contentResolver.openInputStream(created.uri).use { input ->
                        requireNotNull(input) { "Cannot verify destination" }
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            targetHash.update(buffer, 0, count)
                            bytesRead += count
                        }
                    }
                    if (bytesRead != bytesWritten || !sourceHash.digest().contentEquals(targetHash.digest())) {
                        throw IllegalStateException("Destination verification failed")
                    }
                    if (!copy && !source.delete()) {
                        copiedButNotDeleted++
                    } else {
                        completed++
                    }
                    target = null
                } catch (_: Exception) {
                    // Delete incomplete targets, but do not remove the original file.
                    runCatching { target?.delete() }
                    failed++
                }
            }
            activity.runOnUiThread {
                progress.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    message(activity.getString(R.string.saf_v4_result, completed, copiedButNotDeleted, failed))
                    if (completed > 0 && !copy) onComplete()
                }
                worker.shutdown()
            }
        }
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
