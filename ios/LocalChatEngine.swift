import Foundation
import Network

public final class LocalChatEngine {
    public var onMessageReceived: ((String) -> Void)?
    public var onError: ((Error) -> Void)?

    private let queue = DispatchQueue(label: "com.chatz.local-chat")
    private let serviceType = "_localchat._tcp"
    private let serviceName = "ChatZ"
    private let maximumMessageBytes = 64 * 1024
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var activeConnection: NWConnection?
    private var receiveBuffer = Data()
    private var stopped = false

    public init() {}

    public func startServerAndRegister() {
        queue.async { [weak self] in
            guard let self, !self.stopped, self.listener == nil else { return }
            do {
                let listener = try NWListener(using: .tcp)
                listener.service = NWListener.Service(name: self.serviceName, type: self.serviceType)
                listener.newConnectionHandler = { [weak self] connection in
                    self?.accept(connection)
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

    public func sendMessage(_ message: String) {
        queue.async { [weak self] in
            guard let self else { return }
            guard !message.contains("\n"), !message.contains("\r") else {
                self.report(LocalChatError.invalidMessage)
                return
            }
            guard let data = message.data(using: .utf8),
                  data.count <= self.maximumMessageBytes else {
                self.report(LocalChatError.messageTooLarge)
                return
            }
            guard let connection = self.activeConnection else {
                self.report(LocalChatError.noPeerConnected)
                return
            }

            var framedMessage = data
            framedMessage.append(0x0A)
            connection.send(content: framedMessage, completion: .contentProcessed { [weak self] error in
                if let error {
                    self?.report(error)
                }
            })
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self, !self.stopped else { return }
            self.stopped = true
            self.listener?.cancel()
            self.browser?.cancel()
            self.activeConnection?.cancel()
            self.listener = nil
            self.browser = nil
            self.activeConnection = nil
            self.receiveBuffer.removeAll()
        }
    }

    private func accept(_ connection: NWConnection) {
        activeConnection?.cancel()
        activeConnection = connection
        receiveBuffer.removeAll()
        start(connection)
    }

    private func connect(to endpoint: NWEndpoint) {
        guard activeConnection == nil else { return }
        let connection = NWConnection(to: endpoint, using: .tcp)
        activeConnection = connection
        start(connection)
    }

    private func start(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.receive(from: connection)
            case .failed(let error):
                self.report(error)
                self.clear(connection)
            case .cancelled:
                self.clear(connection)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(from connection: NWConnection?) {
        guard let connection else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) {
            [weak self, weak connection] content, _, isComplete, error in
            guard let self, let connection else { return }
            if let error {
                self.report(error)
                self.clear(connection)
                return
            }
            if let content {
                self.receiveBuffer.append(content)
                self.consumeCompleteMessages()
            }
            if isComplete {
                self.clear(connection)
            } else {
                self.receive(from: connection)
            }
        }
    }

    private func consumeCompleteMessages() {
        while let delimiter = receiveBuffer.firstIndex(of: 0x0A) {
            let messageData = receiveBuffer[..<delimiter]
            receiveBuffer.removeSubrange(...delimiter)
            guard messageData.count <= maximumMessageBytes,
                  let message = String(data: messageData, encoding: .utf8) else {
                report(LocalChatError.invalidMessage)
                continue
            }
            onMessageReceived?(message)
        }
        if receiveBuffer.count > maximumMessageBytes {
            report(LocalChatError.messageTooLarge)
            activeConnection?.cancel()
            receiveBuffer.removeAll()
        }
    }

    private func clear(_ connection: NWConnection) {
        guard activeConnection === connection else { return }
        activeConnection = nil
        receiveBuffer.removeAll()
    }

    private func report(_ error: Error) {
        onError?(error)
    }
}

private enum LocalChatError: LocalizedError {
    case invalidMessage
    case messageTooLarge
    case noPeerConnected

    var errorDescription: String? {
        switch self {
        case .invalidMessage:
            return "Messages must be valid UTF-8, single-line text."
        case .messageTooLarge:
            return "Message exceeds the 64 KiB limit."
        case .noPeerConnected:
            return "No peer is connected."
        }
    }
}
