import Foundation
import SwiftUI

@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
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
            refreshHistory()
        } catch {
            self.store = nil
            status = "Local storage error: \(error.localizedDescription)"
        }

        networkEngine.onMessageReceived = { [weak self] message in
            Task { @MainActor in
                self?.receive(message)
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
            _ = try store.insert(body: body, direction: "outgoing")
            networkEngine.sendMessage(body)
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

    private func receive(_ body: String) {
        guard let store else {
            status = "Received a message, but local storage is unavailable."
            return
        }
        do {
            _ = try store.insert(body: body, direction: "incoming")
            refreshHistory()
            status = "Message received over Wi-Fi / tethered network."
        } catch {
            status = "Unable to save received message: \(error.localizedDescription)"
        }
    }

    private func refreshHistory() {
        guard let store else { return }
        do {
            messages = try store.recent()
        } catch {
            status = "Unable to load message history: \(error.localizedDescription)"
        }
    }
}
