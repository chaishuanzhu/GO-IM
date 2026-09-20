import AVFoundation
import Foundation

/// Simple AAC voice memo recorder for chat voice messages.
@MainActor
final class VoiceRecorder: NSObject {
    private var recorder: AVAudioRecorder?
    private var startedAt: Date?

    var isRecording: Bool { recorder?.isRecording == true }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        let rec = try AVAudioRecorder(url: url, settings: settings)
        rec.isMeteringEnabled = true
        guard rec.prepareToRecord(), rec.record() else {
            throw NSError(domain: "VoiceRecorder", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "无法开始录音",
            ])
        }
        recorder = rec
        startedAt = Date()
    }

    /// Returns (data, durationSeconds) or nil if too short / failed.
    func stop() -> (Data, Int)? {
        guard let recorder else { return nil }
        let url = recorder.url
        let elapsed = Int(ceil(Date().timeIntervalSince(startedAt ?? Date())))
        recorder.stop()
        self.recorder = nil
        startedAt = nil
        defer { try? FileManager.default.removeItem(at: url) }
        guard elapsed >= 1, let data = try? Data(contentsOf: url), !data.isEmpty else {
            return nil
        }
        return (data, max(elapsed, 1))
    }

    func cancel() {
        recorder?.stop()
        if let url = recorder?.url {
            try? FileManager.default.removeItem(at: url)
        }
        recorder = nil
        startedAt = nil
    }

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioSession.sharedInstance().requestRecordPermission { ok in
                cont.resume(returning: ok)
            }
        }
    }
}
