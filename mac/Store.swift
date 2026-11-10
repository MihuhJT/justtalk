import AppKit
import SwiftUI
@preconcurrency import UserNotifications

/// All app state, like the phone's Store: chats, messages, live events from the socket.
@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published var me: User?
    @Published var chats: [Chat] = []
    @Published var messages: [Int: [Msg]] = [:]
    /// chat id -> no older messages on the server
    @Published var historyDone: Set<Int> = []
    @Published var openChat: Int?
    @Published var connected = false
    /// chat id -> (user id -> until when "печатает…")
    @Published var typing: [Int: [Int: Date]] = [:]
    @Published var presence: [Int: (Bool, Int)] = [:]
    @Published var toast: String?
    @Published var spots: [Spot] = []
    /// people I share a chat with (with "kent" / "removed" flags)
    @Published var people: [User] = []
    @Published var watchCards: [Int: JSON] = [:]
    /// sidebar tab: chats or spots
    @Published var tab = "chats"
    /// a spot to show on the map (from a spot message)
    @Published var spotFocus: Int?
    /// watch id -> the open room's handler of "watch" events
    var onWatch: [Int: (JSON) -> Void] = [:]
    var showOnline: Bool {
        get { prefs.object(forKey: "show_online") as? Bool ?? true }
        set { prefs.set(newValue, forKey: "show_online"); sendPresence() }
    }

    private let socket = Socket()
    private var lastTyping: [Int: Date] = [:]
    private let prefs = UserDefaults.standard
    /// a test server (JT_SERVER) keeps its own login
    private let tokenKey = SERVER.contains("tuna.free") ? "token" : "token@" + SERVER

    var meId: Int { me?.id ?? 0 }
    var loggedIn: Bool { me != nil }

    init() {
        Api.token = prefs.string(forKey: tokenKey) ?? ""
        socket.onEvent = { [weak self] ev in self?.handle(ev) }
        socket.onState = { [weak self] up in
            guard let self else { return }
            self.connected = up
            if up {
                self.sendPresence()
                // anything missed while offline
                Task { await self.refresh() }
            } else {
                Task { await self.checkToken() }
            }
        }
        if !Api.token.isEmpty {
            Task { await start() }
        }
    }

    // ---------- account ----------

    func start() async {
        do {
            me = User(try await Api.call("GET", "/me") as? JSON ?? [:])
            socket.start()
            await refresh()
            requestNotifications()
        } catch let e as ApiError where e.message == "bad token" {
            logout()
        } catch {
            // offline: keep trying until the server answers
            toast = "Нет связи с сервером"
            try? await Task.sleep(for: .seconds(5))
            if !Api.token.isEmpty && me == nil { await start() }
        }
    }

    private func checkToken() async {
        do { try await Api.call("GET", "/me") } catch let e as ApiError where e.message == "bad token" { logout() } catch {}
    }

    func login(username: String, password: String) async throws {
        let r = try await Api.call("POST", "/login", body: ["username": username, "password": password]) as? JSON ?? [:]
        try await signedIn(r)
    }

    func register(invite: String, name: String, username: String) async throws {
        let rid = prefs.string(forKey: "reg_id") ?? UUID().uuidString
        prefs.set(rid, forKey: "reg_id")
        let r = try await Api.call("POST", "/register", body: ["invite": invite, "name": name, "username": username, "request_id": rid]) as? JSON ?? [:]
        try await signedIn(r)
    }

    private func signedIn(_ r: JSON) async throws {
        guard let t = r["token"] as? String else { throw ApiError(message: "Странный ответ сервера") }
        Api.token = t
        prefs.set(t, forKey: tokenKey)
        await start()
    }

    func setPassword(_ p: String, old: String = "") async throws {
        me = User(try await Api.call("POST", "/me/password", body: ["password": p, "old": old]) as? JSON ?? [:])
    }

    func logout() {
        socket.stop()
        Api.token = ""
        prefs.removeObject(forKey: tokenKey)
        me = nil
        chats = []
        messages = [:]
        openChat = nil
        connected = false
        NSApp.dockTile.badgeLabel = nil
    }

    // ---------- chats and history ----------

    func refresh() async {
        guard let list = try? await Api.call("GET", "/chats") as? [JSON] else { return }
        chats = list.map(Chat.init)
        for c in chats { for u in c.members { presence[u.id] = (u.online, u.lastSeen) } }
        if let list = try? await Api.call("GET", "/users") as? [JSON] { people = list.map(User.init) }
        if let id = openChat { await loadHistory(id, reset: true) }
        updateBadge()
    }

    func chat(_ id: Int) -> Chat? { chats.first { $0.id == id } }

    func user(_ id: Int) -> User? {
        if id == meId { return me }
        for c in chats { if let u = c.members.first(where: { $0.id == id }) { return u } }
        return nil
    }

    func open(_ id: Int?) {
        openChat = id
        guard let id else { return }
        if messages[id] == nil { Task { await loadHistory(id, reset: true) } } else { markRead(id) }
    }

    func loadHistory(_ id: Int, reset: Bool = false) async {
        let before = reset ? 0 : (messages[id]?.first(where: { $0.id > 0 })?.id ?? 0)
        guard let list = try? await Api.call("GET", "/chats/\(id)/messages", query: before > 0 ? ["before": "\(before)"] : [:]) as? [JSON] else { return }
        let got = list.map(Msg.init)
        if reset {
            // keep my messages still on the way
            messages[id] = got + (messages[id]?.filter { $0.id < 0 } ?? [])
            historyDone.remove(id)
        } else {
            messages[id] = got + (messages[id] ?? [])
        }
        if got.count < 100 { historyDone.insert(id) }
        markRead(id)
    }

    func markRead(_ id: Int) {
        guard NSApp.isActive, openChat == id, let i = chats.firstIndex(where: { $0.id == id }) else { return }
        guard let last = messages[id]?.last(where: { $0.id > 0 }) else { return }
        if chats[i].unread > 0 || last.senderId != meId {
            socket.send(["type": "read", "chat_id": id, "message_id": last.id])
        }
        chats[i].unread = 0
        updateBadge()
    }

    func openDm(_ userId: Int) async {
        guard let j = try? await Api.call("POST", "/chats/dm", body: ["user_id": userId]) as? JSON else { return }
        let c = Chat(j)
        if !chats.contains(where: { $0.id == c.id }) { chats.insert(c, at: 0) }
        open(c.id)
    }

    func search(_ q: String) async -> [User] {
        ((try? await Api.call("GET", "/users/search", query: ["q": q])) as? [JSON] ?? []).map(User.init)
    }

    func setMuted(_ id: Int, _ muted: Bool) async {
        await settings(id, ["muted": muted])
    }

    func setPinned(_ id: Int, _ pinned: Bool) async {
        await settings(id, ["pinned": pinned])
        await refresh()
    }

    private func settings(_ id: Int, _ body: JSON) async {
        guard let j = try? await Api.call("POST", "/chats/\(id)/settings", body: body) as? JSON else { return }
        if let i = chats.firstIndex(where: { $0.id == id }) { chats[i] = Chat(j) }
        updateBadge()
    }

    // ---------- sending ----------

    func send(_ chatId: Int, _ text: String, replyTo: Int = 0) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let cid = UUID().uuidString
        var m = Msg(id: -Int.random(in: 1...1_000_000_000), chatId: chatId, senderId: meId, text: text, created: Int(Date().timeIntervalSince1970))
        m.clientId = cid
        m.replyTo = replyTo
        if replyTo > 0, let q = messages[chatId]?.first(where: { $0.id == replyTo }) {
            m.quote = Quote(id: q.id, senderId: q.senderId, text: q.preview, deleted: false)
        }
        messages[chatId, default: []].append(m)
        socket.send(["type": "send", "chat_id": chatId, "text": text, "client_id": cid, "reply_to": replyTo])
    }

    func edit(_ m: Msg, _ text: String) {
        socket.send(["type": "edit", "chat_id": m.chatId, "message_id": m.id, "text": text])
    }

    func delete(_ m: Msg) {
        socket.send(["type": "delete", "chat_id": m.chatId, "message_id": m.id])
    }

    func react(_ m: Msg, _ emoji: String) {
        let mine = m.reactions.first { $0.1.contains(meId) }?.0
        socket.send(["type": "react", "chat_id": m.chatId, "message_id": m.id, "emoji": mine == emoji ? "" : emoji])
    }

    func typingNow(_ chatId: Int) {
        if let t = lastTyping[chatId], Date().timeIntervalSince(t) < 3 { return }
        lastTyping[chatId] = Date()
        socket.send(["type": "typing", "chat_id": chatId])
    }

    func sendRaw(_ ev: JSON) { socket.send(ev) }

    func sendPresence() {
        socket.send(["type": "presence", "active": NSApp.isActive, "show": showOnline])
    }

    func sendPhoto(_ chatId: Int, _ image: NSImage, caption: String = "") async {
        guard let (jpeg, w, h) = Self.jpeg(image) else { toast = "Не получилось прочитать картинку"; return }
        do {
            try await Api.call("POST", "/chats/\(chatId)/photo", query: ["w": "\(w)", "h": "\(h)", "caption": caption,
                                                                          "client_id": UUID().uuidString], raw: jpeg, timeout: 120)
        } catch {
            toast = error.localizedDescription
        }
    }

    func sendFile(_ chatId: Int, _ url: URL) async {
        let ext = url.pathExtension.lowercased()
        if ["jpg", "jpeg", "png", "heic", "gif", "webp", "tiff", "bmp"].contains(ext), let img = NSImage(contentsOf: url) {
            await sendPhoto(chatId, img)
            return
        }
        guard let data = try? Data(contentsOf: url) else { toast = "Не получилось прочитать файл"; return }
        if data.count > 100 * 1024 * 1024 { toast = "Файл больше 100 МБ"; return }
        toast = "Загружаю \(url.lastPathComponent)…"
        do {
            let up = try await Api.call("POST", "/upload", query: ["ext": ext.isEmpty ? "bin" : ext], raw: data, timeout: 600) as? JSON ?? [:]
            try await Api.call("POST", "/chats/\(chatId)/media", body: ["kind": "file", "media": up["name"] as? String ?? "",
                                                                        "name": url.lastPathComponent, "client_id": UUID().uuidString])
            toast = nil
        } catch {
            toast = error.localizedDescription
        }
    }

    /// Shrinks to 2560 px on the long side, like the phone does.
    static func jpeg(_ img: NSImage, max side: Double = 2560, square: Bool = false) -> (Data, Int, Int)? {
        guard var cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        if square {
            // avatars: centre square
            let s = min(cg.width, cg.height)
            if let c = cg.cropping(to: CGRect(x: (cg.width - s) / 2, y: (cg.height - s) / 2, width: s, height: s)) { cg = c }
        }
        let k = min(1, side / Double(max(cg.width, cg.height)))
        let w = Int(Double(cg.width) * k), h = Int(Double(cg.height) * k)
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(.white)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let out = ctx.makeImage() else { return nil }
        let rep = NSBitmapImageRep(cgImage: out)
        guard let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) else { return nil }
        return (data, w, h)
    }

    // ---------- live events ----------

    private func handle(_ ev: JSON) {
        let chatId = ev["chat_id"] as? Int ?? 0
        switch ev["type"] as? String {
        case "message":
            guard let j = ev["message"] as? JSON else { return }
            let m = Msg(j)
            let cid = ev["client_id"] as? String
            var list = messages[m.chatId] ?? []
            if let cid, let i = list.firstIndex(where: { $0.clientId == cid }) {
                list[i] = m
            } else if !list.contains(where: { $0.id == m.id }), messages[m.chatId] != nil {
                list.append(m)
            }
            if messages[m.chatId] != nil { messages[m.chatId] = list }
            if let i = chats.firstIndex(where: { $0.id == m.chatId }) {
                chats[i].last = m
                if m.senderId != meId && m.senderId != 0 { chats[i].unread += 1 }
                // newest chat goes up, below the pinned ones
                let c = chats.remove(at: i)
                let at = c.pinned > 0 ? 0 : (chats.firstIndex { $0.pinned == 0 } ?? chats.count)
                chats.insert(c, at: at)
            } else {
                Task { await refresh() }
            }
            typing[m.chatId]?[m.senderId] = nil
            if m.senderId != meId && m.senderId != 0 { notify(m) }
            markRead(m.chatId)
            updateBadge()
        case "edit":
            guard let j = ev["message"] as? JSON else { return }
            let m = Msg(j)
            replace(m.chatId, m.id) { $0 = m }
        case "delete":
            let id = ev["message_id"] as? Int ?? 0
            messages[chatId]?.removeAll { $0.id == id }
            Task { await refresh() }
        case "reactions":
            let id = ev["message_id"] as? Int ?? 0
            let r = (ev["reactions"] as? [String: [Int]] ?? [:]).map { ($0.key, $0.value) }.sorted { $0.1.count > $1.1.count }
            replace(chatId, id) { $0.reactions = r }
        case "card":
            guard let j = ev["card"] as? JSON else { return }
            replace(chatId, ev["message_id"] as? Int ?? 0) { $0.card = j }
            if j["type"] as? String == "watch", let id = j["id"] as? Int { watchCards[id] = j }
        case "read":
            let uid = ev["user_id"] as? Int ?? 0
            if uid != meId, let i = chats.firstIndex(where: { $0.id == chatId }) {
                // double tick = read by everyone; one reader in a group isn't enough, so ask the server
                if chats[i].isGroup { Task { await refresh() } } else { chats[i].othersRead = max(chats[i].othersRead, ev["message_id"] as? Int ?? 0) }
            }
            if uid == meId, let i = chats.firstIndex(where: { $0.id == chatId }) {
                chats[i].unread = 0
                updateBadge()
            }
        case "typing":
            let uid = ev["user_id"] as? Int ?? 0
            typing[chatId, default: [:]][uid] = Date().addingTimeInterval(5)
            DispatchQueue.main.asyncAfter(deadline: .now() + 5.1) { [weak self] in
                guard let self, let until = self.typing[chatId]?[uid], until <= Date() else { return }
                self.typing[chatId]?[uid] = nil
            }
        case "presence":
            presence[ev["user_id"] as? Int ?? 0] = (ev["online"] as? Bool ?? false, ev["last_seen"] as? Int ?? 0)
        case "blocked":
            let cid = ev["client_id"] as? String
            messages[chatId]?.removeAll { $0.clientId != nil && $0.clientId == cid }
            toast = ev["reason"] as? String ?? "Не отправлено"
        case "chat":
            Task { await refresh() }
        case "spots":
            Task { await loadSpots() }
        case "watch":
            onWatch[ev["watch_id"] as? Int ?? 0]?(ev)
        case let t? where t.hasPrefix("call_"):
            Calls.shared.handle(ev)
        default:
            break
        }
    }

    private func replace(_ chatId: Int, _ id: Int, _ change: (inout Msg) -> Void) {
        if let i = messages[chatId]?.firstIndex(where: { $0.id == id }) { change(&messages[chatId]![i]) }
        if let i = chats.firstIndex(where: { $0.id == chatId }), chats[i].last?.id == id { change(&chats[i].last!) }
    }

    func typingLine(_ chatId: Int) -> String? {
        let ids = (typing[chatId] ?? [:]).filter { $0.value > Date() && $0.key != meId }.map(\.key)
        guard !ids.isEmpty else { return nil }
        if chat(chatId)?.isGroup != true { return "печатает…" }
        let names = ids.compactMap { user($0)?.name }
        return names.count == 1 ? "\(names[0]) печатает…" : "\(names.count) печатают…"
    }

    func presenceLine(_ userId: Int) -> String {
        let (on, seen) = presence[userId] ?? (false, 0)
        if on { return "в сети" }
        guard seen > 0 else { return "" }
        let d = Date(timeIntervalSince1970: TimeInterval(seen))
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        if Calendar.current.isDateInToday(d) { f.dateFormat = "'был(а) в' HH:mm" }
        else if Calendar.current.isDateInYesterday(d) { f.dateFormat = "'был(а) вчера в' HH:mm" }
        else { f.dateFormat = "'был(а)' d MMM" }
        return f.string(from: d)
    }

    // ---------- notifications and dock ----------

    func updateBadge() {
        let n = chats.filter { !$0.muted }.reduce(0) { $0 + $1.unread }
        NSApp.dockTile.badgeLabel = n > 0 ? (n > 99 ? "99+" : "\(n)") : nil
    }

    private func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func notify(_ m: Msg) {
        guard let c = chat(m.chatId), !c.muted else { return }
        if NSApp.isActive && openChat == m.chatId { return }
        let n = UNMutableNotificationContent()
        let author = user(m.senderId)?.name ?? "Кент"
        n.title = c.isGroup ? c.title : author
        n.body = c.isGroup ? "\(author): \(m.preview)" : m.preview
        n.sound = .default
        n.threadIdentifier = "chat\(m.chatId)"
        n.userInfo = ["chat_id": m.chatId]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "m\(m.id)", content: n, trigger: nil))
    }

    func clearNotifications(_ chatId: Int) {
        let c = UNUserNotificationCenter.current()
        c.getDeliveredNotifications { list in
            let ids = list.filter { ($0.request.content.userInfo["chat_id"] as? Int) == chatId }.map(\.request.identifier)
            c.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }
}
