import UIKit
import AVFoundation

@MainActor
final class VoiceAccessoryPanel: UIView, ChatComposerAccessoryPanel {
    static var mode: ChatComposerAccessory { .voice }

    private var actions: ChatComposerPanelActions
    private let voiceRecorder = VoiceRecorder()
    private let recordHint = UILabel()
    private let recordTime = UILabel()
    private let holdButton = UIButton(type: .system)

    private var recordSeconds = 0
    private var recordTimer: Timer?
    private var voiceFingerDown = false
    private var voicePressGeneration = 0
    private var voiceDidStartThisPress = false

    init(actions: ChatComposerPanelActions) {
        self.actions = actions
        super.init(frame: .zero)
        setup()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForDisplay() {
        Task { _ = await VoiceRecorder.requestPermission() }
        resetIdleChrome()
    }

    func prepareForHide() {
        abortVoiceRecording(send: false)
    }

    private func setup() {
        recordHint.text = "按住说话"
        recordHint.font = .systemFont(ofSize: 17, weight: .semibold)
        recordHint.textAlignment = .center
        recordHint.textColor = .label
        recordHint.translatesAutoresizingMaskIntoConstraints = false

        recordTime.text = "0:00"
        recordTime.font = .monospacedDigitSystemFont(ofSize: 28, weight: .medium)
        recordTime.textAlignment = .center
        recordTime.textColor = .secondaryLabel
        recordTime.translatesAutoresizingMaskIntoConstraints = false

        holdButton.setImage(UIImage(systemName: "mic.circle.fill"), for: .normal)
        holdButton.tintColor = .systemBlue
        holdButton.configuration = {
            var c = UIButton.Configuration.plain()
            c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 64, weight: .regular)
            c.contentInsets = NSDirectionalEdgeInsets(top: 24, leading: 40, bottom: 24, trailing: 40)
            return c
        }()
        holdButton.addTarget(self, action: #selector(voiceHoldDown), for: .touchDown)
        holdButton.addTarget(self, action: #selector(voiceHoldUp), for: [.touchUpInside])
        holdButton.addTarget(self, action: #selector(voiceHoldCancel), for: [.touchUpOutside, .touchCancel, .touchDragExit])
        holdButton.translatesAutoresizingMaskIntoConstraints = false
        holdButton.accessibilityLabel = "按住录音"

        addSubview(recordTime)
        addSubview(holdButton)
        addSubview(recordHint)

        NSLayoutConstraint.activate([
            recordTime.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 28),
            recordTime.centerXAnchor.constraint(equalTo: centerXAnchor),

            holdButton.centerXAnchor.constraint(equalTo: centerXAnchor),

            recordHint.centerXAnchor.constraint(equalTo: centerXAnchor),
            recordHint.topAnchor.constraint(equalTo: holdButton.bottomAnchor, constant: 8),
        ])
        let holdCenter = holdButton.centerYAnchor.constraint(
            equalTo: safeAreaLayoutGuide.centerYAnchor,
            constant: 8
        )
        holdCenter.priority = UILayoutPriority(750)
        holdCenter.isActive = true
        let hintBottom = recordHint.bottomAnchor.constraint(
            lessThanOrEqualTo: safeAreaLayoutGuide.bottomAnchor,
            constant: -12
        )
        hintBottom.priority = UILayoutPriority(750)
        hintBottom.isActive = true
    }

    private func resetIdleChrome() {
        recordHint.text = "按住说话"
        recordHint.textColor = .label
        recordTime.text = "0:00"
    }

    @objc private func voiceHoldDown() {
        voiceFingerDown = true
        voiceDidStartThisPress = false
        let generation = voicePressGeneration
        recordHint.text = "准备中…"
        recordHint.textColor = .secondaryLabel

        Task { @MainActor in
            if !VoiceRecorder.hasPermission {
                let ok = await VoiceRecorder.requestPermission()
                guard ok else {
                    self.resetIdleChrome()
                    self.actions.voiceFailed?("请在设置中允许麦克风权限")
                    return
                }
            }
            guard self.voiceFingerDown, generation == self.voicePressGeneration else { return }

            do {
                try self.voiceRecorder.start()
                self.voiceDidStartThisPress = true
                self.recordSeconds = 0
                self.recordTime.text = "0:00"
                self.recordHint.text = "松开发送 · 滑出取消"
                self.recordHint.textColor = .systemRed
                self.recordTimer?.invalidate()
                let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
                    Task { @MainActor in
                        guard let self, self.voiceRecorder.isRecording else { return }
                        self.recordSeconds += 1
                        self.recordTime.text = String(
                            format: "%d:%02d",
                            self.recordSeconds / 60,
                            self.recordSeconds % 60
                        )
                    }
                }
                RunLoop.main.add(timer, forMode: .common)
                self.recordTimer = timer
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            } catch {
                self.resetIdleChrome()
                self.actions.voiceFailed?(error.localizedDescription)
            }
        }
    }

    @objc private func voiceHoldUp() {
        finishVoicePress(send: true)
    }

    @objc private func voiceHoldCancel() {
        finishVoicePress(send: false)
    }

    private func finishVoicePress(send: Bool) {
        guard voiceFingerDown || voiceRecorder.isRecording || voiceDidStartThisPress else { return }
        voiceFingerDown = false
        voicePressGeneration += 1
        abortVoiceRecording(send: send)
    }

    private func abortVoiceRecording(send: Bool) {
        recordTimer?.invalidate()
        recordTimer = nil
        resetIdleChrome()

        if !send {
            voiceRecorder.cancel()
            voiceDidStartThisPress = false
            return
        }

        guard voiceDidStartThisPress || voiceRecorder.isRecording else {
            voiceRecorder.cancel()
            return
        }

        guard let (data, duration) = voiceRecorder.stop() else {
            voiceDidStartThisPress = false
            actions.voiceFailed?("录音太短，请按住再试")
            return
        }
        voiceDidStartThisPress = false
        actions.voiceFinished?(data, duration)
        actions.voiceDidSend?()
    }
}
