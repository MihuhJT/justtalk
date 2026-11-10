import AppKit
import AVKit
import SwiftUI

// Phone's "Чёрная" palette + "Фиолетовый" accent.
enum C {
    static let bg = Color(hex: 0x000000)
    static let s1 = Color(hex: 0x131315)
    static let s2 = Color(hex: 0x1F1F22)
    static let ink = Color(hex: 0xF4F4F6)
    static let mut = Color(hex: 0x8B8B93)
    static let line = Color.white.opacity(0.07)
    static let acc = Color(hex: 0x7366F0)
    static let green = Color(hex: 0x34C46A)
    static let red = Color(hex: 0xFF6B6B)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

func jt(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
    .custom("Onest", size: size).weight(weight)
}

let REACTIONS = ["👍", "❤️", "😂", "🔥", "😮", "😢"]

// ---------- images ----------

/// Media files never change, so a memory cache on top of URLCache is enough.
@MainActor
final class Images {
    static var cache: [String: NSImage] = [:]

    static func load(_ name: String) async -> NSImage? {
        if let i = cache[name] { return i }
        guard let (data, _) = try? await URLSession.shared.data(from: Api.mediaURL(name)), let img = NSImage(data: data) else { return nil }
        cache[name] = img
        return img
    }
}

struct RemoteImage: View {
    let name: String
    var fill = true
    @State private var img: NSImage?

    var body: some View {
        ZStack {
            if let img {
                Image(nsImage: img).resizable().aspectRatio(contentMode: fill ? .fill : .fit)
            } else {
                C.s2
            }
        }
        .task(id: name) { img = await Images.load(name) }
    }
}

struct Avatar: View {
    let name: String
    let photo: String
    var size: CGFloat = 44
    var online = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if photo.isEmpty {
                    ZStack {
                        C.s2
                        Text(String(name.first ?? "?").uppercased()).font(jt(size * 0.42, .semibold)).foregroundStyle(C.ink)
                    }
                } else {
                    RemoteImage(name: photo)
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            if online {
                Circle().fill(C.green).frame(width: size * 0.28, height: size * 0.28)
                    .overlay(Circle().stroke(C.bg, lineWidth: 2.5))
            }
        }
    }
}

// ---------- login ----------

struct LoginView: View {
    @EnvironmentObject var store: Store
    @State private var registerMode = false
    @State private var username = ""
    @State private var password = ""
    @State private var invite = ""
    @State private var name = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        VStack(spacing: 18) {
            Monogram().frame(width: 84, height: 84)
            Text("JustTalk").font(jt(28, .bold)).foregroundStyle(C.ink)
            Picker("", selection: $registerMode) {
                Text("Вход").tag(false)
                Text("Новый аккаунт").tag(true)
            }
            .pickerStyle(.segmented).labelsHidden().frame(width: 280)
            VStack(spacing: 10) {
                if registerMode {
                    Field("Код приглашения", text: $invite)
                    Field("Имя", text: $name)
                    Field("@юзернейм", text: $username)
                } else {
                    Field("@юзернейм", text: $username)
                    Field("Пароль", text: $password, secure: true)
                }
            }
            .frame(width: 300)
            if let error { Text(error).font(jt(13)).foregroundStyle(C.red).multilineTextAlignment(.center) }
            Button(action: submit) {
                Text(busy ? "…" : (registerMode ? "Создать" : "Войти")).font(jt(15, .semibold)).foregroundStyle(.white)
                    .frame(width: 300, height: 42).background(C.acc, in: Capsule())
            }
            .buttonStyle(.plain).keyboardShortcut(.defaultAction).disabled(busy)
            Text(registerMode ? "Код даёт создатель JT" : "Пароль задаётся в приложении на телефоне: Профиль → Сменить пароль")
                .font(jt(12)).foregroundStyle(C.mut).multilineTextAlignment(.center).frame(width: 300)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(C.bg)
        .onChange(of: registerMode) { error = nil }
    }

