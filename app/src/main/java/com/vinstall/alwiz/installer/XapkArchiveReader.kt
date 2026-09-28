package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import com.vinstall.alwiz.model.PackageFormat
import com.vinstall.alwiz.parser.XapkManifest
import com.vinstall.alwiz.parser.XapkManifestDeserializer
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

internal object XapkArchiveReader {
    private val gson = GsonBuilder()
        .registerTypeAdapter(XapkManifest::class.java, XapkManifestDeserializer())
        .create()

    fun listSplits(context: Context, uri: Uri): List<String> {
        val splits = mutableListOf<String>()
        val stream = FileUtil.openStream(context, uri) ?: return emptyList()
        val guard = ArchiveExtractionGuard(context.cacheDir)
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    splits += guard.destination(
                        entry.name,
                        ArchiveExtractionGuard.APK_EXTENSIONS,
                    ).name
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return splits
    }

    fun preflight(
        context: Context,
        uri: Uri,
        elevatedModeCheck: () -> Result<XapkObbInstaller.ElevatedMode>,
    ): PackagePreflight = try {
        val stream = FileUtil.openStream(context, uri)
            ?: throw IOException("Cannot open the selected XAPK")
        val guard = ArchiveExtractionGuard(context.cacheDir)
        var manifest: XapkManifest? = null
        var declaredSize = 0L
        var sizeKnown = true
        var apkCount = 0
        val archivedObbNames = linkedSetOf<String>()
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                when {
                    !entry.isDirectory && isManifestEntry(entry.name) -> {
                        val text = ArchiveExtractionGuard.readMetadata(zip)
                            .toString(Charsets.UTF_8)
                            .trimStart('\uFEFF')
                            .trim()
                        manifest = gson.fromJson(text, XapkManifest::class.java)
                    }
                    !entry.isDirectory && isPayload(entry.name) -> {
                        val payload = guard.destination(
                            entry.name,
                            ArchiveExtractionGuard.XAPK_PAYLOAD_EXTENSIONS,
                        )
                        if (payload.name.endsWith(".apk", ignoreCase = true)) apkCount++
                        if (payload.name.endsWith(".obb", ignoreCase = true)) {
                            archivedObbNames += payload.name.lowercase(Locale.ROOT)
                        }
                        if (entry.size < 0) {
                            sizeKnown = false
                        } else {
                            declaredSize = Math.addExact(declaredSize, entry.size)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val parsed = manifest ?: throw IOException("Invalid XAPK: manifest.json not found in archive")
        if (apkCount == 0) throw IOException("Invalid XAPK: no APK payload found")
        validateObbDeclarations(parsed, archivedObbNames)
        val requiresElevated = archivedObbNames.isNotEmpty()
        val elevatedResult = if (requiresElevated) elevatedModeCheck() else Result.success(null)
        val knownSize = declaredSize.takeIf { sizeKnown }
        if (knownSize != null) StorageBudget.requireSpace(context.cacheDir, knownSize)
        PackagePreflight(
            format = PackageFormat.XAPK,
            requiresElevatedObb = requiresElevated,
            elevatedModeAvailable = elevatedResult.isSuccess,
            declaredExtractionSize = knownSize,
            validationFailure = elevatedResult.exceptionOrNull()?.message,
        )
    } catch (error: Exception) {
        PackagePreflight(
            format = PackageFormat.XAPK,
            validationFailure = error.message ?: "Invalid XAPK",
        )
    }

    fun extract(
        context: Context,
        uri: Uri,
        outputDirectory: File,
        onStep: (String) -> Unit,
    ): XapkManifest? {
        val stream = FileUtil.openStream(context, uri)
            ?: throw IOException("Cannot open the selected XAPK")
        val guard = ArchiveExtractionGuard(outputDirectory)
        var manifest: XapkManifest? = null
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                when {
                    !entry.isDirectory && isManifestEntry(entry.name) -> {
                        val text = ArchiveExtractionGuard.readMetadata(zip)
                            .toString(Charsets.UTF_8)
                            .trimStart('\uFEFF')
                            .trim()
                        DebugLog.d("XapkInstaller", "manifest.json found at entry='${entry.name}'")
                        manifest = gson.fromJson(text, XapkManifest::class.java)
                    }
                    !entry.isDirectory && isPayload(entry.name) -> {
                        val output = guard.destination(
                            entry.name,
                            ArchiveExtractionGuard.XAPK_PAYLOAD_EXTENSIONS,
                        )
                        onStep("Extracting ${output.name}...")
                        guard.extract(zip, output)
                        DebugLog.d(
                            "XapkInstaller",
                            "Extracted: ${output.name} (${output.length()} bytes)",
                        )
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return manifest
    }

    private fun validateObbDeclarations(
        manifest: XapkManifest,
        archivedObbNames: Set<String>,
    ) {
        val declaredObbNames = manifest.expansions.orEmpty().map { expansion ->
            val name = File(expansion.file.replace('\\', '/')).name
            if (name.isBlank() || !name.endsWith(".obb", ignoreCase = true) || name.any(Char::isISOControl)) {
                throw IOException("Invalid XAPK expansion filename")
            }
            name.lowercase(Locale.ROOT)
        }
        if (declaredObbNames.toSet().size != declaredObbNames.size) {
            throw IOException("Invalid XAPK: duplicate expansion declaration")
        }
        if (declaredObbNames.toSet() != archivedObbNames) {
            throw IOException("Invalid XAPK: OBB payload does not match its manifest")
        }
    }

    private fun isPayload(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true) || name.endsWith(".obb", ignoreCase = true)

    private fun isManifestEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimStart('/')
        return normalized.equals("manifest.json", ignoreCase = true) ||
            normalized.endsWith("/manifest.json", ignoreCase = true)
    }
}
