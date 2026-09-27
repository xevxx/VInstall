package com.vinstall.alwiz.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.vinstall.alwiz.receiver.InstallResultReceiver
import com.vinstall.alwiz.settings.AppSettings
import com.vinstall.alwiz.settings.InstallMode
import com.vinstall.alwiz.shizuku.ShizukuHelper
import com.vinstall.alwiz.util.DebugLog
import com.vinstall.alwiz.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SplitInstaller {
    internal var rootProcessRunner: RootProcessRunner = RuntimeRootProcessRunner

    private fun validateBaseApk(context: Context, apkFiles: List<File>): String? {
        val base = apkFiles.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: apkFiles.first()
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageArchiveInfo(base.absolutePath, 0)
            ?: return "Invalid or corrupt package: cannot parse ${base.name}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val minSdk = pkgInfo.applicationInfo?.minSdkVersion ?: 0
            if (minSdk > Build.VERSION.SDK_INT) {
                return "Incompatible package: requires API level $minSdk, device is ${Build.VERSION.SDK_INT}"
            }
        }
        return null
    }

    suspend fun installSplits(
        context: Context,
        apkFiles: List<File>,
        selectedSplits: List<String>? = null,
        onProgress: ((Float) -> Unit)? = null,
        allowSessionFallback: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val filesToInstall = if (selectedSplits != null) {
            apkFiles.filter { f ->
                selectedSplits.any { s -> f.name.equals(s, ignoreCase = true) }
                    || f.name.equals("base.apk", ignoreCase = true)
            }
        } else {
            apkFiles
        }

        if (filesToInstall.isEmpty()) {
            return@withContext Result.failure(Exception("No APK files to install"))
        }

        val orderedFiles = orderApkFiles(context, filesToInstall)
        val mode = AppSettings.getInstallMode(context)
        DebugLog.i("SplitInstaller", "Install mode: $mode, files: ${orderedFiles.map { it.name }}")

        return@withContext when (mode) {
            InstallMode.ROOT -> {
                if (RootHelper.isRooted()) {
                    installViaRoot(orderedFiles, onProgress)
                } else if (!allowSessionFallback) {
                    Result.failure(Exception("Root installation is selected, but root access is not available."))
                } else {
                    DebugLog.e("SplitInstaller", "ROOT mode selected but device is not rooted, falling back to session")
                    installViaSession(context, orderedFiles, onProgress)
                }
            }
            InstallMode.SHIZUKU -> {
                val liveGranted = ShizukuHelper.isAvailable() && ShizukuHelper.isGranted()
                val storedGranted = ShizukuHelper.isAvailable() && AppSettings.isShizukuPermissionGranted(context)
                val canUseShizuku = liveGranted || (allowSessionFallback && storedGranted)

                if (canUseShizuku) {
                    if (ShizukuHelper.isNewProcessAvailable()) {
                        val result = installViaShizuku(context, orderedFiles, onProgress)
                        if (result.isFailure && allowSessionFallback) {
                            AppSettings.setShizukuPermissionGranted(context, false)
                            DebugLog.e("SplitInstaller", "Shizuku install failed, falling back to session")
                            installViaSession(context, orderedFiles, onProgress)
                        } else {
                            result
                        }
                    } else if (allowSessionFallback) {
                        DebugLog.e("SplitInstaller", "Shizuku newProcess not available, falling back to session")
                        installViaSession(context, orderedFiles, onProgress)
                    } else Result.failure(Exception("Shizuku installation is selected, but elevated process access is unavailable."))
                } else if (allowSessionFallback) {
                    DebugLog.e("SplitInstaller", "SHIZUKU mode selected but Shizuku is not active/permitted, falling back to session")
                    installViaSession(context, orderedFiles, onProgress)
                } else {
                    Result.failure(Exception("Shizuku installation is selected, but Shizuku is not active or permitted."))
                }
            }
            InstallMode.NORMAL -> if (allowSessionFallback) {
                installViaSession(context, orderedFiles, onProgress)
            } else {
                Result.failure(Exception("This package requires Root or active Shizuku installation mode."))
            }
        }
    }

    private fun orderApkFiles(context: Context, files: List<File>): List<File> = files.sortedBy { file ->
        when {
            file.name.equals("base.apk", ignoreCase = true) -> 0
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    ?.let { it.splitNames.isNullOrEmpty() } ?: false
            }.getOrDefault(false) -> 1
            else -> 2
        }
    }

    private suspend fun installViaSession(
        context: Context,
        apkFiles: List<File>,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            DebugLog.d("SplitInstaller", "installViaSession: ${apkFiles.size} file(s)")
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            try {
                val totalBytes = apkFiles.sumOf { it.length() }.coerceAtLeast(1L)
                var writtenBytes = 0L
                val buffer = ByteArray(65536)

                val validationError = validateBaseApk(context, apkFiles)
                if (validationError != null) {
                    session.abandon()
                    return@withContext Result.failure(Exception(validationError))
                }

                for (apk in apkFiles) {
                    DebugLog.d("SplitInstaller", "Writing ${apk.name} (${apk.length()} bytes)")
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { input ->
                            var n: Int
                            while (input.read(buffer).also { n = it } >= 0) {
                                out.write(buffer, 0, n)
                                writtenBytes += n
                                val progress = (writtenBytes.toFloat() / totalBytes) * 0.9f
                                session.setStagingProgress(progress)
                                onProgress?.invoke(progress)
                            }
                        }
                        session.fsync(out)
                    }
                }

                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = "com.vinstall.alwiz.INSTALL_STATUS"
                }
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                val sessionCallback = object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sid: Int) {}
                    override fun onBadgingChanged(sid: Int) {}
                    override fun onActiveChanged(sid: Int, active: Boolean) {}
                    override fun onProgressChanged(sid: Int, progress: Float) {
                        if (sid == sessionId) onProgress?.invoke(0.9f + progress * 0.1f)
                    }
                    override fun onFinished(sid: Int, success: Boolean) {
                        if (sid == sessionId) {
                            installer.unregisterSessionCallback(this)
                        }
                    }
                }
                installer.registerSessionCallback(sessionCallback, Handler(Looper.getMainLooper()))

                session.commit(pi.intentSender)
                session.close()
                DebugLog.d("SplitInstaller", "Session committed, id=$sessionId")
                Result.success(Unit)
            } catch (e: Exception) {
                session.abandon()
                DebugLog.e("SplitInstaller", "Session error: ${e.message}")
                Result.failure(e)
            }
        }

    private fun shizukuExec(vararg cmd: String): String {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, cmd, null, null) as rikka.shizuku.ShizukuRemoteProcess
        var stderr = ""
        val stderrThread = Thread {
            stderr = try { process.errorStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        }
        stderrThread.start()
        val stdout = try { process.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        stderrThread.join()
        process.waitFor()
        process.destroy()
        return (stdout + stderr).trim()
    }

    private fun shizukuExecWithInput(inputFile: File, vararg cmd: String): String {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, cmd, null, null) as rikka.shizuku.ShizukuRemoteProcess

        val writeThread = Thread {
            try {
                process.outputStream.use { os ->
                    inputFile.inputStream().use { input -> input.copyTo(os) }
                }
            } catch (_: Exception) {}
        }
        writeThread.start()

        var stderr = ""
        val stderrThread = Thread {
            stderr = try { process.errorStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        }
        stderrThread.start()

        val stdout = try { process.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        stderrThread.join()
        writeThread.join()
        process.waitFor()
        process.destroy()
        return (stdout + stderr).trim()
    }

    private suspend fun installViaShizuku(
        context: Context,
        apkFiles: List<File>,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val totalSize = apkFiles.sumOf { it.length() }
                DebugLog.d("SplitInstaller", "installViaShizuku totalSize=$totalSize")

                val createOutput = shizukuExec("pm", "install-create", "-S", "$totalSize")
                DebugLog.d("SplitInstaller", "Shizuku create: $createOutput")

                val sessionId = Regex("\\[(\\d+)]").find(createOutput)?.groupValues?.get(1)
                    ?: return@withContext Result.failure(Exception("Shizuku: failed to create install session: $createOutput"))

                apkFiles.forEachIndexed { index, apk ->
                    DebugLog.d("SplitInstaller", "Shizuku write: ${apk.name}")
                    val writeOutput = shizukuExecWithInput(
                        apk,
                        "pm", "install-write", "-S", "${apk.length()}", sessionId, apk.name
                    )
                    DebugLog.d("SplitInstaller", "Shizuku write result: $writeOutput")
                    if (!writeOutput.contains("Success", ignoreCase = true)) {
                        shizukuExec("pm", "install-abandon", sessionId)
                        return@withContext Result.failure(
                            Exception("Failed to write ${apk.name}: $writeOutput")
                        )
                    }
                    onProgress?.invoke(((index + 1).toFloat() / apkFiles.size) * 0.9f)
                }

                onProgress?.invoke(0.95f)
                val commitOutput = shizukuExec("pm", "install-commit", sessionId)
                DebugLog.d("SplitInstaller", "Shizuku commit: $commitOutput")

                if (commitOutput.contains("Success", ignoreCase = true)) {
                    com.vinstall.alwiz.installer.InstallHelper.emit(com.vinstall.alwiz.installer.InstallHelper.Result.Success)
                    Result.success(Unit)
                } else {
                    val friendlyMessage = parseInstallError(commitOutput)
                    Result.failure(Exception(friendlyMessage))
                }
            } catch (e: Exception) {
                DebugLog.e("SplitInstaller", "Shizuku exception: ${e.message}")
                Result.failure(e)
            }
        }

    internal suspend fun installViaRoot(
        apkFiles: List<File>,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            var activeSessionId: String? = null
            try {
                val totalSize = apkFiles.sumOf { it.length() }
                DebugLog.d("SplitInstaller", "installViaRoot totalSize=$totalSize")

                val createResult = rootProcessRunner.run(rootCreateCommand(totalSize), null)
                val createOutput = createResult.output

                val sessionId = if (createResult.succeeded) {
                    Regex("\\[(\\d+)]").find(createOutput)?.groupValues?.get(1)
                } else {
                    null
                }
                    ?: return@withContext Result.failure(
                        Exception("Root: failed to create install session (${createResult.exitCode}): $createOutput")
                    )
                activeSessionId = sessionId

                apkFiles.forEachIndexed { index, apk ->
                    val splitName = if (index == 0) "base.apk" else "split_${index.toString().padStart(3, '0')}.apk"
                    val writeResult = rootProcessRunner.run(
                        rootWriteCommand(apk.length(), sessionId, splitName),
                        apk
                    )
                    if (!writeResult.succeeded) {
                        rootProcessRunner.run(rootAbandonCommand(sessionId), null)
                        return@withContext Result.failure(
                            Exception("Root: failed to write $splitName (${writeResult.exitCode}): ${writeResult.output}")
                        )
                    }
                    onProgress?.invoke(((index + 1).toFloat() / apkFiles.size) * 0.9f)
                }

                onProgress?.invoke(0.95f)
                val commitResult = rootProcessRunner.run(rootCommitCommand(sessionId), null)
                val commitOutput = commitResult.output

                DebugLog.d("SplitInstaller", "Root commit: $commitOutput")

                if (commitResult.succeeded) {
                    com.vinstall.alwiz.installer.InstallHelper.emit(com.vinstall.alwiz.installer.InstallHelper.Result.Success)
                    activeSessionId = null
                    Result.success(Unit)
                } else {
                    rootProcessRunner.run(rootAbandonCommand(sessionId), null)
                    activeSessionId = null
                    val friendlyMessage = parseInstallError(commitOutput)
                    Result.failure(Exception(friendlyMessage))
                }
            } catch (e: Exception) {
                activeSessionId?.let { session -> runCatching { rootProcessRunner.run(rootAbandonCommand(session), null) } }
                DebugLog.e("SplitInstaller", "Root exception: ${e.message}")
                Result.failure(e)
            }
        }

    internal fun rootCreateCommand(totalSize: Long): String {
        require(totalSize >= 0)
        return "pm install-create -S $totalSize"
    }

    internal fun rootWriteCommand(size: Long, sessionId: String, splitName: String): String {
        require(size >= 0)
        require(sessionId.all(Char::isDigit))
        require(splitName.matches(Regex("(?:base|split_[0-9]{3})\\.apk")))
        return "pm install-write -S $size $sessionId $splitName -"
    }

    internal fun rootCommitCommand(sessionId: String): String {
        require(sessionId.all(Char::isDigit))
        return "pm install-commit $sessionId"
    }

    internal fun rootAbandonCommand(sessionId: String): String {
        require(sessionId.all(Char::isDigit))
        return "pm install-abandon $sessionId"
    }

    private fun parseInstallError(output: String): String {
        return when {
            output.contains("INSTALL_FAILED_ALREADY_EXISTS") -> "App already installed with a different signature. Uninstall the existing version first."
            output.contains("INSTALL_FAILED_INVALID_APK") -> "Invalid APK: the package file is malformed or could not be written to the install session."
            output.contains("INSTALL_FAILED_CONFLICTING_PROVIDER") -> "Conflict: another app uses the same content provider authority."
            output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> "Not enough storage space to install this package."
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> "Cannot install an older version over a newer one."
            output.contains("INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE") -> "Permission model downgrade is not allowed."
            output.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES") -> "APK is not signed. A valid signature is required."
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") -> "Signature mismatch: uninstall the existing app first."
            output.contains("INSTALL_FAILED_DEXOPT") -> "APK could not be optimized (dexopt). The ABI may be incompatible with this device."
            output.contains("INSTALL_FAILED_CPU_ABI_INCOMPATIBLE") -> "Incompatible APK: this package does not support your device's CPU architecture."
            output.contains("INSTALL_FAILED_NO_MATCHING_ABIS") -> "Incompatible APK: no native libraries match your device's ABI."
            output.contains("INSTALL_FAILED_TEST_ONLY") -> "This is a test-only APK and cannot be installed normally."
            output.contains("INSTALL_FAILED_ABORTED") -> "Installation was cancelled."
            else -> "Install failed: $output"
        }
    }
}
