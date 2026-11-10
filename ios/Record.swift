import UIKit
@preconcurrency import AVFoundation
import AVKit
import SwiftUI

// Voice messages (AAC .m4a, up to 3 min, loudness bars as digits 0-9 like the phone)
// and video circles (square mp4, up to 30 s).

@MainActor
final class VoiceRecorder: ObservableObject {
    @Published var recording = false
    @Published var elapsed = 0.0
    private var rec: AVAudioRecorder?
    private var levels: [Float] = []
    private var timer: Timer?
    private(set) var file = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("voice.m4a")
    var onLimit: () -> Void = {}

    func start() {
        AVCaptureDevice.requestAccess(for: .audio) { ok in
            DispatchQueue.main.async {
                guard ok else { Store.shared.toast = "Нет доступа к микрофону"; return }
                self.begin()
            }
        }
    }

    private func begin() {
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try? AVAudioSession.sharedInstance().setActive(true)
        file = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("voice-\(Int(Date().timeIntervalSince1970)).m4a")
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1,
                                       AVEncoderBitRateKey: 48000]
        guard let r = try? AVAudioRecorder(url: file, settings: settings) else { return }
        r.isMeteringEnabled = true
        r.record()
        rec = r
        levels = []
        elapsed = 0
        recording = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let r = self.rec else { return }
                r.updateMeters()
                self.levels.append(r.averagePower(forChannel: 0))
                self.elapsed = r.currentTime
                if self.elapsed >= 180 { self.onLimit() }
            }
        }
    }

    /// Returns (file, ms, wave) or nil when cancelled / too short.
    func stop(send: Bool) -> (URL, Int, String)? {
        timer?.invalidate()
        let ms = Int((rec?.currentTime ?? 0) * 1000)
        rec?.stop()
        rec = nil
        recording = false
        guard send, ms >= 700 else { return nil }
        return (file, ms, wave())
    }

    /// 40 bars, loudness in dB mapped to 0-9.
    private func wave() -> String {
        guard !levels.isEmpty else { return "" }
        let n = 40
        return (0..<n).map { i in
            let a = i * levels.count / n, b = max(a + 1, (i + 1) * levels.count / n)
            let v = levels[a..<min(b, levels.count)].max() ?? -60
            return String(Int(max(0, min(9, (v + 50) / 50 * 9))))
        }.joined()
    }
}

/// The circle recorder window: camera preview in a circle, record up to 30 s, then send.
struct CircleRecorder: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let chatId: Int
    @StateObject private var cam = CircleCam()

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                CamPreview(session: cam.session).frame(width: 320, height: 320).clipShape(Circle())
                Circle().trim(from: 0, to: cam.elapsed / 30).stroke(C.acc, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                    .rotationEffect(.degrees(-90)).frame(width: 332, height: 332)
            }
            Text(cam.status).font(jt(13)).foregroundStyle(C.mut)
            HStack(spacing: 20) {
                Button("Отмена") {
                    cam.cancel()
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
                Button {
                    if cam.recording { cam.finish() } else { cam.record() }
                } label: {
                    Image(systemName: cam.recording ? "stop.fill" : "record.circle").font(.system(size: 22)).foregroundStyle(.white)
                        .frame(width: 56, height: 56).background(cam.recording ? C.red : C.acc, in: Circle())
                }
                .buttonStyle(.plain).disabled(cam.busy)
            }
        }
        .padding(28).background(C.bg)
        .onAppear {
            cam.onDone = { url, ms, thumb in
                Task { await store.sendCircle(chatId, url, ms: ms, thumb: thumb) }
                dismiss()
            }
            cam.start()
        }
        .onDisappear { cam.shutdown() }
    }
}

