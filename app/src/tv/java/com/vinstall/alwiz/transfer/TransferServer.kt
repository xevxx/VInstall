package com.vinstall.alwiz.transfer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface TransferSessionState {
    data object Stopped : TransferSessionState
    data class AwaitingPairing(val url: String, val code: String) : TransferSessionState
    data class Paired(val url: String) : TransferSessionState
    data class Uploading(
        val fileName: String,
        val receivedBytes: Long,
        val totalBytes: Long,
    ) : TransferSessionState

    data class Completed(val packages: List<IncomingPackageEntry>) : TransferSessionState
    data class Error(val message: String) : TransferSessionState
}

/** A lifecycle-bound, local-network-only HTTP server for TV package transfer. */
class TransferServer(
    private val context: Context,
    private val incomingRepository: IncomingPackageRepository = IncomingPackageRepository(context),
    private val exportRepository: ExportRepository = ExportRepository(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    private val acceptor = Executors.newSingleThreadExecutor()
    private val clients = ThreadPoolExecutor(
        CLIENT_CORE_THREADS,
        CLIENT_MAX_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(CLIENT_QUEUE_CAPACITY),
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    private val activeClientCount = AtomicInteger()
    private val mutableState = MutableStateFlow<TransferSessionState>(TransferSessionState.Stopped)
    private val requestHandler = TransferRequestHandler(
        context,
        incomingRepository,
        exportRepository,
        clock,
        { mutableState.value = it },
    )

    val state: StateFlow<TransferSessionState> = mutableState.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var url: String = ""
        private set

    internal val activeConnectionCount: Int
        get() = activeClientCount.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        incomingRepository.cleanupExpired()
        exportRepository.cleanupExpired()
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = socket
        url = "http://${findLanAddress()}:${socket.localPort}"
        requestHandler.start(url)
        acceptor.execute { acceptLoop(socket) }
        maintenance.scheduleWithFixedDelay(
            requestHandler::expireInactiveSession,
            30,
            30,
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        requestHandler.stop()
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeClients.forEach { client ->
            runCatching { client.close() }
            removeActiveClient(client)
        }
        acceptor.shutdownNow()
        clients.shutdownNow()
        maintenance.shutdownNow()
        awaitShutdown(acceptor, clients, maintenance)
        mutableState.value = TransferSessionState.Stopped
    }

    fun currentPairingCode(): String = requestHandler.currentPairingCode()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept().apply { soTimeout = TRANSFER_HEADER_TIMEOUT_MS }
                if (activeClients.add(client)) activeClientCount.incrementAndGet()
                try {
                    clients.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    rejectBusyClient(client)
                    removeActiveClient(client)
                }
            } catch (error: IOException) {
                if (running.get()) {
                    mutableState.value = TransferSessionState.Error(
                        error.message ?: "Transfer server stopped",
                    )
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                val output = BufferedOutputStream(client.getOutputStream())
                try {
                    val input = BufferedInputStream(client.getInputStream(), TRANSFER_BUFFER_SIZE)
                    requestHandler.handle(client, input, output)
                } catch (error: HttpException) {
                    respond(
                        output,
                        error.status,
                        error.reason,
                        "application/json; charset=utf-8",
                        jsonError(error.message).toByteArray(),
                    )
                } catch (error: Exception) {
                    if (running.get()) {
                        mutableState.value = TransferSessionState.Error(
                            error.message ?: "Transfer failed",
                        )
                    }
                    runCatching {
                        respond(
                            output,
                            500,
                            "Internal Server Error",
                            "application/json; charset=utf-8",
                            jsonError("Transfer failed").toByteArray(),
                        )
                    }
                } finally {
                    runCatching { output.flush() }
                }
            }
        } catch (error: Exception) {
            if (running.get() && !socket.isClosed) {
                mutableState.value = TransferSessionState.Error(error.message ?: "Transfer failed")
            }
        } finally {
            removeActiveClient(socket)
        }
    }

    private fun removeActiveClient(client: Socket) {
        if (activeClients.remove(client)) activeClientCount.decrementAndGet()
    }

    private fun rejectBusyClient(client: Socket) {
        runCatching {
            client.use {
                val output = BufferedOutputStream(it.getOutputStream())
                respond(
                    output,
                    503,
                    "Service Unavailable",
                    "text/plain; charset=utf-8",
                    "Server is busy".toByteArray(),
                )
                output.flush()
            }
        }
    }

    private fun awaitShutdown(vararg executors: ExecutorService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        executors.forEach { executor ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) {
                runCatching { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS) }
            }
        }
    }

    private fun findLanAddress(): String {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
        return (candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull())
            ?.hostAddress
            ?: "127.0.0.1"
    }

    private companion object {
        const val CLIENT_CORE_THREADS = 2
        const val CLIENT_MAX_THREADS = 4
        const val CLIENT_QUEUE_CAPACITY = 8
    }
}
