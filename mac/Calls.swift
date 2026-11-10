import AppKit
@preconcurrency import AVFoundation
import SwiftUI
@preconcurrency import UserNotifications

// Voice calls like the phone's Calls.kt: signaling over the main socket ("call_*" events),
// audio over /call — 20 ms Opus packets at 16 kHz, each prefixed with a codec byte (1 = Opus, 2 = raw 8 kHz PCM).
// The server prefixes incoming frames with the sender's 4-byte id.

private let RATE = 16000.0
private let FRAME = 320
private let CODEC_OPUS: UInt8 = 1
private let CODEC_PCM8: UInt8 = 2

@MainActor
final class Calls: ObservableObject {
    static let shared = Calls()

    /// the call I'm in
    @Published var callId: Int?
    @Published var chatId: Int?
    @Published var muted = false
    @Published var since: Date?
    /// someone is calling me: (call id, chat id, name)
    @Published var ring: (Int, Int, String)?
    /// call id, or -chatId for a new call, that this Mac asked to join
    private var pending: Int?
    private var audio: CallAudio?
    private var ringSound: NSSound?

    var store: Store { Store.shared }

    func start(_ chatId: Int) {
        guard callId == nil else { return }
        askMic {
            self.pending = -chatId
            self.store.sendRaw(["type": "call_start", "chat_id": chatId])
        }
    }

    func join(_ id: Int, chatId: Int) {
        guard callId != id else { return }
        if callId != nil { leave() }
        askMic {
            self.pending = id
            self.stopRinging()
            self.store.sendRaw(["type": "call_join", "call_id": id])
        }
    }

    func decline() {
        guard let (id, _, _) = ring else { return }
        store.sendRaw(["type": "call_decline", "call_id": id])
        stopRinging()
    }

    func leave() {
        guard let id = callId else { return }
        store.sendRaw(["type": "call_leave", "call_id": id])
        finish()
    }

    func toggleMute() {
        guard let id = callId else { return }
        muted.toggle()
        audio?.muted = muted
        store.sendRaw(["type": "call_mute", "call_id": id, "muted": muted])
    }

    func handle(_ ev: JSON) {
        let id = ev["call_id"] as? Int ?? 0
        let chat = ev["chat_id"] as? Int ?? 0
        switch ev["type"] as? String {
        case "call_ring":
            guard callId == nil else { return }
            let chatMuted = store.chat(chat)?.muted ?? false
            ring = (id, chat, store.chat(chat)?.isGroup == true ? (store.chat(chat)?.title ?? "") : (ev["from_name"] as? String ?? "Кент"))
            if !chatMuted { startRinging(ev["from_name"] as? String ?? "Кент") }
        case "call_joined":
            guard pending == id || pending == -chat else { return }
            pending = nil
            callId = id
            chatId = chat
            since = Date()
            muted = false
            audio?.stop()
            let a = CallAudio(callId: id)
            audio = a
            a.start()
        case "call_taken":
            if ring?.0 == id && pending != id { stopRinging() }
        case "call_end":
            if ring?.0 == id { stopRinging() }
            if callId == id { finish() }
        default:
            break
        }
    }

    private func finish() {
        audio?.stop()
        audio = nil
        callId = nil
        chatId = nil
        since = nil
        muted = false
    }

    private func askMic(_ then: @escaping () -> Void) {
        AVCaptureDevice.requestAccess(for: .audio) { ok in
            DispatchQueue.main.async {
                if ok { then() } else { Store.shared.toast = "Нет доступа к микрофону: Настройки → Конфиденциальность → Микрофон" }
            }
        }
    }

    private func startRinging(_ name: String) {
        ringSound?.stop()
        let s = NSSound(named: "Submarine")
        s?.loops = true
        s?.play()
        ringSound = s
        if !NSApp.isActive {
            let n = UNMutableNotificationContent()
            n.title = "Звонок"
            n.body = "\(name) звонит"
            n.sound = nil
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "ring", content: n, trigger: nil))
        }
    }

    private func stopRinging() {
        ringSound?.stop()
        ringSound = nil
        ring = nil
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["ring"])
    }
}

