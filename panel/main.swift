// JT Server: a Mac panel for the JustTalk server on the home PC.
// Commands go to admin.py on the PC: through the public tunnel (works from anywhere) or over ssh (home network only).
import AppKit
import SwiftUI

let accent = Color(red: 0x73 / 255, green: 0x66 / 255, blue: 0xF0 / 255)
let publicURL = "http://127.0.0.1:8000"

// MARK: - data from admin.py

struct Status: Decodable {
    var up: Bool
    var online, background, users, dms, groups, messages, messages_day, media_files: Int
    var media_mb, db_mb, disk_free_gb, disk_total_gb: Double
    var invite: String
}

struct Kent: Decodable, Identifiable {
    var id: Int
    var name, username, about, birthday, gender, version: String
    var created, msgs, last, seen: Int
    var avatar, owner, online, background: Bool
    /// 0.11+ server; nil = older server that knows no passwords
    var has_password: Bool?
}

struct Chat: Decodable, Identifiable {
    var id: Int
    var kind, title: String
    var members: [String]
    var msgs, last: Int
}

struct Reply: Decodable {
    var ok: Bool?
    var error: String?
    var path: String?
    var password: String?
}

struct LogLines: Decodable { var lines: [String] }

enum PanelError: LocalizedError {
    case offline, bad(String)
    var errorDescription: String? {
        switch self {
        case .offline:
            if Remote.token.isEmpty { return "Нет токена админа: открой панель один раз дома (по ssh) или вставь токен в Настройках" }
            if Remote.tokenRejected { return "Сервер не принял токен админа. Дома панель обновит его сама" }
            return "Нет связи с ПК: туннель не отвечает (ПК выключен или упал tuna), ssh только из дома"
        case .bad(let s): return s
        }
    }
}

// MARK: - connection

enum Route: String { case tunnel = "через туннель", ssh = "по ssh (домашняя сеть)" }

enum Remote {
    static var host: String { UserDefaults.standard.string(forKey: "host") ?? "user@example.local" }
    /// The server's admin_token.txt; fetched over ssh the first time the panel runs at home.
    /// Also kept in a file next to the app, so wiped app settings don't lock the panel out of the tunnel.
    static let tokenBackup = URL(fileURLWithPath: NSString(string: "~/kenty-messenger/panel/admin_token.txt").expandingTildeInPath)
    static var token: String {
        get {
            if let t = UserDefaults.standard.string(forKey: "token"), !t.isEmpty { return t }
            return ((try? String(contentsOf: tokenBackup, encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: "token")
            if !newValue.isEmpty { try? newValue.write(to: tokenBackup, atomically: true, encoding: .utf8) }
        }
    }
    /// The server said 403: the next ssh call at home re-reads the token from the PC.
    static var tokenRejected = false
    /// Fresh connections every time: after sleep or a network switch the shared session can hang on dead ones.
    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()
    static let python = #"C:\Users\USERNAME\justtalk\server\.venv\Scripts\python.exe"#
    static let script = #"C:\Users\USERNAME\justtalk\admin.py"#
    static let tokenFile = #"C:\Users\USERNAME\justtalk\server\admin_token.txt"#

    static func shell(_ tool: String, _ args: [String]) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global().async {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: tool)
                p.arguments = ["-o", "BatchMode=yes", "-o", "ConnectTimeout=5"] + args
                let pipe = Pipe()
                p.standardOutput = pipe
                p.standardError = Pipe()
                do { try p.run() } catch { cont.resume(throwing: error); return }
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                p.waitUntilExit()
                // 255 = ssh itself failed: PC off, wrong IP or key
                if p.terminationStatus == 255 { cont.resume(throwing: PanelError.offline) } else { cont.resume(returning: data) }
            }
        }
    }

    /// nil = the tunnel can't be reached (try ssh); 403 = wrong token, that's a real error.
    static func http(_ path: String, method: String = "POST", body: [String: Any]? = nil, timeout: Double = 30) async throws -> Data? {
        guard !token.isEmpty else { return nil }
        var req = URLRequest(url: URL(string: publicURL + path)!)
        req.httpMethod = method
        req.timeoutInterval = timeout
        req.setValue(token, forHTTPHeaderField: "X-Admin")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        // the free tunnel drops single requests now and then, so one miss is not "offline"
        for attempt in 0..<3 {
            if attempt > 0 { try? await Task.sleep(for: .seconds(2)) }
            guard let (data, res) = try? await session.data(for: req), let code = (res as? HTTPURLResponse)?.statusCode else { continue }
            // stale token (the PC was reinstalled): keep it for now, the ssh path at home fetches the new one
            if code == 403 { tokenRejected = true; return nil }
            // 404 = the server is older than this panel
            if code == 404 { return nil }
            // 5xx = the tunnel answers but uvicorn doesn't (yet)
            if code >= 500 { continue }
            tokenRejected = false
            return data
        }
        return nil
    }

    static func ssh(_ args: [String]) async throws -> Data {
        let data = try await shell("/usr/bin/ssh", [host, ([python, script] + args).joined(separator: " ")])
        if token.isEmpty || tokenRejected, let t = try? await shell("/usr/bin/ssh", [host, "type " + tokenFile]) {
            let fresh = String(decoding: t, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            if !fresh.isEmpty { token = fresh; tokenRejected = false }
        }
        return data
    }

    static func run(_ args: [String]) async throws -> (Data, Route) {
        if let d = try await http("/admin/run", body: ["cmd": args[0], "args": Array(args.dropFirst())]) { return (d, .tunnel) }
        return (try await ssh(args), .ssh)
    }

    static func admin<T: Decodable>(_ type: T.Type, _ args: String...) async throws -> (T, Route) {
        let (data, route) = try await run(args)
        if let value = try? JSONDecoder().decode(T.self, from: data) { return (value, route) }
        if let r = try? JSONDecoder().decode(Reply.self, from: data), let e = r.error { throw PanelError.bad(e) }
        throw PanelError.bad("Странный ответ: " + String(decoding: data.prefix(200), as: UTF8.self))
    }
}