    private func submit() {
        busy = true
        error = nil
        Task {
            do {
                if registerMode {
                    try await store.register(invite: invite, name: name, username: username)
                } else {
                    try await store.login(username: username, password: password)
                }
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

struct Field: View {
    let title: String
    @Binding var text: String
    var secure = false

    init(_ title: String, text: Binding<String>, secure: Bool = false) {
        self.title = title
        _text = text
        self.secure = secure
    }

    var body: some View {
        Group {
            if secure { SecureField(title, text: $text) } else { TextField(title, text: $text) }
        }
        .textFieldStyle(.plain).font(jt(15)).foregroundStyle(C.ink)
        .padding(.horizontal, 16).frame(height: 42)
        .background(C.s1, in: Capsule())
        .overlay(Capsule().stroke(C.line))
    }
}

/// The JT monogram: T bar turning into a J hook, accent dot.
struct Monogram: View {
    var body: some View {
        Canvas { ctx, size in
            let k = min(size.width, size.height) / 100
            ctx.translateBy(x: 1 * k, y: 0.5 * k)
            ctx.fill(Path(roundedRect: CGRect(x: 22 * k, y: 22 * k, width: 56 * k, height: 14 * k), cornerRadius: 7 * k), with: .color(.white))
            var stem = Path()
            stem.move(to: CGPoint(x: 50 * k, y: 30 * k))
            stem.addLine(to: CGPoint(x: 50 * k, y: 60 * k))
            stem.addArc(center: CGPoint(x: 36 * k, y: 60 * k), radius: 14 * k, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
            ctx.stroke(stem, with: .color(.white), style: StrokeStyle(lineWidth: 14 * k, lineCap: .round))
            ctx.fill(Path(ellipseIn: CGRect(x: 63 * k, y: 57 * k, width: 20 * k, height: 20 * k)), with: .color(C.acc))
        }
    }
}

/// First run after registration: the account lives only in this Mac until it has a password.
struct SetPasswordView: View {
    @EnvironmentObject var store: Store
    @State private var p1 = ""
    @State private var p2 = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 14) {
            Text("Придумай пароль").font(jt(20, .bold)).foregroundStyle(C.ink)
            Text("С ним можно войти в @\(store.me?.username ?? "") на телефоне или другом Маке")
                .font(jt(13)).foregroundStyle(C.mut).multilineTextAlignment(.center)
            Field("Пароль, минимум 6 символов", text: $p1, secure: true)
            Field("Ещё раз", text: $p2, secure: true)
            if let error { Text(error).font(jt(13)).foregroundStyle(C.red) }
            Button {
                guard p1 == p2 else { error = "Пароли не совпадают"; return }
                Task {
                    do { try await store.setPassword(p1) } catch { self.error = error.localizedDescription }
                }
            } label: {
                Text("Сохранить").font(jt(15, .semibold)).foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 42)
                    .background(C.acc, in: Capsule())
            }
            .buttonStyle(.plain).keyboardShortcut(.defaultAction)
        }
        .padding(28).frame(width: 360).background(C.bg)
    }
}

// ---------- main window ----------

struct RootView: View {
    @EnvironmentObject var store: Store

    var body: some View {
        Group {
            if store.loggedIn {
                MainView()
                    .sheet(isPresented: .constant(store.me?.hasPassword == false)) { SetPasswordView() }
            } else {
                LoginView()
            }
        }
        .preferredColorScheme(.dark)
        .tint(C.acc)
        .overlay(alignment: .top) { if store.loggedIn { CallOverlay() } }
        .overlay(alignment: .bottom) {
            if let t = store.toast {
                Text(t).font(jt(13)).foregroundStyle(C.ink).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(C.s2, in: Capsule()).padding(.bottom, 90)
                    .onTapGesture { store.toast = nil }
                    .task(id: t) {
                        try? await Task.sleep(for: .seconds(4))
                        if store.toast == t { store.toast = nil }
                    }
            }
        }
    }
}

struct MainView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var newChat = false
    @State private var profile = false
    @State private var newGroup = false

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Button { profile = true } label: {
                        Avatar(name: store.me?.name ?? "", photo: store.me?.avatar ?? "", size: 30)
                    }
                    .buttonStyle(.plain).help("Профиль")
                    Text(store.tab == "spots" ? "Споты" : "Чаты").font(jt(20, .bold)).foregroundStyle(C.ink)
                    if !store.connected {
                        ProgressView().controlSize(.small).help("Подключаюсь…")
                    }
                    Spacer()
                    Menu {
                        Button("Новый чат") { newChat = true }.keyboardShortcut("n")
                        Button("Новая группа") { newGroup = true }.keyboardShortcut("n", modifiers: [.command, .shift])
                    } label: {
                        Image(systemName: "square.and.pencil").font(.system(size: 15, weight: .medium)).foregroundStyle(C.ink)
                            .frame(width: 30, height: 30).background(C.s2, in: Circle())
                    }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 30).help("Новый чат или группа")
                }
                .padding(.horizontal, 14).padding(.top, 6).padding(.bottom, 10)
                Picker("", selection: $store.tab) {
                    Text("Чаты").tag("chats")
                    Text("Споты").tag("spots")
                }
                .pickerStyle(.segmented).labelsHidden().padding(.horizontal, 12).padding(.bottom, 8)
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(C.mut)
                    TextField("Поиск", text: $query).textFieldStyle(.plain).font(jt(14))
                }
                .padding(.horizontal, 12).frame(height: 32).background(C.s1, in: Capsule())
                .padding(.horizontal, 12).padding(.bottom, 8)
                List(selection: Binding(get: { store.openChat }, set: { store.open($0) })) {
                    ForEach(filtered) { c in
                        ChatRow(chat: c).tag(c.id)
                            .listRowInsets(EdgeInsets(top: 2, leading: 6, bottom: 2, trailing: 6))
                            .contextMenu { ChatMenu(chat: c) }
                    }
                }
                .listStyle(.sidebar)
                .scrollContentBackground(.hidden)
                .overlay {
                    if store.chats.isEmpty {
                        Text("Пока нет чатов.\nНажми ✎, чтобы найти кента").font(jt(13)).foregroundStyle(C.mut).multilineTextAlignment(.center)
                    }
                }
            }
            .background(C.bg)
            .navigationSplitViewColumnWidth(min: 260, ideal: 320, max: 420)
        } detail: {
            if store.tab == "spots" {
                SpotsView()
            } else if let id = store.openChat, store.chat(id) != nil {
                ChatView(chatId: id).id(id)
            } else {
                VStack(spacing: 12) {
                    Monogram().frame(width: 64, height: 64).opacity(0.5)
                    Text("Выбери чат").font(jt(15)).foregroundStyle(C.mut)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity).background(C.bg)
            }
        }
        .sheet(isPresented: $newChat) { NewChatView() }
        .sheet(isPresented: $profile) { ProfileView() }
        .sheet(isPresented: $newGroup) { NewGroupSheet() }
        .onChange(of: store.openChat) { if store.openChat != nil { store.tab = "chats" } }
    }

    private var filtered: [Chat] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return store.chats }
        return store.chats.filter { c in
            c.title.lowercased().contains(q) || c.members.contains { $0.username.hasPrefix(q.trimmingCharacters(in: ["@"])) && $0.id != store.meId }
        }
    }
}

struct ChatMenu: View {
    @EnvironmentObject var store: Store
    let chat: Chat

