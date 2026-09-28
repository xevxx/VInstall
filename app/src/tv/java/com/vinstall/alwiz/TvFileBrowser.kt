package com.vinstall.alwiz

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

internal data class TvBrowserItem(
    val file: File,
    val label: String,
    val detail: String,
    val kind: TvBrowserItemKind,
)

internal enum class TvBrowserItemKind { ROOT, DIRECTORY, PARENT, PACKAGE }

internal class TvFileBrowser(private val context: Context) {
    private val incomingDirectory = File(context.filesDir, "incoming")

    fun canonical(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    fun chooseInitialDirectory(remembered: File?, hasBroadAccess: Boolean): File? {
        val home = Environment.getExternalStorageDirectory()
        return TvFileBrowserPolicy.chooseInitialDirectory(
            remembered = remembered,
            downloads = File(home, Environment.DIRECTORY_DOWNLOADS),
            storageHome = home,
            incoming = incomingDirectory,
            roots = roots(hasBroadAccess).map { it.file },
        )
    }

    fun normalizeDirectory(directory: File?, hasBroadAccess: Boolean): File? {
        val canonical = directory?.let(::canonical) ?: return null
        return canonical.takeIf { candidate ->
            roots(hasBroadAccess).any { TvFileBrowserPolicy.isWithin(candidate, it.file) }
        }
    }

    fun parent(directory: File, hasBroadAccess: Boolean): File? {
        val root = containingRoot(directory, hasBroadAccess) ?: return null
        val parent = directory.parentFile?.let(::canonical)
        return if (directory == root || parent == null || !TvFileBrowserPolicy.isWithin(parent, root)) {
            null
        } else {
            parent
        }
    }

    fun items(
        directory: File?,
        showSystemFolders: Boolean,
        hasBroadAccess: Boolean,
    ): List<TvBrowserItem> = if (directory == null) {
        roots(hasBroadAccess).map { root ->
            TvBrowserItem(root.file, root.label, root.file.path, TvBrowserItemKind.ROOT)
        }
    } else {
        directoryItems(directory, showSystemFolders, hasBroadAccess)
    }

    fun isExternalDirectory(directory: File, hasBroadAccess: Boolean): Boolean =
        roots(hasBroadAccess)
            .filterNot { it.file == canonical(incomingDirectory) }
            .any { TvFileBrowserPolicy.isWithin(directory, it.file) }

    private fun directoryItems(
        directory: File,
        showSystemFolders: Boolean,
        hasBroadAccess: Boolean,
    ): List<TvBrowserItem> {
        val root = containingRoot(directory, hasBroadAccess) ?: return emptyList()
        val result = mutableListOf<TvBrowserItem>()
        if (directory != root) {
            result += TvBrowserItem(
                file = directory.parentFile?.let(::canonical) ?: root,
                label = context.getString(R.string.tv_picker_parent),
                detail = directory.parent ?: root.path,
                kind = TvBrowserItemKind.PARENT,
            )
        }
        val children = runCatching { directory.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .mapNotNull(::canonical)
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
            result += TvBrowserItem(
                file = child,
                label = child.name,
                detail = if (child.isDirectory) child.path else formatSize(child.length()),
                kind = if (child.isDirectory) {
                    TvBrowserItemKind.DIRECTORY
                } else {
                    TvBrowserItemKind.PACKAGE
                },
            )
        }
        return result
    }

    private fun containingRoot(file: File, hasBroadAccess: Boolean): File? = roots(hasBroadAccess)
        .map { it.file }
        .filter { TvFileBrowserPolicy.isWithin(file, it) }
        .maxByOrNull { it.path.length }

    private fun roots(hasBroadAccess: Boolean): List<Root> {
        val roots = linkedMapOf<String, Root>()
        fun add(file: File, label: String) {
            val canonical = canonical(file) ?: return
            if (!canonical.exists() && !canonical.mkdirs()) return
            if (!canonical.isDirectory || !canonical.canRead()) return
            roots.putIfAbsent(canonical.path, Root(canonical, label))
        }

        add(incomingDirectory, context.getString(R.string.tv_picker_received))
        if (hasBroadAccess) {
            add(Environment.getExternalStorageDirectory(), context.getString(R.string.tv_picker_storage))
            File("/storage").listFiles()?.forEach { volume ->
                if (volume.isDirectory && volume.name != "emulated" && volume.name != "self") {
                    add(volume, volume.name)
                }
            }
            context.getExternalFilesDirs(null).forEach { appDirectory ->
                var candidate = appDirectory?.let(::canonical)
                while (candidate != null && candidate.name != "Android") {
                    candidate = candidate.parentFile
                }
                candidate?.parentFile?.let { storageRoot ->
                    add(
                        storageRoot,
                        storageRoot.name.ifBlank { context.getString(R.string.tv_picker_storage) },
                    )
                }
            }
        }
        return roots.values.toList()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(Locale.US, "%.2f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    private data class Root(val file: File, val label: String)
}
