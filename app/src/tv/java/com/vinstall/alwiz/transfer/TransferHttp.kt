package com.vinstall.alwiz.transfer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val TRANSFER_BUFFER_SIZE = 64 * 1024
internal const val TRANSFER_HEADER_TIMEOUT_MS = 15_000
internal const val TRANSFER_UPLOAD_TIMEOUT_MS = 120_000
private const val MAX_LINE_LENGTH = 8 * 1024
private const val MAX_HEADER_BYTES = 32 * 1024

internal data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
) {
    val contentType: String get() = headers["content-type"].orEmpty()

    fun contentLength(limit: Long = Long.MAX_VALUE): Long {
        val length = headers["content-length"]?.toLongOrNull()
            ?: throw HttpException(411, "Length Required", "Content-Length is required")
        if (length < 0 || length > limit) {
            throw HttpException(413, "Content Too Large", "Request is too large")
        }
        return length
    }
}

internal class HttpException(
    val status: Int,
    val reason: String,
    override val message: String,
) : IOException(message)

internal fun readHttpRequest(input: InputStream): HttpRequest {
    val requestLine = readHttpLine(input).split(' ')
    if (requestLine.size != 3 || requestLine[2] != "HTTP/1.1") {
        throw HttpException(400, "Bad Request", "Malformed request")
    }
    val headers = readHttpHeaders(
        input,
        limitMessage = "Headers are too large",
        malformedMessage = "Malformed header",
    )
    val rawPath = requestLine[1].substringBefore('?')
    return HttpRequest(
        method = requestLine[0].uppercase(Locale.ROOT),
        path = URLDecoder.decode(rawPath, "UTF-8"),
        headers = headers,
    )
}

internal fun readHttpHeaders(
    input: InputStream,
    limitMessage: String,
    malformedMessage: String,
): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    var total = 0
    while (true) {
        val line = readHttpLine(input)
        total += line.length
        if (total > MAX_HEADER_BYTES) {
            throw HttpException(431, "Request Header Fields Too Large", limitMessage)
        }
        if (line.isEmpty()) return headers
        val separator = line.indexOf(':')
        if (separator <= 0) throw HttpException(400, "Bad Request", malformedMessage)
        headers[line.substring(0, separator).lowercase(Locale.ROOT)] =
            line.substring(separator + 1).trim()
    }
}

internal fun readHttpLine(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() <= MAX_LINE_LENGTH) {
        val value = input.read()
        if (value < 0) throw HttpException(400, "Bad Request", "Unexpected end of request")
        if (value == '\r'.code) {
            if (input.read() != '\n'.code) {
                throw HttpException(400, "Bad Request", "Malformed line ending")
            }
            return bytes.toString("UTF-8")
        }
        bytes.write(value)
    }
    throw HttpException(414, "URI Too Long", "Line is too long")
}

internal fun readExactly(input: InputStream, length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < result.size) {
        val read = input.read(result, offset, result.size - offset)
        if (read < 0) throw HttpException(400, "Bad Request", "Incomplete request")
        offset += read
    }
    return result
}

internal fun respond(
    output: OutputStream,
    status: Int,
    reason: String,
    contentType: String,
    body: ByteArray,
    extraHeaders: Map<String, String> = emptyMap(),
) {
    writeHttpHeaders(output, status, reason, contentType, body.size.toLong(), extraHeaders)
    output.write(body)
}

internal fun writeHttpHeaders(
    output: OutputStream,
    status: Int,
    reason: String,
    contentType: String,
    length: Long,
    extraHeaders: Map<String, String>,
) {
    val headers = buildString {
        append("HTTP/1.1 $status $reason\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: $length\r\n")
        append("Connection: close\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("Referrer-Policy: no-referrer\r\n")
        extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
        append("\r\n")
    }
    output.write(headers.toByteArray(StandardCharsets.US_ASCII))
}

internal fun parseMultipartBoundary(contentType: String): String {
    if (!contentType.startsWith("multipart/form-data", true)) {
        throw HttpException(415, "Unsupported Media Type", "Expected multipart upload")
    }
    val value = contentType.split(';')
        .map(String::trim)
        .firstOrNull { it.startsWith("boundary=", true) }
        ?.substringAfter('=')
        ?.trim('"')
        .orEmpty()
    if (value.isBlank() || value.length > 200 || value.any { it == '\r' || it == '\n' }) {
        throw HttpException(400, "Bad Request", "Invalid multipart boundary")
    }
    return value
}

internal fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { field ->
    val parts = field.split('=', limit = 2)
    if (parts.isEmpty()) {
        null
    } else {
        URLDecoder.decode(parts[0], "UTF-8") to
            URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
}.toMap()

internal fun html(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

internal fun httpFileName(value: String): String = value.replace(Regex("[\"\\r\\n]"), "_")

internal fun jsonError(value: String): String =
    "{\"error\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

internal fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = delegate.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }
}