    var body: some View {
        Button(chat.pinned > 0 ? "Открепить" : "Закрепить") { Task { await store.setPinned(chat.id, chat.pinned == 0) } }
        Button(chat.muted ? "Включить уведомления" : "Без звука") { Task { await store.setMuted(chat.id, !chat.muted) } }
        Divider()
        if chat.isGroup {
            Button("Выйти из группы", role: .destructive) { Task { try? await store.leaveGroup(chat.id) } }
        } else {
            Button("Удалить переписку у себя", role: .destructive) { Task { await store.clearChat(chat.id) } }
        }
    }
}

struct ChatRow: View {
    @EnvironmentObject var store: Store
    let chat: Chat

    var body: some View {
        let other = chat.isGroup ? nil : chat.other(store.meId)
        HStack(spacing: 12) {
            Avatar(name: chat.title, photo: chat.isGroup ? chat.avatar : (other?.avatar ?? ""), size: 46,
                   online: other.map { store.presence[$0.id]?.0 ?? false } ?? false)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(chat.title).font(jt(15, .semibold)).foregroundStyle(C.ink).lineLimit(1)
                    if other?.isOwner == true {
                        Text("JT").font(jt(10, .bold)).foregroundStyle(.white).padding(.horizontal, 5).background(C.acc, in: Capsule())
                    }
                    if chat.muted { Image(systemName: "bell.slash.fill").font(.system(size: 10)).foregroundStyle(C.mut) }
                    if chat.pinned > 0 { Image(systemName: "pin.fill").font(.system(size: 10)).foregroundStyle(C.mut) }
                    Spacer(minLength: 4)
                    if let m = chat.last { Text(shortTime(m.created)).font(jt(12)).foregroundStyle(C.mut) }
                }
                HStack(spacing: 6) {
                    Text(lastLine).font(jt(13)).foregroundStyle(store.typingLine(chat.id) != nil ? C.acc : C.mut).lineLimit(1)
                    Spacer(minLength: 4)
                    if chat.unread > 0 {
                        Text(chat.unread > 99 ? "99+" : "\(chat.unread)").font(jt(11, .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 6).frame(minWidth: 20, minHeight: 20)
                            .background(chat.muted ? C.mut : C.acc, in: Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 6).padding(.horizontal, 4)
    }

    private var lastLine: String {
        if let t = store.typingLine(chat.id) { return t }
        guard let m = chat.last else { return "Нет сообщений" }
        if m.senderId == store.meId { return "Ты: " + m.preview }
        if chat.isGroup, m.senderId != 0, let u = store.user(m.senderId) { return "\(u.name): \(m.preview)" }
        return m.preview
    }
}

func shortTime(_ t: Int) -> String {
    let d = Date(timeIntervalSince1970: TimeInterval(t))
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    if Calendar.current.isDateInToday(d) { f.dateFormat = "HH:mm" }
    else if Calendar.current.isDateInYesterday(d) { return "вчера" }
    else if Calendar.current.component(.year, from: d) == Calendar.current.component(.year, from: Date()) { f.dateFormat = "d MMM" }
    else { f.dateFormat = "dd.MM.yy" }
    return f.string(from: d)
}

func dayTitle(_ t: Int) -> String {
    let d = Date(timeIntervalSince1970: TimeInterval(t))
    if Calendar.current.isDateInToday(d) { return "Сегодня" }
    if Calendar.current.isDateInYesterday(d) { return "Вчера" }
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    f.dateFormat = Calendar.current.component(.year, from: d) == Calendar.current.component(.year, from: Date()) ? "d MMMM" : "d MMMM yyyy"
    return f.string(from: d)
}

// ---------- chat ----------

struct ChatView: View {
    @EnvironmentObject var store: Store
    let chatId: Int
    @State private var text = ""
    @State private var replyTo: Msg?
    @State private var editing: Msg?
    @State private var pasted: NSImage?
    @State private var dropping = false
    @State private var viewer: String?
    @State private var circle: Msg?
    /// poll / gather / split / watch / record / info
    @State private var sheet: String?
    @State private var forwarding: Msg?
    @State private var searching = false
    @State private var query = ""
    @State private var found: [Msg] = []
    @State private var atBottom = true
    @State private var unseen = 0
    @StateObject private var voice = VoiceRecorder()
    @FocusState private var focused: Bool

    var body: some View {
        let chat = store.chat(chatId)!
        VStack(spacing: 0) {
            header(chat)
            if searching { searchBar }
            Divider().overlay(C.line)
            messageList(chat)
            composer
        }
        .background(C.bg)
        .onDrop(of: [.fileURL, .image], isTargeted: $dropping) { providers in
            drop(providers)
            return true
        }
        .overlay {
            if dropping {
                RoundedRectangle(cornerRadius: 16).stroke(C.acc, style: StrokeStyle(lineWidth: 2, dash: [8]))
                    .background(C.acc.opacity(0.08)).padding(10)
                    .overlay(Text("Отправить в чат").font(jt(16, .semibold)).foregroundStyle(C.ink))
                    .allowsHitTesting(false)
            }
        }
        .sheet(item: Binding(get: { viewer.map(Ident.init) }, set: { viewer = $0?.id })) { v in PhotoViewer(name: v.id) }
        .sheet(item: $circle) { m in CircleViewer(msg: m) }
        .sheet(item: $forwarding) { m in ForwardSheet(msg: m) }
        .sheet(item: Binding(get: { sheet.map(Ident.init) }, set: { sheet = $0?.id })) { s in
            switch s.id {
            case "poll": NewPollSheet(chatId: chatId)
            case "gather": NewGatherSheet(chatId: chatId)
            case "split": NewSplitSheet(chatId: chatId)
            case "watch": NewWatchSheet(chatId: chatId)
            case "record": CircleRecorder(chatId: chatId)
            default:
                if chat.isGroup { GroupInfoSheet(chatId: chatId) }
                else if let o = chat.other(store.meId) { PersonSheet(user: o) }
            }
        }
        .onAppear {
            focused = true
            store.clearNotifications(chatId)
        }
        .onReceive(NotificationCenter.default.publisher(for: .pasteImage)) { n in
            if let img = n.object as? NSImage { pasted = img }
        }
    }

    private func header(_ chat: Chat) -> some View {
        let other = chat.isGroup ? nil : chat.other(store.meId)
        let sub = store.typingLine(chatId)
            ?? (chat.isGroup ? "\(chat.members.count) участн." : (other.map { store.presenceLine($0.id) } ?? ""))
        return HStack(spacing: 12) {
            HStack(spacing: 12) {
            Avatar(name: chat.title, photo: chat.isGroup ? chat.avatar : (other?.avatar ?? ""), size: 36,
                   online: other.map { store.presence[$0.id]?.0 ?? false } ?? false)
            VStack(alignment: .leading, spacing: 1) {
                Text(chat.title).font(jt(15, .semibold)).foregroundStyle(C.ink)
                if !sub.isEmpty {
                    Text(sub).font(jt(12)).foregroundStyle(store.typingLine(chatId) != nil || sub == "в сети" ? C.acc : C.mut)
                }
            }
            }
            .contentShape(Rectangle()).onTapGesture { sheet = "info" }
            Spacer()
            if let o = other {
                Text(o.handle).font(jt(12, o.isOwner ? .bold : .regular)).foregroundStyle(o.isOwner ? C.acc : C.mut)
            }
            headerButton("magnifyingglass", "Поиск в чате") { searching.toggle(); query = ""; found = [] }
            headerButton("phone.fill", "Позвонить") { Calls.shared.start(chatId) }
            headerButton(chat.isGroup ? "person.3.fill" : "info.circle", chat.isGroup ? "Группа" : "Профиль") { sheet = "info" }
        }
        .padding(.horizontal, 18).frame(height: 56)
    }

    private func headerButton(_ icon: String, _ help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 14, weight: .medium)).foregroundStyle(C.ink)
                .frame(width: 32, height: 32).background(C.s1, in: Circle())
        }
        .buttonStyle(.plain).help(help)
    }

