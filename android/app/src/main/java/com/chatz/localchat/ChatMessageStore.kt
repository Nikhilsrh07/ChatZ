package com.chatz.localchat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class ChatMessage(
    val id: String,
    val body: String,
    val createdAt: Long,
    val direction: String,
    val transport: String,
    val synced: Boolean,
)

class ChatMessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                direction TEXT NOT NULL,
                transport TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX messages_created_at_idx ON messages(created_at)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported ChatZ database upgrade: $oldVersion to $newVersion")
    }

    fun insert(body: String, direction: String, transport: String): ChatMessage {
        require(body.isNotBlank()) { "Message cannot be empty" }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Message exceeds the 64 KiB limit"
        }
        require('\n' !in body && '\r' !in body) { "Messages must be single-line text" }
        require(direction == DIRECTION_OUTGOING || direction == DIRECTION_INCOMING) {
            "Invalid message direction"
        }

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            body = body,
            createdAt = System.currentTimeMillis(),
            direction = direction,
            transport = transport,
            synced = false,
        )
        val values = ContentValues().apply {
            put("id", message.id)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("direction", message.direction)
            put("transport", message.transport)
            put("synced", 0)
        }
        val rowId = writableDatabase.insertOrThrow("messages", null, values)
        check(rowId != -1L) { "Unable to persist chat message" }
        return message
    }

    fun recent(limit: Int = DEFAULT_HISTORY_LIMIT): List<ChatMessage> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "History limit must be between 1 and $MAX_HISTORY_LIMIT" }
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "body", "created_at", "direction", "transport", "synced"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    id = cursor.getString(0),
                    body = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    direction = cursor.getString(3),
                    transport = cursor.getString(4),
                    synced = cursor.getInt(5) != 0,
                )
            }
        }
        return messages.asReversed()
    }

    fun pendingSync(limit: Int = SYNC_BATCH_SIZE): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "body", "created_at", "direction", "transport", "synced"),
            "synced = 0",
            null,
            null,
            null,
            "created_at ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    id = cursor.getString(0),
                    body = cursor.getString(1),
                    createdAt = cursor.getLong(2),
                    direction = cursor.getString(3),
                    transport = cursor.getString(4),
                    synced = cursor.getInt(5) != 0,
                )
            }
        }
        return messages
    }

    fun importRemote(message: ChatMessage) {
        require(message.id.isNotBlank()) { "Remote message ID cannot be empty" }
        require(message.body.isNotBlank()) { "Remote message cannot be empty" }
        require(message.body.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Remote message exceeds the 64 KiB limit"
        }
        require('\n' !in message.body && '\r' !in message.body) {
            "Remote messages must be single-line text"
        }

        val values = ContentValues().apply {
            put("id", message.id)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("direction", DIRECTION_INCOMING)
            put("transport", message.transport)
            put("synced", 1)
        }
        writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun markSynced(id: String) {
        val updated = writableDatabase.update(
            "messages",
            ContentValues().apply { put("synced", 1) },
            "id = ?",
            arrayOf(id),
        )
        check(updated == 1) { "Stored chat message was not found for sync completion" }
    }

    companion object {
        const val DIRECTION_OUTGOING = "outgoing"
        const val DIRECTION_INCOMING = "incoming"
        private const val DATABASE_NAME = "chatz.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val DEFAULT_HISTORY_LIMIT = 200
        private const val MAX_HISTORY_LIMIT = 1_000
        private const val SYNC_BATCH_SIZE = 50
    }
}
