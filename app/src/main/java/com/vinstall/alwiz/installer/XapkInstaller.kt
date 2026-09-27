package com.vinstall.alwiz.installer

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.GsonBuilder
import com.vinstall.alwiz.parser.XapkManifest
import com.vinstall.alwiz.parser.XapkManifestDeserializer
import com.vinstall.alwiz.root.RootHelper
import com.vinstall.alwiz.settings.AppSettings
import com.vinstall.alwiz.settings.InstallMode
import com.vinstall.alwiz.shizuku.ShizukuHelper
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.util.FileUtil
import com.vinstall.alwiz.util.StorageBudget
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

object XapkInstaller {

    private val gson = GsonBuilder()
        .registerTypeAdapter(XapkManifest::class.java, XapkManifestDeserializer())
        .create()

    suspend fun install(
        context: Context,
        uri: Uri,
        onStep: (String) -> Unit,
        selectedSplits: List<String>? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> {
        return try {
            val packagePreflight = preflight(context, uri)
            if (!packagePreflight.accepted) {
                return Result.failure(Exception(packagePreflight.validationFailure ?: "XAPK preflight failed"))
            }
            onStep("Extracting package...")
            val cacheDir = File(context.cacheDir, "xapk_extract").also {
                it.deleteRecursively()
                it.mkdirs()
            }

            val manifest = extractWithZipFile(context, uri, cacheDir, onStep)
                ?: return Result.failure(Exception("Invalid XAPK: manifest.json not found in archive"))

            DebugLog.i("XapkInstaller", "Manifest parsed: pkg=${manifest.packageName} splitBundle=${manifest.isSplitApkBundle()} expansions=${manifest.hasExpansions()}")

            val apkFiles = if (manifest.isSplitApkBundle()) {
                manifest.splitApks!!.mapNotNull { entry ->
                    val f = File(cacheDir, File(entry.file).name)
                    if (f.exists()) f else {
                        DebugLog.e("XapkInstaller", "Split APK not found in cache: ${entry.file}")
                        null
                    }
                }
            } else {
                cacheDir.listFiles { f -> f.name.endsWith(".apk", ignoreCase = true) }?.toList().orEmpty()
            }

            if (apkFiles.isEmpty()) {
                return Result.failure(Exception("No APK files could be extracted from XAPK"))
            }

            val hasObb = manifest.hasExpansions()
            val elevatedMode = if (hasObb) {
                preflightElevatedMode(context).getOrElse { return Result.failure(it) }
            } else null
            val obbFiles = if (hasObb) {
                prepareObbFiles(context, manifest, apkFiles, cacheDir).getOrElse {
                    return Result.failure(it)
                }
            } else emptyList()

            onStep(if (apkFiles.size > 1) "Installing split APKs..." else "Installing APK...")
            val installResult = SplitInstaller.installSplits(
                context = context,
                apkFiles = apkFiles,
                selectedSplits = selectedSplits,
                onProgress = { progress ->
                    onProgress?.invoke(if (hasObb) progress * 0.85f else progress)
                },
                allowSessionFallback = !hasObb
            )
            if (installResult.isFailure) return installResult

            if (hasObb) {
                onStep("Copying expansion data...")
                val copyResult = copyObbFiles(elevatedMode!!, obbFiles) { completed, total ->
                    onProgress?.invoke(0.85f + (completed.toFloat() / total.coerceAtLeast(1)) * 0.15f)
                }
                if (copyResult.isFailure) {
                    return Result.failure(
                        Exception(
                            "App installed, but expansion data could not be copied: " +
                                (copyResult.exceptionOrNull()?.message ?: "unknown error"),
                            copyResult.exceptionOrNull()
                        )
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.e("XapkInstaller", "Install failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun listSplits(context: Context, uri: Uri): List<String> {
        val splits = mutableListOf<String>()
        try {
            val stream = FileUtil.openStream(context, uri) ?: return emptyList()
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
        } catch (e: Exception) {
            DebugLog.e("XapkInstaller", "listSplits error: ${e.message}")
        }
        return splits
    }

    internal fun preflight(context: Context, uri: Uri): PackagePreflight {
        return try {
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
                    if (!entry.isDirectory && isManifestEntry(entry.name)) {
                        val text = ArchiveExtractionGuard.readMetadata(zip)
                            .toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
                        manifest = gson.fromJson(text, XapkManifest::class.java)
                    } else if (!entry.isDirectory && (
                            entry.name.endsWith(".apk", ignoreCase = true) ||
                                entry.name.endsWith(".obb", ignoreCase = true)
                        )
                    ) {
                        val payload = guard.destination(entry.name, ArchiveExtractionGuard.XAPK_PAYLOAD_EXTENSIONS)
                        if (payload.name.endsWith(".apk", ignoreCase = true)) apkCount++
                        if (payload.name.endsWith(".obb", ignoreCase = true)) {
                            archivedObbNames += payload.name.lowercase(Locale.ROOT)
                        }
                        if (entry.size < 0) sizeKnown = false else declaredSize = Math.addExact(declaredSize, entry.size)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val parsed = manifest ?: throw IOException("Invalid XAPK: manifest.json not found in archive")
            if (apkCount == 0) throw IOException("Invalid XAPK: no APK payload found")
            val declaredObbNames = parsed.expansions.orEmpty().map { expansion ->
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
            val requiresElevated = archivedObbNames.isNotEmpty()
            val elevatedResult = if (requiresElevated) preflightElevatedMode(context) else Result.success(null)
            val knownSize = declaredSize.takeIf { sizeKnown }
            if (knownSize != null) StorageBudget.requireSpace(context.cacheDir, knownSize)
            PackagePreflight(
                format = com.vinstall.alwiz.model.PackageFormat.XAPK,
                requiresElevatedObb = requiresElevated,
                elevatedModeAvailable = elevatedResult.isSuccess,
                declaredExtractionSize = knownSize,
                validationFailure = elevatedResult.exceptionOrNull()?.message,
            )
        } catch (error: Exception) {
            PackagePreflight(
                format = com.vinstall.alwiz.model.PackageFormat.XAPK,
                validationFailure = error.message ?: "Invalid XAPK",
            )
        }
    }

    private fun extractWithZipFile(
        context: Context,
        uri: Uri,
        outDir: File,
        onStep: (String) -> Unit
    ): XapkManifest? {
        val stream = FileUtil.openStream(context, uri) ?: throw IOException("Cannot open the selected XAPK")
        val guard = ArchiveExtractionGuard(outDir)
        var manifest: XapkManifest? = null
        ZipInputStream(stream.buffered(FileUtil.BUFFER_SIZE)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                guard.recordEntry(entry.name, entry.isDirectory)
                when {
                    !entry.isDirectory && isManifestEntry(entry.name) -> {
                        val text = ArchiveExtractionGuard.readMetadata(zip)
                            .toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
                        DebugLog.d("XapkInstaller", "manifest.json found at entry='${entry.name}'")
                        manifest = gson.fromJson(text, XapkManifest::class.java)
                    }
                    !entry.isDirectory && (
                        entry.name.endsWith(".apk", ignoreCase = true) ||
                            entry.name.endsWith(".obb", ignoreCase = true)
                    ) -> {
                        val outFile = guard.destination(entry.name, ArchiveExtractionGuard.XAPK_PAYLOAD_EXTENSIONS)
                        onStep("Extracting ${outFile.name}...")
                        guard.extract(zip, outFile)
                        DebugLog.d("XapkInstaller", "Extracted: ${outFile.name} (${outFile.length()} bytes)")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return manifest
    }

    private fun isManifestEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimStart('/')
        return normalized.equals("manifest.json", ignoreCase = true)
            || normalized.endsWith("/manifest.json", ignoreCase = true)
    }

    internal enum class ElevatedMode { ROOT, SHIZUKU }

    private data class ObbCopy(val source: File, val destination: File)

    internal fun preflightElevatedMode(context: Context): Result<ElevatedMode> {
        val mode = AppSettings.getInstallMode(context)
        return when (mode) {
            InstallMode.NORMAL -> selectElevatedMode(mode, false, false, false, false)
            InstallMode.ROOT -> selectElevatedMode(mode, RootHelper.isRooted(), false, false, false)
            InstallMode.SHIZUKU -> {
                val available = ShizukuHelper.isAvailable()
                val granted = available && ShizukuHelper.isGranted()
                selectElevatedMode(
                    mode,
                    rootAvailable = false,
                    shizukuAvailable = available,
                    shizukuGranted = granted,
                    shizukuProcessAvailable = granted && ShizukuHelper.isNewProcessAvailable(),
                )
            }
        }
    }

    internal fun selectElevatedMode(
        mode: InstallMode,
        rootAvailable: Boolean,
        shizukuAvailable: Boolean,
        shizukuGranted: Boolean,
        shizukuProcessAvailable: Boolean,
    ): Result<ElevatedMode> = when (mode) {
        InstallMode.NORMAL -> Result.failure(
            Exception("XAPK expansion files require Root or active Shizuku installation mode.")
        )
        InstallMode.ROOT -> if (rootAvailable) {
            Result.success(ElevatedMode.ROOT)
        } else {
            Result.failure(Exception("Root installation is selected, but root access is not available."))
        }
        InstallMode.SHIZUKU -> if (
            shizukuAvailable && shizukuGranted && shizukuProcessAvailable
        ) {
            Result.success(ElevatedMode.SHIZUKU)
        } else {
            Result.failure(Exception("Shizuku must be running and permission must be granted to install XAPK expansion files."))
        }
    }

    private fun prepareObbFiles(
        context: Context,
        manifest: XapkManifest,
        apkFiles: List<File>,
        cacheDir: File
    ): Result<List<ObbCopy>> = runCatching {
        val declaredBaseName = manifest.splitApks
            ?.firstOrNull { it.id.equals("base", ignoreCase = true) }
            ?.file
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
        val baseApk = declaredBaseName?.let { declared ->
            apkFiles.firstOrNull { it.name.equals(declared, ignoreCase = true) }
        } ?: apkFiles.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: apkFiles.first()
        @Suppress("DEPRECATION")
        val parsedPackage = context.packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)?.packageName
            ?: throw IllegalArgumentException("Cannot verify the package name in ${baseApk.name}.")

        if (!PACKAGE_NAME.matches(parsedPackage)) {
            throw IllegalArgumentException("The APK contains an invalid package name.")
        }
        if (manifest.packageName.isNotBlank() && manifest.packageName != parsedPackage) {
            throw IllegalArgumentException("XAPK manifest package does not match the APK package.")
        }

        manifest.expansions.orEmpty().map { expansion ->
            val destination = validatedObbDestination(
                Environment.getExternalStorageDirectory(),
                parsedPackage,
                expansion.file,
            )
            val fileName = destination.name
            val source = File(cacheDir, fileName)
            if (!source.isFile) {
                throw IllegalArgumentException("Expansion file is missing: $fileName")
            }
            ObbCopy(source, destination)
        }.also {
            if (it.isEmpty()) throw IllegalArgumentException("XAPK declares expansion data but contains no OBB files.")
        }
    }

    private fun copyObbFiles(
        mode: ElevatedMode,
        files: List<ObbCopy>,
        onProgress: (completed: Int, total: Int) -> Unit
    ): Result<Unit> = runCatching {
        files.forEachIndexed { index, copy ->
            val result = when (mode) {
                ElevatedMode.ROOT -> copyViaRoot(copy)
                ElevatedMode.SHIZUKU -> copyViaShizuku(copy)
            }
            if (result.isFailure) throw result.exceptionOrNull()!!
            DebugLog.d("XapkInstaller", "OBB copied to ${copy.destination.absolutePath}")
            onProgress(index + 1, files.size)
        }
    }

    private fun copyViaRoot(copy: ObbCopy): Result<Unit> = runCatching {
        val parent = shellQuote(copy.destination.parentFile!!.absolutePath)
        val destination = shellQuote(copy.destination.absolutePath)
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "mkdir -p $parent && cat > $destination")
        )
        streamFileAndCheck(copy.source, process, "Root OBB copy")
    }

    private fun copyViaShizuku(copy: ObbCopy): Result<Unit> = runCatching {
        val parent = shellQuote(copy.destination.parentFile!!.absolutePath)
        val destination = shellQuote(copy.destination.absolutePath)
        val process = newShizukuProcess(
            "sh", "-c", "mkdir -p $parent && cat > $destination"
        )
        streamFileAndCheck(copy.source, process, "Shizuku OBB copy")
    }

    private fun newShizukuProcess(vararg command: String): Process {
        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun streamFileAndCheck(source: File, process: Process, label: String) {
        var stderr = ""
        val errorReader = Thread {
            stderr = try {
                process.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }.also { it.start() }

        try {
            process.outputStream.use { output ->
                source.inputStream().buffered(FileUtil.BUFFER_SIZE).use { input ->
                    input.copyTo(output, FileUtil.BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            process.destroy()
            throw e
        }

        val stdout = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
        val exitCode = process.waitFor()
        errorReader.join()
        process.destroy()
        if (exitCode != 0) {
            val detail = (stderr.ifBlank { stdout }).trim().ifBlank { "exit code $exitCode" }
            throw IllegalStateException("$label failed: $detail")
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal fun validatedObbDestination(storageRoot: File, packageName: String, archivePath: String): File {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name." }
        val fileName = File(archivePath.replace('\\', '/')).name
        require(fileName.isNotBlank() && fileName.endsWith(".obb", ignoreCase = true)) {
            "Invalid expansion filename: $archivePath"
        }
        val packageDir = File(storageRoot, "Android/obb/$packageName").canonicalFile
        val destination = File(packageDir, fileName).canonicalFile
        require(destination.parentFile == packageDir) {
            "Expansion destination is outside the app OBB directory."
        }
        return destination
    }

    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
}
