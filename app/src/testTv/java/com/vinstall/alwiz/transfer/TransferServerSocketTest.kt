package com.vinstall.alwiz.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferServerSocketTest {
    private lateinit var context: Context
    private var server: TransferServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "incoming").deleteRecursively()
        File(context.filesDir, "exports").deleteRecursively()
    }

    @After
    fun tearDown() {
        server?.stop()
        File(context.filesDir, "incoming").deleteRecursively()
        File(context.filesDir, "exports").deleteRecursively()
    }

    @Test
    fun pairingCookieMultiUploadAndAuthenticatedExportDownload() {
        val exportSource = File(context.cacheDir, "socket-export.apkv").apply { writeText("export-data") }
        val export = ExportRepository(context).register(exportSource)
        val running = TransferServer(context).also { server = it; it.start() }

        assertTrue(request(running, "GET / HTTP/1.1\r\nHost: tv\r\n\r\n").startsWith("HTTP/1.1 200"))
        val pairBody = "code=${running.currentPairingCode()}"
        val pairResponse = request(
            running,
            "POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${pairBody.toByteArray().size}\r\n\r\n$pairBody",
        )
        assertTrue(pairResponse.startsWith("HTTP/1.1 200"))
        val cookie = Regex("Set-Cookie: ([^;]+)").find(pairResponse)!!.groupValues[1]

        val boundary = "VInstallBoundary"
        val multipart = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"one.apk\"\r\n\r\nONE")
            append("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"two.apkm\"\r\n\r\nTWO")
            append("\r\n--$boundary--\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val uploadResponse = request(
            running,
            "POST /api/uploads HTTP/1.1\r\nHost: tv\r\nCookie: $cookie\r\n" +
                "Content-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: ${multipart.size}\r\n\r\n",
            multipart,
        )
        assertTrue(uploadResponse.startsWith("HTTP/1.1 201"))
        assertEquals(2, IncomingPackageRepository(context).list().size)

        val unauthenticated = request(running, "GET /api/exports/${export.id} HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertTrue(unauthenticated.startsWith("HTTP/1.1 401"))
        val downloaded = request(
            running,
            "GET /api/exports/${export.id} HTTP/1.1\r\nHost: tv\r\nCookie: $cookie\r\n\r\n",
        )
        assertTrue(downloaded.startsWith("HTTP/1.1 200"))
        assertTrue(downloaded.endsWith("export-data"))
    }

    @Test
    fun failedAttemptBlockingExpiryAndInterruptedUploadCleanup() {
        var now = 1_000L
        val running = TransferServer(context, clock = { now }).also { server = it; it.start() }
        val wrongCode = if (running.currentPairingCode() == "000000") "999999" else "000000"
        repeat(MAX_PAIRING_ATTEMPTS) {
            val body = "code=$wrongCode"
            val response = request(
                running,
                "POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: ${body.length}\r\n\r\n$body",
            )
            assertTrue(response.startsWith("HTTP/1.1 401"))
            now++
        }
        val blockedBody = "code=${running.currentPairingCode()}"
        val blocked = request(
            running,
            "POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${blockedBody.length}\r\n\r\n$blockedBody",
        )
        assertTrue(blocked.startsWith("HTTP/1.1 429"))

        now += 60_001L
        val pairResponse = request(
            running,
            "POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${blockedBody.length}\r\n\r\n$blockedBody",
        )
        val cookie = Regex("Set-Cookie: ([^;]+)").find(pairResponse)!!.groupValues[1]
        now += PAIRING_SESSION_TIMEOUT_MS + 1
        val expired = request(
            running,
            "POST /api/uploads HTTP/1.1\r\nHost: tv\r\nCookie: $cookie\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: 0\r\n\r\n",
        )
        assertTrue(expired.startsWith("HTTP/1.1 401"))

        // A fresh valid session receives a truncated body. The pending .part must be aborted.
        now += 60_001L
        val freshBody = "code=${running.currentPairingCode()}"
        val freshPair = request(
            running,
            "POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${freshBody.length}\r\n\r\n$freshBody",
        )
        val freshCookie = Regex("Set-Cookie: ([^;]+)").find(freshPair)!!.groupValues[1]
        val port = URL(running.url).port
        Socket("127.0.0.1", port).use { socket ->
            val output = socket.getOutputStream()
            output.write(
                ("POST /api/uploads HTTP/1.1\r\nHost: tv\r\nCookie: $freshCookie\r\n" +
                    "Content-Type: multipart/form-data; boundary=cut\r\nContent-Length: 999\r\n\r\n" +
                    "--cut\r\nContent-Disposition: form-data; name=\"files\"; filename=\"cut.apk\"\r\n\r\nPARTIAL")
                    .toByteArray(),
            )
            output.flush()
        }
        Thread.sleep(500)
        assertTrue(File(context.filesDir, "incoming").listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertTrue(IncomingPackageRepository(context).list().isEmpty())
    }

    @Test
    fun saturatedConnectionQueueReturnsServiceUnavailable() {
        val running = TransferServer(context).also { server = it; it.start() }
        val port = URL(running.url).port
        val held = mutableListOf<Socket>()
        try {
            repeat(12) { held += Socket("127.0.0.1", port) }
            Thread.sleep(250)
            Socket("127.0.0.1", port).use { overflow ->
                overflow.soTimeout = 5_000
                val response = overflow.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 503"))
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    private fun request(server: TransferServer, headers: String, body: ByteArray = byteArrayOf()): String {
        val port = URL(server.url).port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
            return socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }
}
