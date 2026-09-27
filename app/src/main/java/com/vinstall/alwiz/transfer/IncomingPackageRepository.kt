package com.vinstall.alwiz.transfer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.vinstall.alwiz.util.StorageBudget
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class IncomingPackageEntry(
    val id: String,
    val displayName: String,
    val size: Long,
    val file: File,
    val createdAt: Long,
)

/** Atomic, private staging for packages received over the local network. */
class IncomingPackageRepository(private val context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val gson = Gson()

    @Synchronized
    fun beginUpload(originalName: String, expectedSize: Long? = null): PendingUpload {
        val displayName = sanitizeFileName(originalName)
        if (expectedSize != null) {
            require(expectedSize >= 0) { "Invalid upload size" }
            ensureSpace(expectedSize)
        }
        val id = UUID.randomUUID().toString()
        return PendingUpload(id, displayName, expectedSize)
    }

    @Synchronized
    fun list(): List<IncomingPackageEntry> {
        cleanupOrphans()
        return directory.listFiles { file -> file.extension.equals(METADATA_EXTENSION, true) }
            .orEmpty()
            .mapNotNull(::readMetadata)
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun find(id: String): IncomingPackageEntry? {
        if (!SAFE_ID.matches(id)) return null
        return readMetadata(File(directory, "$id.$METADATA_EXTENSION"))
    }

    fun uriFor(entry: IncomingPackageEntry): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        entry.file,
    )

    @Synchronized
    fun deleteByUri(uri: Uri): Boolean {
        val entry = list().firstOrNull { uriFor(it) == uri } ?: return false
        return delete(entry.id)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!SAFE_ID.matches(id)) return false
        val metadataFile = File(directory, "$id.$METADATA_EXTENSION")
        val entry = readMetadata(metadataFile)
        val packageDeleted = entry?.file?.delete()
            ?: directory.listFiles { file -> file.name.startsWith("$id.") && file.extension != METADATA_EXTENSION }
                .orEmpty().all(File::delete)
        val metadataDeleted = !metadataFile.exists() || metadataFile.delete()
        return packageDeleted && metadataDeleted
    }

    @Synchronized
    fun cleanupExpired(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        list().forEach { entry ->
            if (now - entry.createdAt >= MAX_AGE_MS && delete(entry.id)) removed++
        }
        return removed
    }

    inner class PendingUpload internal constructor(
        private val id: String,
        private val displayName: String,
        private val expectedSize: Long?,
    ) : Closeable {
        private val extension = displayName.substringAfterLast('.').lowercase(Locale.ROOT)
        private val temporary = File(directory, "$id.$extension.part")
        private val output = FileOutputStream(temporary)
        private var bytesWritten = 0L
        private var completed = false

        @Synchronized
        @Throws(IOException::class)
        fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
            check(!completed) { "Upload is already closed" }
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (expectedSize != null && bytesWritten + length > expectedSize) {
                throw IOException("Upload exceeds its declared size")
            }
            ensureSpace(length.toLong())
            output.write(buffer, offset, length)
            bytesWritten += length
        }

        @Synchronized
        fun commit(): IncomingPackageEntry {
            check(!completed) { "Upload is already closed" }
            completed = true
            output.fd.sync()
            output.close()
            if (bytesWritten == 0L) {
                temporary.delete()
                throw IOException("Empty package upload")
            }
            if (expectedSize != null && bytesWritten != expectedSize) {
                temporary.delete()
                throw IOException("Incomplete package upload")
            }
            val destination = File(directory, "$id.$extension")
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IOException("Unable to finalize package upload")
            }
            val entry = IncomingPackageEntry(id, displayName, destination.length(), destination, System.currentTimeMillis())
            try {
                writeMetadata(entry)
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            return entry
        }

        @Synchronized
        fun abort() {
            if (!completed) {
                completed = true
                runCatching { output.close() }
                temporary.delete()
            }
        }

        override fun close() = abort()
    }

    private fun ensureSpace(bytes: Long) {
        StorageBudget.requireSpace(directory, bytes)
    }

    private fun writeMetadata(entry: IncomingPackageEntry) {
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
            if (!temporary.renameTo(target)) throw IOException("Unable to persist package metadata")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun readMetadata(metadataFile: File): IncomingPackageEntry? = runCatching {
        val metadata = gson.fromJson(metadataFile.readText(), Metadata::class.java)
        if (!SAFE_ID.matches(metadata.id) || sanitizeFileName(metadata.displayName) != metadata.displayName) return null
        val extension = metadata.displayName.substringAfterLast('.').lowercase(Locale.ROOT)
        val file = File(directory, "${metadata.id}.$extension")
        if (!file.isFile) {
            metadataFile.delete()
            return null
        }
        IncomingPackageEntry(metadata.id, metadata.displayName, file.length(), file, metadata.createdAt)
    }.getOrNull()

    private fun cleanupOrphans() {
        directory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".part") && System.currentTimeMillis() - file.lastModified() >= PART_MAX_AGE_MS -> file.delete()
                file.extension.lowercase(Locale.ROOT) in ALLOWED_EXTENSIONS &&
                    !File(directory, "${file.name.substringBeforeLast('.')}.$METADATA_EXTENSION").isFile &&
                    System.currentTimeMillis() - file.lastModified() >= ORPHAN_GRACE_MS -> file.delete()
            }
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "incoming"
        private const val METADATA_EXTENSION = "json"
        private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(24)
        private val PART_MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)
        private val ORPHAN_GRACE_MS = TimeUnit.HOURS.toMillis(1)
        private val SAFE_ID = Regex("[a-fA-F0-9-]{36}")
        val ALLOWED_EXTENSIONS = setOf("apk", "apkm", "apks", "apkv", "xapk", "zip")

        @JvmStatic
        fun sanitizeFileName(originalName: String): String {
            val trimmed = originalName.trim()
            require(trimmed.isNotEmpty()) { "Missing filename" }
            require(!trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.contains('\u0000')) {
                "Invalid filename"
            }
            require(trimmed != "." && trimmed != "..") { "Invalid filename" }
            val extension = trimmed.substringAfterLast('.', "").lowercase(Locale.ROOT)
            require(extension in ALLOWED_EXTENSIONS) { "Unsupported package format" }
            val stem = trimmed.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._() -]"), "_")
                .trim('.', ' ')
                .ifBlank { "package" }
                .take(120)
            return "$stem.$extension"
        }
    }

    private data class Metadata(val id: String, val displayName: String, val createdAt: Long)
}