/// Microphone -> Opus -> socket, socket -> Opus -> speakers. Runs off the main thread.
final class CallAudio: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let callId: Int
    private let engine = AVAudioEngine()
    private let pcm16 = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: RATE, channels: 1, interleaved: true)!
    private let float16 = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: RATE, channels: 1, interleaved: false)!
    private let opus: AVAudioFormat
    private var encoder: AVAudioConverter?
    private var micConverter: AVAudioConverter?
    private var decoders: [UInt32: AVAudioConverter] = [:]
    private var players: [UInt32: AVAudioPlayerNode] = [:]
    private var queued: [UInt32: Int] = [:]
    private var pendingPCM: [Int16] = []
    private var ws: URLSessionWebSocketTask?
    private var session: URLSession!
    private let q = DispatchQueue(label: "jt.call")
    private var running = false
    private var retries = 0
    var muted = false

    init(callId: Int) {
        self.callId = callId
        var d = AudioStreamBasicDescription(mSampleRate: RATE, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
                                            mFramesPerPacket: UInt32(FRAME), mBytesPerFrame: 0, mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
        opus = AVAudioFormat(streamDescription: &d)!
        super.init()
        let oq = OperationQueue()
        oq.underlyingQueue = q
        session = URLSession(configuration: .default, delegate: self, delegateQueue: oq)
        encoder = AVAudioConverter(from: pcm16, to: opus)
        encoder?.bitRate = 24000
    }

    func start() {
        q.async { [self] in
            running = true
            connect()
            startEngine()
        }
    }

    func stop() {
        q.async { [self] in
            running = false
            ws?.cancel(with: .goingAway, reason: nil)
            ws = nil
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
    }

    private func connect() {
        guard running else { return }
        var c = URLComponents(url: Api.url("/call", ["token": Api.token, "call_id": "\(callId)"]), resolvingAgainstBaseURL: false)!
        c.scheme = c.scheme == "https" ? "wss" : "ws"
        let t = session.webSocketTask(with: c.url!)
        ws = t
        t.resume()
        receive(t)
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] r in
            guard let self, t === self.ws else { return }
            switch r {
            case .success(.data(let d)):
                self.retries = 0
                self.play(d)
                self.receive(t)
            case .success:
                self.receive(t)
            case .failure:
                // lost network: the server keeps my place for 20 s
                self.ws = nil
                guard self.running, self.retries < 8 else { return }
                self.retries += 1
                self.q.asyncAfter(deadline: .now() + 2) { self.connect() }
            }
        }
    }

    private func startEngine() {
        let input = engine.inputNode
        try? input.setVoiceProcessingEnabled(true)
        let inFormat = input.outputFormat(forBus: 0)
        guard inFormat.sampleRate > 0 else { return }
        micConverter = AVAudioConverter(from: inFormat, to: pcm16)
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buf, _ in
            self?.q.async { self?.mic(buf) }
        }
        engine.prepare()
        try? engine.start()
    }

    // ---------- sending ----------

    private func mic(_ buf: AVAudioPCMBuffer) {
        guard let conv = micConverter, running else { return }
        let cap = AVAudioFrameCount(Double(buf.frameLength) * RATE / buf.format.sampleRate) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm16, frameCapacity: cap) else { return }
        var fed = false
        conv.convert(to: out, error: nil) { _, s in
            if fed { s.pointee = .noDataNow; return nil }
            fed = true
            s.pointee = .haveData
            return buf
        }
        pendingPCM.append(contentsOf: UnsafeBufferPointer(start: out.int16ChannelData![0], count: Int(out.frameLength)))
        while pendingPCM.count >= FRAME {
            let frame = Array(pendingPCM.prefix(FRAME))
            pendingPCM.removeFirst(FRAME)
            if !muted { send(frame) }
        }
    }

    private func send(_ frame: [Int16]) {
        guard let ws else { return }
        if let enc = encoder, let packet = encode(enc, frame) {
            ws.send(.data(Data([CODEC_OPUS]) + packet)) { _ in }
        } else {
            // no Opus: every second sample, 8 kHz raw
            var d = Data([CODEC_PCM8])
            for i in stride(from: 0, to: frame.count, by: 2) { withUnsafeBytes(of: frame[i].littleEndian) { d.append(contentsOf: $0) } }
            ws.send(.data(d)) { _ in }
        }
    }

    private func encode(_ enc: AVAudioConverter, _ frame: [Int16]) -> Data? {
        guard let inb = AVAudioPCMBuffer(pcmFormat: pcm16, frameCapacity: AVAudioFrameCount(FRAME)) else { return nil }
        inb.frameLength = AVAudioFrameCount(FRAME)
        frame.withUnsafeBufferPointer { inb.int16ChannelData![0].update(from: $0.baseAddress!, count: FRAME) }
        let out = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: 1500)
        var fed = false
        enc.convert(to: out, error: nil) { _, s in
            if fed { s.pointee = .noDataNow; return nil }
            fed = true
            s.pointee = .haveData
            return inb
        }
        guard out.packetCount > 0, out.byteLength > 0 else { return nil }
        return Data(bytes: out.data, count: Int(out.byteLength))
    }

    // ---------- receiving ----------

    private func play(_ d: Data) {
        guard d.count > 5 else { return }
        let sender = d.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        let codec = d[d.startIndex + 4]
        let payload = d.dropFirst(5)
        var samples: [Int16] = []
        if codec == CODEC_OPUS {
            samples = decode(sender, Data(payload))
        } else if codec == CODEC_PCM8 {
            // 8 kHz -> 16 kHz by repeating each sample
            payload.withUnsafeBytes { raw in
                for s in raw.bindMemory(to: Int16.self) { let v = Int16(littleEndian: s); samples.append(v); samples.append(v) }
            }
        }
        guard !samples.isEmpty, let buf = AVAudioPCMBuffer(pcmFormat: float16, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        for (i, s) in samples.enumerated() { buf.floatChannelData![0][i] = Float(s) / 32768 }
        let node = player(sender)
        // too far behind (network burst): drop instead of growing the delay
        if (queued[sender] ?? 0) > 12 { return }
        queued[sender, default: 0] += 1
        node.scheduleBuffer(buf) { [weak self] in self?.q.async { self?.queued[sender, default: 1] -= 1 } }
        if !node.isPlaying { node.play() }
    }

    private func decode(_ sender: UInt32, _ packet: Data) -> [Int16] {
        let dec = decoders[sender] ?? AVAudioConverter(from: opus, to: pcm16)
        guard let dec else { return [] }
        decoders[sender] = dec
        let pkt = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: max(packet.count, 1))
        packet.withUnsafeBytes { pkt.data.copyMemory(from: $0.baseAddress!, byteCount: packet.count) }
        pkt.byteLength = UInt32(packet.count)
        pkt.packetCount = 1
        pkt.packetDescriptions![0] = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm16, frameCapacity: 2880) else { return [] }
        var fed = false
        dec.convert(to: out, error: nil) { _, s in
            if fed { s.pointee = .noDataNow; return nil }
            fed = true
            s.pointee = .haveData
            return pkt
        }
        return Array(UnsafeBufferPointer(start: out.int16ChannelData![0], count: Int(out.frameLength)))
    }

    private func player(_ sender: UInt32) -> AVAudioPlayerNode {
        if let p = players[sender] { return p }
        let p = AVAudioPlayerNode()
        engine.attach(p)
        engine.connect(p, to: engine.mainMixerNode, format: float16)
        if !engine.isRunning { try? engine.start() }
        players[sender] = p
        return p
    }
}

