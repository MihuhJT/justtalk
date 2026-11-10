import AppKit
import MapKit
import SwiftUI

// «Споты» like the phone's Spots.kt, on Apple's map: filters, place a new spot by clicking the map,
// spot card with «Я тут» and «Позвать кентов».

struct SpotsView: View {
    @EnvironmentObject var store: Store
    @State private var camera: MapCameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 55.751, longitude: 37.618),
                                                                              span: MKCoordinateSpan(latitudeDelta: 0.25, longitudeDelta: 0.25)))
    @State private var filter = "all"
    @State private var selected: Int?
    @State private var placing = false
    @State private var newPoint: CLLocationCoordinate2D?
    @State private var editing: Spot?

    var body: some View {
        let shown = store.spots.filter { filter == "all" || (filter == "mine" && $0.ownerId == store.meId) || $0.kind == filter }
        ZStack(alignment: .top) {
            MapReader { proxy in
                Map(position: $camera, selection: $selected) {
                    ForEach(shown) { s in
                        Annotation(s.title, coordinate: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng)) {
                            SpotPin(spot: s, on: selected == s.id)
                        }
                        .tag(s.id)
                    }
                    if let p = newPoint { Marker("Новый спот", coordinate: p).tint(C.acc) }
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                .onTapGesture { pt in
                    guard placing, let c = proxy.convert(pt, from: .local) else { return }
                    newPoint = c
                    placing = false
                }
            }
            HStack(spacing: 6) {
                chip("all", "Все")
                chip("mine", "Мои")
                ForEach(kinds, id: \.self) { k in chip(k, kindLabel(k)) }
                Spacer()
                Button {
                    placing.toggle()
                    newPoint = nil
                } label: {
                    Label(placing ? "Кликни по карте" : "Спот", systemImage: placing ? "hand.tap" : "plus")
                        .font(jt(13, .semibold)).foregroundStyle(.white).padding(.horizontal, 14).frame(height: 32)
                        .background(C.acc, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(12)
            if let id = selected, let s = store.spots.first(where: { $0.id == id }) {
                VStack {
                    Spacer()
                    SpotCard(spot: s, onEdit: { editing = s }, onClose: { selected = nil })
                }
                .padding(12)
            }
        }
        .sheet(isPresented: Binding(get: { newPoint != nil }, set: { if !$0 { newPoint = nil } })) {
            if let p = newPoint { SpotEditor(spot: nil, point: p) }
        }
        .sheet(item: $editing) { s in SpotEditor(spot: s, point: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng)) }
        .task { await store.loadSpots() }
        .onAppear(perform: focus)
        .onChange(of: store.spotFocus) { focus() }
    }

    private var kinds: [String] {
        let all = Array(Set(store.spots.map(\.kind)))
        return all.sorted { a, b in
            let ia = SPOT_KINDS.firstIndex { $0.0 == a } ?? 99, ib = SPOT_KINDS.firstIndex { $0.0 == b } ?? 99
            return ia == ib ? a < b : ia < ib
        }
    }

    private func focus() {
        guard let id = store.spotFocus else { return }
        store.spotFocus = nil
        Task {
            if store.spots.first(where: { $0.id == id }) == nil { await store.loadSpots() }
            guard let s = store.spots.first(where: { $0.id == id }) else { store.toast = "Спот скрыт или удалён"; return }
            selected = id
            camera = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lng),
                                                span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)))
        }
    }

    private func chip(_ id: String, _ title: String) -> some View {
        Button { filter = id } label: {
            Text(title).font(jt(13, .medium)).foregroundStyle(filter == id ? .white : C.ink).padding(.horizontal, 12).frame(height: 30)
                .background(filter == id ? C.acc : C.s2.opacity(0.92), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct SpotPin: View {
    let spot: Spot
    let on: Bool

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(on ? C.acc : C.s2).frame(width: on ? 34 : 28, height: on ? 34 : 28)
                Image(systemName: icon(spot.kind)).font(.system(size: on ? 15 : 12, weight: .semibold)).foregroundStyle(.white)
            }
            .overlay(Circle().stroke(.white, lineWidth: 2))
            if !spot.here.isEmpty {
                Text("\(spot.here.count)").font(jt(10, .bold)).foregroundStyle(.white).padding(.horizontal, 5).background(C.green, in: Capsule())
                    .offset(y: -4)
            }
        }
    }
}

func icon(_ kind: String) -> String {
    ["club": "music.note", "place": "mappin", "home": "house.fill", "park": "tree.fill", "cafe": "cup.and.saucer.fill", "study": "book.fill"][kind] ?? "star.fill"
}

struct SpotCard: View {
    @EnvironmentObject var store: Store
    let spot: Spot
    let onEdit: () -> Void
    let onClose: () -> Void
    @State private var calling = false
    @State private var callText = ""

