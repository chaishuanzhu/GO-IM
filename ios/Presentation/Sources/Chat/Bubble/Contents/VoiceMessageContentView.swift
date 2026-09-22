import UIKit

@MainActor
final class VoiceMessageContentView: UIView, MessageContentView {
    static let reuseKey = "voice"

    private let mediaRow = UIStackView()
    private let mediaIcon = UIImageView()
    private let voiceWaveform = VoiceWaveformBarsView()
    private let bodyLabel = UILabel()

    private var playURL: URL?
    private var duration = 0
    private var isWaveAnimating = false
    private var actions = MessageContentActions()
    private var observing = false

    override init(frame: CGRect) {
        super.init(frame: frame)

        mediaIcon.contentMode = .scaleAspectFit
        mediaIcon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)
        mediaIcon.setContentHuggingPriority(.required, for: .horizontal)

        voiceWaveform.isHidden = true
        voiceWaveform.translatesAutoresizingMaskIntoConstraints = false
        voiceWaveform.setContentHuggingPriority(.required, for: .horizontal)

        bodyLabel.numberOfLines = 1
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true

        mediaRow.axis = .horizontal
        mediaRow.spacing = 10
        mediaRow.alignment = .center
        mediaRow.addArrangedSubview(mediaIcon)
        mediaRow.addArrangedSubview(bodyLabel)
        mediaRow.translatesAutoresizingMaskIntoConstraints = false
        addSubview(mediaRow)

        NSLayoutConstraint.activate([
            mediaRow.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            mediaRow.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            mediaRow.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            mediaRow.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
            mediaIcon.widthAnchor.constraint(equalToConstant: 28),
            mediaIcon.heightAnchor.constraint(equalToConstant: 28),
        ])

        isUserInteractionEnabled = true
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        startObserving()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForReuse() {
        playURL = nil
        duration = 0
        stopWaveAnimation()
        bodyLabel.text = nil
        actions = MessageContentActions()
    }

    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    ) {
        self.actions = actions
        guard case let .voice(content) = model else { return }
        duration = content.duration
        playURL = content.playURL
        mediaIcon.isHidden = false
        mediaIcon.image = UIImage(systemName: "waveform")
        mediaIcon.tintColor = chrome.iconTint
        voiceWaveform.barColor = chrome.iconTint
        bodyLabel.textColor = chrome.bodyColor
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        detachWaveform()
        voiceWaveform.isAnimating = false
        refreshPlaybackUI()
    }

    @objc private func tapped() {
        guard let playURL else { return }
        actions.onPlayVoice?(playURL, duration)
    }

    private func startObserving() {
        guard !observing else { return }
        observing = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidChange),
            name: VoicePlayer.didChangeNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerProgress),
            name: VoicePlayer.progressNotification,
            object: nil
        )
    }

    @objc private func playerDidChange() {
        refreshPlaybackUI()
    }

    @objc private func playerProgress() {
        guard let playURL, VoicePlayer.shared.isPlaying(playURL) else { return }
        let rem = VoicePlayer.shared.remainingSeconds
        bodyLabel.text = rem > 0 ? "语音 \(rem)\"" : idleLabelText()
    }

    private func refreshPlaybackUI() {
        guard let playURL else {
            stopWaveAnimation()
            bodyLabel.text = idleLabelText()
            return
        }
        if VoicePlayer.shared.isPlaying(playURL) {
            startWaveAnimation()
            let rem = VoicePlayer.shared.remainingSeconds
            bodyLabel.text = rem > 0 ? "语音 \(rem)\"" : idleLabelText()
        } else {
            stopWaveAnimation()
            bodyLabel.text = idleLabelText()
        }
    }

    private func idleLabelText() -> String {
        duration > 0 ? "语音 \(duration)\"" : "语音消息"
    }

    private func startWaveAnimation() {
        guard !isWaveAnimating else { return }
        isWaveAnimating = true
        mediaIcon.isHidden = true
        attachWaveform()
        voiceWaveform.isAnimating = true
    }

    private func stopWaveAnimation() {
        isWaveAnimating = false
        voiceWaveform.isAnimating = false
        detachWaveform()
        if playURL != nil {
            mediaIcon.isHidden = false
            mediaIcon.image = UIImage(systemName: "waveform")
        }
    }

    private func attachWaveform() {
        guard voiceWaveform.superview !== mediaRow else {
            voiceWaveform.isHidden = false
            return
        }
        let bodyIndex = mediaRow.arrangedSubviews.firstIndex(of: bodyLabel) ?? mediaRow.arrangedSubviews.count
        mediaRow.insertArrangedSubview(voiceWaveform, at: bodyIndex)
        voiceWaveform.isHidden = false
    }

    private func detachWaveform() {
        voiceWaveform.isAnimating = false
        voiceWaveform.isHidden = true
        guard voiceWaveform.superview != nil else { return }
        mediaRow.removeArrangedSubview(voiceWaveform)
        voiceWaveform.removeFromSuperview()
    }
}