    private var searchBar: some View {
        VStack(spacing: 4) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(C.mut)
                TextField("Искать в чате", text: $query).textFieldStyle(.plain).font(jt(14))
                Button("Закрыть") { searching = false; found = [] }.buttonStyle(.plain).foregroundStyle(C.acc)
            }
            .padding(.horizontal, 14).frame(height: 34).background(C.s1, in: Capsule())
            if !found.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(found) { m in
                            Button { jump(m.id) } label: {
                                HStack {
                                    Text(store.user(m.senderId)?.name ?? "").font(jt(12, .semibold)).foregroundStyle(C.acc)
                                    Text(m.preview).font(jt(13)).foregroundStyle(C.ink).lineLimit(1)
                                    Spacer()
                                    Text(shortTime(m.created)).font(jt(11)).foregroundStyle(C.mut)
                                }
                                .padding(.vertical, 5).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxHeight: 160)
            } else if !query.isEmpty {
                Text("Ничего не нашлось").font(jt(12)).foregroundStyle(C.mut)
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 8)
        .task(id: query) {
            guard !query.isEmpty else { found = []; return }
            try? await Task.sleep(for: .milliseconds(300))
            found = await store.searchIn(chatId, query)
        }
    }

    /// Search result: load older history until the message is there, then scroll to it.
    private func jump(_ id: Int) {
        Task {
            var tries = 0
            while store.messages[chatId]?.contains(where: { $0.id == id }) != true && !store.historyDone.contains(chatId) && tries < 30 {
                await store.loadHistory(chatId)
                tries += 1
            }
            NotificationCenter.default.post(name: .jumpTo, object: id)
        }
    }

    private func messageList(_ chat: Chat) -> some View {
        let list = store.messages[chatId] ?? []
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 2) {
                    if !store.historyDone.contains(chatId) && !list.isEmpty {
                        ProgressView().controlSize(.small).padding(10)
                            .onAppear {
                                let first = list.first?.id
                                Task {
                                    await store.loadHistory(chatId)
                                    if let first { proxy.scrollTo(first, anchor: .top) }
                                }
                            }
                    }
                    ForEach(Array(list.enumerated()), id: \.element.id) { i, m in
                        item(m, prev: i > 0 ? list[i - 1] : nil, chat: chat, proxy: proxy)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: list.last?.id) {
                guard let last = list.last else { return }
                if atBottom || last.senderId == store.meId {
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
                } else {
                    unseen += 1
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .jumpTo)) { n in
                if let id = n.object as? Int { withAnimation { proxy.scrollTo(id, anchor: .center) } }
            }
            .overlay(alignment: .bottomTrailing) {
                if !atBottom {
                    Button {
                        if let id = list.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                        unseen = 0
                    } label: {
                        Image(systemName: "chevron.down").font(.system(size: 14, weight: .bold)).foregroundStyle(C.ink)
                            .frame(width: 38, height: 38).background(C.s2, in: Circle())
                            .overlay(alignment: .topTrailing) {
                                if unseen > 0 {
                                    Text("\(unseen)").font(jt(10, .bold)).foregroundStyle(.white).padding(.horizontal, 5)
                                        .background(C.acc, in: Capsule()).offset(x: 4, y: -4)
                                }
                            }
                    }
                    .buttonStyle(.plain).padding(16)
                }
            }
            .overlay {
                if store.messages[chatId] == nil { ProgressView() }
                else if list.isEmpty { Text("Напиши первым 👋").font(jt(14)).foregroundStyle(C.mut) }
            }
        }
    }

    @ViewBuilder
    private func item(_ m: Msg, prev: Msg?, chat: Chat, proxy: ScrollViewProxy) -> some View {
        if prev.map({ !sameDay($0.created, m.created) }) ?? true {
            Text(dayTitle(m.created)).font(jt(12, .medium)).foregroundStyle(C.mut)
                .padding(.horizontal, 12).padding(.vertical, 4).background(C.s1, in: Capsule()).padding(.vertical, 8)
        }
        MessageRow(msg: m, chat: chat, showName: chat.isGroup && prev?.senderId != m.senderId,
                   onReply: { replyTo = $0; editing = nil; focused = true },
                   onEdit: { editing = $0; replyTo = nil; text = $0.text; focused = true },
                   onPhoto: { viewer = $0 }, onCircle: { circle = $0 },
                   onQuote: { id in jump(id) }, onForward: { forwarding = $0 })
            .id(m.id)
            .onAppear { if m.id == store.messages[chatId]?.last?.id { atBottom = true; unseen = 0 } }
            .onDisappear { if m.id == store.messages[chatId]?.last?.id { atBottom = false } }
    }

    private func sameDay(_ a: Int, _ b: Int) -> Bool {
        Calendar.current.isDate(Date(timeIntervalSince1970: TimeInterval(a)), inSameDayAs: Date(timeIntervalSince1970: TimeInterval(b)))
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if let r = replyTo ?? editing {
                HStack(spacing: 10) {
                    Rectangle().fill(C.acc).frame(width: 3, height: 32).clipShape(Capsule())
                    VStack(alignment: .leading, spacing: 1) {
                        Text(editing != nil ? "Изменить" : (store.user(r.senderId)?.name ?? "Ответ")).font(jt(12, .semibold)).foregroundStyle(C.acc)
                        Text(r.preview).font(jt(12)).foregroundStyle(C.mut).lineLimit(1)
                    }
                    Spacer()
                    Button {
                        if editing != nil { text = "" }
                        replyTo = nil; editing = nil
                    } label: { Image(systemName: "xmark").foregroundStyle(C.mut) }
                        .buttonStyle(.plain).keyboardShortcut(.cancelAction)
                }
                .padding(.horizontal, 18).padding(.top, 8)
            }
            if let img = pasted {
                HStack(spacing: 10) {
                    Image(nsImage: img).resizable().aspectRatio(contentMode: .fill).frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    Text("Фото из буфера — Enter, чтобы отправить").font(jt(12)).foregroundStyle(C.mut)
                    Spacer()
                    Button { pasted = nil } label: { Image(systemName: "xmark").foregroundStyle(C.mut) }.buttonStyle(.plain)
                }
                .padding(.horizontal, 18).padding(.top, 8)
            }
            HStack(alignment: .bottom, spacing: 10) {
                Menu {
                    Button("Фото или файл…", action: pickFile)
                    Button("Кружок…") { sheet = "record" }
                    Divider()
                    Button("📊 Опрос") { sheet = "poll" }
                    Button("📅 Сбор") { sheet = "gather" }
                    Button("💸 Скидываемся") { sheet = "split" }
                    Button("🎬 Смотрим вместе") { sheet = "watch" }
                } label: {
                    Image(systemName: "plus").font(.system(size: 16, weight: .semibold)).foregroundStyle(C.mut)
                        .frame(width: 36, height: 36).background(C.s1, in: Circle())
                }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).frame(width: 36, height: 36).help("Прикрепить")
                TextField(pasted != nil ? "Подпись" : "Сообщение", text: $text, axis: .vertical)
                    .textFieldStyle(.plain).font(jt(15)).foregroundStyle(C.ink).lineLimit(1...8)
                    .focused($focused)
                    .padding(.horizontal, 16).padding(.vertical, 9)
                    .background(C.s1, in: RoundedRectangle(cornerRadius: 18))
                    .onKeyPress(.return, phases: .down) { press in
                        if press.modifiers.contains(.shift) || press.modifiers.contains(.option) { return .ignored }
                        submit()
                        return .handled
                    }
                    .onChange(of: text) { if !text.isEmpty && editing == nil { store.typingNow(chatId) } }
                if canSend || editing != nil {
                    Button(action: submit) {
                        Image(systemName: editing != nil ? "checkmark" : "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(C.acc, in: Circle())
                    }
                    .buttonStyle(.plain)
                } else {
                    Button { voice.start() } label: {
                        Image(systemName: "mic.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(C.ink)
                            .frame(width: 36, height: 36).background(C.s2, in: Circle())
                    }
                    .buttonStyle(.plain).help("Голосовое")
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .overlay {
                if voice.recording {
                    HStack(spacing: 12) {
                        Circle().fill(C.red).frame(width: 10, height: 10)
                        Text("Запись " + durationLabel(Int(voice.elapsed * 1000))).font(jt(14)).foregroundStyle(C.ink).monospacedDigit()
                        Spacer()
                        Button("Отмена") { _ = voice.stop(send: false) }.keyboardShortcut(.cancelAction)
                        Button { finishVoice() } label: {
                            Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 36, height: 36).background(C.acc, in: Circle())
                        }
                        .buttonStyle(.plain).keyboardShortcut(.defaultAction)
                    }
                    .padding(.horizontal, 18).frame(maxWidth: .infinity, maxHeight: .infinity).background(C.bg)
                    .onAppear { voice.onLimit = finishVoice }
                }
            }
        }
    }

    private func finishVoice() {
        guard let (url, ms, wave) = voice.stop(send: true) else { return }
        let r = replyTo?.id ?? 0
        replyTo = nil
        Task { await store.sendVoice(chatId, url, ms: ms, wave: wave, replyTo: r) }
    }

    private var canSend: Bool { pasted != nil || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private func submit() {
        if let img = pasted {
            let caption = text
            pasted = nil
            clearText()
            Task { await store.sendPhoto(chatId, img, caption: caption) }
            return
        }
        guard canSend else { return }
        if let e = editing {
            store.edit(e, text.trimmingCharacters(in: .whitespacesAndNewlines))
            editing = nil
        } else {
            store.send(chatId, text, replyTo: replyTo?.id ?? 0)
            replyTo = nil
        }
        clearText()
    }

    // the multiline field ignores a reset made while it handles the key, so clear on the next run loop
    private func clearText() {
        text = ""
        DispatchQueue.main.async { text = "" }
    }

    private func pickFile() {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.canChooseDirectories = false
        guard p.runModal() == .OK else { return }
        let urls = p.urls
        Task { for u in urls { await store.sendFile(chatId, u) } }
    }

    private func drop(_ providers: [NSItemProvider]) {
        for p in providers {
            if p.canLoadObject(ofClass: URL.self) {
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    guard let url, url.isFileURL else { return }
                    Task { @MainActor in await store.sendFile(chatId, url) }
                }
            } else if p.canLoadObject(ofClass: NSImage.self) {
                _ = p.loadObject(ofClass: NSImage.self) { img, _ in
                    guard let img = img as? NSImage else { return }
                    Task { @MainActor in pasted = img }
                }
            }
        }
    }
}

