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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinstall.alwiz.databinding.ActivityTvFilePickerBinding
import com.vinstall.alwiz.util.TvFocus
import java.io.File
import java.util.Locale

/** A remote-first package browser. Android TV does not guarantee a TV DocumentsUI. */
class TvFilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvFilePickerBinding
    private val selectedFiles = linkedSetOf<File>()
    private val rootLabels = linkedMapOf<String, String>()
    private var currentDirectory: File? = null
    private lateinit var adapter: FileAdapter
    private var waitingForStorageSettings = false
    private var showSystemFolders = false

    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshBrowser(requestFocus = true) }

    private val storageSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        waitingForStorageSettings = false
        refreshBrowser(requestFocus = true)
    }

    private val otherFileManager = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uris = result.data?.let(::collectUris).orEmpty()
        if (uris.isNotEmpty()) finishWithUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FileAdapter(
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

        TvFocus.install(binding.allowAccessButton)
        TvFocus.install(binding.showSystemButton)
        TvFocus.install(binding.otherManagerButton)
        TvFocus.install(binding.reviewButton)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBackOrFinish()
        })
        showSystemFolders = savedInstanceState?.getBoolean(STATE_SHOW_SYSTEM) ?: false
        currentDirectory = savedInstanceState?.getString(STATE_DIRECTORY)?.let(::File)?.canonicalOrNull()
        if (currentDirectory == null) currentDirectory = chooseInitialDirectory()
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

    private fun navigateBackOrFinish() {
        val directory = currentDirectory ?: run {
            finish()
            return
        }
        val root = containingRoot(directory)
        val parent = directory.parentFile?.canonicalOrNull()
        if (root != null && parent != null && TvFileBrowserPolicy.isWithin(parent, root)) {
            currentDirectory = if (directory == root) null else parent
            refreshBrowser(requestFocus = true)
        } else {
            currentDirectory = null
            refreshBrowser(requestFocus = true)
        }
    }

    private fun refreshBrowser(requestFocus: Boolean) {
        val roots = availableRoots()
        val granted = hasBroadStorageAccess()
        binding.permissionPanel.isVisible = !granted

        val directory = currentDirectory?.canonicalOrNull()
        if (directory != null && roots.none { TvFileBrowserPolicy.isWithin(directory, it) }) currentDirectory = null

        val items = if (currentDirectory == null) {
            roots.map { root ->
                BrowserItem(
                    file = root,
                    label = rootLabels[root.path] ?: root.name.ifBlank { root.path },
                    detail = root.path,
                    kind = ItemKind.ROOT,
                )
            }
        } else {
            directoryItems(currentDirectory!!)
        }

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
                        ?.itemView?.requestFocus() ?: binding.fileList.requestFocus()
                    else -> binding.otherManagerButton.requestFocus()
                }
            }
        }
    }

    private fun directoryItems(directory: File): List<BrowserItem> {
        val root = containingRoot(directory) ?: return emptyList()
        val result = mutableListOf<BrowserItem>()
        if (directory != root) {
            result += BrowserItem(
                file = directory.parentFile?.canonicalOrNull() ?: root,
                label = getString(R.string.tv_picker_parent),
                detail = directory.parent ?: root.path,
                kind = ItemKind.PARENT,
            )
        }
        val children = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            .mapNotNull { it.canonicalOrNull() }
            .filter { child ->
                TvFileBrowserPolicy.isWithin(child, root) && when {
                    child.isDirectory -> child.canRead() &&
                        TvFileBrowserPolicy.shouldShowDirectory(child.name, showSystemFolders)
                    child.isFile -> TvFileBrowserPolicy.isSupportedPackage(child.name) && child.canRead()
                    else -> false
                }
            }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        children.forEach { child ->
            result += BrowserItem(
                file = child,
                label = child.name,
                detail = if (child.isDirectory) child.path else formatSize(child.length()),
                kind = if (child.isDirectory) ItemKind.DIRECTORY else ItemKind.PACKAGE,
            )
        }
        return result
    }

    private fun openItem(item: BrowserItem) {
        when (item.kind) {
            ItemKind.ROOT, ItemKind.DIRECTORY, ItemKind.PARENT -> {
                currentDirectory = item.file
                refreshBrowser(requestFocus = true)
            }
            ItemKind.PACKAGE -> {
                if (!selectedFiles.add(item.file)) selectedFiles.remove(item.file)
                adapter.notifySelectionChanged(item.file)
                updateSelectionControls()
            }
        }
    }

    private fun updateSelectionControls() {
        binding.selectedCountText.text = getString(R.string.tv_picker_selected_count, selectedFiles.size)
        binding.reviewButton.isEnabled = selectedFiles.isNotEmpty()
    }

    private fun updateSystemFolderButton() {
        binding.showSystemButton.text = getString(
            if (showSystemFolders) R.string.tv_picker_hide_system else R.string.tv_picker_show_system
        )
    }

    private fun chooseInitialDirectory(): File? {
        val roots = availableRoots()
        val home = Environment.getExternalStorageDirectory()
        val remembered = getSharedPreferences(PICKER_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_DIRECTORY, null)?.let(::File)
        return TvFileBrowserPolicy.chooseInitialDirectory(
            remembered = remembered,
            downloads = File(home, Environment.DIRECTORY_DOWNLOADS),
            storageHome = home,
            incoming = File(filesDir, "incoming"),
            roots = roots,
        )
    }

    private fun rememberCurrentDirectory() {
        val directory = currentDirectory?.canonicalOrNull() ?: return
        val externalRoots = availableRoots().filterNot { it == File(filesDir, "incoming").canonicalOrNull() }
        if (externalRoots.none { TvFileBrowserPolicy.isWithin(directory, it) }) return
        getSharedPreferences(PICKER_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_LAST_DIRECTORY, directory.path)
            .apply()
    }

    private fun availableRoots(): List<File> {
        rootLabels.clear()
        val roots = linkedMapOf<String, File>()
        fun add(file: File, label: String) {
            val canonical = file.canonicalOrNull() ?: return
            if (!canonical.exists() && !canonical.mkdirs()) return
            if (!canonical.isDirectory || !canonical.canRead()) return
            if (canonical.path !in roots) {
                roots[canonical.path] = canonical
                rootLabels[canonical.path] = label
            }
        }

        add(File(filesDir, "incoming"), getString(R.string.tv_picker_received))
        if (hasBroadStorageAccess()) {
            add(Environment.getExternalStorageDirectory(), getString(R.string.tv_picker_storage))
            File("/storage").listFiles()?.forEach { volume ->
                if (volume.isDirectory && volume.name != "emulated" && volume.name != "self") {
                    add(volume, volume.name)
                }
            }
            getExternalFilesDirs(null).forEach { appDirectory ->
                var candidate = appDirectory?.canonicalOrNull()
                while (candidate != null && candidate.name != "Android") candidate = candidate.parentFile
                candidate?.parentFile?.let { add(it, it.name.ifBlank { getString(R.string.tv_picker_storage) }) }
            }
        }
        return roots.values.toList()
    }

    private fun containingRoot(file: File): File? = availableRoots()
        .filter { TvFileBrowserPolicy.isWithin(file, it) }
        .maxByOrNull { it.path.length }

    private fun hasBroadStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
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
        if (clip != null) for (index in 0 until clip.itemCount) result += clip.getItemAt(index).uri
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

    private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.2f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private data class BrowserItem(
        val file: File,
        val label: String,
        val detail: String,
        val kind: ItemKind,
    )

    private enum class ItemKind { ROOT, DIRECTORY, PARENT, PACKAGE }

    private inner class FileAdapter(
        private val isSelected: (BrowserItem) -> Boolean,
        private val onClick: (BrowserItem) -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {
        private val items = mutableListOf<BrowserItem>()

        init { setHasStableIds(true) }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val type: TextView = view.findViewById(R.id.type_badge)
            val name: TextView = view.findViewById(R.id.name_text)
            val detail: TextView = view.findViewById(R.id.detail_text)
            val selected: TextView = view.findViewById(R.id.selected_badge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_file, parent, false)
            TvFocus.install(view)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.type.text = when (item.kind) {
                ItemKind.PACKAGE -> getString(R.string.tv_picker_package_badge)
                else -> getString(R.string.tv_picker_folder_badge)
            }
            holder.name.text = item.label
            holder.detail.text = item.detail
            holder.selected.isVisible = item.kind == ItemKind.PACKAGE && isSelected(item)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = TvFocus.stableId(
            "${items[position].kind}:${items[position].file.path}"
        )

        fun submit(newItems: List<BrowserItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun notifySelectionChanged(file: File) {
            val index = items.indexOfFirst { it.file == file }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    companion object {
        private const val PICKER_PREFS = "tv_file_picker"
        private const val KEY_LAST_DIRECTORY = "last_directory"
        private const val STATE_DIRECTORY = "directory"
        private const val STATE_SHOW_SYSTEM = "show_system"
    }
}