// MARK: - state

@MainActor @Observable
final class Panel {
    var status: Status?
    var kents: [Kent] = []
    var chats: [Chat] = []
    var log: [String] = []
    var tunnelOK: Bool?
    var route: Route?
    var error = ""
    var busy = ""
    var updated: Date?

    func refresh() async {
        async let tunnel = checkTunnel()
        do {
            (status, route) = try await Remote.admin(Status.self, "status")
            kents = try await Remote.admin([Kent].self, "users").0
            chats = try await Remote.admin([Chat].self, "chats").0
            error = ""
            updated = Date()
        } catch {
            self.error = error.localizedDescription
            status = nil
            route = nil
        }
        tunnelOK = await tunnel
    }

    func loadLog() async {
        do { log = try await Remote.admin(LogLines.self, "log", "300").0.lines } catch { self.error = error.localizedDescription }
    }

    /// The address the phones use: any HTTP answer means the tunnel and the server both work.
    func checkTunnel() async -> Bool {
        var req = URLRequest(url: URL(string: publicURL + "/me")!)
        req.timeoutInterval = 6
        guard let (_, res) = try? await Remote.session.data(for: req) else { return false }
        return (res as? HTTPURLResponse).map { $0.statusCode < 500 } ?? false
    }

    func act(_ label: String, _ args: String...) async {
        busy = label
        defer { busy = "" }
        do {
            let r = args == ["restart"] ? try await restart() : try await Remote.run(args).0
            let reply = try JSONDecoder().decode(Reply.self, from: r)
            if reply.ok == false { error = reply.error ?? "Не вышло" }
        } catch { self.error = error.localizedDescription }
        await refresh()
    }

    /// A kent forgot the password: the server sets a temporary one, shown here to pass on to him.
    func resetPassword(_ k: Kent) async -> String? {
        busy = "Сброс пароля…"
        defer { busy = "" }
        do {
            let reply = try JSONDecoder().decode(Reply.self, from: try await Remote.run(["reset_password", "\(k.id)"]).0)
            if let p = reply.password { await refresh(); return p }
            error = reply.error ?? "Не вышло"
        } catch { self.error = error.localizedDescription }
        return nil
    }