struct Ident: Identifiable { let id: String }

extension Notification.Name {
    static let pasteImage = Notification.Name("jt.pasteImage")
    static let jumpTo = Notification.Name("jt.jumpTo")
}

// ---------- one message ----------

struct MessageRow: View {
    @EnvironmentObject var store: Store
    let msg: Msg
    let chat: Chat
    let showName: Bool
    let onReply: (Msg) -> Void
    let onEdit: (Msg) -> Void
    let onPhoto: (String) -> Void
    let onCircle: (Msg) -> Void
    let onQuote: (Int) -> Void
    let onForward: (Msg) -> Void

    private var mine: Bool { msg.senderId == store.meId }

    var body: some View {
        if msg.senderId == 0 {
            Text(msg.text).font(jt(12)).foregroundStyle(C.mut).multilineTextAlignment(.center)
                .frame(maxWidth: .infinity).padding(.vertical, 6)
        } else {
            HStack(alignment: .bottom, spacing: 8) {
                if mine { Spacer(minLength: 80) }
                if chat.isGroup && !mine {
                    if showName, let u = store.user(msg.senderId) { Avatar(name: u.name, photo: u.avatar, size: 28) }
                    else { Color.clear.frame(width: 28, height: 1) }
                }
                VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                    bubble
                    if !msg.reactions.isEmpty { reactions }
                }
                if !mine { Spacer(minLength: 80) }
            }
            .padding(.top, showName ? 8 : 1)
            .contextMenu { menu }
        }
    }

    private var bubble: some View {
        BubbleStack(maxWidth: 496) {
            if showName && !mine, let u = store.user(msg.senderId) {
                Text(u.name).font(jt(12, .semibold)).foregroundStyle(u.isOwner ? C.acc : Color(hex: 0xA79EFF))
            }
            if !msg.fwdName.isEmpty {
                Text("Переслано от \(msg.fwdName)").font(jt(12, .medium)).foregroundStyle(mine ? .white.opacity(0.75) : C.acc)
            }
            if let q = msg.quote { quote(q) }
            content
            HStack(spacing: 3) {
                Spacer(minLength: 0)
                if msg.edited { Text("изм.").font(jt(10)) }
                Text(shortClock(msg.created)).font(jt(10))
                if mine { ticks }
            }
            .foregroundStyle(mine ? .white.opacity(0.7) : C.mut)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(mine ? C.acc : C.s2, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .frame(maxWidth: 520, alignment: mine ? .trailing : .leading)
        .opacity(msg.id < 0 ? 0.7 : 1)
    }

    @ViewBuilder private var ticks: some View {
        if msg.id < 0 {
            Image(systemName: "clock").font(.system(size: 9))
        } else {
            Image(systemName: msg.id <= chat.othersRead ? "checkmark.circle.fill" : "checkmark").font(.system(size: 9, weight: .bold))
        }
    }

    private func quote(_ q: Quote) -> some View {
        HStack(spacing: 8) {
            Rectangle().fill(mine ? Color.white : C.acc).frame(width: 3).clipShape(Capsule())
            VStack(alignment: .leading, spacing: 1) {
                Text(store.user(q.senderId)?.name ?? "Кент").font(jt(12, .semibold)).foregroundStyle(mine ? .white : C.acc)
                Text(q.deleted ? "Сообщение удалено" : q.text).font(jt(12)).lineLimit(1)
                    .foregroundStyle(mine ? .white.opacity(0.8) : C.mut)
            }
        }
        .padding(6).frame(maxWidth: .infinity, alignment: .leading)
        .background((mine ? Color.white : C.acc).opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .fixedSize(horizontal: false, vertical: true)
        .onTapGesture { onQuote(q.id) }
    }

    @ViewBuilder private var content: some View {
        if let p = msg.photo {
            let w = Double(max(msg.photoW, 1)), h = Double(max(msg.photoH, 1))
            let width = min(340, w), height = min(420, width * h / w)
            RemoteImage(name: p).frame(width: msg.photoW > 0 ? height * w / h : 280, height: msg.photoW > 0 ? height : 200)
                .clipShape(RoundedRectangle(cornerRadius: 12)).onTapGesture { onPhoto(p) }
        }
        switch msg.mediaKind {
        case "voice": VoiceView(msg: msg, mine: mine)
        case "circle":
            ZStack {
                if !msg.thumb.isEmpty { RemoteImage(name: msg.thumb) } else { C.s1 }
                Image(systemName: "play.fill").font(.system(size: 26)).foregroundStyle(.white).shadow(radius: 4)
            }
            .frame(width: 200, height: 200).clipShape(Circle()).onTapGesture { onCircle(msg) }
        case "file": FileView(msg: msg, mine: mine)
        default: EmptyView()
        }
        if let s = msg.spotTitle {
            Button {
                store.spotFocus = msg.spotId
                store.tab = "spots"
            } label: {
                Label(s, systemImage: "mappin.circle.fill").font(jt(14, .semibold)).foregroundStyle(mine ? .white : C.ink)
            }
            .buttonStyle(.plain).help("Открыть на карте")
        }
        if msg.card != nil {
            // the text of a card message is only a fallback for old apps
            CardView(msg: msg, mine: mine)
        } else if !msg.text.isEmpty && !(msg.mediaKind == "file" && msg.text == msg.mediaName) {
            Text(linked(msg.text)).font(jt(15)).foregroundStyle(mine ? .white : C.ink).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var reactions: some View {
        HStack(spacing: 4) {
            ForEach(msg.reactions, id: \.0) { e, who in
                let me = who.contains(store.meId)
                Button { store.react(msg, e) } label: {
                    HStack(spacing: 3) {
                        Text(e).font(.system(size: 13))
                        if who.count > 1 { Text("\(who.count)").font(jt(12, .semibold)) }
                    }
                    .foregroundStyle(C.ink).padding(.horizontal, 8).frame(height: 24)
                    .background(me ? C.acc.opacity(0.35) : C.s1, in: Capsule())
                    .overlay(Capsule().stroke(me ? C.acc : C.line))
                }
                .buttonStyle(.plain)
                .help(who.compactMap { store.user($0)?.name }.joined(separator: ", "))
            }
        }
    }

    @ViewBuilder private var menu: some View {
        if msg.id > 0 {
            Button("Ответить") { onReply(msg) }
            if msg.card == nil { Button("Переслать") { onForward(msg) } }
            Menu("Реакция") {
                ForEach(REACTIONS, id: \.self) { e in Button(e) { store.react(msg, e) } }
            }
        }
        if !msg.text.isEmpty {
            Button("Копировать текст") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(msg.text, forType: .string)
            }
        }
        if let p = msg.photo {
            Button("Сохранить фото") { Downloads.save(p, as: "JustTalk-\(msg.id).jpg") }
        }
        if let m = msg.media {
            Button("Сохранить в Загрузки") { Downloads.save(m, as: msg.mediaName.isEmpty ? m : msg.mediaName) }
        }
        if mine && msg.id > 0 {
            Divider()
            if msg.photo == nil && msg.media == nil && msg.cardType == nil && msg.spotTitle == nil && msg.fwdName.isEmpty {
                Button("Изменить") { onEdit(msg) }
            }
            Button("Удалить", role: .destructive) { store.delete(msg) }
        }
    }
}

func shortClock(_ t: Int) -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
}

/// Text with clickable links.
func linked(_ s: String) -> AttributedString {
    var out = AttributedString(s)
    guard let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return out }
    for m in det.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
        guard let url = m.url, let r = Range(m.range, in: s),
              let lo = AttributedString.Index(r.lowerBound, within: out), let hi = AttributedString.Index(r.upperBound, within: out) else { continue }
        out[lo..<hi].link = url
        out[lo..<hi].underlineStyle = .single
    }
    return out
}

