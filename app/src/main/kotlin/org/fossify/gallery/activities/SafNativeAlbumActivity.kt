package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Menu
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.databinding.ActivityMediaBinding
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.SafNativeMedia
import org.fossify.gallery.helpers.SafNativeSession
import org.fossify.gallery.helpers.SafAlbumStore
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Native Gallery grid/list adapter displaying SAF backed Medium instances. */
class SafNativeAlbumActivity : SimpleActivity(), MediaOperationsListener {
    private val binding by viewBinding(ActivityMediaBinding::inflate)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var scanCancellation: CancellationSignal? = null
    private var scanTask: Future<*>? = null
    private lateinit var tree: Uri
    private var allMedia = arrayListOf<Medium>()
    private var transferSources = listOf<Medium>()
    private var transferMove = false

    private val chooseDestination = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { destination ->
        if (destination != null) performTransfer(destination, transferSources, transferMove)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = intent.getStringExtra(SafGalleryActivity.EXTRA_TREE_URI) ?: run { finish(); return }
        tree = Uri.parse(root)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.mediaMenu), padBottomImeAndSystem = listOf(binding.mediaGrid))
        val albumName = SafAlbumStore.albumLabel(this, root)
        binding.mediaMenu.requireToolbar().inflateMenu(R.menu.menu_media)
        binding.mediaMenu.setupMenu()
        binding.mediaMenu.toggleForceArrowBackIcon(true)
        binding.mediaMenu.onNavigateBackClickListener = { finish() }
        binding.mediaMenu.updateHintText(albumName)
        binding.mediaMenu.requireToolbar().title = albumName
        binding.mediaMenu.requireToolbar().menu.apply {
            val visible = setOf(R.id.sort, R.id.change_view_type, R.id.toggle_filename, R.id.column_count)
            for (i in 0 until size()) getItem(i).isVisible = getItem(i).itemId in visible
        }
        binding.mediaMenu.requireToolbar().setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort -> { chooseSort(); true }
                R.id.column_count -> { changeColumnCount(); true }
                R.id.toggle_filename -> {
                    config.displayFileNames = !config.displayFileNames
                    (binding.mediaGrid.adapter as? MediaAdapter)?.updateDisplayFilenames(config.displayFileNames)
                    true
                }
                R.id.change_view_type -> {
                    toggleViewType()
                    true
                }
                else -> false
            }
        }
        binding.mediaMenu.onSearchTextChangedListener = { filterItems(it) }
        (binding.mediaGrid.layoutManager as? MyGridLayoutManager)?.spanCount = config.mediaColumnCnt
        binding.mediaRefreshLayout.setOnRefreshListener { loadMedia() }
        binding.mediaFastscroller.updateColors(getProperPrimaryColor())
        loadMedia()
    }

    private fun applyAdapter(items: ArrayList<Medium>) {
        val entries = ArrayList<ThumbnailItem>(items)
        val previous = binding.mediaGrid.adapter as? MediaAdapter
        if (previous == null) {
            val adapter = MediaAdapter(
                activity = this, media = entries, listener = this,
                isAGetIntent = false, allowMultiplePicks = false,
                path = SafAlbumStore.albumPath(tree), recyclerView = binding.mediaGrid
            ) { clicked ->
                if (clicked is Medium) {
                    SafNativeSession.items = allMedia.toList()
                    val index = allMedia.indexOfFirst { it.path == clicked.path }
                    startActivity(Intent(this, SafNativeViewerActivity::class.java).apply {
                        putExtra(SafNativeViewerActivity.EXTRA_POSITION, index.coerceAtLeast(0))
                    })
                }
            }
            binding.mediaGrid.adapter = adapter
        } else {
            previous.updateMedia(entries)
        }
        binding.mediaEmptyTextPlaceholder.beVisibleIf(items.isEmpty())
        binding.mediaEmptyTextPlaceholder2.beGone()
        binding.mediaRefreshLayout.isRefreshing = false
        binding.loadingIndicator.hide()
    }

    private fun loadMedia() {
        // Cancel a preceding query immediately if the user refreshes repeatedly.
        scanCancellation?.cancel()
        scanTask?.cancel(true)
        val cancel = CancellationSignal()
        scanCancellation = cancel
        val scanId = ++generation
        binding.loadingIndicator.show()
        scanTask = worker.submit {
            val outcome = runCatching { SafNativeMedia.scan(this, tree, cancel) }
            runOnUiThread {
                if (isDestroyed || scanId != generation) return@runOnUiThread
                scanTask = null
                scanCancellation = null
                outcome.onSuccess {
                    allMedia = it
                    SafAlbumStore.updateAlbum(this, tree.toString(), albumNameForTree(),
                        it.firstOrNull()?.path?.let(Uri::parse), it.size)
                    applyAdapter(it)
                }.onFailure {
                    binding.loadingIndicator.hide()
                    binding.mediaRefreshLayout.isRefreshing = false
                    if (it !is android.os.OperationCanceledException &&
                        it !is java.util.concurrent.CancellationException) {
                        toastError(getString(R.string.saf_native_read_failed) + ": " + (it.message ?: ""))
                    }
                }
            }
        }
    }

    private fun albumNameForTree(): String = SafAlbumStore.albumLabel(this, tree.toString())

    private fun filterItems(text: String) {
        val current = allMedia.filter { it.name.contains(text, ignoreCase = true) }
        applyAdapter(ArrayList(current))
    }

    private fun chooseSort() {
        val titles = arrayOf(getString(R.string.saf_native_name), getString(R.string.saf_native_newest), getString(R.string.saf_native_oldest))
        AlertDialog.Builder(this).setItems(titles) { _, selected ->
            when (selected) {
                0 -> allMedia.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                1 -> allMedia.sortByDescending { it.modified }
                else -> allMedia.sortBy { it.modified }
            }
            applyAdapter(allMedia)
        }.show()
    }

    private fun changeColumnCount() {
        val widths = (2..8).map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.saf_native_column_count).setItems(widths) { _, index ->
            config.mediaColumnCnt = index + 2
            (binding.mediaGrid.layoutManager as? MyGridLayoutManager)?.spanCount = index + 2
        }.show()
    }

    private fun toggleViewType() {
        // Use the very same adapter and views as regular Gallery, changing its configured view type.
        val path = SafAlbumStore.albumPath(tree)
        val next = if (config.getFolderViewType(path) == VIEW_TYPE_GRID) VIEW_TYPE_LIST else VIEW_TYPE_GRID
        config.saveFolderViewType(path, next)
        binding.mediaGrid.adapter = null
        (binding.mediaGrid.layoutManager as? MyGridLayoutManager)?.spanCount = if (next == VIEW_TYPE_GRID) config.mediaColumnCnt else 1
        applyAdapter(allMedia)
    }

    /** Invoked only by MediaAdapter when its path is a SAF album. */
    fun handleSafAction(actionId: Int, selected: List<Medium>) {
        if (selected.isEmpty()) return
        when (actionId) {
            R.id.cab_share -> share(selected)
            R.id.cab_rename -> if (selected.size == 1) rename(selected.first())
            R.id.cab_delete -> delete(selected)
            R.id.cab_copy_to, R.id.cab_move_to -> {
                transferSources = selected.toList()
                transferMove = actionId == R.id.cab_move_to
                if (transferMove && !SafNativeMedia.canWrite(this, tree)) {
                    toastError(getString(R.string.saf_write_denied))
                } else {
                    chooseDestination.launch(null)
                }
            }
            R.id.cab_properties -> {
                val text = selected.joinToString("\n") { "${it.name} (${it.size} bytes)" }
                AlertDialog.Builder(this).setTitle(R.string.saf_native_properties).setMessage(text)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun share(selected: List<Medium>) {
        val uris = selected.map { Uri.parse(it.path) }
        val action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        val clip = ClipData.newUri(contentResolver, selected[0].name, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val send = Intent(action).apply {
            type = "image/*"
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        startActivity(Intent.createChooser(send, getString(R.string.saf_share)))
    }

    private fun rename(medium: Medium) {
        if (!SafNativeMedia.canWrite(this, tree)) { toastError(getString(R.string.saf_write_denied)); return }
        val input = EditText(this).apply { setText(medium.name); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.saf_rename).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || '/' in name || name == "." || name == "..") {
                    toastError(getString(R.string.saf_invalid_name)); return@setPositiveButton
                }
                worker.execute {
                    val ok = runCatching { DocumentFile.fromSingleUri(this, Uri.parse(medium.path))?.renameTo(name) == true }
                        .getOrDefault(false)
                    runOnUiThread { if (ok) loadMedia() else toastError(getString(R.string.saf_write_denied)) }
                }
            }.show()
    }

    private fun delete(items: List<Medium>) {
        if (!SafNativeMedia.canWrite(this, tree)) { toastError(getString(R.string.saf_write_denied)); return }
        AlertDialog.Builder(this).setTitle(R.string.saf_delete)
            .setMessage(getString(R.string.saf_native_delete_confirm, items.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saf_delete) { _, _ ->
                worker.execute {
                    val failed = items.count { item ->
                        runCatching { DocumentFile.fromSingleUri(this, Uri.parse(item.path))?.delete() != true }.getOrDefault(true)
                    }
                    runOnUiThread {
                        if (failed != 0) toastError(getString(R.string.saf_native_failed, failed))
                        loadMedia()
                    }
                }
            }.show()
    }

    /** Copy and verify every file before any source is removed. Destination is a writable SAF tree. */
    private fun performTransfer(destination: Uri, sources: List<Medium>, move: Boolean) {
        if (sources.isEmpty()) return
        binding.loadingIndicator.show()
        worker.execute {
            var failures = 0
            val destRoot = DocumentFile.fromTreeUri(this, destination)
            if (destRoot == null || !destRoot.isDirectory || !destRoot.canWrite()) {
                runOnUiThread { binding.loadingIndicator.hide(); toastError(getString(R.string.saf_write_denied)) }
                return@execute
            }
            for (item in sources) {
                var created: DocumentFile? = null
                try {
                    if (destRoot.findFile(item.name) != null) throw IllegalStateException("Destination exists")
                    val mime = contentResolver.getType(Uri.parse(item.path)) ?: "application/octet-stream"
                    val target = destRoot.createFile(mime, item.name) ?: error("Create failed")
                    created = target
                    if (target.name != item.name) error("Provider changed the destination name")
                    val originalDigest = MessageDigest.getInstance("SHA-256")
                    contentResolver.openInputStream(Uri.parse(item.path)).use { input ->
                        requireNotNull(input)
                        contentResolver.openOutputStream(target.uri, "w").use { output ->
                            requireNotNull(output)
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(bytes)
                                if (count < 0) break
                                originalDigest.update(bytes, 0, count)
                                output.write(bytes, 0, count)
                            }
                            output.flush()
                        }
                    }
                    val copiedDigest = MessageDigest.getInstance("SHA-256")
                    contentResolver.openInputStream(target.uri).use { input ->
                        requireNotNull(input)
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            copiedDigest.update(bytes, 0, count)
                        }
                    }
                    if (!originalDigest.digest().contentEquals(copiedDigest.digest())) {
                        throw IllegalStateException("Hash verification failed")
                    }
                    if (move) {
                        // If deletion fails the copied destination is deliberately retained.
                        if (DocumentFile.fromSingleUri(this, Uri.parse(item.path))?.delete() != true) {
                            created = null
                            throw IllegalStateException("Source delete failed after verified copy")
                        }
                    }
                } catch (_: Exception) {
                    failures++
                    runCatching { created?.delete() }
                }
            }
            runOnUiThread {
                binding.loadingIndicator.hide()
                if (failures == 0) Toast.makeText(this, R.string.saf_copy_done, Toast.LENGTH_SHORT).show()
                else toastError(getString(R.string.saf_native_failed, failures))
                if (move) loadMedia()
            }
        }
    }

    override fun refreshItems() = loadMedia()
    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        // The SAF action path in MediaAdapter handles delete. Never call File APIs for these URIs.
    }
    override fun selectedPaths(paths: ArrayList<String>) = Unit
    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) = Unit

    // Returning from the viewer reuses the already loaded image list.
    // An explicit swipe-to-refresh or successful file mutation invokes loadMedia().
    override fun onDestroy() {
        generation++
        scanCancellation?.cancel()
        scanTask?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }
    private fun toastError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
