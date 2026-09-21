import AVFoundation
import Foundation
import UIKit

/// Simple AAC voice memo recorder for chat voice messages.
@MainActor
final class VoiceRecorder: NSObject {
    private var recorder: AVAudioRecorder?
    private var startedAt: Date?
    private var fileURL: URL?

    var isRecording: Bool { recorder?.isRecording == true }

    func start() throws {
        cancel()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVEncoderBitRateKey: 32_000,
        ]
        let rec = try AVAudioRecorder(url: url, settings: settings)
        rec.isMeteringEnabled = true
        guard rec.prepareToRecord(), rec.record() else {
            throw NSError(domain: "VoiceRecorder", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "无法开始录音",
            ])
        }
        recorder = rec
        fileURL = url
        startedAt = Date()
    }

    /// Returns (data, durationSeconds) or nil if too short / failed.
    /// Duration uses the recorder's media timeline, not wall-clock — wall-clock
    /// overstates length when start was delayed (permission / async hold).
    func stop() -> (Data, Int)? {
        guard let recorder else { return nil }
        let url = recorder.url
        let recorded = recorder.currentTime
        let wall = Date().timeIntervalSince(startedAt ?? Date())
        let seconds = recorded > 0.05 ? recorded : wall
        recorder.stop()
        self.recorder = nil
        startedAt = nil
        fileURL = nil
        defer { try? FileManager.default.removeItem(at: url) }

        guard seconds >= 0.5, let data = try? Data(contentsOf: url), data.count > 100 else {
            return nil
        }
        return (data, max(1, Int(seconds.rounded())))
    }

    func cancel() {
        let url = recorder?.url ?? fileURL
        recorder?.stop()
        recorder = nil
        startedAt = nil
        fileURL = nil
        if let url {
            try? FileManager.default.removeItem(at: url)
        }
    }

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioSession.sharedInstance().requestRecordPermission { ok in
                cont.resume(returning: ok)
            }
        }
    }

    static var hasPermission: Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }
}

/// In-app voice playback. Downloads `/file` to a temp `.m4a` then plays with AVPlayer.
@MainActor
final class VoicePlayer: NSObject {
    static let shared = VoicePlayer()

    private(set) var playingURL: URL?
    /// Remaining whole seconds for UI countdown (0 when idle).
    private(set) var remainingSeconds: Int = 0
    static let didChangeNotification = Notification.Name("GOIMVoicePlayerDidChange")
    static let progressNotification = Notification.Name("GOIMVoicePlayerProgress")

    private var player: AVPlayer?
    private var endObserver: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var loadTask: Task<Void, Never>?
    private var progressTimer: Timer?
    private var tempFileURL: URL?
    private var playingKey: String?
    private var estimatedDuration = 0
    private var generation = 0

    func toggle(url: URL, estimatedDuration: Int = 0) {
        if playingKey == url.absoluteString, isActivelyPlaying || loadTask != nil {
            stop()
            return
        }
        play(url: url, estimatedDuration: estimatedDuration)
    }

    func play(url: URL, estimatedDuration: Int = 0) {
        stopPlayback(notify: false)
        let gen = generation &+ 1
        generation = gen
        playingURL = url
        playingKey = url.absoluteString
        self.estimatedDuration = max(0, estimatedDuration)
        remainingSeconds = self.estimatedDuration
        notifyChange()
        notifyProgress()

        loadTask = Task { @MainActor in
            do {
                let local = try await self.resolveLocalFile(url)
                guard !Task.isCancelled, self.generation == gen else { return }
                try self.startPlayer(fileURL: local)
            } catch is CancellationError {
                return
            } catch {
                #if DEBUG
                print("[IM] voice play failed:", error)
                #endif
                guard self.generation == gen else { return }
                self.stop()
            }
        }
    }

    func stop() {
        stopPlayback(notify: true)
    }

    private var isActivelyPlaying: Bool {
        guard let player else { return false }
        switch player.timeControlStatus {
        case .playing, .waitingToPlayAtSpecifiedRate:
            return true
        default:
            return false
        }
    }

    private func resolveLocalFile(_ url: URL) async throws -> URL {
        if url.isFileURL { return url }

        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200..<300).contains(status), data.count > 100 else {
            throw NSError(domain: "VoicePlayer", code: status, userInfo: [
                NSLocalizedDescriptionKey: "下载语音失败",
            ])
        }
        removeTempFile()
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-play-\(UUID().uuidString).m4a")
        try data.write(to: temp, options: .atomic)
        tempFileURL = temp
        return temp
    }

    private func startPlayer(fileURL: URL) throws {
        let session = AVAudioSession.sharedInstance()
        // `.defaultToSpeaker` is only valid with `.playAndRecord`, not `.playback`.
        try session.setCategory(.playback, mode: .default)
        try session.setActive(true)

        let item = AVPlayerItem(url: fileURL)
        let p = AVPlayer(playerItem: item)
        player = p

        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self else { return }
                if item.status == .failed {
                    self.stop()
                } else if item.status == .readyToPlay {
                    self.tickProgress()
                }
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.stop() }
        }

        p.play()
        startProgressTimer()
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        notifyChange()
        tickProgress()
    }

    private func startProgressTimer() {
        progressTimer?.invalidate()
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tickProgress() }
        }
        RunLoop.main.add(timer, forMode: .common)
        progressTimer = timer
    }

    private func tickProgress() {
        guard let player, let item = player.currentItem else { return }
        let current = player.currentTime().seconds
        guard current.isFinite, current >= 0 else { return }

        let mediaDuration = item.duration.seconds
        let total: Double
        if mediaDuration.isFinite, mediaDuration > 0.05 {
            total = mediaDuration
        } else if estimatedDuration > 0 {
            total = Double(estimatedDuration)
        } else {
            return
        }

        let remaining = max(0, Int(ceil(total - current)))
        guard remaining != remainingSeconds else { return }
        remainingSeconds = remaining
        notifyProgress()
    }

    private func stopPlayback(notify: Bool) {
        loadTask?.cancel()
        loadTask = nil
        progressTimer?.invalidate()
        progressTimer = nil
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        statusObservation?.invalidate()
        statusObservation = nil
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        playingURL = nil
        playingKey = nil
        remainingSeconds = 0
        estimatedDuration = 0
        removeTempFile()
        if notify {
            notifyChange()
            notifyProgress()
        }
    }

    private func removeTempFile() {
        if let tempFileURL {
            try? FileManager.default.removeItem(at: tempFileURL)
        }
        tempFileURL = nil
    }

    private func notifyChange() {
        let obj: Any = playingKey ?? NSNull()
        NotificationCenter.default.post(name: Self.didChangeNotification, object: obj)
    }

    private func notifyProgress() {
        let obj: Any = playingKey ?? NSNull()
        NotificationCenter.default.post(name: Self.progressNotification, object: obj)
    }

    func isPlaying(_ url: URL?) -> Bool {
        guard let url, let playingKey, playingKey == url.absoluteString else { return false }
        return isActivelyPlaying || loadTask != nil
    }
}