// ---------- media ----------

enum Downloads {
    /// Into ~/Downloads/JustTalk, never over an existing file.
    static func save(_ media: String, as name: String) {
        Task {
            let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("JustTalk")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let safe = name.replacingOccurrences(of: "/", with: "_")
            var dest = dir.appendingPathComponent(safe)
            let base = dest.deletingPathExtension().lastPathComponent, ext = dest.pathExtension
            var n = 2
            while FileManager.default.fileExists(atPath: dest.path) {
                dest = dir.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
                n += 1
            }
            guard let (tmp, resp) = try? await URLSession.shared.download(from: Api.mediaURL(media)),
                  (resp as? HTTPURLResponse)?.statusCode == 200 else {
                await MainActor.run { Store.shared.toast = "Не получилось скачать" }
                return
            }
            try? FileManager.default.moveItem(at: tmp, to: dest)
            let saved = dest
            await MainActor.run { NSWorkspace.shared.activateFileViewerSelecting([saved]) }
        }
    }
}

func sizeLabel(_ b: Int) -> String {
    if b >= 1024 * 1024 { return String(format: "%.1f МБ", Double(b) / 1048576).replacingOccurrences(of: ".", with: ",") }
    if b >= 1024 { return "\(b / 1024) КБ" }
    return "\(b) Б"
}

