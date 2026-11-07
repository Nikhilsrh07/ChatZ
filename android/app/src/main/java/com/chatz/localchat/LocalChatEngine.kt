package com.chatz.localchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalChatEngine(
    context: Context,
    private val onMessageReceived: (LocalChatEvent) -> Unit,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
    private val onPeerConnected: () -> Unit = {},
) : AutoCloseable {
    private val serviceType = "_localchat._tcp."
    private val serviceName = "ChatZ-${UUID.randomUUID().toString().take(8)}"
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private val io: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var closed = false
    private var serverSocket: ServerSocket? = null
    private var serverStarting = false
    private var activeSocket: Socket? = null
    private val peerWriters = linkedMapOf<Socket, BufferedWriter>()
    private val seenMessageIds = LinkedHashSet<String>()
    private val seenConversationIds = LinkedHashSet<String>()
    private var registrationRequested = false
    private var discoveryStarted = false

    fun startServerAndRegister() {
        synchronized(lock) {
            check(!closed) { "LocalChatEngine is closed" }
            if (serverSocket != null || serverStarting) return
            serverStarting = true
        }

        io.execute {
            var server: ServerSocket? = null
            try {
                val createdServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SERVER_PORT))
                }
                server = createdServer
                synchronized(lock) {
                    serverStarting = false
                    if (closed) {
                        createdServer.close()
                        return@execute
                    }
                    serverSocket = createdServer
                }

                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = this@LocalChatEngine.serviceName
                    serviceType = this@LocalChatEngine.serviceType
                    port = createdServer.localPort
                }
                synchronized(lock) { registrationRequested = true }
                nsdManager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener,
                )

                while (!closed) {
                    val socket = createdServer.accept()
                    attachPeer(socket)
                }
            } catch (error: IOException) {
                synchronized(lock) {
                    serverStarting = false
                    if (serverSocket === server) serverSocket = null
                }
                if (!closed) report("Local chat server stopped unexpectedly", error)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    serverStarting = false
                    if (serverSocket === server) serverSocket = null
                }
                if (!closed) report("Unable to start or register the local chat server", error)
            } finally {
                val shouldUnregister = synchronized(lock) {
                    if (serverSocket === server) serverSocket = null
                    serverStarting = false
                    registrationRequested.also { registrationRequested = false }
                }
                closeQuietly(server)
                if (shouldUnregister) {
                    try {
                        nsdManager.unregisterService(registrationListener)
                    } catch (error: IllegalArgumentException) {
                        if (!closed) report("Unable to unregister the stopped local chat service", error)
                    }
                }
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

    fun connectToPeer(address: String) {
        check(!closed) { "LocalChatEngine is closed" }
        val host = address.trim()
        require(host.isNotEmpty()) { "Enter the peer's IP address" }
        io.execute {
            val socket = Socket()
            try {
                val remoteAddress = java.net.InetAddress.getByName(host)
                socket.connect(InetSocketAddress(remoteAddress, SERVER_PORT), CONNECT_TIMEOUT_MS)
                attachPeer(socket)
            } catch (error: IOException) {
                closeQuietly(socket)
                if (!closed) report("Unable to connect to network peer at $host", error)
            }
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
                    val target = activeSocket?.let(peerWriters::get)
                        ?: throw IOException("No peer is connected")
                    synchronized(target) {
                        target.write(message)
                        target.newLine()
                        target.flush()
                    }
                }
            } catch (error: IOException) {
                report("Unable to send message", error)
            }
        }
    }

    fun announceConversation(conversation: ChatConversation) {
        if (conversation.type == ChatMessageStore.TYPE_DIRECT) return
        synchronized(lock) { remember(seenConversationIds, conversation.id) }
        val packet = JSONObject()
            .put("kind", "conversation")
            .put("id", conversation.id)
            .put("name", conversation.name)
            .put("type", conversation.type)
        broadcast(encodePacket(packet))
    }

    fun sendRoomMessage(message: ChatMessage) {
        val packet = JSONObject()
            .put("kind", "message")
            .put("id", message.id)
            .put("conversation_id", message.conversationId)
            .put("conversation_name", message.conversationName)
            .put("conversation_type", message.conversationType)
            .put("body", message.body)
        synchronized(lock) { remember(seenMessageIds, message.id) }
        broadcast(encodePacket(packet))
    }

    override fun close() {
        val socketsToClose: List<Socket>
        val serverToClose: ServerSocket?
        val shouldUnregister: Boolean
        val shouldStopDiscovery: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            serverStarting = false
            socketsToClose = peerWriters.keys.toList()
            peerWriters.clear()
            activeSocket = null
            serverToClose = serverSocket
            serverSocket = null
            shouldUnregister = registrationRequested
            registrationRequested = false
            shouldStopDiscovery = discoveryStarted
            discoveryStarted = false
        }

        socketsToClose.forEach(::closeQuietly)
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
        val nextWriter = try {
            BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            )
        } catch (error: IOException) {
            closeQuietly(socket)
            if (!closed) report("Unable to open the peer connection", error)
            return
        }
        synchronized(lock) {
            if (closed) {
                closeQuietly(socket)
                return
            }
            activeSocket = socket
            peerWriters[socket] = nextWriter
        }
        onPeerConnected()

        io.execute {
            try {
                BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                ).use { reader ->
                    while (!closed) {
                        val line = readBoundedLine(reader) ?: break
                        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_FRAME_BYTES) {
                            throw IOException("Received chat frame exceeds the size limit")
                        }
                        receiveLine(line, socket)
                    }
                }
            } catch (error: IOException) {
                if (!closed) report("Peer connection ended with an error", error)
            } finally {
                synchronized(lock) {
                    peerWriters.remove(socket)
                    if (activeSocket === socket) {
                        activeSocket = peerWriters.keys.lastOrNull()
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
            if (line.length >= MAX_FRAME_BYTES) {
                throw IOException("Received chat frame exceeds the size limit")
            }
            line.append(value.toChar())
        }
    }

    private fun receiveLine(line: String, source: Socket) {
        if (!line.startsWith(PACKET_PREFIX)) {
            onMessageReceived(
                LocalChatEvent(
                    kind = "message",
                    id = UUID.randomUUID().toString(),
                    conversationId = ChatMessageStore.DIRECT_CONVERSATION_ID,
                    conversationName = "Direct",
                    conversationType = ChatMessageStore.TYPE_DIRECT,
                    body = line,
                ),
            )
            return
        }

        try {
            val packet = JSONObject(line.removePrefix(PACKET_PREFIX))
            val kind = packet.getString("kind")
            val event = LocalChatEvent(
                kind = kind,
                id = packet.getString("id"),
                conversationId = if (kind == "conversation") {
                    packet.getString("id")
                } else {
                    packet.getString("conversation_id")
                },
                conversationName = if (kind == "conversation") {
                    packet.getString("name")
                } else {
                    packet.getString("conversation_name")
                },
                conversationType = if (kind == "conversation") {
                    packet.getString("type")
                } else {
                    packet.getString("conversation_type")
                },
                body = packet.optString("body"),
            )
            val isNew = synchronized(lock) {
                when (kind) {
                    "conversation" -> remember(seenConversationIds, event.conversationId)
                    "message" -> remember(seenMessageIds, event.id)
                    else -> throw IllegalArgumentException("Unknown chat frame kind")
                }
            }
            if (!isNew) return
            broadcast(encodePacket(packet), source)
            onMessageReceived(event)
        } catch (error: RuntimeException) {
            report("Received an invalid ChatZ mesh message", error)
        }
    }

    private fun broadcast(packet: String, excluded: Socket? = null) {
        io.execute {
            val writers = synchronized(lock) {
                peerWriters.filterKeys { it !== excluded }.values.toList()
            }
            for (target in writers) {
                try {
                    synchronized(target) {
                        target.write(packet)
                        target.newLine()
                        target.flush()
                    }
                } catch (error: IOException) {
                    report("Unable to send a message to a mesh peer", error)
                }
            }
        }
    }

    private fun encodePacket(packet: JSONObject): String = PACKET_PREFIX + packet.toString()

    private fun remember(ids: LinkedHashSet<String>, id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > MAX_SEEN_IDS) ids.remove(ids.first())
        return true
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val MAX_SEEN_IDS = 5_000
        private const val PACKET_PREFIX = "CHATZ1:"
        const val SERVER_PORT = 45_454
    }
}

data class LocalChatEvent(
    val kind: String,
    val id: String,
    val conversationId: String,
    val conversationName: String,
    val conversationType: String,
    val body: String,
)
