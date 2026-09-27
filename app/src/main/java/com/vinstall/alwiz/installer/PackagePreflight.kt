package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import com.vinstall.alwiz.model.PackageFormat
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import java.io.IOException
import java.util.zip.ZipInputStream

internal data class PackagePreflight(
    val format: PackageFormat,
    val requiresElevatedObb: Boolean = false,
    val elevatedModeAvailable: Boolean = true,
    val declaredExtractionSize: Long? = null,
    val validationFailure: String? = null,
) {
    val accepted: Boolean get() = validationFailure == null && (!requiresElevatedObb || elevatedModeAvailable)
}

internal fun validatePackageBatch(preflights: List<PackagePreflight>): Result<Unit> {
    val rejected = preflights.firstOrNull { !it.accepted } ?: return Result.success(Unit)
    return Result.failure(
        IllegalStateException(rejected.validationFailure ?: "Package preflight failed before batch installation.")
    )
}

internal object PackagePreflightInspector {
    fun inspect(
        context: Context,
        uri: Uri,
        format: PackageFormat,
        apkvPassword: String? = null,
    ): PackagePreflight = try {
        when (format) {
            PackageFormat.APK -> inspectApk(context, uri)
            PackageFormat.XAPK -> XapkInstaller.preflight(context, uri)
            PackageFormat.APKM, PackageFormat.APKS, PackageFormat.ZIP -> inspectApkArchive(context, uri, format)
            PackageFormat.APKV -> inspectApkv(context, uri, apkvPassword)
            PackageFormat.UNKNOWN -> PackagePreflight(format, validationFailure = "Unsupported package format")
        }
    } catch (error: Exception) {
        PackagePreflight(format, validationFailure = error.message ?: "Package validation failed")
    }

    private fun inspectApk(context: Context, uri: Uri): PackagePreflight {
        val stream = FileUtil.openStream(context, uri) ?: throw IOException("Cannot open the selected APK")
        stream.use { if (it.read() < 0) throw IOException("The selected APK is empty") }
        val size = FileUtil.getFileSize(context, uri).takeIf { it > 0 }
        if (size != null) StorageBudget.requireSpace(context.cacheDir, size)
        return PackagePreflight(PackageFormat.APK, declaredExtractionSize = size)
    }

    private fun inspectApkArchive(context: Context, uri: Uri, format: PackageFormat): PackagePreflight {
        val stream = FileUtil.openStream(context, uri) ?: throw IOException("Cannot open the selected archive")
        val guard = ArchiveExtractionGuard(context.cacheDir)
        var apkCount = 0
        var declaredSize = 0L
        var sizeKnown = true
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    guard.destination(entry.name, ArchiveExtractionGuard.APK_EXTENSIONS)
                    apkCount++
                    if (entry.size < 0) sizeKnown = false else declaredSize = Math.addExact(declaredSize, entry.size)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (apkCount == 0) throw IOException("Archive contains no APK payload")
        val knownSize = declaredSize.takeIf { sizeKnown }
        if (knownSize != null) StorageBudget.requireSpace(context.cacheDir, knownSize)
        return PackagePreflight(format, declaredExtractionSize = knownSize)
    }

    private fun inspectApkv(context: Context, uri: Uri, password: String?): PackagePreflight {
        val stream = FileUtil.openStream(context, uri) ?: throw IOException("Cannot open the selected APKV")
        val guard = ArchiveExtractionGuard(context.cacheDir)
        var encrypted = false
        var hasEncryptedPayload = false
        var apkCount = 0
        var declaredSize = 0L
        var sizeKnown = true
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                if (!entry.isDirectory) {
                    when (entry.name) {
                        ".apkv_enc" -> encrypted = true
                        "payload.enc" -> hasEncryptedPayload = true
                        "header.json", "manifest.json", "manifest.enc" -> ArchiveExtractionGuard.readMetadata(zip)
                        else -> if (entry.name.endsWith(".apk", ignoreCase = true)) {
                            guard.destination(entry.name, ArchiveExtractionGuard.APK_EXTENSIONS)
                            apkCount++
                            if (entry.size < 0) sizeKnown = false else {
                                declaredSize = Math.addExact(declaredSize, entry.size)
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (encrypted) {
            if (!hasEncryptedPayload) throw IOException("Encrypted APKV payload is missing")
            if (password == null) throw IOException("Password is required for this APKV")
            if (ApkvInstaller.readManifest(context, uri, password) == null) throw IOException("Incorrect APKV password")
            return PackagePreflight(PackageFormat.APKV)
        }
        if (apkCount == 0) throw IOException("APKV contains no APK payload")
        val knownSize = declaredSize.takeIf { sizeKnown }
        if (knownSize != null) StorageBudget.requireSpace(context.cacheDir, knownSize)
        return PackagePreflight(PackageFormat.APKV, declaredExtractionSize = knownSize)
    }
}
