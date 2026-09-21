import UIKit
import AVFoundation
import Photos
import Domain

/// Accessory mode for the iOS-26 style chat dock.
enum ChatComposerAccessory: Equatable {
    case none
    case emoji
    case voice
    case image
    case more
}

@MainActor
protocol ChatComposerBarDelegate: AnyObject {
    func composerBar(_ bar: ChatComposerBar, didSendText text: String)
    func composerBarDidTapMention(_ bar: ChatComposerBar)
    func composerBar(_ bar: ChatComposerBar, didFinishVoice data: Data, duration: Int)
    func composerBarDidRequestCamera(_ bar: ChatComposerBar)
    func composerBarDidRequestAlbum(_ bar: ChatComposerBar)
    func composerBarDidRequestVideo(_ bar: ChatComposerBar)
    func composerBarDidRequestFile(_ bar: ChatComposerBar)
    func composerBar(
        _ bar: ChatComposerBar,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    )
    func composerBar(_ bar: ChatComposerBar, voiceFailed message: String)
    func composerBar(_ bar: ChatComposerBar, accessoryChanged mode: ChatComposerAccessory)
    func composerBar(_ bar: ChatComposerBar, didSelectSticker sticker: StickerRef)
}

/// iOS 26–style floating dock: rounded top, input row + tool row, expandable accessory.
@MainActor
final class ChatComposerBar: UIView, UITextViewDelegate {
    weak var delegate: ChatComposerBarDelegate?

    private let glass = UIVisualEffectView(effect: UIBlurEffect(style: .systemChromeMaterial))
    private let chrome = UIView()
    private let textView = UITextView()
    private let placeholder = UILabel()
    private let toolRow = UIStackView()
    private let topStack = UIStackView()
    private let accessoryHost = UIView()

    private var emojiButton: UIButton!
    private var mentionButton: UIButton!
    private var voiceButton: UIButton!
    private var photoButton: UIButton!
    private var moreButton: UIButton!

    private var textHeightConstraint: NSLayoutConstraint!
    private var accessoryHeightConstraint: NSLayoutConstraint!
    private var topStackBottomToSafe: NSLayoutConstraint!
    private var accessoryTopToTools: NSLayoutConstraint!
    /// Vertical fill inside accessoryHost — deactivated while height == 0 to avoid conflicts.
    private var accessoryContentConstraints: [NSLayoutConstraint] = []
    private let minTextH: CGFloat = 36
    private let maxTextH: CGFloat = 110

    private(set) var accessory: ChatComposerAccessory = .none
    private var keyboardHeight: CGFloat = 336

    private let voiceRecorder = VoiceRecorder()
    private var recordSeconds = 0
    private var recordTimer: Timer?
    /// True while the finger is down on the record control.
    private var voiceFingerDown = false
    /// Bumps on every touch-up so an in-flight async start is abandoned.
    private var voicePressGeneration = 0
    private var voiceDidStartThisPress = false
    private var holdButton: UIButton!

    // Accessory subviews
    private let emojiModeControl = UISegmentedControl(items: ["表情", "贴纸"])
    private let emojiCollection: UICollectionView
    private var stickerPanel: StickerAccessoryPanel?
    private let emojiSendButton = UIButton(type: .system)
    private let voicePanel = UIView()
    private let imagePanel = ChatImageAccessoryPanel()
    private let morePanel = UIView()
    private let recordHint = UILabel()
    private let recordTime = UILabel()

    private static let emojis: [String] = [
        "😀", "😂", "🥰", "😍", "🤔", "😎", "😭", "😡",
        "👍", "👎", "👏", "🙏", "🔥", "✨", "🎉", "❤️",
        "🥰", "😊", "🤗", "😴", "🤝", "💪", "🌟", "💯",
        "😅", "🤣", "😘", "😜", "🥺", "😱", "🙄", "😇",
    ]

    override init(frame: CGRect) {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 6
        layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        emojiCollection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: frame)
        setup()
        observeKeyboard()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func insertMention(_ uid: String) {
        let token = "@\(uid) "
        textView.text = (textView.text ?? "") + token
        placeholder.isHidden = true
        updateTextHeight()
        textView.becomeFirstResponder()
    }

