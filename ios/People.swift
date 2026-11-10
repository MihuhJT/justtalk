import UIKit
import SwiftUI

// Groups, people, profile: the phone's group info, PersonScreen, Profile, Kents, ChatMenu.

/// Pick people from my chats (and search by @username).
struct PeoplePicker: View {
    @EnvironmentObject var store: Store
    @Binding var picked: Set<Int>
    var exclude: Set<Int> = []
    @State private var query = ""
    @State private var found: [User] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Field("Найти по @юзернейму", text: $query)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(list) { u in
                        Button {
                            if picked.contains(u.id) { picked.remove(u.id) } else { picked.insert(u.id) }
                        } label: {
                            HStack(spacing: 10) {
                                Avatar(name: u.name, photo: u.avatar, size: 32)
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(u.name).font(jt(14, .semibold)).foregroundStyle(C.ink)
                                    Text(u.handle).font(jt(11)).foregroundStyle(C.mut)
                                }
                                Spacer()
                                Image(systemName: picked.contains(u.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(picked.contains(u.id) ? C.acc : C.mut)
                            }
                            .padding(6).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .frame(height: 240)
        }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { found = []; return }
            try? await Task.sleep(for: .milliseconds(250))
            found = await store.search(q)
        }
    }

    private var list: [User] {
        let base = query.isEmpty ? store.people.filter { !$0.removed } : found
        return base.filter { !exclude.contains($0.id) && $0.id != store.meId }
    }
}

struct NewGroupSheet: View {
    @EnvironmentObject var store: Store
    @State private var title = ""
    @State private var picked: Set<Int> = []

    var body: some View {
        SheetFrame(title: "Новая группа", ok: "Создать", canOk: !title.isEmpty && !picked.isEmpty, action: {
            do { try await store.createGroup(title, Array(picked)); return true } catch { store.toast = error.localizedDescription; return false }
        }) {
            Field("Название группы", text: $title)
            PeoplePicker(picked: $picked)
        }
    }
}

