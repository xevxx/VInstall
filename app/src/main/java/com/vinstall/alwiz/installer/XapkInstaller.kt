package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import com.vinstall.alwiz.parser.XapkManifest
import com.vinstall.alwiz.util.DebugLog
import java.io.File

object XapkInstaller {
    suspend fun install(
        context: Context,
        uri: Uri,
        onStep: (String) -> Unit,
        selectedSplits: List<String>? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<Unit> {
        return try {
            val packagePreflight = preflight(context, uri)
            if (!packagePreflight.accepted) {
                return Result.failure(
                    Exception(packagePreflight.validationFailure ?: "XAPK preflight failed"),
                )
            }
            onStep("Extracting package...")
            val cacheDirectory = File(context.cacheDir, "xapk_extract").also {
                it.deleteRecursively()
                it.mkdirs()
            }
            val manifest = XapkArchiveReader.extract(context, uri, cacheDirectory, onStep)
                ?: return Result.failure(
                    Exception("Invalid XAPK: manifest.json not found in archive"),
                )
            DebugLog.i(
                "XapkInstaller",
                "Manifest parsed: pkg=${manifest.packageName} " +
                    "splitBundle=${manifest.isSplitApkBundle()} expansions=${manifest.hasExpansions()}",
            )

            val apkFiles = findApkFiles(manifest, cacheDirectory)
            if (apkFiles.isEmpty()) {
                return Result.failure(Exception("No APK files could be extracted from XAPK"))
            }

            val hasObb = manifest.hasExpansions()
            val elevatedMode = if (hasObb) {
                XapkObbInstaller.preflightElevatedMode(context)
                    .getOrElse { return Result.failure(it) }
            } else {
                null
            }
            val obbFiles = if (hasObb) {
                XapkObbInstaller.prepare(context, manifest, apkFiles, cacheDirectory)
                    .getOrElse { return Result.failure(it) }
            } else {
                emptyList()
            }

            onStep(if (apkFiles.size > 1) "Installing split APKs..." else "Installing APK...")
            val installResult = SplitInstaller.installSplits(
                context = context,
                apkFiles = apkFiles,
                selectedSplits = selectedSplits,
                onProgress = { progress ->
                    onProgress?.invoke(if (hasObb) progress * 0.85f else progress)
                },
                allowSessionFallback = !hasObb,
            )
            if (installResult.isFailure) return installResult

            if (hasObb) {
                onStep("Copying expansion data...")
                val copyResult = XapkObbInstaller.copy(elevatedMode!!, obbFiles) { completed, total ->
                    onProgress?.invoke(
                        0.85f + (completed.toFloat() / total.coerceAtLeast(1)) * 0.15f,
                    )
                }
                if (copyResult.isFailure) {
                    return Result.failure(
                        Exception(
                            "App installed, but expansion data could not be copied: " +
                                (copyResult.exceptionOrNull()?.message ?: "unknown error"),
                            copyResult.exceptionOrNull(),
                        ),
                    )
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            DebugLog.e("XapkInstaller", "Install failed: ${error.message}")
            Result.failure(error)
        }
    }

    fun listSplits(context: Context, uri: Uri): List<String> = try {
        XapkArchiveReader.listSplits(context, uri)
    } catch (error: Exception) {
        DebugLog.e("XapkInstaller", "listSplits error: ${error.message}")
        emptyList()
    }

    internal fun preflight(context: Context, uri: Uri): PackagePreflight =
        XapkArchiveReader.preflight(context, uri) {
            XapkObbInstaller.preflightElevatedMode(context)
        }

    private fun findApkFiles(manifest: XapkManifest, cacheDirectory: File): List<File> =
        if (manifest.isSplitApkBundle()) {
            manifest.splitApks.orEmpty().mapNotNull { entry ->
                val file = File(cacheDirectory, File(entry.file).name)
                if (file.exists()) {
                    file
                } else {
                    DebugLog.e("XapkInstaller", "Split APK not found in cache: ${entry.file}")
                    null
                }
            }
        } else {
            cacheDirectory.listFiles { file -> file.name.endsWith(".apk", ignoreCase = true) }
                ?.toList()
                .orEmpty()
        }
}