func durationLabel(_ ms: Int) -> String {
    let s = ms / 1000
    return String(format: "%d:%02d", s / 60, s % 60)
}

struct FileView: View {
    let msg: Msg
    let mine: Bool

    var body: some View {
        Button {
            if let m = msg.media { Downloads.save(m, as: msg.mediaName.isEmpty ? m : msg.mediaName) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "arrow.down.doc.fill").font(.system(size: 18)).foregroundStyle(mine ? C.acc : .white)
                    .frame(width: 40, height: 40).background(mine ? Color.white : C.acc, in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(msg.mediaName.isEmpty ? "Файл" : msg.mediaName).font(jt(14, .medium)).lineLimit(1)
                    Text(sizeLabel(msg.mediaSize)).font(jt(12)).opacity(0.7)
                }
                .foregroundStyle(mine ? .white : C.ink)
            }
        }
        .buttonStyle(.plain).help("Скачать в Загрузки")
    }
}

/// One shared player: starting a voice message stops the previous one.
@MainActor
final class Player: ObservableObject {
    static let shared = Player()
    @Published var playing: Int?
    @Published var progress = 0.0
    private var player: AVPlayer?
    private var observer: Any?

    func toggle(_ msg: Msg) {
        if playing == msg.id {
            stop()
            return
        }
        stop()
        guard let m = msg.media else { return }
        let p = AVPlayer(url: Api.mediaURL(m))
        player = p
        playing = msg.id
        observer = p.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 20), queue: .main) { [weak self] t in
            MainActor.assumeIsolated {
                guard let self, let d = p.currentItem?.duration.seconds, d > 0 else { return }
                self.progress = t.seconds / d
                if t.seconds >= d - 0.05 { self.stop() }
            }
        }
        p.play()
    }

    func stop() {
        if let observer { player?.removeTimeObserver(observer) }
        observer = nil
        player?.pause()
        player = nil
        playing = nil
        progress = 0
    }
}