    /// Through the tunnel the server can't stop itself and wait, so a separate task on the PC does it; we wait here.
    func restart() async throws -> Data {
        guard let d = try await Remote.http("/admin/restart") else { return try await Remote.ssh(["restart"]) }
        if (try? JSONDecoder().decode(Reply.self, from: d))?.ok == false { return d }
        try await Task.sleep(for: .seconds(4))
        for _ in 0..<25 {
            if (try? await Remote.http("/admin/run", body: ["cmd": "status"], timeout: 5)) != nil { return Data(#"{"ok":true}"#.utf8) }
            try await Task.sleep(for: .seconds(1))
        }
        return Data(#"{"ok":false,"error":"Сервер не поднялся за 30 с"}"#.utf8)
    }

    /// Consistent copy on the PC, then scp to ~/kenty-messenger/backups and show it in Finder.
    func backup() async {
        busy = "Бэкап…"
        defer { busy = "" }
        do {
            let dir = FileManager.default.homeDirectoryForCurrentUser.appending(path: "kenty-messenger/backups")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            if let data = try await Remote.http("/admin/backup", method: "GET", timeout: 120) {
                let f = DateFormatter()
                f.dateFormat = "yyyyMMdd-HHmmss"
                let local = dir.appending(path: "justtalk-\(f.string(from: Date())).db")
                try data.write(to: local)
                NSWorkspace.shared.activateFileViewerSelecting([local])
                return
            }
            let (r, _) = try await Remote.admin(Reply.self, "backup")
            guard let remote = r.path else { throw PanelError.bad(r.error ?? "Нет пути бэкапа") }
            let name = remote.split(whereSeparator: { $0 == "\\" || $0 == "/" }).last.map(String.init) ?? "justtalk.db"
            let local = dir.appending(path: name)
            _ = try await Remote.shell("/usr/bin/scp", ["\(Remote.host):\(remote.replacingOccurrences(of: "\\", with: "/"))", local.path])
            guard FileManager.default.fileExists(atPath: local.path) else { throw PanelError.bad("Файл не скачался") }
            NSWorkspace.shared.activateFileViewerSelecting([local])
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - helpers

func ago(_ t: Int) -> String {
    if t == 0 { return "—" }
    let s = Int(Date().timeIntervalSince1970) - t
    switch s {
    case ..<60: return "только что"
    case ..<3600: return "\(s / 60) мин назад"
    case ..<86400: return "\(s / 3600) ч назад"
    default:
        let f = DateFormatter()
        f.dateFormat = "dd.MM.yy HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
    }
}

struct Card: View {
    var title: String
    var value: String
    var hint: String = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 28, weight: .bold, design: .rounded))
            Text(hint).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct Dot: View {
    var on: Bool?
    var body: some View {
        Circle().fill(on == nil ? .gray : (on! ? .green : .red)).frame(width: 9, height: 9)
            .shadow(color: on == true ? .green : .clear, radius: 4)
    }
}

/** Green = app open now, grey = only the background connection, empty = offline. */
struct Presence: View {
    var k: Kent
    var body: some View {
        Circle().fill(k.online ? Color.green : k.background ? Color.gray : Color.clear)
            .overlay(Circle().stroke(.gray.opacity(0.5), lineWidth: k.online || k.background ? 0 : 1))
            .frame(width: 9, height: 9).shadow(color: k.online ? .green : .clear, radius: 4)
            .help(k.online ? "в приложении" : k.background ? "в фоне: уведомления приходят, приложение закрыто" : "не на связи")
    }
}

// MARK: - screens

enum Tab: String, CaseIterable, Identifiable {
    case overview = "Обзор", kents = "Кенты", chats = "Чаты", log = "Логи", settings = "Настройки"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .overview: "gauge.with.dots.needle.67percent"
        case .kents: "person.2"
        case .chats: "bubble.left.and.bubble.right"
        case .log: "doc.plaintext"
        case .settings: "gearshape"
        }
    }
}

struct Overview: View {
    @Environment(Panel.self) var panel
    @State var askRestart = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    Dot(on: panel.status?.up).scaleEffect(1.6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(panel.status == nil ? (panel.error.isEmpty ? "Проверяем…" : "Нет связи с ПК")
                             : panel.status!.up ? "Сервер работает" : "Сервер не отвечает")
                            .font(.system(size: 24, weight: .bold))
                        Text(tunnelText).font(.system(size: 13)).foregroundStyle(.secondary)
                        if let r = panel.route { Text("Панель на связи " + r.rawValue).font(.system(size: 12)).foregroundStyle(.tertiary) }
                    }
                    Spacer()
                    Button { askRestart = true } label: { Label("Перезапустить", systemImage: "arrow.clockwise") }
                        .disabled(!panel.busy.isEmpty || panel.status == nil)
                    Button { Task { await panel.backup() } } label: { Label("Бэкап базы", systemImage: "externaldrive") }
                        .disabled(!panel.busy.isEmpty || panel.status == nil)
                }
                if let s = panel.status {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), spacing: 12)], spacing: 12) {
                        // "в сети" = the app is open on screen; the background connection doesn't count
                        Card(title: "В сети", value: "\(s.online)", hint: "из \(s.users) кентов · \(s.background) в фоне")
                        Card(title: "Кентов", value: "\(s.users)", hint: "аккаунтов")
                        Card(title: "Чатов", value: "\(s.dms + s.groups)", hint: "\(s.dms) личных · \(s.groups) групп")
                        Card(title: "Сообщений", value: "\(s.messages)", hint: "+\(s.messages_day) за сутки")
                        Card(title: "Фото", value: "\(s.media_files)", hint: String(format: "%.1f МБ", s.media_mb))
                        Card(title: "База", value: String(format: "%.2f МБ", s.db_mb), hint: "justtalk.db")
                        Card(title: "Диск C:", value: String(format: "%.0f ГБ", s.disk_free_gb), hint: String(format: "свободно из %.0f", s.disk_total_gb))
                        Card(title: "Код приглашения", value: s.invite, hint: "меняется в Настройках")
                    }
                    versions
                }
            }
            .padding(24)
        }
        .confirmationDialog("Перезапустить сервер?", isPresented: $askRestart) {
            Button("Перезапустить") { Task { await panel.act("Перезапуск…", "restart") } }
        } message: { Text("Кенты на пару секунд потеряют связь, приложение переподключится само.") }
    }

    var tunnelText: String {
        switch panel.tunnelOK {
        case true: "Туннель работает: кенты достучатся снаружи"
        case false: "Туннель не отвечает: снаружи сервер недоступен"
        default: "Проверяем туннель…"
        }
    }

    /// How many kents run which app version.
    var versions: some View {
        let groups = Dictionary(grouping: panel.kents, by: { $0.version.isEmpty ? "0.8 или старше" : $0.version })
            .sorted { $0.key > $1.key }
        return VStack(alignment: .leading, spacing: 8) {
            Text("Версии приложения").font(.headline)
            ForEach(groups, id: \.key) { version, people in
                HStack {
                    Text(version).font(.system(.body, design: .monospaced)).frame(width: 130, alignment: .leading)
                    Text(people.map(\.name).joined(separator: ", ")).foregroundStyle(.secondary).lineLimit(1)
                    Spacer()
                    Text("\(people.count)").bold()
                }
            }
        }
        .padding(16)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct KentsView: View {
    @Environment(Panel.self) var panel
    @State var toDelete: Kent?
    @State var toReset: Kent?
    /// (who, temporary password) shown after a reset
    @State var newPassword: (String, String)?

    var body: some View {
        Table(panel.kents) {
            TableColumn("") { k in Presence(k: k) }.width(14)
            TableColumn("Имя") { k in Text(k.name).bold() }
            TableColumn("Юзернейм") { k in
                if k.owner { Text("@JT").fontWeight(.heavy).foregroundStyle(accent).shadow(color: accent, radius: 4) }
                else { Text("@" + k.username).foregroundStyle(.secondary) }
            }
            TableColumn("Версия") { k in Text(k.version.isEmpty ? "≤ 0.8" : k.version).font(.system(.body, design: .monospaced)) }.width(70)
            // apps up to 0.8 don't say when they are open, so their time is only a guess
            TableColumn("Был в сети") { k in
                Text(k.online ? "сейчас" : k.version.isEmpty ? "неизвестно (≤ 0.8)" : ago(k.seen)).foregroundStyle(k.online ? .green : .primary)
            }
            TableColumn("Пароль") { k in
                Text(k.has_password == true ? "есть" : "нет").foregroundStyle(k.has_password == true ? .primary : .secondary)
            }.width(60)
            TableColumn("Сообщений") { k in Text("\(k.msgs)") }.width(80)
            TableColumn("Регистрация") { k in Text(ago(k.created)) }
            TableColumn("Профиль") { k in
                Text([k.avatar ? "аватарка" : "", k.birthday, k.gender == "m" ? "парень" : k.gender == "f" ? "девушка" : ""]
                    .filter { !$0.isEmpty }.joined(separator: " · ")).foregroundStyle(.secondary)
            }
        }
        .contextMenu(forSelectionType: Kent.ID.self) { ids in
            if let id = ids.first, let k = panel.kents.first(where: { $0.id == id }) {
                Button("Сбросить пароль @\(k.username)…") { toReset = k }
                if !k.owner { Button("Удалить аккаунт @\(k.username)…", role: .destructive) { toDelete = k } }
            }
        }
        .confirmationDialog("Сбросить пароль @\(toReset?.username ?? "")?", isPresented: .init(get: { toReset != nil }, set: { if !$0 { toReset = nil } })) {
            Button("Сбросить") {
                if let k = toReset { Task { if let p = await panel.resetPassword(k) { newPassword = (k.username, p) } } }
            }
        } message: { Text("Старый пароль перестанет работать. Покажу временный: передай его кенту, он войдёт и сменит в Профиле.") }
        .alert("Новый пароль @\(newPassword?.0 ?? "")", isPresented: .init(get: { newPassword != nil }, set: { if !$0 { newPassword = nil } })) {
            Button("Скопировать") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(newPassword?.1 ?? "", forType: .string)
            }
            Button("Готово", role: .cancel) {}
        } message: { Text(newPassword?.1 ?? "").font(.system(.title, design: .monospaced)) }
        .confirmationDialog("Удалить @\(toDelete?.username ?? "")?", isPresented: .init(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })) {
            Button("Удалить навсегда", role: .destructive) {
                if let k = toDelete { Task { await panel.act("Удаление…", "delete_user", "\(k.id)") } }
            }
        } message: { Text("Аккаунт, его личные чаты (у обеих сторон) и его сообщения в группах. Вернуть нельзя, только из бэкапа.") }
    }
}