struct GroupInfoSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let chatId: Int
    @State private var title = ""
    @State private var adding = false
    @State private var picked: Set<Int> = []
    @State private var leaving = false
    @State private var person: User?

    var body: some View {
        let chat = store.chat(chatId)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Button(action: pickAvatar) {
                    Avatar(name: chat?.title ?? "", photo: chat?.avatar ?? "", size: 64)
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: "camera.fill").font(.system(size: 10)).foregroundStyle(.white).padding(5).background(C.acc, in: Circle())
                        }
                }
                .buttonStyle(.plain).help("Фото группы")
                VStack(alignment: .leading) {
                    Field("Название", text: $title)
                    if title != (chat?.title ?? "") && !title.isEmpty {
                        Button("Переименовать") { Task { try? await store.renameGroup(chatId, title) } }
                    }
                }
            }
            Text("Участники · \(chat?.members.count ?? 0)").font(jt(13, .semibold)).foregroundStyle(C.mut)
            if adding {
                PeoplePicker(picked: $picked, exclude: Set(chat?.members.map(\.id) ?? []))
                HStack {
                    Button("Отмена") { adding = false; picked = [] }
                    Spacer()
                    Button("Добавить \(picked.count)") {
                        Task {
                            do { try await store.addMembers(chatId, Array(picked)) } catch { store.toast = error.localizedDescription }
                            adding = false
                            picked = []
                        }
                    }
                    .disabled(picked.isEmpty)
                }
            } else {
                ScrollView {
                    VStack(spacing: 2) {
                        ForEach(chat?.members ?? []) { u in
                            Button { if u.id != store.meId { person = u } } label: {
                                HStack(spacing: 10) {
                                    Avatar(name: u.name, photo: u.avatar, size: 32, online: store.presence[u.id]?.0 ?? false)
                                    Text(u.id == store.meId ? "\(u.name) (ты)" : u.name).font(jt(14)).foregroundStyle(C.ink)
                                    Spacer()
                                    Text(u.handle).font(jt(12, u.isOwner ? .bold : .regular)).foregroundStyle(u.isOwner ? C.acc : C.mut)
                                }
                                .padding(6).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxHeight: 260)
                Button("+ Добавить кентов") { adding = true }.buttonStyle(.plain).foregroundStyle(C.acc)
            }
            HStack {
                Button(leaving ? "Точно выйти?" : "Выйти из группы", role: .destructive) {
                    if !leaving { leaving = true; return }
                    Task {
                        try? await store.leaveGroup(chatId)
                        dismiss()
                    }
                }
                Spacer()
                Button("Готово") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(24).frame(maxWidth: .infinity).background(C.bg)
        .onAppear { title = chat?.title ?? "" }
        .sheet(item: $person) { u in PersonSheet(user: u) }
    }

    private func pickAvatar() {
        let p = FilePickerRequest()
        p.allowedContentTypes = [.image]
        p.present { urls in
            guard let u = urls.first, let img = UIImage(contentsOfFile: u.path) else { return }
            Task { try? await store.groupAvatar(chatId, img) }
        }
    }
}

/// Someone's profile: open dm, call, кент / знакомый, rename, block, remove.
struct PersonSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let user: User
    @State private var alias = ""
    @State private var blocked = false

    var body: some View {
        let dm = store.chats.first { !$0.isGroup && $0.members.contains { $0.id == user.id } }
        VStack(spacing: 12) {
            Avatar(name: user.name, photo: user.avatar, size: 84, online: store.presence[user.id]?.0 ?? false)
            Text(user.name).font(jt(20, .bold)).foregroundStyle(C.ink)
            Text(user.handle).font(jt(14, user.isOwner ? .bold : .regular)).foregroundStyle(user.isOwner ? C.acc : C.mut)
            let line = store.presenceLine(user.id)
            if !line.isEmpty { Text(line).font(jt(12)).foregroundStyle(line == "в сети" ? C.green : C.mut) }
            if !user.about.isEmpty { Text(user.about).font(jt(13)).foregroundStyle(C.ink).multilineTextAlignment(.center) }
            if let f = facts { Text(f).font(jt(12)).foregroundStyle(C.mut) }
            HStack(spacing: 8) {
                CardButton(title: "Написать", on: true, ink: C.ink, mine: false) {
                    Task { await store.openDm(user.id) }
                    store.tab = "chats"
                    dismiss()
                }
                if let dm {
                    CardButton(title: "Позвонить", ink: C.ink, mine: false) {
                        Calls.shared.start(dm.id)
                        dismiss()
                    }
                }
            }
            Toggle(isOn: Binding(get: { store.isKent(user.id) }, set: { v in Task { await store.setKent(user.id, v) } })) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Кент").font(jt(14, .semibold))
                    Text("Кенты видят твои споты и получают «Позвать на спот»").font(jt(11)).foregroundStyle(C.mut)
                }
            }
            .toggleStyle(.switch)
            if let dm {
                HStack {
                    Field("Имя для себя", text: $alias)
                    Button("Сохранить") { Task { await store.setAlias(dm.id, alias) } }
                }
            }
            HStack {
                Button(blocked ? "Разблокировать" : "Заблокировать", role: .destructive) {
                    let next = !blocked
                    Task { await store.setBlocked(user.id, next); blocked = next }
                }
                Spacer()
                Button("Удалить из кентов", role: .destructive) {
                    Task { await store.removeKent(user.id) }
                    dismiss()
                }
            }
            .font(jt(12))
            HStack { Spacer(); Button("Готово") { dismiss() }.keyboardShortcut(.cancelAction) }
        }
        .padding(24).frame(maxWidth: .infinity).background(C.bg)
        .task {
            alias = dm?.alias ?? ""
            blocked = await store.blocks().contains { $0.id == user.id }
        }
    }

    private var facts: String? {
        var out: [String] = []
        if user.gender == "m" { out.append("Парень") } else if user.gender == "f" { out.append("Девушка") }
        let f = DateFormatter()
        f.dateFormat = "dd.MM.yyyy"
        if let b = f.date(from: user.birthday), let y = Calendar.current.dateComponents([.year], from: b, to: Date()).year { out.append("\(y) лет") }
        return out.isEmpty ? nil : out.joined(separator: " · ")
    }
}