struct VoiceView: View {
    let msg: Msg
    let mine: Bool
    @ObservedObject private var player = Player.shared

    var body: some View {
        let on = player.playing == msg.id
        HStack(spacing: 10) {
            Button { player.toggle(msg) } label: {
                Image(systemName: on ? "pause.fill" : "play.fill").font(.system(size: 15)).foregroundStyle(mine ? C.acc : .white)
                    .frame(width: 40, height: 40).background(mine ? Color.white : C.acc, in: Circle())
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 4) {
                WaveBars(wave: msg.wave, played: on ? player.progress : 0, ink: mine ? .white : C.acc)
                Text(durationLabel(msg.duration)).font(jt(12)).foregroundStyle(mine ? .white.opacity(0.8) : C.mut)
            }
        }
    }
}

struct CircleViewer: View {
    let msg: Msg
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer?

    var body: some View {
        VStack(spacing: 14) {
            if let player {
                VideoPlayer(player: player).frame(width: 360, height: 360).clipShape(Circle())
            }
            Button("Закрыть") { dismiss() }.keyboardShortcut(.cancelAction)
        }
        .padding(24).background(C.bg)
        .onAppear {
            guard let m = msg.media else { return }
            let p = AVPlayer(url: Api.mediaURL(m))
            player = p
            Player.shared.stop()
            p.play()
        }
        .onDisappear { player?.pause() }
    }
}

struct PhotoViewer: View {
    let name: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            RemoteImage(name: name, fill: false).frame(minWidth: 400, idealWidth: 900, maxWidth: 1400, minHeight: 300, idealHeight: 700, maxHeight: 1000)
            HStack {
                Button("Сохранить в Загрузки") { Downloads.save(name, as: name) }
                Spacer()
                Button("Закрыть") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            .padding(12)
        }
        .background(Color.black)
        .onTapGesture { dismiss() }
    }
}

// ---------- new chat, profile ----------

struct NewChatView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var found: [User] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Новый чат").font(jt(20, .bold)).foregroundStyle(C.ink)
            Field("@юзернейм кента", text: $query)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(people) { u in
                        Button {
                            Task { await store.openDm(u.id) }
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(name: u.name, photo: u.avatar, size: 38)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(u.name).font(jt(14, .semibold)).foregroundStyle(C.ink)
                                    Text(u.handle).font(jt(12, u.isOwner ? .bold : .regular)).foregroundStyle(u.isOwner ? C.acc : C.mut)
                                }
                                Spacer()
                            }
                            .padding(8).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if people.isEmpty && !query.isEmpty {
                        Text("Никого с таким ником").font(jt(13)).foregroundStyle(C.mut).padding(.top, 20)
                    }
                }
            }
            .frame(height: 320)
            HStack { Spacer(); Button("Закрыть") { dismiss() }.keyboardShortcut(.cancelAction) }
            Text("Группы пока создаются на телефоне").font(jt(11)).foregroundStyle(C.mut)
        }
        .padding(24).frame(width: 400).background(C.bg)
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { found = []; return }
            try? await Task.sleep(for: .milliseconds(250))
            found = await store.search(q)
        }
    }

    /// Search results, or people I already have a dm with.
    private var people: [User] {
        if !query.isEmpty { return found }
        return store.chats.filter { !$0.isGroup }.compactMap { $0.other(store.meId) }.filter { $0.id != store.meId }
    }
}

/// Column as wide as its widest child's natural width (capped), so short messages get short bubbles
/// while rows with spacers (the time line) still stretch to that width.
struct BubbleStack: Layout {
    var maxWidth: CGFloat
    var spacing: CGFloat = 4

    private func width(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGFloat {
        let cap = min(proposal.width ?? maxWidth, maxWidth)
        return subviews.map { min($0.sizeThatFits(.unspecified).width, cap) }.max() ?? 0
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = width(proposal, subviews)
        let hs = subviews.map { $0.sizeThatFits(ProposedViewSize(width: w, height: nil)).height }
        return CGSize(width: w, height: hs.reduce(0, +) + spacing * CGFloat(max(hs.count - 1, 0)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for s in subviews {
            let p = ProposedViewSize(width: bounds.width, height: nil)
            let h = s.sizeThatFits(p).height
            s.place(at: CGPoint(x: bounds.minX, y: y), proposal: p)
            y += h + spacing
        }
    }
}