    /// Wire sticker repository into the emoji accessory (贴纸 tab).
    func configureStickers(_ repository: StickerRepository) {
        if stickerPanel == nil {
            let panel = StickerAccessoryPanel(stickers: repository)
            panel.translatesAutoresizingMaskIntoConstraints = false
            panel.isHidden = true
            panel.onSelect = { [weak self] ref in
                guard let self else { return }
                self.delegate?.composerBar(self, didSelectSticker: ref)
            }
            accessoryHost.addSubview(panel)
            let stickerTop = panel.topAnchor.constraint(equalTo: emojiModeControl.bottomAnchor, constant: 4)
            let stickerBottom = panel.bottomAnchor.constraint(equalTo: accessoryHost.bottomAnchor)
            let stickerConstraints = [
                stickerTop,
                panel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
                panel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
                stickerBottom,
            ]
            // Keep inactive while the host is collapsed (height == 0).
            stickerConstraints.forEach { $0.isActive = accessory != .none }
            accessoryContentConstraints.append(contentsOf: [stickerTop, stickerBottom])
            stickerPanel = panel
            accessoryHost.bringSubviewToFront(emojiSendButton)
            updateEmojiCollectionInsets()
        }
        stickerPanel?.reload()
    }

    func dismissAccessory() {
        setAccessory(.none, animated: true)
    }

    func appendPickedImage(_ image: UIImage) {
        imagePanel.appendExtraImage(image)
        if accessory != .image {
            setAccessory(.image, animated: true)
        }
    }

    // MARK: - Setup

    private func setup() {
        backgroundColor = .clear

        chrome.translatesAutoresizingMaskIntoConstraints = false
        chrome.backgroundColor = .clear
        chrome.layer.cornerRadius = 28
        chrome.layer.cornerCurve = .continuous
        chrome.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        chrome.clipsToBounds = true
        addSubview(chrome)

        glass.translatesAutoresizingMaskIntoConstraints = false
        chrome.addSubview(glass)

        textView.backgroundColor = UIColor.tertiarySystemFill
        textView.layer.cornerRadius = 18
        textView.layer.cornerCurve = .continuous
        textView.font = .preferredFont(forTextStyle: .body)
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 10, bottom: 8, right: 10)
        textView.textContainer.lineFragmentPadding = 0
        textView.isScrollEnabled = false
        textView.delegate = self
        textView.returnKeyType = .send
        textView.translatesAutoresizingMaskIntoConstraints = false

        placeholder.text = "发消息…"
        placeholder.font = .preferredFont(forTextStyle: .body)
        placeholder.textColor = .placeholderText
        placeholder.translatesAutoresizingMaskIntoConstraints = false
        textView.addSubview(placeholder)

        let inputRow = UIStackView(arrangedSubviews: [textView])
        inputRow.axis = .horizontal
        inputRow.alignment = .bottom
        inputRow.spacing = 8
        inputRow.isLayoutMarginsRelativeArrangement = true
        inputRow.layoutMargins = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        inputRow.translatesAutoresizingMaskIntoConstraints = false

