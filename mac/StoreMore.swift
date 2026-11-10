import AppKit
import SwiftUI

/// A place on the map; kind = a preset id (SPOT_KINDS) or the author's own category; vis = "kents" or "me".
struct Spot: Identifiable, Equatable {
    let id: Int
    let ownerId: Int
    let ownerName: String
    var title: String
    var kind: String
    var lat: Double
    var lng: Double
    var about: String
    var photos: [String]
    var vis: String
    /// «Я тут»: (user id, name, avatar)
    var here: [Int]
    var hereNames: [String]

    init(_ j: JSON) {
        id = j["id"] as? Int ?? 0
        ownerId = j["owner_id"] as? Int ?? 0
        ownerName = j["owner_name"] as? String ?? ""
        title = j["title"] as? String ?? ""
        kind = j["kind"] as? String ?? "place"
        lat = (j["lat"] as? NSNumber)?.doubleValue ?? 0
        lng = (j["lng"] as? NSNumber)?.doubleValue ?? 0
        about = j["about"] as? String ?? ""
        photos = j["photos"] as? [String] ?? []
        vis = j["vis"] as? String ?? "kents"
        let h = j["here"] as? [JSON] ?? []
        here = h.compactMap { $0["user_id"] as? Int }
        hereNames = h.compactMap { $0["name"] as? String }
    }
}

let SPOT_KINDS = [("club", "Клуб"), ("place", "Место"), ("home", "Квартира"), ("park", "Парк"), ("cafe", "Кафе"), ("study", "Учёба")]

func kindLabel(_ k: String) -> String { SPOT_KINDS.first { $0.0 == k }?.1 ?? k }

/// Server calls that the phone has on other screens: groups, profile, kents, spots, cards, media.
extension Store {
    // ---------- groups ----------

    func createGroup(_ title: String, _ ids: [Int]) async throws {
        let j = try await Api.call("POST", "/chats/group", body: ["title": title, "user_ids": ids]) as? JSON ?? [:]
        await refresh()
        open(j["id"] as? Int)
    }

    func addMembers(_ chatId: Int, _ ids: [Int]) async throws {
        try await Api.call("POST", "/chats/\(chatId)/members", body: ["user_ids": ids])
        await refresh()
    }

    func renameGroup(_ chatId: Int, _ title: String) async throws {
        try await Api.call("POST", "/chats/\(chatId)/title", body: ["title": title])
        await refresh()
    }

    func groupAvatar(_ chatId: Int, _ img: NSImage) async throws {
        guard let (jpeg, _, _) = Store.jpeg(img, max: 512) else { return }
        try await Api.call("POST", "/chats/\(chatId)/avatar", raw: jpeg)
        await refresh()
    }

    func leaveGroup(_ chatId: Int) async throws {
        try await Api.call("POST", "/chats/\(chatId)/leave")
        if openChat == chatId { openChat = nil }
        chats.removeAll { $0.id == chatId }
    }

    // ---------- chat menu ----------

    func setAlias(_ chatId: Int, _ alias: String) async {
        _ = try? await Api.call("POST", "/chats/\(chatId)/settings", body: ["alias": alias])
        await refresh()
    }

    func clearChat(_ chatId: Int) async {
        _ = try? await Api.call("POST", "/chats/\(chatId)/clear")
        if openChat == chatId { openChat = nil }
        messages[chatId] = nil
        chats.removeAll { $0.id == chatId }
    }

    func blocks() async -> [User] {
        ((try? await Api.call("GET", "/blocks")) as? [JSON] ?? []).map(User.init)
    }

    func setBlocked(_ userId: Int, _ on: Bool) async {
        _ = try? await Api.call(on ? "POST" : "DELETE", "/blocks/\(userId)")
    }

    func removeKent(_ userId: Int) async {
        _ = try? await Api.call("POST", "/kents/\(userId)/remove")
        await refresh()
    }

    func setKent(_ userId: Int, _ on: Bool) async {
        _ = try? await Api.call("POST", "/friends/\(userId)", body: ["kent": on])
        if let i = people.firstIndex(where: { $0.id == userId }) { people[i].kent = on }
    }

    func isKent(_ userId: Int) -> Bool { people.first { $0.id == userId }?.kent ?? false }

    // ---------- profile ----------

    func saveProfile(name: String, username: String, about: String, birthday: String, gender: String) async throws {
        me = User(try await Api.call("POST", "/me", body: ["name": name, "username": username, "about": about,
                                                         "birthday": birthday, "gender": gender]) as? JSON ?? [:])
    }

    func usernameCheck(_ u: String) async -> (Bool, String?) {
        guard let j = try? await Api.call("GET", "/username/\(u.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? u)") as? JSON
        else { return (true, nil) }
        return (j["free"] as? Bool ?? false, j["error"] as? String)
    }