// ---------- call UI ----------

/// Top bar while in a call, and the incoming call panel.
struct CallOverlay: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var calls = Calls.shared

    var body: some View {
        VStack(spacing: 8) {
            if let (id, chat, name) = calls.ring {
                HStack(spacing: 12) {
                    Avatar(name: name, photo: avatar(chat), size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).font(jt(15, .semibold)).foregroundStyle(C.ink)
                        Text("Звонит…").font(jt(12)).foregroundStyle(C.mut)
                    }
                    Spacer()
                    round("phone.down.fill", C.red) { calls.decline() }
                    round("phone.fill", C.green) { calls.join(id, chatId: chat) }
                }
                .padding(12).frame(width: 360).background(C.s2, in: RoundedRectangle(cornerRadius: 22))
                .shadow(radius: 20)
            }
            if let chat = calls.chatId {
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").foregroundStyle(C.green)
                    Text(store.chat(chat)?.title ?? "Звонок").font(jt(14, .semibold)).foregroundStyle(C.ink)
                    if let s = calls.since {
                        TimelineView(.periodic(from: s, by: 1)) { t in
                            Text(durationLabel(Int(t.date.timeIntervalSince(s) * 1000))).font(jt(13)).foregroundStyle(C.mut).monospacedDigit()
                        }
                    }
                    Spacer()
                    round(calls.muted ? "mic.slash.fill" : "mic.fill", calls.muted ? C.red : C.s1) { calls.toggleMute() }
                    round("phone.down.fill", C.red) { calls.leave() }
                }
                .padding(.horizontal, 14).padding(.vertical, 8).frame(width: 380).background(C.s2, in: Capsule())
                .shadow(radius: 12)
            }
        }
        .padding(.top, 10)
    }

    private func avatar(_ chat: Int) -> String {
        guard let c = store.chat(chat) else { return "" }
        return c.isGroup ? c.avatar : (c.other(store.meId)?.avatar ?? "")
    }

    private func round(_ icon: String, _ bg: Color, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 36, height: 36).background(bg, in: Circle())
        }
        .buttonStyle(.plain)
    }
}
