import AppKit
import AVKit
import SwiftUI
import WebKit

// «Смотрим вместе» like the phone's Watch.kt: the server holds (playing, pos); remote changes are applied,
// my own play / pause / seek are sent; every second a player that drifted > 2.5 s jumps back.

protocol VideoCtl: AnyObject {
    func play(_ at: Double?)
    func pause(_ at: Double?)
    func seek(_ t: Double)
    func position(_ cb: @escaping (Double) -> Void)
    func close()
}

final class FileCtl: VideoCtl {
    let player: AVPlayer
    init(_ url: URL) { player = AVPlayer(url: url) }
    func play(_ at: Double?) { if let at { seek(at) }; player.play() }
    func pause(_ at: Double?) { player.pause(); if let at { seek(at) } }
    func seek(_ t: Double) { player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) }
    func position(_ cb: @escaping (Double) -> Void) { cb(player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0) }
    func close() { player.pause() }
}

/// YouTube / VK embed page from the server; window.JT is bridged to WKScriptMessageHandler.
final class WebCtl: NSObject, VideoCtl, WKScriptMessageHandler {
    let web: WKWebView
    var onReady: (Double, String) -> Void = { _, _ in }
    var onState: (Int, Double) -> Void = { _, _ in }
    var onError: (String) -> Void = { _ in }

    init(_ url: URL) {
        let cfg = WKWebViewConfiguration()
        cfg.mediaTypesRequiringUserActionForPlayback = []
        let bridge = """
        window.JT={onReady:function(d,t){webkit.messageHandlers.jt.postMessage({k:'ready',d:d,t:t})},
        onState:function(s,t){webkit.messageHandlers.jt.postMessage({k:'state',s:s,t:t})},
        onError:function(e){webkit.messageHandlers.jt.postMessage({k:'error',e:e})}};
        """
        cfg.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        cfg.userContentController.add(self, name: "jt")
        web.load(URLRequest(url: url))
    }

    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let b = m.body as? [String: Any] else { return }
        let num = { (k: String) in (b[k] as? NSNumber)?.doubleValue ?? 0 }
        switch b["k"] as? String {
        case "ready": onReady(num("d"), b["t"] as? String ?? "")
        case "state": onState(Int(num("s")), num("t"))
        default: onError("\(b["e"] ?? "")")
        }
    }

    private func js(_ s: String) { web.evaluateJavaScript(s) }
    private func arg(_ t: Double?) -> String { t.map { String(format: "%.2f", $0) } ?? "null" }
    func play(_ at: Double?) { js("cmd('play',\(arg(at)))") }
    func pause(_ at: Double?) { js("cmd('pause',\(arg(at)))") }
    func seek(_ t: Double) { js("cmd('seek',\(arg(t)))") }
    func position(_ cb: @escaping (Double) -> Void) {
        web.evaluateJavaScript("pos()") { r, _ in let v = (r as? NSNumber)?.doubleValue ?? -1; if v >= 0 { cb(v) } }
    }
    func close() {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "jt")
        web.loadHTMLString("", baseURL: nil)
    }
}

@MainActor
final class Sync: ObservableObject {
    var ctl: VideoCtl?
    var ready = false
    @Published var playing = false
    private var pos = 0.0
    private var at = Date()
    private var quietUntil = Date.distantPast
    let send: (String, Double) -> Void

    init(send: @escaping (String, Double) -> Void) { self.send = send }

    func expected() -> Double { pos + (playing ? Date().timeIntervalSince(at) : 0) }

    func setState(_ p: Bool, _ position: Double) { playing = p; pos = position; at = Date() }

    func remote(_ p: Bool, _ position: Double, mine: Bool) {
        setState(p, position)
        if !mine { apply() }
    }

    func apply() {
        guard let c = ctl, ready else { return }
        quietUntil = Date().addingTimeInterval(1.5)
        if playing { c.play(expected()) } else { c.pause(expected()) }
    }

    func togglePlay() {
        guard let c = ctl else { return }
        c.position { now in
            let next = !self.playing
            self.setState(next, now)
            self.quietUntil = Date().addingTimeInterval(1.5)
            if next { c.play(nil) } else { c.pause(nil) }
            self.send(next ? "play" : "pause", now)
        }
    }

    func seekTo(_ t: Double) {
        let target = max(0, t)
        setState(playing, target)
        quietUntil = Date().addingTimeInterval(1.5)
        ctl?.seek(target)
        send("seek", target)
    }

    func seekBy(_ d: Double) { ctl?.position { self.seekTo($0 + d) } }

    /// the embed's own controls: 1 = playing, 2 = paused (YouTube states)
    func native(_ state: Int, _ time: Double) {
        guard Date() >= quietUntil else { return }
        if state == 1 && !playing { setState(true, time); send("play", time) }
        else if state == 2 && playing { setState(false, time); send("pause", time) }
        else if state == 1 && abs(time - expected()) > 3 { setState(true, time); send("seek", time) }
    }

    func check() {
        guard let c = ctl, ready, playing, Date() >= quietUntil else { return }
        c.position { now in
            if abs(now - self.expected()) > 2.5 {
                self.quietUntil = Date().addingTimeInterval(1.5)
                c.seek(self.expected())
            }
        }
    }
}

enum WatchWindow {
    @MainActor static var open: [Int: NSWindow] = [:]

