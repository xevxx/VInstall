package com.vinstall.alwiz

import java.io.File
import java.util.Locale

internal object TvFileBrowserPolicy {
    private val supportedExtensions = setOf("apk", "apkm", "apks", "apkv", "xapk", "zip")

    fun isSupportedPackage(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
        return extension in supportedExtensions
    }

    fun isWithin(file: File, root: File): Boolean {
        val filePath = file.path
        val rootPath = root.path.trimEnd(File.separatorChar)
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    fun shouldShowDirectory(name: String, showSystemFolders: Boolean): Boolean {
        if (showSystemFolders) return true
        return !name.startsWith('.') &&
            !name.equals("Android", ignoreCase = true) &&
            !name.equals("LOST.DIR", ignoreCase = true)
    }

    fun chooseInitialDirectory(
        remembered: File?,
        downloads: File,
        storageHome: File,
        incoming: File,
        roots: List<File>,
    ): File? = listOfNotNull(remembered, downloads, storageHome, incoming)
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .firstOrNull { candidate ->
            candidate.isDirectory && candidate.canRead() && roots.any { root ->
                val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
                canonicalRoot != null && isWithin(candidate, canonicalRoot)
            }
        }
}
