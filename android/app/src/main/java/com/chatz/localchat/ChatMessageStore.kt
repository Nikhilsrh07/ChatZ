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
    val conversationId: String,
    val conversationName: String,
    val conversationType: String,
)

data class ChatConversation(
    val id: String,
    val name: String,
    val type: String,
)

class ChatMessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        createConversationsTable(database)
        database.execSQL(
            "INSERT INTO conversations (id, name, type) VALUES ('$DIRECT_CONVERSATION_ID', 'Direct', 'direct')",
        )
        database.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                direction TEXT NOT NULL,
                transport TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                conversation_id TEXT NOT NULL DEFAULT '$DIRECT_CONVERSATION_ID'
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX messages_created_at_idx ON messages(created_at)")
        database.execSQL("CREATE INDEX messages_conversation_idx ON messages(conversation_id, created_at)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createConversationsTable(database)
            database.execSQL(
                "INSERT OR IGNORE INTO conversations (id, name, type) VALUES ('$DIRECT_CONVERSATION_ID', 'Direct', 'direct')",
            )
            database.execSQL(
                "ALTER TABLE messages ADD COLUMN conversation_id TEXT NOT NULL DEFAULT '$DIRECT_CONVERSATION_ID'",
            )
            database.execSQL(
                "CREATE INDEX messages_conversation_idx ON messages(conversation_id, created_at)",
            )
        }
    }

    fun insert(
        body: String,
        direction: String,
        transport: String,
        conversation: ChatConversation = directConversation(),
        id: String = UUID.randomUUID().toString(),
    ): ChatMessage {
        require(body.isNotBlank()) { "Message cannot be empty" }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Message exceeds the 64 KiB limit"
        }
        require('\n' !in body && '\r' !in body) { "Messages must be single-line text" }
        require(direction == DIRECTION_OUTGOING || direction == DIRECTION_INCOMING) {
            "Invalid message direction"
        }
        require(id.isNotBlank() && id.length <= MAX_CONVERSATION_ID_LENGTH) {
            "Message ID must contain between 1 and $MAX_CONVERSATION_ID_LENGTH characters"
        }
        requireConversation(conversation, allowDirect = true)
        val message = ChatMessage(
            id = id,
            body = body,
            createdAt = System.currentTimeMillis(),
            direction = direction,
            transport = transport,
            synced = false,
            conversationId = conversation.id,
            conversationName = conversation.name,
            conversationType = conversation.type,
        )
        val values = ContentValues().apply {
            put("id", message.id)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("direction", message.direction)
            put("transport", message.transport)
            put("synced", 0)
            put("conversation_id", message.conversationId)
        }
        val database = writableDatabase
        database.beginTransaction()
        val rowId = try {
            ensureConversation(database, conversation)
            val insertedId = if (direction == DIRECTION_INCOMING) {
                database.insertWithOnConflict(
                    "messages",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            } else {
                database.insertOrThrow("messages", null, values)
            }
            if (insertedId == -1L && direction == DIRECTION_INCOMING) {
                val exists = containsMessage(database, id)
                check(exists) { "Unable to persist incoming chat message" }
            }
            insertedId.also {
                database.setTransactionSuccessful()
            }
        } finally {
            database.endTransaction()
        }
        check(rowId != -1L || direction == DIRECTION_INCOMING) { "Unable to persist chat message" }
        return message
    }

    fun recent(
        conversationId: String? = null,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): List<ChatMessage> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "History limit must be between 1 and $MAX_HISTORY_LIMIT" }
        val messages = mutableListOf<ChatMessage>()
        val knownConversations = conversations().associateBy(ChatConversation::id)
        readableDatabase.query(
            "messages",
            arrayOf("id", "body", "created_at", "direction", "transport", "synced", "conversation_id"),
            conversationId?.let { "conversation_id = ?" },
            conversationId?.let { arrayOf(it) },
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
                    conversationId = cursor.getString(6),
                    conversationName = knownConversations[cursor.getString(6)]?.name ?: "Direct",
                    conversationType = knownConversations[cursor.getString(6)]?.type ?: TYPE_DIRECT,
                )
            }
        }
        return messages.asReversed()
    }

    fun pendingSync(limit: Int = SYNC_BATCH_SIZE): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val knownConversations = conversations().associateBy(ChatConversation::id)
        readableDatabase.query(
            "messages",
            arrayOf("id", "body", "created_at", "direction", "transport", "synced", "conversation_id"),
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
                    conversationId = cursor.getString(6),
                    conversationName = knownConversations[cursor.getString(6)]?.name ?: "Direct",
                    conversationType = knownConversations[cursor.getString(6)]?.type ?: TYPE_DIRECT,
                )
            }
        }
        return messages
    }

    fun conversations(): List<ChatConversation> =
        readableDatabase.query(
            "conversations",
            arrayOf("id", "name", "type"),
            null,
            null,
            null,
            null,
            "CASE WHEN type = 'direct' THEN 0 ELSE 1 END, name COLLATE NOCASE",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ChatConversation(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            type = cursor.getString(2),
                        ),
                    )
                }
            }
        }

    fun createConversation(name: String, type: String): ChatConversation {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Conversation name cannot be empty" }
        require(normalizedName.length <= MAX_CONVERSATION_NAME_LENGTH) {
            "Conversation name cannot exceed $MAX_CONVERSATION_NAME_LENGTH characters"
        }
        require(type == TYPE_GROUP || type == TYPE_CHANNEL) { "Invalid conversation type" }
        val conversation = ChatConversation(UUID.randomUUID().toString(), normalizedName, type)
        ensureConversation(writableDatabase, conversation)
        return conversation
    }

    fun saveConversation(conversation: ChatConversation) {
        require(conversation.id.isNotBlank()) { "Conversation ID cannot be empty" }
        requireConversation(conversation, allowDirect = false)
        ensureConversation(writableDatabase, conversation)
    }

    fun importRemote(message: ChatMessage) {
        require(message.id.isNotBlank()) { "Remote message ID cannot be empty" }
        require(message.id.length <= MAX_CONVERSATION_ID_LENGTH) { "Remote message ID is too long" }
        require(message.body.isNotBlank()) { "Remote message cannot be empty" }
        require(message.body.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Remote message exceeds the 64 KiB limit"
        }
        require('\n' !in message.body && '\r' !in message.body) {
            "Remote messages must be single-line text"
        }
        val conversation = ChatConversation(
            message.conversationId,
            message.conversationName,
            message.conversationType,
        )
        requireConversation(conversation, allowDirect = true)

        val values = ContentValues().apply {
            put("id", message.id)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("direction", DIRECTION_INCOMING)
            put("transport", message.transport)
            put("synced", 1)
            put("conversation_id", message.conversationId)
        }
        val database = writableDatabase
        database.beginTransaction()
        try {
            ensureConversation(database, conversation)
            val insertedId = database.insertWithOnConflict(
                "messages",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            check(insertedId != -1L || containsMessage(database, message.id)) {
                "Unable to import remote chat message"
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
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
        const val DIRECT_CONVERSATION_ID = "direct"
        const val DIRECTION_OUTGOING = "outgoing"
        const val DIRECTION_INCOMING = "incoming"
        const val TYPE_DIRECT = "direct"
        const val TYPE_GROUP = "group"
        const val TYPE_CHANNEL = "channel"
        private const val DATABASE_NAME = "chatz.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_CONVERSATION_NAME_LENGTH = 48
        private const val MAX_CONVERSATION_ID_LENGTH = 64
        private const val DEFAULT_HISTORY_LIMIT = 200
        private const val MAX_HISTORY_LIMIT = 1_000
        private const val SYNC_BATCH_SIZE = 50

        fun directConversation() =
            ChatConversation(DIRECT_CONVERSATION_ID, "Direct", TYPE_DIRECT)
    }

    private fun createConversationsTable(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun ensureConversation(database: SQLiteDatabase, conversation: ChatConversation) {
        val values = ContentValues().apply {
            put("id", conversation.id)
            put("name", conversation.name)
            put("type", conversation.type)
        }
        val rowId = database.insertWithOnConflict(
            "conversations",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId != -1L) { "Unable to save chat conversation" }
    }

    private fun containsMessage(database: SQLiteDatabase, id: String): Boolean =
        database.query(
            "messages",
            arrayOf("id"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    private fun requireConversation(conversation: ChatConversation, allowDirect: Boolean) {
        require(conversation.id.isNotBlank() && conversation.id.length <= MAX_CONVERSATION_ID_LENGTH) {
            "Conversation ID must contain between 1 and $MAX_CONVERSATION_ID_LENGTH characters"
        }
        require(conversation.name.isNotBlank() && conversation.name.length <= MAX_CONVERSATION_NAME_LENGTH) {
            "Conversation name must contain between 1 and $MAX_CONVERSATION_NAME_LENGTH characters"
        }
        require(
            (allowDirect && conversation.type == TYPE_DIRECT) ||
                conversation.type == TYPE_GROUP ||
                conversation.type == TYPE_CHANNEL,
        ) { "Invalid conversation type" }
    }
}
