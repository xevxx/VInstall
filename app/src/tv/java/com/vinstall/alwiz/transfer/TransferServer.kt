package com.vinstall.alwiz.transfer

import android.content.Context
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface TransferSessionState {
    data object Stopped : TransferSessionState
    data class AwaitingPairing(val url: String, val code: String) : TransferSessionState
    data class Paired(val url: String) : TransferSessionState
    data class Uploading(val fileName: String, val receivedBytes: Long, val totalBytes: Long) : TransferSessionState
    data class Completed(val packages: List<IncomingPackageEntry>) : TransferSessionState
    data class Error(val message: String) : TransferSessionState
}

/** A lifecycle-bound, local-network-only HTTP server for TV package transfer. */
class TransferServer(
    private val context: Context,
    private val incomingRepository: IncomingPackageRepository = IncomingPackageRepository(context),
    private val exportRepository: ExportRepository = ExportRepository(context),
) {
    private val random = SecureRandom()
    private val running = AtomicBoolean(false)
    private val clients = Executors.newCachedThreadPool()
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    private val failedAttempts = ConcurrentHashMap<String, PairingAttemptWindow>()
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val stateMutable = MutableStateFlow<TransferSessionState>(TransferSessionState.Stopped)
    val state: StateFlow<TransferSessionState> = stateMutable.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pairingCode = ""
    @Volatile private var sessionToken: String? = null
    @Volatile private var lastSessionActivity = 0L
    @Volatile var url: String = ""
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        incomingRepository.cleanupExpired()
        exportRepository.cleanupExpired()
        pairingCode = newPairingCode()
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = socket
        url = "http://${findLanAddress()}:${socket.localPort}"
        stateMutable.value = TransferSessionState.AwaitingPairing(url, pairingCode)
        clients.execute { acceptLoop(socket) }
        maintenance.scheduleWithFixedDelay(::expireInactiveSession, 30, 30, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        sessionToken = null
        failedAttempts.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeClients.forEach { runCatching { it.close() } }
        activeClients.toList().forEach(activeClients::remove)
        clients.shutdownNow()
        maintenance.shutdownNow()
        stateMutable.value = TransferSessionState.Stopped
    }

    fun currentPairingCode(): String = pairingCode

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept().apply { soTimeout = SOCKET_TIMEOUT_MS }
                activeClients += client
                clients.execute { handleClient(client) }
            } catch (error: IOException) {
                if (running.get()) stateMutable.value = TransferSessionState.Error(error.message ?: "Transfer server stopped")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val output = BufferedOutputStream(client.getOutputStream())
                try {
            val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
            val request = readRequest(input)
            when {
                request.method == "GET" && request.path == "/" -> servePage(request, output)
                request.method == "POST" && request.path == "/api/pair" -> pair(request, input, output, client)
                request.method == "POST" && request.path == "/api/uploads" -> upload(request, input, output)
                request.method == "GET" && request.path.startsWith("/api/exports/") -> downloadExport(request, output)
                else -> respond(output, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
            }
                } catch (error: HttpException) {
                    respond(output, error.status, error.reason, "application/json; charset=utf-8", jsonError(error.message).toByteArray())
                } catch (error: Exception) {
                    if (running.get()) stateMutable.value = TransferSessionState.Error(error.message ?: "Transfer failed")
                    runCatching {
                        respond(output, 500, "Internal Server Error", "application/json; charset=utf-8", jsonError("Transfer failed").toByteArray())
                    }
                } finally {
                    runCatching { output.flush() }
                }
            }
        } finally {
            activeClients -= socket
        }
    }

    private fun servePage(request: Request, output: OutputStream) {
        val authorized = authorize(request)
        if (authorized) touchSession()
        val exports = if (authorized) exportRepository.list() else emptyList()
        val exportHtml = if (exports.isEmpty()) {
            "<p class=muted>No exports are ready.</p>"
        } else {
            exports.joinToString("\n") { entry ->
                "<a class=download href=\"/api/exports/${entry.id}\">${html(entry.displayName)} <small>${formatBytes(entry.size)}</small></a>"
            }
        }
        val template = context.assets.open("transfer/index.html").bufferedReader().use { it.readText() }
        val page = template
            .replace("{{PAIRING_DISPLAY}}", if (authorized) "none" else "block")
            .replace("{{TRANSFER_DISPLAY}}", if (authorized) "block" else "none")
            .replace("{{EXPORTS}}", exportHtml)
        respond(output, 200, "OK", "text/html; charset=utf-8", page.toByteArray())
    }

    private fun pair(request: Request, input: InputStream, output: OutputStream, client: Socket) {
        val address = client.inetAddress.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.computeIfAbsent(address) { PairingAttemptWindow() }
        if (attempt.isBlocked(now)) throw HttpException(429, "Too Many Requests", "Too many attempts; try again shortly")
        val length = request.contentLength(limit = MAX_PAIR_BODY)
        val body = readExactly(input, length.toInt()).toString(StandardCharsets.UTF_8)
        val submitted = when {
            request.contentType.startsWith("application/json") -> Regex("\"code\"\\s*:\\s*\"?(\\d{6})").find(body)?.groupValues?.get(1)
            else -> parseForm(body)["code"]
        }
        if (submitted != pairingCode) {
            attempt.recordFailure(now)
            throw HttpException(401, "Unauthorized", "Incorrect pairing code")
        }
        failedAttempts.remove(address)
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        sessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        lastSessionActivity = now
        stateMutable.value = TransferSessionState.Paired(url)
        respond(
            output,
            200,
            "OK",
            "application/json; charset=utf-8",
            "{\"paired\":true}".toByteArray(),
            mapOf("Set-Cookie" to "$COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    private fun upload(request: Request, input: InputStream, output: OutputStream) {
        requireAuthorized(request)
        val totalLength = request.contentLength()
        val boundary = parseBoundary(request.contentType)
        val body = LimitedInputStream(input, totalLength)
        val committed = mutableListOf<IncomingPackageEntry>()
        try {
            parseMultipart(body, boundary) { filename, partInput ->
                val pending = incomingRepository.beginUpload(filename)
                try {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var fileBytes = 0L
                    while (true) {
                        val read = partInput.read(buffer)
                        if (read < 0) break
                        pending.write(buffer, 0, read)
                        fileBytes += read
                        stateMutable.value = TransferSessionState.Uploading(filename, fileBytes, totalLength)
                    }
                    committed += pending.commit()
                } catch (error: Exception) {
                    pending.abort()
                    throw error
                }
            }
            if (committed.isEmpty()) throw HttpException(400, "Bad Request", "No package files supplied")
            touchSession()
            pairingCode = newPairingCode()
            stateMutable.value = TransferSessionState.Completed(committed.toList())
            respond(output, 201, "Created", "application/json; charset=utf-8", "{\"uploaded\":${committed.size}}".toByteArray())
        } catch (error: Exception) {
            committed.forEach { incomingRepository.delete(it.id) }
            throw error
        }
    }

    private fun downloadExport(request: Request, output: OutputStream) {
        requireAuthorized(request)
        val id = request.path.substringAfterLast('/')
        val entry = exportRepository.find(id) ?: throw HttpException(404, "Not Found", "Export not found")
        touchSession()
        val headers = mapOf(
            "Content-Disposition" to "attachment; filename=\"${httpFileName(entry.displayName)}\"",
            "Cache-Control" to "no-store",
        )
        writeHeaders(output, 200, "OK", "application/octet-stream", entry.size, headers)
        entry.file.inputStream().use { it.copyTo(output, BUFFER_SIZE) }
    }

    private fun parseMultipart(input: InputStream, boundary: String, onFile: (String, InputStream) -> Unit) {
        val firstBoundary = "--$boundary"
        if (readLine(input, MAX_LINE_LENGTH) != firstBoundary) throw HttpException(400, "Bad Request", "Malformed multipart body")
        var finished = false
        while (!finished) {
            val headers = readPartHeaders(input)
            val disposition = headers["content-disposition"].orEmpty()
            val filename = Regex("filename=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
            val part = MultipartPartInputStream(input, "\r\n--$boundary".toByteArray(StandardCharsets.US_ASCII))
            if (!filename.isNullOrBlank()) {
                try {
                    onFile(filename, part)
                } finally {
                    while (part.read() >= 0) Unit
                }
            } else {
                var discarded = 0
                while (part.read() >= 0) {
                    discarded++
                    if (discarded > MAX_NON_FILE_PART) throw HttpException(413, "Content Too Large", "Form field is too large")
                }
            }
            finished = part.isFinalBoundary
        }
    }

    private fun readPartHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, MAX_LINE_LENGTH)
            total += line.length
            if (total > MAX_HEADER_BYTES) throw HttpException(431, "Request Header Fields Too Large", "Multipart headers are too large")
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpException(400, "Bad Request", "Malformed multipart header")
            headers[line.substring(0, separator).lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
        }
    }

    private fun readRequest(input: InputStream): Request {
        val requestLine = readLine(input, MAX_LINE_LENGTH).split(' ')
        if (requestLine.size != 3 || requestLine[2] != "HTTP/1.1") throw HttpException(400, "Bad Request", "Malformed request")
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, MAX_LINE_LENGTH)
            total += line.length
            if (total > MAX_HEADER_BYTES) throw HttpException(431, "Request Header Fields Too Large", "Headers are too large")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpException(400, "Bad Request", "Malformed header")
            headers[line.substring(0, separator).lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
        }
        val rawPath = requestLine[1].substringBefore('?')
        return Request(requestLine[0].uppercase(Locale.ROOT), URLDecoder.decode(rawPath, "UTF-8"), headers)
    }

    private fun authorize(request: Request): Boolean {
        val expected = sessionToken ?: return false
        if (isPairingSessionExpired(lastSessionActivity, System.currentTimeMillis())) {
            expireSession()
            return false
        }
        val cookie = request.headers["cookie"].orEmpty().split(';')
            .map { it.trim().split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == COOKIE_NAME }
            ?.get(1)
        return cookie != null && TextUtils.equals(cookie, expected)
    }

    private fun requireAuthorized(request: Request) {
        if (!authorize(request)) throw HttpException(401, "Unauthorized", "Pair this browser first")
        touchSession()
    }

    private fun touchSession() {
        lastSessionActivity = System.currentTimeMillis()
    }

    private fun expireInactiveSession() {
        if (sessionToken != null && isPairingSessionExpired(lastSessionActivity, System.currentTimeMillis())) expireSession()
    }

    @Synchronized
    private fun expireSession() {
        sessionToken = null
        pairingCode = newPairingCode()
        if (running.get()) stateMutable.value = TransferSessionState.AwaitingPairing(url, pairingCode)
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(output, status, reason, contentType, body.size.toLong(), extraHeaders)
        output.write(body)
    }

    private fun writeHeaders(
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

    private fun newPairingCode(): String = String.format(Locale.US, "%06d", random.nextInt(1_000_000))

    private fun findLanAddress(): String {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
        return (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())?.hostAddress ?: "127.0.0.1"
    }

    private fun parseBoundary(contentType: String): String {
        if (!contentType.startsWith("multipart/form-data", true)) throw HttpException(415, "Unsupported Media Type", "Expected multipart upload")
        val value = contentType.split(';').map(String::trim).firstOrNull { it.startsWith("boundary=", true) }
            ?.substringAfter('=')?.trim('"').orEmpty()
        if (value.isBlank() || value.length > 200 || value.any { it == '\r' || it == '\n' }) {
            throw HttpException(400, "Bad Request", "Invalid multipart boundary")
        }
        return value
    }

    private fun Request.contentLength(limit: Long = Long.MAX_VALUE): Long {
        val length = headers["content-length"]?.toLongOrNull()
            ?: throw HttpException(411, "Length Required", "Content-Length is required")
        if (length < 0 || length > limit) throw HttpException(413, "Content Too Large", "Request is too large")
        return length
    }

    private fun readLine(input: InputStream, maxLength: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maxLength) {
            val value = input.read()
            if (value < 0) throw HttpException(400, "Bad Request", "Unexpected end of request")
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw HttpException(400, "Bad Request", "Malformed line ending")
                return bytes.toString("UTF-8")
            }
            bytes.write(value)
        }
        throw HttpException(414, "URI Too Long", "Line is too long")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < result.size) {
            val read = input.read(result, offset, result.size - offset)
            if (read < 0) throw HttpException(400, "Bad Request", "Incomplete request")
            offset += read
        }
        return result
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { field ->
        val parts = field.split('=', limit = 2)
        if (parts.isEmpty()) null else URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }.toMap()

    private fun html(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun httpFileName(value: String): String = value.replace(Regex("[\"\\r\\n]"), "_")
    private fun jsonError(value: String): String = "{\"error\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>) {
        val contentType: String get() = headers["content-type"].orEmpty()
    }

    private class HttpException(val status: Int, val reason: String, override val message: String) : IOException(message)

    private class LimitedInputStream(private val delegate: InputStream, private var remaining: Long) : InputStream() {
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
                    ended = true
                } else {
                    ready.addLast(pending.removeFirst())
                }
            }
        }

        private fun ArrayDeque<Byte>.matches(bytes: ByteArray): Boolean {
            if (size != bytes.size) return false
            var index = 0
            for (value in this) if (value != bytes[index++]) return false
            return true
        }

        private fun consumeOptionalCrLf(input: InputStream) {
            // The final CRLF is optional and the connection is closed after the request.
            if (input.markSupported()) {
                input.mark(2)
                if (input.read() != '\r'.code || input.read() != '\n'.code) input.reset()
            }
        }
    }

    companion object {
        private const val COOKIE_NAME = "VINSTALL_SESSION"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_LINE_LENGTH = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_PAIR_BODY = 4 * 1024L
        private const val MAX_NON_FILE_PART = 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 120_000
    }
}