struct ProfileView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var username = ""
    @State private var about = ""
    @State private var birthday = ""
    @State private var gender = ""
    @State private var usernameError: String?
    @State private var msg: String?
    @State private var page = "main"
    @State private var old = ""
    @State private var new1 = ""
    @State private var new2 = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch page {
            case "password": password
            case "kents": KentsPage(back: { page = "main" })
            case "blocks": BlocksPage(back: { page = "main" })
            default: main
            }
        }
        .padding(24).frame(maxWidth: .infinity).background(C.bg)
        .onAppear(perform: fill)
    }

    private var main: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Menu {
                    Button("Выбрать фото…", action: pickAvatar)
                    if store.me?.avatar.isEmpty == false { Button("Убрать фото") { Task { try? await store.setAvatar(nil) } } }
                } label: {
                    Avatar(name: store.me?.name ?? "", photo: store.me?.avatar ?? "", size: 72)
                }
                .frame(width: 72)
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.me?.name ?? "").font(jt(20, .bold)).foregroundStyle(C.ink)
                    Text(store.me?.handle ?? "").font(jt(13)).foregroundStyle(store.me?.isOwner == true ? C.acc : C.mut)
                }
            }
            Field("Имя", text: $name)
            Field("@юзернейм", text: $username)
            if let usernameError { Text(usernameError).font(jt(11)).foregroundStyle(C.red) }
            Field("О себе", text: $about)
            HStack {
                Field("Дата рождения ДД.ММ.ГГГГ", text: $birthday)
                Picker("", selection: $gender) {
                    Text("—").tag("")
                    Text("Парень").tag("m")
                    Text("Девушка").tag("f")
                }
                .labelsHidden().frame(width: 110)
            }
            Button("Сохранить профиль") {
                Task {
                    do {
                        try await store.saveProfile(name: name, username: username, about: about, birthday: birthday, gender: gender)
                        msg = "Сохранено"
                    } catch { msg = error.localizedDescription }
                }
            }
            .disabled(usernameError != nil)
            Divider()
            Toggle("Показывать «в сети»", isOn: Binding(get: { store.showOnline }, set: { store.showOnline = $0 })).toggleStyle(.switch)
            HStack(spacing: 14) {
                Button("Кенты") { page = "kents" }
                Button("Заблокированные") { page = "blocks" }
                Button("Сменить пароль") { page = "password"; msg = nil }
            }
            .buttonStyle(.plain).foregroundStyle(C.acc).font(jt(13, .semibold))
            if let msg { Text(msg).font(jt(12)).foregroundStyle(C.mut) }
            HStack {
                Menu("Выйти") {
                    Button("Выйти на этом iPhone") { store.logout(); dismiss() }
                    Button("Выйти на всех других устройствах") { Task { try? await store.logoutAll() } }
                }
                .frame(width: 90)
                Spacer()
                Button("Готово") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            Text("JustTalk \(APP_VERSION)").font(jt(10)).foregroundStyle(C.mut)
        }
        .task(id: username) {
            let u = username.trimmingCharacters(in: .whitespaces).lowercased()
            guard !u.isEmpty, u != store.me?.username else { usernameError = nil; return }
            try? await Task.sleep(for: .milliseconds(300))
            let (free, err) = await store.usernameCheck(u)
            usernameError = err ?? (free ? nil : "@\(u) уже занят")
        }
    }

    private var password: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Сменить пароль").font(jt(20, .bold)).foregroundStyle(C.ink)
            Field("Старый пароль", text: $old, secure: true)
            Field("Новый пароль, минимум 6", text: $new1, secure: true)
            Field("Ещё раз", text: $new2, secure: true)
            if let msg { Text(msg).font(jt(12)).foregroundStyle(C.red) }
            HStack {
                Button("Назад") { page = "main" }
                Spacer()
                Button("Сохранить") {
                    guard new1 == new2 else { msg = "Пароли не совпадают"; return }
                    Task {
                        do {
                            try await store.setPassword(new1, old: old)
                            msg = "Пароль изменён"
                            page = "main"
                        } catch { msg = error.localizedDescription }
                    }
                }
                .keyboardShortcut(.defaultAction)
            }
        }
    }

    private func fill() {
        guard let me = store.me else { return }
        name = me.name; username = me.username; about = me.about; birthday = me.birthday; gender = me.gender
    }

    private func pickAvatar() {
        let p = FilePickerRequest()
        p.allowedContentTypes = [.image]
        p.present { urls in
            guard let u = urls.first, let img = UIImage(contentsOfFile: u.path) else { return }
            Task { try? await store.setAvatar(img) }
        }
    }
}

