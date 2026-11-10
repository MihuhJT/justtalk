import Foundation

// Same server as the phones; JT_SERVER env overrides it for local tests.
let SERVER = ProcessInfo.processInfo.environment["JT_SERVER"] ?? "http://127.0.0.1:8000"
let APP_VERSION = "Mac 0.15"

typealias JSON = [String: Any]

struct User: Identifiable, Equatable {
    let id: Int
    var name: String
    var username: String
    var about = ""
    var avatar = ""
    var online = false
    var lastSeen = 0
    var hasPassword = true
    var birthday = ""
    var gender = ""
    var kent = false
    var removed = false

    var isOwner: Bool { username == "jt" }
    var handle: String { isOwner ? "@JT" : "@\(username)" }

    init(_ j: JSON) {
        id = j["id"] as? Int ?? 0
        name = j["name"] as? String ?? ""
        username = j["username"] as? String ?? ""
        about = j["about"] as? String ?? ""
        avatar = j["avatar"] as? String ?? ""
        online = j["online"] as? Bool ?? false
        lastSeen = j["last_seen"] as? Int ?? 0
        hasPassword = j["has_password"] as? Bool ?? true
        birthday = j["birthday"] as? String ?? ""
        gender = j["gender"] as? String ?? ""
        kent = j["kent"] as? Bool ?? false
        removed = j["removed"] as? Bool ?? false
    }
}

struct Quote: Equatable {
    let id: Int
    let senderId: Int
    let text: String
    let deleted: Bool
}

struct Msg: Identifiable, Equatable {
    /// negative = still on the way to the server; clientId matches it to the server echo
    var id: Int
    let chatId: Int
    let senderId: Int
    var text: String
    let created: Int
    var clientId: String? = nil
    var replyTo = 0
    var edited = false
    var quote: Quote? = nil
    var photo: String? = nil
    var photoW = 0
    var photoH = 0
    var media: String? = nil
    var mediaKind = ""
    var mediaName = ""
    var mediaSize = 0
    var duration = 0
    var thumb = ""
    var fwdName = ""
    var reactions: [(String, [Int])] = []
    var spotTitle: String? = nil
    var spotId = 0
    var spotLat = 0.0
    var spotLng = 0.0
    var wave = ""
    /// 0.15 live card state from the server (poll, gather, split, watch, call)
    var card: JSON? = nil
    var cardType: String? { card?["type"] as? String }

    static func == (a: Msg, b: Msg) -> Bool {
        a.id == b.id && a.text == b.text && a.edited == b.edited && a.photo == b.photo
            && a.reactions.map { "\($0.0)\($0.1)" } == b.reactions.map { "\($0.0)\($0.1)" } && a.quote == b.quote
            && (a.card as NSDictionary?) == (b.card as NSDictionary?)
    }

    init(id: Int, chatId: Int, senderId: Int, text: String, created: Int) {
        self.id = id; self.chatId = chatId; self.senderId = senderId; self.text = text; self.created = created
    }