struct ChatsView: View {
    @Environment(Panel.self) var panel
    @State var toDelete: Chat?

    var body: some View {
        Table(panel.chats) {
            TableColumn("Чат") { c in Text(c.title).bold() }
            TableColumn("Тип") { c in Text(c.kind == "group" ? "группа" : "личный") }.width(70)
            TableColumn("Участники") { c in Text(c.members.joined(separator: ", ")).foregroundStyle(.secondary) }
            TableColumn("Сообщений") { c in Text("\(c.msgs)") }.width(80)
            TableColumn("Последнее") { c in Text(ago(c.last)) }
        }
        .contextMenu(forSelectionType: Chat.ID.self) { ids in
            if let id = ids.first, let c = panel.chats.first(where: { $0.id == id }) {
                Button("Удалить чат «\(c.title)»…", role: .destructive) { toDelete = c }
            }
        }
        .confirmationDialog("Удалить чат «\(toDelete?.title ?? "")»?", isPresented: .init(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })) {
            Button("Удалить у всех", role: .destructive) {
                if let c = toDelete { Task { await panel.act("Удаление…", "delete_chat", "\(c.id)") } }
            }
        } message: { Text("Все сообщения и фото этого чата пропадут у всех участников.") }
    }
}

struct LogView: View {
    @Environment(Panel.self) var panel
    @State var live = true

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Toggle("Обновлять каждые 5 с", isOn: $live)
                Spacer()
                Button("Обновить") { Task { await panel.loadLog() } }
            }
            .padding(10)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(Array(panel.log.enumerated()), id: \.offset) { i, line in
                            Text(line).font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(line.contains("Error") || line.contains("Traceback") ? .red : .primary)
                                .textSelection(.enabled).id(i)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                }
                .background(.black.opacity(0.3))
                .onChange(of: panel.log.count) { proxy.scrollTo(panel.log.count - 1, anchor: .bottom) }
            }
        }
        .task(id: live) {
            await panel.loadLog()
            while live && !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                await panel.loadLog()
            }
        }
    }
}

