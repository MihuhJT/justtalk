import SwiftUI

extension Store {
    func openBot() async {
        do {
            guard let json = try await Api.call("POST", "/bot/chat", body: [:]) as? JSON else { return }
            let chat = Chat(json)
            if !chats.contains(where: { $0.id == chat.id }) { chats.insert(chat, at: 0) }
            open(chat.id)
            if let state = try? await Api.call("GET", "/bot/state/\(chat.id)") as? JSON { botBusy[chat.id] = state["busy"] as? Bool ?? false }
        } catch { toast = error.localizedDescription }
    }
}

struct BotSettingsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var style = "friendly"
    @State private var target = 0
    @State private var loading = true
    @State private var busy = false
    @State private var error: String?
    private let styles = [("friendly", "Свой в компании"), ("calm", "Спокойный"), ("brief", "Коротко"), ("detailed", "Подробно")]
    var body: some View {
        NavigationStack {
            Form {
                Section("Характер JT бота") {
                    Picker("Стиль", selection: $style) {
                        ForEach(styles, id: \.0) { id, title in Text(title).tag(id) }
                    }.pickerStyle(.inline).labelsHidden()
                }
                Section("Куда отправлять карточки") {
                    Picker("Чат", selection: $target) {
                        Text("Только в чат бота").tag(0)
                        ForEach(store.chats.filter { $0.kind != "bot" }) { chat in Text(chat.title).tag(chat.id) }
                    }
                }
                Section { Text("В переписках позови бота через @бот. Он учитывает до 20 предыдущих сообщений этого чата.").foregroundStyle(C.mut) }
                if let error { Text(error).foregroundStyle(C.red) }
            }.scrollContentBackground(.hidden).background(C.bg)
                .navigationTitle("JT бот").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(busy ? "…" : "Сохранить") {
                            busy = true
                            Task {
                                do { try await Api.call("POST", "/bot/settings", body: ["style": style, "target_chat_id": target]); dismiss() }
                                catch { self.error = error.localizedDescription }
                                busy = false
                            }
                        }.disabled(loading || busy)
                    }
                }
                .task {
                    do {
                        let settings = try await Api.call("GET", "/bot/settings") as? JSON ?? [:]
                        style = settings["style"] as? String ?? "friendly"
                        target = settings["target_chat_id"] as? Int ?? 0
                        loading = false
                    } catch { self.error = error.localizedDescription }
                }
        }.preferredColorScheme(.dark)
    }
}
