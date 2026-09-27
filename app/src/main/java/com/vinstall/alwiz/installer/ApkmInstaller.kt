package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import com.vinstall.alwiz.util.FileUtil
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

object ApkmInstaller {

    suspend fun install(
        context: Context,
        uri: Uri,
        onStep: (String) -> Unit,
        selectedSplits: List<String>? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> {
        return try {
            onStep("Extracting splits...")
            val cacheDir = File(context.cacheDir, "apkm_extract").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            extractSplits(context, uri, cacheDir, onStep)
            val apkFiles = cacheDir.listFiles { f -> f.name.endsWith(".apk") }?.toList()
                ?: emptyList()
            if (apkFiles.isEmpty()) return Result.failure(Exception("No APK splits found in APKM"))
            onStep("Installing splits...")
            SplitInstaller.installSplits(context, apkFiles, selectedSplits, onProgress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listSplits(context: Context, uri: Uri): List<String> {
        val splits = mutableListOf<String>()
        val stream = FileUtil.openStream(context, uri) ?: return splits
        val guard = ArchiveExtractionGuard(context.cacheDir)
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    splits.add(guard.destination(entry.name, ArchiveExtractionGuard.APK_EXTENSIONS).name)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return splits
    }

    private fun extractSplits(
        context: Context,
        uri: Uri,
        outDir: File,
        onStep: (String) -> Unit
    ) {
        val totalSize = FileUtil.getFileSize(context, uri).coerceAtLeast(1L)
        var extractedBytes = 0L
        val stream = FileUtil.openStream(context, uri) ?: throw IOException("Cannot open the selected archive")
        val guard = ArchiveExtractionGuard(outDir)
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val out = guard.destination(entry.name, ArchiveExtractionGuard.APK_EXTENSIONS)
                    val fileName = out.name
                    guard.extract(zip, out) { bytes ->
                        val pct = if (entry.size > 0) {
                            ((bytes * 100) / entry.size).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        onStep("Extracting $fileName… $pct%")
                    }
                    extractedBytes += out.length()
                    val totalPct = ((extractedBytes * 100) / totalSize).coerceIn(0, 100)
                    onStep("Extracting splits… $totalPct%")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
