import UIKit
import SwiftUI

// Live cards of 0.15 Beta, drawn inside the message bubble. State comes from the server (Msg.card)
// and is refreshed by "card" events.

struct CardView: View {
    @EnvironmentObject var store: Store
    let msg: Msg
    let mine: Bool

    var body: some View {
        let c = msg.card ?? [:]
        VStack(alignment: .leading, spacing: 8) {
            if c["gone"] as? Bool == true {
                Text(Msg.cardLabel(msg.cardType ?? "") + " · удалено").font(jt(13)).foregroundStyle(ink.opacity(0.7))
            } else {
                switch msg.cardType {
                case "poll": PollCard(c: c, ink: ink, mine: mine)
                case "gather": GatherCard(c: c, ink: ink, mine: mine)
                case "split": SplitCard(c: c, ink: ink, mine: mine)
                case "watch": WatchCard(c: c, ink: ink, mine: mine, chatId: msg.chatId)
                case "call": CallCard(c: c, ink: ink, mine: mine)
                default: Text(msg.text).font(jt(14)).foregroundStyle(ink)
                }
            }
        }
        .frame(width: 290, alignment: .leading)
    }

    private var ink: Color { mine ? .white : C.ink }
}

func cardHeader(_ icon: String, _ title: String, _ ink: Color) -> some View {
    HStack(spacing: 6) {
        Image(systemName: icon).font(.system(size: 12, weight: .semibold))
        Text(title).font(jt(12, .semibold))
    }
    .foregroundStyle(ink.opacity(0.75))
}

/// Pill button used inside cards.
struct CardButton: View {
    let title: String
    var on = false
    let ink: Color
    let mine: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(jt(13, .semibold)).foregroundStyle(on ? (mine ? C.acc : .white) : ink)
                .padding(.horizontal, 12).frame(height: 30).frame(maxWidth: .infinity)
                .background(on ? (mine ? Color.white : C.acc) : ink.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct PollCard: View {
    @EnvironmentObject var store: Store
    let c: JSON
    let ink: Color
    let mine: Bool

    var body: some View {
        let id = c["id"] as? Int ?? 0
        let options = c["options"] as? [JSON] ?? []
        let my = c["mine"] as? [Int] ?? []
        let closed = c["closed"] as? Bool ?? false
        let multi = c["multi"] as? Bool ?? false
        let total = max(options.reduce(0) { $0 + ($1["votes"] as? Int ?? 0) }, 1)
        cardHeader("chart.bar.fill", (c["anon"] as? Bool == true ? "Анонимный опрос" : "Опрос") + (multi ? " · несколько ответов" : "") + (closed ? " · закрыт" : ""), ink)
        Text(c["question"] as? String ?? "").font(jt(16, .semibold)).foregroundStyle(ink).fixedSize(horizontal: false, vertical: true)
        ForEach(Array(options.enumerated()), id: \.offset) { i, o in
            let votes = o["votes"] as? Int ?? 0
            let picked = my.contains(i)
            Button {
                var next = multi ? (picked ? my.filter { $0 != i } : my + [i]) : (picked ? [] : [i])
                next.sort()
                Task { _ = await store.card("/polls/\(id)/vote", ["options": next]) }
            } label: {
                ZStack(alignment: .leading) {
                    GeometryReader { g in
                        Capsule().fill(ink.opacity(picked ? 0.3 : 0.14)).frame(width: max(g.size.width * Double(votes) / Double(total), 0))
                    }
                    HStack(spacing: 6) {
                        Image(systemName: picked ? "checkmark.circle.fill" : "circle").font(.system(size: 13))
                        Text(o["text"] as? String ?? "").font(jt(14)).lineLimit(2)
                        Spacer()
                        Text("\(votes)").font(jt(13, .semibold))
                    }
                    .padding(.horizontal, 10)
                }
                .foregroundStyle(ink).frame(height: 32).background(ink.opacity(0.06), in: Capsule())
            }
            .buttonStyle(.plain).disabled(closed)
            .help((o["voters"] as? [Int] ?? []).compactMap { store.user($0)?.name }.joined(separator: ", "))
        }
        HStack {
            Text("Проголосовали: \(c["voters"] as? Int ?? 0)").font(jt(12)).foregroundStyle(ink.opacity(0.7))
            Spacer()
            if c["owner_id"] as? Int == store.meId && !closed {
                Button("Закрыть опрос") { Task { _ = await store.card("/polls/\(id)/close", [:]) } }
                    .buttonStyle(.plain).font(jt(12, .semibold)).foregroundStyle(ink)
            }
        }
    }
}

func gatherTime(_ t: Int) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    f.dateFormat = "EE, d MMMM 'в' HH:mm"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
}

struct GatherCard: View {
    @EnvironmentObject var store: Store
    let c: JSON
    let ink: Color
    let mine: Bool

