import Foundation
import SwiftUI

@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var conversations: [ChatConversation] = [.direct]
    @Published var selectedConversationId = ChatConversation.direct.id {
        didSet { refreshHistory() }
    }
    @Published var status = "Messages are saved on this device. Both peers need the same Wi-Fi or tethered IP network."
    @Published var peerAddress = ""
    @Published var messageText = ""
    @Published private(set) var isSyncConfigured = false

    private let store: ChatMessageStore?
    private let networkEngine = LocalChatEngine()
    private var syncClient: MongoSyncClient?

    init() {
        do {
            let store = try ChatMessageStore()
            self.store = store
            let syncClient = MongoSyncClient(store: store)
            self.syncClient = syncClient
            isSyncConfigured = syncClient.isConfigured
            refreshConversations()
            refreshHistory()
        } catch {
            self.store = nil
            status = "Local storage error: \(error.localizedDescription)"
        }

        networkEngine.onMessageReceived = { [weak self] event in
            Task { @MainActor in
                self?.receive(event)
            }
        }
        networkEngine.onPeerConnected = { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                for conversation in self.conversations where conversation.type != "direct" {
                    self.networkEngine.announceConversation(conversation)
                    if let store = self.store {
                        do {
                            let history = try store.recent(conversationId: conversation.id)
                            history.forEach(self.networkEngine.sendRoomMessage)
                        } catch {
                            self.status = "Unable to share \(conversation.name) history with the mesh: \(error.localizedDescription)"
                        }
                    }
                }
            }
        }
        networkEngine.onError = { [weak self] error in
            Task { @MainActor in
                self?.status = error.localizedDescription
            }
        }
    }

    func startNetworkChat() {
        networkEngine.startServerAndRegister()
        networkEngine.discoverPeers()
        status = "Listening and discovering on TCP port \(LocalChatEngine.serverPort). For tethering, connect using the peer's IP address."
    }

    func connectByIP() {
        let address = peerAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !address.isEmpty else {
            status = "Enter the peer's IP address."
            return
        }
        networkEngine.connectToPeer(address)
        status = "Connecting to \(address):\(LocalChatEngine.serverPort)."
    }

    func sendMessage() {
        let body = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        guard let store else {
            status = "Messages cannot be saved because local storage is unavailable."
            return
        }
        do {
            let conversation = selectedConversation
            let message = try store.insert(
                body: body,
                direction: "outgoing",
                conversation: conversation
            )
            if conversation.type == "direct" {
                networkEngine.sendMessage(body)
            } else {
                networkEngine.sendRoomMessage(message)
            }
            messageText = ""
            refreshHistory()
        } catch {
            status = error.localizedDescription
        }
    }

    func syncMessages() {
        guard let syncClient else {
            status = "Local storage is unavailable; messages cannot be synced."
            return
        }
        guard syncClient.isConfigured else {
            status = "Sync is not configured. Set the sync URL and token in the Xcode build settings."
            return
        }
        status = "Syncing messages with the configured API..."
        Task {
            do {
                let uploaded = try await syncClient.sync()
                refreshHistory()
                status = "Sync complete. Uploaded \(uploaded) pending message(s) and refreshed remote history."
            } catch {
                status = "Sync failed: \(error.localizedDescription)"
            }
        }
    }

    func stop() {
        networkEngine.stop()
    }

    func createConversation(name: String, type: String) {
        guard let store else {
            status = "Local storage is unavailable; a conversation cannot be created."
            return
        }
        do {
            let conversation = try store.createConversation(name: name, type: type)
            refreshConversations()
            selectedConversationId = conversation.id
            networkEngine.announceConversation(conversation)
            status = "\(conversation.name) is visible to peers on the local network."
        } catch {
            status = error.localizedDescription
        }
    }

    private var selectedConversation: ChatConversation {
        conversations.first(where: { $0.id == selectedConversationId }) ?? .direct
    }

    private func receive(_ event: LocalChatEvent) {
        guard let store else {
            status = "Received a message, but local storage is unavailable."
            return
        }
        do {
            if event.kind == "conversation" {
                try store.saveConversation(
                    ChatConversation(
                        id: event.conversationId,
                        name: event.conversationName,
                        type: event.conversationType
                    )
                )
                refreshConversations()
                return
            }
            let conversation = event.conversationType == "direct"
                ? ChatConversation.direct
                : ChatConversation(
                    id: event.conversationId,
                    name: event.conversationName,
                    type: event.conversationType
                )
            _ = try store.insert(
                body: event.body,
                direction: "incoming",
                conversation: conversation,
                id: event.id
            )
            refreshConversations()
            refreshHistory()
            status = "Message received in \(conversation.name)."
        } catch {
            status = "Unable to save received message: \(error.localizedDescription)"
        }
    }

    private func refreshHistory() {
        guard let store else { return }
        do {
            messages = try store.recent(conversationId: selectedConversationId)
        } catch {
            status = "Unable to load message history: \(error.localizedDescription)"
        }
    }

    private func refreshConversations() {
        guard let store else { return }
        do {
            conversations = try store.conversations()
            if !conversations.contains(where: { $0.id == selectedConversationId }) {
                selectedConversationId = ChatConversation.direct.id
            }
        } catch {
            status = "Unable to load conversations: \(error.localizedDescription)"
        }
    }
}
