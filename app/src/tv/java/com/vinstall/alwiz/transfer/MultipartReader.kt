package com.vinstall.alwiz.transfer

import java.io.InputStream
import java.nio.charset.StandardCharsets

internal object MultipartReader {
    private const val MAX_NON_FILE_PART = 1024 * 1024

    fun readFiles(
        input: InputStream,
        boundary: String,
        onFile: (String, InputStream) -> Unit,
    ) {
        val firstBoundary = "--$boundary"
        if (readHttpLine(input) != firstBoundary) {
            throw HttpException(400, "Bad Request", "Malformed multipart body")
        }
        var finished = false
        while (!finished) {
            val headers = readHttpHeaders(
                input,
                limitMessage = "Multipart headers are too large",
                malformedMessage = "Malformed multipart header",
            )
            val disposition = headers["content-disposition"].orEmpty()
            val filename = Regex("filename=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(disposition)
                ?.groupValues
                ?.get(1)
            val part = MultipartPartInputStream(
                input,
                "\r\n--$boundary".toByteArray(StandardCharsets.US_ASCII),
            )
            if (!filename.isNullOrBlank()) {
                try {
                    onFile(filename, part)
                } finally {
                    while (part.read() >= 0) Unit
                }
            } else {
                discardPart(part)
            }
            finished = part.isFinalBoundary
        }
    }

    private fun discardPart(part: InputStream) {
        var discarded = 0
        while (part.read() >= 0) {
            discarded++
            if (discarded > MAX_NON_FILE_PART) {
                throw HttpException(413, "Content Too Large", "Form field is too large")
            }
        }
    }
}

/** Streams bytes up to a multipart delimiter without retaining the file in memory. */
private class MultipartPartInputStream(
    private val source: InputStream,
    private val delimiter: ByteArray,
) : InputStream() {
    private val pending = ArrayDeque<Byte>()
    private val ready = ArrayDeque<Byte>()
    private var ended = false
    var isFinalBoundary: Boolean = false
        private set

    override fun read(): Int {
        fillReady()
        return if (ready.isEmpty()) -1 else ready.removeFirst().toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            val value = read()
            if (value < 0) break
            buffer[offset + count] = value.toByte()
            count++
        }
        return if (count == 0) -1 else count
    }

    private fun fillReady() {
        while (ready.isEmpty() && !ended) {
            val value = source.read()
            if (value < 0) throw HttpException(400, "Bad Request", "Incomplete multipart body")
            pending.addLast(value.toByte())
            if (pending.size < delimiter.size) continue
            if (pending.matches(delimiter)) {
                pending.clear()
                finishBoundary()
                ended = true
            } else {
                ready.addLast(pending.removeFirst())
            }
        }
    }

    private fun finishBoundary() {
        val first = source.read()
        val second = source.read()
        when {
            first == '-'.code && second == '-'.code -> {
                isFinalBoundary = true
                consumeOptionalCrLf(source)
            }
            first == '\r'.code && second == '\n'.code -> isFinalBoundary = false
            else -> throw HttpException(400, "Bad Request", "Malformed multipart boundary")
        }
    }

    private fun ArrayDeque<Byte>.matches(bytes: ByteArray): Boolean {
        if (size != bytes.size) return false
        var index = 0
        for (value in this) if (value != bytes[index++]) return false
        return true
    }

    private fun consumeOptionalCrLf(input: InputStream) {
        if (input.markSupported()) {
            input.mark(2)
            if (input.read() != '\r'.code || input.read() != '\n'.code) input.reset()
        }
    }
}