    var body: some View {
        let id = c["id"] as? Int ?? 0
        let rsvp = c["rsvp"] as? [String: [Int]] ?? [:]
        let cancelled = c["cancelled"] as? Bool ?? false
        let my = ["yes", "maybe", "no"].first { rsvp[$0]?.contains(store.meId) == true }
        cardHeader("calendar", cancelled ? "Сбор отменён" : "Сбор", ink)
        Text(c["title"] as? String ?? "").font(jt(16, .semibold)).foregroundStyle(ink).strikethrough(cancelled)
        Label(gatherTime(c["at"] as? Int ?? 0), systemImage: "clock").font(jt(13)).foregroundStyle(ink)
        if let s = c["spot"] as? JSON {
            Button {
                store.spotFocus = s["id"] as? Int
                store.tab = "spots"
            } label: { Label(s["title"] as? String ?? "Спот", systemImage: "mappin.circle.fill").font(jt(13, .semibold)).foregroundStyle(ink) }
                .buttonStyle(.plain)
        } else if let p = c["place"] as? String, !p.isEmpty {
            Label(p, systemImage: "mappin").font(jt(13)).foregroundStyle(ink)
        }
        if let n = c["note"] as? String, !n.isEmpty { Text(n).font(jt(13)).foregroundStyle(ink.opacity(0.85)) }
        if !cancelled {
            HStack(spacing: 6) {
                ForEach([("yes", "Иду"), ("maybe", "Может"), ("no", "Не иду")], id: \.0) { k, t in
                    CardButton(title: "\(t) \(rsvp[k]?.count ?? 0)", on: my == k, ink: ink, mine: mine) {
                        Task { _ = await store.card("/gathers/\(id)/rsvp", ["status": my == k ? "" : k]) }
                    }
                }
            }
            let going = (rsvp["yes"] ?? []).compactMap { store.user($0)?.name }
            if !going.isEmpty { Text("Идут: " + going.joined(separator: ", ")).font(jt(12)).foregroundStyle(ink.opacity(0.7)) }
            if c["owner_id"] as? Int == store.meId {
                Button("Отменить сбор") { Task { _ = await store.card("/gathers/\(id)/cancel", [:]) } }
                    .buttonStyle(.plain).font(jt(12, .semibold)).foregroundStyle(ink.opacity(0.8))
            }
        }
    }
}

struct SplitCard: View {
    @EnvironmentObject var store: Store
    let c: JSON
    let ink: Color
    let mine: Bool