    var body: some View {
        let imHere = spot.here.contains(store.meId)
        let canEdit = spot.ownerId == store.meId || store.me?.isOwner == true
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(kindLabel(spot.kind)).font(jt(12, .semibold)).foregroundStyle(C.acc)
                if spot.vis == "me" { Label("Только я", systemImage: "lock.fill").font(jt(11)).foregroundStyle(C.mut) }
                Spacer()
                Button(action: onClose) { Image(systemName: "xmark").foregroundStyle(C.mut) }.buttonStyle(.plain)
            }
            Text(spot.title).font(jt(18, .bold)).foregroundStyle(C.ink)
            if !spot.about.isEmpty { Text(spot.about).font(jt(13)).foregroundStyle(C.ink) }
            if !spot.photos.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(spot.photos, id: \.self) { p in
                            RemoteImage(name: p).frame(width: 110, height: 80).clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
            }
            Text("Добавил(а) \(spot.ownerId == store.meId ? "ты" : spot.ownerName)").font(jt(11)).foregroundStyle(C.mut)
            if !spot.hereNames.isEmpty {
                Text("Сейчас тут: " + spot.hereNames.joined(separator: ", ")).font(jt(12, .medium)).foregroundStyle(C.green)
            }
            if calling {
                Field("Еду на «\(spot.title)», подтягивайся!", text: $callText)
                HStack {
                    Button("Отмена") { calling = false }
                    Spacer()
                    Button("Отправить всем кентам") {
                        calling = false
                        Task { await store.callToSpot(spot.id, callText) }
                    }
                }
            } else {
                HStack(spacing: 6) {
                    CardButton(title: imHere ? "Ухожу" : "Я тут", on: !imHere, ink: C.ink, mine: false) {
                        Task { await store.checkin(imHere ? 0 : spot.id) }
                    }
                    CardButton(title: "Позвать кентов", ink: C.ink, mine: false) { calling = true }
                    Button {
                        NSWorkspace.shared.open(URL(string: "https://yandex.ru/maps/?rtext=~\(spot.lat),\(spot.lng)&rtt=auto")!)
                    } label: { Image(systemName: "arrow.triangle.turn.up.right.diamond.fill").foregroundStyle(C.ink) }
                        .buttonStyle(.plain).help("Маршрут в Яндекс Картах")
                }
                if canEdit {
                    HStack {
                        Button("Изменить", action: onEdit).buttonStyle(.plain).foregroundStyle(C.acc)
                        Spacer()
                        Button("Удалить") { Task { await store.deleteSpot(spot.id); onClose() } }.buttonStyle(.plain).foregroundStyle(C.red)
                    }
                    .font(jt(12, .semibold))
                }
            }
        }
        .padding(16).frame(width: 360).background(C.bg.opacity(0.95), in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(C.line))
    }
}

struct SpotEditor: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let spot: Spot?
    let point: CLLocationCoordinate2D
    @State private var title = ""
    @State private var kind = "place"
    @State private var custom = ""
    @State private var about = ""
    @State private var photos: [String] = []
    @State private var vis = "kents"
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(spot == nil ? "Новый спот" : "Спот").font(jt(20, .bold)).foregroundStyle(C.ink)
            Field("Название", text: $title)
            Picker("Тип", selection: $kind) {
                ForEach(SPOT_KINDS, id: \.0) { k, t in Text(t).tag(k) }
                Text("Своё…").tag("custom")
            }
            if kind == "custom" { Field("Своя категория", text: $custom) }
            Field("Описание (необязательно)", text: $about)
            HStack(spacing: 6) {
                ForEach(photos, id: \.self) { p in
                    RemoteImage(name: p).frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .topTrailing) {
                            Button { photos.removeAll { $0 == p } } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.white, .black)
                            }.buttonStyle(.plain)
                        }
                }
                if photos.count < 5 {
                    Button(action: addPhotos) {
                        Image(systemName: "photo.badge.plus").font(.system(size: 18)).foregroundStyle(C.mut)
                            .frame(width: 56, height: 56).background(C.s1, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
            }
            Picker("Кто видит", selection: $vis) {
                Text("Кенты").tag("kents")
                Text("Только я").tag("me")
            }
            .pickerStyle(.segmented)
            Text("Кенты — те, кого ты отметил в Профиль → Кенты").font(jt(11)).foregroundStyle(C.mut)
            if let error { Text(error).font(jt(12)).foregroundStyle(C.red) }
            HStack {
                Button("Отмена") { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button(busy ? "…" : "Сохранить", action: save).keyboardShortcut(.defaultAction).disabled(title.isEmpty || busy)
            }
        }
        .padding(24).frame(width: 420).background(C.bg)
        .onAppear {
            guard let s = spot else { return }
            title = s.title
            about = s.about
            photos = s.photos
            vis = s.vis
            if SPOT_KINDS.contains(where: { $0.0 == s.kind }) { kind = s.kind } else { kind = "custom"; custom = s.kind }
        }
    }

    private func addPhotos() {
        let p = NSOpenPanel()
        p.allowsMultipleSelection = true
        p.allowedContentTypes = [.image]
        guard p.runModal() == .OK else { return }
        let urls = Array(p.urls.prefix(5 - photos.count))
        Task {
            for u in urls {
                guard let img = NSImage(contentsOf: u), let (jpeg, _, _) = Store.jpeg(img, max: 1600),
                      let name = try? await store.upload(jpeg, ext: "jpg") else { continue }
                photos.append(name)
            }
        }
    }

    private func save() {
        busy = true
        let k = kind == "custom" ? custom.trimmingCharacters(in: .whitespaces) : kind
        Task {
            do {
                try await store.saveSpot(spot?.id, ["title": title, "kind": k, "lat": point.latitude, "lng": point.longitude,
                                                    "about": about, "photos": photos, "vis": vis])
                dismiss()
            } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}
