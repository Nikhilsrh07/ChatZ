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
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                direction TEXT NOT NULL,
                transport TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        try execute("CREATE INDEX IF NOT EXISTS messages_created_at_idx ON messages(created_at)")
    }

    deinit {
        sqlite3_close(database)
    }

    func insert(body: String, direction: String) throws -> ChatMessage {
        try validate(body: body)
        guard direction == "outgoing" || direction == "incoming" else {
            throw storeError("Invalid message direction")
        }
        let message = ChatMessage(body: body, direction: direction)
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            "INSERT INTO messages (id, body, created_at, direction, transport, synced) VALUES (?, ?, ?, ?, ?, 0)"
        )
        defer { sqlite3_finalize(statement) }
        bind(message.id, to: statement, at: 1)
        bind(message.body, to: statement, at: 2)
        sqlite3_bind_int64(statement, 3, message.createdAt)
        bind(message.direction, to: statement, at: 4)
        bind(message.transport, to: statement, at: 5)
        try step(statement, operation: "Unable to save the message")
        return message
    }

    func recent(limit: Int = 200) throws -> [ChatMessage] {
        guard (1...1_000).contains(limit) else {
            throw storeError("History limit must be between 1 and 1,000")
        }
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            "SELECT id, body, created_at, direction, transport, synced FROM messages ORDER BY created_at DESC LIMIT ?"
        )
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(limit))
        var messages: [ChatMessage] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            messages.append(readMessage(statement))
            result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE else {
            throw storeError("Unable to read local message history")
        }
        return Array(messages.reversed())
    }

    func pendingSync(limit: Int = 50) throws -> [ChatMessage] {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            "SELECT id, body, created_at, direction, transport, synced FROM messages WHERE synced = 0 ORDER BY created_at ASC LIMIT ?"
        )
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(limit))
        var messages: [ChatMessage] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            messages.append(readMessage(statement))
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
        try validate(body: message.body)
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(
            "INSERT OR IGNORE INTO messages (id, body, created_at, direction, transport, synced) VALUES (?, ?, ?, 'incoming', ?, 1)"
        )
        defer { sqlite3_finalize(statement) }
        bind(message.id, to: statement, at: 1)
        bind(message.body, to: statement, at: 2)
        sqlite3_bind_int64(statement, 3, message.createdAt)
        bind(message.transport, to: statement, at: 4)
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

    private func readMessage(_ statement: OpaquePointer) -> ChatMessage {
        func text(_ column: Int32) -> String {
            guard let value = sqlite3_column_text(statement, column) else { return "" }
            let characters = UnsafeRawPointer(value).assumingMemoryBound(to: CChar.self)
            return String(cString: characters)
        }
        return ChatMessage(
            id: text(0),
            body: text(1),
            createdAt: sqlite3_column_int64(statement, 2),
            direction: text(3),
            transport: text(4),
            synced: sqlite3_column_int(statement, 5) != 0
        )
    }

    private func storeError(_ message: String) -> NSError {
        NSError(
            domain: "com.chatz.localchat.store",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }
}