        emojiButton = makeTool("face.smiling", action: #selector(emojiTapped))
        mentionButton = makeTool("at", action: #selector(mentionTapped))
        voiceButton = makeTool("mic.fill", action: #selector(voiceTapped))
        photoButton = makeTool("photo.on.rectangle", action: #selector(photoTapped))
        moreButton = makeTool("plus", action: #selector(moreTapped))

        toolRow.axis = .horizontal
        toolRow.distribution = .fillEqually
        toolRow.alignment = .center
        toolRow.spacing = 0
        [emojiButton, mentionButton, voiceButton, photoButton, moreButton].forEach { toolRow.addArrangedSubview($0!) }
        toolRow.translatesAutoresizingMaskIntoConstraints = false

        accessoryHost.translatesAutoresizingMaskIntoConstraints = false
        accessoryHost.clipsToBounds = true
        // Opaque panel color — extends into home-indicator strip when expanded.
        accessoryHost.backgroundColor = .secondarySystemBackground
        accessoryHost.isHidden = true

        topStack.axis = .vertical
        topStack.spacing = 6
        topStack.addArrangedSubview(inputRow)
        topStack.addArrangedSubview(toolRow)
        topStack.translatesAutoresizingMaskIntoConstraints = false
        chrome.addSubview(topStack)
        chrome.addSubview(accessoryHost)

        textHeightConstraint = textView.heightAnchor.constraint(equalToConstant: minTextH)
        accessoryHeightConstraint = accessoryHost.heightAnchor.constraint(equalToConstant: 0)
        // Pin to physical bottom; constant is set from the window home-indicator inset
        // (avoids tab-bar safe-area inflation on device after hidesBottomBarWhenPushed).
        topStackBottomToSafe = topStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8)
        accessoryTopToTools = accessoryHost.topAnchor.constraint(equalTo: topStack.bottomAnchor, constant: 6)
        accessoryTopToTools.isActive = false

        NSLayoutConstraint.activate([
            chrome.topAnchor.constraint(equalTo: topAnchor),
            chrome.leadingAnchor.constraint(equalTo: leadingAnchor),
            chrome.trailingAnchor.constraint(equalTo: trailingAnchor),
            chrome.bottomAnchor.constraint(equalTo: bottomAnchor),

            glass.topAnchor.constraint(equalTo: chrome.topAnchor),
            glass.leadingAnchor.constraint(equalTo: chrome.leadingAnchor),
            glass.trailingAnchor.constraint(equalTo: chrome.trailingAnchor),
            glass.bottomAnchor.constraint(equalTo: chrome.bottomAnchor),

            topStack.topAnchor.constraint(equalTo: chrome.topAnchor, constant: 8),
            topStack.leadingAnchor.constraint(equalTo: chrome.leadingAnchor),
            topStack.trailingAnchor.constraint(equalTo: chrome.trailingAnchor),
            topStackBottomToSafe,

            accessoryHost.leadingAnchor.constraint(equalTo: chrome.leadingAnchor),
            accessoryHost.trailingAnchor.constraint(equalTo: chrome.trailingAnchor),
            // Bleed into home-indicator area so safe-area strip matches panel color.
            accessoryHost.bottomAnchor.constraint(equalTo: chrome.bottomAnchor),
            accessoryHeightConstraint,

            textHeightConstraint,
            toolRow.heightAnchor.constraint(equalToConstant: 40),

            placeholder.leadingAnchor.constraint(equalTo: textView.leadingAnchor, constant: 14),
            placeholder.topAnchor.constraint(equalTo: textView.topAnchor, constant: 8),
        ])

        setupAccessoryPanels()
        updateCollapsedBottomInset()
    }

    private func makeTool(_ systemName: String, action: Selector) -> UIButton {
        var cfg = UIButton.Configuration.plain()
        cfg.image = UIImage(systemName: systemName)
        cfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)
        let b = UIButton(configuration: cfg)
        b.tintColor = .secondaryLabel
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    private func setupAccessoryPanels() {
        emojiModeControl.selectedSegmentIndex = 0
        emojiModeControl.translatesAutoresizingMaskIntoConstraints = false
        emojiModeControl.addTarget(self, action: #selector(emojiModeChanged), for: .valueChanged)
        accessoryHost.addSubview(emojiModeControl)

        emojiCollection.backgroundColor = .clear
        emojiCollection.dataSource = self
        emojiCollection.delegate = self
        emojiCollection.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.reuseID)
        emojiCollection.translatesAutoresizingMaskIntoConstraints = false
        // We manage home-indicator padding ourselves — avoid double safe-area inset.
        emojiCollection.contentInsetAdjustmentBehavior = .never
        accessoryHost.addSubview(emojiCollection)

        var sendCfg = UIButton.Configuration.filled()
        sendCfg.cornerStyle = .capsule
        sendCfg.baseBackgroundColor = .systemBlue
        sendCfg.baseForegroundColor = .white
        sendCfg.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        sendCfg.title = "发送"
        sendCfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = .systemFont(ofSize: 15, weight: .semibold)
            return out
        }
        emojiSendButton.configuration = sendCfg
        emojiSendButton.translatesAutoresizingMaskIntoConstraints = false
        emojiSendButton.isHidden = true
        emojiSendButton.isEnabled = false
        emojiSendButton.addTarget(self, action: #selector(emojiSendTapped), for: .touchUpInside)
        accessoryHost.addSubview(emojiSendButton)

        // Voice
        voicePanel.translatesAutoresizingMaskIntoConstraints = false
        voicePanel.isHidden = true
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
        let hold = UIButton(type: .system)
        hold.setImage(UIImage(systemName: "mic.circle.fill"), for: .normal)
        hold.tintColor = .systemBlue
        hold.configuration = {
            var c = UIButton.Configuration.plain()
            c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 64, weight: .regular)
            c.contentInsets = NSDirectionalEdgeInsets(top: 24, leading: 40, bottom: 24, trailing: 40)
            return c
        }()
        hold.addTarget(self, action: #selector(voiceHoldDown), for: .touchDown)
        hold.addTarget(self, action: #selector(voiceHoldUp), for: [.touchUpInside])
        hold.addTarget(self, action: #selector(voiceHoldCancel), for: [.touchUpOutside, .touchCancel, .touchDragExit])
        hold.translatesAutoresizingMaskIntoConstraints = false
        hold.accessibilityLabel = "按住录音"
        holdButton = hold
        voicePanel.addSubview(recordTime)
        voicePanel.addSubview(hold)
        voicePanel.addSubview(recordHint)
        accessoryHost.addSubview(voicePanel)

        // Image
        imagePanel.translatesAutoresizingMaskIntoConstraints = false
        imagePanel.isHidden = true
        imagePanel.delegate = self
        accessoryHost.addSubview(imagePanel)

        // More
        morePanel.translatesAutoresizingMaskIntoConstraints = false
        morePanel.isHidden = true
        let videoBtn = makePanelAction(title: "视频", symbol: "video.fill", action: #selector(requestVideo))
        let fileBtn = makePanelAction(title: "文件", symbol: "doc.fill", action: #selector(requestFile))
        let moreStack = UIStackView(arrangedSubviews: [videoBtn, fileBtn])
        moreStack.axis = .horizontal
        moreStack.spacing = 28
        moreStack.distribution = .fillEqually
        moreStack.translatesAutoresizingMaskIntoConstraints = false
        morePanel.addSubview(moreStack)
        accessoryHost.addSubview(morePanel)

        let emojiTop = emojiModeControl.topAnchor.constraint(equalTo: accessoryHost.topAnchor, constant: 8)
        let emojiCollectionTop = emojiCollection.topAnchor.constraint(
            equalTo: emojiModeControl.bottomAnchor,
            constant: 4
        )
        let emojiCollectionBottom = emojiCollection.bottomAnchor.constraint(
            equalTo: accessoryHost.bottomAnchor
        )

        let voiceTop = voicePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor)
        let voiceBottom = voicePanel.bottomAnchor.constraint(equalTo: accessoryHost.bottomAnchor)
        let recordTop = recordTime.topAnchor.constraint(
            equalTo: voicePanel.safeAreaLayoutGuide.topAnchor,
            constant: 28
        )
        let holdCenterY = hold.centerYAnchor.constraint(
            equalTo: voicePanel.safeAreaLayoutGuide.centerYAnchor,
            constant: 8
        )
        let hintBottom = recordHint.bottomAnchor.constraint(
            lessThanOrEqualTo: voicePanel.safeAreaLayoutGuide.bottomAnchor,
            constant: -12
        )

        let imageTop = imagePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor)
        let imageBottom = imagePanel.bottomAnchor.constraint(equalTo: accessoryHost.bottomAnchor)

        let moreTop = morePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor)
        let moreBottom = morePanel.bottomAnchor.constraint(equalTo: accessoryHost.bottomAnchor)
        let moreStackCenterY = moreStack.centerYAnchor.constraint(
            equalTo: morePanel.safeAreaLayoutGuide.centerYAnchor
        )

        // Collapsed host height is 0 — keep vertical fill off until the panel expands.
        accessoryContentConstraints = [
            emojiTop, emojiCollectionTop, emojiCollectionBottom,
            voiceTop, voiceBottom, recordTop, holdCenterY, hintBottom,
            imageTop, imageBottom,
            moreTop, moreBottom, moreStackCenterY,
        ]
        accessoryContentConstraints.forEach { $0.isActive = false }

        NSLayoutConstraint.activate([
            emojiModeControl.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor, constant: 16),
            emojiModeControl.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor, constant: -16),

            emojiCollection.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            emojiCollection.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),

            emojiSendButton.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor, constant: -16),
            emojiSendButton.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor, constant: -10),
            emojiSendButton.heightAnchor.constraint(equalToConstant: 36),

            voicePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            voicePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            recordTime.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            hold.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            recordHint.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            recordHint.topAnchor.constraint(equalTo: hold.bottomAnchor, constant: 8),

            imagePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            imagePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),

            morePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            morePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            moreStack.centerXAnchor.constraint(equalTo: morePanel.centerXAnchor),
            moreStack.leadingAnchor.constraint(greaterThanOrEqualTo: morePanel.leadingAnchor, constant: 24),
            moreStack.trailingAnchor.constraint(lessThanOrEqualTo: morePanel.trailingAnchor, constant: -24),
        ])
    }

    private func makePanelAction(title: String, symbol: String, action: Selector) -> UIButton {
        var cfg = UIButton.Configuration.gray()
        cfg.cornerStyle = .large
        cfg.image = UIImage(systemName: symbol)
        cfg.title = title
        cfg.imagePlacement = .top
        cfg.imagePadding = 10
        cfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 28, weight: .medium)
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 18, leading: 22, bottom: 18, trailing: 22)
        let b = UIButton(configuration: cfg)
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    // MARK: - Keyboard height

    private func observeKeyboard() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardWillChange(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
    }

    @objc private func keyboardWillChange(_ note: Notification) {
        guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let h = max(0, UIScreen.main.bounds.height - frame.origin.y)
        if h > 100 {
            keyboardHeight = h
            if accessory != .none {
                accessoryHeightConstraint.constant = expandedAccessoryHeight()
            }
        }
    }

    /// Panel content height + home-indicator strip (same background color).
    private func expandedAccessoryHeight() -> CGFloat {
        let inset = resolvedBottomSafeInset()
        return keyboardHeight + inset
    }

    /// Home-indicator only — prefer the window inset so a hidden tab bar cannot inflate padding.
    private func resolvedBottomSafeInset() -> CGFloat {
        if let windowInset = window?.safeAreaInsets.bottom, windowInset > 0 {
            return windowInset
        }
        if let sceneInset = windowSceneHomeIndicatorInset(), sceneInset > 0 {
            return sceneInset
        }
        if safeAreaInsets.bottom > 0 { return safeAreaInsets.bottom }
        return superview?.safeAreaInsets.bottom ?? 0
    }

    private func windowSceneHomeIndicatorInset() -> CGFloat? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            if let key = scene.windows.first(where: \.isKeyWindow) {
                return key.safeAreaInsets.bottom
            }
            if let any = scene.windows.first {
                return any.safeAreaInsets.bottom
            }
        }
        return nil
    }

    /// Collapsed dock: sit just above the home indicator with a tight 4pt pad (not a tall empty band).
    private func updateCollapsedBottomInset() {
        guard accessory == .none else { return }
        let inset = resolvedBottomSafeInset()
        // Keep a small pad above the home indicator; pull slightly into the strip so it
        // doesn't look like a second empty safe-area on device.
        let pad: CGFloat = inset > 0 ? max(inset - 6, 8) : 8
        topStackBottomToSafe.constant = -pad
    }

    /// Scroll content under the send button / home indicator without leaving a blank strip.
    private func updateEmojiCollectionInsets() {
        let bottomSafe = resolvedBottomSafeInset()
        // Send button sits just above the home indicator (36pt + 10pt margin).
        let sendClearance: CGFloat = 46
        let inset = bottomSafe + sendClearance
        emojiCollection.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: inset, right: 0)
        emojiCollection.scrollIndicatorInsets = emojiCollection.contentInset
        stickerPanel?.setBottomContentInset(bottomSafe + 8)
    }

    public override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        updateCollapsedBottomInset()
        updateEmojiCollectionInsets()
        if accessory != .none {
            accessoryHeightConstraint.constant = expandedAccessoryHeight()
        }
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        updateCollapsedBottomInset()
        updateEmojiCollectionInsets()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        updateCollapsedBottomInset()
        updateEmojiCollectionInsets()
    }

    // MARK: - Accessory switching

    private func setAccessory(_ mode: ChatComposerAccessory, animated: Bool) {
        let next = (accessory == mode) ? ChatComposerAccessory.none : mode
        accessory = next

        if next != .none {
            textView.resignFirstResponder()
        }

        emojiModeControl.isHidden = next != .emoji
        emojiCollection.isHidden = next != .emoji || emojiModeControl.selectedSegmentIndex != 0
        stickerPanel?.isHidden = next != .emoji || emojiModeControl.selectedSegmentIndex != 1
        updateEmojiSendButtonVisibility()
        voicePanel.isHidden = next != .voice
        imagePanel.isHidden = next != .image
        morePanel.isHidden = next != .more
        if next == .voice {
            // Warm mic permission so the first press can start recording promptly.
            Task { _ = await VoiceRecorder.requestPermission() }
            recordHint.text = "按住说话"
            recordHint.textColor = .label
            recordTime.text = "0:00"
        }
        if next != .voice {
            abortVoiceRecording(send: false)
        }
        if next == .image {
            imagePanel.reloadLibrary()
        }
        if next == .emoji {
            stickerPanel?.reload()
            updateEmojiStickerVisibility()
            updateEmojiCollectionInsets()
        }
        if next != .image {
            imagePanel.clearSelection()
        }

        highlightTools()

        let expanding = next != .none
        let targetH: CGFloat = expanding ? expandedAccessoryHeight() : 0
        let updates = {
            self.accessoryHost.isHidden = !expanding
            if expanding {
                // Mutually exclusive bottom pins — never both active.
                self.topStackBottomToSafe.isActive = false
                self.accessoryTopToTools.isActive = true
                self.accessoryHeightConstraint.constant = targetH
                self.accessoryContentConstraints.forEach { $0.isActive = true }
            } else {
                // Tear down panel fill first, then swap pins (avoids a short host + safe-area fight).
                self.accessoryContentConstraints.forEach { $0.isActive = false }
                self.accessoryTopToTools.isActive = false
                self.accessoryHeightConstraint.constant = 0
                self.topStackBottomToSafe.isActive = true
                self.updateCollapsedBottomInset()
            }
            self.layoutIfNeeded()
        }
        if animated {
            UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseInOut], animations: updates)
        } else {
            updates()
        }
        delegate?.composerBar(self, accessoryChanged: next)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func highlightTools() {
        let map: [(UIButton, ChatComposerAccessory)] = [
            (emojiButton, .emoji),
            (voiceButton, .voice),
            (photoButton, .image),
        ]
        for (btn, mode) in map {
            btn.tintColor = (accessory == mode) ? .systemBlue : .secondaryLabel
        }
        mentionButton.tintColor = .secondaryLabel

        // Plus when collapsed; close (xmark) when more panel is open.
        let moreExpanded = accessory == .more
        var moreCfg = moreButton.configuration ?? UIButton.Configuration.plain()
        moreCfg.image = UIImage(systemName: moreExpanded ? "xmark" : "plus")
        moreCfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)
        moreButton.configuration = moreCfg
        moreButton.tintColor = moreExpanded ? .systemBlue : .secondaryLabel
    }

    // MARK: - Actions

    @objc private func emojiTapped() { setAccessory(.emoji, animated: true) }

    @objc private func emojiModeChanged() {
        updateEmojiStickerVisibility()
        if emojiModeControl.selectedSegmentIndex == 1 {
            stickerPanel?.reload()
        }
    }

    private func updateEmojiStickerVisibility() {
        guard accessory == .emoji else { return }
        let showStickers = emojiModeControl.selectedSegmentIndex == 1
        emojiCollection.isHidden = showStickers
        stickerPanel?.isHidden = !showStickers
        updateEmojiSendButtonVisibility()
    }

    private func updateEmojiSendButtonVisibility() {
        let showEmojiTab = accessory == .emoji && emojiModeControl.selectedSegmentIndex == 0
        emojiSendButton.isHidden = !showEmojiTab
        if showEmojiTab {
            updateEmojiSendButtonEnabled()
        }
    }

    private func updateEmojiSendButtonEnabled() {
        let hasText = !(textView.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        emojiSendButton.isEnabled = hasText
        emojiSendButton.alpha = hasText ? 1 : 0.45
    }

    @objc private func emojiSendTapped() {
        sendCurrentText()
        updateEmojiSendButtonEnabled()
    }

    @objc private func voiceTapped() { setAccessory(.voice, animated: true) }
    @objc private func photoTapped() { setAccessory(.image, animated: true) }
    @objc private func moreTapped() { setAccessory(.more, animated: true) }

    @objc private func mentionTapped() {
        setAccessory(.none, animated: true)
        delegate?.composerBarDidTapMention(self)
    }

    private func sendCurrentText() {
        let text = textView.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        textView.text = ""
        placeholder.isHidden = false
        updateTextHeight()
        updateEmojiSendButtonEnabled()
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        delegate?.composerBar(self, didSendText: text)
    }

    @objc private func requestVideo() { delegate?.composerBarDidRequestVideo(self) }
    @objc private func requestFile() { delegate?.composerBarDidRequestFile(self) }

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
                    self.recordHint.text = "按住说话"
                    self.recordHint.textColor = .label
                    self.delegate?.composerBar(self, voiceFailed: "请在设置中允许麦克风权限")
                    return
                }
            }
            // Finger already lifted (or a newer press superseded this one).
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
                self.recordHint.text = "按住说话"
                self.recordHint.textColor = .label
                self.delegate?.composerBar(self, voiceFailed: error.localizedDescription)
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
        recordHint.text = "按住说话"
        recordHint.textColor = .label

        if !send {
            voiceRecorder.cancel()
            voiceDidStartThisPress = false
            return
        }

        guard voiceDidStartThisPress || voiceRecorder.isRecording else {
            // Press ended before recording could start — treat as cancel, not "too short".
            voiceRecorder.cancel()
            return
        }

        guard let (data, duration) = voiceRecorder.stop() else {
            voiceDidStartThisPress = false
            delegate?.composerBar(self, voiceFailed: "录音太短，请按住再试")
            return
        }
        voiceDidStartThisPress = false
        delegate?.composerBar(self, didFinishVoice: data, duration: duration)
        setAccessory(.none, animated: true)
    }

    // MARK: - TextView

    func textViewDidBeginEditing(_ textView: UITextView) {
        if accessory != .none {
            setAccessory(.none, animated: true)
        }
    }

    func textViewDidChange(_ textView: UITextView) {
        placeholder.isHidden = !textView.text.isEmpty
        updateTextHeight()
        updateEmojiSendButtonEnabled()
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        // Keyboard Return / Send key sends the message; no dedicated send button.
        if text == "\n" {
            sendCurrentText()
            return false
        }
        return true
    }

    private func updateTextHeight() {
        let size = textView.sizeThatFits(CGSize(width: textView.bounds.width, height: .greatestFiniteMagnitude))
        let h = min(max(size.height, minTextH), maxTextH)
        textView.isScrollEnabled = size.height > maxTextH
        if abs(textHeightConstraint.constant - h) > 0.5 {
            textHeightConstraint.constant = h
            UIView.animate(withDuration: 0.15) { self.superview?.layoutIfNeeded() }
        }
    }
}

