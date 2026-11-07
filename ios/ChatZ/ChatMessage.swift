import Foundation

struct ChatMessage: Identifiable, Equatable {
    let id: String
    let body: String
    let createdAt: Int64
    let direction: String
    let transport: String
    let synced: Bool
    let conversationId: String
    let conversationName: String
    let conversationType: String

    init(
        id: String = UUID().uuidString,
        body: String,
        createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000),
        direction: String,
        transport: String = "Wi-Fi / tethered network",
        synced: Bool = false,
        conversationId: String = "direct",
        conversationName: String = "Direct",
        conversationType: String = "direct"
    ) {
        self.id = id
        self.body = body
        self.createdAt = createdAt
        self.direction = direction
        self.transport = transport
        self.synced = synced
        self.conversationId = conversationId
        self.conversationName = conversationName
        self.conversationType = conversationType
    }
}

struct ChatConversation: Identifiable, Equatable {
    let id: String
    let name: String
    let type: String

    static let direct = ChatConversation(id: "direct", name: "Direct", type: "direct")
}
