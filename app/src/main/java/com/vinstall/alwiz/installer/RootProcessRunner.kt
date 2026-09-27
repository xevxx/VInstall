package com.vinstall.alwiz.installer

import com.vinstall.alwiz.util.FileUtil
import java.io.File

internal data class RootProcessResult(val exitCode: Int, val output: String) {
    val succeeded: Boolean
        get() = exitCode == 0 && output.contains("Success", ignoreCase = true)
}
internal fun interface RootProcessRunner {
    fun run(command: String, input: File?): RootProcessResult
}

internal object RuntimeRootProcessRunner : RootProcessRunner {
    override fun run(command: String, input: File?): RootProcessResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        var stderr = ""
        val stderrThread = Thread {
            stderr = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        stderrThread.start()

        var writeFailure: Throwable? = null
        val writerThread = Thread {
            try {
                process.outputStream.use { output ->
                    input?.inputStream()?.use { source -> source.copyTo(output, FileUtil.BUFFER_SIZE) }
                }
            } catch (error: Throwable) {
                writeFailure = error
            }
        }
        writerThread.start()

        val stdout = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        writerThread.join()
        stderrThread.join()
        val exitCode = process.waitFor()
        process.destroy()
        writeFailure?.let { throw it }
        return RootProcessResult(exitCode, (stdout + stderr).trim())
    }
}
