package com.chatz.localchat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: ChatMessageStore
    private lateinit var syncClient: MongoSyncClient
    private lateinit var networkEngine: LocalChatEngine
    private lateinit var bluetoothEngine: BluetoothChatEngine
    private lateinit var statusView: TextView
    private lateinit var historyView: TextView
    private lateinit var messageInput: EditText
    private lateinit var networkAddressInput: EditText
    private lateinit var bluetoothAddressInput: EditText
    private lateinit var bluetoothPeerSpinner: Spinner
    private lateinit var bluetoothPeerAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChatMessageStore(applicationContext)
        buildInterface()

        syncClient = MongoSyncClient(
            store,
            ::showError,
            onSyncComplete = { runOnUiThread(::refreshHistory) },
        )
        networkEngine = LocalChatEngine(
            applicationContext,
            onMessageReceived = { receiveMessage(it, "Wi-Fi / tethered network") },
            onError = ::showError,
        )
        bluetoothEngine = BluetoothChatEngine(
            applicationContext,
            onMessageReceived = { receiveMessage(it, "Bluetooth") },
            onPeerFound = { name, address ->
                runOnUiThread { addBluetoothPeer(name, address) }
            },
            onError = ::showError,
        )
        refreshHistory()
        syncClient.syncPending()
    }

    override fun onDestroy() {
        if (::networkEngine.isInitialized) networkEngine.close()
        if (::bluetoothEngine.isInitialized) bluetoothEngine.close()
        if (::syncClient.isInitialized) syncClient.close()
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        statusView.text = if (granted) {
            "Bluetooth permissions granted. Retry the Bluetooth action."
        } else {
            "Bluetooth permissions are required for discovery and connections."
        }
    }

    private fun buildInterface() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scrollView = ScrollView(this).apply {
            addView(content)
        }

        content.addView(TextView(this).apply {
            text = "ChatZ local chat"
            textSize = 24f
        })
        statusView = TextView(this).apply {
            text = "Messages are stored on this device. Both peers must be on the same network for Wi-Fi/tethered chat."
            textSize = 14f
        }
        content.addView(statusView, matchWidth())

        content.addView(button("Start Wi-Fi / tethered network chat") {
            try {
                networkEngine.startServerAndRegister()
                networkEngine.discoverPeers()
                statusView.text = "Starting TCP listener on port ${LocalChatEngine.SERVER_PORT} " +
                    "and discovering over the current IP network."
            } catch (error: RuntimeException) {
                showError("Unable to start network chat", error)
            }
        })
        networkAddressInput = EditText(this).apply {
            hint = "Peer IP address (for Bluetooth tethering)"
            singleLine = true
        }
        content.addView(networkAddressInput, matchWidth())
        content.addView(button("Connect by IP on port 45454") {
            val address = networkAddressInput.text.toString().trim()
            try {
                networkEngine.connectToPeer(address)
                statusView.text = "Connecting to $address:45454."
            } catch (error: IllegalArgumentException) {
                statusView.text = error.message ?: "Enter the peer's IP address."
            }
        })
        content.addView(TextView(this).apply {
            text = "Bluetooth direct chat (paired devices)"
            textSize = 18f
        })
        content.addView(button("Start Bluetooth server") {
            if (ensureBluetoothPermissions()) {
                bluetoothEngine.startServer()
                statusView.text = "Starting Bluetooth server. Pair devices in Android settings first."
            }
        })
        content.addView(button("Load paired Bluetooth devices") {
            if (ensureBluetoothPermissions()) {
                try {
                    bluetoothEngine.pairedPeers().forEach { (name, address) ->
                        addBluetoothPeer(name, address)
                    }
                    statusView.text = "Paired devices loaded. Choose a peer, then connect."
                } catch (error: SecurityException) {
                    showError("Bluetooth permission is required to list paired devices", error)
                } catch (error: IllegalStateException) {
                    showError("Enable Bluetooth before listing paired devices", error)
                }
            }
        })
        content.addView(button("Discover Bluetooth devices") {
            if (ensureBluetoothPermissions()) {
                bluetoothEngine.discoverPeers()
                statusView.text = "Scanning for Bluetooth devices."
            }
        })
        bluetoothPeerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        bluetoothPeerSpinner = Spinner(this).apply {
            adapter = bluetoothPeerAdapter
        }
        content.addView(bluetoothPeerSpinner, matchWidth())
        bluetoothAddressInput = EditText(this).apply {
            hint = "Bluetooth address (filled after discovery)"
            singleLine = true
        }
        content.addView(bluetoothAddressInput, matchWidth())
        bluetoothPeerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                val label = parent?.getItemAtPosition(position)?.toString().orEmpty()
                val address = label.substringAfterLast('(').removeSuffix(")")
                if (address.isNotBlank()) bluetoothAddressInput.setText(address)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        content.addView(button("Connect Bluetooth") {
            if (!ensureBluetoothPermissions()) return@button
            val address = bluetoothAddressInput.text.toString().trim()
            if (address.isEmpty()) {
                statusView.text = "Discover a Bluetooth peer or enter its address."
            } else {
                bluetoothEngine.connect(address)
            }
        })

        content.addView(TextView(this).apply {
            text = "Message"
            textSize = 18f
        })
        messageInput = EditText(this).apply {
            hint = "Type a single-line message"
            singleLine = true
        }
        content.addView(messageInput, matchWidth())
        content.addView(button("Send over Wi-Fi / tethered network") {
            sendMessage(networkEngine::sendMessage, "Wi-Fi / tethered network")
        })
        content.addView(button("Send over Bluetooth") {
            if (ensureBluetoothPermissions()) sendMessage(bluetoothEngine::sendMessage, "Bluetooth")
        })
        content.addView(button("Retry MongoDB sync") {
            syncClient.syncPending()
            statusView.text = "Retrying unsynced messages."
        })
        content.addView(TextView(this).apply {
            text = "Message history"
            textSize = 18f
        })
        historyView = TextView(this).apply {
            textSize = 14f
        }
        content.addView(historyView, matchWidth())
        setContentView(scrollView)
    }

    private fun sendMessage(send: (String) -> Unit, transport: String) {
        val body = messageInput.text.toString().trim()
        if (body.isEmpty()) {
            statusView.text = "Enter a message first."
            return
        }
        try {
            store.insert(body, ChatMessageStore.DIRECTION_OUTGOING, transport)
            refreshHistory()
            syncClient.syncPending()
            send(body)
            messageInput.text.clear()
            statusView.text = "Message saved locally; attempting to send via $transport."
        } catch (error: IllegalArgumentException) {
            statusView.text = error.message ?: "Message is not valid."
        } catch (error: SQLiteException) {
            showError("Unable to save message locally", error)
        } catch (error: IllegalStateException) {
            showError("Unable to send message via $transport", error)
        }
    }

    private fun receiveMessage(body: String, transport: String) {
        try {
            store.insert(body, ChatMessageStore.DIRECTION_INCOMING, transport)
            syncClient.syncPending()
            runOnUiThread {
                refreshHistory()
                statusView.text = "Message received via $transport."
            }
        } catch (error: IllegalArgumentException) {
            showError("Received invalid message", error)
        } catch (error: SQLiteException) {
            showError("Unable to save received message", error)
        } catch (error: IllegalStateException) {
            showError("Unable to save received message", error)
        }
    }

    private fun refreshHistory() {
        val messages = store.recent()
        historyView.text = if (messages.isEmpty()) {
            "No messages yet."
        } else {
            messages.joinToString("\n\n") { message ->
                val sender = if (message.direction == ChatMessageStore.DIRECTION_OUTGOING) {
                    "You"
                } else {
                    "Peer"
                }
                val synced = if (message.synced) " · synced" else ""
                "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))} · " +
                    "$sender via ${message.transport}$synced\n${message.body}"
            }
        }
    }

    private fun addBluetoothPeer(name: String, address: String) {
        bluetoothAddressInput.setText(address)
        val peer = "$name ($address)"
        if (bluetoothPeerAdapter.getPosition(peer) == -1) bluetoothPeerAdapter.add(peer)
        statusView.text = "Found $name ($address). Tap Connect Bluetooth."
    }

    private fun ensureBluetoothPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        return false
    }

    private fun showError(message: String, error: Throwable?) {
        runOnUiThread {
            val detail = error?.message?.let { ": $it" }.orEmpty()
            statusView.text = message + detail
        }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
    }
}