    var body: some View {
        let id = c["id"] as? Int ?? 0
        let shares = c["shares"] as? [JSON] ?? []
        let owner = c["owner_id"] as? Int == store.meId
        let paid = shares.filter { $0["paid"] as? Bool == true }.count
        cardHeader("rublesign.circle.fill", c["closed"] as? Bool == true ? "Все скинулись" : "Скидываемся · \(paid) из \(shares.count)", ink)
        HStack(alignment: .firstTextBaseline) {
            Text(c["title"] as? String ?? "").font(jt(16, .semibold))
            Spacer()
            Text("\(c["total"] as? Int ?? 0) ₽").font(jt(16, .bold))
        }
        .foregroundStyle(ink)
        if let d = c["details"] as? String, !d.isEmpty {
            Button {
                UIPasteboard.general.string = nil
                UIPasteboard.general.string = d
                store.toast = "Скопировано"
            } label: { Label(d, systemImage: "doc.on.doc").font(jt(13)).foregroundStyle(ink) }
                .buttonStyle(.plain).help("Скопировать")
        }
        ForEach(Array(shares.enumerated()), id: \.offset) { _, s in
            let uid = s["user_id"] as? Int ?? 0
            let done = s["paid"] as? Bool ?? false
            let can = uid == store.meId || owner
            Button {
                Task { _ = await store.card("/splits/\(id)/paid", ["user_id": uid, "paid": !done]) }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: done ? "checkmark.circle.fill" : "circle").font(.system(size: 14))
                    Text(uid == store.meId ? "Я" : (store.user(uid)?.name ?? "Кент")).font(jt(14))
                    Spacer()
                    Text("\(s["amount"] as? Int ?? 0) ₽").font(jt(13, .semibold))
                }
                .foregroundStyle(ink.opacity(done ? 0.65 : 1)).contentShape(Rectangle())
            }
            .buttonStyle(.plain).disabled(!can)
        }
    }
}

func sourceLabel(_ s: String) -> String { ["youtube": "YouTube", "vk": "VK Видео"][s] ?? "Видео" }

struct WatchCard: View {
    @EnvironmentObject var store: Store
    let c: JSON
    let ink: Color
    let mine: Bool
    let chatId: Int

    var body: some View {
        let id = c["id"] as? Int ?? 0
        let ended = c["ended"] as? Bool ?? false
        let viewers = c["viewers"] as? [Int] ?? []
        cardHeader("play.rectangle.fill", "Смотрим вместе · " + sourceLabel(c["source"] as? String ?? ""), ink)
        if c["source"] as? String == "youtube", let src = c["src"] as? String {
            AsyncImage(url: URL(string: "https://img.youtube.com/vi/\(src)/hqdefault.jpg")) { i in i.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { Color.black }
                .frame(width: 290, height: 163).clipShape(RoundedRectangle(cornerRadius: 10))
        }
        if let t = c["title"] as? String, !t.isEmpty { Text(t).font(jt(15, .semibold)).foregroundStyle(ink).lineLimit(2) }
        if ended {
            Text("Просмотр закончен").font(jt(13)).foregroundStyle(ink.opacity(0.7))
        } else {
            if !viewers.isEmpty {
                Text("Смотрят: " + viewers.compactMap { store.user($0)?.name }.joined(separator: ", ")).font(jt(12)).foregroundStyle(ink.opacity(0.75))
            }
            HStack(spacing: 6) {
                CardButton(title: "Смотреть", on: true, ink: ink, mine: mine) { WatchWindow.show(id, card: c) }
                if c["owner_id"] as? Int == store.meId {
                    CardButton(title: "Закончить", ink: ink, mine: mine) {
                        Task { _ = try? await Api.call("POST", "/watch/\(id)/end") }
                    }
                }
            }
        }
    }
}

struct CallCard: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var calls = Calls.shared
    let c: JSON
    let ink: Color
    let mine: Bool

    var body: some View {
        let status = c["status"] as? String ?? ""
        let id = c["id"] as? Int ?? 0
        let inCall = c["in"] as? [Int] ?? []
        HStack(spacing: 10) {
            Image(systemName: status == "missed" ? "phone.down.fill" : "phone.fill").font(.system(size: 16))
                .foregroundStyle(status == "missed" ? C.red : ink)
                .frame(width: 36, height: 36).background(ink.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(status == "active" ? "Идёт звонок" : status == "missed" ? (mine ? "Без ответа" : "Пропущенный звонок") : "Звонок")
                    .font(jt(15, .semibold)).foregroundStyle(ink)
                Text(status == "active" ? "В звонке: \(inCall.count)" : status == "ended" ? durationLabel((c["duration"] as? Int ?? 0) * 1000) : "")
                    .font(jt(12)).foregroundStyle(ink.opacity(0.7))
            }
            Spacer()
            if status == "active" && calls.callId != id {
                CardButton(title: "Войти", on: true, ink: ink, mine: mine) { calls.join(id, chatId: c["chat_id"] as? Int ?? 0) }.frame(width: 80)
            }
        }
    }
}