struct SettingsView: View {
    @Environment(Panel.self) var panel
    @AppStorage("host") var host = "user@example.local"
    @AppStorage("token") var token = ""
    @State var invite = ""

    var body: some View {
        Form {
            Section("Код приглашения") {
                HStack {
                    TextField("9 цифр", text: $invite).font(.system(.body, design: .monospaced))
                    Button("Сохранить") { Task { await panel.act("Сохраняем…", "set_invite", invite) } }
                        .disabled(invite.count != 9 || !invite.allSatisfy(\.isNumber))
                }
                Text("Новые кенты регистрируются только с этим кодом. Уже вошедших смена не трогает.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Подключение") {
                TextField("ssh", text: $host)
                Text("Адрес ПК меняется, если сменилась сеть (например, раздача с телефона). Нужен только дома.").font(.caption).foregroundStyle(.secondary)
                SecureField("Токен админа", text: $token)
                Text(token.isEmpty ? "Пусто: панель возьмёт токен сама при первом подключении из дома, после этого работает откуда угодно."
                     : "Есть: панель ходит через туннель, из дома не обязательно.").font(.caption).foregroundStyle(.secondary)
                LabeledContent("Адрес для телефонов", value: publicURL).textSelection(.enabled)
            }
        }
        .formStyle(.grouped)
        .onAppear { invite = panel.status?.invite ?? "" }
    }
}

struct Root: View {
    @Environment(Panel.self) var panel
    @State var tab: Tab = .overview

    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 0) {
                List(Tab.allCases, selection: $tab) { t in Label(t.rawValue, systemImage: t.icon).tag(t) }
                VStack(alignment: .leading, spacing: 4) {
                    if !panel.busy.isEmpty { HStack { ProgressView().controlSize(.small); Text(panel.busy) } }
                    if let u = panel.updated { Text("Обновлено \(u.formatted(date: .omitted, time: .standard))").foregroundStyle(.secondary) }
                }
                .font(.caption).padding(12)
            }
            .navigationSplitViewColumnWidth(170)
        } detail: {
            VStack(spacing: 0) {
                if !panel.error.isEmpty {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(panel.error).lineLimit(2)
                        Spacer()
                        Button("Скрыть") { panel.error = "" }.buttonStyle(.borderless)
                    }
                    .padding(10).background(.red.opacity(0.25))
                }
                switch tab {
                case .overview: Overview()
                case .kents: KentsView()
                case .chats: ChatsView()
                case .log: LogView()
                case .settings: SettingsView()
                }
            }
            .navigationTitle("JT Server")
            .toolbar { Button { Task { await panel.refresh() } } label: { Image(systemName: "arrow.clockwise") }.help("Обновить") }
        }
        .tint(accent)
        .task {
            // status every 15 s; the log tab polls on its own
            while !Task.isCancelled {
                await panel.refresh()
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }
}

@main
struct JTServerApp: App {
    @State var panel = Panel()

    var body: some Scene {
        WindowGroup {
            Root().environment(panel).frame(minWidth: 860, minHeight: 560).preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
    }
}
