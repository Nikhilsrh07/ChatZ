package com.chatz.localchat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BluetoothChatEngine(
    context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onPeerFound: (String, String) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter
    private val lock = Any()
    private val io: ExecutorService = Executors.newFixedThreadPool(3)

    @Volatile
    private var closed = false
    private var serverSocket: android.bluetooth.BluetoothServerSocket? = null
    private var serverStarting = false
    private var activeSocket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = intent.bluetoothDeviceExtra() ?: return
            try {
                onPeerFound(device.name ?: "Nearby ChatZ device", device.address)
            } catch (error: SecurityException) {
                report("Bluetooth device details are not permitted", error)
            }
        }
    }

    fun startServer() {
        check(!closed) { "BluetoothChatEngine is closed" }
        synchronized(lock) {
            if (serverSocket != null || serverStarting) return
            serverStarting = true
        }
        io.execute {
            var server: android.bluetooth.BluetoothServerSocket? = null
            try {
                val bluetooth = requireAdapter()
                val createdServer = bluetooth.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = createdServer
                synchronized(lock) {
                    serverStarting = false
                    if (closed) {
                        createdServer.close()
                        return@execute
                    }
                    serverSocket = createdServer
                }
                while (!closed) attachPeer(createdServer.accept())
            } catch (error: IOException) {
                if (!closed) report("Bluetooth server stopped unexpectedly", error)
            } catch (error: SecurityException) {
                if (!closed) report("Bluetooth permission is required to start the server", error)
            } catch (error: IllegalStateException) {
                if (!closed) report("Enable Bluetooth before starting the server", error)
            } finally {
                synchronized(lock) {
                    serverStarting = false
                    if (serverSocket === server) serverSocket = null
                }
                closeQuietly(server)
            }
        }
    }

    fun discoverPeers() {
        check(!closed) { "BluetoothChatEngine is closed" }
        try {
            val bluetooth = requireAdapter()
            registerDiscoveryReceiver()
            if (!bluetooth.startDiscovery()) {
                report("Bluetooth discovery could not be started", null)
            }
        } catch (error: SecurityException) {
            report("Bluetooth scan permission is required to discover devices", error)
        } catch (error: IllegalStateException) {
            report("Enable Bluetooth before discovering devices", error)
        } catch (error: IOException) {
            report("Unable to listen for Bluetooth discovery results", error)
        }
    }

    fun pairedPeers(): List<Pair<String, String>> {
        val bluetooth = requireAdapter()
        return bluetooth.bondedDevices.map { device ->
            (device.name ?: "Paired Android device") to device.address
        }
    }

    fun connect(address: String) {
        check(!closed) { "BluetoothChatEngine is closed" }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            report("Enter a valid Bluetooth device address", null)
            return
        }
        io.execute {
            var socket: BluetoothSocket? = null
            try {
                val bluetooth = requireAdapter()
                if (bluetooth.isDiscovering) bluetooth.cancelDiscovery()
                socket = bluetooth.getRemoteDevice(address)
                    .createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                attachPeer(socket)
            } catch (error: IOException) {
                closeQuietly(socket)
                if (!closed) report("Unable to connect to Bluetooth peer $address", error)
            } catch (error: SecurityException) {
                closeQuietly(socket)
                if (!closed) report("Bluetooth connection permission is required", error)
            } catch (error: IllegalStateException) {
                closeQuietly(socket)
                if (!closed) report("Enable Bluetooth before connecting", error)
            }
        }
    }

    fun sendMessage(message: String) {
        check(!closed) { "BluetoothChatEngine is closed" }
        require('\n' !in message && '\r' !in message) {
            "Messages must not contain line breaks"
        }
        require(message.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Message exceeds the 64 KiB limit"
        }

        io.execute {
            try {
                synchronized(lock) {
                    val target = writer ?: throw IOException("No Bluetooth peer is connected")
                    target.write(message)
                    target.newLine()
                    target.flush()
                }
            } catch (error: IOException) {
                if (!closed) report("Unable to send Bluetooth message", error)
            }
        }
    }

    override fun close() {
        val socketToClose: BluetoothSocket?
        val serverToClose: android.bluetooth.BluetoothServerSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            socketToClose = activeSocket
            activeSocket = null
            writer = null
            serverToClose = serverSocket
            serverSocket = null
        }
        adapter?.let {
            try {
                if (it.isDiscovering) it.cancelDiscovery()
            } catch (error: SecurityException) {
                report("Bluetooth scan permission was revoked before shutdown", error)
            }
        }
        synchronized(lock) {
            if (receiverRegistered) {
                try {
                    appContext.unregisterReceiver(discoveryReceiver)
                } catch (error: IllegalArgumentException) {
                    report("Unable to stop Bluetooth discovery listener", error)
                }
                receiverRegistered = false
            }
        }
        closeQuietly(socketToClose)
        closeQuietly(serverToClose)
        io.shutdownNow()
    }

    private fun registerDiscoveryReceiver() {
        synchronized(lock) {
            if (receiverRegistered) return
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun requireAdapter(): BluetoothAdapter {
        val bluetooth = adapter ?: throw IllegalStateException("This device does not support Bluetooth")
        if (!bluetooth.isEnabled) throw IllegalStateException("Bluetooth is disabled")
        return bluetooth
    }

    private fun attachPeer(socket: BluetoothSocket) {
        val nextWriter = try {
            BufferedWriter(
                OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8),
            )
        } catch (error: IOException) {
            closeQuietly(socket)
            if (!closed) report("Unable to open the Bluetooth peer connection", error)
            return
        }
        synchronized(lock) {
            if (closed) {
                closeQuietly(socket)
                return
            }
            closeQuietly(activeSocket)
            activeSocket = socket
            writer = nextWriter
        }
        io.execute {
            try {
                BufferedReader(
                    InputStreamReader(socket.inputStream, StandardCharsets.UTF_8),
                ).use { reader ->
                    while (!closed) {
                        val line = readBoundedLine(reader) ?: break
                        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                            throw IOException("Received Bluetooth message exceeds the 64 KiB limit")
                        }
                        onMessageReceived(line)
                    }
                }
            } catch (error: IOException) {
                if (!closed) report("Bluetooth peer connection ended with an error", error)
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
                throw IOException("Received Bluetooth message exceeds the 64 KiB limit")
            }
            line.append(value.toChar())
        }
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (error: IOException) {
            if (!closed) report("Unable to close Bluetooth peer connection", error)
        }
    }

    private fun closeQuietly(socket: android.bluetooth.BluetoothServerSocket?) {
        try {
            socket?.close()
        } catch (error: IOException) {
            if (!closed) report("Unable to close Bluetooth server", error)
        }
    }

    private fun report(message: String, error: Throwable?) {
        onError(message, error)
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val SERVICE_NAME = "ChatZ"
        const val MAX_MESSAGE_BYTES = 64 * 1024
        val SERVICE_UUID: UUID = UUID.fromString("6d871e52-f4f7-4e15-95a0-5033e6ba3401")
    }
}
