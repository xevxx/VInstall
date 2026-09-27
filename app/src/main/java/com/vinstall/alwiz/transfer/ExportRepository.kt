package com.vinstall.alwiz.transfer

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ExportEntry(
    val id: String,
    val displayName: String,
    val size: Long,
    val file: File,
    val createdAt: Long,
)

/** Owns private APKV exports that may be saved through SAF or downloaded while Receive is open. */
class ExportRepository(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val gson = Gson()

    @Synchronized
    fun register(file: File): ExportEntry {
        require(file.isFile) { "Export does not exist: ${file.name}" }
        val displayName = normalizeDisplayName(file.name)
        val existing = readMetadata(file)
        if (file.parentFile?.canonicalFile == directory.canonicalFile && existing != null) {
            return existing
        }

        val id = UUID.randomUUID().toString()
        val destination = File(directory, "$id.apkv")
        if (file.canonicalFile != destination.canonicalFile) {
            file.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val entry = ExportEntry(id, displayName, destination.length(), destination, System.currentTimeMillis())
        writeMetadata(entry)
        return entry
    }

    @Synchronized
    fun list(): List<ExportEntry> {
        cleanupOrphans()
        return directory.listFiles { file -> file.extension.equals(METADATA_EXTENSION, true) }
            .orEmpty()
            .mapNotNull(::readMetadataFile)
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun find(id: String): ExportEntry? {
        if (!isSafeId(id)) return null
        return readMetadataFile(File(directory, "$id.$METADATA_EXTENSION"))
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val metadata = File(directory, "$id.$METADATA_EXTENSION")
        val entry = readMetadataFile(metadata)
        val deletedFile = entry?.file?.delete() ?: File(directory, "$id.apkv").let { !it.exists() || it.delete() }
        val deletedMetadata = !metadata.exists() || metadata.delete()
        return deletedFile && deletedMetadata
    }

    @Synchronized
    fun cleanupExpired(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        list().forEach { entry ->
            if (now - entry.createdAt >= MAX_AGE_MS && delete(entry.id)) removed++
        }
        return removed
    }

    private fun readMetadata(exportFile: File): ExportEntry? {
        val metadata = directory.listFiles { candidate -> candidate.extension == METADATA_EXTENSION }
            .orEmpty()
            .firstOrNull { readMetadataFile(it)?.file?.canonicalFile == exportFile.canonicalFile }
        return metadata?.let(::readMetadataFile)
    }

    private fun writeMetadata(entry: ExportEntry) {
        val metadata = Metadata(entry.id, entry.displayName, entry.createdAt)
        val target = File(directory, "${entry.id}.$METADATA_EXTENSION")
        val temporary = File(directory, "${entry.id}.$METADATA_EXTENSION.part")
        temporary.writeText(gson.toJson(metadata))
        check(temporary.renameTo(target)) { "Unable to persist export metadata" }
    }

    private fun readMetadataFile(metadataFile: File): ExportEntry? = runCatching {
        val metadata = gson.fromJson(metadataFile.readText(), Metadata::class.java)
        if (!isSafeId(metadata.id) || normalizeDisplayName(metadata.displayName) != metadata.displayName) return null
        val file = File(directory, "${metadata.id}.apkv")
        if (!file.isFile) {
            metadataFile.delete()
            return null
        }
        ExportEntry(metadata.id, metadata.displayName, file.length(), file, metadata.createdAt)
    }.getOrNull()

    private fun cleanupOrphans() {
        directory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".part") && System.currentTimeMillis() - file.lastModified() >= PART_MAX_AGE_MS -> file.delete()
                file.extension.equals("apkv", true) &&
                    !File(directory, "${file.nameWithoutExtension}.$METADATA_EXTENSION").isFile -> file.delete()
            }
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "exports"
        private const val METADATA_EXTENSION = "json"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
        private val PART_MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)
        private val SAFE_ID = Regex("[a-fA-F0-9-]{36}")

        internal fun normalizeDisplayName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
            val stem = base.removeSuffix(".apkv").ifBlank { "export" }
                .replace(Regex("[^A-Za-z0-9._() -]"), "_")
                .take(120)
            return "$stem.apkv"
        }

        private fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)
    }

    private data class Metadata(val id: String, val displayName: String, val createdAt: Long)
}
