package com.chatz.localchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalChatEngine(
    context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val serviceType = "_localchat._tcp."
    private val serviceName = "ChatZ"
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private val io: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var closed = false
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var writer: BufferedWriter? = null
    private var registrationRequested = false
    private var discoveryStarted = false

    fun startServerAndRegister() {
        synchronized(lock) {
            check(!closed) { "LocalChatEngine is closed" }
            if (serverSocket != null) return
        }

        io.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                synchronized(lock) {
                    if (closed) {
                        server.close()
                        return@execute
                    }
                    serverSocket = server
                }

                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = this@LocalChatEngine.serviceName
                    serviceType = this@LocalChatEngine.serviceType
                    port = server.localPort
                }
                synchronized(lock) { registrationRequested = true }
                nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener,
                )

                while (!closed) {
                    val socket = server.accept()
                    attachPeer(socket)
                }
            } catch (error: IOException) {
                if (!closed) report("Local chat server stopped unexpectedly", error)
            } catch (error: RuntimeException) {
                if (!closed) report("Unable to start or register the local chat server", error)
            }
        }
    }

    fun discoverPeers() {
        synchronized(lock) {
            check(!closed) { "LocalChatEngine is closed" }
            if (discoveryStarted) return
            discoveryStarted = true
        }
        try {
            nsdManager.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (error: RuntimeException) {
            synchronized(lock) { discoveryStarted = false }
            report("Unable to start local service discovery", error)
        }
    }

    fun sendMessage(message: String) {
        check(!closed) { "LocalChatEngine is closed" }
        require('\n' !in message && '\r' !in message) {
            "Messages must not contain line breaks"
        }
        require(message.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Message exceeds the 64 KiB limit"
        }

        io.execute {
            try {
                synchronized(lock) {
                    val target = writer ?: throw IOException("No peer is connected")
                    target.write(message)
                    target.newLine()
                    target.flush()
                }
            } catch (error: IOException) {
                report("Unable to send message", error)
            }
        }
    }

    override fun close() {
        val socketToClose: Socket?
        val serverToClose: ServerSocket?
        val shouldUnregister: Boolean
        val shouldStopDiscovery: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            socketToClose = activeSocket
            activeSocket = null
            writer = null
            serverToClose = serverSocket
            serverSocket = null
            shouldUnregister = registrationRequested
            registrationRequested = false
            shouldStopDiscovery = discoveryStarted
            discoveryStarted = false
        }

        closeQuietly(socketToClose)
        closeQuietly(serverToClose)
        if (shouldUnregister) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (error: IllegalArgumentException) {
                report("Unable to unregister the local chat service", error)
            }
        }
        if (shouldStopDiscovery) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (error: IllegalArgumentException) {
                report("Unable to stop local service discovery", error)
            }
        }
        io.shutdownNow()
    }

    private fun attachPeer(socket: Socket) {
        synchronized(lock) {
            if (closed) {
                closeQuietly(socket)
                return
            }
            closeQuietly(activeSocket)
            activeSocket = socket
            writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            )
        }

        io.execute {
            try {
                BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                ).use { reader ->
                    while (!closed) {
                        val line = readBoundedLine(reader) ?: break
                        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                            throw IOException("Received message exceeds the 64 KiB limit")
                        }
                        onMessageReceived(line)
                    }
                }
            } catch (error: IOException) {
                if (!closed) report("Peer connection ended with an error", error)
            } finally {
                synchronized(lock) {
                    if (activeSocket === socket) {
                        activeSocket = null
                        writer = null
                    }
                }
                closeQuietly(socket)
            }
        }
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        var readAny = false
        while (true) {
            val value = reader.read()
            if (value == -1) return if (readAny) line.toString() else null
            readAny = true
            if (value == '\n'.code) return line.toString()
            if (value == '\r'.code) {
                reader.mark(1)
                if (reader.read() != '\n'.code) reader.reset()
                return line.toString()
            }
            if (line.length >= MAX_MESSAGE_BYTES) {
                throw IOException("Received message exceeds the 64 KiB limit")
            }
            line.append(value.toChar())
        }
    }

    private fun connectToHost(host: java.net.InetAddress, port: Int) {
        io.execute {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                attachPeer(socket)
            } catch (error: IOException) {
                closeQuietly(socket)
                if (!closed) report("Unable to connect to discovered peer", error)
            }
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (error: IOException) {
            if (!closed) report("Unable to close peer connection", error)
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (error: IOException) {
            if (!closed) report("Unable to close local chat server", error)
        }
    }

    private fun report(message: String, error: Throwable?) {
        onError(message, error)
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            synchronized(lock) { registrationRequested = false }
            report("Service registration failed (code $errorCode)", null)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            report("Service unregistration failed (code $errorCode)", null)
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(lock) { discoveryStarted = false }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(lock) { discoveryStarted = false }
            report("Service discovery failed to start (code $errorCode)", null)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(lock) { discoveryStarted = false }
            report("Service discovery failed to stop (code $errorCode)", null)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != this@LocalChatEngine.serviceType ||
                serviceInfo.serviceName == this@LocalChatEngine.serviceName
            ) {
                return
            }
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    report("Peer resolution failed (code $errorCode)", null)
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    connectToHost(resolvedInfo.host, resolvedInfo.port)
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_MESSAGE_BYTES = 64 * 1024
    }
}