    init(_ j: JSON) {
        id = j["id"] as? Int ?? 0
        chatId = j["chat_id"] as? Int ?? 0
        senderId = j["sender_id"] as? Int ?? 0
        text = j["text"] as? String ?? ""
        created = j["created"] as? Int ?? 0
        replyTo = j["reply_to"] as? Int ?? 0
        edited = j["edited"] as? Bool ?? false
        photo = (j["photo"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        photoW = j["photo_w"] as? Int ?? 0
        photoH = j["photo_h"] as? Int ?? 0
        media = (j["media"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        mediaKind = j["media_kind"] as? String ?? ""
        mediaName = j["media_name"] as? String ?? ""
        mediaSize = j["media_size"] as? Int ?? 0
        duration = j["duration"] as? Int ?? 0
        thumb = j["thumb"] as? String ?? ""
        fwdName = j["fwd_name"] as? String ?? ""
        if let r = j["reply"] as? JSON {
            quote = Quote(id: r["id"] as? Int ?? 0, senderId: r["sender_id"] as? Int ?? 0,
                          text: r["text"] as? String ?? "", deleted: r["deleted"] as? Bool ?? false)
        }
        if let r = j["reactions"] as? [String: [Int]] {
            reactions = r.map { ($0.key, $0.value) }.sorted { $0.1.count > $1.1.count || ($0.1.count == $1.1.count && $0.0 < $1.0) }
        }
        if let s = j["spot"] as? JSON {
            spotTitle = s["title"] as? String ?? "Спот"
            spotId = s["id"] as? Int ?? 0
            spotLat = (s["lat"] as? NSNumber)?.doubleValue ?? 0
            spotLng = (s["lng"] as? NSNumber)?.doubleValue ?? 0
        }
        card = j["card"] as? JSON
        wave = j["wave"] as? String ?? ""
    }

    /// One line for the chat list and notifications, like the phone's Msg.preview().
    var preview: String {
        if let c = cardType { return Msg.cardLabel(c) }
        if photo != nil { return text.isEmpty ? "📷 Фото" : "📷 \(text)" }
        switch mediaKind {
        case "voice": return "🎤 Голосовое"
        case "circle": return "⏺ Кружок"
        case "file": return "📎 " + (text.isEmpty ? (mediaName.isEmpty ? "Файл" : mediaName) : text)
        default: break
        }
        if let s = spotTitle { return "📍 \(s)" }
        return text
    }

    static func cardLabel(_ t: String) -> String {
        ["poll": "📊 Опрос", "gather": "📅 Сбор", "split": "💸 Скидываемся", "watch": "🎬 Смотрим вместе", "call": "📞 Звонок"][t] ?? "Карточка"
    }
}

struct Chat: Identifiable, Equatable {
    let id: Int
    var kind: String
    var title: String
    var members: [User]
    var last: Msg?
    var unread: Int
    var othersRead: Int
    var muted: Bool
    var avatar: String
    var pinned: Int
    var alias: String

    init(_ j: JSON) {
        id = j["id"] as? Int ?? 0
        kind = j["kind"] as? String ?? "dm"
        title = j["title"] as? String ?? ""
        members = (j["members"] as? [JSON] ?? []).map(User.init)
        last = (j["last"] as? JSON).map(Msg.init)
        unread = j["unread"] as? Int ?? 0
        othersRead = j["others_read"] as? Int ?? 0
        muted = j["muted"] as? Bool ?? false
        avatar = j["avatar"] as? String ?? ""
        pinned = j["pinned"] as? Int ?? 0
        alias = j["alias"] as? String ?? ""
    }

    var isGroup: Bool { kind == "group" }
    func other(_ meId: Int) -> User? { members.first { $0.id != meId } ?? members.first }
}

struct ApiError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

enum Api {
    static var token = ""

    static func url(_ path: String, _ query: [String: String] = [:]) -> URL {
        var c = URLComponents(string: SERVER + path)!
        if !query.isEmpty { c.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
        return c.url!
    }

    static func mediaURL(_ name: String) -> URL {
        url("/media/\(name)", ["t": token])
    }

    @discardableResult
    static func call(_ method: String, _ path: String, query: [String: String] = [:], body: Any? = nil,
                     raw: Data? = nil, timeout: Double = 30) async throws -> Any {
        var req = URLRequest(url: url(path, query), timeoutInterval: timeout)
        req.httpMethod = method
        if !token.isEmpty { req.setValue(token, forHTTPHeaderField: "Authorization") }
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if let raw {
            req.httpBody = raw
            req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        }
        let data: Data, resp: URLResponse
        do {
            (data, resp) = try await URLSession.shared.data(for: req)
        } catch {
            throw ApiError(message: "Нет связи с сервером")
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) ?? [:]
        if code >= 400 {
            if code == 401 { throw ApiError(message: "bad token") }
            let detail = (json as? JSON)?["detail"]
            throw ApiError(message: detail as? String ?? "Ошибка сервера (\(code))")
        }
        return json
    }
}

/// One WebSocket for live events; reconnects by itself until stop().
final class Socket: NSObject, URLSessionWebSocketDelegate {
    var onEvent: (JSON) -> Void = { _ in }
    var onState: (Bool) -> Void = { _ in }
    var onBadToken: () -> Void = {}
    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private var running = false
    private var retry = 1.0

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }

    func start() {
        running = true
        connect()
    }

    func stop() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    private func connect() {
        guard running else { return }
        let u = Api.url("/ws", ["token": Api.token, "v": APP_VERSION])
        var c = URLComponents(url: u, resolvingAgainstBaseURL: false)!
        c.scheme = c.scheme == "https" ? "wss" : "ws"
        let t = session.webSocketTask(with: c.url!)
        task = t
        t.resume()
        receive(t)
        ping(t)
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            guard let self, t === self.task else { return }
            switch result {
            case .success(.string(let s)):
                if let d = s.data(using: .utf8), let j = try? JSONSerialization.jsonObject(with: d) as? JSON {
                    self.onEvent(j)
                }
                self.receive(t)
            case .success:
                self.receive(t)
            case .failure:
                self.dropped(t)
            }
        }
    }

    // the tunnel drops quiet sockets, so ping every 25 s
    private func ping(_ t: URLSessionWebSocketTask) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
            guard let self, t === self.task else { return }
            t.sendPing { err in if err != nil { DispatchQueue.main.async { self.dropped(t) } } }
            self.ping(t)
        }
    }

    private func dropped(_ t: URLSessionWebSocketTask) {
        guard t === task else { return }
        if t.closeCode.rawValue == 4401 {
            running = false
            onBadToken()
            return
        }
        task = nil
        onState(false)
        DispatchQueue.main.asyncAfter(deadline: .now() + retry) { [weak self] in self?.connect() }
        retry = min(retry * 2, 15)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        retry = 1
        onState(true)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        dropped(webSocketTask)
    }

    func send(_ ev: JSON) {
        guard let d = try? JSONSerialization.data(withJSONObject: ev), let s = String(data: d, encoding: .utf8) else { return }
        task?.send(.string(s)) { _ in }
    }
}