// ---------- creating cards ----------

struct SheetFrame<Content: View>: View {
    let title: String
    let ok: String
    let canOk: Bool
    let action: () async -> Bool
    @ViewBuilder let content: Content
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(jt(20, .bold)).foregroundStyle(C.ink)
            content
            HStack {
                Button("Отмена") { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(busy ? "…" : ok) {
                    busy = true
                    Task {
                        if await action() { dismiss() }
                        busy = false
                    }
                }
                .keyboardShortcut(.defaultAction).disabled(!canOk || busy)
            }
            .padding(.top, 4)
        }
        .padding(24).frame(maxWidth: .infinity).background(C.bg)
    }
}

struct NewPollSheet: View {
    @EnvironmentObject var store: Store
    let chatId: Int
    @State private var q = ""
    @State private var options = ["", ""]
    @State private var multi = false
    @State private var anon = false

    var body: some View {
        let filled = options.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        SheetFrame(title: "Опрос", ok: "Создать", canOk: !q.isEmpty && filled.count >= 2, action: {
            await store.card("/chats/\(chatId)/poll", ["question": q, "options": filled, "multi": multi, "anon": anon, "client_id": UUID().uuidString])
        }) {
            Field("Вопрос", text: $q)
            ForEach(options.indices, id: \.self) { i in
                Field("Вариант \(i + 1)", text: $options[i])
            }
            if options.count < 10 { Button("+ Вариант") { options.append("") }.buttonStyle(.plain).foregroundStyle(C.acc) }
            Toggle("Несколько ответов", isOn: $multi)
            Toggle("Анонимный", isOn: $anon)
        }
    }
}

struct NewGatherSheet: View {
    @EnvironmentObject var store: Store
    let chatId: Int
    @State private var title = ""
    @State private var at = Calendar.current.date(byAdding: .hour, value: 2, to: Date())!
    @State private var place = ""
    @State private var spotId = 0
    @State private var note = ""

    var body: some View {
        SheetFrame(title: "Сбор", ok: "Позвать", canOk: !title.isEmpty, action: {
            await store.card("/chats/\(chatId)/gather", ["title": title, "at": Int(at.timeIntervalSince1970), "place": place,
                                                         "spot_id": spotId, "note": note, "client_id": UUID().uuidString])
        }) {
            Field("Что делаем? Например, «Катаем на Воробьях»", text: $title)
            DatePicker("Когда", selection: $at, in: Date()...).environment(\.locale, Locale(identifier: "ru_RU"))
            Picker("Спот", selection: $spotId) {
                Text("Без спота").tag(0)
                ForEach(store.spots) { s in Text(s.title).tag(s.id) }
            }
            if spotId == 0 { Field("Где (необязательно)", text: $place) }
            Field("Заметка (необязательно)", text: $note)
        }
        .task { if store.spots.isEmpty { await store.loadSpots() } }
    }
}

struct NewSplitSheet: View {
    @EnvironmentObject var store: Store
    let chatId: Int
    @State private var title = ""
    @State private var total = ""
    @State private var details = ""
    @State private var picked: Set<Int> = []