// MARK: - Image accessory

extension ChatComposerBar: ChatImageAccessoryPanelDelegate {
    func imagePanelDidTapCamera(_ panel: ChatImageAccessoryPanel) {
        delegate?.composerBarDidRequestCamera(self)
    }

    func imagePanelDidTapAlbum(_ panel: ChatImageAccessoryPanel) {
        delegate?.composerBarDidRequestAlbum(self)
    }

    func imagePanel(
        _ panel: ChatImageAccessoryPanel,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    ) {
        delegate?.composerBar(self, didConfirmAssets: assets, extraImages: extraImages, sendOriginal: sendOriginal)
    }
}

// MARK: - Emoji collection

extension ChatComposerBar: UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        Self.emojis.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.reuseID, for: indexPath) as! EmojiCell
        cell.label.text = Self.emojis[indexPath.item]
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        textView.text = (textView.text ?? "") + Self.emojis[indexPath.item]
        placeholder.isHidden = true
        updateTextHeight()
        updateEmojiSendButtonEnabled()
        UISelectionFeedbackGenerator().selectionChanged()
    }

    func collectionView(
        _ collectionView: UICollectionView,
        layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        let cols: CGFloat = 8
        let inset: CGFloat = 32
        let spacing: CGFloat = 6 * (cols - 1)
        let w = floor((collectionView.bounds.width - inset - spacing) / cols)
        return CGSize(width: max(w, 32), height: max(w, 32))
    }
}

private final class EmojiCell: UICollectionViewCell {
    static let reuseID = "EmojiCell"
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.font = .systemFont(ofSize: 28)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }
}