    @MainActor static func open(_ id: Int, chatId: Int, card: JSON) {
        if let w = open[id] { w.makeKeyAndOrderFront(nil); return }
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 620), styleMask: [.titled, .closable, .resizable, .miniaturizable],
                         backing: .buffered, defer: false)
        w.title = (card["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Смотрим вместе"
        w.isReleasedWhenClosed = false
        w.contentView = NSHostingView(rootView: WatchRoom(watchId: id, card: card).environmentObject(Store.shared).preferredColorScheme(.dark))
        w.center()
        w.makeKeyAndOrderFront(nil)
        open[id] = w
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { _ in
            MainActor.assumeIsolated { open[id] = nil }
        }
    }
}

struct WatchRoom: View {
    @EnvironmentObject var store: Store
    let watchId: Int
    let card: JSON
    @StateObject private var sync: Sync
    @State private var now = 0.0
    @State private var duration = 0.0
    @State private var error = ""
    @State private var ctlView: AnyView?

    init(watchId: Int, card: JSON) {
        self.watchId = watchId
        self.card = card
        _sync = StateObject(wrappedValue: Sync { action, pos in
            Store.shared.sendRaw(["type": "watch", "watch_id": watchId, "action": action, "pos": pos])
        })
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                ctlView
                if !error.isEmpty { Text(error).font(jt(14)).foregroundStyle(.white).padding().background(.black.opacity(0.7)) }
            }
            HStack(spacing: 14) {
                Button { sync.seekBy(-10) } label: { Image(systemName: "gobackward.10") }.buttonStyle(.plain)
                Button { sync.togglePlay() } label: {
                    Image(systemName: sync.playing ? "pause.fill" : "play.fill").font(.system(size: 18)).foregroundStyle(.white)
                        .frame(width: 40, height: 40).background(C.acc, in: Circle())
                }
                .buttonStyle(.plain).keyboardShortcut(.space, modifiers: [])
                Button { sync.seekBy(10) } label: { Image(systemName: "goforward.10") }.buttonStyle(.plain)
                Text(timeLabel(now) + (duration > 0 ? " / " + timeLabel(duration) : "")).font(jt(13)).foregroundStyle(C.mut).monospacedDigit()
                if duration > 0 {
                    Slider(value: Binding(get: { min(now, duration) }, set: { now = $0 }), in: 0...duration) { editing in
                        if !editing { sync.seekTo(now) }
                    }
                } else { Spacer() }
                let viewers = (store.watchCards[watchId]?["viewers"] as? [Int]) ?? []
                if !viewers.isEmpty {
                    Text("👀 \(viewers.count)").font(jt(13)).foregroundStyle(C.mut)
                        .help(viewers.compactMap { store.user($0)?.name }.joined(separator: ", "))
                }
            }
            .foregroundStyle(C.ink).padding(.horizontal, 16).frame(height: 60).background(C.bg)
        }
        .onAppear(perform: setup)
        .onDisappear {
            store.onWatch[watchId] = nil
            store.sendRaw(["type": "watch", "watch_id": watchId, "action": "leave", "pos": 0])
            sync.ctl?.close()
            sync.ctl = nil
        }
        .task {
            if let w = await store.watchState(watchId) {
                store.watchCards[watchId] = w
                sync.setState(w["playing"] as? Bool ?? false, (w["pos"] as? NSNumber)?.doubleValue ?? 0)
                sync.apply()
            }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                sync.ctl?.position { now = $0 }
                sync.check()
            }
        }
    }

    private func setup() {
        store.onWatch[watchId] = { ev in
            sync.remote(ev["playing"] as? Bool ?? false, (ev["pos"] as? NSNumber)?.doubleValue ?? 0, mine: ev["by"] as? Int == store.meId)
        }
        store.sendRaw(["type": "watch", "watch_id": watchId, "action": "join", "pos": 0])
        let source = card["source"] as? String ?? ""
        let src = card["src"] as? String ?? ""
        if source == "file" {
            let f = FileCtl(Api.mediaURL(src))
            sync.ctl = f
            let item = f.player.currentItem
            Task {
                if let d = try? await item?.asset.load(.duration), d.seconds.isFinite { duration = d.seconds }
                sync.ready = true
                sync.apply()
            }
            ctlView = AnyView(PlainPlayer(player: f.player))
        } else {
            let url: URL
            if source == "youtube" {
                url = Api.url("/player/yt", ["v": src])
            } else {
                let p = src.split(separator: "_").map(String.init)
                url = Api.url("/player/vk", ["oid": p[0], "id": p.count > 1 ? p[1] : "", "hash": p.count > 2 ? p[2] : ""])
            }
            let w = WebCtl(url)
            w.onReady = { d, _ in duration = d; sync.ready = true; sync.apply() }
            w.onState = { s, t in sync.native(s, t) }
            w.onError = { e in
                error = source != "youtube" ? "VK Видео не открылось: возможно, видео закрыто"
                    : ["101", "150", "152"].contains(e) ? "Автор разрешил смотреть это видео только на самом YouTube. Возьмите другое"
                    : "YouTube не грузится (\(e)). Возможно, нужен VPN"
            }
            sync.ctl = w
            ctlView = AnyView(WebBox(web: w.web))
        }
    }
}

func timeLabel(_ s: Double) -> String {
    let t = max(Int(s), 0)
    return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
}

struct WebBox: NSViewRepresentable {
    let web: WKWebView
    func makeNSView(context: Context) -> WKWebView { web }
    func updateNSView(_ v: WKWebView, context: Context) {}
}

/// AVPlayerView without its own controls: play / pause go through Sync.
struct PlainPlayer: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> AVPlayerView {
        let v = AVPlayerView()
        v.player = player
        v.controlsStyle = .none
        return v
    }
    func updateNSView(_ v: AVPlayerView, context: Context) {}
}