@MainActor
final class CircleCam: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    private let output = AVCaptureMovieFileOutput()
    @Published var recording = false
    @Published var busy = false
    @Published var elapsed = 0.0
    @Published var status = "Нажми, чтобы записать кружок (до 30 с)"
    private var timer: Timer?
    private var cancelled = false
    var onDone: (URL, Int, UIImage?) -> Void = { _, _, _ in }

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { v in
            AVCaptureDevice.requestAccess(for: .audio) { a in
                DispatchQueue.main.async {
                    guard v && a else { self.status = "Нужен доступ к камере и микрофону"; return }
                    self.configure()
                }
            }
        }
    }

    private func configure() {
        session.beginConfiguration()
        session.sessionPreset = .high
        if let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front), let i = try? AVCaptureDeviceInput(device: cam), session.canAddInput(i) { session.addInput(i) }
        if let mic = AVCaptureDevice.default(for: .audio), let i = try? AVCaptureDeviceInput(device: mic), session.canAddInput(i) { session.addInput(i) }
        if session.canAddOutput(output) { session.addOutput(output) }
        output.maxRecordedDuration = CMTime(seconds: 30, preferredTimescale: 600)
        session.commitConfiguration()
        let s = session
        DispatchQueue.global().async { s.startRunning() }
    }

    func record() {
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("circle-\(Int(Date().timeIntervalSince1970)).mov")
        cancelled = false
        output.startRecording(to: url, recordingDelegate: self)
        recording = true
        elapsed = 0
        status = "Запись…"
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.elapsed = min(30, (self?.elapsed ?? 0) + 0.1) }
        }
    }

    func finish() {
        timer?.invalidate()
        output.stopRecording()
    }

    func cancel() {
        cancelled = true
        if recording { finish() }
    }

    func shutdown() {
        timer?.invalidate()
        let s = session
        DispatchQueue.global().async { s.stopRunning() }
    }

    nonisolated func fileOutput(_ o: AVCaptureFileOutput, didFinishRecordingTo url: URL, from c: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            self.recording = false
            self.timer?.invalidate()
            guard !self.cancelled else { return }
            self.busy = true
            self.status = "Готовлю…"
            if let (mp4, ms, thumb) = await CircleCam.square(url) { self.onDone(mp4, ms, thumb) } else { self.status = "Не получилось записать"; self.busy = false }
        }
    }

    /// Centre square 480×480 mp4 + first frame.
    nonisolated static func square(_ url: URL) async -> (URL, Int, UIImage?)? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let size = try? await track.load(.naturalSize), let dur = try? await asset.load(.duration) else { return nil }
        guard let preferred = try? await track.load(.preferredTransform) else { return nil }
        let bounds = CGRect(origin: .zero, size: size).applying(preferred)
        let displaySize = CGSize(width: abs(bounds.width), height: abs(bounds.height))
        let side = min(displaySize.width, displaySize.height)
        let comp = AVMutableVideoComposition()
        comp.renderSize = CGSize(width: 480, height: 480)
        comp.frameDuration = CMTime(value: 1, timescale: 30)
        let ins = AVMutableVideoCompositionInstruction()
        ins.timeRange = CMTimeRange(start: .zero, duration: dur)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
        let k = 480 / side
        // mirror like a selfie camera, crop the centre
        var t = preferred.concatenating(CGAffineTransform(translationX: -bounds.minX - (displaySize.width - side) / 2, y: -bounds.minY - (displaySize.height - side) / 2)).concatenating(CGAffineTransform(scaleX: k, y: k))
        t = t.concatenating(CGAffineTransform(scaleX: -1, y: 1)).concatenating(CGAffineTransform(translationX: 480, y: 0))
        layer.setTransform(t, at: .zero)
        ins.layerInstructions = [layer]
        comp.instructions = [ins]
        let out = url.deletingPathExtension().appendingPathExtension("mp4")
        try? FileManager.default.removeItem(at: out)
        guard let ex = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetMediumQuality) else { return nil }
        ex.videoComposition = comp
        do { try await ex.export(to: out, as: .mp4) } catch { return nil }
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: out))
        let thumb = (try? await gen.image(at: .zero).image).map { UIImage(cgImage: $0) }
        return (out, Int(dur.seconds * 1000), thumb)
    }
}

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
}
struct CamPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        let layer = view.layer as! AVCaptureVideoPreviewLayer
        layer.session = session
        layer.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ view: CameraPreviewView, context: Context) {}
}

/// Loudness bars of a voice message; [played] 0...1 colors the played part.
struct WaveBars: View {
    let wave: String
    let played: Double
    let ink: Color

    var body: some View {
        let bars = wave.isEmpty ? Array(repeating: 3, count: 30) : wave.compactMap { $0.wholeNumberValue }
        HStack(alignment: .center, spacing: 2) {
            ForEach(Array(bars.enumerated()), id: \.offset) { i, b in
                Capsule().fill(Double(i) / Double(bars.count) < played ? ink : ink.opacity(0.35))
                    .frame(width: 2.5, height: 3 + CGFloat(b) * 2.3)
            }
        }
        .frame(height: 26)
    }
}
