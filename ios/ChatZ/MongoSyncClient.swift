import Foundation

final class MongoSyncClient {
    private let store: ChatMessageStore
    private let baseURL: URL?
    private let token: String
    private let session: URLSession

    var isConfigured: Bool {
        baseURL != nil && !token.isEmpty
    }

    init(store: ChatMessageStore) {
        self.store = store
        let rawURL = (Bundle.main.object(forInfoDictionaryKey: "CHATZ_SYNC_API_BASE_URL") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        baseURL = URL(string: rawURL).flatMap { url in
            guard let scheme = url.scheme?.lowercased(),
                  ["http", "https"].contains(scheme),
                  url.host != nil else {
                return nil
            }
            return url
        }
        token = Bundle.main.object(forInfoDictionaryKey: "CHATZ_SYNC_API_TOKEN") as? String ?? ""
        session = URLSession(configuration: .ephemeral)
    }

    func sync() async throws -> Int {
        guard let baseURL, !token.isEmpty else {
            throw SyncError.notConfigured
        }
        var uploadedCount = 0
        while true {
            let pending = try store.pendingSync()
            guard !pending.isEmpty else { break }
            for message in pending {
                var request = URLRequest(url: baseURL.appendingPathComponent("api/messages"))
                request.httpMethod = "POST"
                request.timeoutInterval = 10
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONEncoder().encode(APIMessage(message))
                _ = try await checkedData(for: request)
                try store.markSynced(message.id)
                uploadedCount += 1
            }
        }

        var components = URLComponents(
            url: baseURL.appendingPathComponent("api/messages"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "limit", value: "100")]
        guard let messagesURL = components?.url else {
            throw SyncError.invalidURL
        }
        var request = URLRequest(url: messagesURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await checkedData(for: request)
        let messages = try JSONDecoder().decode([APIMessage].self, from: data)
        for remoteMessage in messages {
            try store.importRemote(remoteMessage.asChatMessage)
        }
        return uploadedCount
    }

    private func checkedData(for request: URLRequest) async throws -> (Data, URLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let detail = String(data: data, encoding: .utf8) ?? ""
            throw SyncError.httpFailure(status, detail)
        }
        return (data, response)
    }
}

private enum SyncError: LocalizedError {
    case notConfigured
    case invalidURL
    case httpFailure(Int, String)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "MongoDB sync is not configured. Set CHATZ_SYNC_API_BASE_URL and CHATZ_SYNC_API_TOKEN in the iOS build settings."
        case .invalidURL:
            return "The configured sync API URL is invalid."
        case .httpFailure(let status, let detail):
            return "Sync API returned HTTP \(status): \(detail)"
        }
    }
}

private struct APIMessage: Codable {
    let id: String
    let body: String
    let createdAt: Int64
    let direction: String
    let transport: String
    let conversationId: String?
    let conversationName: String?
    let conversationType: String?

    enum CodingKeys: String, CodingKey {
        case id
        case body
        case createdAt = "created_at"
        case direction
        case transport
        case conversationId = "conversation_id"
        case conversationName = "conversation_name"
        case conversationType = "conversation_type"
    }

    init(_ message: ChatMessage) {
        id = message.id
        body = message.body
        createdAt = message.createdAt
        direction = message.direction
        transport = message.transport
        conversationId = message.conversationId
        conversationName = message.conversationName
        conversationType = message.conversationType
    }

    var asChatMessage: ChatMessage {
        ChatMessage(
            id: id,
            body: body,
            createdAt: createdAt,
            direction: "incoming",
            transport: transport,
            synced: true,
            conversationId: conversationId ?? ChatConversation.direct.id,
            conversationName: conversationName ?? ChatConversation.direct.name,
            conversationType: conversationType ?? ChatConversation.direct.type
        )
    }
}
