package com.vinstall.alwiz

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.vinstall.alwiz.databinding.ActivityTvFilePickerBinding
import com.vinstall.alwiz.util.TvFocus
import java.io.File

/** A remote-first package browser. Android TV does not guarantee a TV DocumentsUI. */
class TvFilePickerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvFilePickerBinding
    private lateinit var browser: TvFileBrowser
    private lateinit var adapter: TvFileAdapter
    private val selectedFiles = linkedSetOf<File>()
    private var currentDirectory: File? = null
    private var waitingForStorageSettings = false
    private var showSystemFolders = false

    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshBrowser(requestFocus = true) }

    private val storageSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        waitingForStorageSettings = false
        refreshBrowser(requestFocus = true)
    }

    private val otherFileManager = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uris = result.data?.let(::collectUris).orEmpty()
        if (uris.isNotEmpty()) finishWithUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        browser = TvFileBrowser(this)
        adapter = TvFileAdapter(
            isSelected = { selectedFiles.contains(it.file) },
            onClick = ::openItem,
        )

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { navigateBackOrFinish() }
        binding.allowAccessButton.setOnClickListener { requestStorageAccess() }
        binding.otherManagerButton.setOnClickListener { launchOtherFileManager() }
        binding.showSystemButton.setOnClickListener {
            showSystemFolders = !showSystemFolders
            updateSystemFolderButton()
            refreshBrowser(requestFocus = false)
            binding.showSystemButton.requestFocus()
        }
        binding.reviewButton.setOnClickListener {
            rememberCurrentDirectory()
            finishWithUris(selectedFiles.mapNotNull(::providerUri))
        }

        installFocusStyling()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBackOrFinish()
        })
        showSystemFolders = savedInstanceState?.getBoolean(STATE_SHOW_SYSTEM) ?: false
        val restoredDirectory = savedInstanceState?.getString(STATE_DIRECTORY)?.let(::File)
        currentDirectory = restoredDirectory?.let(browser::canonical) ?: chooseInitialDirectory()
        updateSystemFolderButton()
        refreshBrowser(requestFocus = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DIRECTORY, currentDirectory?.path)
        outState.putBoolean(STATE_SHOW_SYSTEM, showSystemFolders)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForStorageSettings) {
            waitingForStorageSettings = false
            refreshBrowser(requestFocus = true)
        }
    }

    private fun installFocusStyling() {
        TvFocus.install(binding.allowAccessButton)
        TvFocus.install(binding.showSystemButton)
        TvFocus.install(binding.otherManagerButton)
        TvFocus.install(binding.reviewButton)
    }

    private fun navigateBackOrFinish() {
        val directory = currentDirectory ?: run {
            finish()
            return
        }
        currentDirectory = browser.parent(directory, hasBroadStorageAccess())
        refreshBrowser(requestFocus = true)
    }

    private fun refreshBrowser(requestFocus: Boolean) {
        val granted = hasBroadStorageAccess()
        binding.permissionPanel.isVisible = !granted
        currentDirectory = browser.normalizeDirectory(currentDirectory, granted)
        val items = browser.items(currentDirectory, showSystemFolders, granted)

        binding.pathText.text = currentDirectory?.path ?: getString(R.string.tv_picker_storage)
        binding.emptyText.isVisible = items.isEmpty()
        binding.fileList.isVisible = items.isNotEmpty()
        adapter.submit(items)
        updateSelectionControls()

        if (requestFocus) {
            binding.root.post {
                when {
                    !granted -> binding.allowAccessButton.requestFocus()
                    items.isNotEmpty() -> binding.fileList.findViewHolderForAdapterPosition(0)
                        ?.itemView
                        ?.requestFocus()
                        ?: binding.fileList.requestFocus()
                    else -> binding.otherManagerButton.requestFocus()
                }
            }
        }
    }

    private fun openItem(item: TvBrowserItem) {
        when (item.kind) {
            TvBrowserItemKind.ROOT,
            TvBrowserItemKind.DIRECTORY,
            TvBrowserItemKind.PARENT,
            -> {
                currentDirectory = item.file
                refreshBrowser(requestFocus = true)
            }
            TvBrowserItemKind.PACKAGE -> {
                if (!selectedFiles.add(item.file)) selectedFiles.remove(item.file)
                adapter.notifySelectionChanged(item.file)
                updateSelectionControls()
            }
        }
    }

    private fun updateSelectionControls() {
        binding.selectedCountText.text = getString(
            R.string.tv_picker_selected_count,
            selectedFiles.size,
        )
        binding.reviewButton.isEnabled = selectedFiles.isNotEmpty()
    }

    private fun updateSystemFolderButton() {
        binding.showSystemButton.text = getString(
            if (showSystemFolders) R.string.tv_picker_hide_system else R.string.tv_picker_show_system,
        )
    }

    private fun chooseInitialDirectory(): File? {
        val remembered = getSharedPreferences(PICKER_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_DIRECTORY, null)
            ?.let(::File)
        return browser.chooseInitialDirectory(remembered, hasBroadStorageAccess())
    }

    private fun rememberCurrentDirectory() {
        val directory = currentDirectory?.let(browser::canonical) ?: return
        if (!browser.isExternalDirectory(directory, hasBroadStorageAccess())) return
        getSharedPreferences(PICKER_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_LAST_DIRECTORY, directory.path)
            .apply()
    }

    private fun hasBroadStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                waitingForStorageSettings = true
                storageSettings.launch(appPage)
            } catch (_: ActivityNotFoundException) {
                waitingForStorageSettings = true
                storageSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun launchOtherFileManager() {
        val selection = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (selection.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.tv_picker_no_file_manager, Toast.LENGTH_LONG).show()
            return
        }
        otherFileManager.launch(Intent.createChooser(selection, getString(R.string.tv_picker_title)))
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        intent.data?.let(result::add)
        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) result += clip.getItemAt(index).uri
        }
        return result.toList()
    }

    private fun finishWithUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        setResult(Activity.RESULT_OK, Intent().apply {
            putParcelableArrayListExtra(MainActivity.EXTRA_PACKAGE_URIS, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        finish()
    }

    private fun providerUri(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(this, "$packageName.provider", file)
    }.getOrNull()

    private companion object {
        const val PICKER_PREFS = "tv_file_picker"
        const val KEY_LAST_DIRECTORY = "last_directory"
        const val STATE_DIRECTORY = "directory"
        const val STATE_SHOW_SYSTEM = "show_system"
    }
}
