package com.vinstall.alwiz.installer

import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal class ArchiveExtractionGuard(private val outputDirectory: File) {
    private val extractedNames = HashSet<String>()
    private val archiveNames = HashSet<String>()
    private var entryCount = 0

    fun recordEntry() {
        entryCount++
        if (entryCount > MAX_ENTRIES) throw IOException("Archive contains more than $MAX_ENTRIES entries.")
    }

    fun recordEntry(entryName: String, isDirectory: Boolean) {
        recordEntry()
        val normalized = entryName.replace('\\', '/')
        if (normalized.isBlank() || normalized.any { it.isISOControl() }) {
            throw IOException("Archive contains an invalid entry name.")
        }
        if (!isDirectory) {
            val flatName = validateFlatName(normalized)
            if (!archiveNames.add(flatName.lowercase(Locale.ROOT))) {
                throw IOException("Archive contains duplicate filename: $flatName")
            }
        }
    }

    fun destination(entryName: String, allowedExtensions: Set<String>): File {
        val normalized = entryName.replace('\\', '/')
        val name = validateFlatName(normalized)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in allowedExtensions) throw IOException("Unsupported archive entry: $name")
        if (!extractedNames.add(name.lowercase(Locale.ROOT))) {
            throw IOException("Archive contains duplicate filename: $name")
        }
        val directory = outputDirectory.canonicalFile
        val destination = File(directory, name).canonicalFile
        if (destination.parentFile != directory) throw IOException("Archive entry escapes extraction directory.")
        return destination
    }

    private fun validateFlatName(normalized: String): String {
        val name = normalized.substringAfterLast('/')
        if (name.isBlank() || name.any { it.isISOControl() } || name.length > MAX_FILE_NAME_LENGTH) {
            throw IOException("Archive contains an invalid filename.")
        }
        return name
    }

    fun extract(input: InputStream, destination: File, onBytes: ((Long) -> Unit)? = null) {
        outputDirectory.mkdirs()
        val partial = File(outputDirectory, ".${destination.name}.${System.nanoTime()}.part")
        var written = 0L
        try {
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(FileUtil.BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    StorageBudget.requireSpace(outputDirectory, read.toLong())
                    output.write(buffer, 0, read)
                    written += read
                    onBytes?.invoke(written)
                }
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) throw IOException("Cannot replace ${destination.name}")
            if (!partial.renameTo(destination)) throw IOException("Cannot finalize ${destination.name}")
        } finally {
            partial.delete()
        }
    }

    companion object {
        const val MAX_ENTRIES = 512
        const val MAX_METADATA_BYTES = 1024 * 1024
        private const val MAX_FILE_NAME_LENGTH = 240
        val APK_EXTENSIONS = setOf("apk")
        val XAPK_PAYLOAD_EXTENSIONS = setOf("apk", "obb")

        fun readMetadata(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_METADATA_BYTES) throw IOException("Archive metadata exceeds 1 MiB.")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
