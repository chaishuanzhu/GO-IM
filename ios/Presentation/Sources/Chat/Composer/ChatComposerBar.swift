import UIKit
import Photos
import Domain

/// iOS 26–style floating dock: chrome + tools + pluggable accessory panels.
///
/// Mode UI lives under `Composer/Accessories/`. See `ChatComposerAccessoryPanel`.
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
    /// Vertical fill for the active panel — off while height == 0.
    private var activePanelFillConstraints: [NSLayoutConstraint] = []
    private var panelFillConstraints: [ChatComposerAccessory: [NSLayoutConstraint]] = [:]
    private let minTextH: CGFloat = 36
    private let maxTextH: CGFloat = 110

    private(set) var accessory: ChatComposerAccessory = .none
    private var keyboardHeight: CGFloat = 336

    private var panels: [ChatComposerAccessory: any ChatComposerAccessoryPanel] = [:]
    private var currentPanel: (any ChatComposerAccessoryPanel)?
    private var pendingStickerRepository: StickerRepository?

    override init(frame: CGRect) {
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
        (panels[.emoji] as? EmojiAccessoryPanel)?.updateSendEnabled()
    }

    func configureStickers(_ repository: StickerRepository) {
        pendingStickerRepository = repository
        if let emoji = panels[.emoji] as? EmojiAccessoryPanel {
            emoji.configureStickers(repository)
        }
    }

    func dismissAccessory() {
        setAccessory(.none, animated: true)
    }

    func appendPickedImage(_ image: UIImage) {
        let panel = panel(for: .image) as? ChatImageAccessoryPanel
        panel?.appendExtraImage(image)
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

        emojiButton = makeTool("face.smiling", action: #selector(emojiTapped))
        mentionButton = makeTool("at", action: #selector(mentionTapped))
        voiceButton = makeTool("mic.fill", action: #selector(voiceTapped))
        photoButton = makeTool("photo.on.rectangle", action: #selector(photoTapped))
        moreButton = makeTool("plus", action: #selector(moreTapped))

        toolRow.axis = .horizontal
        toolRow.distribution = .fillEqually
        toolRow.alignment = .center
        [emojiButton, mentionButton, voiceButton, photoButton, moreButton].forEach { toolRow.addArrangedSubview($0!) }

        accessoryHost.translatesAutoresizingMaskIntoConstraints = false
        accessoryHost.clipsToBounds = true
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
            accessoryHost.bottomAnchor.constraint(equalTo: chrome.bottomAnchor),
            accessoryHeightConstraint,

            textHeightConstraint,
            toolRow.heightAnchor.constraint(equalToConstant: 40),

            placeholder.leadingAnchor.constraint(equalTo: textView.leadingAnchor, constant: 14),
            placeholder.topAnchor.constraint(equalTo: textView.topAnchor, constant: 8),
        ])

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

    private func makePanelActions() -> ChatComposerPanelActions {
        ChatComposerPanelActions(
            insertEmoji: { [weak self] emoji in
                guard let self else { return }
                self.textView.text = (self.textView.text ?? "") + emoji
                self.placeholder.isHidden = true
                self.updateTextHeight()
            },
            sendText: { [weak self] in self?.sendCurrentText() },
            textHasContent: { [weak self] in
                !(self?.textView.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            },
            selectSticker: { [weak self] ref in
                guard let self else { return }
                self.delegate?.composerBar(self, didSelectSticker: ref)
            },
            voiceFinished: { [weak self] data, duration in
                guard let self else { return }
                self.delegate?.composerBar(self, didFinishVoice: data, duration: duration)
            },
            voiceFailed: { [weak self] message in
                guard let self else { return }
                self.delegate?.composerBar(self, voiceFailed: message)
            },
            voiceDidSend: { [weak self] in
                self?.setAccessory(.none, animated: true)
            },
            requestCamera: { [weak self] in
                guard let self else { return }
                self.delegate?.composerBarDidRequestCamera(self)
            },
            requestAlbum: { [weak self] in
                guard let self else { return }
                self.delegate?.composerBarDidRequestAlbum(self)
            },
            confirmAssets: { [weak self] assets, extras, original in
                guard let self else { return }
                self.delegate?.composerBar(
                    self,
                    didConfirmAssets: assets,
                    extraImages: extras,
                    sendOriginal: original
                )
            },
            requestVideo: { [weak self] in
                guard let self else { return }
                self.delegate?.composerBarDidRequestVideo(self)
            },
            requestFile: { [weak self] in
                guard let self else { return }
                self.delegate?.composerBarDidRequestFile(self)
            }
        )
    }

    private func panel(for mode: ChatComposerAccessory) -> (any ChatComposerAccessoryPanel)? {
        if mode == .none { return nil }
        if let existing = panels[mode] { return existing }
        guard let created = ChatComposerAccessoryRegistry.make(mode, actions: makePanelActions()) else {
            return nil
        }
        created.translatesAutoresizingMaskIntoConstraints = false
        created.isHidden = true
        accessoryHost.addSubview(created)
        NSLayoutConstraint.activate([
            created.leadingAnchor.constraint(equalTo: accessoryHost.leadingAnchor),
            created.trailingAnchor.constraint(equalTo: accessoryHost.trailingAnchor),
        ])
        let top = created.topAnchor.constraint(equalTo: accessoryHost.topAnchor)
        let bottom = created.bottomAnchor.constraint(equalTo: accessoryHost.bottomAnchor)
        top.isActive = false
        bottom.isActive = false
        panelFillConstraints[mode] = [top, bottom]
        panels[mode] = created

        if mode == .emoji, let repo = pendingStickerRepository {
            (created as? EmojiAccessoryPanel)?.configureStickers(repo)
        }
        return created
    }

    // MARK: - Keyboard / safe area

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

    private func expandedAccessoryHeight() -> CGFloat {
        keyboardHeight + resolvedBottomSafeInset()
    }

    /// Matches `ChatImageAccessoryPanel` vertical chrome: tip + spacings + bottom bar + home indicator.
    private func estimateImageStripHeight(hostHeight: CGFloat) -> CGFloat {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let tipVisible = status == .limited || status == .denied || status == .restricted
        let tipH: CGFloat = tipVisible ? 32 : 0
        let tipSpacing: CGFloat = tipVisible ? 8 : 10
        let bottomGap: CGFloat = 10
        let bottomBar: CGFloat = 48
        let safe = resolvedBottomSafeInset()
        return max(hostHeight - tipH - tipSpacing - bottomGap - bottomBar - safe, 96)
    }

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

    private func updateCollapsedBottomInset() {
        guard accessory == .none else { return }
        let inset = resolvedBottomSafeInset()
        let pad: CGFloat = inset > 0 ? max(inset - 6, 8) : 8
        topStackBottomToSafe.constant = -pad
    }

    private func refreshPanelInsets() {
        let inset = resolvedBottomSafeInset()
        currentPanel?.applyBottomSafeInset(inset)
        (panels[.emoji] as? EmojiAccessoryPanel)?.applyBottomSafeInset(inset)
    }

    public override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        updateCollapsedBottomInset()
        refreshPanelInsets()
        if accessory != .none {
            accessoryHeightConstraint.constant = expandedAccessoryHeight()
        }
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        updateCollapsedBottomInset()
        refreshPanelInsets()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        updateCollapsedBottomInset()
        refreshPanelInsets()
    }

    // MARK: - Accessory switching

    private func setAccessory(_ mode: ChatComposerAccessory, animated: Bool) {
        let next = (accessory == mode) ? ChatComposerAccessory.none : mode
        let previous = accessory
        accessory = next

        if next != .none {
            textView.resignFirstResponder()
        }

        if previous != .none, previous != next {
            let old = panels[previous]
            old?.prepareForHide()
            old?.isHidden = true
        }

        highlightTools()

        let expanding = next != .none
        let targetH: CGFloat = expanding ? expandedAccessoryHeight() : 0

        if expanding {
            let panel = panel(for: next)
            currentPanel = panel
            panels.values.forEach { $0.isHidden = true }
            panel?.isHidden = false

            // 1) Unhide host with height 0; keep panel L/R only (no top/bottom yet).
            //    Activating vertical fill at height 0 fights voice/more safe-area content.
            accessoryHost.isHidden = false
            topStackBottomToSafe.isActive = false
            accessoryTopToTools.isActive = true
            activePanelFillConstraints.forEach { $0.isActive = false }
            activePanelFillConstraints = []
            accessoryHeightConstraint.constant = 0
            layoutIfNeeded()
            refreshPanelInsets()

            if next == .image, let imagePanel = panel as? ChatImageAccessoryPanel {
                // Lock thumbs to final strip height before expanding — no post-animation jump.
                imagePanel.prepareForDisplay(
                    expectedStripHeight: estimateImageStripHeight(hostHeight: targetH)
                )
            } else {
                panel?.prepareForDisplay()
                panel?.setNeedsLayout()
                panel?.layoutIfNeeded()
            }

            // 2) Expand height and pin vertical fill together.
            let updates = {
                if let fills = self.panelFillConstraints[next] {
                    fills.forEach { $0.isActive = true }
                    self.activePanelFillConstraints = fills
                }
                self.accessoryHeightConstraint.constant = targetH
                self.layoutIfNeeded()
            }
            let finish = {
                self.refreshPanelInsets()
                if next == .image {
                    (panel as? ChatImageAccessoryPanel)?.finalizeStripLayout()
                } else if next == .emoji {
                    panel?.setNeedsLayout()
                    panel?.layoutIfNeeded()
                }
            }
            if animated {
                UIView.animate(
                    withDuration: 0.28,
                    delay: 0,
                    options: [.curveEaseInOut],
                    animations: updates,
                    completion: { _ in finish() }
                )
            } else {
                updates()
                finish()
            }
        } else {
            currentPanel?.prepareForHide()
            currentPanel?.isHidden = true
            currentPanel = nil

            let updates = {
                self.activePanelFillConstraints.forEach { $0.isActive = false }
                self.activePanelFillConstraints = []
                self.accessoryTopToTools.isActive = false
                self.accessoryHeightConstraint.constant = 0
                self.topStackBottomToSafe.isActive = true
                self.accessoryHost.isHidden = true
                self.updateCollapsedBottomInset()
                self.layoutIfNeeded()
            }
            if animated {
                UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseInOut], animations: updates)
            } else {
                updates()
            }
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
        (panels[.emoji] as? EmojiAccessoryPanel)?.updateSendEnabled()
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        delegate?.composerBar(self, didSendText: text)
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
        (panels[.emoji] as? EmojiAccessoryPanel)?.updateSendEnabled()
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
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