    var body: some View {
        let members = store.chat(chatId)?.members ?? []
        let sum = Int(total.filter(\.isNumber)) ?? 0
        let each = picked.isEmpty ? 0 : sum / picked.count
        SheetFrame(title: "Скидываемся", ok: "Создать", canOk: !title.isEmpty && sum > 0 && !(picked.isEmpty || picked == [store.meId]), action: {
            await store.card("/chats/\(chatId)/split", ["title": title, "total": sum, "user_ids": Array(picked), "details": details,
                                                        "client_id": UUID().uuidString])
        }) {
            Field("На что? Например, «Пицца»", text: $title)
            Field("Сумма, ₽", text: $total)
            Field("Куда кидать: карта или телефон для СБП", text: $details)
            Text("Кто скидывается" + (each > 0 ? " · по \(each) ₽" : "")).font(jt(13, .semibold)).foregroundStyle(C.mut)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(members) { u in
                        Toggle(isOn: Binding(get: { picked.contains(u.id) }, set: { if $0 { picked.insert(u.id) } else { picked.remove(u.id) } })) {
                            Text(u.id == store.meId ? "Я" : u.name).font(jt(14))
                        }
                        .toggleStyle(.switch).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .frame(maxHeight: 180)
        }
        .onAppear { picked = Set(members.map(\.id)) }
    }
}

/// A YouTube or VK link -> (source, src for the server), like the phone's parseVideoLink.
func parseVideoLink(_ link: String) -> (String, String)? {
    let s = link.trimmingCharacters(in: .whitespaces)
    func first(_ p: String) -> [String]? {
        guard let r = try? NSRegularExpression(pattern: p), let m = r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (0..<m.numberOfRanges).map { i in Range(m.range(at: i), in: s).map { String(s[$0]) } ?? "" }
    }
    if let g = first(#"(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})"#) { return ("youtube", g[1]) }
    if let g = first(#"video_ext\.php\?(?=.*oid=(-?\d+))(?=.*[?&]id=(\d+))(?:.*hash=([0-9a-f]+))?"#) {
        return ("vk", [g[1], g[2], g[3]].filter { !$0.isEmpty }.joined(separator: "_"))
    }
    if let g = first(#"(?:vk\.com|vkvideo\.ru|vk\.ru)/.*?video(-?\d+)_(\d+)"#) {
        let hash = first(#"[?&]hash=([0-9a-f]+)"#)?[1] ?? ""
        return ("vk", [g[1], g[2], hash].filter { !$0.isEmpty }.joined(separator: "_"))
    }
    return nil
}

struct NewWatchSheet: View {
    @EnvironmentObject var store: Store
    let chatId: Int
    @State private var link = ""
    @State private var title = ""
    @State private var file: URL?
    @State private var progress = ""

    var body: some View {
        let parsed = parseVideoLink(link)
        SheetFrame(title: "Смотрим вместе", ok: "Позвать смотреть", canOk: parsed != nil || file != nil, action: {
            var source = "", src = ""
            if let file {
                progress = "Загружаю видео…"
                guard let data = try? Data(contentsOf: file, options: .mappedIfSafe),
                      let name = try? await store.upload(data, ext: file.pathExtension.lowercased(), big: true) else {
                    progress = "Не получилось загрузить"
                    return false
                }
                source = "file"; src = name
            } else if let (a, b) = parsed { source = a; src = b }
            let t = title.isEmpty ? (file?.deletingPathExtension().lastPathComponent ?? "") : title
            return await store.card("/chats/\(chatId)/watch", ["source": source, "src": src, "title": t, "client_id": UUID().uuidString])
        }) {
            Field("Ссылка на YouTube или VK Видео", text: $link)
            if !link.isEmpty {
                Text(parsed.map { "✓ " + sourceLabel($0.0) } ?? "Не понял ссылку: нужна ссылка на видео YouTube или VK")
                    .font(jt(12)).foregroundStyle(parsed != nil ? C.green : C.red)
            }
            HStack {
                Button(file == nil ? "Или своё видео с iPhone…" : "Видео: \(file!.lastPathComponent)") {
                    let p = FilePickerRequest()
                    p.allowedContentTypes = [.movie, .mpeg4Movie, .quickTimeMovie]
                    p.present { urls in file = urls.first; link = "" }
                }
                if file != nil { Button("✕") { file = nil }.buttonStyle(.plain) }
            }
            Field("Название (необязательно)", text: $title)
            if !progress.isEmpty { Text(progress).font(jt(12)).foregroundStyle(C.mut) }
            Text("Видео до 1 ГБ. YouTube без VPN может тормозить").font(jt(11)).foregroundStyle(C.mut)
        }
    }
}