/// Кент or просто знакомый for everyone I talk to.
struct KentsPage: View {
    @EnvironmentObject var store: Store
    let back: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Кенты").font(jt(20, .bold)).foregroundStyle(C.ink)
            Text("Кенты видят твои споты (кроме «Только я»), твоё «Я тут» и получают «Позвать на спот». Остальные — просто знакомые.")
                .font(jt(12)).foregroundStyle(C.mut)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(store.people.filter { !$0.removed }) { u in
                        Toggle(isOn: Binding(get: { store.isKent(u.id) }, set: { v in Task { await store.setKent(u.id, v) } })) {
                            HStack(spacing: 10) {
                                Avatar(name: u.name, photo: u.avatar, size: 30)
                                Text(u.name).font(jt(14)).foregroundStyle(C.ink)
                                Text(u.handle).font(jt(11)).foregroundStyle(C.mut)
                            }
                        }
                        .toggleStyle(.switch).padding(4)
                    }
                }
            }
            .frame(height: 320)
            Button("Назад", action: back)
        }
    }
}

struct BlocksPage: View {
    @EnvironmentObject var store: Store
    let back: () -> Void
    @State private var list: [User] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Заблокированные").font(jt(20, .bold)).foregroundStyle(C.ink)
            if list.isEmpty { Text("Никого").font(jt(13)).foregroundStyle(C.mut) }
            ForEach(list) { u in
                HStack {
                    Avatar(name: u.name, photo: u.avatar, size: 30)
                    Text(u.name).font(jt(14)).foregroundStyle(C.ink)
                    Spacer()
                    Button("Разблокировать") {
                        Task {
                            await store.setBlocked(u.id, false)
                            list = await store.blocks()
                        }
                    }
                }
            }
            Button("Назад", action: back)
        }
        .task { list = await store.blocks() }
    }
}

struct ForwardSheet: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let msg: Msg

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Переслать").font(jt(20, .bold)).foregroundStyle(C.ink)
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(store.chats) { c in
                        Button {
                            store.forward(msg, to: c.id)
                            store.toast = "Переслано в «\(c.title)»"
                            dismiss()
                        } label: {
                            HStack(spacing: 10) {
                                Avatar(name: c.title, photo: c.isGroup ? c.avatar : (c.other(store.meId)?.avatar ?? ""), size: 32)
                                Text(c.title).font(jt(14)).foregroundStyle(C.ink)
                                Spacer()
                            }
                            .padding(6).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .frame(height: 320)
            HStack { Spacer(); Button("Отмена") { dismiss() }.keyboardShortcut(.cancelAction) }
        }
        .padding(24).frame(maxWidth: .infinity).background(C.bg)
    }
}
