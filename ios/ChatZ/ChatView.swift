import SwiftUI

struct ChatView: View {
    @ObservedObject var model: ChatViewModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                GroupBox("Connection status") {
                    Text(model.status)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .font(.subheadline)
                        .textSelection(.enabled)
                }

                HStack {
                    Button("Start Wi-Fi chat") {
                        model.startNetworkChat()
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Sync") {
                        model.syncMessages()
                    }
                    .buttonStyle(.bordered)
                    Text(model.isSyncConfigured ? "Sync ready" : "Sync off")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack {
                    TextField("Peer IP address (Bluetooth tethering)", text: $model.peerAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .textFieldStyle(.roundedBorder)
                    Button("Connect") {
                        model.connectByIP()
                    }
                    .buttonStyle(.bordered)
                }

                GroupBox("Direct Bluetooth") {
                    Text("iOS does not support Android Bluetooth Classic RFCOMM. Use Wi-Fi or Bluetooth tethering, then connect to the peer's IP address.")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .font(.footnote)
                }

                Divider()
                if model.messages.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "bubble.left.and.bubble.right")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("No messages yet")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 10) {
                                ForEach(model.messages) { message in
                                    messageBubble(message)
                                        .id(message.id)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                        }
                        .onChange(of: model.messages.count) { _ in
                            guard let lastMessage = model.messages.last else { return }
                            proxy.scrollTo(lastMessage.id, anchor: .bottom)
                        }
                    }
                }

                HStack {
                    TextField("Message", text: $model.messageText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(model.sendMessage)
                    Button {
                        model.sendMessage()
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.title2)
                    }
                    .accessibilityLabel("Send message")
                    .disabled(model.messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .padding()
            .navigationTitle("ChatZ")
        }
    }

    private func messageBubble(_ message: ChatMessage) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(message.body)
                .textSelection(.enabled)
            HStack {
                Text(message.direction == "outgoing" ? "You" : "Peer")
                Text(Date(timeIntervalSince1970: TimeInterval(message.createdAt) / 1_000), style: .time)
                if !message.synced {
                    Text("Local")
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(10)
        .background(message.direction == "outgoing" ? Color.blue.opacity(0.12) : Color.gray.opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: .infinity, alignment: message.direction == "outgoing" ? .trailing : .leading)
    }
}
