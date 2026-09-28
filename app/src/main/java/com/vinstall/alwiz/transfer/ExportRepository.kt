package com.vinstall.alwiz.transfer

import android.content.Context
import com.google.gson.Gson
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    fun register(file: File): ExportEntry = synchronized(PROCESS_LOCK) {
        require(file.isFile) { "Export does not exist: ${file.name}" }
        val displayName = normalizeDisplayName(file.name)
        val existing = readMetadata(file)
        if (file.parentFile?.canonicalFile == directory.canonicalFile && existing != null) {
            return@synchronized existing
        }

        val id = UUID.randomUUID().toString()
        val destination = File(directory, "$id.apkv")
        val partial = File(directory, "$id.apkv.part")
        try {
            if (file.parentFile?.canonicalFile == directory.canonicalFile) {
                if (!file.renameTo(destination)) throw IOException("Unable to atomically register export")
            } else {
                file.inputStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(FileUtil.BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            StorageBudget.requireSpace(directory, read.toLong())
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (!partial.renameTo(destination)) throw IOException("Unable to finalize export")
            }
            val entry = ExportEntry(
                id,
                displayName,
                destination.length(),
                destination,
                System.currentTimeMillis(),
            )
            writeMetadata(entry)
            entry
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            throw error
        }
    }

    fun list(): List<ExportEntry> = synchronized(PROCESS_LOCK) {
        cleanupOrphans()
        directory.listFiles { file -> file.extension.equals(METADATA_EXTENSION, true) }
            .orEmpty()
            .mapNotNull(::readMetadataFile)
            .sortedByDescending { it.createdAt }
    }

    fun find(id: String): ExportEntry? = synchronized(PROCESS_LOCK) {
        if (!isSafeId(id)) return@synchronized null
        readMetadataFile(File(directory, "$id.$METADATA_EXTENSION"))
    }

    fun delete(id: String): Boolean = synchronized(PROCESS_LOCK) {
        if (!isSafeId(id)) return@synchronized false
        val metadata = File(directory, "$id.$METADATA_EXTENSION")
        val entry = readMetadataFile(metadata)
        val deletedFile = entry?.file?.delete()
            ?: File(directory, "$id.apkv").let { !it.exists() || it.delete() }
        val deletedMetadata = !metadata.exists() || metadata.delete()
        deletedFile && deletedMetadata
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()): Int = synchronized(PROCESS_LOCK) {
        var removed = 0
        list().forEach { entry ->
            if (now - entry.createdAt >= MAX_AGE_MS && delete(entry.id)) removed++
        }
        removed
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
        val bytes = gson.toJson(metadata).toByteArray(Charsets.UTF_8)
        try {
            StorageBudget.requireSpace(directory, bytes.size.toLong())
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Unable to persist export metadata")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun readMetadataFile(metadataFile: File): ExportEntry? = runCatching {
        val metadata = gson.fromJson(metadataFile.readText(), Metadata::class.java)
        if (
            !isSafeId(metadata.id) ||
            normalizeDisplayName(metadata.displayName) != metadata.displayName
        ) {
            return null
        }
        val file = File(directory, "${metadata.id}.apkv")
        if (!file.isFile) {
            metadataFile.delete()
            return null
        }
        ExportEntry(
            metadata.id,
            metadata.displayName,
            file.length(),
            file,
            metadata.createdAt,
        )
    }.getOrNull()

    private fun cleanupOrphans() {
        directory.listFiles().orEmpty().forEach { file ->
            when {
                (file.name.endsWith(".part") || file.name.endsWith(".partial")) &&
                    System.currentTimeMillis() - file.lastModified() >= PART_MAX_AGE_MS -> file.delete()
                file.extension.equals("apkv", true) &&
                    !File(directory, "${file.nameWithoutExtension}.$METADATA_EXTENSION").isFile &&
                    System.currentTimeMillis() - file.lastModified() >= ORPHAN_GRACE_MS -> file.delete()
            }
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "exports"
        private const val METADATA_EXTENSION = "json"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
        private val PART_MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)
        private val ORPHAN_GRACE_MS = TimeUnit.HOURS.toMillis(1)
        private val SAFE_ID = Regex("[a-fA-F0-9-]{36}")
        private val PROCESS_LOCK = Any()

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
