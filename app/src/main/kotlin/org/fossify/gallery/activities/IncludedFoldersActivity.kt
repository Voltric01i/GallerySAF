package org.fossify.gallery.activities

import org.fossify.gallery.helpers.SafAlbumStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.gallery.R
import org.fossify.gallery.adapters.ManageFoldersAdapter
import org.fossify.gallery.databinding.ActivityManageFoldersBinding
import org.fossify.gallery.extensions.config

class IncludedFoldersActivity : SimpleActivity(), RefreshRecyclerViewListener {

    private val safFolderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val granted = (result.data?.flags ?: 0) and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (granted and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            Toast.makeText(this, R.string.saf_v4_registration_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, granted)
            SafAlbumStore.registerRoot(this, uri)
            Toast.makeText(this, R.string.saf_v4_registered, Toast.LENGTH_SHORT).show()
            updateFolders()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.saf_v4_registration_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val binding by viewBinding(ActivityManageFoldersBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateFolders()
        setupOptionsMenu()
        binding.manageFoldersToolbar.title = getString(R.string.include_folders)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.manageFoldersAppbar),
            padBottomSystem = listOf(binding.manageFoldersList)
        )
        setupMaterialScrollListener(binding.manageFoldersList, binding.manageFoldersAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageFoldersAppbar, NavigationIcon.Arrow)
    }

    private fun updateFolders() {
        val folders = ArrayList<String>()
        config.includedFolders.mapTo(folders) { it }
        SafAlbumStore.registeredRoots(this).mapTo(folders) { it }
        binding.manageFoldersPlaceholder.apply {
            text = getString(R.string.included_activity_placeholder)
            beVisibleIf(folders.isEmpty())
            setTextColor(getProperTextColor())
        }

        val adapter = ManageFoldersAdapter(this, folders, false, this, binding.manageFoldersList) {}
        binding.manageFoldersList.adapter = adapter
    }

    private fun setupOptionsMenu() {
        binding.manageFoldersToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_folder -> addFolder()
                R.id.open_saf_gallery -> startActivity(Intent(this, SafGalleryActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun refreshItems() {
        updateFolders()
    }

    private fun addFolder() {
        val choices = arrayOf(getString(R.string.saf_v4_add_provider), getString(R.string.saf_v4_add_legacy))
        AlertDialog.Builder(this)
            .setTitle(R.string.saf_v4_choose_source)
            .setItems(choices) { _, which ->
                if (which == 0) {
                    safFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                } else {
                    showAddIncludedFolderDialog { updateFolders() }
                }
            }
            .show()
    }
}
