package com.vinstall.alwiz.installer

import android.content.Context
import android.os.Environment
import com.vinstall.alwiz.parser.XapkManifest
import com.vinstall.alwiz.root.RootHelper
import com.vinstall.alwiz.settings.AppSettings
import com.vinstall.alwiz.settings.InstallMode
import com.vinstall.alwiz.shizuku.ShizukuHelper
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.util.FileUtil
import java.io.File

internal object XapkObbInstaller {
    enum class ElevatedMode { ROOT, SHIZUKU }

    data class ObbCopy(val source: File, val destination: File)

    fun preflightElevatedMode(context: Context): Result<ElevatedMode> {
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

    fun selectElevatedMode(
        mode: InstallMode,
        rootAvailable: Boolean,
        shizukuAvailable: Boolean,
        shizukuGranted: Boolean,
        shizukuProcessAvailable: Boolean,
    ): Result<ElevatedMode> = when (mode) {
        InstallMode.NORMAL -> Result.failure(
            Exception("XAPK expansion files require Root or active Shizuku installation mode."),
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
            Result.failure(
                Exception(
                    "Shizuku must be running and permission must be granted to install " +
                        "XAPK expansion files.",
                ),
            )
        }
    }

    fun prepare(
        context: Context,
        manifest: XapkManifest,
        apkFiles: List<File>,
        cacheDirectory: File,
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
        val parsedPackage = context.packageManager.getPackageArchiveInfo(baseApk.absolutePath, 0)
            ?.packageName
            ?: throw IllegalArgumentException("Cannot verify the package name in ${baseApk.name}.")

        if (!PACKAGE_NAME.matches(parsedPackage)) {
            throw IllegalArgumentException("The APK contains an invalid package name.")
        }
        if (manifest.packageName.isNotBlank() && manifest.packageName != parsedPackage) {
            throw IllegalArgumentException("XAPK manifest package does not match the APK package.")
        }

        manifest.expansions.orEmpty().map { expansion ->
            val destination = validatedDestination(
                Environment.getExternalStorageDirectory(),
                parsedPackage,
                expansion.file,
            )
            val source = File(cacheDirectory, destination.name)
            if (!source.isFile) {
                throw IllegalArgumentException("Expansion file is missing: ${destination.name}")
            }
            ObbCopy(source, destination)
        }.also { copies ->
            if (copies.isEmpty()) {
                throw IllegalArgumentException(
                    "XAPK declares expansion data but contains no OBB files.",
                )
            }
        }
    }

    fun copy(
        mode: ElevatedMode,
        files: List<ObbCopy>,
        onProgress: (completed: Int, total: Int) -> Unit,
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

    fun validatedDestination(storageRoot: File, packageName: String, archivePath: String): File {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name." }
        val fileName = File(archivePath.replace('\\', '/')).name
        require(fileName.isNotBlank() && fileName.endsWith(".obb", ignoreCase = true)) {
            "Invalid expansion filename: $archivePath"
        }
        val packageDirectory = File(storageRoot, "Android/obb/$packageName").canonicalFile
        val destination = File(packageDirectory, fileName).canonicalFile
        require(destination.parentFile == packageDirectory) {
            "Expansion destination is outside the app OBB directory."
        }
        return destination
    }

    private fun copyViaRoot(copy: ObbCopy): Result<Unit> = runCatching {
        val parent = shellQuote(copy.destination.parentFile!!.absolutePath)
        val destination = shellQuote(copy.destination.absolutePath)
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "mkdir -p $parent && cat > $destination"),
        )
        streamFileAndCheck(copy.source, process, "Root OBB copy")
    }

    private fun copyViaShizuku(copy: ObbCopy): Result<Unit> = runCatching {
        val parent = shellQuote(copy.destination.parentFile!!.absolutePath)
        val destination = shellQuote(copy.destination.absolutePath)
        val process = newShizukuProcess("sh", "-c", "mkdir -p $parent && cat > $destination")
        streamFileAndCheck(copy.source, process, "Shizuku OBB copy")
    }

    private fun newShizukuProcess(vararg command: String): Process {
        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
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
        } catch (error: Exception) {
            process.destroy()
            throw error
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

    private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
}
