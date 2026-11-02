package com.chatz.localchat

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MongoSyncClient(
    private val store: ChatMessageStore,
    private val onError: (String, Throwable?) -> Unit,
    private val onSyncComplete: () -> Unit,
) : AutoCloseable {
    private val baseUrl = BuildConfig.SYNC_API_BASE_URL.trimEnd('/')
    private val token = BuildConfig.SYNC_API_TOKEN
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun syncPending() {
        if (baseUrl.isBlank() && token.isBlank()) return
        if (baseUrl.isBlank() || token.isBlank()) {
            onError("MongoDB sync needs both chatz.syncUrl and chatz.syncToken in android/local.properties", null)
            return
        }
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val pending = try {
                    store.pendingSync()
                } catch (error: RuntimeException) {
                    onError("Unable to load messages waiting for MongoDB sync", error)
                    return@execute
                }
                if (pending.isEmpty()) {
                    try {
                        pullRemoteMessages()
                        onSyncComplete()
                    } catch (error: IOException) {
                        onError("Unable to download messages from MongoDB", error)
                    } catch (error: org.json.JSONException) {
                        onError("MongoDB returned an invalid message", error)
                    } catch (error: RuntimeException) {
                        onError("Unable to save downloaded messages", error)
                    }
                    return@execute
                }
                for (message in pending) {
                    try {
                        sync(message)
                        store.markSynced(message.id)
                    } catch (error: IOException) {
                        onError("Unable to sync message ${message.id}", error)
                        return@execute
                    } catch (error: org.json.JSONException) {
                        onError("Unable to encode message ${message.id} for sync", error)
                        return@execute
                    } catch (error: RuntimeException) {
                        onError("Unable to persist sync state for message ${message.id}", error)
                        return@execute
                    }
                }
            }
        }
    }

    private fun pullRemoteMessages() {
        val connection = URL("$baseUrl/api/messages?limit=100").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("MongoDB sync API returned HTTP $statusCode: $detail")
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val messages = org.json.JSONArray(response)
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                store.importRemote(
                    ChatMessage(
                        id = message.getString("id"),
                        body = message.getString("body"),
                        createdAt = message.getLong("created_at"),
                        direction = ChatMessageStore.DIRECTION_INCOMING,
                        transport = message.getString("transport"),
                        synced = true,
                    ),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sync(message: ChatMessage) {
        val connection = URL("$baseUrl/api/messages").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject()
                .put("id", message.id)
                .put("body", message.body)
                .put("created_at", message.createdAt)
                .put("direction", message.direction)
                .put("transport", message.transport)
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("MongoDB sync API returned HTTP $statusCode: $detail")
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        executor.shutdown()
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000
    }
}
