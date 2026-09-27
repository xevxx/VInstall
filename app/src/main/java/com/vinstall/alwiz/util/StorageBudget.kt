package com.vinstall.alwiz.util

import java.io.File
import java.io.IOException
import java.io.OutputStream

object StorageBudget {
    const val RESERVED_FREE_BYTES: Long = 256L * 1024L * 1024L

    internal fun hasSpace(usableBytes: Long, bytesToWrite: Long): Boolean =
        bytesToWrite >= 0L && usableBytes - bytesToWrite >= RESERVED_FREE_BYTES

    @Throws(IOException::class)
    fun requireSpace(directory: File, bytesToWrite: Long) {
        if (!hasSpace(directory.usableSpace, bytesToWrite)) {
            throw IOException("Not enough storage space; VInstall keeps 256 MiB free for the system.")
        }
    }

    fun guarding(directory: File, output: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            requireSpace(directory, 1)
            output.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireSpace(directory, length.toLong())
            output.write(buffer, offset, length)
        }

        override fun flush() = output.flush()
        override fun close() = output.close()
    }
}
