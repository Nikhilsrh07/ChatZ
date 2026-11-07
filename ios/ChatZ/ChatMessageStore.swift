import Foundation
import SQLite3

final class ChatMessageStore {
    private var database: OpaquePointer?
    private let lock = NSLock()
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    private static let maximumMessageBytes = 64 * 1024

    init() throws {
        let directory = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let databaseURL = directory.appendingPathComponent("chatz.sqlite")
        guard sqlite3_open(databaseURL.path, &database) == SQLITE_OK else {
            throw storeError("Unable to open the local message database")
        }
        try execute(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL
            )
            """
        )
        try execute(
            "INSERT OR IGNORE INTO conversations (id, name, type) VALUES ('direct', 'Direct', 'direct')"
        )
        try execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                direction TEXT NOT NULL,
                transport TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                conversation_id TEXT NOT NULL DEFAULT 'direct'
            )
            """
        )
        let existingMessageColumns = try messageColumns()
        if !existingMessageColumns.contains("conversation_id") {
            try execute(
                "ALTER TABLE messages ADD COLUMN conversation_id TEXT NOT NULL DEFAULT 'direct'"
            )
        }
        try execute("CREATE INDEX IF NOT EXISTS messages_created_at_idx ON messages(created_at)")
        try execute(
            "CREATE INDEX IF NOT EXISTS messages_conversation_idx ON messages(conversation_id, created_at)"
        )
    }

    deinit {
        sqlite3_close(database)
    }

    func insert(
        body: String,
        direction: String,
        conversation: ChatConversation = .direct,
        id: String = UUID().uuidString
    ) throws -> ChatMessage {
        try validate(body: body)
        guard direction == "outgoing" || direction == "incoming" else {
            throw storeError("Invalid message direction")
        }
        try validate(conversation: conversation, allowDirect: true)
        guard !id.isEmpty, id.count <= 64 else {
            throw storeError("Message ID must contain between 1 and 64 characters")
        }
        let message = ChatMessage(
            id: id,
            body: body,
            direction: direction,
            conversationId: conversation.id,
            conversationName: conversation.name,
            conversationType: conversation.type
        )
        lock.lock()
        defer { lock.unlock() }
        try upsertConversation(conversation)
        let conflictBehavior = direction == "incoming" ? "OR IGNORE " : ""
        let statement = try prepare(
            "INSERT \(conflictBehavior)INTO messages (id, body, created_at, direction, transport, synced, conversation_id) VALUES (?, ?, ?, ?, ?, 0, ?)"
        )
        defer { sqlite3_finalize(statement) }
        bind(message.id, to: statement, at: 1)
        bind(message.body, to: statement, at: 2)
        sqlite3_bind_int64(statement, 3, message.createdAt)
        bind(message.direction, to: statement, at: 4)
        bind(message.transport, to: statement, at: 5)
        bind(message.conversationId, to: statement, at: 6)
        try step(statement, operation: "Unable to save the message")
        return message
    }

    func recent(conversationId: String? = nil, limit: Int = 200) throws -> [ChatMessage] {
        guard (1...1_000).contains(limit) else {
            throw storeError("History limit must be between 1 and 1,000")
        }
        lock.lock()
        defer { lock.unlock() }
        let sql: String
        if conversationId == nil {
            sql = """
            SELECT messages.id, messages.body, messages.created_at, messages.direction,
                   messages.transport, messages.synced, messages.conversation_id,
                   conversations.name, conversations.type
            FROM messages
            LEFT JOIN conversations ON conversations.id = messages.conversation_id
            ORDER BY messages.created_at DESC LIMIT ?
            """
        } else {
            sql = """
            SELECT messages.id, messages.body, messages.created_at, messages.direction,
                   messages.transport, messages.synced, messages.conversation_id,
                   conversations.name, conversations.type
            FROM messages
            LEFT JOIN conversations ON conversations.id = messages.conversation_id
            WHERE messages.conversation_id = ?
            ORDER BY messages.created_at DESC LIMIT ?
            """
        }
        let statement = try prepare(
            sql
        )
        defer { sqlite3_finalize(statement) }
        if let conversationId {
            bind(conversationId, to: statement, at: 1)
            sqlite3_bind_int(statement, 2, Int32(limit))
        } else {
            sqlite3_bind_int(statement, 1, Int32(limit))
        }
        var messages: [ChatMessage] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            messages.append(readMessage(statement, includesConversation: true))
            result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE else {
            throw storeError("Unable to read local message history")
        }
        return Array(messages.reversed())
    }

    func conversations() throws -> [ChatConversation] {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            "SELECT id, name, type FROM conversations ORDER BY CASE WHEN type = 'direct' THEN 0 ELSE 1 END, name COLLATE NOCASE"
        )
        defer { sqlite3_finalize(statement) }
        var result: [ChatConversation] = []
        var status = sqlite3_step(statement)
        while status == SQLITE_ROW {
            result.append(
                ChatConversation(
                    id: text(statement, column: 0),
                    name: text(statement, column: 1),
                    type: text(statement, column: 2)
                )
            )
            status = sqlite3_step(statement)
        }
        guard status == SQLITE_DONE else {
            throw storeError("Unable to read local conversations")
        }
        return result
    }

    func createConversation(name: String, type: String) throws -> ChatConversation {
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedName.isEmpty, normalizedName.count <= 48 else {
            throw storeError("Conversation name must be between 1 and 48 characters")
        }
        guard type == "group" || type == "channel" else {
            throw storeError("Invalid conversation type")
        }
        let conversation = ChatConversation(id: UUID().uuidString, name: normalizedName, type: type)
        lock.lock()
        defer { lock.unlock() }
        try upsertConversation(conversation)
        return conversation
    }

    func saveConversation(_ conversation: ChatConversation) throws {
        try validate(conversation: conversation, allowDirect: false)
        lock.lock()
        defer { lock.unlock() }
        try upsertConversation(conversation)
    }

    func pendingSync(limit: Int = 50) throws -> [ChatMessage] {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            """
            SELECT messages.id, messages.body, messages.created_at, messages.direction,
                   messages.transport, messages.synced, messages.conversation_id,
                   conversations.name, conversations.type
            FROM messages
            LEFT JOIN conversations ON conversations.id = messages.conversation_id
            WHERE messages.synced = 0
            ORDER BY messages.created_at ASC LIMIT ?
            """
        )
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(limit))
        var messages: [ChatMessage] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            messages.append(readMessage(statement, includesConversation: true))
            result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE else {
            throw storeError("Unable to read messages waiting for sync")
        }
        return messages
    }

    func importRemote(_ message: ChatMessage) throws {
        guard !message.id.isEmpty else {
            throw storeError("Remote message ID cannot be empty")
        }
        guard message.id.count <= 64 else {
            throw storeError("Remote message ID is too long")
        }
        try validate(body: message.body)
        try validate(
            conversation: ChatConversation(
                id: message.conversationId,
                name: message.conversationName,
                type: message.conversationType
            ),
            allowDirect: true
        )
        lock.lock()
        defer { lock.unlock() }
        try upsertConversation(
            ChatConversation(
                id: message.conversationId,
                name: message.conversationName,
                type: message.conversationType
            )
        )
        let statement = try prepare(
            "INSERT OR IGNORE INTO messages (id, body, created_at, direction, transport, synced, conversation_id) VALUES (?, ?, ?, 'incoming', ?, 1, ?)"
        )
        defer { sqlite3_finalize(statement) }
        bind(message.id, to: statement, at: 1)
        bind(message.body, to: statement, at: 2)
        sqlite3_bind_int64(statement, 3, message.createdAt)
        bind(message.transport, to: statement, at: 4)
        bind(message.conversationId, to: statement, at: 5)
        try step(statement, operation: "Unable to import a remote message")
    }

    func markSynced(_ id: String) throws {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare("UPDATE messages SET synced = 1 WHERE id = ?")
        defer { sqlite3_finalize(statement) }
        bind(id, to: statement, at: 1)
        try step(statement, operation: "Unable to mark the message as synced")
        guard sqlite3_changes(database) == 1 else {
            throw storeError("Stored message was not found when marking sync complete")
        }
    }

    private func validate(body: String) throws {
        guard !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !body.contains("\n"), !body.contains("\r") else {
            throw storeError("Messages must be non-empty, single-line text")
        }
        guard body.lengthOfBytes(using: .utf8) <= Self.maximumMessageBytes else {
            throw storeError("Message exceeds the 64 KiB limit")
        }
    }

    private func validate(conversation: ChatConversation, allowDirect: Bool) throws {
        guard !conversation.id.isEmpty, conversation.id.count <= 64,
              !conversation.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              conversation.name.count <= 48 else {
            throw storeError("Conversation ID or name is invalid")
        }
        guard (allowDirect && conversation.type == "direct") ||
                conversation.type == "group" || conversation.type == "channel" else {
            throw storeError("Invalid conversation type")
        }
    }

    private func execute(_ sql: String) throws {
        lock.lock()
        defer { lock.unlock() }
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw storeError("Unable to initialize the local message database")
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw storeError("Unable to prepare a local message database operation")
        }
        return statement
    }

    private func bind(_ value: String, to statement: OpaquePointer, at index: Int32) {
        sqlite3_bind_text(statement, index, (value as NSString).utf8String, -1, Self.transient)
    }

    private func step(_ statement: OpaquePointer, operation: String) throws {
        guard sqlite3_step(statement) == SQLITE_DONE else {
            throw storeError(operation)
        }
    }

    private func readMessage(_ statement: OpaquePointer, includesConversation: Bool = false) -> ChatMessage {
        let conversationId = text(statement, column: 6)
        let conversationName = includesConversation ? text(statement, column: 7) : "Direct"
        let conversationType = includesConversation ? text(statement, column: 8) : "direct"
        return ChatMessage(
            id: text(statement, column: 0),
            body: text(statement, column: 1),
            createdAt: sqlite3_column_int64(statement, 2),
            direction: text(statement, column: 3),
            transport: text(statement, column: 4),
            synced: sqlite3_column_int(statement, 5) != 0,
            conversationId: conversationId,
            conversationName: conversationName,
            conversationType: conversationType
        )
    }

    private func text(_ statement: OpaquePointer, column: Int32) -> String {
        guard let value = sqlite3_column_text(statement, column) else { return "" }
        let characters = UnsafeRawPointer(value).assumingMemoryBound(to: CChar.self)
        return String(cString: characters)
    }

    private func upsertConversation(_ conversation: ChatConversation) throws {
        let statement = try prepare(
            "INSERT INTO conversations (id, name, type) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET name = excluded.name, type = excluded.type"
        )
        defer { sqlite3_finalize(statement) }
        bind(conversation.id, to: statement, at: 1)
        bind(conversation.name, to: statement, at: 2)
        bind(conversation.type, to: statement, at: 3)
        try step(statement, operation: "Unable to save conversation")
    }

    private func messageColumns() throws -> Set<String> {
        let statement = try prepare("PRAGMA table_info(messages)")
        defer { sqlite3_finalize(statement) }
        var columns = Set<String>()
        while sqlite3_step(statement) == SQLITE_ROW {
            columns.insert(text(statement, column: 1))
        }
        return columns
    }

    private func storeError(_ message: String) -> NSError {
        NSError(
            domain: "com.chatz.localchat.store",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }
}
