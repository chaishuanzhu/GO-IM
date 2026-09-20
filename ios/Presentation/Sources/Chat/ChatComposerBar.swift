import UIKit
import AVFoundation
import Photos

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
    private let minTextH: CGFloat = 36
    private let maxTextH: CGFloat = 110

    private(set) var accessory: ChatComposerAccessory = .none
    private var keyboardHeight: CGFloat = 336

    private let voiceRecorder = VoiceRecorder()
    private var recordSeconds = 0
    private var recordTimer: Timer?

    // Accessory subviews
    private let emojiCollection: UICollectionView
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
        topStack.spacing = 8
        topStack.addArrangedSubview(inputRow)
        topStack.addArrangedSubview(toolRow)
        topStack.translatesAutoresizingMaskIntoConstraints = false
        chrome.addSubview(topStack)
        chrome.addSubview(accessoryHost)

        textHeightConstraint = textView.heightAnchor.constraint(equalToConstant: minTextH)
        accessoryHeightConstraint = accessoryHost.heightAnchor.constraint(equalToConstant: 0)
        topStackBottomToSafe = topStack.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor)
        accessoryTopToTools = accessoryHost.topAnchor.constraint(equalTo: topStack.bottomAnchor, constant: 8)
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

            topStack.topAnchor.constraint(equalTo: chrome.topAnchor, constant: 10),
            topStack.leadingAnchor.constraint(equalTo: chrome.leadingAnchor),
            topStack.trailingAnchor.constraint(equalTo: chrome.trailingAnchor),
            topStackBottomToSafe,

            accessoryHost.leadingAnchor.constraint(equalTo: chrome.leadingAnchor),
            accessoryHost.trailingAnchor.constraint(equalTo: chrome.trailingAnchor),
            // Bleed into home-indicator area so safe-area strip matches panel color.
            accessoryHost.bottomAnchor.constraint(equalTo: chrome.bottomAnchor),
            accessoryHeightConstraint,

            textHeightConstraint,
            toolRow.heightAnchor.constraint(equalToConstant: 44),

            placeholder.leadingAnchor.constraint(equalTo: textView.leadingAnchor, constant: 14),
            placeholder.topAnchor.constraint(equalTo: textView.topAnchor, constant: 8),
        ])

        setupAccessoryPanels()
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
        emojiCollection.backgroundColor = .clear
        emojiCollection.dataSource = self
        emojiCollection.delegate = self
        emojiCollection.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.reuseID)
        emojiCollection.translatesAutoresizingMaskIntoConstraints = false
        accessoryHost.addSubview(emojiCollection)

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
            return c
        }()
        hold.addTarget(self, action: #selector(voiceHoldDown), for: .touchDown)
        hold.addTarget(self, action: #selector(voiceHoldUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        hold.translatesAutoresizingMaskIntoConstraints = false
        hold.accessibilityLabel = "按住录音"
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

        NSLayoutConstraint.activate([
            emojiCollection.topAnchor.constraint(equalTo: accessoryHost.topAnchor),
            emojiCollection.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            emojiCollection.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            // Keep controls above home indicator; host background still bleeds below.
            emojiCollection.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),

            voicePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor),
            voicePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            voicePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            voicePanel.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
            recordTime.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            recordTime.topAnchor.constraint(equalTo: voicePanel.topAnchor, constant: 28),
            hold.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            hold.centerYAnchor.constraint(equalTo: voicePanel.centerYAnchor, constant: 8),
            recordHint.centerXAnchor.constraint(equalTo: voicePanel.centerXAnchor),
            recordHint.topAnchor.constraint(equalTo: hold.bottomAnchor, constant: 8),

            imagePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor),
            imagePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            imagePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            imagePanel.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),

            morePanel.topAnchor.constraint(equalTo: accessoryHost.topAnchor),
            morePanel.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            morePanel.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
            morePanel.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
            moreStack.centerXAnchor.constraint(equalTo: morePanel.centerXAnchor),
            moreStack.centerYAnchor.constraint(equalTo: morePanel.centerYAnchor),
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
        let inset = safeAreaInsets.bottom > 0
            ? safeAreaInsets.bottom
            : (window?.safeAreaInsets.bottom ?? superview?.safeAreaInsets.bottom ?? 0)
        return keyboardHeight + inset
    }

    // MARK: - Accessory switching

    private func setAccessory(_ mode: ChatComposerAccessory, animated: Bool) {
        let next = (accessory == mode) ? ChatComposerAccessory.none : mode
        accessory = next

        if next != .none {
            textView.resignFirstResponder()
        }

        emojiCollection.isHidden = next != .emoji
        voicePanel.isHidden = next != .voice
        imagePanel.isHidden = next != .image
        morePanel.isHidden = next != .more
        if next == .image {
            imagePanel.reloadLibrary()
        }
        if next != .image {
            imagePanel.clearSelection()
        }

        highlightTools()

        let expanding = next != .none
        let targetH: CGFloat = expanding ? expandedAccessoryHeight() : 0
        let updates = {
            self.accessoryHost.isHidden = !expanding
            self.topStackBottomToSafe.isActive = !expanding
            self.accessoryTopToTools.isActive = expanding
            self.accessoryHeightConstraint.constant = targetH
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
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        delegate?.composerBar(self, didSendText: text)
    }

    @objc private func requestVideo() { delegate?.composerBarDidRequestVideo(self) }
    @objc private func requestFile() { delegate?.composerBarDidRequestFile(self) }

    @objc private func voiceHoldDown() {
        Task {
            let ok = await VoiceRecorder.requestPermission()
            guard ok else {
                delegate?.composerBar(self, voiceFailed: "请在设置中允许麦克风权限")
                return
            }
            do {
                try voiceRecorder.start()
                recordSeconds = 0
                recordTime.text = "0:00"
                recordHint.text = "松开发送"
                recordHint.textColor = .systemRed
                recordTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.recordSeconds += 1
                        self.recordTime.text = String(format: "%d:%02d", self.recordSeconds / 60, self.recordSeconds % 60)
                    }
                }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            } catch {
                delegate?.composerBar(self, voiceFailed: error.localizedDescription)
            }
        }
    }

    @objc private func voiceHoldUp() {
        recordTimer?.invalidate()
        recordTimer = nil
        recordHint.text = "按住说话"
        recordHint.textColor = .label
        guard let (data, duration) = voiceRecorder.stop() else {
            delegate?.composerBar(self, voiceFailed: "录音太短，请按住再试")
            return
        }
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
