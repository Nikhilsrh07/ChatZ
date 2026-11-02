import SwiftUI

@main
struct ChatZApp: App {
    @StateObject private var model = ChatViewModel()

    var body: some Scene {
        WindowGroup {
            ChatView(model: model)
                .onDisappear {
                    model.stop()
                }
        }
    }
}
