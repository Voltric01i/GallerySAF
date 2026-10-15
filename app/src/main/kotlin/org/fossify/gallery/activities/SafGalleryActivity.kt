package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import com.squareup.picasso.Picasso
import org.fossify.gallery.R
import org.fossify.gallery.helpers.SafAlbumStore
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SAF-backed gallery screen.  Uses the returned document URIs end-to-end; they are
 * never treated as File paths or passed to Gallery's MediaStore/SQLite index.
 *
 * A provider may grant read-only access (e.g. BitLocker reader).  When write
 * access is granted and the individual document supports the operation,
 * rename/delete and copy/move are offered with confirmation.
 */
class SafGalleryActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanGeneration = AtomicInteger(0)
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private val images = ArrayList<ImageEntry>()
    private val adapter by lazy { ImagesAdapter() }
    private var selectedImage: ImageEntry? = null
    private var moveAfterCopy = false

    private val openTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val granted = result.data?.flags ?: 0
        if (granted and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            showMessage(getString(R.string.saf_read_permission_failed))
            return@registerForActivityResult
        }
        try {
            val permissions = granted and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, permissions)
            prefs.edit().putStringSet(KEY_ROOTS, savedRoots() + uri.toString()).apply()
            // If a single album was previously selected, switch into the newly added album.
            intent.putExtra(EXTRA_TREE_URI, uri.toString())
            refresh()
        } catch (_: Exception) {
            showMessage(getString(R.string.saf_read_permission_failed))
        }
    }

    private val destinationPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val destination = result.data?.data ?: return@registerForActivityResult
        val source = selectedImage ?: return@registerForActivityResult
        val granted = result.data?.flags ?: 0
        if (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) {
            showMessage(getString(R.string.saf_write_denied))
            return@registerForActivityResult
        }
        try {
            // No persisted destination grant needed; the stream copy happens immediately.
            copyOrMove(source, destination, moveAfterCopy)
        } catch (_: Exception) {
            showMessage(getString(R.string.saf_write_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.saf_gallery_title)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(Button(this).apply {
            text = getString(R.string.saf_add_folder)
            setOnClickListener { pickFolder() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = getString(R.string.saf_manage_folders)
            setOnClickListener { manageFolders() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        status = TextView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = getString(R.string.saf_no_folders)
        }
        root.addView(status)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(3)
            verticalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = this@SafGalleryActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showPhoto(position) }
            setOnItemLongClickListener { _, _, position, _ ->
                showFileActions(images[position]); true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        // Re-scan after NTFS Reader unlocks/reconnects, or files change externally.
        if (::grid.isInitialized) refresh()
    }

    private fun savedRoots(): Set<String> = prefs.getStringSet(KEY_ROOTS, emptySet())?.toSet() ?: emptySet()

    private fun pickFolder() {
        openTree.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }

    private fun manageFolders() {
        val roots = savedRoots().sorted()
        if (roots.isEmpty()) {
            showMessage(getString(R.string.saf_no_folders))
            return
        }
        val labels = roots.map { root ->
            SafAlbumStore.albumLabel(this, root)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saf_remove_folder)
            .setItems(labels) { _, selected ->
                val removed = roots[selected]
                AlertDialog.Builder(this)
                    .setMessage(R.string.saf_confirm_remove)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.edit().putStringSet(KEY_ROOTS, savedRoots() - removed).apply()
                        SafAlbumStore.deleteMetadata(this, removed)
                        if (intent.getStringExtra(EXTRA_TREE_URI) == removed) {
                            intent.removeExtra(EXTRA_TREE_URI)
                        }
                        refresh()
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val selectedRoot = intent.getStringExtra(EXTRA_TREE_URI)
        val roots = savedRoots().let { saved ->
            if (selectedRoot != null) saved.filterTo(linkedSetOf()) { it == selectedRoot } else saved
        }
        val generation = scanGeneration.incrementAndGet()
        images.clear()
        adapter.notifyDataSetChanged()
        if (roots.isEmpty()) {
            status.text = getString(R.string.saf_no_folders)
            return
        }
        status.text = getString(R.string.saf_scanning)
        executor.execute {
            val found = ArrayList<ImageEntry>()
            val seen = HashSet<String>()
            val albumUpdates = ArrayList<AlbumUpdate>()
            var failures = 0
            for (root in roots) {
                val beforeCount = found.size
                if (generation != scanGeneration.get()) return@execute
                try {
                    val treeUri = Uri.parse(root)
                    val permission = contentResolver.persistedUriPermissions.any {
                        it.isReadPermission && it.uri == treeUri
                    }
                    if (!permission) {
                        failures++
                        continue
                    }
                    val tree = DocumentFile.fromTreeUri(this, treeUri)
                    if (tree == null || !tree.isDirectory) {
                        failures++
                        continue
                    }
                    val pending = ArrayDeque<DocumentFile>()
                    pending.add(tree)
                    while (pending.isNotEmpty() && found.size < MAX_IMAGES) {
                        if (generation != scanGeneration.get()) return@execute
                        val children = try {
                            val folder = pending.removeLast()
                            if (!seen.add(folder.uri.toString())) continue
                            folder.listFiles()
                        } catch (_: Exception) {
                            failures++
                            continue
                        }
                        for (child in children) {
                            if (generation != scanGeneration.get()) return@execute
                            val name = child.name ?: continue
                            if (name.startsWith('.')) continue
                            if (child.isDirectory) pending.add(child)
                            else if (child.isFile && supportedImage(child, name)) {
                                found.add(ImageEntry(child.uri, name, child.type ?: "image/*", treeUri))
                                if (found.size >= MAX_IMAGES) break
                            }
                        }
                    }
                    val fromRoot = found.subList(beforeCount, found.size)
                    albumUpdates.add(AlbumUpdate(root, tree.name, fromRoot.firstOrNull()?.uri, fromRoot.size))
                } catch (_: Exception) {
                    failures++
                }
            }
            found.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            mainHandler.post {
                if (generation != scanGeneration.get() || isFinishing || isDestroyed) return@post
                albumUpdates.forEach { update ->
                    SafAlbumStore.updateAlbum(this, update.root, update.name, update.cover, update.count)
                }
                images.clear()
                images.addAll(found)
                adapter.notifyDataSetChanged()
                status.text = getString(R.string.saf_image_count, found.size) +
                    if (failures > 0) " " + getString(R.string.saf_scan_warning, failures) else ""
            }
        }
    }

    private fun supportedImage(file: DocumentFile, name: String): Boolean {
        val mime = file.type?.lowercase() ?: ""
        if (mime.startsWith("image/")) return true
        return name.substringAfterLast('.', "").lowercase() in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )
    }

    private fun showPhoto(position: Int) {
        if (position !in images.indices) return
        var current = position
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels * 2 / 3
        ))
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val previous = Button(this).apply { text = "◀" }
        val next = Button(this).apply { text = "▶" }
        val actions = Button(this).apply { text = getString(R.string.saf_actions) }
        navigation.addView(previous, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navigation.addView(actions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        navigation.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(navigation)
        val dialog = AlertDialog.Builder(this).setView(content)
            .setPositiveButton(android.R.string.ok, null).create()
        fun render() {
            if (current !in images.indices) { dialog.dismiss(); return }
            val entry = images[current]
            dialog.setTitle(entry.name)
            Picasso.get().cancelRequest(image)
            Picasso.get().load(entry.uri)
                .resize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                .onlyScaleDown().centerInside().into(image)
        }
        previous.setOnClickListener { current = (current - 1 + images.size) % images.size; render() }
        next.setOnClickListener { current = (current + 1) % images.size; render() }
        actions.setOnClickListener { showFileActions(images[current], dialog) }
        dialog.show()
        render()
        dialog.setOnDismissListener { Picasso.get().cancelRequest(image) }
    }

    private fun hasWriteGrant(entry: ImageEntry): Boolean = contentResolver.persistedUriPermissions.any {
        it.isWritePermission && it.uri == entry.treeUri
    }

    private fun showFileActions(entry: ImageEntry, viewer: AlertDialog? = null) {
        val options = arrayOf(
            getString(R.string.saf_share), getString(R.string.saf_rename),
            getString(R.string.saf_delete), getString(R.string.saf_copy), getString(R.string.saf_move)
        )
        AlertDialog.Builder(this).setTitle(entry.name).setItems(options) { _, index ->
            when (index) {
                0 -> share(entry)
                1 -> rename(entry, viewer)
                2 -> delete(entry, viewer)
                3, 4 -> {
                    if (index == 4 && !hasWriteGrant(entry)) {
                        showMessage(getString(R.string.saf_write_denied))
                    } else {
                        selectedImage = entry
                        moveAfterCopy = index == 4
                        destinationPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        })
                    }
                }
            }
        }.show()
    }

    private fun share(entry: ImageEntry) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = entry.mime.takeUnless { it == "image/*" } ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            clipData = ClipData.newUri(contentResolver, entry.name, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.saf_share)))
    }

    private fun rename(entry: ImageEntry, viewer: AlertDialog?) {
        if (!hasWriteGrant(entry)) { showMessage(getString(R.string.saf_write_denied)); return }
        val input = EditText(this).apply { setText(entry.name); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.saf_rename).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || '/' in newName || newName == "." || newName == "..") {
                    showMessage(getString(R.string.saf_invalid_name))
                    return@setPositiveButton
                }
                executor.execute {
                    val ok = try {
                        DocumentFile.fromSingleUri(this, entry.uri)?.renameTo(newName) == true
                    } catch (_: Exception) { false }
                    mainHandler.post {
                        if (ok) { viewer?.dismiss(); refresh() }
                        else showMessage(getString(R.string.saf_write_denied))
                    }
                }
            }.show()
    }

    private fun delete(entry: ImageEntry, viewer: AlertDialog?) {
        if (!hasWriteGrant(entry)) { showMessage(getString(R.string.saf_write_denied)); return }
        AlertDialog.Builder(this).setTitle(R.string.saf_delete)
            .setMessage(getString(R.string.saf_permanent_delete, entry.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saf_delete) { _, _ ->
                executor.execute {
                    val ok = try {
                        DocumentFile.fromSingleUri(this, entry.uri)?.delete() == true
                    } catch (_: Exception) { false }
                    mainHandler.post {
                        if (ok) { viewer?.dismiss(); refresh() }
                        else showMessage(getString(R.string.saf_write_denied))
                    }
                }
            }.show()
    }

    private fun copyOrMove(source: ImageEntry, destinationUri: Uri, move: Boolean) {
        status.text = getString(R.string.saf_copying)
        executor.execute {
            var destination: DocumentFile? = null
            val result = try {
                val dir = DocumentFile.fromTreeUri(this, destinationUri)
                    ?: throw IllegalStateException("Destination is not available")
                val targetName = source.name
                // Prevent overwriting another document with the same name.
                if (dir.findFile(targetName) != null) throw IllegalStateException("File already exists")
                destination = dir.createFile(source.mime.takeUnless { it == "image/*" } ?: "application/octet-stream", targetName)
                    ?: throw IllegalStateException("Document create failed")
                val outputUri = destination!!.uri
                contentResolver.openInputStream(source.uri).use { input ->
                    if (input == null) throw IllegalStateException("Source open failed")
                    contentResolver.openOutputStream(outputUri, "w").use { output ->
                        if (output == null) throw IllegalStateException("Destination open failed")
                        input.copyTo(output)
                        output.flush()
                    }
                }
                if (move) {
                    if (!hasWriteGrant(source) ||
                        DocumentFile.fromSingleUri(this, source.uri)?.delete() != true) {
                        // Copy succeeded, but source could not be removed: report partial success.
                        throw SourceDeleteFailed()
                    }
                }
                true
            } catch (e: SourceDeleteFailed) {
                mainHandler.post {
                    showMessage(getString(R.string.saf_partial_move))
                    refresh()
                }
                return@execute
            } catch (_: Exception) {
                // Do not leave an incomplete copy; never delete source on copy failure.
                try { destination?.delete() } catch (_: Exception) { }
                false
            }
            mainHandler.post {
                showMessage(if (result) getString(R.string.saf_copy_done) else getString(R.string.saf_copy_failed))
                refresh()
            }
        }
    }

    private inner class ImagesAdapter : BaseAdapter() {
        override fun getCount() = images.size
        override fun getItem(position: Int) = images[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = (convertView as? ImageView) ?: ImageView(this@SafGalleryActivity).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116))
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            Picasso.get().load(images[position].uri).resize(dp(200), dp(200)).centerCrop().into(image)
            image.contentDescription = images[position].name
            return image
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        scanGeneration.incrementAndGet()
        executor.shutdownNow()
        super.onDestroy()
    }

    private class SourceDeleteFailed : RuntimeException()
    private data class ImageEntry(val uri: Uri, val name: String, val mime: String, val treeUri: Uri)
    private data class AlbumUpdate(val root: String, val name: String?, val cover: Uri?, val count: Int)

    companion object {
        const val EXTRA_TREE_URI = "org.fossify.gallery.extra.SAF_TREE_URI"
        private const val PREFS = "saf_gallery"
        private const val KEY_ROOTS = "saf_tree_uris"
        private const val MAX_IMAGES = 10000
    }
}
