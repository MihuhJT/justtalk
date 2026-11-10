import UIKit
import SwiftUI
import UniformTypeIdentifiers

@MainActor enum PhoneFiles {
    static var presenter: UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var controller = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let next = controller?.presentedViewController { controller = next }
        return controller
    }
    static func share(_ url: URL) {
        guard let presenter else { return }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = presenter.view
        presenter.present(sheet, animated: true)
    }
}

@MainActor final class FilePickerRequest: NSObject, UIDocumentPickerDelegate {
    var allowsMultipleSelection = false
    var canChooseDirectories = false
    var allowedContentTypes: [UTType] = [.item]
    private var completion: ([URL]) -> Void = { _ in }
    private static var active: FilePickerRequest?
    func present(_ completion: @escaping ([URL]) -> Void) {
        self.completion = completion
        Self.active = self
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: allowedContentTypes, asCopy: true)
        picker.allowsMultipleSelection = allowsMultipleSelection
        picker.delegate = self
        PhoneFiles.presenter?.present(picker, animated: true)
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        // Keep imported files in our sandbox beyond the lifetime of the picker.
        let local = urls.compactMap { url -> URL? in
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
            do { try FileManager.default.copyItem(at: url, to: dest); return dest } catch { return nil }
        }
        completion(local)
        Self.active = nil
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { Self.active = nil }
}

@main struct JustTalkApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var watch = WatchPresentation.shared
    @Environment(\.scenePhase) private var phase
    @AppStorage("newsSeen") private var newsSeen = ""
    @State private var news = false
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onAppear { news = newsSeen != APP_VERSION }
                .sheet(isPresented: $news) {
                    NewsView { newsSeen = APP_VERSION; news = false }
                }
                .overlay { WatchLayer().environmentObject(store) }
                .onChange(of: phase) {
                    store.sendPresence()
                    if phase == .active {
                        Task { await store.refresh() }
                        if let id = store.openChat { store.markRead(id); store.clearNotifications(id) }
                    }
                }
        }
    }
}

struct NewsView: View {
    let close: () -> Void
    private let entries = [
        ("bubble.left.and.bubble.right", "JustTalk на iPhone", "Первая iOS-сборка: вход в существующий аккаунт, чаты, ответы, реакции и поиск сообщений."),
        ("paperclip", "Медиа и карточки", "Фото, файлы, голосовые и кружки. Опросы, сборы и совместный просмотр."),
        ("phone", "Звонки и споты", "Голосовые звонки с панелью управления и карта мест с точным прицелом."),
        ("sparkles", "JT бот", "Личный чат с ботом, вызов через @бот и выбор характера в настройках.")
    ]
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Monogram().frame(width: 90, height: 90)
                Text("Что нового").font(jt(28, .bold))
                Text(APP_VERSION).foregroundStyle(C.mut)
                ForEach(entries, id: \.0) { icon, title, detail in
                    HStack(alignment: .top, spacing: 14) {
                        Image(systemName: icon).foregroundStyle(C.acc).frame(width: 28)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(title).font(jt(17, .semibold))
                            Text(detail).font(jt(14)).foregroundStyle(C.mut)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.padding(16).background(C.s1, in: RoundedRectangle(cornerRadius: 20))
                }
                Button(action: close) { Text("Погнали").font(jt(17, .semibold)).frame(maxWidth: .infinity).padding().background(C.acc, in: Capsule()) }.buttonStyle(.plain)
            }.padding(24)
        }.background(C.bg).foregroundStyle(C.ink).preferredColorScheme(.dark)
    }
}

struct WatchLayer: View {
    @ObservedObject private var watch = WatchPresentation.shared
    @State private var compact = false
    @State private var offset = CGSize.zero
    @State private var savedOffset = CGSize.zero
    @State private var scale: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1
    var body: some View {
        GeometryReader { geometry in
            if let room = watch.room {
                let width = compact ? min(geometry.size.width - 20, max(230, 280 * scale * pinch)) : geometry.size.width
                let height = compact ? width * 0.72 : geometry.size.height
                VStack(spacing: 0) {
                    HStack {
                        Button { compact.toggle(); offset = .zero; savedOffset = .zero } label: {
                            Image(systemName: compact ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left")
                        }.padding(10)
                        Text("Смотрим вместе").font(jt(12)).lineLimit(1)
                        Spacer(minLength: 0)
                        Button { watch.room = nil; compact = false } label: { Image(systemName: "xmark") }.padding(10)
                    }.foregroundStyle(C.ink).background(C.s2)
                    WatchRoom(watchId: room.id, card: room.card)
                }
                .frame(width: width, height: height).background(C.bg)
                .clipShape(RoundedRectangle(cornerRadius: compact ? 18 : 0))
                .shadow(radius: compact ? 14 : 0)
                .offset(offset)
                .simultaneousGesture(DragGesture().onChanged { value in
                    guard compact else { return }
                    offset = CGSize(width: min(0, max(-geometry.size.width + width, savedOffset.width + value.translation.width)), height: min(0, max(-geometry.size.height + height, savedOffset.height + value.translation.height)))
                }.onEnded { _ in savedOffset = offset })
                .simultaneousGesture(MagnificationGesture().updating($pinch) { value, state, _ in if compact { state = value } }.onEnded { value in if compact { scale = min(1.4, max(0.82, scale * value)) } })
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
    }
}