    func setAvatar(_ img: NSImage?) async throws {
        if let img {
            guard let (jpeg, _, _) = Store.jpeg(img, max: 512, square: true) else { return }
            me = User(try await Api.call("POST", "/me/avatar", raw: jpeg) as? JSON ?? [:])
        } else {
            me = User(try await Api.call("DELETE", "/me/avatar") as? JSON ?? [:])
        }
    }

    func logoutAll() async throws {
        let j = try await Api.call("POST", "/me/logout_all") as? JSON ?? [:]
        if let t = j["token"] as? String {
            Api.token = t
            UserDefaults.standard.set(t, forKey: SERVER.contains("tuna.free") ? "token" : "token@" + SERVER)
            toast = "Остальные устройства вышли"
        }
    }

    // ---------- messages ----------

    func forward(_ m: Msg, to chatId: Int) {
        sendRaw(["type": "send", "chat_id": chatId, "forward": m.id, "client_id": UUID().uuidString])
    }

    func searchIn(_ chatId: Int, _ q: String) async -> [Msg] {
        ((try? await Api.call("GET", "/chats/\(chatId)/search", query: ["q": q])) as? [JSON] ?? []).map(Msg.init)
    }

    /// Raw body to /upload; returns the server file name.
    func upload(_ data: Data, ext: String, big: Bool = false) async throws -> String {
        var q = ["ext": ext]
        if big { q["big"] = "1" }
        let j = try await Api.call("POST", "/upload", query: q, raw: data, timeout: 1200) as? JSON ?? [:]
        guard let n = j["name"] as? String else { throw ApiError(message: "Файл не загрузился") }
        return n
    }

    func sendVoice(_ chatId: Int, _ file: URL, ms: Int, wave: String, replyTo: Int = 0) async {
        do {
            let name = try await upload(try Data(contentsOf: file), ext: "m4a")
            try await Api.call("POST", "/chats/\(chatId)/media", body: ["kind": "voice", "media": name, "duration": ms, "wave": wave,
                                                                        "client_id": UUID().uuidString, "reply_to": replyTo])
        } catch { toast = error.localizedDescription }
    }

    func sendCircle(_ chatId: Int, _ file: URL, ms: Int, thumb: NSImage?) async {
        toast = "Отправляю кружок…"
        do {
            let name = try await upload(try Data(contentsOf: file), ext: "mp4")
            var t = ""
            if let thumb, let (jpeg, _, _) = Store.jpeg(thumb, max: 400, square: true) { t = try await upload(jpeg, ext: "jpg") }
            try await Api.call("POST", "/chats/\(chatId)/media", body: ["kind": "circle", "media": name, "duration": ms, "thumb": t,
                                                                        "client_id": UUID().uuidString])
            toast = nil
        } catch { toast = error.localizedDescription }
    }

    // ---------- cards ----------

    func card(_ path: String, _ body: JSON) async -> Bool {
        do {
            let j = try await Api.call("POST", path, body: body) as? JSON
            // the "card" event comes only to people online; update my copy right away
            if let j, let t = j["type"] as? String, let id = j["id"] as? Int { applyCard(t, id, j) }
            return true
        } catch {
            toast = error.localizedDescription
            return false
        }
    }

    private func applyCard(_ type: String, _ id: Int, _ j: JSON) {
        for (cid, list) in messages {
            if let i = list.firstIndex(where: { $0.cardType == type && $0.card?["id"] as? Int == id }) {
                messages[cid]![i].card = j
            }
        }
    }

    func watchState(_ id: Int) async -> JSON? {
        try? await Api.call("GET", "/watch/\(id)") as? JSON
    }

    // ---------- spots ----------

    func loadSpots() async {
        guard let list = try? await Api.call("GET", "/spots") as? [JSON] else { return }
        spots = list.map(Spot.init)
    }

    func saveSpot(_ id: Int?, _ body: JSON) async throws {
        if let id { try await Api.call("PUT", "/spots/\(id)", body: body) } else { try await Api.call("POST", "/spots", body: body) }
        await loadSpots()
    }

    func deleteSpot(_ id: Int) async {
        _ = try? await Api.call("DELETE", "/spots/\(id)")
        await loadSpots()
    }

    func callToSpot(_ id: Int, _ text: String) async {
        do {
            let j = try await Api.call("POST", "/spots/\(id)/call", body: ["text": text]) as? JSON ?? [:]
            toast = "Позвал кентов: \(j["sent"] as? Int ?? 0)"
            await refresh()
        } catch { toast = error.localizedDescription }
    }

    func checkin(_ spotId: Int) async {
        _ = try? await Api.call("POST", "/me/checkin", body: ["spot_id": spotId])
        await loadSpots()
    }
}
