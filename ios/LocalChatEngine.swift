import Foundation
import Network

public struct LocalChatEvent {
    public let kind: String
    public let id: String
    public let conversationId: String
    public let conversationName: String
    public let conversationType: String
    public let body: String
}

public final class LocalChatEngine {
    public var onMessageReceived: ((LocalChatEvent) -> Void)?
    public var onError: ((Error) -> Void)?
    public var onPeerConnected: (() -> Void)?

    private let queue = DispatchQueue(label: "com.chatz.local-chat")
    private let serviceType = "_localchat._tcp"
    private let serviceName = "ChatZ-\(UUID().uuidString.prefix(8))"
    private let maximumMessageBytes = 64 * 1024
    private let maximumFrameBytes = 256 * 1024
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var connections: [UUID: NWConnection] = [:]
    private var activeConnectionID: UUID?
    private var receiveBuffers: [UUID: Data] = [:]
    private var seenMessageIds = Set<String>()
    private var seenConversationIds = Set<String>()
    private var stopped = false

    public init() {}

    public func startServerAndRegister() {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopped = false
            guard self.listener == nil else { return }
            do {
                guard let port = NWEndpoint.Port(rawValue: Self.serverPort) else {
                    self.report(LocalChatError.invalidPort)
                    return
                }
                let listener = try NWListener(using: .tcp, on: port)
                listener.service = NWListener.Service(name: self.serviceName, type: self.serviceType)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.attach(connection)
                }
                listener.stateUpdateHandler = { [weak self] state in
                    if case .failed(let error) = state {
                        self?.report(error)
                    }
                }
                self.listener = listener
                listener.start(queue: self.queue)
            } catch {
                self.report(error)
            }
        }
    }

    public func discoverPeers() {
        queue.async { [weak self] in
            guard let self, !self.stopped, self.browser == nil else { return }
            let browser = NWBrowser(
                for: .bonjour(type: self.serviceType, domain: nil),
                using: .tcp
            )
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                guard let self else { return }
                for result in results {
                    guard case .service(let name, _, _, _) = result.endpoint,
                          name != self.serviceName else {
                        continue
                    }
                    self.connect(to: result.endpoint)
                }
            }
            browser.stateUpdateHandler = { [weak self] state in
                if case .failed(let error) = state {
                    self?.report(error)
                }
            }
            self.browser = browser
            browser.start(queue: self.queue)
        }
    }

    public func connectToPeer(_ host: String) {
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        queue.async { [weak self] in
            guard let self, !self.stopped else { return }
            guard !address.isEmpty, let port = NWEndpoint.Port(rawValue: Self.serverPort) else {
                self.report(LocalChatError.invalidAddress)
                return
            }
            self.connect(to: .hostPort(host: NWEndpoint.Host(address), port: port))
        }
    }

    public func sendMessage(_ message: String) {
        queue.async { [weak self] in
            guard let self else { return }
            guard !message.isEmpty, !message.contains("\n"), !message.contains("\r") else {
                self.report(LocalChatError.invalidMessage)
                return
            }
            guard let data = message.data(using: .utf8),
                  data.count <= self.maximumMessageBytes else {
                self.report(LocalChatError.messageTooLarge)
                return
            }
            guard let id = self.activeConnectionID, let connection = self.connections[id] else {
                self.report(LocalChatError.noPeerConnected)
                return
            }
            var frame = data
            frame.append(0x0A)
            self.send(data: frame, to: connection)
        }
    }

    func announceConversation(_ conversation: ChatConversation) {
        guard conversation.type != "direct" else { return }
        queue.async { [weak self] in
            guard let self else { return }
            let packet: [String: Any] = [
                "kind": "conversation",
                "id": conversation.id,
                "name": conversation.name,
                "type": conversation.type
            ]
            _ = self.remember(conversation.id, in: &self.seenConversationIds)
            self.broadcast(packet)
        }
    }

    func sendRoomMessage(_ message: ChatMessage) {
        queue.async { [weak self] in
            guard let self else { return }
            _ = self.remember(message.id, in: &self.seenMessageIds)
            let packet: [String: Any] = [
                "kind": "message",
                "id": message.id,
                "conversation_id": message.conversationId,
                "conversation_name": message.conversationName,
                "conversation_type": message.conversationType,
                "body": message.body
            ]
            self.broadcast(packet)
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self, !self.stopped else { return }
            self.stopped = true
            self.listener?.cancel()
            self.browser?.cancel()
            self.connections.values.forEach { $0.cancel() }
            self.listener = nil
            self.browser = nil
            self.connections.removeAll()
            self.receiveBuffers.removeAll()
            self.activeConnectionID = nil
        }
    }

    private func connect(to endpoint: NWEndpoint) {
        guard !stopped else { return }
        let connection = NWConnection(to: endpoint, using: .tcp)
        attach(connection)
    }

    private func attach(_ connection: NWConnection) {
        guard !stopped else {
            connection.cancel()
            return
        }
        let id = UUID()
        connections[id] = connection
        receiveBuffers[id] = Data()
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.activeConnectionID = id
                self.onPeerConnected?()
                self.receive(from: connection, id: id)
            case .failed(let error):
                self.report(error)
                self.clear(id)
            case .cancelled:
                self.clear(id)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(from connection: NWConnection, id: UUID) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
            [weak self, weak connection] content, _, isComplete, error in
            guard let self, let connection else { return }
            if let error {
                self.report(error)
                self.clear(id)
                return
            }
            if let content {
                var buffer = self.receiveBuffers[id, default: Data()]
                buffer.append(content)
                self.receiveBuffers[id] = buffer
                self.consumeCompleteMessages(from: connection, id: id)
            }
            if isComplete {
                if !(self.receiveBuffers[id] ?? Data()).isEmpty {
                    self.report(LocalChatError.invalidMessage)
                }
                self.clear(id)
            } else {
                self.receive(from: connection, id: id)
            }
        }
    }

    private func consumeCompleteMessages(from connection: NWConnection, id: UUID) {
        guard var buffer = receiveBuffers[id] else { return }
        while let delimiter = buffer.firstIndex(of: 0x0A) {
            let frame = Data(buffer[..<delimiter])
            buffer.removeSubrange(...delimiter)
            guard frame.count <= maximumFrameBytes,
                  let line = String(data: frame, encoding: .utf8) else {
                report(LocalChatError.invalidMessage)
                continue
            }
            receiveLine(line, from: connection)
        }
        if buffer.count > maximumFrameBytes {
            report(LocalChatError.messageTooLarge)
            connection.cancel()
            buffer.removeAll()
        }
        receiveBuffers[id] = buffer
    }

    private func receiveLine(_ line: String, from connection: NWConnection) {
        guard line.hasPrefix(Self.packetPrefix) else {
            onMessageReceived?(
                LocalChatEvent(
                    kind: "message",
                    id: UUID().uuidString,
                    conversationId: ChatConversation.direct.id,
                    conversationName: ChatConversation.direct.name,
                    conversationType: ChatConversation.direct.type,
                    body: line
                )
            )
            return
        }
        do {
            let payload = Data(line.dropFirst(Self.packetPrefix.count).utf8)
            guard let packet = try JSONSerialization.jsonObject(with: payload) as? [String: Any],
                  let kind = packet["kind"] as? String,
                  let id = packet["id"] as? String,
                  let name = (packet["name"] ?? packet["conversation_name"]) as? String,
                  let type = (packet["type"] ?? packet["conversation_type"]) as? String else {
                throw LocalChatError.invalidMessage
            }
            let conversationId = kind == "conversation"
                ? id
                : (packet["conversation_id"] as? String ?? "")
            let event = LocalChatEvent(
                kind: kind,
                id: id,
                conversationId: conversationId,
                conversationName: name,
                conversationType: type,
                body: packet["body"] as? String ?? ""
            )
            let isNew: Bool
            switch kind {
            case "conversation":
                isNew = remember(conversationId, in: &seenConversationIds)
            case "message":
                isNew = remember(id, in: &seenMessageIds)
            default:
                throw LocalChatError.invalidMessage
            }
            guard isNew else { return }
            broadcast(packet, excluding: connection)
            onMessageReceived?(event)
        } catch {
            report(error)
        }
    }

    private func broadcast(_ packet: [String: Any], excluding excluded: NWConnection? = nil) {
        do {
            let data = try JSONSerialization.data(withJSONObject: packet, options: [.sortedKeys])
            var frame = Data(Self.packetPrefix.utf8)
            frame.append(data)
            frame.append(0x0A)
            guard frame.count <= maximumFrameBytes else {
                throw LocalChatError.messageTooLarge
            }
            for connection in connections.values where excluded == nil || connection !== excluded {
                send(data: frame, to: connection)
            }
        } catch {
            report(error)
        }
    }

    private func send(data: Data, to connection: NWConnection) {
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.report(error)
            }
        })
    }

    @discardableResult
    private func remember(_ id: String, in values: inout Set<String>) -> Bool {
        guard values.insert(id).inserted else { return false }
        if values.count > 5_000, let first = values.first {
            values.remove(first)
        }
        return true
    }

    private func clear(_ id: UUID) {
        connections.removeValue(forKey: id)
        receiveBuffers.removeValue(forKey: id)
        if activeConnectionID == id {
            activeConnectionID = connections.keys.first
        }
    }

    private func report(_ error: Error) {
        onError?(error)
    }

    public static let serverPort: UInt16 = 45_454
    private static let packetPrefix = "CHATZ1:"
}

private enum LocalChatError: LocalizedError {
    case invalidAddress
    case invalidMessage
    case invalidPort
    case messageTooLarge
    case noPeerConnected

    var errorDescription: String? {
        switch self {
        case .invalidAddress:
            return "Enter the peer's IP address."
        case .invalidMessage:
            return "Received an invalid ChatZ mesh message."
        case .invalidPort:
            return "The chat TCP port is invalid."
        case .messageTooLarge:
            return "Message exceeds the 64 KiB limit."
        case .noPeerConnected:
            return "No peer is connected."
        }
    }
}
