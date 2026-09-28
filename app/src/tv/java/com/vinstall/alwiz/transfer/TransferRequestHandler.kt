package com.vinstall.alwiz.transfer

import android.content.Context
import android.text.TextUtils
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class TransferRequestHandler(
    private val context: Context,
    private val incomingRepository: IncomingPackageRepository,
    private val exportRepository: ExportRepository,
    private val clock: () -> Long,
    private val publishState: (TransferSessionState) -> Unit,
) {
    private val random = SecureRandom()
    private val failedAttempts = ConcurrentHashMap<String, PairingAttemptWindow>()

    @Volatile private var active = false
    @Volatile private var serverUrl = ""
    @Volatile private var pairingCode = ""
    @Volatile private var sessionToken: String? = null
    @Volatile private var lastSessionActivity = 0L

    fun start(url: String) {
        active = true
        serverUrl = url
        pairingCode = newPairingCode()
        publishState(TransferSessionState.AwaitingPairing(url, pairingCode))
    }

    fun stop() {
        active = false
        sessionToken = null
        failedAttempts.clear()
    }

    fun currentPairingCode(): String = pairingCode

    fun handle(client: Socket, input: InputStream, output: OutputStream) {
        val request = readHttpRequest(input)
        when {
            request.method == "GET" && request.path == "/" -> servePage(request, output)
            request.method == "POST" && request.path == "/api/pair" -> pair(request, input, output, client)
            request.method == "POST" && request.path == "/api/uploads" -> {
                client.soTimeout = TRANSFER_UPLOAD_TIMEOUT_MS
                upload(request, input, output)
            }
            request.method == "GET" && request.path.startsWith("/api/exports/") ->
                downloadExport(request, output)
            else -> respond(
                output,
                404,
                "Not Found",
                "text/plain; charset=utf-8",
                "Not found".toByteArray(),
            )
        }
    }

    fun expireInactiveSession() {
        if (sessionToken != null && isPairingSessionExpired(lastSessionActivity, clock())) {
            expireSession()
        }
    }

    private fun servePage(request: HttpRequest, output: OutputStream) {
        val authorized = authorize(request)
        if (authorized) touchSession()
        val exports = if (authorized) exportRepository.list() else emptyList()
        val exportHtml = if (exports.isEmpty()) {
            "<p class=muted>No exports are ready.</p>"
        } else {
            exports.joinToString("\n") { entry ->
                "<a class=download href=\"/api/exports/${entry.id}\">" +
                    "${html(entry.displayName)} <small>${formatTransferBytes(entry.size)}</small></a>"
            }
        }
        val template = context.assets.open("transfer/index.html").bufferedReader().use { it.readText() }
        val page = template
            .replace("{{PAIRING_DISPLAY}}", if (authorized) "none" else "block")
            .replace("{{TRANSFER_DISPLAY}}", if (authorized) "block" else "none")
            .replace("{{EXPORTS}}", exportHtml)
        respond(output, 200, "OK", "text/html; charset=utf-8", page.toByteArray())
    }

    private fun pair(
        request: HttpRequest,
        input: InputStream,
        output: OutputStream,
        client: Socket,
    ) {
        val address = client.inetAddress.hostAddress ?: "unknown"
        val now = clock()
        val attempt = failedAttempts.computeIfAbsent(address) { PairingAttemptWindow() }
        if (attempt.isBlocked(now)) {
            throw HttpException(429, "Too Many Requests", "Too many attempts; try again shortly")
        }
        val length = request.contentLength(limit = MAX_PAIR_BODY)
        val body = readExactly(input, length.toInt()).toString(StandardCharsets.UTF_8)
        val submitted = when {
            request.contentType.startsWith("application/json") ->
                Regex("\"code\"\\s*:\\s*\"?(\\d{6})").find(body)?.groupValues?.get(1)
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
        publishState(TransferSessionState.Paired(serverUrl))
        respond(
            output,
            200,
            "OK",
            "application/json; charset=utf-8",
            "{\"paired\":true}".toByteArray(),
            mapOf("Set-Cookie" to "$COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    private fun upload(request: HttpRequest, input: InputStream, output: OutputStream) {
        requireAuthorized(request)
        val totalLength = request.contentLength()
        val boundary = parseMultipartBoundary(request.contentType)
        val body = LimitedInputStream(input, totalLength)
        val committed = mutableListOf<IncomingPackageEntry>()
        try {
            MultipartReader.readFiles(body, boundary) { filename, partInput ->
                val pending = incomingRepository.beginUpload(filename)
                try {
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    var fileBytes = 0L
                    while (true) {
                        val read = partInput.read(buffer)
                        if (read < 0) break
                        pending.write(buffer, 0, read)
                        fileBytes += read
                        publishState(TransferSessionState.Uploading(filename, fileBytes, totalLength))
                    }
                    committed += pending.commit()
                } catch (error: Exception) {
                    pending.abort()
                    throw error
                }
            }
            if (committed.isEmpty()) {
                throw HttpException(400, "Bad Request", "No package files supplied")
            }
            touchSession()
            pairingCode = newPairingCode()
            publishState(TransferSessionState.Completed(committed.toList()))
            respond(
                output,
                201,
                "Created",
                "application/json; charset=utf-8",
                "{\"uploaded\":${committed.size}}".toByteArray(),
            )
        } catch (error: Exception) {
            committed.forEach { incomingRepository.delete(it.id) }
            throw error
        }
    }

    private fun downloadExport(request: HttpRequest, output: OutputStream) {
        requireAuthorized(request)
        val id = request.path.substringAfterLast('/')
        val entry = exportRepository.find(id)
            ?: throw HttpException(404, "Not Found", "Export not found")
        touchSession()
        val headers = mapOf(
            "Content-Disposition" to "attachment; filename=\"${httpFileName(entry.displayName)}\"",
            "Cache-Control" to "no-store",
        )
        writeHttpHeaders(output, 200, "OK", "application/octet-stream", entry.size, headers)
        entry.file.inputStream().use { it.copyTo(output, TRANSFER_BUFFER_SIZE) }
    }

    private fun authorize(request: HttpRequest): Boolean {
        val expected = sessionToken ?: return false
        if (isPairingSessionExpired(lastSessionActivity, clock())) {
            expireSession()
            return false
        }
        val cookie = request.headers["cookie"].orEmpty().split(';')
            .map { it.trim().split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == COOKIE_NAME }
            ?.get(1)
        return cookie != null && TextUtils.equals(cookie, expected)
    }

    private fun requireAuthorized(request: HttpRequest) {
        if (!authorize(request)) {
            throw HttpException(401, "Unauthorized", "Pair this browser first")
        }
        touchSession()
    }

    private fun touchSession() {
        lastSessionActivity = clock()
    }

    @Synchronized
    private fun expireSession() {
        sessionToken = null
        pairingCode = newPairingCode()
        if (active) publishState(TransferSessionState.AwaitingPairing(serverUrl, pairingCode))
    }

    private fun newPairingCode(): String =
        String.format(Locale.US, "%06d", random.nextInt(1_000_000))

    private companion object {
        const val COOKIE_NAME = "VINSTALL_SESSION"
        const val MAX_PAIR_BODY = 4 * 1024L
    }
}
